package com.denartes.bygdoketernal.realm

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class RealmModDefinition(
    val id: String,
    val title: String,
    val configFile: String,
    val enableKeys: List<String>,
    val requiresPlayerbots: Boolean = false,
    val warning: String = ""
)

object RealmMods {
    val all = listOf(
        RealmModDefinition("mod-account-achievements", "Account Achievements", "mod_achievements.conf", listOf("Account.Achievements.Enable")),
        RealmModDefinition("mod-aoe-loot", "AoE Loot", "mod_aoe_loot.conf", listOf("AOELoot.Enable")),
        RealmModDefinition("mod-ah-bot-plus", "Auction House Bot", "mod_ahbot.conf", listOf("AuctionHouseBot.EnableSeller", "AuctionHouseBot.EnableBuyer"),
            warning = "Turning this off stops buying and selling, but does not remove existing auctions."),
        RealmModDefinition("mod-duel-reset", "Duel Reset", "duelreset.conf", listOf("DuelReset.Cooldowns", "DuelReset.HealthMana")),
        RealmModDefinition("mod-emblem-transfer", "Emblem Transfer", "emblem_transfer.conf", listOf("EmblemTransfer.Enable"),
            warning = "Pending transfers are retained while disabled."),
        RealmModDefinition("mod-guildhouse", "Guildhouse", "mod_guildhouse.conf", listOf("GuildHouse.Enable"),
            warning = "Existing houses, NPCs and objects are retained while disabled."),
        RealmModDefinition("mod-instance-reset", "Instance Reset", "instance-reset.conf", listOf("instanceReset.Enable")),
        RealmModDefinition("mod-npc-enchanter", "NPC Enchanter", "npc_enchanter.conf", listOf("Enchanter.Enable")),
        RealmModDefinition("mod-reagent-bank", "Reagent Bank", "reagent_bank.conf", listOf("ReagentBank.Enable"),
            warning = "Stored items are retained but inaccessible while disabled."),
        RealmModDefinition("mod-playerbots", "Playerbots", "playerbots.conf", listOf("AiPlayerbot.Enabled"),
            warning = "Turning this off also disables Guild Mate, Bot Level Brackets and Ollama Chat for that server session."),
        RealmModDefinition("mod-player-bot-level-brackets", "Bot Level Brackets", "mod_player_bot_level_brackets.conf", listOf("BotLevelBrackets.Enabled"), true),
        RealmModDefinition("mod-individual-progression", "Individual Progression", "individualProgression.conf", listOf("IndividualProgression.Enable"),
            warning = "Disabling runtime behavior does not undo world SQL changes or restore a stock WotLK realm."),
        RealmModDefinition("mod-guild-mate", "Guild Mate", "mod_guild_mate.conf", listOf("GuildMate.Enable"), true),
        RealmModDefinition("mod-transmog-plus", "Transmog Plus", "mod_transmog_plus.conf", listOf("Transmog.Enable"),
            warning = "Saved appearances remain when disabled."),
        RealmModDefinition("mod-ollama-chat", "Ollama Chat", "mod_ollama_chat.conf", listOf("OllamaChat.Enable"), true,
            "Choose app-managed on-device Ollama, or configure a remote Ollama API endpoint and model.")
    )
    val playerbots = all.single { it.id == "mod-playerbots" }
}

object RealmModConfig {
    const val ENABLED_KEY = "Bygdok.Mod.Enabled"
    private val assignment = Regex("""^([A-Za-z][A-Za-z0-9_.]*)\s*=\s*(.*)$""")
    private val booleans = setOf("0", "1", "true", "false")

