import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "compare_forever_changes_wotlk",
    ROOT / "scripts" / "compare-forever-changes-wotlk.py",
)
COMPARER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPARER)


class CompareForeverChangesWotlkTest(unittest.TestCase):
    def test_reads_pinned_core_spell_rank_sql(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            path = directory / "spell_ranks.sql"
            updates = directory / "updates"
            updates.mkdir()
            path.write_text(
                "INSERT INTO `spell_ranks` VALUES\n"
                "(133,133,1),\n"
                "(133,143,2),\n"
                "(133,145,3);\n",
                encoding="utf-8",
            )
            ranks = COMPARER.load_spell_ranks(path, updates)
        self.assertEqual(ranks[133], [(133, 133, 1), (133, 143, 2), (133, 145, 3)])

    def test_applies_rank_changes_from_world_sql_updates(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            updates = directory / "updates"
            updates.mkdir()
            base = directory / "spell_ranks.sql"
            base.write_text(
                "INSERT INTO `spell_ranks` VALUES (59887,59887,1), "
                "(59887,59888,2);",
                encoding="utf-8",
            )
            (updates / "2026_01_01_00.sql").write_text(
                "DELETE FROM `spell_ranks` WHERE `spell_id` IN (59887, 59888);\n"
                "INSERT INTO `spell_ranks` (`first_spell_id`, `spell_id`, `rank`) VALUES\n"
                "(59887,59887,1), (59887,59888,2), (59887,59889,3);",
                encoding="utf-8",
            )
            ranks = COMPARER.load_spell_ranks(base, updates)
        self.assertEqual(
            ranks[59887],
            [(59887, 59887, 1), (59887, 59888, 2), (59887, 59889, 3)],
        )

    def test_inventory_features_match_tracked_wotlk_talent_and_spell_names(self):
        spells_by_name, talents_by_name = COMPARER.load_wotlk_data()
        spell_ranks = {
            133: [(133, 133, 1), (133, 143, 2), (133, 145, 3)],
        }

        talent_row = {
            "Class": "Warrior",
            "Feature": "Improved Heroic Strike",
            "Feature type": "Talent",
            "Talent tree or spell group": "Arms",
            "Forever change type": "Changed",
            "Forever behaviour": "Reduces the cost by 1.",
        }
        spell_row = {
            "Class": "Mage",
            "Feature": "Fireball",
            "Feature type": "Spell",
            "Talent tree or spell group": "Fire",
            "Forever change type": "Changed",
            "Forever behaviour": "Changed numbers.",
        }
        COMPARER.compare_row(
            talent_row, spells_by_name, talents_by_name, spell_ranks
        )
        COMPARER.compare_row(
            spell_row, spells_by_name, talents_by_name, spell_ranks
        )

        self.assertIn("Arms", talent_row["WotLK behaviour"])
        self.assertIn("3 ranks [12282, 12663, 12664]", talent_row["WotLK behaviour"])
        self.assertIn("AzerothCore rank chains", spell_row["WotLK behaviour"])
        self.assertIn("rank 1=133, rank 2=143, rank 3=145", spell_row["WotLK behaviour"])
        self.assertIn("Name mapping does not identify", spell_row["WotLK behaviour"])
        self.assertIn("WotLK counterpart exists", spell_row["Required change"])

    def test_identifies_missing_exact_new_talent_as_candidate_not_fact(self):
        spells_by_name, talents_by_name = COMPARER.load_wotlk_data()
        row = {
            "Class": "Warrior",
            "Feature": "Spearing Strike",
            "Feature type": "Talent",
            "Talent tree or spell group": "Arms",
            "Forever change type": "New",
            "Forever behaviour": "A new attack.",
        }
        COMPARER.compare_row(
            row, spells_by_name, talents_by_name, {}
        )
        self.assertIn("No exact class talent", row["WotLK behaviour"])
        self.assertIn("Candidate missing WotLK feature", row["Required change"])

    def test_resolves_replaced_talent_name_against_wotlk(self):
        spells_by_name, talents_by_name = COMPARER.load_wotlk_data()
        row = {
            "Class": "Warrior",
            "Feature": "Improved Tactical Mastery",
            "Feature type": "Talent",
            "Talent tree or spell group": "Arms",
            "Forever change type": "Changed",
            "Forever behaviour": "Replaces Tactical Mastery. 3 ranks.",
        }
        COMPARER.compare_row(
            row, spells_by_name, talents_by_name, {}
        )
        self.assertIn("Tactical Mastery", row["WotLK behaviour"])
        self.assertIn("WotLK Arms talent", row["WotLK behaviour"])
        self.assertIn("references an existing WotLK name", row["Required change"])

    def test_legacy_name_spell_match_has_single_sentence_terminator(self):
        spells_by_name, talents_by_name = COMPARER.load_wotlk_data()
        row = {
            "Class": "Paladin",
            "Feature": "Improved Seals",
            "Feature type": "Talent",
            "Talent tree or spell group": "Holy",
            "Forever change type": "Changed",
            "Forever behaviour": "Renamed from Improved Seal of Righteousness.",
        }
        COMPARER.compare_row(row, spells_by_name, talents_by_name, {})
        self.assertIn("spell record(s)", row["WotLK behaviour"])
        self.assertNotIn("..", row["WotLK behaviour"])

    def test_marks_talent_to_trained_spell_transition(self):
        spells_by_name, talents_by_name = COMPARER.load_wotlk_data()
        row = {
            "Class": "Warrior",
            "Feature": "Tactical Mastery",
            "Feature type": "Spell",
            "Talent tree or spell group": "Arms",
            "Forever change type": "Was a talent",
            "Forever behaviour": "An Arms talent in Classic; trained without one now.",
        }
        COMPARER.compare_row(
            row, spells_by_name, talents_by_name, {}
        )
        self.assertIn("same-name WotLK talent record", row["WotLK behaviour"])
        self.assertIn("WotLK counterpart is a talent", row["Required change"])

    def test_rejects_rank_sql_without_rows(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "empty.sql"
            path.write_text("LOCK TABLES `spell_ranks` WRITE;", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "No spell_ranks rows"):
                COMPARER.load_spell_ranks(path, Path(temporary))


if __name__ == "__main__":
    unittest.main()
