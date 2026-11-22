package com.denartes.bygdoketernal.realm

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class RealmFullBackupTest {
    private val runtime = "database-runtime"
    private fun directory() = Files.createTempDirectory("full-realm-backup").toFile()
    private fun put(root: File, path: String, value: String) {
        File(root, path).apply { checkNotNull(parentFile).mkdirs(); writeText(value) }
    }
    private fun fixture(root: File) {
        put(root, "realm/data/ibdata1", "innodb system")
        for (database in RealmBackupImporter.requiredDatabases) {
            put(root, "realm/data/$database/sample.ibd", "$database contents")
        }
        for (path in listOf(
            "realm/data/mysql/global_priv.MAD", "realm/data/undo001", "realm/data/.acore-schema-bootstrap-complete",
            "realm/client-data/vmaps/000.vmtree", "realm/client-data/mmaps/000.mmap",
            "realm/client-data/maps/000.map", "realm/client-data/dbc/Spell.dbc",
            "realm/ollama/models/blobs/sha256-model", "realm/ollama/models/manifests/model",
            "realm/llm-chatter/memory.json", "realm/etc/worldserver.conf",
            "realm/etc/authserver.conf", "mod-settings/mod_llm_chatter.conf",
            "ollama-drivers/active", "ollama-drivers/id/driver.so", "ollama-drivers/id/meta.json"
        )) put(root, path, "saved $path")
        put(root, "realm/empty", "")
        File(root, "realm/empty-directory").mkdirs()
    }
    private fun snapshot(root: File, archive: File, settings: JSONObject = JSONObject().put("realm", "Bygdok")) {
        archive.outputStream().use { RealmFullBackup.export(root, it, settings, runtime, "test") {} }
    }
    private fun regularFiles(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to RealmFullBackup.fingerprint(it) }

    @Test
    fun `full export restores every persistent realm surface into a fresh install`() {
        val root = directory()
        try {
            val source = File(root, "source").apply { mkdirs() }
            fixture(source)
            File(source, "realm/ollama/models/blobs/sha256-model").writeBytes(
                ByteArray(3 * 1024 * 1024 + 17).also { java.util.Random(42).nextBytes(it) })
            val archive = File(root, "backup.zip")
            snapshot(source, archive)
            assertTrue(RealmFullBackup.isFullBackup(archive))
            val staging = File(root, "staging")
            val settings = RealmFullBackup.extract(archive, staging, runtime) {}
            var saved = JSONObject()
            val fresh = File(root, "fresh").apply { mkdirs() }
            RealmBackupTransaction(fresh, File(root, "transaction").apply { mkdirs() },
                { saved }, { saved = it }).install(staging, settings)
            assertEquals(regularFiles(source), regularFiles(fresh))
            assertEquals("Bygdok", saved.getString("realm"))
            assertTrue(File(fresh, "realm/empty-directory").isDirectory)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `generated APK paths and transient sockets are not exported`() {
        val root = directory()
        try {
            val source = File(root, "source").apply { mkdirs() }
            fixture(source)
            for (path in listOf("realm/bin/client", "realm/sql/update.sql", "realm/mariadb-provider/lib.so",
                "realm/import/backup.zip", "realm/ollama/tmp/work", "realm/llm-chatter/tmp/bridge.lock",
                "realm/mysqld.pid", "realm/mysqld.sock")) put(source, path, "generated")
            val link = File(source, "realm/ollama/provider-libraries/lib.so")
            checkNotNull(link.parentFile).mkdirs()
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) link.writeText("generated linker file")
            else Files.createSymbolicLink(link.toPath(), File(source, "realm/empty").toPath())
            val archive = File(root, "backup.zip")
            snapshot(source, archive)
            ZipFile(archive).use {
                assertNull(it.getEntry("files/realm/mysqld.sock"))
                assertNull(it.getEntry("files/realm/ollama/provider-libraries/lib.so"))
                assertNull(it.getEntry("files/realm/bin/client"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `unexpected persistent symlinks fail instead of silently losing state`() {
        org.junit.Assume.assumeFalse("Symlink policy is tested on Linux; Windows requires administrator privileges",
            System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val root = directory()
        try {
            fixture(root)
            Files.createSymbolicLink(File(root, "mod-settings/link").toPath(), File(root, "realm/empty").toPath())
            assertThrows(IllegalStateException::class.java) {
                snapshot(root, File(root, "backup.zip"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `incompatible database runtime is rejected before replacing anything`() {
        val root = directory()
        try {
            val source = File(root, "source").apply { mkdirs() }
            fixture(source)
            val archive = File(root, "backup.zip")
            snapshot(source, archive)
            val staging = File(root, "staging")
            assertThrows(IllegalArgumentException::class.java) {
                RealmFullBackup.extract(archive, staging, "other-runtime") {}
            }
            assertFalse(staging.exists())
            assertEquals("innodb system", File(source, "realm/data/ibdata1").readText())
        } finally { root.deleteRecursively() }
    }

    private fun rewrite(source: File, destination: File, transform: (String, ByteArray) -> ByteArray) {
        ZipFile(source).use { zip ->
            ZipOutputStream(destination.outputStream()).use { output ->
                for (entry in zip.entries()) {
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(transform(entry.name, zip.getInputStream(entry).use { it.readBytes() }))
                    output.closeEntry()
                }
            }
        }
    }

    @Test
    fun `checksum mismatch rejects corrupted state and cleans staging`() {
        val root = directory()
        try {
            val source = File(root, "source").apply { mkdirs() }
            fixture(source)
            val good = File(root, "good.zip")
            snapshot(source, good)
            val bad = File(root, "bad.zip")
            rewrite(good, bad) { name, bytes ->
                if (name == "files/realm/data/ibdata1") "corrupted".toByteArray() else bytes
            }
            val staging = File(root, "staging")
            assertThrows(IllegalArgumentException::class.java) {
                RealmFullBackup.extract(bad, staging, runtime) {}
            }
            assertFalse(staging.exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `missing integrity inventory is rejected`() {
        val root = directory()
        try {
            fixture(root)
            val archive = File(root, "backup.zip")
            snapshot(root, archive)
            val bad = File(root, "bad.zip")
            ZipFile(archive).use { zip ->
                ZipOutputStream(bad.outputStream()).use { out ->
                    for (entry in zip.entries()) {
                        if (entry.name == "inventory.json") continue
                        out.putNextEntry(ZipEntry(entry.name))
                        out.write(zip.getInputStream(entry).use { it.readBytes() })
                        out.closeEntry()
                    }
                }
            }
            assertThrows(IllegalStateException::class.java) {
                RealmFullBackup.extract(bad, File(root, "staging"), runtime) {}
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `unsafe archive paths cannot escape managed realm roots`() {
        val root = directory()
        try {
            for ((index, path) in listOf("files/realm/../../outside", "files/realm\\escape", "files/other/file",
                "files/realm/sql/stale.sql", "files/realm/./file").withIndex()) {
                val archive = File(root, "$index.zip")
                ZipOutputStream(archive.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("full-backup.json"))
                    zip.write(JSONObject().put("format_version", 2)
                        .put("database_runtime_sha256", runtime).put("settings", JSONObject()).toString().toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry(path))
                    zip.write("bad".toByteArray())
                    zip.closeEntry()
                }
                assertThrows(IllegalArgumentException::class.java) {
                    RealmFullBackup.extract(archive, File(root, "staging-$index"), runtime) {}
                }
            }
            assertFalse(File(root, "outside").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `restore overwrites all old roots and preferences rather than merging`() {
        val root = directory()
        try {
            val current = File(root, "current").apply { mkdirs() }
            val staging = File(root, "staging").apply { mkdirs() }
            put(current, "realm/old", "old realm")
            put(current, "mod-settings/old", "old config")
            put(current, "ollama-drivers/old", "old driver")
            put(staging, "realm/new", "new realm")
            var settings = JSONObject().put("value", "old")
            val workspace = File(root, "work").apply { mkdirs() }
            RealmBackupTransaction(current, workspace, { settings }, { settings = it })
                .install(staging, JSONObject().put("value", "new"))
            assertEquals("new", settings.getString("value"))
            assertTrue(File(current, "realm/new").exists())
            assertFalse(File(current, "realm/old").exists())
            assertFalse(File(current, "mod-settings").exists())
            assertFalse(File(current, "ollama-drivers").exists())
            assertFalse(File(workspace, "journal.json").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `preference failure rolls back files and partially written preferences`() {
        val root = directory()
        try {
            val current = File(root, "current").apply { mkdirs() }
            val staging = File(root, "staging").apply { mkdirs() }
            put(current, "realm/old", "original")
            put(staging, "realm/new", "replacement")
            val old = JSONObject().put("value", "old")
            var settings = old
            val transaction = RealmBackupTransaction(current, File(root, "work").apply { mkdirs() },
                { settings }, {
                    settings = it
                    if (it.getString("value") == "new") error("preference write failed")
                })
            assertThrows(IllegalStateException::class.java) {
                transaction.install(staging, JSONObject().put("value", "new"))
            }
            assertTrue(File(current, "realm/old").exists())
            assertFalse(File(current, "realm/new").exists())
            assertEquals("old", settings.getString("value"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `interrupted restore rolls back on next startup`() {
        val root = directory()
        try {
            val current = File(root, "current").apply { mkdirs() }
            val workspace = File(root, "work").apply { mkdirs() }
            put(workspace, "previous/realm/old", "original")
            put(current, "realm/new", "uncommitted")
            put(current, "mod-settings/new", "uncommitted")
            put(workspace, "journal.json", JSONObject().put("settings", JSONObject().put("value", "old"))
                .put("existed", JSONObject().put("realm", true).put("mod-settings", false).put("ollama-drivers", false))
                .toString())
            var settings = JSONObject()
            val transaction = RealmBackupTransaction(current, workspace, { settings }, { settings = it })
            transaction.recover()
            transaction.recover()
            assertTrue(File(current, "realm/old").exists())
            assertFalse(File(current, "realm/new").exists())
            assertFalse(File(current, "mod-settings").exists())
            assertEquals("old", settings.getString("value"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `committed restore recovery keeps new realm and cleans rollback files`() {
        val root = directory()
        try {
            val current = File(root, "current").apply { mkdirs() }
            val workspace = File(root, "work").apply { mkdirs() }
            put(workspace, "previous/realm/old", "old")
            put(current, "realm/new", "new")
            put(workspace, "journal.json", JSONObject().toString())
            put(workspace, "committed", "complete")
            RealmBackupTransaction(current, workspace, { JSONObject() }, { error("Must not rewrite committed settings") })
                .recover()
            assertTrue(File(current, "realm/new").exists())
            assertFalse(File(workspace, "previous").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `recovery preserves original roots not yet moved when restore was interrupted`() {
        val root = directory()
        try {
            val current = File(root, "current").apply { mkdirs() }
            val workspace = File(root, "work").apply { mkdirs() }
            put(workspace, "previous/realm/old", "original realm")
            put(current, "mod-settings/original", "original config")
            put(workspace, "staging/realm/new", "staged replacement")
            put(workspace, "journal.json", JSONObject().put("settings", JSONObject())
                .put("existed", JSONObject().put("realm", true).put("mod-settings", true).put("ollama-drivers", false))
                .toString())
            RealmBackupTransaction(current, workspace, { JSONObject() }, {}).recover()
            assertTrue(File(current, "realm/old").exists())
            assertEquals("original config", File(current, "mod-settings/original").readText())
            assertFalse(File(workspace, "staging").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `startup removes interrupted extraction without changing the existing realm`() {
        val root = directory()
        try {
            val current = File(root, "current").apply { mkdirs() }
            val workspace = File(root, "work").apply { mkdirs() }
            put(current, "realm/old", "original realm")
            put(workspace, "staging/realm/incomplete", "partial extraction")
            RealmBackupTransaction(current, workspace, { JSONObject() }, { error("Settings must not change") })
                .recover()
            assertEquals("original realm", File(current, "realm/old").readText())
            assertFalse(File(workspace, "staging").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `backup state gates every running component and mod edits`() {
        assertTrue(RealmState().canBackup)
        for (state in listOf(
            RealmState(database = ComponentStatus.RUNNING),
            RealmState(authserver = ComponentStatus.RUNNING),
            RealmState(worldserver = ComponentStatus.STARTING),
            RealmState(ollama = ComponentStatus.RUNNING),
            RealmState(phase = RealmPhase.EXPORTING_REALM),
            RealmState(phase = RealmPhase.IMPORTING_REALM)
        )) assertFalse(state.canBackup)
        assertFalse(RealmState(phase = RealmPhase.EXPORTING_REALM).canEditMods)
    }

    @Test
    fun `server configuration restore retains values while accepting new defaults`() {
        val baseline = "[authserver]\nLogsDir = \"default\"\nRealmPort = 3724\nNewKey = 1\n"
        val saved = "[authserver]\nLogsDir = \"old-install\"\nRealmPort = 9999\nCustomKey = 2\n"
        val merged = mergeRealmServerConfig(baseline, saved)
        assertTrue(merged.contains("RealmPort = 9999"))
        assertTrue(merged.contains("NewKey = 1"))
        assertTrue(merged.contains("CustomKey = 2"))
        assertEquals(1, Regex("\\[authserver]").findAll(merged).count())
    }
}
