import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "modules" / "mod-aoe-loot"


class AoeLootUpgradeTest(unittest.TestCase):
    def test_pin_and_sql_use_current_module_string_schema(self):
        manifest = json.loads((ROOT / "modules" / "manifest.json").read_text())
        module = next(item for item in manifest["modules"] if item["name"] == "mod-aoe-loot")
        self.assertEqual(module["commit"], "957f9a1a686141d96c8b55e9431bfe2a0fb26e46")
        sql_files = list((MODULE / "data" / "sql").rglob("*.sql"))
        self.assertEqual([path.name for path in sql_files], ["aoe_loot_module_string.sql"])
        sql = sql_files[0].read_text(encoding="utf-8")
        self.assertNotIn("`acore_string`", sql)
        self.assertNotIn("`locale_koKR`", sql)
        self.assertIn("INSERT INTO `module_string` (`module`, `id`, `string`)", sql)
        self.assertIn(
            "INSERT INTO `module_string_locale` (`module`, `id`, `locale`, `string`)", sql
        )
        defaults, locales = sql.split("-- localizations")
        ids = set(re.findall(r"\(@MODULE_STRING, (\d+),", defaults))
        self.assertEqual(ids, {str(value) for value in range(1, 9)})
        translations = re.findall(r"\(@MODULE_STRING, (\d+), '([^']+)',", locales)
        self.assertEqual(len(translations), 64)
        self.assertEqual(len(set(translations)), 64)
        for language in ("koKR", "frFR", "deDE", "zhCN", "zhTW", "esES", "esMX", "ruRU"):
            self.assertEqual({id for id, locale in translations if locale == language}, ids)

    def test_current_packet_api_and_safe_mail_default(self):
        header = (MODULE / "src" / "aoe_loot.h").read_text()
        source = (MODULE / "src" / "aoe_loot.cpp").read_text()
        self.assertIn(
            "CanPacketReceive(WorldSession* session, WorldPacket const& packet) override",
            header,
        )
        self.assertIn("WorldPacket packetCopy(packet)", source)
        self.assertIn('GetOption<bool>("AOELoot.Enable", true)', source)
        self.assertIn('GetOption<bool>("AOELoot.MailEnable", false)', source)
        config = (MODULE / "conf" / "mod_aoe_loot.conf.dist").read_text()
        self.assertIn("AOELoot.MailEnable = 0", config)
        self.assertIn("AOELoot.Enable = 1", config)


if __name__ == "__main__":
    unittest.main()
