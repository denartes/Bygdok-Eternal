import csv
import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "modules" / "mod-instance-reset"


class InstanceResetUpgradeTest(unittest.TestCase):
    def test_pin_and_npc_schema_match_upstream_fix(self):
        manifest = json.loads((ROOT / "modules" / "manifest.json").read_text())
        module = next(item for item in manifest["modules"] if item["name"] == "mod-instance-reset")
        self.assertEqual(module["commit"], "1e2c20770f40f5382348b7649291371f47c86812")
        sql_files = list((MODULE / "data" / "sql").rglob("*.sql"))
        self.assertEqual(len(sql_files), 1)
        sql = sql_files[0].read_text(encoding="utf-8")
        insert = re.search(
            r"INSERT INTO `creature_template` \((.*?)\) VALUES\s*\((.*?)\);", sql, re.S
        )
        self.assertIsNotNone(insert)
        columns = re.findall(r"`([^`]+)`", insert[1])
        values = next(csv.reader([insert[2]], quotechar='"', skipinitialspace=True))
        self.assertEqual(len(values), len(columns))
        self.assertNotIn("scale", columns)
        self.assertEqual(values[columns.index("ScriptName")], "instance_reset")
        self.assertIn("DisplayScale", sql)
        self.assertIn("(@Entry, '0', @Model, '1', '1', NULL)", sql)
        self.assertEqual(
            set(re.findall(r"\(@entry, '([a-z]{4})',", sql, re.I)),
            {"esES", "esMX", "frFR", "koKR", "deDE", "zhCN", "zhTW", "ruRU"},
        )
        self.assertNotRegex(sql, r"(?i)(?:DROP|TRUNCATE)\s+TABLE")
        self.assertNotIn("`instance`", sql)


if __name__ == "__main__":
    unittest.main()
