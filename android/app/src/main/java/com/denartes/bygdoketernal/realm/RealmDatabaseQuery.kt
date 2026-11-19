package com.denartes.bygdoketernal.realm

import java.io.File

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
