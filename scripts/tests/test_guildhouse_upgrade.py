import csv
import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "modules" / "mod-guildhouse"
SQL = MODULE / "data" / "sql" / "db-world"


class GuildHouseUpgradeTest(unittest.TestCase):
    def test_manifest_tracks_updated_upstream(self):
        manifest = json.loads((ROOT / "modules" / "manifest.json").read_text())
        module = next(item for item in manifest["modules"] if item["name"] == "mod-guildhouse")
        self.assertEqual(module["commit"], "0b3cc14e947a859f8a7751a10676107d6316275b")

    def test_creature_insert_uses_current_columns_and_matching_values(self):
        sql = (SQL / "2024_04_07_00_creatures_objects.sql").read_text()
        insert = re.search(r"INSERT INTO `creature_template` \((.*?)\) VALUES\s*(.*?);", sql, re.S)
        self.assertIsNotNone(insert)
        columns = re.findall(r"`([^`]+)`", insert[1])
        obsolete = {
            "scale", "trainer_type", "trainer_spell", "trainer_class", "trainer_race",
            "mechanic_immune_mask", "spell_school_immune_mask",
        }
        self.assertFalse(obsolete.intersection(columns))
        rows = re.findall(r"\((.*?)\)", insert[2], re.S)
        self.assertEqual(len(rows), 3)
        for row in rows:
            with self.subTest(row=row):
                values = next(csv.reader([row], quotechar="'", skipinitialspace=True))
                self.assertEqual(len(values), len(columns))
        self.assertIn("`DisplayScale`", sql)
        innkeeper = (SQL / "2024_04_07_02_innkeeper.sql").read_text()
        self.assertIn("WHERE `id`=500032", innkeeper)
        self.assertNotIn("`id1`", innkeeper)

    def test_local_disable_guards_are_preserved(self):
        targets = {
            "mod_guildhouse.cpp": (
                "OnCreate", "OnDisband", "UpdateAI", "OnGossipHello", "OnGossipSelect",
                "OnPlayerUpdateZone", "OnPlayerBeforeTeleport", "CheckPlayer",
                "HandleSpawnButlerCommand", "HandleGuildHouseTeleCommand",
                "OnBeforeWorldObjectSetPhaseMask",
            ),
            "mod_guildhouse_butler.cpp": ("UpdateAI", "OnGossipHello", "OnGossipSelect"),
        }
        for file, functions in targets.items():
            source = (MODULE / "src" / file).read_text()
            for function in functions:
                with self.subTest(file=file, function=function):
                    self.assertRegex(
                        source,
                        rf"\b{function}\([^;{{}}]*\)[^;{{}}]*\{{\s*"
                        r'if \(!sConfigMgr->GetOption<bool>\("GuildHouse.Enable", true\)\)',
                    )
        config = (MODULE / "conf" / "mod_guildhouse.conf.dist").read_text()
        self.assertIn("GuildHouse.Enable = 1", config)

    def test_every_localized_menu_id_has_english_text(self):
        header = (MODULE / "src" / "guildhouse.h").read_text()
        ids = set(re.findall(r"GUILDHOUSE_\w+\s*=\s*(\d+)", header))
        sql = "\n".join(path.read_text(encoding="utf-8") for path in SQL.glob("*locale.sql"))
        english_ids = set(re.findall(r"\((\d+), 'enUS',", sql))
        self.assertTrue(ids)
        self.assertFalse(ids - english_ids, f"Missing English strings: {ids - english_ids}")


if __name__ == "__main__":
    unittest.main()
