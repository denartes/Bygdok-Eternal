package com.denartes.bygdoketernal.realm

import java.io.File

internal fun prepareRealmForAuthserver(
    worldserverRunning: Boolean,
    query: (String) -> String,
    onDiagnostic: (String) -> Unit
) {
    check(!worldserverRunning) { "Cannot reset realm startup flags while worldserver is running" }
    val select = "SELECT flag FROM realmlist WHERE id = 1;"
    val flag = query(select).trim().toIntOrNull()
    check(flag != null && flag in 0..255) { "Realm row 1 was not found or contains an invalid flag" }
    val offlineFlag = (flag and 1.inv()) or 2
    if (flag == offlineFlag) return

    // Worldserver uses bit 1 while loading; an interrupted load must not exclude the next authserver start.
    query("UPDATE realmlist SET flag = (flag & ~1) | 2 WHERE id = 1;")
    check(query(select).trim().toIntOrNull() == offlineFlag) { "Could not verify realm startup flags" }
    onDiagnostic("Prepared realm 1 for startup: flag $flag -> $offlineFlag (offline until worldserver is ready)")
}

internal fun runRealmDatabaseQuery(
    builder: ProcessBuilder,
    temporaryDirectory: File,
    onDiagnostic: (String) -> Unit
): String {
    val diagnostics = File.createTempFile("database-query-", ".stderr", temporaryDirectory)
    var process: Process? = null
    try {
        process = builder.redirectErrorStream(false).redirectError(diagnostics).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        val errors = diagnostics.readText()
        errors.lineSequence().filter { it.isNotBlank() }.forEach(onDiagnostic)
        check(exitCode == 0) {
            errors.ifBlank { output }.ifBlank { "MariaDB query failed (exit $exitCode)" }
        }
        return output
    } finally {
        process?.let { if (it.isAlive) it.destroyForcibly().waitFor() }
        if (!diagnostics.delete()) onDiagnostic("Could not remove temporary database diagnostics: ${diagnostics.name}")
    }
}
