package com.denartes.bygdoketernal.realm

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

internal data class PreparedRealmBackup(
    val extractionDir: File,
    val databases: Map<String, File>,
    val moduleConfigs: List<File>,
    val moduleData: List<File>,
    val manifest: JSONObject?
)

internal object RealmBackupImporter {
    val requiredDatabases = listOf(
        "acore_auth",
        "acore_characters",
        "acore_playerbots",
        "acore_world"
    )

    private const val MAX_ENTRIES = 1024
    private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_CONFIG_BYTES = 2L * 1024 * 1024

    fun extractAndValidate(archive: File, destination: File): PreparedRealmBackup {
        require(archive.isFile && archive.length() > 0L) { "Selected backup archive is empty" }
        destination.deleteRecursively()
        check(destination.mkdirs() || destination.isDirectory) { "Could not create import staging directory" }

        val databases = linkedMapOf<String, File>()
        val moduleConfigs = mutableListOf<File>()
        val moduleData = mutableListOf<File>()
        var manifest: JSONObject? = null
        var entryCount = 0
        var totalBytes = 0L

        try {
            forEachArchiveEntry(archive) { entryName, entryStream, isDirectory ->
                    entryCount++
                    require(entryCount <= MAX_ENTRIES) { "Backup archive contains too many entries" }
                    if (isDirectory) return@forEachArchiveEntry

                    val relativePath = normalizeEntryPath(entryName)
                    val target = File(destination, relativePath)
                    check(target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                        "Backup archive contains an unsafe path"
                    }
                    val allowedConfig = relativePath.startsWith("config/modules/") && relativePath.endsWith(".conf")
                    val allowedModuleData = relativePath.startsWith("module-data/")
                    val allowed = relativePath == "manifest.json" ||
                        relativePath == "checksums.sha256" ||
                        relativePath == "config/worldserver.conf" ||
                        relativePath == "config/authserver.conf" ||
                        allowedConfig || allowedModuleData ||
                        requiredDatabases.any { relativePath == "databases/$it.sql" }
                    require(allowed) { "Backup contains an unsupported file: $relativePath" }
                    require(relativePath != "checksums.sha256") {
                        "Checksum files are not supported without a manifest"
                    }

                    target.parentFile?.mkdirs()
                    val limit = if (relativePath.startsWith("config/")) MAX_CONFIG_BYTES else MAX_ENTRY_BYTES
                    var entryBytes = 0L
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = entryStream.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            totalBytes += read
                            require(entryBytes <= limit) { "Backup entry exceeds the size limit: $relativePath" }
                            require(totalBytes <= MAX_TOTAL_BYTES) { "Backup archive exceeds the expanded size limit" }
                            output.write(buffer, 0, read)
                        }
                    }
                    require(entryBytes > 0L) { "Backup contains an empty file: $relativePath" }

                    when {
                        relativePath == "manifest.json" -> {
                            check(manifest == null) { "Backup contains duplicate manifests" }
                            manifest = JSONObject(target.readText())
                            val formatVersion = manifest!!.optInt("format_version", 1)
                            require(formatVersion == 1) { "Unsupported backup format version: $formatVersion" }
                        }
                        relativePath.startsWith("databases/") -> {
                            val database = relativePath.removePrefix("databases/").removeSuffix(".sql")
                            check(databases.put(database, target) == null) { "Backup contains duplicate database dumps" }
                        }
                        allowedConfig -> moduleConfigs.add(target)
                        allowedModuleData -> moduleData.add(target)
                    }
            }

            val missing = requiredDatabases - databases.keys
            require(missing.isEmpty()) { "Backup is missing database dumps: ${missing.joinToString()}" }
            for ((database, dump) in databases) validateDatabaseDump(database, dump)

