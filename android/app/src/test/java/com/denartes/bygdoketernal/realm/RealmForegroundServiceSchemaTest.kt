package com.denartes.bygdoketernal.realm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RealmForegroundServiceSchemaTest {
    @Test
    fun `module base sql supports data and legacy module layouts`() {
        val modulesDir = File.createTempFile("module-sql", "")
        modulesDir.delete()
        modulesDir.mkdirs()
        try {
            val worldSql = File(modulesDir, "mod-individual-progression/sql/world/base/quest_xp_table.sql")
            val playerbotsSql = File(modulesDir, "mod-playerbots/data/sql/playerbots/base/playerbots.sql")
            val transmogSql = File(modulesDir, "mod-transmog-plus/sql/characters/mod_transmog_plus_characters.sql")
            worldSql.parentFile?.mkdirs()
            playerbotsSql.parentFile?.mkdirs()
            transmogSql.parentFile?.mkdirs()
            worldSql.writeText("CREATE TABLE custom_quest_xp (entry INT);\n")
            playerbotsSql.writeText("CREATE TABLE playerbots (id INT);\n")
            transmogSql.writeText("CREATE TABLE mod_transmog_plus_appearances (id INT);\n")

            val batches = realmModuleDatabaseBootstrapFiles(modulesDir)

            assertEquals(3, batches.size)
            assertTrue(batches.any { it.moduleName == "mod-individual-progression" && it.database == "acore_world" })
            assertTrue(batches.any { it.moduleName == "mod-playerbots" && it.database == "acore_playerbots" })
            assertTrue(batches.any { it.moduleName == "mod-transmog-plus" && it.database == "acore_characters" })
        } finally {
            modulesDir.deleteRecursively()
        }
    }

    @Test
    fun `base schema import only runs when neither marker nor core schema exists`() {
        assertTrue(RealmForegroundService.shouldImportDatabaseSchema(markerExists = false, coreSchemaExists = false))
        assertFalse(RealmForegroundService.shouldImportDatabaseSchema(markerExists = true, coreSchemaExists = false))
        assertFalse(RealmForegroundService.shouldImportDatabaseSchema(markerExists = false, coreSchemaExists = true))
    }

    @Test
    fun `module schema marker is trusted only when required table exists`() {
        assertTrue(RealmForegroundService.shouldSkipModuleDatabaseBootstrap(markerExists = true, requiredTableExists = true))
        assertFalse(RealmForegroundService.shouldSkipModuleDatabaseBootstrap(markerExists = true, requiredTableExists = false))
        assertFalse(RealmForegroundService.shouldSkipModuleDatabaseBootstrap(markerExists = false, requiredTableExists = true))
    }

    @Test
    fun `transmog table validation only targets characters database`() {
        assertTrue(RealmForegroundService.requiresTransmogSchemaTableCheck("mod-transmog-plus", "acore_characters"))
        assertFalse(RealmForegroundService.requiresTransmogSchemaTableCheck("mod-transmog-plus", "acore_world"))
        assertFalse(RealmForegroundService.requiresTransmogSchemaTableCheck("mod-individual-progression", "acore_world"))
    }

    @Test
    fun `database bootstrap mapping keeps runtime database names aligned`() {
        val baseDir = File.createTempFile("sql-base", "")
        baseDir.delete()
        baseDir.mkdirs()
        try {
            val dbAuth = File(baseDir, "db_auth")
            val dbCharacters = File(baseDir, "db_characters")
            val dbWorld = File(baseDir, "db_world")
            dbAuth.mkdirs()
            dbCharacters.mkdirs()
            dbWorld.mkdirs()

            val authSql = File(dbAuth, "001_auth.sql")
            val characterSql = File(dbCharacters, "001_characters.sql")
            val worldSql = File(dbWorld, "001_world.sql")
            authSql.writeText("CREATE TABLE IF NOT EXISTS account;\n")
            characterSql.writeText("CREATE TABLE IF NOT EXISTS characters;\n")
            worldSql.writeText("CREATE TABLE IF NOT EXISTS creature;\n")

            val mapping = mapOf(
                "db_auth" to "acore_auth",
                "db_characters" to "acore_characters",
                "db_world" to "acore_world"
            )
            val runtimeDatabases = mutableListOf<String>()
            baseDir.listFiles()?.forEach { databaseDir ->
                if (!databaseDir.isDirectory) return@forEach
                if (databaseDir.listFiles { file -> file.isFile && file.extension == "sql" }?.isNotEmpty() == true) {
                    runtimeDatabases.add(mapping[databaseDir.name] ?: databaseDir.name)
                }
            }

            assertEquals(listOf("acore_auth", "acore_characters", "acore_world"), runtimeDatabases.sorted())
            assertTrue(File(dbAuth, "001_auth.sql").readText().contains("account"))
        } finally {
            baseDir.deleteRecursively()
        }
    }
}
