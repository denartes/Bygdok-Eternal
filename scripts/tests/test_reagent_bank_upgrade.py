import csv
import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "modules" / "mod-reagent-bank"


class ReagentBankUpgradeTest(unittest.TestCase):
    def test_upstream_pin_and_npc_schema(self):
        manifest = json.loads((ROOT / "modules" / "manifest.json").read_text())
        module = next(item for item in manifest["modules"] if item["name"] == "mod-reagent-bank")
        self.assertEqual(module["commit"], "62c265338ceba10fb754394413e6c4c61cedf9de")
        sql = (MODULE / "data" / "sql" / "db-world" / "base" / "reagent_bank_NPC.sql").read_text()
        match = re.search(
            r"INSERT INTO `creature_template` \((.*?)\) VALUES\s*\((.*?)\);", sql, re.S
        )
        self.assertIsNotNone(match)
        columns = re.findall(r"`([^`]+)`", match[1])
        values = next(csv.reader([match[2]], quotechar="'", skipinitialspace=True))
        self.assertEqual(len(values), len(columns))
        self.assertNotIn("mechanic_immune_mask", columns)
        self.assertEqual(values[columns.index("ScriptName")], "npc_reagent_banker")
        self.assertIn("(@Entry, 0, 15965, 1.0, 1.0, NULL)", sql)
        self.assertNotIn("custom_reagent_bank", sql)

    def test_local_disable_guards_remain(self):
        source = (MODULE / "src" / "ReagentBank.cpp").read_text()
        self.assertEqual(source.count('sConfigMgr->GetOption<bool>("ReagentBank.Enable", true)'), 4)
        config = (MODULE / "conf" / "reagent_bank.conf.dist").read_text()
        self.assertIn("ReagentBank.Enable = 1", config)


if __name__ == "__main__":
    unittest.main()
