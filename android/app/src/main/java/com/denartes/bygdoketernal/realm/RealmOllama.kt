package com.denartes.bygdoketernal.realm

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONObject

object RealmOllamaConfig {
    const val MANAGED = "Bygdok.Ollama.Managed"
    const val DOWNLOAD = "Bygdok.Ollama.AllowModelDownload"
    const val MODEL = "qwen2.5:0.5b"
    const val PORT = 11435
    const val BASE_URL = "http://127.0.0.1:$PORT"
    val requiredLibraries = listOf("libc++_shared.so", "libggml-base.so")

    fun managed(config: String): Boolean =
        RealmModConfig.values(config)[MANAGED]?.lowercase() in setOf("1", "true")

    fun quietPlayerbotsConfig(config: String): String = RealmModConfig.replace(config, mapOf(
        "Playerbots.RandomBotTalk" to "0",
        "Playerbots.EnableBroadcasts" to "0",
        "Playerbots.BroadcastChanceGuildManagement" to "0",
        "Playerbots.RandomBotSuggestDungeons" to "0",
        "Playerbots.RandomBotEmote" to "0",
        "Playerbots.EnableGreet" to "0",
        "Playerbots.InviteChat" to "0",
        "Playerbots.RandomBotSayWithoutMaster" to "0"
    ))

    fun downloadAllowed(config: String): Boolean =
        RealmModConfig.values(config)[DOWNLOAD]?.lowercase() in setOf("1", "true")

    fun selectedModel(config: String): String {
        val model = RealmModConfig.values(config)["LLMChatter.Model"]?.trim()?.trim('"').orEmpty()
        check(model.isNotEmpty()) { "Set LLMChatter.Model before starting on-device Ollama" }
        return if (':' in model.substringAfterLast('/')) model else "$model:latest"
    }

    fun selectManaged(config: String, enabled: Boolean): String = RealmModConfig.replace(
        config, mapOf(MANAGED to if (enabled) "1" else "0", DOWNLOAD to if (enabled) "1" else "0")
    )

    fun backendEnvironment(useVulkan: Boolean): Map<String, String> =
        if (useVulkan) mapOf("OLLAMA_VULKAN" to "1")
        else mapOf("OLLAMA_VULKAN" to "0", "GGML_VK_VISIBLE_DEVICES" to "")

    fun validSelfTestResponse(response: String): Boolean =
        Regex("(4|four)[.!]?", RegexOption.IGNORE_CASE).matches(response.trim())

    fun bridgeRuntimeConfig(config: String, runtimeDir: File): String {
        val logsDir = File(runtimeDir, "logs").apply { mkdirs() }
        val tempDir = File(runtimeDir, "tmp").apply { mkdirs() }
        return RealmModConfig.replace(config, mapOf(
            "LLMChatter.Database.Host" to "127.0.0.1",
            "LLMChatter.Database.Port" to "3306",
            "LLMChatter.Database.User" to "acore",
            "LLMChatter.Database.Password" to "acore",
            "LLMChatter.RuntimeDir" to tempDir.absolutePath,
            "LLMChatter.RequestLog.Path" to File(logsDir, "llm_requests.jsonl").absolutePath,
            "LLMChatter.HealthCheck.LogPath" to File(logsDir, "healthcheck.log").absolutePath
        ))
    }

    fun bridgeLogFile(runtimeDir: File): File = File(runtimeDir, "tmp/bridge.log")

    fun isolatedLibraryPath(root: File, nativeLibraryDir: File): File {
        val directory = File(root, "provider-libraries")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create Ollama library directory" }
        for (name in requiredLibraries) {
            val source = File(nativeLibraryDir, name)
            check(source.isFile) { "Required Ollama library is missing: $name" }
            val link = File(directory, name).toPath()
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.delete(link)
            Files.createSymbolicLink(link, source.toPath())
        }
        return directory
    }

    fun localRuntimeConfig(config: String): String = RealmModConfig.replace(config, mapOf(
        "LLMChatter.Provider" to "ollama",
        "LLMChatter.Model" to selectedModel(config),
        "LLMChatter.Ollama.BaseUrl" to BASE_URL,
        "LLMChatter.GroupChatter.PreCacheEnable" to "0"
    ))
}