            return PreparedRealmBackup(destination, databases, moduleConfigs, moduleData, manifest)
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        }
    }

    private fun forEachArchiveEntry(archive: File, action: (String, InputStream, Boolean) -> Unit) {
        archive.inputStream().buffered().use { input ->
            if (archive.name.endsWith(".tar.gz", ignoreCase = true) ||
                archive.name.endsWith(".tgz", ignoreCase = true)
            ) {
                TarInputStream(GZIPInputStream(input)).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry() ?: break
                        action(entry.name, tar, entry.isDirectory)
                        tar.finishEntry()
                    }
                }
            } else {
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        action(entry.name, zip, entry.isDirectory)
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    private fun normalizeEntryPath(path: String): String {
        require(path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\')) {
            "Backup archive contains an absolute path"
        }
        require(!path.contains('\\') && !path.contains('\u0000')) { "Backup archive contains an invalid path" }
        val parts = path.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Backup archive contains an unsafe path"
        }
        val normalized = if (parts.firstOrNull() == "bygdok-export") parts.drop(1).joinToString("/") else path
        require(normalized.isNotBlank()) { "Backup archive contains an invalid root entry" }
        return normalized
    }

    private fun validateDatabaseDump(database: String, dump: File) {
        var hasCreateTable = false
        var hasInsert = false
        BufferedReader(InputStreamReader(dump.inputStream(), Charsets.UTF_8)).useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("CREATE TABLE", ignoreCase = true)) hasCreateTable = true
                if (trimmed.startsWith("INSERT INTO", ignoreCase = true)) hasInsert = true
                if (trimmed.startsWith("USE ", ignoreCase = true)) {
                    val target = trimmed.removePrefix("USE ").trim().trim('`', ';', ' ', '\'')
                    require(target == database) {
                        "The $database dump switches to unexpected database $target"
                    }
                }
                require(!trimmed.startsWith("DROP DATABASE", ignoreCase = true)) {
                    "Database dumps must not drop databases"
                }
                require(!trimmed.startsWith("CREATE USER", ignoreCase = true) &&
                    !trimmed.startsWith("GRANT ", ignoreCase = true)
                ) { "Database dumps must not modify MariaDB users or grants" }
            }
        }
        require(hasCreateTable) { "$database dump contains no table definitions" }
        require(hasInsert) { "$database dump contains no data inserts" }
    }
}

private data class TarEntry(val name: String, val size: Long, val isDirectory: Boolean)

private class TarInputStream(private val input: InputStream) : InputStream() {
    private var remaining = 0L
    private var padding = 0L

    fun nextEntry(): TarEntry? {
        finishEntry()
        val header = ByteArray(512)
        var read = 0
        while (read < header.size) {
            val count = input.read(header, read, header.size - read)
            if (count < 0) return null
            read += count
        }
        if (header.all { it == 0.toByte() }) return null
        val name = field(header, 0, 100)
        val prefix = field(header, 345, 155)
        val fullName = if (prefix.isBlank()) name else "$prefix/$name"
        val size = octalField(header, 124, 12)
        val type = header[156].toInt().toChar()
        remaining = size
        padding = (512 - (size % 512)) % 512
        return TarEntry(fullName, size, type == '5')
    }

    fun finishEntry() {
        skipFully(remaining + padding)
        remaining = 0L
        padding = 0L
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return -1
        val requested = minOf(length.toLong(), remaining).toInt()
        val count = input.read(buffer, offset, requested)
        if (count < 0) return -1
        remaining -= count
        return count
    }

    override fun read(): Int {
        if (remaining == 0L) return -1
        val value = input.read()
        if (value >= 0) remaining--
        return value
    }

    override fun close() = input.close()

    private fun skipFully(bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else if (input.read() >= 0) {
                left--
            } else {
                throw IllegalArgumentException("Truncated tar archive")
            }
        }
    }

    private fun field(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length)
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
            .trim()

    private fun octalField(bytes: ByteArray, offset: Int, length: Int): Long =
        field(bytes, offset, length).trim().trimStart('0').ifBlank { "0" }.toLong(8)
}