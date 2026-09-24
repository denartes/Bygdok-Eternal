#!/usr/bin/env python3
"""Stage the verified Pocket Realm ARM64 MariaDB provider for local Bygdok builds."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "runtime" / "build" / "mariadb-arm64"
CACHE = Path(os.environ.get("LOCALAPPDATA", tempfile.gettempdir())) / "Bygdok" / "cache"
APK_NAME = "Pocket-Realm-0.103.0-alpha-arm64-v8a.apk"
APK_URL = (
    "https://github.com/puzzled-pancake/pocket-realm/releases/download/"
    "v0.103.0-alpha/Pocket-Realm-0.103.0-alpha-arm64-v8a.apk"
)
APK_SHA256 = "09d5fde0d9ba9a58331ce6ecca1b03bf7b02e586304058063d6253e9542b7144"
PROVIDER_ID = "mariadb-12.3.2-termux-bionic-arm64"
PROVIDER_PREFIX = "assets/database/provider/"
NATIVE_PREFIX = "lib/arm64-v8a/"


def digest_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def digest_file(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def download() -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / APK_NAME
    if target.is_file() and digest_file(target) == APK_SHA256:
        return target
    target.unlink(missing_ok=True)
    partial = target.with_suffix(target.suffix + ".part")
    partial.unlink(missing_ok=True)
    request = urllib.request.Request(APK_URL, headers={"User-Agent": "Bygdok local provider staging"})
    print(f"Downloading {APK_URL}")
    with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as output:
        shutil.copyfileobj(response, output, length=1024 * 1024)
    if digest_file(partial) != APK_SHA256:
        partial.unlink(missing_ok=True)
        raise RuntimeError("Pocket Realm APK checksum mismatch")
    partial.replace(target)
    return target


def safe_member(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        raise RuntimeError(f"unsafe APK member: {name}")
    return path


def write_verified(archive: zipfile.ZipFile, member: str, target: Path, expected: dict) -> None:
    data = archive.read(member)
    if len(data) != expected["size"] or digest_bytes(data) != expected["sha256"]:
        raise RuntimeError(f"provider artifact mismatch: {member}")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    target.chmod(0o755)


def main() -> int:
    apk = download()
    with zipfile.ZipFile(apk) as archive:
        manifest_member = PROVIDER_PREFIX + "runtime-manifest.json"
        manifest_bytes = archive.read(manifest_member)
        manifest = json.loads(manifest_bytes)
        if manifest.get("provider") != PROVIDER_ID or manifest.get("abi") != "arm64-v8a":
            raise RuntimeError("unexpected Pocket Realm MariaDB provider identity")

        staging = OUTPUT.with_name(OUTPUT.name + ".staging")
        shutil.rmtree(staging, ignore_errors=True)
        (staging / "bin").mkdir(parents=True)
        (staging / "lib").mkdir()
        (staging / "share").mkdir()

        executables = manifest["executables"]
        write_verified(
            archive,
            NATIVE_PREFIX + executables["mariadbd"]["apk_name"],
            staging / "bin" / "mariadbd",
            executables["mariadbd"],
        )
        write_verified(
            archive,
            NATIVE_PREFIX + executables["mariadb"]["apk_name"],
            staging / "bin" / "mariadb",
            executables["mariadb"],
        )

        for link in manifest["links"]:
            write_verified(
                archive,
                NATIVE_PREFIX + link["apk_name"],
                staging / "lib" / link["apk_name"],
                link,
            )

        for info in archive.infolist():
            if not info.filename.startswith(PROVIDER_PREFIX) or info.is_dir():
                continue
            relative = safe_member(info.filename.removeprefix(PROVIDER_PREFIX))
            if relative == PurePosixPath("runtime-manifest.json"):
                continue
            target = staging / "share" / Path(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(info))

        bootstrap = staging / "share" / "bootstrap.sql"
        if not bootstrap.is_file() or digest_file(bootstrap) != manifest["bootstrap_sha256"]:
            raise RuntimeError("Pocket Realm MariaDB bootstrap mismatch")
        (staging / "share" / "runtime-manifest.json").write_bytes(manifest_bytes)
        provenance = {
            "schema": 1,
            "source": "Pocket Realm release APK",
            "source_url": APK_URL,
            "source_sha256": APK_SHA256,
            "source_tag": "v0.103.0-alpha",
            "source_commit": "ec2783f175f71e5df239b0bc48bb93c09639518e",
            "provider": PROVIDER_ID,
            "provider_manifest_sha256": digest_bytes(manifest_bytes),
        }
        (staging / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
        (staging / ".build-complete").write_text("complete\n", encoding="ascii")

        shutil.rmtree(OUTPUT, ignore_errors=True)
        staging.replace(OUTPUT)

    print(f"Staged {PROVIDER_ID} at {OUTPUT}")
    print(f"  executables: {len(list((OUTPUT / 'bin').iterdir()))}")
    print(f"  private libraries: {len(list((OUTPUT / 'lib').iterdir()))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
