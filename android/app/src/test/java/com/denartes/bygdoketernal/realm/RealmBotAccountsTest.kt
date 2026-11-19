package com.denartes.bygdoketernal.realm

import org.junit.Assert.*
import org.junit.Test

class RealmBotAccountsTest {
    private val snapshot = BotAccountSnapshot(
        accounts = listOf(BotAccount(1, "HUMAN", true), BotAccount(2, "BOTS", false)),
        guilds = listOf(BotGuild(4, "Guild"))
    )

    @Test
    fun `loads accounts characters guild membership exclusions and bidirectional links`() {
        val parsed = RealmBotAccounts.parse(
            "A\t1\t48554D414E\t1\nA\t2\t424F5453\t0\n" +
                "C\t8\t2\t426F62\t20\t4\nC\t9\t1\t416C696365\t80\t0\n" +
                "G\t4\t4775696C64\nL\t1\t2\nL\t2\t1\n"
        )
        assertEquals(snapshot.accounts.sortedBy { it.name }, parsed.accounts)
        assertEquals(snapshot.guilds, parsed.guilds)
        assertEquals(listOf("Alice", "Bob"), parsed.characters.map { it.name })
        assertNull(parsed.characters[0].guildId)
        assertEquals(4, parsed.characters[1].guildId)
        assertTrue(parsed.linked(1, 2))
        assertFalse(parsed.linked(1, 3))
    }

    @Test
    fun `hex text handles unicode punctuation and whitespace without changing database row structure`() {
        val name = "A 'guild'\\with\ttabs\nand é"
        val hex = name.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        assertEquals(name, RealmBotAccounts.parse("G\t3\t$hex").guilds.single().name)
    }

    @Test
    fun `malformed database output fails instead of producing partial success`() {
        for (row in listOf(
            "warning", "A\t1", "A\tbad\t41\t0", "A\t1\t4\t0",
            "A\t1\tZZ\t0", "A\t1\t41\t2", "G\t-1\t41", "C\t1\t2\t41\tbad\t0", "L\t1\t2\textra"
        )) {
            assertThrows("Accepted $row", Exception::class.java) { RealmBotAccounts.parse(row) }
        }
    }

    @Test
    fun `link and unlink preserve reciprocal semantics and use a transaction`() {
        val link = RealmBotAccounts.mutation(RealmBotAccounts.LINK, 1, 2, snapshot)
        assertTrue(link.startsWith("START TRANSACTION;"))
        assertTrue(link.endsWith("COMMIT;"))
        assertTrue(link.contains("VALUES (1, 2), (2, 1)"))
        assertTrue(link.contains("INSERT IGNORE"))
        val unlink = RealmBotAccounts.mutation(RealmBotAccounts.UNLINK, 1, 2, snapshot)
        assertTrue(unlink.contains("(account_id = 1 AND linked_account_id = 2)"))
        assertTrue(unlink.contains("(account_id = 2 AND linked_account_id = 1)"))
        assertFalse(link.contains("guild_member"))
        assertFalse(unlink.contains("guild_member"))
    }

    @Test
    fun `exclusion only changes selected account without deleting notes or other exclusions`() {
        val exclude = RealmBotAccounts.mutation(RealmBotAccounts.EXCLUDE, 1, 0, snapshot)
        assertTrue(exclude.contains("INSERT IGNORE INTO acore_characters.guildmate_excluded_accounts"))
        assertTrue(exclude.contains("VALUES (1,"))
        assertFalse(exclude.contains("DROP"))
        val include = RealmBotAccounts.mutation(RealmBotAccounts.INCLUDE, 1, 0, snapshot)
        assertTrue(include.contains("WHERE account_id = 1;"))
        assertFalse(include.contains("playerbots_account_links"))
    }

    @Test
    fun `rejects invalid missing or self linked accounts and unknown operations`() {
        for ((operation, account, target) in listOf(
            Triple("link", 1, 1), Triple("link", 1, 3), Triple("link", 0, 2),
            Triple("link", -1, 2), Triple("unlink", 1, -2), Triple("exclude", 3, 0),
            Triple("DROP TABLE", 1, 2)
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                RealmBotAccounts.mutation(operation, account, target, snapshot)
            }
        }
    }

    @Test
    fun `readback verification detects incomplete links or unsuccessful exclusions`() {
        val linked = snapshot.copy(links = setOf(1 to 2, 2 to 1))
        RealmBotAccounts.verify("link", 1, 2, linked)
        RealmBotAccounts.verify("unlink", 1, 2, snapshot)
        RealmBotAccounts.verify("exclude", 1, 0, snapshot)
        RealmBotAccounts.verify("include", 2, 0, snapshot)
        assertThrows(IllegalStateException::class.java) {
            RealmBotAccounts.verify("link", 1, 2, snapshot.copy(links = setOf(1 to 2)))
        }
        assertThrows(IllegalStateException::class.java) { RealmBotAccounts.verify("unlink", 1, 2, linked) }
        assertThrows(IllegalStateException::class.java) { RealmBotAccounts.verify("exclude", 2, 0, snapshot) }
        assertThrows(IllegalStateException::class.java) { RealmBotAccounts.verify("include", 1, 0, snapshot) }
    }

    @Test
    fun `guild selection supports empty multiple and missing existing ids without dropping preferences`() {
        assertEquals(emptySet<Int>(), RealmBotAccounts.guildIds("\"\""))
        assertEquals(setOf(1, 4), RealmBotAccounts.guildIds("\"1, 4,1\""))
        assertEquals("\"1,4,99\"", RealmBotAccounts.guildValue(setOf(99, 4, 1)))
        for (value in listOf("\"0\"", "\"-1\"", "\"1,x\"", "\"1,,2\"", "\"2147483648\"")) {
            assertThrows(IllegalArgumentException::class.java) { RealmBotAccounts.guildIds(value) }
        }
        assertThrows(IllegalArgumentException::class.java) { RealmBotAccounts.guildValue(setOf(0)) }
    }

    @Test
    fun `management requires stopped game servers even when state and processes disagree`() {
        assertTrue(RealmBotAccounts.canManage(RealmState(), false, false))
        assertTrue(RealmBotAccounts.canManage(
            RealmState(phase = RealmPhase.DATABASE_READY, database = ComponentStatus.RUNNING), false, false
        ))
        for (phase in RealmPhase.entries.filter { RealmState(phase = it).isBusy }) {
            assertFalse(RealmBotAccounts.canManage(RealmState(phase = phase), false, false))
        }
        assertFalse(RealmBotAccounts.canManage(RealmState(), true, false))
        assertFalse(RealmBotAccounts.canManage(RealmState(), false, true))
        assertFalse(RealmBotAccounts.canManage(RealmState(worldserver = ComponentStatus.RUNNING), false, false))
    }
}
