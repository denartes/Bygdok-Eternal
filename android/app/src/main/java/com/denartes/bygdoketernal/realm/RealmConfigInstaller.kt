package com.denartes.bygdoketernal.realm

import android.content.Context
import java.io.File

/**
 * Copies the canonical authserver/worldserver configs and the SQL update
 * tree from APK assets into app-private storage, patching only the handful
 * of paths that must become on-device absolute paths. Database connection
 * strings are left untouched: the repository's default
 * `127.0.0.1;3306;acore;acore;<db>` already matches the embedded mariadbd.
 */
class RealmConfigInstaller(private val context: Context, private val paths: RealmPaths) {

    fun install() {
        val bundledMysqlClient = installMysqlClientExecutable()
        installConfig("authserver.conf", paths.authserverConf) { line ->
            patchDirective(line, "LogsDir", paths.logsDir.absolutePath)
                ?: patchDirective(line, "SourceDirectory", paths.sqlDir.absolutePath)
                ?: patchDirective(line, "MySQLExecutable", bundledMysqlClient.absolutePath)
                ?: patchDirective(line, "LoginDatabaseInfo", ".;${paths.socketFile.absolutePath};acore;acore;acore_auth")
                ?: line
        }
        installConfig("worldserver.conf", paths.worldserverConf) { line ->
            patchDirective(line, "LogsDir", paths.logsDir.absolutePath)
                ?: patchDirective(line, "SourceDirectory", paths.sqlDir.absolutePath)
                ?: patchDirective(line, "MySQLExecutable", bundledMysqlClient.absolutePath)
                ?: patchDirective(line, "DataDir", paths.clientDataDir.absolutePath)
                ?: patchDirective(line, "WorldDatabaseInfo", ".;${paths.socketFile.absolutePath};acore;acore;acore_world")
                ?: patchDirective(line, "CharacterDatabaseInfo", ".;${paths.socketFile.absolutePath};acore;acore;acore_characters")
                ?: patchDirective(line, "PlayerbotsDatabaseInfo", ".;${paths.socketFile.absolutePath};acore;acore;acore_playerbots")
                ?: line
        }
        File(paths.etcDir, "my.cnf").writeText("[mysqld]\nversion=8.0.36\n")
        realmModConfigStore(context).install(paths.modulesConfigDir)
        replaceBundledRealmSql(paths.sqlDir) { destination ->
            copyAssetTreeIfPresent("sql", destination)
        }
        copyAssetTreeIfPresent("mariadb-share", paths.mariadbBaseDir)
        paths.mariadbLibDir.deleteRecursively()
        paths.mariadbLibDir.mkdirs()
        copyAssetTreeIfPresent("mariadb-libs", paths.mariadbLibDir)
    }

    private fun installMysqlClientExecutable(): File {
        return File(paths.nativeLibraryDir, "libpocket_mariadb_client.so")
    }

    private fun installConfig(assetName: String, destination: File, patchLine: (String) -> String) {
        val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
        val patched = text.lineSequence().joinToString("\n") { patchLine(it) }
        destination.writeText(patched)
    }

    /** Matches `Key = "value"` or `Key = value` at the start of a config line. */
    private fun patchDirective(line: String, key: String, newValue: String): String? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("$key ") && !trimmed.startsWith("$key=")) return null
        val equalsIndex = line.indexOf('=')
        if (equalsIndex < 0) return null
        val prefix = line.substring(0, equalsIndex + 1)
        return "$prefix \"$newValue\""
    }

    private fun copyAssetTreeIfPresent(assetPath: String, destinationDir: File) {
        val entries = context.assets.list(assetPath) ?: return
        if (entries.isEmpty()) {
            // A leaf file rather than a directory.
            destinationDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destinationDir.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destinationDir.mkdirs()
        for (entry in entries) {
            copyAssetTreeIfPresent("$assetPath/$entry", File(destinationDir, entry))
        }
    }
}

internal fun replaceBundledRealmSql(destination: File, copyAssets: (File) -> Unit) {
    // SQL assets are disposable; merging retains migrations moved into archive by core updates.
    check(!destination.exists() || destination.deleteRecursively()) {
        "Could not remove outdated bundled SQL at ${destination.absolutePath}"
    }
    check(destination.mkdirs()) {
        "Could not create bundled SQL directory at ${destination.absolutePath}"
    }
    copyAssets(destination)
}
