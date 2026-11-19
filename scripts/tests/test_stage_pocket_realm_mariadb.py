import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "stage_pocket_realm_mariadb", ROOT / "tools" / "stage-pocket-realm-mariadb.py"
)
STAGER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(STAGER)


def windows_error(code):
    error = PermissionError("File in use")
    error.winerror = code
    return error


class StagePocketRealmMariaDbTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.output = self.root / "runtime"
        self.apk = self.root / "provider.apk"
        self.bootstrap = b"SELECT 1;\n"
        binaries = {
            "mariadbd": b"server",
            "mariadb": b"client",
            "libdependency.so": b"library",
        }
        entries = {
            name: {"apk_name": name, "size": len(data), "sha256": STAGER.digest_bytes(data)}
            for name, data in binaries.items()
        }
        manifest = {
            "provider": STAGER.PROVIDER_ID,
            "abi": "arm64-v8a",
            "executables": {name: entries[name] for name in ("mariadbd", "mariadb")},
            "links": [entries["libdependency.so"]],
            "bootstrap_sha256": STAGER.digest_bytes(self.bootstrap),
        }
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr(STAGER.PROVIDER_PREFIX + "runtime-manifest.json", json.dumps(manifest))
            archive.writestr(STAGER.PROVIDER_PREFIX + "bootstrap.sql", self.bootstrap)
            for name, data in binaries.items():
                archive.writestr(STAGER.NATIVE_PREFIX + name, data)
        self.addCleanup(patch.stopall)
        patch.object(STAGER, "OUTPUT", self.output).start()
        patch.object(STAGER, "download", return_value=self.apk).start()
        self.sleep = patch.object(STAGER.time, "sleep").start()

    def stage(self):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return STAGER.main()

    def test_repeat_staging_replaces_existing_provider_and_stale_staging(self):
        self.assertEqual(self.stage(), 0)
        (self.output / "obsolete").write_text("stale")
        staging = self.output.with_name("runtime.staging")
        staging.mkdir()
        (staging / "partial").write_text("interrupted previous build")
        self.assertEqual(self.stage(), 0)
        self.assertFalse((self.output / "obsolete").exists())
        self.assertFalse(staging.exists())
        self.assertEqual((self.output / "bin" / "mariadbd").read_bytes(), b"server")
        self.assertEqual((self.output / "share" / "bootstrap.sql").read_bytes(), self.bootstrap)
        self.assertEqual((self.output / ".build-complete").read_text(), "complete\n")
        self.assertEqual(len(list((self.output / "lib").iterdir())), 1)

    def test_transient_windows_delete_failure_is_retried_before_publish(self):
        self.stage()
        remove = STAGER.shutil.rmtree
        calls = 0

        def locked_once(path):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise windows_error(32)
            return remove(path)

        with patch.object(STAGER.shutil, "rmtree", side_effect=locked_once):
            self.assertEqual(self.stage(), 0)
        self.assertEqual(calls, 2)
        self.sleep.assert_called_once_with(0.2)

    def test_transient_windows_publish_failure_is_retried(self):
        replace = Path.replace
        calls = 0

        def locked_once(path, target):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise windows_error(5)
            return replace(path, target)

        with patch.object(Path, "replace", autospec=True, side_effect=locked_once):
            self.assertEqual(self.stage(), 0)
        self.assertEqual(calls, 2)
        self.assertTrue((self.output / ".build-complete").is_file())
        self.sleep.assert_called_once_with(0.2)

    def test_permanent_cleanup_failure_is_reported_without_publishing(self):
        self.stage()
        (self.output / "old-marker").write_text("keep")
        with patch.object(STAGER.shutil, "rmtree", side_effect=windows_error(5)) as remove, \
             patch.object(Path, "replace") as replace:
            with self.assertRaisesRegex(RuntimeError, "Close programs using the runtime"):
                self.stage()
        self.assertEqual(remove.call_count, 6)
        replace.assert_not_called()
        self.assertTrue((self.output / "old-marker").is_file())
        self.assertTrue((self.output.with_name("runtime.staging") / ".build-complete").is_file())

    def test_only_windows_lock_related_errors_are_retried(self):
        for error in (PermissionError("Unix permission error"), OSError("Disk failure")):
            with self.subTest(error=error):
                operation = Mock(side_effect=error)
                with self.assertRaises(OSError):
                    STAGER.retry_windows_file_operation(operation, "Test operation")
                operation.assert_called_once()
        self.sleep.assert_not_called()

    def test_supported_windows_errors_retry_with_a_bounded_delay(self):
        for code in (5, 32, 33, 145):
            with self.subTest(code=code):
                operation = Mock(side_effect=[windows_error(code), None])
                with contextlib.redirect_stderr(io.StringIO()):
                    STAGER.retry_windows_file_operation(operation, "Test operation")
                self.assertEqual(operation.call_count, 2)


if __name__ == "__main__":
    unittest.main()
