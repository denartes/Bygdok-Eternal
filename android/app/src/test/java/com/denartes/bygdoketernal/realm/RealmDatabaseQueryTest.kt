package com.denartes.bygdoketernal.realm

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RealmDatabaseQueryTest {
    @Test
    fun `successful queries keep warnings separate from result rows`() {
        withClient(0) { builder, directory ->
            val diagnostics = mutableListOf<String>()
            val output = runRealmDatabaseQuery(builder, directory, diagnostics::add)
            assertEquals("A\t1\t48554D414E\t1", output.trim())
            assertEquals("HUMAN", RealmBotAccounts.parse(output).accounts.single().name)
            assertTrue(diagnostics.single().contains("--ssl-verify-server-cert"))
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".stderr") })
        }
    }

    @Test
    fun `failed queries report stderr instead of returning success shaped rows`() {
        withClient(1) { builder, directory ->
            val diagnostics = mutableListOf<String>()
            val error = assertThrows(IllegalStateException::class.java) {
                runRealmDatabaseQuery(builder, directory, diagnostics::add)
            }
            assertTrue(error.message.orEmpty().contains("--ssl-verify-server-cert"))
            assertEquals(1, diagnostics.size)
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".stderr") })
        }
    }

    @Test
    fun `failed process launch is explicit and removes temporary diagnostics`() {
        val directory = Files.createTempDirectory("query-launch-test").toFile()
        try {
            assertThrows(java.io.IOException::class.java) {
                runRealmDatabaseQuery(ProcessBuilder(File(directory, "missing-client").absolutePath), directory) {}
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun withClient(exit: Int, test: (ProcessBuilder, File) -> Unit) {
        val directory = Files.createTempDirectory("query-client-test").toFile()
        try {
            val source = File(directory, "QueryClient.java")
            source.writeText("""
                class QueryClient {
                    public static void main(String[] args) {
                        System.out.println("A\t1\t48554D414E\t1");
                        System.err.println("WARNING: option --ssl-verify-server-cert is disabled, because of an insecure passwordless login");
                        System.exit($exit);
                    }
                }
            """.trimIndent())
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            test(ProcessBuilder(java, source.absolutePath), directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
