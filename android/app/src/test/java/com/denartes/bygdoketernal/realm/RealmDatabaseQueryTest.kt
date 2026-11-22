package com.denartes.bygdoketernal.realm

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RealmDatabaseQueryTest {
    @Test
    fun `realm startup clears stale mismatch and preserves other flags while offline`() {
        for (original in 0..255) {
            var flag = original
            val queries = mutableListOf<String>()
            val diagnostics = mutableListOf<String>()
            prepareRealmForAuthserver(false, { sql ->
                queries.add(sql)
                when (sql) {
                    "SELECT flag FROM realmlist WHERE id = 1;" -> "$flag\n"
                    "UPDATE realmlist SET flag = (flag & ~1) | 2 WHERE id = 1;" -> {
                        flag = (flag and 1.inv()) or 2
                        ""
                    }
                    else -> error("Unexpected query: $sql")
                }
            }, diagnostics::add)
            assertEquals((original and 1.inv()) or 2, flag)
            assertEquals(0, flag and 1)
            assertEquals(2, flag and 2)
            assertEquals(original and 3.inv(), flag and 3.inv())
            assertEquals(if (flag == original) 1 else 3, queries.size)
            assertEquals(if (flag == original) 0 else 1, diagnostics.size)
        }
    }

    @Test
    fun `realm startup rejects missing invalid or unverified flags`() {
        for (row in listOf("", "invalid", "-1", "256", "3\n2")) {
            val queries = mutableListOf<String>()
            assertThrows(IllegalStateException::class.java) {
                prepareRealmForAuthserver(false, { sql -> queries.add(sql); row }) {}
            }
            assertEquals(1, queries.size)
        }
        val diagnostics = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            prepareRealmForAuthserver(false, { "3" }, diagnostics::add)
        }
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `realm startup cannot change flags while worldserver is alive`() {
        assertThrows(IllegalStateException::class.java) {
            prepareRealmForAuthserver(true, { error("Database must not be queried") }) {}
        }
    }

    @Test
    fun `realm startup propagates database failures without reporting success`() {
        val diagnostics = mutableListOf<String>()
        assertThrows(java.io.IOException::class.java) {
            prepareRealmForAuthserver(false, { throw java.io.IOException("query failed") }, diagnostics::add)
        }
        assertTrue(diagnostics.isEmpty())
    }

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