    fun values(text: String): LinkedHashMap<String, String> {
        require(text.length <= 256 * 1024) { "Module config exceeds 256 KB" }
        val result = linkedMapOf<String, String>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            require(line.none { it.code < 32 && it != '\t' }) { "Control character on line ${index + 1}" }
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            if (line == "[worldserver]") return@forEachIndexed
            val match = assignment.matchEntire(line)
                ?: throw IllegalArgumentException("Expected Key = value on line ${index + 1}")
            val key = match.groupValues[1]
            var quoted = false
            var escaped = false
            val value = match.groupValues[2]
            var end = value.length
            for ((position, char) in value.withIndex()) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\' && quoted) {
                    escaped = true
                } else if (char == '"') {
                    quoted = !quoted
                } else if (char == '#' && !quoted) {
                    end = position
                    break
                }
            }
            require(!quoted) { "Unclosed quote for $key on line ${index + 1}" }
            require(key !in result) { "Duplicate setting: $key" }
            result[key] = value.substring(0, end).trim()
        }
        return result
    }

    fun merge(defaults: String, overrides: String?): String =
        replace(defaults, overrides?.let(::values).orEmpty())

    fun replace(text: String, settings: Map<String, String>): String {
        values(text)
        val remaining = settings.toMutableMap()
        val lines = text.lineSequence().filter { it.trim() != "[worldserver]" }.map { line ->
            val key = assignment.matchEntire(line.trim())?.groupValues?.get(1)
            if (key != null && remaining.containsKey(key)) "$key = ${remaining.remove(key)}" else line
        }.toMutableList()
        remaining.forEach { (key, value) -> lines.add("$key = $value") }
        return "[worldserver]\n" + lines.joinToString("\n").trim() + "\n"
    }

    fun enabled(mod: RealmModDefinition, text: String): Boolean {
        val settings = values(text)
        if (settings[ENABLED_KEY]?.lowercase() in setOf("0", "false")) return false
        return mod.enableKeys.any { key ->
            settings[key]?.trim('"')?.lowercase() in setOf("1", "true")
        }
    }

    fun validate(mod: RealmModDefinition, text: String, defaults: String) {
        val settings = values(text)
        val baseline = values(defaults)
        require(settings.keys.containsAll(baseline.keys)) { "Config is missing bundled settings" }
        for (key in mod.enableKeys) {
            require(settings[key]?.lowercase() in booleans) { "$key must be 0, 1, true or false" }
        }
        settings[ENABLED_KEY]?.let { require(it.lowercase() in booleans) { "$ENABLED_KEY must be 0, 1, true or false" } }
        for ((key, defaultValue) in baseline) {
            val value = settings.getValue(key)
            when {
                key in mod.enableKeys -> Unit
                defaultValue.lowercase() in setOf("true", "false") ->
                    require(value.lowercase() in booleans) { "$key must be 0, 1, true or false" }
                defaultValue in setOf("0", "1") && value.lowercase() in booleans -> Unit
                defaultValue.toDoubleOrNull() != null ->
                    require(value.toDoubleOrNull()?.isFinite() == true) { "$key must be a finite number" }
            }
        }
    }

    fun runtimeConfigs(configs: Map<RealmModDefinition, String>): Map<RealmModDefinition, String> {
        val botsEnabled = enabled(RealmMods.playerbots, configs.getValue(RealmMods.playerbots))
        return configs.mapValues { (mod, text) ->
            val effective = if (!enabled(mod, text) || (mod.requiresPlayerbots && !botsEnabled)) {
                replace(text, mod.enableKeys.associateWith { "0" })
            } else text
            effective.lineSequence().filterNot {
                assignment.matchEntire(it.trim())?.groupValues?.get(1) == ENABLED_KEY
            }.joinToString("\n")
        }
    }
}

class RealmModConfigStore(
    private val directory: File,
    private val defaults: (RealmModDefinition) -> String
) {
    fun load(mod: RealmModDefinition): String {
        val saved = File(directory, mod.configFile)
        return RealmModConfig.merge(defaults(mod), if (saved.isFile) saved.readText() else null)
    }

    fun save(mod: RealmModDefinition, text: String) {
        require(mod in RealmMods.all) { "Unknown module: ${mod.id}" }
        RealmModConfig.validate(mod, text, defaults(mod))
        check(directory.isDirectory || directory.mkdirs()) { "Could not create persistent module settings directory" }
        val target = File(directory, mod.configFile)
        val temporary = File.createTempFile(mod.configFile, ".tmp", directory)
        try {
            temporary.outputStream().use { output ->
                output.write(RealmModConfig.replace(text, emptyMap()).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    fun setEnabled(mod: RealmModDefinition, enabled: Boolean) {
        val text = load(mod)
        val changes = mutableMapOf(RealmModConfig.ENABLED_KEY to if (enabled) "1" else "0")
        if (enabled && mod.enableKeys.none { RealmModConfig.values(text)[it]?.lowercase() in setOf("1", "true") }) {
            changes.putAll(mod.enableKeys.associateWith { "1" })
        }
        save(mod, RealmModConfig.replace(text, changes))
    }

    fun install(destination: File, portableConfigs: Map<RealmModDefinition, String> = emptyMap()) {
        val configs = RealmMods.all.associateWith { mod ->
            val saved = File(directory, mod.configFile)
            val portable = RealmModConfig.merge(defaults(mod), portableConfigs[mod])
            RealmModConfig.merge(portable, if (saved.isFile) saved.readText() else null)
                .also { RealmModConfig.validate(mod, it, defaults(mod)) }
        }
        check(destination.isDirectory || destination.mkdirs()) { "Could not create module runtime config directory" }
        destination.listFiles()?.filter { it.isFile && (it.name.endsWith(".conf") || it.name.endsWith(".conf.dist")) }
            ?.forEach { check(it.delete()) { "Could not replace module config: ${it.name}" } }
        RealmModConfig.runtimeConfigs(configs).forEach { (mod, text) ->
            // AzerothCore requires the original .conf.dist before it loads the active .conf.
            File(destination, "${mod.configFile}.dist").writeText(RealmModConfig.merge(defaults(mod), null))
            File(destination, mod.configFile).writeText(text)
        }
    }
}
