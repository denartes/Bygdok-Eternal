package com.denartes.bygdoketernal.realm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealmOllamaConfigTest {
    @Test
    fun `guild mode opts in and quiets native chatter without disabling bots`() {
        assertFalse(RealmOllamaConfig.guildRelationships("OllamaChat.Enable = 1"))
        assertTrue(RealmOllamaConfig.guildRelationships("OllamaChat.GuildRelationships.Enable = 1"))
        val saved = "AiPlayerbot.Enabled = 1\nAiPlayerbot.RandomBotTalk = 1\nAiPlayerbot.EnableBroadcasts = 1"
        val runtime = RealmModConfig.values(RealmOllamaConfig.quietPlayerbotsConfig(saved))
        assertEquals("1", runtime["AiPlayerbot.Enabled"])
        for (setting in listOf("RandomBotTalk", "EnableBroadcasts", "BroadcastChanceGuildManagement",
            "RandomBotSuggestDungeons", "RandomBotEmote", "EnableGreet", "InviteChat", "RandomBotSayWithoutMaster")) {
            assertEquals(setting, "0", runtime["AiPlayerbot.$setting"])
        }
        assertEquals("1", RealmModConfig.values(saved)["AiPlayerbot.RandomBotTalk"])
    }

    @Test
    fun `self test accepts correct numeric or written answers but rejects corrupt output`() {
        for (answer in listOf("4", " 4\n", "Four", "four.", "4!")) {
            assertTrue(answer, RealmOllamaConfig.validSelfTestResponse(answer))
        }
        for (answer in listOf("", "5", "fourteen", "4乱码", "four four", " robes\n\n", "I cannot answer")) {
            assertFalse(answer, RealmOllamaConfig.validSelfTestResponse(answer))
        }
    }

    @Test
    fun `Vulkan is preferred and CPU recovery hides all Vulkan devices`() {
        assertEquals(mapOf("OLLAMA_VULKAN" to "1"), RealmOllamaConfig.backendEnvironment(true))
        assertEquals(mapOf("OLLAMA_VULKAN" to "0", "GGML_VK_VISIBLE_DEVICES" to ""),
            RealmOllamaConfig.backendEnvironment(false))
    }

    private val remote = """
        [worldserver]
        OllamaChat.Enable = 1
        OllamaChat.Url = http://192.168.1.10:11434/api/generate
        OllamaChat.Model = remote-model
        OllamaChat.DungeonChat.Enable = 1
    """.trimIndent()

    @Test
    fun `managed runtime requires opt in and separate download consent`() {
        assertFalse(RealmOllamaConfig.managed(remote))
        assertFalse(RealmOllamaConfig.downloadAllowed(remote))
        val selected = RealmOllamaConfig.selectManaged(remote, true)
        assertTrue(RealmOllamaConfig.managed(selected))
        assertTrue(RealmOllamaConfig.downloadAllowed(selected))
        val restored = RealmOllamaConfig.selectManaged(selected, false)
        assertFalse(RealmOllamaConfig.managed(restored))
        assertFalse(RealmOllamaConfig.downloadAllowed(restored))
        assertEquals(RealmModConfig.values(remote), RealmModConfig.values(restored)
            .filterKeys { it != RealmOllamaConfig.MANAGED && it != RealmOllamaConfig.DOWNLOAD })
    }

    @Test
    fun `runtime override selects loopback and small model without changing saved preferences`() {
        val selected = RealmOllamaConfig.selectManaged(remote, true)
        val runtime = RealmModConfig.values(RealmOllamaConfig.localRuntimeConfig(selected))
        assertEquals("http://127.0.0.1:11435/api/generate", runtime["OllamaChat.Url"])
        assertEquals("qwen2.5:0.5b", runtime["OllamaChat.Model"])
        assertEquals("1", runtime["OllamaChat.MaxConcurrentQueries"])
        assertEquals("2048", runtime["OllamaChat.NumCtx"])
        assertEquals("1", runtime["OllamaChat.DungeonChat.Enable"])
        assertEquals("remote-model", RealmModConfig.values(selected)["OllamaChat.Model"])
    }

    @Test
    fun `model download can be disabled independently for offline operation`() {
        val selected = RealmModConfig.replace(RealmOllamaConfig.selectManaged(remote, true),
            mapOf(RealmOllamaConfig.DOWNLOAD to "0"))
        assertTrue(RealmOllamaConfig.managed(selected))
        assertFalse(RealmOllamaConfig.downloadAllowed(selected))
    }
}
