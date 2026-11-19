package com.denartes.bygdoketernal.realm

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONException
import org.json.JSONObject

data class RealmOllamaDriver(val name: String, val library: File) {
    fun environment(hookDirectory: File): Map<String, String> = mapOf(
        "BYGDOK_VULKAN_DRIVER_DIR" to library.parentFile!!.absolutePath,
        "BYGDOK_VULKAN_DRIVER_NAME" to library.name,
        "BYGDOK_VULKAN_HOOK_DIR" to hookDirectory.absolutePath
    )
}

class RealmOllamaDriverStore(private val root: File, private val deviceApi: Int) {
    private val selection = File(root, "active")

    fun selected(): RealmOllamaDriver? {
        val directory = selectedDirectory() ?: return null
        return validate(directory)
    }

    fun install(input: InputStream): RealmOllamaDriver {
        check(root.mkdirs() || root.isDirectory) { "Could not create private driver storage" }
        val previous = selectedDirectory()
        val staging = File(root, UUID.randomUUID().toString())
        check(staging.mkdir()) { "Could not create driver import directory" }
        val pointer = File(root, "active-${staging.name}")
        var committed = false
        try {
            val names = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && safeName.matches(entry.name)) {
                        "Driver ZIP must contain flat file names without directories or unsafe paths"
                    }
                    require(names.add(entry.name)) { "Driver ZIP contains duplicate files" }
                    require(names.size <= 32) { "Driver ZIP contains too many files" }
                    val library = libraryName.matches(entry.name)
                    require(library || entry.name == "meta.json" ||
                        entry.name.lowercase().startsWith("license") ||
                        entry.name.lowercase().startsWith("notice") ||
                        entry.name.lowercase().startsWith("readme")) {
                        "Unsupported driver ZIP entry: ${entry.name}"
                    }
                    val limit = if (library) MAX_LIBRARY_BYTES else MAX_TEXT_BYTES
                    var written = 0L
                    File(staging, entry.name).outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            written += count
                            total += count
                            require(written <= limit && total <= MAX_TOTAL_BYTES) {
                                "Driver ZIP exceeds the allowed expanded size"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                    require(written > 0) { "Driver ZIP contains an empty file" }
                    zip.closeEntry()
                }
            }
            val driver = validate(staging)
            pointer.writeText(staging.name)
            Files.move(pointer.toPath(), selection.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            committed = true
            if (previous != null) check(previous.deleteRecursively()) {
                "Driver imported, but the previous driver could not be removed"
            }
            return driver
        } finally {
            check(!pointer.exists() || pointer.delete()) { "Could not clean driver selection staging file" }
            if (!committed) check(staging.deleteRecursively()) { "Could not clean failed driver import" }
        }
    }

    fun remove() {
        val directory = selectedDirectory() ?: return
        check(selection.delete()) { "Could not clear the selected custom driver" }
        check(directory.deleteRecursively()) { "Custom driver deselected, but its files could not be removed" }
    }

    private fun selectedDirectory(): File? {
        if (!selection.exists()) return null
        require(selection.length() <= 36) { "Invalid custom driver selection" }
        val id = selection.readText()
        require(directoryName.matches(id)) { "Invalid custom driver selection" }
        return File(root, id)
    }

    private fun validate(directory: File): RealmOllamaDriver {
        val metadata = File(directory, "meta.json")
        require(metadata.isFile && metadata.length() in 1..MAX_TEXT_BYTES) { "Driver ZIP is missing valid meta.json" }
        val json = try {
            JSONObject(metadata.readText())
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid driver metadata JSON", error)
        }
        require(json.optInt("schemaVersion") == 1) { "Unsupported driver metadata schema" }
        val minApi = json.optInt("minApi", -1)
        require(minApi in 21..deviceApi) { "Driver requires unsupported Android API $minApi (device: $deviceApi)" }
        val name = json.optString("name")
        require(name.isNotBlank() && name.length <= 160) { "Driver metadata is missing a valid name" }
        val library = json.optString("libraryName")
        require(libraryName.matches(library)) { "Driver metadata contains an unsafe library name" }
        val file = File(directory, library)
        require(file.isFile) { "Driver ZIP is missing $library" }
        directory.listFiles().orEmpty().filter { it.name.endsWith(".so") }.forEach(::validateElf)
        return RealmOllamaDriver(name, file)
    }

    private fun validateElf(file: File) {
        val header = ByteArray(64)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                require(count > 0) { "Truncated driver library: ${file.name}" }
                offset += count
            }
        }
        require(header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[4] == 2.toByte() && header[5] == 1.toByte() && header[6] == 1.toByte() &&
            header[16] == 3.toByte() && header[17] == 0.toByte() &&
            header[18] == 183.toByte() && header[19] == 0.toByte()) {
            "Driver must contain Android ARM64 ELF shared libraries: ${file.name}"
        }
    }

    companion object {
        private val safeName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val libraryName = Regex("lib[A-Za-z0-9_-]+\\.so")
        private val directoryName = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private const val MAX_LIBRARY_BYTES = 64L * 1024 * 1024
        private const val MAX_TEXT_BYTES = 64L * 1024
        private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
    }
}
