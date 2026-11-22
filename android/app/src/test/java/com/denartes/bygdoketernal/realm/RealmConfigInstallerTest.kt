package com.denartes.bygdoketernal.realm

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealmConfigInstallerTest {
    @Test
    fun `SQL replacement removes moved migrations and retired modules without touching realm data`() {
        val root = Files.createTempDirectory("realm-sql-install").toFile()
        try {
            val sql = File(root, "sql")
            val stale = File(sql, "data/sql/updates/db_auth/2024_01_20_00.sql")
            requireNotNull(stale.parentFile).mkdirs()
            stale.writeText("old migration")
            val retired = File(sql, "modules/mod-ollama-chat/old.sql")
            requireNotNull(retired.parentFile).mkdirs()
            retired.writeText("retired module")
            val data = File(root, "data/characters.ibd")
            requireNotNull(data.parentFile).mkdirs()
            data.writeText("persistent character data")

            repeat(2) {
                replaceBundledRealmSql(sql) { destination ->
                    val archived = File(destination, "data/sql/archive/db_auth/2024_01_20_00.sql")
                    requireNotNull(archived.parentFile).mkdirs()
                    archived.writeText("archived migration")
                }
                assertFalse(stale.exists())
                assertFalse(retired.exists())
                assertEquals(1, sql.walkTopDown().count { it.name == "2024_01_20_00.sql" })
                assertEquals("persistent character data", data.readText())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `SQL copy failure propagates and a later attempt replaces partial files`() {
        val root = Files.createTempDirectory("realm-sql-retry").toFile()
        try {
            val sql = File(root, "sql")
            val failure = IllegalStateException("asset copy failed")
            try {
                replaceBundledRealmSql(sql) { destination ->
                    File(destination, "partial.sql").writeText("partial")
                    throw failure
                }
                throw AssertionError("Expected asset copy failure")
            } catch (actual: IllegalStateException) {
                assertTrue(actual === failure)
            }
            replaceBundledRealmSql(sql) { destination ->
                File(destination, "complete.sql").writeText("complete")
            }
            assertFalse(File(sql, "partial.sql").exists())
            assertEquals("complete", File(sql, "complete.sql").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
