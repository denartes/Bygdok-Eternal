package com.denartes.bygdoketernal.realm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealmOllamaConfigTest {
    @Test
    fun `Ollama dependency path excludes server libraries that shadow Android Vulkan dependencies`() {
        assertEquals(listOf("libc++_shared.so", "libggml-base.so"), RealmOllamaConfig.requiredLibraries)
        assertFalse(RealmOllamaConfig.requiredLibraries.contains("liblzma.so"))
        val root = java.nio.file.Files.createTempDirectory("ollama-libraries-test").toFile()
        try {
            val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                RealmOllamaConfig.isolatedLibraryPath(root, java.io.File(root, "missing-native"))
            }
            assertTrue(error.message.orEmpty().contains("libc++_shared.so"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `native playerbots chatter is quieted without disabling bots`() {
        val saved = "Playerbots.Enabled = 1\nPlayerbots.RandomBotTalk = 1\nPlayerbots.EnableBroadcasts = 1"
        val runtime = RealmModConfig.values(RealmOllamaConfig.quietPlayerbotsConfig(saved))
        assertEquals("1", runtime["Playerbots.Enabled"])
        for (setting in listOf("RandomBotTalk", "EnableBroadcasts", "BroadcastChanceGuildManagement",
            "RandomBotSuggestDungeons", "RandomBotEmote", "EnableGreet", "InviteChat", "RandomBotSayWithoutMaster")) {
            assertEquals(setting, "0", runtime["Playerbots.$setting"])
        }
        assertEquals("1", RealmModConfig.values(saved)["Playerbots.RandomBotTalk"])
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
        LLMChatter.Enable = 1
        LLMChatter.Provider = openai_compatible
        LLMChatter.OpenAICompatible.BaseUrl = http://192.168.1.10:11434/v1
        LLMChatter.Model = remote-model
        LLMChatter.Database.Host = localhost
        LLMChatter.Database.Port = 3306
        LLMChatter.Database.User = root
        LLMChatter.Database.Password = acore
        LLMChatter.RuntimeDir =
        LLMChatter.RequestLog.Path = /logs/llm_requests.jsonl
        LLMChatter.HealthCheck.LogPath =
        LLMChatter.Ollama.BaseUrl = http://localhost:11434
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
    fun `runtime override selects local provider without changing saved preferences`() {
        val selected = RealmOllamaConfig.selectManaged(remote, true)
        val runtime = RealmModConfig.values(RealmOllamaConfig.localRuntimeConfig(selected))
        assertEquals("ollama", runtime["LLMChatter.Provider"])
        assertEquals("http://127.0.0.1:11435", runtime["LLMChatter.Ollama.BaseUrl"])
        assertEquals("remote-model:latest", runtime["LLMChatter.Model"])
        assertEquals("0", runtime["LLMChatter.GroupChatter.PreCacheEnable"])
        assertEquals("openai_compatible", RealmModConfig.values(selected)["LLMChatter.Provider"])
        assertEquals("remote-model", RealmModConfig.values(selected)["LLMChatter.Model"])
    }

    @Test
    fun `managed runtime respects selected model and normalizes only missing tags`() {
        for ((selected, expected) in listOf(
            "llama3.2:1b" to "llama3.2:1b",
            "\"llama3.2:1b\"" to "llama3.2:1b",
            "qwen2.5:0.5b" to "qwen2.5:0.5b",
            "llama3.2" to "llama3.2:latest",
            "owner/model" to "owner/model:latest",
            "localhost:5000/owner/model" to "localhost:5000/owner/model:latest"
        )) {
            val config = RealmModConfig.replace(remote, mapOf("LLMChatter.Model" to selected))
            assertEquals(expected, RealmOllamaConfig.selectedModel(config))
            assertEquals(expected, RealmModConfig.values(RealmOllamaConfig.localRuntimeConfig(config))["LLMChatter.Model"])
            assertEquals(selected, RealmModConfig.values(config)["LLMChatter.Model"])
        }
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            RealmOllamaConfig.selectedModel("LLMChatter.Model =\n")
        }
    }

    @Test
    fun `bridge runtime uses the embedded database account and app-private paths`() {
        val runtimeRoot = java.io.File("build/test-llm-chatter")
        val runtime = RealmModConfig.values(RealmOllamaConfig.bridgeRuntimeConfig(remote, runtimeRoot))
        assertEquals("127.0.0.1", runtime["LLMChatter.Database.Host"])
        assertEquals("acore", runtime["LLMChatter.Database.User"])
        assertEquals("acore", runtime["LLMChatter.Database.Password"])
        assertEquals(java.io.File(runtimeRoot, "tmp").absolutePath, runtime["LLMChatter.RuntimeDir"])
        assertEquals(java.io.File(runtime["LLMChatter.RuntimeDir"], "bridge.log").absolutePath,
            RealmOllamaConfig.bridgeLogFile(runtimeRoot).absolutePath)
        assertEquals(java.io.File(runtimeRoot, "logs/llm_requests.jsonl").absolutePath, runtime["LLMChatter.RequestLog.Path"])
    }

    @Test
    fun `model download can be disabled independently for offline operation`() {
        val selected = RealmModConfig.replace(RealmOllamaConfig.selectManaged(remote, true),
            mapOf(RealmOllamaConfig.DOWNLOAD to "0"))
        assertTrue(RealmOllamaConfig.managed(selected))
        assertFalse(RealmOllamaConfig.downloadAllowed(selected))
    }
}
