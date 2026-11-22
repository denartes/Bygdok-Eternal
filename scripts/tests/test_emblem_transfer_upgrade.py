import csv
import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "modules" / "mod-emblem-transfer"


class EmblemTransferUpgradeTest(unittest.TestCase):
    def test_pin_and_creature_schema_match_upstream_fix(self):
        manifest = json.loads((ROOT / "modules" / "manifest.json").read_text())
        module = next(item for item in manifest["modules"] if item["name"] == "mod-emblem-transfer")
        self.assertEqual(module["commit"], "feb2007a50c77680bd6d15aaaf45e8a185dad7cf")
        sql = (MODULE / "data" / "sql" / "db-world" / "base" /
               "base_npc_emblem_transfer.sql").read_text(encoding="utf-8")
        insert = re.search(
            r"INSERT INTO `creature_template` \((.*?)\) VALUES\s*\((.*?)\);", sql, re.S
        )
        self.assertIsNotNone(insert)
        columns = re.findall(r"`([^`]+)`", insert[1])
        values = next(csv.reader([insert[2]], quotechar='"', skipinitialspace=True))
        self.assertEqual(len(values), len(columns))
        self.assertNotIn("scale", columns)
        self.assertEqual(values[columns.index("ScriptName")], "npc_emblem_transfer")
        self.assertIn("`DisplayScale`", sql)
        self.assertIn("(@Entry, 0, @Model, 1, 1, 0)", sql)
        self.assertIn("'esES'", sql)
        self.assertIn("'esMX'", sql)

    def test_local_enable_guards_and_saved_transfers_are_retained(self):
        source = (MODULE / "src" / "npc_emblem_transfer.cpp").read_text()
        for function in ("OnGossipHello", "OnGossipSelect", "OnGossipSelectCode"):
            with self.subTest(function=function):
                self.assertRegex(
                    source,
                    rf"\b{function}\([^;{{}}]*\)\s*\{{\s*"
                    r"(?:player->PlayerTalkClass->ClearMenus\(\);\s*)?"
                    r'if \(!sConfigMgr->GetOption<bool>\("EmblemTransfer.Enable", true\)\)',
                )
        config = (MODULE / "conf" / "emblem_transfer.conf.dist").read_text()
        self.assertIn("EmblemTransfer.Enable = 1", config)
        sql = "\n".join(path.read_text(encoding="utf-8")
                        for path in (MODULE / "data" / "sql" / "db-world").rglob("*.sql"))
        self.assertNotRegex(sql, r"(?i)(?:DROP|TRUNCATE)\s+TABLE")
        self.assertNotIn("emblem_transferences", sql)


if __name__ == "__main__":
    unittest.main()
