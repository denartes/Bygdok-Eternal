import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "generate_forever_changes_inventory",
    ROOT / "scripts" / "generate-forever-changes-inventory.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class ForeverChangesInventoryTest(unittest.TestCase):
    def test_export_produces_complete_class_spell_and_talent_inventory(self):
        export = json.loads((ROOT / "foreverchanges_export.json").read_text(encoding="utf-8"))
        rows = GENERATOR.inventory_rows(export)

        self.assertGreater(len(rows), 700)
        self.assertEqual({row["Class"] for row in rows}, {name.title() for name in GENERATOR.EXPECTED_CLASSES})
        self.assertEqual({row["Feature type"] for row in rows}, {"Talent", "Spell"})
        self.assertTrue(all(row["Feature"] and row["Forever behaviour"] for row in rows))
        self.assertTrue(all(row["WotLK behaviour"].startswith("Not compared") for row in rows))
        self.assertTrue(all(row["Status"] == "Inventory captured; WotLK comparison not started" for row in rows))

    def test_export_rows_include_non_talent_spell_change_labels(self):
        export = json.loads((ROOT / "foreverchanges_export.json").read_text(encoding="utf-8"))
        rows = GENERATOR.inventory_rows(export)
        change_types = {row["Forever change type"] for row in rows}

        self.assertTrue({"New", "Changed", "Moved", "Removed", "Earlier", "Was a talent"} <= change_types)


if __name__ == "__main__":
    unittest.main()
