package com.denartes.bygdoketernal.realm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RealmBackupImporterTest {
    @Test
    fun `extracts the legacy bygdok export layout with all required databases`() {
        val archive = createArchive(validEntries())
        val destination = tempDirectory("realm-import")
        try {
            val prepared = RealmBackupImporter.extractAndValidate(archive, destination)

            assertEquals(4, prepared.databases.size)
            assertTrue(prepared.databases.containsKey("acore_playerbots"))
            assertEquals(1, prepared.moduleConfigs.size)
            assertEquals(1, prepared.moduleData.size)
        } finally {
            archive.delete()
            destination.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an archive missing a required database`() {
        val entries = validEntries().filterKeys { !it.endsWith("acore_playerbots.sql") }
        val archive = createArchive(entries)
        try {
            RealmBackupImporter.extractAndValidate(archive, tempDirectory("realm-import"))
        } finally {
            archive.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects SQL that switches to a different database`() {
        val entries = validEntries().toMutableMap()
        entries["bygdok-export/databases/acore_auth.sql"] = dump("acore_world")
        val archive = createArchive(entries)
        try {
            RealmBackupImporter.extractAndValidate(archive, tempDirectory("realm-import"))
        } finally {
            archive.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects archive path traversal`() {
        val entries = validEntries().toMutableMap()
        entries["bygdok-export/../../outside.txt"] = "unsafe"
        val archive = createArchive(entries)
        try {
            RealmBackupImporter.extractAndValidate(archive, tempDirectory("realm-import"))
        } finally {
            archive.delete()
        }
    }

    private fun validEntries(): Map<String, String> = buildMap {
        for (database in RealmBackupImporter.requiredDatabases) {
            put("bygdok-export/databases/$database.sql", dump(database))
        }
        put("bygdok-export/config/modules/playerbots.conf", "AiPlayerbot.Enabled = 1\n")
        put("bygdok-export/module-data/mod-ollama-chat/rag.json", "[]\n")
    }

    private fun dump(database: String): String = """
        USE `$database`;
        CREATE TABLE `sample` (`id` int NOT NULL);
        INSERT INTO `sample` VALUES (1);
    """.trimIndent()

    private fun createArchive(entries: Map<String, String>): File {
        val archive = File.createTempFile("bygdok-backup", ".zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun tempDirectory(prefix: String): File =
        File.createTempFile(prefix, "").apply { delete(); mkdirs() }
}