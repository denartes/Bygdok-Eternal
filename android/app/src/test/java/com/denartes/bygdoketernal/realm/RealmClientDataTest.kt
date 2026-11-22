package com.denartes.bygdoketernal.realm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RealmClientDataTest {
    @get:Rule val temporary = TemporaryFolder()
    private val vmap = "VMAP_4.8".toByteArray()
    private val mmap = byteArrayOf(0x50, 0x41, 0x4d, 0x4d, 7, 0, 0, 0, 20, 0, 0, 0)

    private fun archive(
        tree: ByteArray = vmap,
        tile: ByteArray = mmap,
        extra: Map<String, ByteArray> = emptyMap()
    ): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val entries = mapOf(
                "dbc/Map.dbc" to byteArrayOf(1),
                "maps/0002035.map" to byteArrayOf(1),
                "vmaps/000.vmtree" to tree,
                "mmaps/0002239.mmtile" to tile
            ) + extra
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test fun `replaces old data only after validating the archive`() {
        val dir = temporary.newFolder("client-data")
        val old = File(dir, "old").apply { writeText("old") }
        val realm = File(temporary.root, "database").apply { writeText("keep") }
        RealmClientData.install(dir, archive())
        assertTrue(RealmClientData.isPresent(dir))
        assertFalse(old.exists())
        assertEquals("keep", realm.readText())
        assertFalse(File(temporary.root, "client-data.previous").exists())
        assertFalse(File(temporary.root, "client-data.download").exists())
    }

    @Test fun `incompatible and incomplete archives preserve the old data`() {
        val dir = temporary.newFolder("client-data")
        val old = File(dir, "old").apply { writeText("keep") }
        for (input in listOf(archive(tree = "VMAP_4.7".toByteArray()), archive(tile = ByteArray(12)), ByteArrayInputStream(ByteArray(0)))) {
            assertThrows(IllegalStateException::class.java) { RealmClientData.install(dir, input) }
            assertEquals("keep", old.readText())
            assertFalse(File(temporary.root, "client-data.download").exists())
        }
    }

    @Test fun `old vmap and old movement maps are rejected`() {
        val dir = temporary.newFolder("client-data")
        RealmClientData.install(dir, archive())
        File(dir, "vmaps/000.vmtree").writeText("VMAP_4.7")
        assertFalse(RealmClientData.isPresent(dir))
        File(dir, "vmaps/000.vmtree").writeBytes(vmap)
        File(dir, "mmaps/0002239.mmtile").writeBytes(mmap.clone().apply { this[8] = 16 })
        assertFalse(RealmClientData.isPresent(dir))
    }

    @Test fun `rejects zip traversal without touching old data`() {
        val dir = temporary.newFolder("client-data")
        val old = File(dir, "old").apply { writeText("keep") }
        assertThrows(IllegalArgumentException::class.java) {
            RealmClientData.install(dir, archive(extra = mapOf("../escape" to byteArrayOf(1))))
        }
        assertFalse(File(temporary.root, "escape").exists())
        assertEquals("keep", old.readText())
    }

    @Test fun `checksum covers the complete archive including its central directory`() {
        val dir = temporary.newFolder("client-data")
        val old = File(dir, "old").apply { writeText("keep") }
        val bytes = archive().readBytes()
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertThrows(IllegalStateException::class.java) {
            RealmClientData.install(dir, ByteArrayInputStream(bytes.copyOf(bytes.size - 1)), checksum)
        }
        assertEquals("keep", old.readText())
        RealmClientData.install(dir, ByteArrayInputStream(bytes), checksum)
        assertTrue(RealmClientData.isPresent(dir))
    }

    @Test fun `restores interrupted replacement before retrying`() {
        val dir = temporary.newFolder("client-data")
        val backup = temporary.newFolder("client-data.previous")
        File(backup, "old").writeText("keep")
        assertThrows(IllegalStateException::class.java) {
            RealmClientData.install(dir, archive(tree = "VMAP_4.7".toByteArray()))
        }
        assertEquals("keep", File(dir, "old").readText())
        assertFalse(backup.exists())
    }

    @Test fun `keeps backup and reports ambiguous interrupted replacement`() {
        val dir = temporary.newFolder("client-data")
        File(dir, "unexpected").writeText("keep")
        val backup = temporary.newFolder("client-data.previous")
        File(backup, "old").writeText("keep")
        assertThrows(IllegalStateException::class.java) { RealmClientData.install(dir, archive()) }
        assertEquals("keep", File(dir, "unexpected").readText())
        assertEquals("keep", File(backup, "old").readText())
    }
}
