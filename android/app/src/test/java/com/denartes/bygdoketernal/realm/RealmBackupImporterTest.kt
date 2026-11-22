package com.denartes.bygdoketernal.realm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.GZIPOutputStream

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

    @Test
    fun `extracts a gzip compressed tar export`() {
        val archive = File.createTempFile("bygdok-backup", ".tar.gz")
        val destination = tempDirectory("realm-import")
        try {
            GZIPOutputStream(archive.outputStream()).use { gzip ->
                for ((name, text) in validEntries()) {
                    writeTarEntry(
                        gzip,
                        "./${name.removePrefix("bygdok-export/")}",
                        text.toByteArray(Charsets.UTF_8)
                    )
                }
                gzip.write(ByteArray(1024))
            }

            val prepared = RealmBackupImporter.extractAndValidate(archive, destination)

            assertEquals(4, prepared.databases.size)
            assertTrue(prepared.databases.containsKey("acore_characters"))
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
        put("bygdok-export/config/modules/playerbots.conf", "Playerbots.Enabled = 1\n")
        put("bygdok-export/module-data/mod-llm-chatter/rag.json", "[]\n")
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

    private fun writeTarEntry(output: java.io.OutputStream, name: String, bytes: ByteArray) {
        val header = ByteArray(512)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0, 0, minOf(name.length, 100))
        "0000644".toByteArray().copyInto(header, 100)
        "0000000".toByteArray().copyInto(header, 108)
        "0000000".toByteArray().copyInto(header, 116)
        val size = "%011o".format(bytes.size).toByteArray()
        size.copyInto(header, 124)
        header[124 + size.size] = 0
        "00000000000".toByteArray().copyInto(header, 136)
        header[156] = '0'.code.toByte()
        "ustar\u0000".toByteArray().copyInto(header, 257)
        "00".toByteArray().copyInto(header, 263)
        "root".toByteArray().copyInto(header, 265)
        "root".toByteArray().copyInto(header, 297)
        header.fill(' '.code.toByte(), 148, 156)
        val checksum = header.sumOf { it.toInt() and 0xFF }
        "%06o".format(checksum).toByteArray().copyInto(header, 148)
        header[154] = 0
        header[155] = ' '.code.toByte()
        output.write(header)
        output.write(bytes)
        output.write(ByteArray((512 - (bytes.size % 512)) % 512))
    }
}