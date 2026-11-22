import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SQL_ROOT = ROOT / "modules" / "mod-llm-chatter" / "data" / "sql" / "characters"


class LlmChatterSchemaTest(unittest.TestCase):
    def test_screenshot_schema_uses_bundled_charset_with_case_sensitive_tokens(self):
        definitions = []
        for relative in (
            "base/00000000_llm_chatter_tables.sql",
            "updates/20261003_screenshot_proximity.sql",
        ):
            with self.subTest(sql=relative):
                sql = (SQL_ROOT / relative).read_text(encoding="utf-8")
                match = re.search(
                    r"CREATE TABLE IF NOT EXISTS `llm_screenshot_proximity` \(.*?\) ENGINE=.*?;",
                    sql,
                    re.DOTALL,
                )
                self.assertIsNotNone(match)
                definition = match.group()
                self.assertIn(
                    "`request_token` CHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL",
                    definition,
                )
                self.assertIn("UNIQUE KEY `uq_request_token` (`request_token`)", definition)
                definitions.append(definition)
        self.assertEqual(definitions[0], definitions[1])


if __name__ == "__main__":
    unittest.main()
