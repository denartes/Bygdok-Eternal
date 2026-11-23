import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
TESTS = ROOT / "modules" / "mod-single-button-assistant" / "tests"


class SingleButtonAssistantTest(unittest.TestCase):
    def test_request_and_spell_selection(self):
        compiler = os.environ.get("CXX", "g++")
        if not shutil.which(compiler):
            self.skipTest("C++ compiler not available")

        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "assistant_test"
            subprocess.run(
                [
                    compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror",
                    "-I", str(TESTS / "stubs"), str(TESTS / "assistant_test.cpp"),
                    "-o", str(executable),
                ],
                check=True,
            )
            subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    unittest.main()
