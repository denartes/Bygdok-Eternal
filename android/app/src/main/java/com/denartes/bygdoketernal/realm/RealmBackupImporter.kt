package com.denartes.bygdoketernal.realm

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
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
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    require(entryCount <= MAX_ENTRIES) { "Backup archive contains too many entries" }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }

                    val relativePath = normalizeEntryPath(entry.name)
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
                            val read = zip.read(buffer)
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
                    zip.closeEntry()
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