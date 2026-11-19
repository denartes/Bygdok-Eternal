package com.denartes.bygdoketernal.realm

data class BotAccount(val id: Int, val name: String, val excluded: Boolean)
data class BotCharacter(val id: Int, val accountId: Int, val name: String, val level: Int, val guildId: Int?)
data class BotGuild(val id: Int, val name: String)
data class BotAccountSnapshot(
    val accounts: List<BotAccount> = emptyList(),
    val characters: List<BotCharacter> = emptyList(),
    val guilds: List<BotGuild> = emptyList(),
    val links: Set<Pair<Int, Int>> = emptySet()
) {
    fun linked(first: Int, second: Int) = (first to second) in links && (second to first) in links
}

data class BotAccountState(
    val snapshot: BotAccountSnapshot? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

internal object RealmBotAccounts {
    const val LOAD = "load"
    const val LINK = "link"
    const val UNLINK = "unlink"
    const val EXCLUDE = "exclude"
    const val INCLUDE = "include"

    fun canManage(state: RealmState, authAlive: Boolean, worldAlive: Boolean): Boolean =
        state.canEditMods && !authAlive && !worldAlive

    val snapshotQuery = """
        SELECT 'A', a.id, HEX(a.username), IF(e.account_id IS NULL, 0, 1)
        FROM acore_auth.account a LEFT JOIN acore_characters.guildmate_excluded_accounts e ON e.account_id = a.id;
        SELECT 'C', c.guid, c.account, HEX(c.name), c.level, COALESCE(gm.guildid, 0)
        FROM acore_characters.characters c LEFT JOIN acore_characters.guild_member gm ON gm.guid = c.guid;
        SELECT 'G', guildid, HEX(name) FROM acore_characters.guild;
        SELECT 'L', account_id, linked_account_id FROM acore_playerbots.playerbots_account_links;
    """.trimIndent()

    fun parse(output: String): BotAccountSnapshot {
        val accounts = mutableListOf<BotAccount>()
        val characters = mutableListOf<BotCharacter>()
        val guilds = mutableListOf<BotGuild>()
        val links = mutableSetOf<Pair<Int, Int>>()
        for (line in output.lineSequence().filter { it.isNotBlank() }) {
            val fields = line.split('\t')
            fun number(index: Int): Int = fields[index].toInt().also { require(it >= 0) { "Negative database ID/value" } }
            fun text(index: Int): String {
                val hex = fields[index]
                require(hex.length % 2 == 0) { "Invalid database text encoding" }
                return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
            }
            when (fields.first()) {
                "A" -> {
                    require(fields.size == 4) { "Invalid account database row" }
                    require(fields[3] in setOf("0", "1")) { "Invalid exclusion value" }
                    accounts.add(BotAccount(number(1), text(2), fields[3] == "1"))
                }
                "C" -> {
                    require(fields.size == 6) { "Invalid character database row" }
                    characters.add(BotCharacter(number(1), number(2), text(3), number(4), number(5).takeIf { it > 0 }))
                }
                "G" -> {
                    require(fields.size == 3) { "Invalid guild database row" }
                    guilds.add(BotGuild(number(1), text(2)))
                }
                "L" -> {
                    require(fields.size == 3) { "Invalid account link database row" }
                    links.add(number(1) to number(2))
                }
                else -> error("Unexpected database output while loading bot accounts: $line")
            }
        }
        return BotAccountSnapshot(accounts.sortedBy { it.name }, characters.sortedBy { it.name }, guilds.sortedBy { it.name }, links)
    }

    fun mutation(operation: String, account: Int, target: Int, snapshot: BotAccountSnapshot): String {
        require(account > 0 && snapshot.accounts.any { it.id == account }) { "Select an existing account" }
        val statement = when (operation) {
            LINK, UNLINK -> {
                require(target > 0 && target != account && snapshot.accounts.any { it.id == target }) {
                    "Select a different existing account to link"
                }
                if (operation == LINK) {
                    "INSERT IGNORE INTO acore_playerbots.playerbots_account_links (account_id, linked_account_id) " +
                        "VALUES ($account, $target), ($target, $account);"
                } else {
                    "DELETE FROM acore_playerbots.playerbots_account_links " +
                        "WHERE (account_id = $account AND linked_account_id = $target) " +
                        "OR (account_id = $target AND linked_account_id = $account);"
                }
            }
            EXCLUDE -> "INSERT IGNORE INTO acore_characters.guildmate_excluded_accounts (account_id, notes) " +
                "VALUES ($account, 'Human account excluded via Android app');"
            INCLUDE -> "DELETE FROM acore_characters.guildmate_excluded_accounts WHERE account_id = $account;"
            else -> throw IllegalArgumentException("Unknown bot account operation")
        }
        return "START TRANSACTION;\n$statement\nCOMMIT;"
    }

    fun verify(operation: String, account: Int, target: Int, snapshot: BotAccountSnapshot) {
        val saved = when (operation) {
            LOAD -> true
            LINK -> snapshot.linked(account, target)
            UNLINK -> (account to target) !in snapshot.links && (target to account) !in snapshot.links
            EXCLUDE -> snapshot.accounts.any { it.id == account && it.excluded }
            INCLUDE -> snapshot.accounts.any { it.id == account && !it.excluded }
            else -> false
        }
        check(saved) { "Could not verify the saved account change. Reload the database before retrying." }
    }

    fun guildIds(value: String): Set<Int> {
        val content = value.trim().removeSurrounding("\"")
        if (content.isBlank()) return emptySet()
        return content.split(',').map {
            requireNotNull(it.trim().toIntOrNull()?.takeIf { id -> id > 0 }) { "Guild IDs must be positive whole numbers" }
        }.toSet()
    }

    fun guildValue(ids: Set<Int>): String {
        require(ids.all { it > 0 }) { "Guild IDs must be positive" }
        return "\"${ids.sorted().joinToString(",")}\""
    }
}
