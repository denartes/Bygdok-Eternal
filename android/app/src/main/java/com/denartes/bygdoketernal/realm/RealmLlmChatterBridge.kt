package com.denartes.bygdoketernal.realm

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class RealmLlmChatterBridge(
    context: Context,
    private val configFile: File,
    private val runtimeDir: File,
    private val log: (String) -> Unit
) {
    private val applicationContext = context.applicationContext
    @Volatile private var pythonModule: PyObject? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var stopping = false

    @Synchronized
    fun start() {
        check(worker?.isAlive != true) { "LLM Chatter bridge is already running" }
        check(configFile.isFile) { "LLM Chatter configuration is missing" }
        check(runtimeDir.isDirectory || runtimeDir.mkdirs()) { "Could not create LLM Chatter runtime directory" }
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        val module = Python.getInstance().getModule("bygdok_llm_chatter")
        module.callAttr("reset")
        pythonModule = module
        stopping = false
        worker = Thread({
            try {
                log("Starting embedded Python bridge")
                module.callAttr("run", configFile.absolutePath, runtimeDir.absolutePath)
                if (!stopping) log("LLM Chatter bridge exited")
            } catch (error: Exception) {
                if (!stopping) log("LLM Chatter bridge failed: ${error.message}")
            }
        }, "llm-chatter-bridge").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop(timeoutMillis: Long = STOP_TIMEOUT_MILLIS): Boolean {
        val running = worker ?: return true
        stopping = true
        pythonModule?.callAttr("request_stop")
        running.join(timeoutMillis)
        if (running.isAlive) {
            log("LLM Chatter bridge did not stop within ${timeoutMillis}ms")
            return false
        }
        worker = null
        pythonModule = null
        log("LLM Chatter bridge stopped")
        return true
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 20_000L
    }
}
