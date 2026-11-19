package com.denartes.bygdoketernal.realm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RealmOllamaDriverStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store() = RealmOllamaDriverStore(File(temporary.root, "drivers"), 30)

    private fun elf(machine: Int = 183): ByteArray = ByteArray(64).apply {
        this[0] = 0x7f
        this[1] = 'E'.code.toByte()
        this[2] = 'L'.code.toByte()
        this[3] = 'F'.code.toByte()
        this[4] = 2
        this[5] = 1
        this[6] = 1
        this[16] = 3
        this[18] = machine.toByte()
    }

    private fun metadata(library: String = "libvulkan_freedreno.so", api: Int = 28, schema: Int = 1) =
        JSONObject().put("schemaVersion", schema).put("name", "Test Turnip")
            .put("libraryName", library).put("minApi", api).toString().toByteArray()

    private fun archive(
        metadata: ByteArray = metadata(),
        library: ByteArray = elf(),
        extra: Map<String, ByteArray> = emptyMap()
    ): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val entries = linkedMapOf("meta.json" to metadata, "libvulkan_freedreno.so" to library) + extra
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test fun `imports flat AdrenoTools zip into private storage and remembers selection`() {
        val driver = store().install(archive(extra = mapOf("LICENSE" to "licence".toByteArray())))
        assertEquals("Test Turnip", driver.name)
        assertArrayEquals(elf(), driver.library.readBytes())
        assertEquals(driver, store().selected())
        assertTrue(driver.library.canonicalPath.startsWith(temporary.root.canonicalPath + File.separator))
        val hooks = File(temporary.root, "native")
        val environment = driver.environment(hooks)
        assertEquals(driver.library.parentFile!!.absolutePath, environment["BYGDOK_VULKAN_DRIVER_DIR"])
        assertEquals("libvulkan_freedreno.so", environment["BYGDOK_VULKAN_DRIVER_NAME"])
        assertEquals(hooks.absolutePath, environment["BYGDOK_VULKAN_HOOK_DIR"])
    }

    @Test fun `replacement removes old library only after successful validation`() {
        val first = store().install(archive())
        val second = store().install(archive())
        assertNotEquals(first.library, second.library)
        assertFalse(first.library.exists())
        assertEquals(second, store().selected())
    }

    @Test fun `failed replacement preserves previous selection and cleans staging`() {
        val first = store().install(archive())
        assertThrows(IllegalArgumentException::class.java) {
            store().install(archive(metadata = "{broken".toByteArray()))
        }
        assertEquals(first, store().selected())
        assertEquals(2, File(temporary.root, "drivers").listFiles()!!.size)
    }

    @Test fun `removing custom driver restores system selection without touching unrelated data`() {
        val unrelated = File(temporary.root, "realm-data").apply { writeText("keep") }
        val selected = store().install(archive())
        store().remove()
        assertNull(store().selected())
        assertFalse(selected.library.exists())
        assertEquals("keep", unrelated.readText())
    }

    @Test fun `rejects zip traversal and unsupported entries`() {
        for (name in listOf("../escape.so", "/absolute.so", "folder/libdriver.so", "..\\escape.so", "payload.exe")) {
            assertThrows(IllegalArgumentException::class.java) {
                store().install(archive(extra = mapOf(name to elf())))
            }
            assertNull(store().selected())
        }
        assertFalse(File(temporary.root, "escape.so").exists())
    }

    @Test fun `rejects unsupported architecture and truncated libraries`() {
        for (binary in listOf(elf(62), ByteArray(12), ByteArray(64), elf().apply { this[4] = 1 })) {
            assertThrows(IllegalArgumentException::class.java) { store().install(archive(library = binary)) }
        }
    }

    @Test fun `rejects unsafe metadata names and missing library`() {
        for (name in listOf("../libvulkan.so", "/libvulkan.so", "libmissing.so")) {
            assertThrows(IllegalArgumentException::class.java) { store().install(archive(metadata = metadata(name))) }
        }
    }

    @Test fun `rejects too new Android requirements and unknown metadata schema`() {
        assertThrows(IllegalArgumentException::class.java) { store().install(archive(metadata = metadata(api = 35))) }
        assertThrows(IllegalArgumentException::class.java) { store().install(archive(metadata = metadata(schema = 2))) }
    }

    @Test fun `rejects oversized metadata by actual expanded bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            store().install(archive(metadata = ByteArray(65537) { ' '.code.toByte() }))
        }
    }

    @Test fun `rejects oversized libraries by actual expanded bytes`() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("libvulkan_freedreno.so"))
            val chunk = ByteArray(1024 * 1024)
            repeat(65) { zip.write(chunk) }
            zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().install(ByteArrayInputStream(bytes.toByteArray()))
        }
        assertNull(store().selected())
    }

    @Test fun `rejects excessive entries`() {
        val extra = (1..31).associate { "NOTICE$it" to byteArrayOf(1) }
        assertThrows(IllegalArgumentException::class.java) { store().install(archive(extra = extra)) }
    }

    @Test fun `rejects tampered selection paths`() {
        store().install(archive())
        File(temporary.root, "drivers/active").writeText("../realm")
        assertThrows(IllegalArgumentException::class.java) { store().selected() }
    }
}
