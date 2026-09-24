package com.denartes.bygdoketernal.realm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.denartes.bygdoketernal.MainActivity
import java.io.File
import java.io.IOException
import com.denartes.bygdoketernal.realm.RealmPhase
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Foreground service that launches the embedded mariadbd, authserver, and
 * worldserver binaries directly from the app's native library directory,
 * with no external database or Termux dependency.
 *
 * This is the first, unvalidated-on-device implementation of the
 * "self-sufficient realm" orchestration; mariadbd startup flags, error
 * message file locations, and readiness detection are the most likely
 * areas to need adjustment once tested on a real device.
 */
class RealmForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var mariadbProcess: Process? = null
    private var authserverProcess: Process? = null
    private var worldserverProcess: Process? = null
    private var intentionalStop = false

    private lateinit var paths: RealmPaths

    override fun onCreate() {
        super.onCreate()
        paths = RealmPaths(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            intentionalStop = true
            stopRealm()
            return START_NOT_STICKY
        }

        intentionalStop = false
        startForeground(NOTIFICATION_ID, buildNotification("Starting realm..."))
        scope.launch { startRealm() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRealm()
        serviceJob.cancel()
        super.onDestroy()
    }

    private suspend fun startRealm() {
        try {
            paths.ensureDirectories()
            RealmConfigInstaller(this, paths).install()

            startDatabase()
            startAuthserver()
            RealmRuntime.updateState { it.copy(phase = RealmPhase.PREPARING_CLIENT_DATA) }
            RealmClientData.download(paths.clientDataDir) { RealmRuntime.appendLog("[client-data] $it") }
            startWorldserver()
        } catch (t: Throwable) {
            RealmRuntime.appendLog("[realm] Startup failed: ${t.message}")
            updateNotification("Realm failed to start")
            RealmRuntime.resetToDefault()
            stopRealm(resetState = false)
        }
    }

    private suspend fun startDatabase() {
        RealmRuntime.updateState { it.copy(phase = RealmPhase.STARTING_DATABASE) }
        RealmRuntime.updateState { it.copy(database = ComponentStatus.STARTING) }
        updateNotification("Starting database...")

        if (mariadbProcess?.isAlive == true) {
            RealmRuntime.updateState { it.copy(database = ComponentStatus.RUNNING) }
            return
        }
        mariadbProcess = null

        val basedir = paths.mariadbBaseDir
        installDatabaseLibraryLinks()
        val bootstrapMarker = File(paths.dataDir, ".bootstrap-complete")
        val firstRun = !bootstrapMarker.isFile
        if (firstRun) {
            RealmRuntime.appendLog("[mariadbd] Initializing data directory")
            try {
                initializeDatabase(basedir)
                bootstrapMarker.writeText("complete\n")
            } catch (error: Throwable) {
                File(paths.dataDir, "mysql").deleteRecursively()
                bootstrapMarker.delete()
                throw error
            }
        }

        val process = ProcessBuilder(
            nativeBinary("libpocket_mariadbd.so"),
            "--no-defaults",
            "--datadir=${paths.dataDir.absolutePath}",
            "--basedir=${basedir.absolutePath}",
            "--socket=${paths.socketFile.absolutePath}",
            "--pid-file=${paths.pidFile.absolutePath}",
            "--lc-messages-dir=${File(basedir, "share").absolutePath}",
            "--bind-address=127.0.0.1",
            "--port=3306"
        )
            .directory(paths.root)
            .redirectErrorStream(true)
            .also { it.environment()["LD_LIBRARY_PATH"] = databaseLibraryPath() }
            .start()
        mariadbProcess = process
        streamOutput(process, "mariadbd")
        monitorExit(process, "mariadbd") { ComponentStatus.FAILED }

        waitForTcpPort("127.0.0.1", 3306, timeoutMillis = 60_000) {
            mariadbProcess?.isAlive == true
        }

        if (firstRun) {
            bootstrapDatabaseSchema()
        }

        RealmRuntime.updateState { it.copy(database = ComponentStatus.RUNNING) }
    }

    private fun initializeDatabase(basedir: File) {
        val shareDir = File(basedir, "share")
        val bootstrapSql = File(basedir, "bootstrap.sql").readText()
        runBlockingProcess(
            listOf(
                nativeBinary("libpocket_mariadbd.so"),
                "--no-defaults",
                "--bootstrap",
                "--silent-startup",
                "--basedir=${basedir.absolutePath}",
                "--datadir=${paths.dataDir.absolutePath}",
                "--lc-messages-dir=${shareDir.absolutePath}",
                "--log-warnings=0",
                "--max-allowed-packet=8M",
                "--net-buffer-length=16K"
            ),
            logPrefix = "mariadbd-init",
            standardInput = bootstrapSql
        )
    }

    private fun bootstrapDatabaseSchema() {
        val clientBinary = File(paths.nativeLibraryDir, "libpocket_mariadb_client.so")
        if (!clientBinary.exists()) {
            RealmRuntime.appendLog("[realm] No embedded SQL client; skipping automatic user/database creation")
            return
        }
        RealmRuntime.appendLog("[realm] Creating acore user and databases")
        val bootstrapSql = """
            DROP USER IF EXISTS 'acore'@'%';
            CREATE USER 'acore'@'%' IDENTIFIED BY 'acore';
            GRANT ALL PRIVILEGES ON *.* TO 'acore'@'%';
            CREATE DATABASE IF NOT EXISTS acore_auth;
            CREATE DATABASE IF NOT EXISTS acore_world;
            CREATE DATABASE IF NOT EXISTS acore_characters;
            CREATE DATABASE IF NOT EXISTS acore_playerbots;
            FLUSH PRIVILEGES;
        """.trimIndent()
        runBlockingProcess(
            listOf(
                clientBinary.absolutePath,
                "--no-defaults",
                "-h", "127.0.0.1",
                "-P", "3306",
                "-u", "root",
                "-e", bootstrapSql
            ),
            logPrefix = "db-bootstrap"
        )
    }

    private suspend fun startAuthserver() {
        RealmRuntime.updateState { it.copy(phase = RealmPhase.STARTING_AUTHSERVER) }
        RealmRuntime.updateState { it.copy(authserver = ComponentStatus.STARTING) }
        updateNotification("Starting authserver...")

        if (authserverProcess?.isAlive == true) {
            RealmRuntime.updateState { it.copy(authserver = ComponentStatus.RUNNING) }
            return
        }
        authserverProcess = null

        val process = ProcessBuilder(
            nativeBinary("libauthserver.so"),
            "-c", paths.authserverConf.absolutePath
        )
            .directory(paths.etcDir)
            .redirectErrorStream(true)
            .also { it.environment()["LD_LIBRARY_PATH"] = databaseLibraryPath() }
            .start()
        authserverProcess = process
        streamOutput(process, "authserver")
        monitorExit(process, "authserver") { ComponentStatus.FAILED }

        waitForTcpPort("127.0.0.1", 3724, 60_000) { process.isAlive }
        RealmRuntime.updateState { it.copy(authserver = ComponentStatus.RUNNING) }
    }

    private suspend fun startWorldserver() {
        RealmRuntime.updateState { it.copy(phase = RealmPhase.STARTING_WORLDSERVER) }
        RealmRuntime.updateState { it.copy(worldserver = ComponentStatus.STARTING) }
        updateNotification("Starting worldserver...")

        if (worldserverProcess?.isAlive == true) {
            RealmRuntime.updateState {
                it.copy(worldserver = ComponentStatus.RUNNING, phase = RealmPhase.RUNNING)
            }
            return
        }
        worldserverProcess = null

        val process = ProcessBuilder(
            nativeBinary("libworldserver.so"),
            "-c", paths.worldserverConf.absolutePath
        )
            .directory(paths.etcDir)
            .redirectErrorStream(true)
            .also { it.environment()["LD_LIBRARY_PATH"] = databaseLibraryPath() }
            .start()
        worldserverProcess = process
        streamOutput(process, "worldserver")
        monitorExit(process, "worldserver") { ComponentStatus.FAILED }

        waitForTcpPort("127.0.0.1", 8085, 120_000) { process.isAlive }
        RealmRuntime.updateState { it.copy(worldserver = ComponentStatus.RUNNING) }
        RealmRuntime.updateState { it.copy(phase = RealmPhase.RUNNING) }
        updateNotification("Realm running")
    }

    private fun stopRealm(resetState: Boolean = true) {
        worldserverProcess?.destroyForcibly()
        authserverProcess?.destroyForcibly()
        mariadbProcess?.destroyForcibly()
        worldserverProcess = null
        authserverProcess = null
        mariadbProcess = null
        if (resetState) {
            RealmRuntime.updateState {
                it.copy(
                    database = ComponentStatus.STOPPED,
                    authserver = ComponentStatus.STOPPED,
                    worldserver = ComponentStatus.STOPPED
                )
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun nativeBinary(name: String): String = File(paths.nativeLibraryDir, name).absolutePath

    private fun installDatabaseLibraryLinks() {
        val manifest = JSONObject(File(paths.mariadbBaseDir, "runtime-manifest.json").readText())
        val links = manifest.getJSONArray("links")
        for (index in 0 until links.length()) {
            val link = links.getJSONObject(index)
            val logicalName = link.getString("logical")
            check(File(logicalName).name == logicalName) {
                "Invalid database runtime manifest entry"
            }
            val target = File(paths.mariadbLibDir, logicalName)
            check(target.isFile) { "Missing database runtime library: $logicalName" }
            check(target.isFile) { "Missing database runtime library: $logicalName" }
        }
    }

    private fun databaseLibraryPath(): String =
        "${paths.mariadbLibDir.absolutePath}:${paths.nativeLibraryDir}"

    private fun streamOutput(process: Process, tag: String) {
        scope.launch {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> RealmRuntime.appendLog("[$tag] $line") }
                }
            } catch (_: IOException) {
                // Process pipes can close while the service is stopping.
            }
        }
    }

    private fun monitorExit(process: Process, tag: String, onExit: () -> ComponentStatus) {
        scope.launch {
            val exitCode = process.waitFor()
            RealmRuntime.appendLog("[$tag] exited with code $exitCode")
            val status = onExit()
            when (tag) {
                "authserver" -> if (authserverProcess === process) authserverProcess = null
                "worldserver" -> if (worldserverProcess === process) worldserverProcess = null
                "mariadbd" -> if (mariadbProcess === process) mariadbProcess = null
            }
            if (!intentionalStop) {
                RealmRuntime.updateState {
                    when (tag) {
                        "authserver" -> it.copy(authserver = status, phase = if (status == ComponentStatus.FAILED) RealmPhase.FAILED else it.phase)
                        "worldserver" -> it.copy(worldserver = status, phase = if (status == ComponentStatus.FAILED) RealmPhase.FAILED else it.phase)
                        "mariadbd" -> it.copy(database = status, phase = if (status == ComponentStatus.FAILED) RealmPhase.FAILED else it.phase)
                        else -> it
                    }
                }
            }
        }
    }

    private fun runBlockingProcess(command: List<String>, logPrefix: String, standardInput: String? = null) {
        val process = ProcessBuilder(command)
            .directory(paths.root)
            .redirectErrorStream(true)
            .also { it.environment()["LD_LIBRARY_PATH"] = databaseLibraryPath() }
            .start()
        if (standardInput != null) {
            process.outputStream.bufferedWriter().use { it.write(standardInput) }
        } else {
            process.outputStream.close()
        }
        process.inputStream.bufferedReader().forEachLine { line ->
            RealmRuntime.appendLog("[$logPrefix] $line")
        }
        val exitCode = process.waitFor()
        check(exitCode == 0) { "$logPrefix exited with code $exitCode" }
    }

    private suspend fun waitForTcpPort(
        host: String,
        port: Int,
        timeoutMillis: Long,
        isProcessAlive: () -> Boolean = { true }
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            check(isProcessAlive()) { "Process exited before $host:$port became ready" }
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 1_000)
                }
                return
            } catch (_: Exception) {
                kotlinx.coroutines.delay(1_000)
            }
        }
        error("Timed out waiting for database on $host:$port")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Realm", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bygdok Eternal")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        const val ACTION_STOP = "com.denartes.bygdoketernal.realm.STOP"
        private const val CHANNEL_ID = "realm"
        private const val NOTIFICATION_ID = 1
    }
}