/** Owns only the process it starts; never adopts an unrelated loopback server. */
class RealmOllama(
    private val root: File,
    private val nativeLibraryDir: File,
    private val log: (String) -> Unit,
    private val status: (ComponentStatus) -> Unit,
    private val driverStore: RealmOllamaDriverStore? = null,
    private val model: String
) {
    private val stopped = AtomicBoolean(false)
    private val connection = AtomicReference<HttpURLConnection?>()
    @Volatile private var process: Process? = null
    private var customDriver: RealmOllamaDriver? = null

    private suspend fun startServer(useVulkan: Boolean) {
        check(!stopped.get()) { "Ollama startup was stopped" }
        val binary = File(nativeLibraryDir, "libollama.so")
        check(binary.isFile) { "This APK does not contain the embedded Ollama runtime; rebuild the app" }
        root.mkdirs()
        val libraryPath = RealmOllamaConfig.isolatedLibraryPath(root, nativeLibraryDir)
        val models = File(root, "models").also { it.mkdirs() }
        val outputLog = File(root, "ollama.log")
        if (outputLog.length() > 2 * 1024 * 1024) outputLog.writeText("")
        // Avoid talking to a Termux instance or any other process already on this port.
        ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", RealmOllamaConfig.PORT)) }
        status(ComponentStatus.STARTING)
        val child = ProcessBuilder(binary.absolutePath, "serve")
            .directory(root)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(outputLog))
            .apply {
                environment().apply {
                    put("HOME", root.absolutePath)
                    put("TMPDIR", File(root, "tmp").also { it.mkdirs() }.absolutePath)
                    // Do not shadow Android's liblzma/libunwindstack dependencies with server libraries.
                    put("LD_LIBRARY_PATH", libraryPath.absolutePath)
                    put("OLLAMA_LIBRARY_PATH", nativeLibraryDir.absolutePath)
                    put("OLLAMA_HOST", "127.0.0.1:${RealmOllamaConfig.PORT}")
                    put("OLLAMA_MODELS", models.absolutePath)
                    put("OLLAMA_NO_CLOUD", "1")
                    put("OLLAMA_NUM_PARALLEL", "1")
                    put("OLLAMA_MAX_LOADED_MODELS", "1")
                    put("OLLAMA_MAX_QUEUE", "8")
                    put("OLLAMA_CONTEXT_LENGTH", "2048")
                    put("OLLAMA_KEEP_ALIVE", "5m")
                    put("OLLAMA_FLASH_ATTENTION", "0")
                    putAll(RealmOllamaConfig.backendEnvironment(useVulkan))
                    if (useVulkan) {
                        customDriver?.let { putAll(it.environment(nativeLibraryDir)) }
                    }
                }
            }.start()
        synchronized(this) {
            if (stopped.get()) {
                child.destroyForcibly()
                error("Ollama startup was stopped")
            }
            process = child
        }
        log("Starting embedded Ollama; native output: ${File(root, "ollama.log").absolutePath}")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (true) {
            coroutineContext.ensureActive()
            checkAlive()
            try {
                request("/api/version")
                break
            } catch (error: IOException) {
                if (System.nanoTime() >= deadline) throw IOException("Ollama readiness timed out", error)
            }
            delay(250)
        }
    }

    suspend fun start(allowDownload: Boolean) {
        customDriver = driverStore?.selected()
        customDriver?.let { log("Requesting imported Vulkan driver: ${it.name}") }
        startServer(useVulkan = true)
        val tags = JSONObject(request("/api/tags")).getJSONArray("models")
        val hasModel = (0 until tags.length()).any {
            tags.getJSONObject(it).getString("name") == model
        }
        if (!hasModel) {
            check(allowDownload) { "Local model is missing. Enable its download in Mods > LLM Chatter." }
            log("Downloading $model. Size depends on the selected model; partial downloads can resume on the next start.")
            pullModel()
        }
        try {
            verifyModel()
        } catch (error: IOException) {
            startCpuFallback(error)
        } catch (error: IllegalStateException) {
            startCpuFallback(error)
        }
        checkAlive()
        val running = JSONObject(request("/api/ps")).getJSONArray("models")
        val gpuActive = (0 until running.length()).any { running.getJSONObject(it).optLong("size_vram") > 0 }
        log(if (gpuActive) "Vulkan GPU offload active." else "CPU fallback active. See ollama.log for GPU discovery and any compatibility failure.")
        status(ComponentStatus.RUNNING)
        log("Embedded Ollama ready with $model")
    }

    private suspend fun startCpuFallback(error: Exception) {
        coroutineContext.ensureActive()
        check(!stopped.get()) { "Ollama startup was stopped" }
        log("GPU-first inference verification failed: ${error.message}. Restarting Ollama with Vulkan devices disabled and verifying CPU fallback.")
        stopProcess()
        startServer(useVulkan = false)
        verifyModel()
    }

    private fun verifyModel() {
        // Confirm that both the model and runner can load before enabling worldserver chat.
        log("Loading and verifying $model...")
        request("/api/generate", JSONObject()
            .put("model", model)
            .put("prompt", "What is two plus two? Answer with the number only.")
            .put("stream", false)
            .put("options", JSONObject().put("num_predict", 8).put("num_ctx", 2048)
                .put("num_thread", 2).put("temperature", 0))
            .toString(), readTimeout = 180_000).let {
                val response = JSONObject(it)
                check(!response.has("error") && response.optBoolean("done")) { "Ollama model warm-up failed: $it" }
                check(RealmOllamaConfig.validSelfTestResponse(response.optString("response"))) {
                    "Ollama inference self-test returned an incorrect answer; this runtime/device combination is not usable. See ollama.log."
                }
            }
    }

    suspend fun monitor() {
        while (!stopped.get()) {
            delay(1000)
            if (stopped.get()) return
            if (process?.isAlive != true) {
                status(ComponentStatus.FAILED)
                log("Embedded Ollama exited unexpectedly. Realm gameplay continues; see ollama.log.")
                return
            }
        }
    }

    private fun checkAlive() {
        check(!stopped.get() && process?.isAlive == true) { "Embedded Ollama stopped; see ollama.log" }
    }

    private fun open(path: String, body: String?, readTimeout: Int): HttpURLConnection {
        checkAlive()
        val http = URL(RealmOllamaConfig.BASE_URL + path).openConnection() as HttpURLConnection
        http.connectTimeout = 2_000
        http.readTimeout = readTimeout
        http.instanceFollowRedirects = false
        connection.set(http)
        if (stopped.get()) {
            http.disconnect()
            error("Ollama was stopped")
        }
        if (body != null) {
            http.requestMethod = "POST"
            http.doOutput = true
            http.setRequestProperty("Content-Type", "application/json")
            http.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return http
    }

    private fun request(path: String, body: String? = null, readTimeout: Int = 5_000): String {
        var http: HttpURLConnection? = null
        try {
            http = open(path, body, readTimeout)
            check(http.responseCode == 200) { "Ollama $path returned HTTP ${http.responseCode}" }
            return http.inputStream.bufferedReader().use { it.readText() }
        } finally {
            (http ?: connection.get())?.disconnect()
            connection.set(null)
        }
    }

    private suspend fun pullModel() {
        var http: HttpURLConnection? = null
        try {
            http = open("/api/pull", JSONObject().put("model", model).put("stream", true).toString(), 120_000)
            check(http.responseCode == 200) { "Ollama model download returned HTTP ${http.responseCode}" }
            var lastProgress = ""
            var success = false
            http.inputStream.bufferedReader().use { reader ->
                while (true) {
                    coroutineContext.ensureActive()
                    checkAlive()
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val message = JSONObject(line)
                    check(!message.has("error")) { "Model download failed: ${message.optString("error")}" }
                    val text = message.optString("status")
                    val total = message.optLong("total")
                    val progress = if (total > 0) "$text ${message.optLong("completed") * 100 / total}%" else text
                    if (progress != lastProgress) {
                        log(progress)
                        lastProgress = progress
                    }
                    if (text == "success") success = true
                }
            }
            check(success) { "Model download ended without success" }
        } finally {
            (http ?: connection.get())?.disconnect()
            connection.set(null)
        }
    }

    fun stop() {
        stopped.set(true)
        connection.getAndSet(null)?.disconnect()
        stopProcess()
        status(ComponentStatus.STOPPED)
    }

    private fun stopProcess() {
        val child = synchronized(this) { process.also { process = null } }
        if (child != null) {
            child.destroy()
            if (!child.waitFor(3, TimeUnit.SECONDS)) {
                child.destroyForcibly()
                child.waitFor(3, TimeUnit.SECONDS)
            }
        }
    }
}
