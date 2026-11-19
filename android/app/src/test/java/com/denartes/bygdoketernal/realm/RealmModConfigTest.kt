package com.denartes.bygdoketernal.realm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RealmModConfigTest {
    private val mod = RealmMods.all.first()
    private val defaults = """
        [worldserver]
        # Keep the bundled documentation
        Account.Achievements.Enable = 1
        Account.Achievements.Excluded = 0
    """.trimIndent()

    @Test
    fun `settings survive store recreation runtime regeneration and updated bundled defaults`() {
        val directory = Files.createTempDirectory("realm-mod-settings").toFile()
        try {
            val store = RealmModConfigStore(directory) { defaults }
            store.setEnabled(mod, false)
            val updated = "$defaults\nNew.Setting = 25\n"
            val recreated = RealmModConfigStore(directory) { updated }
            val loaded = recreated.load(mod)
            assertFalse(RealmModConfig.enabled(mod, loaded))
            assertEquals("25", RealmModConfig.values(loaded)["New.Setting"])
            assertTrue(loaded.contains("# Keep the bundled documentation"))
            assertTrue(File(directory, mod.configFile).isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `playerbots dependency affects effective configs without losing user preferences`() {
        val configs = RealmMods.all.associateWith { definition ->
            "[worldserver]\n" + definition.enableKeys.joinToString("\n") { "$it = 1" } + "\n"
        }.toMutableMap()
        configs[RealmMods.playerbots] = "AiPlayerbot.Enabled = 0\n"
        val runtime = RealmModConfig.runtimeConfigs(configs)
        for (dependent in RealmMods.all.filter { it.requiresPlayerbots }) {
            assertFalse(RealmModConfig.enabled(dependent, runtime.getValue(dependent)))
            assertTrue(RealmModConfig.enabled(dependent, configs.getValue(dependent)))
        }
        configs[RealmMods.playerbots] = "AiPlayerbot.Enabled = 1\n"
        assertTrue(RealmModConfig.runtimeConfigs(configs)
            .filterKeys { it.requiresPlayerbots }.all { (mod, text) -> RealmModConfig.enabled(mod, text) })
    }

    @Test
    fun `disabled auction house and duel reset turn off both features`() {
        for (id in listOf("mod-ah-bot-plus", "mod-duel-reset")) {
            val definition = RealmMods.all.single { it.id == id }
            val text = definition.enableKeys.joinToString("\n") { "$it = 1" }
            val changed = RealmModConfig.replace(text, definition.enableKeys.associateWith { "0" })
            assertFalse(RealmModConfig.enabled(definition, changed))
            assertTrue(RealmModConfig.values(changed).values.all { it == "0" })
        }
    }

    @Test
    fun `master switch preserves independently configured auction buyer and seller`() {
        val directory = Files.createTempDirectory("realm-mod-switch").toFile()
        try {
            val auction = RealmMods.all.single { it.id == "mod-ah-bot-plus" }
            val store = RealmModConfigStore(directory) { definition ->
                definition.enableKeys.joinToString("\n") { "$it = ${if (it.endsWith("Buyer")) 0 else 1}" }
            }
            store.setEnabled(auction, false)
            assertFalse(RealmModConfig.enabled(auction, store.load(auction)))
            store.install(File(directory, "runtime"))
            val effective = File(directory, "runtime/${auction.configFile}").readText()
            assertTrue(RealmModConfig.values(effective).values.all { it == "0" })
            assertFalse(effective.contains(RealmModConfig.ENABLED_KEY))
            store.setEnabled(auction, true)
            val values = RealmModConfig.values(store.load(auction))
            assertEquals("1", values["AuctionHouseBot.EnableSeller"])
            assertEquals("0", values["AuctionHouseBot.EnableBuyer"])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `portable import configs cannot overwrite app saved choices`() {
        val directory = Files.createTempDirectory("realm-mod-import").toFile()
        try {
            val store = RealmModConfigStore(File(directory, "saved")) { definition ->
                definition.enableKeys.joinToString("\n") { "$it = 1" }
            }
            val runtime = File(directory, "runtime")
            store.setEnabled(mod, false)
            store.install(runtime, mapOf(mod to mod.enableKeys.joinToString("\n") { "$it = 1" }))
            assertFalse(RealmModConfig.enabled(mod, File(runtime, mod.configFile).readText()))
            assertFalse(RealmModConfig.enabled(mod, store.load(mod)))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `mod controls lock during startup import and active processes`() {
        assertTrue(RealmState().canEditMods)
        assertTrue(RealmState(phase = RealmPhase.DATABASE_READY, database = ComponentStatus.RUNNING).canEditMods)
        for (phase in RealmPhase.entries.filter { RealmState(phase = it).isBusy }) {
            assertFalse(RealmState(phase = phase).canEditMods)
        }
        assertFalse(RealmState(authserver = ComponentStatus.STARTING).canEditMods)
        assertFalse(RealmState(worldserver = ComponentStatus.RUNNING).canEditMods)
        RealmRuntime.beginStart()
        try {
            var saved = false
            try {
                RealmRuntime.editModSettings { saved = true }
                throw AssertionError("Should reject edits while starting")
            } catch (_: IllegalStateException) {
                assertFalse(saved)
            }
        } finally {
            RealmRuntime.resetToDefault()
        }
    }

    @Test
    fun `rejects invalid configs without replacing saved settings`() {
        val directory = Files.createTempDirectory("realm-mod-validation").toFile()
        try {
            val store = RealmModConfigStore(directory) { defaults }
            store.save(mod, defaults)
            val previous = File(directory, mod.configFile).readText()
            val invalid = listOf(
                defaults + "\nAccount.Achievements.Enable = 0",
                defaults.replace("Enable = 1", "Enable = maybe"),
                defaults.replace("Excluded = 0", "Excluded = not-a-number"),
                defaults + "\nBroken syntax",
                defaults + "\nPrompt = \"unclosed",
                defaults + "\n[authserver]",
                defaults + "\nPath = bad\u0000path",
                "Account.Achievements.Enable = 1\n"
            )
            for (text in invalid) {
                try {
                    store.save(mod, text)
                    throw AssertionError("Invalid config was accepted: $text")
                } catch (_: IllegalArgumentException) {
                    assertEquals(previous, File(directory, mod.configFile).readText())
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `parser supports empty values quotes comments and equals in strings`() {
        val settings = RealmModConfig.values("""
            [worldserver]
            Blank =
            Prompt = "text # inside = quoted" # trailing comment
            Url = http://localhost:11434/api/generate
        """.trimIndent())
        assertEquals("", settings["Blank"])
        assertEquals("\"text # inside = quoted\"", settings["Prompt"])
        assertEquals("http://localhost:11434/api/generate", settings["Url"])
        val changed = RealmModConfig.replace("X = 1\n", mapOf("X" to "2", "New.Key" to "\"value\""))
        assertEquals(mapOf("X" to "2", "New.Key" to "\"value\""), RealmModConfig.values(changed))
        assertEquals(1, changed.lineSequence().count { it == "[worldserver]" })
    }

    @Test
    fun `installation replaces stale aliases and always reapplies saved preferences`() {
        val directory = Files.createTempDirectory("realm-mod-install").toFile()
        try {
            val store = RealmModConfigStore(File(directory, "saved")) { definition ->
                definition.enableKeys.joinToString("\n") { "$it = 1" }
            }
            val runtime = File(directory, "runtime").apply { mkdirs() }
            File(runtime, "guildmate.conf.dist").writeText("GuildMate.Enable = 1")
            File(runtime, "old-module.conf").writeText("Old.Enable = 1")
            store.setEnabled(mod, false)
            repeat(2) {
                store.install(runtime)
                assertEquals(RealmMods.all.flatMap { listOf(it.configFile, "${it.configFile}.dist") }.toSet(), runtime.list()?.toSet())
                assertFalse(RealmModConfig.enabled(mod, File(runtime, mod.configFile).readText()))
                for (definition in RealmMods.all) {
                    val template = File(runtime, "${definition.configFile}.dist").readText()
                    assertTrue(template.startsWith("[worldserver]\n"))
                    assertTrue(RealmModConfig.enabled(definition, template))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `catalog exactly matches module manifest and all bundled configs validate`() {
        val repo = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "modules/manifest.json").isFile }
        val manifest = File(repo, "modules/manifest.json").readText()
        val ids = Regex(""""name"\s*:\s*"([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(ids, RealmMods.all.map { it.id }.toSet())
        assertEquals(15, RealmMods.all.size)
        for (definition in RealmMods.all) {
            val canonical = File(repo, "configs/modules/${definition.configFile}")
            val template = File(repo, "configs/modules/${definition.configFile}.dist")
            val source = if (template.isFile) template else File(repo, "modules/${definition.id}/conf/${definition.configFile}.dist")
            val text = RealmModConfig.merge(source.readText(), if (canonical.isFile) canonical.readText() else null)
            RealmModConfig.validate(definition, RealmModConfig.merge(text, null), text)
        }
    }
}
