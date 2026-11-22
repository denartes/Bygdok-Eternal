import contextlib
import importlib.util
import io
import json
import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("stage_server_modules", ROOT / "scripts" / "stage-server-modules.py")
STAGER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(STAGER)

KEPT_MODULES = {
    "mod-account-achievements", "mod-aoe-loot", "mod-ah-bot-plus", "mod-duel-reset",
    "mod-emblem-transfer", "mod-guildhouse", "mod-instance-reset", "mod-npc-enchanter",
    "mod-reagent-bank", "mod-playerbots", "mod-player-bot-level-brackets",
    "mod-individual-progression", "mod-guild-mate", "mod-transmog-plus", "mod-llm-chatter",
}


class StageServerModulesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.destination = self.root / "core" / "modules"
        self.module = self.source / "mod-example" / "src"
        self.module.mkdir(parents=True)
        (self.module / "example.cpp").write_text("local source\n", encoding="utf-8")
        self.write_manifest([{"name": "mod-example"}])

    def write_manifest(self, modules):
        (self.source / "manifest.json").write_text(json.dumps({"modules": modules}), encoding="utf-8")

    def stage(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return STAGER.stage_modules(self.source, self.destination)

    def test_stages_local_edits_without_network_or_processes(self):
        with patch.object(socket, "socket", side_effect=AssertionError("Network not allowed")), \
             patch("subprocess.Popen", side_effect=AssertionError("External process not allowed")):
            self.assertEqual(self.stage(), ["mod-example"])
            (self.module / "example.cpp").write_text("edited source\n", encoding="utf-8")
            self.stage()
        self.assertEqual(
            (self.destination / "mod-example" / "src" / "example.cpp").read_text(),
            "edited source\n",
        )

    def test_removes_stale_modules_and_deleted_source_files(self):
        self.stage()
        stale = self.destination / "mod-transmog"
        stale.mkdir()
        (stale / "old.cpp").write_text("stale")
        scaffold = self.destination / "CMakeLists.txt"
        scaffold.write_text("core scaffold")
        (self.module / "example.cpp").unlink()
        self.stage()
        self.assertFalse(stale.exists())
        self.assertFalse((self.destination / "mod-example" / "src" / "example.cpp").exists())
        self.assertEqual(scaffold.read_text(), "core scaffold")

    def test_preserves_auction_house_build_alias(self):
        self.module.parent.rename(self.source / "mod-ah-bot-plus")
        self.write_manifest([{"name": "mod-ah-bot-plus", "build_name": "mod-ah-bot"}])
        self.assertEqual(self.stage(), ["mod-ah-bot"])
        self.assertTrue((self.destination / "mod-ah-bot" / "src" / "example.cpp").is_file())
        self.assertFalse((self.destination / "mod-ah-bot-plus").exists())

    def test_missing_source_fails_before_deleting_existing_build(self):
        self.stage()
        self.write_manifest([{"name": "mod-example"}, {"name": "mod-missing"}])
        with self.assertRaisesRegex(ValueError, "Missing local module source"):
            self.stage()
        self.assertTrue((self.destination / "mod-example" / "src" / "example.cpp").is_file())

    def test_rejects_empty_selection(self):
        self.write_manifest([])
        with self.assertRaisesRegex(ValueError, "non-empty"):
            self.stage()

    def test_rejects_unlisted_sources(self):
        (self.source / "mod-unlisted").mkdir()
        with self.assertRaisesRegex(ValueError, "missing from manifest"):
            self.stage()

    def test_rejects_duplicate_source_or_destination(self):
        for modules in (
            [{"name": "mod-example"}, {"name": "mod-example", "build_name": "mod-other"}],
            [{"name": "mod-example"}, {"name": "mod-other", "build_name": "mod-example"}],
        ):
            with self.subTest(modules=modules):
                self.write_manifest(modules)
                with self.assertRaisesRegex(ValueError, "Duplicate"):
                    self.stage()

    def test_rejects_unsafe_names(self):
        for entry in ({"name": "../escape"}, {"name": "mod-example", "build_name": "../escape"}):
            with self.subTest(entry=entry):
                self.write_manifest([entry])
                with self.assertRaisesRegex(ValueError, "Invalid module name"):
                    self.stage()

    def test_rejects_overlapping_source_and_destination(self):
        for destination in (self.source, self.source.parent, self.source / "core" / "modules"):
            with self.subTest(destination=destination):
                with self.assertRaisesRegex(ValueError, "must not overlap"):
                    STAGER.stage_modules(self.source, destination)

    def test_rejects_non_modules_destination(self):
        with self.assertRaisesRegex(ValueError, "modules directory"):
            STAGER.stage_modules(self.source, self.root / "core")

    def test_repository_has_exactly_the_requested_modules_and_stages_offline(self):
        source = ROOT / "modules"
        manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual({module["name"] for module in manifest["modules"]}, KEPT_MODULES)
        self.assertEqual(len(manifest["modules"]), 15)
        self.assertEqual({path.name for path in source.iterdir() if path.is_dir()}, KEPT_MODULES)
        with patch.object(socket, "socket", side_effect=AssertionError("Network not allowed")), \
             patch("subprocess.Popen", side_effect=AssertionError("External process not allowed")), \
             contextlib.redirect_stdout(io.StringIO()):
            staged = STAGER.stage_modules(source, self.destination)
        self.assertEqual(set(staged), KEPT_MODULES - {"mod-ah-bot-plus"} | {"mod-ah-bot"})
        for module in manifest["modules"]:
            name = module["name"]
            target = self.destination / module.get("build_name", name)
            self.assertFalse((target / ".git").exists())
            expected_files = {
                path.relative_to(source / name) for path in (source / name).rglob("*") if path.is_file()
            }
            actual_files = {path.relative_to(target) for path in target.rglob("*") if path.is_file()}
            self.assertEqual(actual_files, expected_files, name)
            for relative in expected_files:
                self.assertEqual((target / relative).read_bytes(), (source / name / relative).read_bytes())


if __name__ == "__main__":
    unittest.main()
