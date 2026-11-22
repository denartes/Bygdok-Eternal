package com.denartes.bygdoketernal.realm

import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object RealmFullBackup {
    val roots = listOf("realm", "mod-settings", "ollama-drivers")
    private const val HEADER = "full-backup.json"
    private const val INVENTORY = "inventory.json"
    private const val MAX_METADATA = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 100000
    private val disposable = listOf(
        "realm/bin", "realm/sql", "realm/mariadb-provider", "realm/import",
        "realm/ollama/provider-libraries", "realm/ollama/tmp", "realm/llm-chatter/tmp"
    )

    fun fingerprint(binary: File): String = binary.inputStream().use { digest(it).second }

    fun isFullBackup(archive: File): Boolean {
        archive.inputStream().use {
            if (it.read() != 'P'.code || it.read() != 'K'.code) return false
        }
        return ZipFile(archive).use { it.getEntry(HEADER) != null }
    }

    fun export(
        files: File,
        output: OutputStream,
        settings: JSONObject,
        databaseRuntime: String,
        appVersion: String,
        progress: (String) -> Unit
    ) {
        check(File(files, "realm/data/ibdata1").isFile) { "Initialize the realm before creating a backup" }
        for (database in RealmBackupImporter.requiredDatabases) {
            check(File(files, "realm/data/$database").isDirectory) { "Realm database is missing: $database" }
        }
        val inventory = JSONObject()
        var entryCount = 2
        ZipOutputStream(output.buffered()).use { zip ->
            zip.setLevel(1)
            writeJson(zip, HEADER, JSONObject()
                .put("format_version", 2)
                .put("database_runtime_sha256", databaseRuntime)
                .put("app_version", appVersion)
                .put("created_at", java.time.Instant.now().toString())
                .put("settings", settings))
            for (root in roots) {
                val directory = File(files, root)
                if (!directory.exists()) continue
                progress("Saving $root")
                for (file in directory.walkTopDown().onEnter { !excluded(it.relativeTo(files).invariantSeparatorsPath) }) {
                    val relative = file.relativeTo(files).invariantSeparatorsPath
                    if (excluded(relative)) continue
                    safePath("files/$relative", false)
                    require(++entryCount <= MAX_ENTRIES) { "Realm contains too many backup entries" }
                    check(!Files.isSymbolicLink(file.toPath())) { "Cannot back up symbolic link: $relative" }
                    if (file.isDirectory) {
                        zip.putNextEntry(ZipEntry("files/$relative/"))
                        zip.closeEntry()
                    } else {
                        check(file.isFile) { "Cannot back up special file: $relative" }
                        zip.putNextEntry(ZipEntry("files/$relative"))
                        val hash = MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        file.inputStream().buffered().use { input ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                zip.write(buffer, 0, read)
                                hash.update(buffer, 0, read)
                                size += read
                            }
                        }
                        zip.closeEntry()
                        inventory.put(relative, JSONObject().put("size", size).put("sha256", hex(hash.digest())))
                    }
                }
            }
            writeJson(zip, INVENTORY, inventory)
        }
    }

    private fun excluded(path: String): Boolean =
        disposable.any { path == it || path.startsWith("$it/") } ||
            path in listOf("realm/mysqld.sock", "realm/mysqld.pid")

    fun extract(
        archive: File,
        staging: File,
        databaseRuntime: String,
        progress: (String) -> Unit
    ): JSONObject {
        check(!staging.exists()) { "Backup extraction directory already exists" }
        check(staging.mkdirs()) { "Could not create backup extraction directory" }
        val actual = JSONObject()
        val diskBudget = staging.usableSpace - 64L * 1024 * 1024
        require(diskBudget > 0) { "Not enough space to restore the realm backup" }
        var expandedBytes = 0L
        val names = mutableSetOf<String>()
        var header: JSONObject? = null
        var expected: JSONObject? = null
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(names.add(entry.name) && names.size <= MAX_ENTRIES) { "Duplicate or excessive backup entries" }
                    when (entry.name) {
                        HEADER -> {
                            header = readJson(zip)
                            require(header.getInt("format_version") == 2) { "Unsupported full backup version" }
                            require(header.getString("database_runtime_sha256") == databaseRuntime) {
                                "This backup needs the same MariaDB runtime. Install the app version used to create it."
                            }
                            header.getJSONObject("settings")
                        }
                        INVENTORY -> expected = readJson(zip)
                        else -> {
                            require(header != null) { "Backup header must precede realm files" }
                            val relative = safePath(entry.name, entry.isDirectory)
                            val target = File(staging, relative)
                            require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                                "Unsafe backup path"
                            }
                            if (entry.isDirectory) {
                                check(target.mkdirs() || target.isDirectory) { "Could not create restored directory" }
                            } else {
                                val parent = checkNotNull(target.parentFile) { "Restored file has no parent directory" }
                                check(parent.mkdirs() || parent.isDirectory) {
                                    "Could not create restored file directory"
                                }
                                progress("Restoring $relative")
                                val hash = MessageDigest.getInstance("SHA-256")
                                var size = 0L
                                target.outputStream().buffered().use { out ->
                                    val buffer = ByteArray(1024 * 1024)
                                    while (true) {
                                        val read = zip.read(buffer)
                                        if (read < 0) break
                                        expandedBytes += read
                                        require(expandedBytes <= diskBudget) { "Not enough space to restore the realm backup" }
                                        out.write(buffer, 0, read)
                                        hash.update(buffer, 0, read)
                                        size += read
                                    }
                                }
                                actual.put(relative, JSONObject().put("size", size).put("sha256", hex(hash.digest())))
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val inventory = checkNotNull(expected) { "Backup integrity inventory is missing" }
            require(inventory.length() == actual.length()) { "Backup inventory does not match its files" }
            for (name in actual.keys()) {
                val stored = actual.getJSONObject(name)
                val wanted = inventory.getJSONObject(name)
                require(stored.getLong("size") == wanted.getLong("size") &&
                    stored.getString("sha256") == wanted.getString("sha256")) {
                    "Backup checksum mismatch: $name"
                }
            }
            require(File(staging, "realm/data/ibdata1").isFile) { "Backup is missing the realm database" }
            for (database in RealmBackupImporter.requiredDatabases) {
                require(File(staging, "realm/data/$database").isDirectory) { "Backup is missing $database" }
            }
            return checkNotNull(header).getJSONObject("settings")
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun safePath(name: String, directory: Boolean): String {
        require(name.startsWith("files/")) { "Unsupported backup entry: $name" }
        val path = name.removePrefix("files/").let { if (directory) it.removeSuffix("/") else it }
        val parts = path.split('/')
        require(parts.first() in roots && parts.none { it.isBlank() || it == "." || it == ".." } &&
            !path.contains('\\') && !path.contains('\u0000') && !excluded(path)) {
            "Unsafe or unsupported backup path: $name"
        }
        return path
    }

    private fun writeJson(zip: ZipOutputStream, name: String, value: JSONObject) {
        val bytes = value.toString().toByteArray()
        require(bytes.size <= MAX_METADATA) { "Backup metadata is too large" }
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readJson(input: InputStream): JSONObject {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(bytes.size() + read <= MAX_METADATA) { "Backup metadata is too large" }
            bytes.write(buffer, 0, read)
        }
        return JSONObject(bytes.toString(Charsets.UTF_8.name()))
    }

    private fun digest(input: InputStream): Pair<Long, String> {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            size += read
            hash.update(buffer, 0, read)
        }
        return size to hex(hash.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

internal class RealmBackupTransaction(
    private val files: File,
    private val workspace: File,
    private val readSettings: () -> JSONObject,
    private val writeSettings: (JSONObject) -> Unit
) {
    fun recover() {
        val journal = File(workspace, "journal.json")
        if (!journal.exists()) {
            remove(File(workspace, "previous"))
            remove(File(workspace, "committed"))
            remove(File(workspace, "staging"))
            return
        }
        val state = JSONObject(journal.readText())
        if (!File(workspace, "committed").exists()) {
            for (name in RealmFullBackup.roots) {
                val previous = File(workspace, "previous/$name")
                val current = File(files, name)
                if (previous.exists()) {
                    remove(current)
                    move(previous, current)
                } else if (!state.getJSONObject("existed").getBoolean(name)) {
                    remove(current)
                }
            }
            writeSettings(state.getJSONObject("settings"))
        }
        remove(File(workspace, "previous"))
        check(journal.delete()) { "Could not remove restore journal" }
        remove(File(workspace, "committed"))
        remove(File(workspace, "staging"))
    }

    fun install(staging: File, settings: JSONObject) {
        check(!File(workspace, "journal.json").exists()) { "A previous restore needs recovery" }
        check(!File(workspace, "committed").exists()) { "A previous restore needs cleanup" }
        val previous = File(workspace, "previous")
        check(!previous.exists() && previous.mkdirs()) { "Could not create restore rollback directory" }
        val existed = JSONObject()
        RealmFullBackup.roots.forEach { existed.put(it, File(files, it).exists()) }
        writeDurably(File(workspace, "journal.json"), JSONObject()
            .put("existed", existed).put("settings", readSettings()).toString())
        try {
            for (name in RealmFullBackup.roots) {
                val current = File(files, name)
                if (current.exists()) move(current, File(previous, name))
                val restored = File(staging, name)
                if (restored.exists()) move(restored, current)
            }
            writeSettings(settings)
            writeDurably(File(workspace, "committed"), "complete")
        } catch (error: Exception) {
            recover()
            throw error
        }
        recover()
    }

    private fun move(source: File, destination: File) {
        check(source.renameTo(destination)) { "Could not move ${source.name} during realm restore" }
    }

    private fun writeDurably(file: File, text: String) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use {
            it.write(text.toByteArray())
            it.fd.sync()
        }
        check(temporary.renameTo(file)) { "Could not persist restore journal" }
    }

    private fun remove(file: File) {
        check(!file.exists() || file.deleteRecursively()) { "Could not remove ${file.name} during realm restore" }
    }
}
