#!/usr/bin/env python3
"""Stage the selected local module sources into an AzerothCore build tree."""

import argparse
import json
import re
import shutil
from pathlib import Path


def stage_modules(source: Path, destination: Path) -> list[str]:
    source = source.resolve()
    destination = destination.resolve()
    if source == destination or source in destination.parents or destination in source.parents:
        raise ValueError("Module source and build destination must not overlap")
    if destination.name != "modules":
        raise ValueError("Build destination must be an AzerothCore modules directory")

    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    modules = manifest["modules"]
    if not isinstance(modules, list) or not modules:
        raise ValueError("Module manifest must contain a non-empty modules list")

    names = set()
    build_names = set()
    selected = []
    for module in modules:
        name = module["name"]
        build_name = module.get("build_name", name)
        if not all(isinstance(value, str) and re.fullmatch(r"mod-[a-z0-9-]+", value)
                   for value in (name, build_name)):
            raise ValueError(f"Invalid module name: {name!r} / {build_name!r}")
        if name in names or build_name in build_names:
            raise ValueError(f"Duplicate module source or build name: {name}")
        module_source = source / name
        if module_source.is_symlink() or not (module_source / "src").is_dir():
            raise ValueError(f"Missing local module source: {module_source}")
        names.add(name)
        build_names.add(build_name)
        selected.append((module_source, build_name))

    unexpected = {
        path.name for path in source.iterdir()
        if path.is_dir() and path.name.startswith("mod-") and path.name not in names
    }
    if unexpected:
        raise ValueError(f"Local modules missing from manifest: {', '.join(sorted(unexpected))}")

    # Validate all inputs before replacing generated module copies, including stale cached mods.
    destination.mkdir(parents=True, exist_ok=True)
    for existing in destination.iterdir():
        if existing.name.startswith("mod-"):
            if existing.is_symlink():
                existing.unlink()
            elif existing.is_dir():
                shutil.rmtree(existing)
    for module_source, build_name in selected:
        shutil.copytree(module_source, destination / build_name)
        print(f"[modules] Staged {module_source.name} as {build_name}")
    return [build_name for _, build_name in selected]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path, help="Generated AzerothCore modules directory")
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1] / "modules")
    args = parser.parse_args()
    try:
        stage_modules(args.source, args.destination)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"[modules] Staging failed: {error}\n")


if __name__ == "__main__":
    main()
