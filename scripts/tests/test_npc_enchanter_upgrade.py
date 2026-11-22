import csv
import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "modules" / "mod-npc-enchanter"


class NpcEnchanterUpgradeTest(unittest.TestCase):
    def test_pin_and_npc_schema_match_upstream_fix(self):
        manifest = json.loads((ROOT / "modules" / "manifest.json").read_text())
        module = next(item for item in manifest["modules"] if item["name"] == "mod-npc-enchanter")
        self.assertEqual(module["commit"], "af32add66eafc0e0eb8775999e76ceed75f18b74")
        sql = (MODULE / "data" / "sql" / "db-world" / "npc_enchanter.sql").read_text(encoding="utf-8")
        insert = re.search(
            r"INSERT INTO creature_template \((.*?)\) VALUES\s*\((.*?)\);", sql, re.S
        )
        self.assertIsNotNone(insert)
        columns = re.findall(r"`([^`]+)`", insert[1])
        values = next(csv.reader([insert[2]], skipinitialspace=True))
        self.assertEqual(len(values), len(columns))
        self.assertNotIn("scale", columns)
        self.assertEqual(values[columns.index("ScriptName")], "@Script")
        self.assertIn('"npc_enchantment"', sql)
        self.assertIn("`DisplayScale`", sql)
        self.assertIn("(@Entry, 0, @Model, 1, 1, 0)", sql)
        self.assertIn("(@Entry, 1, 11343, 0, 0, 18019)", sql)
        self.assertIn("Beauregard Boneglitter", sql)
        self.assertNotRegex(sql, r"(?i)(?:DROP|TRUNCATE)\s+TABLE")

    def test_disabled_module_does_not_announce(self):
        source = (MODULE / "src" / "npc_enchanter.cpp").read_text()
        self.assertIn("if (EnchanterEnableModule && EnchanterAnnounceModule)", source)


if __name__ == "__main__":
    unittest.main()
