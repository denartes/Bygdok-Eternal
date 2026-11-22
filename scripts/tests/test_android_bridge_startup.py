import importlib.util
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
WRAPPER = ROOT / "android" / "app" / "src" / "main" / "python" / "bygdok_llm_chatter.py"


class AndroidBridgeStartupTest(unittest.TestCase):
    def test_run_does_not_mutate_native_environment(self):
        spec = importlib.util.spec_from_file_location("android_bridge_test", WRAPPER)
        wrapper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(wrapper)
        bridge = types.ModuleType("llm_chatter_bridge")
        calls = []
        bridge.main = lambda **kwargs: calls.append(kwargs)
        environment = dict(os.environ)
        with tempfile.TemporaryDirectory() as directory:
            runtime = str(Path(directory) / "runtime")
            with (
                patch.dict(sys.modules, {"llm_chatter_bridge": bridge}),
                patch.object(wrapper.logging, "basicConfig") as configure_logging,
                patch.object(tempfile, "tempdir", tempfile.tempdir),
                patch.object(sys, "argv", sys.argv[:]),
                patch("os.putenv", side_effect=AssertionError("Native environment mutation")),
                patch("os.unsetenv", side_effect=AssertionError("Native environment mutation")),
            ):
                wrapper.run("bridge.conf", runtime)
                self.assertEqual(dict(os.environ), environment)
                self.assertEqual(tempfile.gettempdir(), runtime)
                with tempfile.NamedTemporaryFile() as temporary:
                    self.assertEqual(Path(temporary.name).parent, Path(runtime))
                self.assertEqual(sys.argv, ["llm_chatter_bridge.py", "--config", "bridge.conf"])
                self.assertEqual(calls, [{"stop_event": wrapper._stop_event}])
                self.assertEqual(
                    configure_logging.call_args.kwargs["filename"],
                    str(Path(runtime) / "bridge.log"),
                )
                wrapper.request_stop()
                self.assertTrue(wrapper._stop_event.is_set())
                wrapper.reset()
                self.assertFalse(wrapper._stop_event.is_set())


if __name__ == "__main__":
    unittest.main()
