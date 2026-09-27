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
import java.io.RandomAccessFile
import com.denartes.bygdoketernal.realm.RealmPhase
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
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
    private var logTailJob: Job? = null

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
            clearNativeLogs()
            RealmConfigInstaller(this, paths).install()
            startNativeLogTail()

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
        RealmRuntime.appendLog("[realm] Database phase: starting MariaDB")
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

        val myCnf = File(paths.etcDir, "my.cnf")
        val process = ProcessBuilder(
            nativeBinary("libpocket_mariadbd.so"),
            "--defaults-file=${myCnf.absolutePath}",
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
            .also { configureMysqlEnvironment(it) }
            .start()
        mariadbProcess = process
        streamOutput(process, "mariadbd")
        monitorExit(process, "mariadbd") { ComponentStatus.FAILED }

        waitForTcpPort("127.0.0.1", 3306, timeoutMillis = 60_000) {
            mariadbProcess?.isAlive == true
        }
        RealmRuntime.appendLog("[realm] Database phase: MariaDB port 3306 ready")

        bootstrapDatabaseSchema()

        RealmRuntime.updateState { it.copy(database = ComponentStatus.RUNNING) }
    }

    private fun initializeDatabase(basedir: File) {
        val shareDir = File(basedir, "share")
        val bootstrapSql = File(basedir, "bootstrap.sql").readText()
        val myCnf = File(paths.etcDir, "my.cnf")
        runBlockingProcess(
            listOf(
                nativeBinary("libpocket_mariadbd.so"),
                "--defaults-file=${myCnf.absolutePath}",
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

        val schemaMarker = File(paths.dataDir, ".acore-schema-bootstrap-complete")
        val markerExists = schemaMarker.isFile
        val existingCoreSchema = !markerExists && hasCoreDatabaseSchema(clientBinary)
        if (!shouldImportDatabaseSchema(markerExists, existingCoreSchema)) {
            if (!markerExists) schemaMarker.writeText("existing schema detected\n")
            RealmRuntime.appendLog("[realm] Base database schema already initialized; skipping import")
            return
        }

        RealmRuntime.appendLog("[realm] Creating acore user and databases")
        val bootstrapSql = """
            DROP USER IF EXISTS 'acore'@'%';
            DROP USER IF EXISTS 'acore'@'localhost';
            DROP USER IF EXISTS 'acore'@'127.0.0.1';
            CREATE USER 'acore'@'%' IDENTIFIED BY 'acore';
            CREATE USER 'acore'@'localhost' IDENTIFIED BY 'acore';
            CREATE USER 'acore'@'127.0.0.1' IDENTIFIED BY 'acore';
            GRANT ALL PRIVILEGES ON *.* TO 'acore'@'%';
            GRANT ALL PRIVILEGES ON *.* TO 'acore'@'localhost';
            GRANT ALL PRIVILEGES ON *.* TO 'acore'@'127.0.0.1';
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

        val schemaBases = realmDatabaseBootstrapFiles(File(paths.sqlDir, "data/sql/base"))
        if (schemaBases.isEmpty()) {
            RealmRuntime.appendLog("[realm] No bundled base schema files were found; authserver will not have tables ready")
            return
        }

        for ((database, sqlFiles) in schemaBases) {
            for (sqlFile in sqlFiles) {
                if (!sqlFile.isFile || sqlFile.length() == 0L) continue
                RealmRuntime.appendLog("[db-schema:$database] Importing ${sqlFile.name}")
                runBlockingProcessFromFile(
                    listOf(
                        clientBinary.absolutePath,
                        "--no-defaults",
                        "-h", "127.0.0.1",
                        "-P", "3306",
                        "-u", "root",
                        "--database", database
                    ),
                    logPrefix = "db-schema:$database",
                    inputFile = sqlFile
                )
            }
        }
        schemaMarker.writeText("complete\n")
    }

    private fun hasCoreDatabaseSchema(clientBinary: File): Boolean {
        val query = """
            SELECT CASE WHEN
                EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'acore_auth' AND table_name = 'account')
                AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'acore_characters' AND table_name = 'characters')
                AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'acore_world' AND table_name = 'creature_template')
            THEN 'complete' ELSE 'missing' END;
        """.trimIndent()
        val process = ProcessBuilder(
            clientBinary.absolutePath,
            "--no-defaults",
            "-h", "127.0.0.1",
            "-P", "3306",
            "-u", "root",
            "--batch",
            "--skip-column-names",
            "-e", query
        )
            .directory(paths.root)
            .redirectErrorStream(true)
            .also { configureMysqlEnvironment(it) }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() == 0 && output.lineSequence().any { it.trim() == "complete" }
    }

    private suspend fun startAuthserver() {
        RealmRuntime.appendLog("[realm] Authserver phase: launching authserver")
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
            .also { configureMysqlEnvironment(it) }
            .start()
        authserverProcess = process
        streamOutput(process, "authserver")
        monitorExit(process, "authserver") { ComponentStatus.FAILED }

        waitForTcpPort("127.0.0.1", 3724, 60_000) { process.isAlive }
        RealmRuntime.appendLog("[realm] Authserver phase: port 3724 ready")
        RealmRuntime.updateState { it.copy(authserver = ComponentStatus.RUNNING) }
    }

    private suspend fun startWorldserver() {
        RealmRuntime.appendLog("[realm] Worldserver phase: launching worldserver")
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
            .also { configureMysqlEnvironment(it) }
            .start()
        worldserverProcess = process
        streamOutput(process, "worldserver")
        monitorExit(process, "worldserver") { ComponentStatus.FAILED }

        waitForTcpPort("127.0.0.1", 8085, 120_000) { process.isAlive }
        RealmRuntime.appendLog("[realm] Worldserver phase: port 8085 ready")
        RealmRuntime.updateState { it.copy(worldserver = ComponentStatus.RUNNING) }
        RealmRuntime.updateState { it.copy(phase = RealmPhase.RUNNING) }
        updateNotification("Realm running")
    }

    private fun stopRealm(resetState: Boolean = true) {
        logTailJob?.cancel()
        logTailJob = null
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

    private fun nativeBinary(name: String): String =
        RealmExecutableBinary.nativeLibraryPath(paths.nativeLibraryDir, name)

    private fun realmDatabaseBootstrapFiles(baseDir: File): Map<String, List<File>> {
        if (!baseDir.isDirectory) return emptyMap()
        val databaseNameMap = mapOf(
            "db_auth" to "acore_auth",
            "db_characters" to "acore_characters",
            "db_world" to "acore_world"
        )
        val filesByDb = linkedMapOf<String, MutableList<File>>()
        baseDir.listFiles()?.forEach { databaseDir ->
            if (!databaseDir.isDirectory) return@forEach
            val sqlFiles = databaseDir.listFiles { file -> file.isFile && file.extension == "sql" }
                ?.sortedBy { it.name }
                ?.toMutableList() ?: mutableListOf()
            if (sqlFiles.isNotEmpty()) {
                val runtimeDatabaseName = databaseNameMap[databaseDir.name] ?: databaseDir.name
                filesByDb[runtimeDatabaseName] = sqlFiles
            }
        }
        return filesByDb
    }

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

    private fun configureMysqlEnvironment(builder: ProcessBuilder) {
        val socketPath = paths.socketFile.absolutePath
        val env = builder.environment()
        env["LD_LIBRARY_PATH"] = databaseLibraryPath()
        env["MYSQL_UNIX_PORT"] = socketPath
        env["MYSQL_UNIX_ADDR"] = socketPath
        env["MARIADB_UNIX_PORT"] = socketPath
        env["MARIADB_UNIX_ADDR"] = socketPath
    }

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

    private fun startNativeLogTail() {
        logTailJob?.cancel()
        logTailJob = scope.launch {
            val files = listOf(
                "Auth.log" to "authserver-file",
                "Server.log" to "worldserver-file",
                "Errors.log" to "errors-file",
                "Playerbots.log" to "playerbots-file"
            )
            val positions = files.associate { (name, _) ->
                name to 0L
            }.toMutableMap()
            while (isActive) {
                for ((name, tag) in files) {
                    val file = File(paths.logsDir, name)
                    if (!file.isFile) continue
                    RandomAccessFile(file, "r").use { reader ->
                        val position = positions[name] ?: 0L
                        if (file.length() < position) {
                            positions[name] = 0L
                            reader.seek(0)
                        } else {
                            reader.seek(position)
                        }
                        var line = reader.readLine()
                        while (line != null) {
                            RealmRuntime.appendLog("[$tag] $line")
                            line = reader.readLine()
                        }
                        positions[name] = reader.filePointer
                    }
                }
                kotlinx.coroutines.delay(250)
            }
        }
    }

    private fun clearNativeLogs() {
        paths.logsDir.listFiles()?.forEach { it.delete() }
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
            .also { configureMysqlEnvironment(it) }
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

    private fun runBlockingProcessFromFile(command: List<String>, logPrefix: String, inputFile: File) {
        val process = ProcessBuilder(command)
            .directory(paths.root)
            .redirectErrorStream(true)
            .also { configureMysqlEnvironment(it) }
            .start()

        inputFile.inputStream().use { input ->
            input.copyTo(process.outputStream, bufferSize = 64 * 1024)
        }
        process.outputStream.close()

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

        internal fun shouldImportDatabaseSchema(markerExists: Boolean, coreSchemaExists: Boolean): Boolean =
            !markerExists && !coreSchemaExists
    }
}
