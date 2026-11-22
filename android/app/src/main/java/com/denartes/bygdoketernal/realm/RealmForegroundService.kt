package com.denartes.bygdoketernal.realm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import com.denartes.bygdoketernal.MainActivity
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import com.denartes.bygdoketernal.realm.RealmPhase
import com.denartes.bygdoketernal.realm.RealmNetworkSettings
import com.denartes.bygdoketernal.realm.RealmNetworkSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONException

private data class ProcessStartupSignal(val ready: Boolean, val exitCode: Int? = null)

private data class ProcessReadiness(
    val firstSignal: CompletableDeferred<ProcessStartupSignal> = CompletableDeferred(),
    val outputClosed: CompletableDeferred<Unit> = CompletableDeferred(),
    @Volatile var pid: Int? = null
)

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
    private val lifecycleMutex = Mutex()

    private var mariadbProcess: Process? = null
    private var authserverProcess: Process? = null
    private var worldserverProcess: Process? = null
    private var worldserverReadiness: ProcessReadiness? = null
    @Volatile private var ollama: RealmOllama? = null
    private var ollamaMonitorJob: Job? = null
    @Volatile private var llmChatterBridge: RealmLlmChatterBridge? = null
    @Volatile private var intentionalStop = false
    private var stopJob: Job? = null
    private var logTailJob: Job? = null

    private lateinit var paths: RealmPaths
    private var backupRecoveryFailed = false

    private val backupWorkspace: File
        get() = File(noBackupFilesDir, "realm-backup")

    private fun backupTransaction(): RealmBackupTransaction {
        val settings = RealmBackupSettings(this)
        return RealmBackupTransaction(filesDir, backupWorkspace, settings::read, settings::write)
    }

    override fun onCreate() {
        super.onCreate()
        paths = RealmPaths(this)
        createNotificationChannel()
        try {
            backupTransaction().recover()
        } catch (error: Exception) {
            backupRecoveryFailed = true
            RealmRuntime.appendLog("[backup] Restore recovery failed; original files retained: ${error.message}")
            RealmRuntime.updateState { it.copy(phase = RealmPhase.FAILED) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (backupRecoveryFailed) {
            startForeground(NOTIFICATION_ID, buildNotification("Realm restore recovery failed; data retained"))
            RealmRuntime.appendLog("[backup] Realm action blocked until restore recovery succeeds")
            return START_NOT_STICKY
        }
        if (RealmRuntime.state.value.phase in
            listOf(RealmPhase.EXPORTING_REALM, RealmPhase.IMPORTING_REALM)) {
            RealmRuntime.appendLog("[backup] Action blocked while backup or restore recovery is in progress")
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_EXPORT_REALM -> {
                val uri = intent.data
                if (uri == null || !RealmRuntime.state.value.canBackup || RealmRuntime.botAccounts.value.busy) {
                    RealmRuntime.appendLog("[backup] Stop all realm components before exporting a backup")
                    return START_NOT_STICKY
                }
                val previous = RealmRuntime.state.value
                RealmRuntime.updateState { it.copy(phase = RealmPhase.EXPORTING_REALM) }
                startForeground(NOTIFICATION_ID, buildNotification("Exporting full realm backup..."))
                scope.launch { lifecycleMutex.withLock { exportFullBackup(uri, previous) } }
                return START_NOT_STICKY
            }
            ACTION_START_REALM -> {
                if (stopJob?.isActive == true) return START_NOT_STICKY
                intentionalStop = false
                startForeground(NOTIFICATION_ID, buildNotification("Starting realm..."))
                scope.launch { lifecycleMutex.withLock { startRealm() } }
                return START_STICKY
            }
            ACTION_START_DATABASE_ONLY -> {
                if (stopJob?.isActive == true) return START_NOT_STICKY
                intentionalStop = false
                startForeground(NOTIFICATION_ID, buildNotification("Starting MariaDB..."))
                scope.launch { lifecycleMutex.withLock { startDatabaseOnly() } }
                return START_STICKY
            }
            ACTION_COMPLETE_INITIAL_SETUP -> {
                scope.launch {
                    completeInitialSetup(
                        intent.getStringExtra(EXTRA_REALM_NAME).orEmpty(),
                        intent.getStringExtra(EXTRA_REALM_ADDRESS).orEmpty(),
                        intent.getStringExtra(EXTRA_LOCAL_ADDRESS).orEmpty(),
                        intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
                        intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
                    )
                }
                return START_STICKY
            }
            ACTION_IMPORT_REALM -> {
                val archiveUri = intent.data
                if (archiveUri == null) {
                    RealmRuntime.appendLog("[import] No backup archive was selected")
                } else {
                    if (RealmRuntime.state.value.isBusy || RealmRuntime.botAccounts.value.busy) {
                        RealmRuntime.appendLog("[import] Wait for the current realm operation to finish")
                        return START_NOT_STICKY
                    }
                    startForeground(NOTIFICATION_ID, buildNotification("Preparing realm import..."))
                    scope.launch { lifecycleMutex.withLock { importRealm(archiveUri) } }
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                if (stopJob?.isActive == true) return START_NOT_STICKY
                val graceful = RealmRuntime.state.value.worldserver == ComponentStatus.RUNNING
                intentionalStop = true
                RealmRuntime.updateState { it.copy(phase = RealmPhase.STOPPING) }
                updateNotification("Stopping realm...")
                if (graceful || RealmRuntime.botAccounts.value.busy) {
                    stopJob = scope.launch {
                        lifecycleMutex.withLock {
                            try {
                                stopWorldserverGracefully()
                            } finally {
                                stopRealm()
                            }
                        }
                    }
                } else {
                    stopRealm()
                }
                return START_NOT_STICKY
            }
            ACTION_BOT_ACCOUNTS -> {
                if (RealmRuntime.botAccounts.value.busy) return START_STICKY
                RealmRuntime.setBotAccounts(RealmRuntime.botAccounts.value.copy(busy = true, error = null, message = null))
                startForeground(NOTIFICATION_ID, buildNotification("Managing bot accounts..."))
                scope.launch {
                    lifecycleMutex.withLock {
                        manageBotAccounts(
                            intent.getStringExtra(EXTRA_OPERATION).orEmpty(),
                            intent.getIntExtra(EXTRA_ACCOUNT_ID, 0),
                            intent.getIntExtra(EXTRA_TARGET_ACCOUNT_ID, 0)
                        )
                    }
                }
                return START_STICKY
            }
            ACTION_CONSOLE_COMMAND -> {
                scope.launch { sendConsoleCommand(intent.getStringExtra(EXTRA_COMMAND).orEmpty()) }
                return START_STICKY
            }
            ACTION_REFRESH_ACCOUNTS -> {
                scope.launch { refreshAccounts() }
                return START_STICKY
            }
            ACTION_CREATE_ACCOUNT -> {
                scope.launch {
                    createAccount(
                        intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
                        intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                        intent.getBooleanExtra(EXTRA_GM, false)
                    )
                }
                return START_STICKY
            }
            ACTION_SET_ACCOUNT_GM_LEVEL -> {
                scope.launch {
                    setAccountGmLevel(
                        intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
                        intent.getIntExtra(EXTRA_GM_LEVEL, 0)
                    )
                }
                return START_STICKY
            }
            ACTION_LOAD_NETWORK_SETTINGS -> {
                scope.launch { loadRealmNetworkSettings() }
                return START_STICKY
            }
            ACTION_SAVE_NETWORK_SETTINGS -> {
                scope.launch {
                    saveRealmNetworkSettings(
                        intent.getStringExtra(EXTRA_REALM_ADDRESS).orEmpty(),
                        intent.getStringExtra(EXTRA_LOCAL_ADDRESS).orEmpty()
                    )
                }
                return START_STICKY
            }
            null -> {
                intentionalStop = false
                startForeground(NOTIFICATION_ID, buildNotification("Restoring MariaDB..."))
                scope.launch { lifecycleMutex.withLock { startDatabaseOnly() } }
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stopRealm()
        serviceJob.cancel()
        if (RealmRuntime.botAccounts.value.busy) {
            val message = "Database management was interrupted. Reload before continuing."
            RealmRuntime.appendLog("[bot-accounts] $message")
            RealmRuntime.setBotAccounts(BotAccountState(error = message))
        }
        super.onDestroy()
    }

    private suspend fun manageBotAccounts(operation: String, account: Int, target: Int) {
        try {
            check(RealmBotAccounts.canManage(
                RealmRuntime.state.value, authserverProcess?.isAlive == true, worldserverProcess?.isAlive == true
            )) {
                "Stop the realm before managing bot accounts or guilds"
            }
            if (mariadbProcess?.isAlive != true) {
                intentionalStop = false
                startDatabaseOnly()
            }
            check(mariadbProcess?.isAlive == true && RealmRuntime.state.value.database == ComponentStatus.RUNNING) {
                "MariaDB could not be started. See Console for details."
            }
            val before = RealmBotAccounts.parse(runAuthDatabaseQuery(RealmBotAccounts.snapshotQuery))
            if (operation != RealmBotAccounts.LOAD) {
                runAuthDatabaseQuery(RealmBotAccounts.mutation(operation, account, target, before))
            }
            val snapshot = if (operation == RealmBotAccounts.LOAD) before else {
                RealmBotAccounts.parse(runAuthDatabaseQuery(RealmBotAccounts.snapshotQuery))
            }
            RealmBotAccounts.verify(operation, account, target, snapshot)
            RealmRuntime.setBotAccounts(BotAccountState(snapshot = snapshot, message =
                if (operation == RealmBotAccounts.LOAD) "Database loaded. Game servers remain stopped."
                else "Saved. Changes take effect on the next realm start."))
            updateNotification("MariaDB ready; game servers stopped")
        } catch (cancelled: CancellationException) {
            RealmRuntime.setBotAccounts(BotAccountState(error = "Bot account operation was interrupted. Reload before continuing."))
            throw cancelled
        } catch (error: Exception) {
            val message = "Bot account operation failed: ${error.message}"
            RealmRuntime.appendLog("[bot-accounts] $message")
            RealmRuntime.setBotAccounts(BotAccountState(error = message))
        }
    }

    private suspend fun startRealm() {
        try {
            RealmRuntime.updateState { it.copy(phase = RealmPhase.STARTING_DATABASE) }
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
            stopRealm(resetState = false)
            RealmRuntime.resetToDefault()
        }
    }

    private suspend fun startDatabaseOnly() {
        if (authserverProcess?.isAlive == true || worldserverProcess?.isAlive == true) {
            RealmRuntime.appendLog("[setup] Stop the realm before starting database-only setup")
            return
        }
        try {
            RealmRuntime.updateState { it.copy(phase = RealmPhase.STARTING_DATABASE) }
            paths.ensureDirectories()
            clearNativeLogs()
            RealmConfigInstaller(this, paths).install()
            startNativeLogTail()
            startDatabase()
            RealmRuntime.updateState { it.copy(phase = RealmPhase.DATABASE_READY) }
            updateNotification("MariaDB ready for first-run setup")
        } catch (error: Throwable) {
            val nativeFailure = RealmRuntime.logLines.value
                .lastOrNull {
                    (it.startsWith("[mariadbd]") || it.startsWith("[mariadbd-init]")) &&
                        !it.contains("ready for connections", ignoreCase = true) &&
                        !it.contains("exited with code", ignoreCase = true)
                }
            val detail = nativeFailure?.let { "; $it" }.orEmpty()
            RealmRuntime.appendLog("[setup] MariaDB startup failed: ${error.message}$detail")
            RealmRuntime.updateState { it.copy(phase = RealmPhase.FAILED, database = ComponentStatus.FAILED) }
            updateNotification("MariaDB failed to start")
            stopRealm(resetState = false)
        }
    }

    private fun requireStoppedBackupProcesses() {
        check(listOf(mariadbProcess, authserverProcess, worldserverProcess).none { it?.isAlive == true } &&
            ollama == null && llmChatterBridge == null) { "Stop all realm components before backup or restore" }
        for (port in listOf(3306, 3724, 8085, RealmOllamaConfig.PORT)) {
            java.net.ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress("127.0.0.1", port))
            }
        }
    }

    private fun exportFullBackup(uri: android.net.Uri, previous: RealmState) {
        try {
            requireStoppedBackupProcesses()
            val settings = RealmBackupSettings(this).read()
            check(settings.getBoolean("setup_complete")) { "Complete realm setup before exporting a backup" }
            RealmRuntime.appendLog("[backup] Export started; includes databases, client data, AI models, drivers and settings")
            val binary = File(paths.nativeLibraryDir, "libpocket_mariadbd.so")
            val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            checkNotNull(contentResolver.openOutputStream(uri, "wt")) { "Could not open backup destination" }.use { output ->
                RealmFullBackup.export(filesDir, output, settings, RealmFullBackup.fingerprint(binary), version) {
                    RealmRuntime.appendLog("[backup] $it")
                    updateNotification("Exporting backup: $it")
                }
            }
            RealmRuntime.appendLog("[backup] Full realm backup exported successfully")
            updateNotification("Full realm backup complete")
        } catch (error: Exception) {
            RealmRuntime.appendLog("[backup] Export failed; do not use the incomplete archive: ${error.message}")
            try {
                check(android.provider.DocumentsContract.deleteDocument(contentResolver, uri)) {
                    "Document provider did not delete the incomplete backup"
                }
            } catch (cleanup: Exception) {
                RealmRuntime.appendLog("[backup] Could not delete incomplete archive; remove it manually: ${cleanup.message}")
            }
            updateNotification("Realm backup failed")
        } finally {
            RealmRuntime.updateState { previous }
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun restoreFullBackup(archive: File, previous: RealmState) {
        check(previous.canBackup) { "Stop all realm components before restoring a full backup" }
        requireStoppedBackupProcesses()
        backupTransaction().recover()
        check(backupWorkspace.isDirectory || backupWorkspace.mkdirs()) { "Could not create restore workspace" }
        val staging = File(backupWorkspace, "staging")
        check(!staging.exists() || staging.deleteRecursively()) { "Could not clear previous restore staging" }
        try {
            updateNotification("Validating complete realm backup...")
            var lastProgress = 0L
            val settings = RealmFullBackup.extract(archive, staging,
                RealmFullBackup.fingerprint(File(paths.nativeLibraryDir, "libpocket_mariadbd.so"))) {
                val now = System.currentTimeMillis()
                if (now - lastProgress >= 1000) {
                    updateNotification(it)
                    lastProgress = now
                }
            }
            RealmBackupSettings(this).validate(settings)
            check(settings.getBoolean("setup_complete")) { "Backup does not contain a completed realm setup" }
            RealmRuntime.appendLog("[import] Full backup checksums validated; replacing stopped realm")
            backupTransaction().install(staging, settings)
            RealmRuntime.resetToDefault()
            RealmRuntime.onBackupRestored()
            RealmRuntime.appendLog("[import] Full realm restored, including client data, AI models, drivers and saved settings")
            updateNotification("Full realm restored; start the realm")
        } finally {
            if (!File(backupWorkspace, "journal.json").exists()) {
                check(!staging.exists() || staging.deleteRecursively()) { "Could not remove restore staging" }
            }
        }
    }

    private suspend fun importRealm(archiveUri: android.net.Uri) {
        RealmRuntime.setBotAccounts(BotAccountState())
        val stateBeforeImport = RealmRuntime.state.value
        RealmRuntime.updateState { it.copy(phase = RealmPhase.IMPORTING_REALM) }
        RealmRuntime.appendLog("[import] Import started; validating archive")
        val importRoot = File(paths.root, "import")
        val preparedDir = File(importRoot, "prepared")
        val archive = File(importRoot, "backup.zip")
        val previousData = File(importRoot, "previous-data")
        val currentNetworkSettings = RealmNetworkSettingsStore(this).load()
        val currentRealmName = RealmSetupStore(this).realmName()
        try {
            paths.ensureDirectories()
            importRoot.deleteRecursively()
            importRoot.mkdirs()
            contentResolver.openInputStream(archiveUri).use { input ->
                checkNotNull(input) { "Could not open selected backup" }
                archive.outputStream().use { output -> input.copyTo(output) }
            }
            if (RealmFullBackup.isFullBackup(archive)) {
                restoreFullBackup(archive, stateBeforeImport)
                return
            }
            val prepared = RealmBackupImporter.extractAndValidate(archive, preparedDir)
            RealmRuntime.appendLog("[import] Archive validated; stopping realm processes")
            intentionalStop = true
            stopProcessesOnly()
            intentionalStop = false

            renameForImport(paths.dataDir, previousData)
            try {
                paths.dataDir.mkdirs()
                RealmConfigInstaller(this, paths).install()
                startForeground(NOTIFICATION_ID, buildNotification("Restoring realm databases..."))
                startDatabase()
                restoreBackupDatabases(prepared)
                markImportedDatabaseSchemas(prepared)
                val portableModuleConfigs = portableModuleConfigs(prepared)
                startAuthserver()
                startWorldserver(portableModuleConfigs, startAiBridge = false)
                reapplyCurrentRealmConfiguration(currentRealmName, currentNetworkSettings)
                refreshRealmNetworkSettings()
                val importedSettings = validateImportedRealm()
                intentionalStop = true
                stopProcessesOnly()
                intentionalStop = false
                previousData.deleteRecursively()
                RealmSetupStore(this).markComplete(importedSettings.realmName)
                RealmRuntime.setSetupComplete(true, importedSettings.realmName)
                RealmRuntime.resetToDefault()
                RealmRuntime.appendLog("[import] Realm import completed successfully")
                updateNotification("Realm import complete; start the realm")
            } catch (error: Throwable) {
                RealmRuntime.appendLog("[import] Import failed: ${error.message}")
                intentionalStop = true
                stopProcessesOnly()
                paths.dataDir.deleteRecursively()
                renameForImport(previousData, paths.dataDir)
                intentionalStop = false
                RealmRuntime.resetToDefault()
                updateNotification("Realm import failed; previous realm restored")
            }
        } catch (error: Throwable) {
            RealmRuntime.appendLog("[import] Import rejected: ${error.message}")
            if (File(backupWorkspace, "journal.json").exists()) {
                backupRecoveryFailed = true
                RealmRuntime.appendLog("[backup] Restore rollback needs recovery; realm actions are blocked until app restart")
            }
            RealmRuntime.updateState { stateBeforeImport }
            updateNotification("Realm import rejected")
        } finally {
            importRoot.deleteRecursively()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun renameForImport(source: File, destination: File) {
        if (!source.exists()) return
        check(source.renameTo(destination)) { "Could not move realm data for import" }
    }

    private fun restoreBackupDatabases(prepared: PreparedRealmBackup) {
        val clientBinary = File(paths.nativeLibraryDir, "libpocket_mariadb_client.so")
        for (database in RealmBackupImporter.requiredDatabases) {
            val dump = prepared.databases[database] ?: error("Missing prepared dump: $database")
            RealmRuntime.appendLog("[import] Restoring $database")
            runBlockingProcessFromFile(
                listOf(
                    clientBinary.absolutePath,
                    "--no-defaults", "-h", "127.0.0.1", "-P", "3306", "-u", "root",
                    "--database", database
                ),
                "import:$database",
                dump
            )
        }
    }

    private fun markImportedDatabaseSchemas(prepared: PreparedRealmBackup) {
        File(paths.dataDir, ".acore-schema-bootstrap-complete").writeText("imported backup\n")
        for (batch in realmModuleDatabaseBootstrapFiles(File(paths.sqlDir, "modules"))) {
            File(paths.dataDir, ".module-base-${batch.moduleName}-${batch.database}.complete")
                .writeText("imported backup\n")
        }
    }

    private fun portableModuleConfigs(prepared: PreparedRealmBackup): Map<RealmModDefinition, String> {
        val configs = mutableMapOf<RealmModDefinition, String>()
        for (source in prepared.moduleConfigs) {
            val filename = source.name
            if (!filename.matches(Regex("[A-Za-z0-9._-]+\\.conf"))) continue
            val mod = RealmMods.all.find { it.configFile == filename }
            if (mod == null) {
                if (filename == "mod_ollama_chat.conf") {
                    val chatter = RealmMods.all.single { it.id == "mod-llm-chatter" }
                    if (chatter !in configs) {
                        configs[chatter] = RealmModConfig.migrateLegacyOllamaChat(source.readText())
                    }
                    RealmRuntime.appendLog("[import] Migrating legacy Ollama Chat settings to LLM Chatter")
                    continue
                }
                RealmRuntime.appendLog("[import] Skipping config for module not bundled in APK: $filename")
                continue
            }
            configs[mod] = source.readText()
            RealmRuntime.appendLog("[import] Applying portable config with app-saved settings taking priority: $filename")
        }
        if (prepared.moduleData.isNotEmpty()) {
            RealmRuntime.appendLog("[import] Module data files were validated but are not auto-installed")
        }
        return configs
    }

    private fun reapplyCurrentRealmConfiguration(
        realmName: String,
        settings: RealmNetworkSettings
    ) {
        if (!isValidRealmAddress(settings.realmAddress) || !isValidRealmAddress(settings.localAddress)) {
            RealmRuntime.appendLog("[import] No valid existing network settings; retaining imported addresses")
            return
        }
        val escapedName = realmName.replace("'", "''")
        val escapedAddress = settings.realmAddress.replace("'", "''")
        val escapedLocal = settings.localAddress.replace("'", "''")
        runAuthDatabaseQuery(
            "UPDATE realmlist SET name = '$escapedName', address = '$escapedAddress', localAddress = '$escapedLocal' WHERE id = 1;"
        )
        RealmNetworkSettingsStore(this).save(settings)
        RealmRuntime.setNetworkSettings(settings)
        RealmRuntime.appendLog("[import] Reapplied APK realm name and network settings")
    }

    private fun validateImportedRealm(): PersistedRealmSettings {
        val settings = readRealmDatabaseSettings()
        check(settings.realmName.isNotBlank()) { "Imported realm has no name" }
        check(isValidRealmAddress(settings.realmAddress)) { "Imported realm address is invalid" }
        check(isValidRealmAddress(settings.localAddress)) { "Imported local address is invalid" }
        val output = runAuthDatabaseQuery("SELECT COUNT(*) FROM account;")
        check(output.lineSequence().any { it.trim().toIntOrNull()?.let { count -> count > 0 } == true }) {
            "Imported auth database contains no accounts"
        }
        val transmogCount = runAuthDatabaseQuery(
            "SELECT COUNT(*) FROM acore_characters.mod_transmog_plus_appearances;"
        )
        val transmogTables = runAuthDatabaseQuery(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'acore_characters' AND table_name IN ('mod_transmog_plus', 'mod_transmog_plus_appearances') ORDER BY table_name;"
        ).lineSequence().filter { it.isNotBlank() }.toList()
        check(transmogTables.contains("mod_transmog_plus_appearances")) {
            "Imported Transmog Plus appearance table is missing"
        }
        RealmRuntime.appendLog("[import] Transmog appearance rows: ${transmogCount.trim()}")
        return settings
    }

    private fun completeInitialSetup(
        realmName: String,
        realmAddress: String,
        localAddress: String,
        username: String,
        password: String
    ) {
        val name = realmName.trim()
        val address = realmAddress.trim()
        val local = localAddress.trim()
        val accountName = username.trim().uppercase(java.util.Locale.ROOT)
        if (!isValidRealmName(name)) {
            RealmRuntime.appendLog("[setup] Realm name must be 1-32 characters without control characters")
            return
        }
        if (!isValidRealmAddress(address) || !isValidRealmAddress(local)) {
            RealmRuntime.appendLog("[setup] Enter a valid IPv4 address or hostname for both addresses")
            return
        }
        if (!accountName.matches(Regex("[A-Z0-9_]{3,16}")) ||
            password.length !in 3..16 || password.any { it.code !in 33..126 }
        ) {
            RealmRuntime.appendLog("[setup] GM username must be 3-16 letters, digits, or underscores; password must be 3-16 printable ASCII characters")
            return
        }
        if (mariadbProcess?.isAlive != true) {
            RealmRuntime.appendLog("[setup] Start MariaDB before saving first-run setup")
            return
        }
        if (authserverProcess?.isAlive == true || worldserverProcess?.isAlive == true) {
            RealmRuntime.appendLog("[setup] Stop authserver and worldserver before saving first-run setup")
            return
        }

        RealmRuntime.updateState { it.copy(phase = RealmPhase.CONFIGURING_INITIAL_SETUP) }
        try {
            readRealmDatabaseSettings()
            val credentials = RealmAccountCredentialGenerator.generate(accountName, password)
            val escapedName = name.replace("'", "''")
            val setupSql = """
                START TRANSACTION;
                INSERT INTO account (username, salt, verifier)
                VALUES ('$accountName', UNHEX('${credentials.saltHex}'), UNHEX('${credentials.verifierHex}'));
                SET @initial_account_id = LAST_INSERT_ID();
                INSERT INTO account_access (id, gmlevel, RealmID, comment)
                VALUES (@initial_account_id, 3, -1, 'Initial GM account');
                UPDATE realmlist
                SET name = '$escapedName', address = '$address', localAddress = '$local'
                WHERE id = 1;
                COMMIT;
            """.trimIndent()
            runAuthDatabaseQuery(setupSql)
            val persistedSettings = readRealmDatabaseSettings()
            check(
                persistedSettings.realmName == name &&
                    persistedSettings.realmAddress == address &&
                    persistedSettings.localAddress == local
            ) { "Saved realm settings could not be verified" }

            val networkSettings = RealmNetworkSettings(address, local)
            RealmNetworkSettingsStore(this).save(networkSettings)
            RealmSetupStore(this).markComplete(persistedSettings.realmName)
            RealmRuntime.setNetworkSettings(networkSettings)
            RealmRuntime.setSetupComplete(true, persistedSettings.realmName)
            RealmRuntime.updateState { it.copy(phase = RealmPhase.DATABASE_READY) }
            RealmRuntime.appendLog("[setup] Realm '$name' and initial GM account '$accountName' created")
            updateNotification("First-run setup complete; MariaDB is ready")
        } catch (error: Exception) {
            RealmRuntime.updateState { it.copy(phase = RealmPhase.DATABASE_READY) }
            RealmRuntime.appendLog("[setup] Failed to save first-run setup: ${error.message}")
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
        val readiness = ProcessReadiness()
        streamOutput(process, "mariadbd", readiness)
        monitorExit(process, "mariadbd", readiness) { ComponentStatus.FAILED }

        awaitProcessReadiness(process, "mariadbd", readiness, DATABASE_STARTUP_WATCHDOG_MS)
        RealmRuntime.appendLog("[realm] Database phase: MariaDB readiness event received")

        bootstrapDatabaseSchema()
        applySavedRealmNetworkSettings()
        refreshRealmNetworkSettings()

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
            bootstrapModuleDatabaseSchemas(clientBinary)
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
        bootstrapModuleDatabaseSchemas(clientBinary)
    }

    private fun bootstrapModuleDatabaseSchemas(clientBinary: File) {
        for (batch in realmModuleDatabaseBootstrapFiles(File(paths.sqlDir, "modules"))) {
            val marker = File(paths.dataDir, ".module-base-${batch.moduleName}-${batch.database}.complete")
            if (batch.moduleName == "mod-guild-mate" &&
                databaseTableExists(clientBinary, batch.database, "guildmate_excluded_accounts")) {
                marker.writeText("existing schema detected\n")
                continue
            }
            val validatesTransmogSchema = requiresTransmogSchemaTableCheck(batch.moduleName, batch.database)
            val transmogSchemaReady = !validatesTransmogSchema ||
               databaseTableExists(clientBinary, batch.database, "mod_transmog_plus_appearances")
            val skipBase = shouldSkipModuleDatabaseBootstrap(marker.isFile, transmogSchemaReady)
            val sqlFiles = if (skipBase) emptyList() else batch.sqlFiles
            if (sqlFiles.isEmpty()) continue
            if (batch.moduleName == "mod-playerbots" &&
                databaseTableExists(clientBinary, batch.database, "playerbots_account_keys")
            ) {
                RealmRuntime.appendLog("[db-module:${batch.database}] Base schema already populated; skipping import")
                marker.writeText("existing schema detected\n")
                continue
            }

            for (sqlFile in sqlFiles) {
                if (sqlFile.length() == 0L) continue
                RealmRuntime.appendLog("[db-module:${batch.database}] Importing ${sqlFile.name}")
                runBlockingProcessFromFile(
                    listOf(
                        clientBinary.absolutePath,
                        "--no-defaults",
                        "-h", "127.0.0.1",
                        "-P", "3306",
                        "-u", "root",
                        "--database", batch.database
                    ),
                    logPrefix = "db-module:${batch.database}",
                    inputFile = sqlFile
                )
            }
            marker.writeText("complete\n")
            if (validatesTransmogSchema) {
                check(databaseTableExists(clientBinary, batch.database, "mod_transmog_plus_appearances")) {
                    "Transmog Plus base SQL did not create mod_transmog_plus_appearances"
                }
            }
        }
    }

    private fun databaseTableExists(clientBinary: File, database: String, table: String): Boolean =
        databaseSchemaExists(clientBinary,
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = '$database' AND table_name = '$table');")

    private fun databaseSchemaExists(clientBinary: File, query: String): Boolean {
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
        return process.waitFor() == 0 && output.lineSequence().any { it.trim() == "1" }
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

        prepareRealmForAuthserver(
            worldserverProcess?.isAlive == true,
            ::runAuthDatabaseQuery
        ) { RealmRuntime.appendLog("[realm] $it") }

        val process = ProcessBuilder(
            nativeBinary("libauthserver.so"),
            "-c", paths.authserverConf.absolutePath
        )
            .directory(paths.etcDir)
            .redirectErrorStream(true)
            .also { configureMysqlEnvironment(it) }
            .start()
        authserverProcess = process
        val readiness = ProcessReadiness()
        streamOutput(process, "authserver", readiness)
        monitorExit(process, "authserver", readiness) { ComponentStatus.FAILED }

        awaitProcessReadiness(process, "authserver", readiness, AUTHSERVER_STARTUP_WATCHDOG_MS)
        RealmRuntime.appendLog("[realm] Authserver phase: readiness event received")
        RealmRuntime.updateState { it.copy(authserver = ComponentStatus.RUNNING) }
    }

    private suspend fun startWorldserver(
        portableModuleConfigs: Map<RealmModDefinition, String> = emptyMap(),
        startAiBridge: Boolean = true
    ) {
        RealmRuntime.appendLog("[realm] Worldserver phase: launching worldserver")
        RealmRuntime.updateState { it.copy(phase = RealmPhase.STARTING_WORLDSERVER) }
        RealmRuntime.updateState { it.copy(worldserver = ComponentStatus.STARTING) }
        updateNotification("Starting worldserver...")

        if (worldserverProcess?.isAlive == true) {
            RealmRuntime.updateState {
                it.copy(worldserver = ComponentStatus.RUNNING, phase = RealmPhase.RUNNING)
            }
            refreshAccounts()
            return
        }
        worldserverProcess = null

        realmModConfigStore(this).install(paths.modulesConfigDir, portableModuleConfigs)
        if (startAiBridge) {
            startLlmChatter()
        } else {
            val chatterConfig = File(paths.modulesConfigDir, "mod_llm_chatter.conf")
            if (chatterConfig.isFile) {
                chatterConfig.writeText(RealmModConfig.replace(
                    chatterConfig.readText(),
                    mapOf("LLMChatter.Enable" to "0")
                ))
            }
        }
        check(!intentionalStop) { "Realm startup was stopped" }
        val process = ProcessBuilder(
            "/system/bin/sh", "-c",
            "printf 'BYGDOK_PID:%s\\n' \"\$\$\"; exec \"\$@\"", "worldserver",
            nativeBinary("libworldserver.so"),
            "-c", paths.worldserverConf.absolutePath
        )
            .directory(paths.etcDir)
            .redirectErrorStream(true)
            .also { configureMysqlEnvironment(it) }
            .start()
        worldserverProcess = process
        val readiness = ProcessReadiness()
        worldserverReadiness = readiness
        streamOutput(process, "worldserver", readiness)
        monitorExit(process, "worldserver", readiness) { ComponentStatus.FAILED }

        awaitProcessReadiness(process, "worldserver", readiness, WORLDSERVER_STARTUP_WATCHDOG_MS)
        RealmRuntime.appendLog("[realm] Worldserver phase: readiness event received")
        RealmRuntime.updateState { it.copy(worldserver = ComponentStatus.RUNNING) }
        RealmRuntime.updateState { it.copy(phase = RealmPhase.RUNNING) }
        updateNotification("Realm running")
        refreshAccounts()
    }

    private fun sendConsoleCommand(command: String) {
        val normalized = command.trim()
        if (normalized.isEmpty() || normalized.contains('\n') || normalized.contains('\r')) return
        val process = worldserverProcess
        if (process?.isAlive != true) {
            RealmRuntime.appendLog("[console] Worldserver is not running")
            return
        }
        try {
            synchronized(process) {
                process.outputStream.write("$normalized\n".toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }
            RealmRuntime.appendLog("[console] Command submitted")
        } catch (error: IOException) {
            RealmRuntime.appendLog("[console] Failed to send command: ${error.message}")
        }
    }

    private fun createAccount(username: String, password: String, makeGm: Boolean) {
        if (!username.matches(Regex("[A-Za-z0-9_]{3,16}")) ||
            password.length !in 3..16 || password.any(Char::isWhitespace)
        ) {
            RealmRuntime.appendLog("[accounts] Username must be 3-16 letters, digits, or underscores; password must be 3-16 non-space characters")
            return
        }
        if (!sendAccountCommand("account create $username $password")) return
        RealmRuntime.appendLog("[accounts] Account creation submitted for $username")
        if (makeGm) {
            if (!sendAccountCommand("account set gmlevel $username 3 -1")) return
            RealmRuntime.appendLog("[accounts] GM level 3 submitted for $username")
        }
        scope.launch {
            kotlinx.coroutines.delay(750)
            refreshAccounts()
        }
    }

    private fun setAccountGmLevel(username: String, gmLevel: Int) {
        if (!username.matches(Regex("[A-Za-z0-9_]{3,16}")) || gmLevel !in 0..3) {
            RealmRuntime.appendLog("[accounts] Invalid account or GM level")
            return
        }
        if (sendAccountCommand("account set gmlevel $username $gmLevel -1")) {
            RealmRuntime.appendLog("[accounts] GM level $gmLevel submitted for $username")
            scope.launch {
                kotlinx.coroutines.delay(500)
                refreshAccounts()
            }
        }
    }

    private fun sendAccountCommand(command: String): Boolean {
        val process = worldserverProcess
        if (process?.isAlive != true) {
            RealmRuntime.appendLog("[accounts] Worldserver is not running")
            return false
        }
        return try {
            synchronized(process) {
                process.outputStream.write("$command\n".toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }
            true
        } catch (error: IOException) {
            RealmRuntime.appendLog("[accounts] Failed to send command: ${error.message}")
            false
        }
    }

    private fun refreshAccounts() {
        val query = """
            SELECT a.id, a.username, COALESCE(MAX(aa.gmlevel), 0)
            FROM account a
            LEFT JOIN account_access aa ON aa.id = a.id AND (aa.RealmID = -1 OR aa.RealmID = 1)
            GROUP BY a.id, a.username
            ORDER BY a.id;
        """.trimIndent()
        try {
            val output = runAuthDatabaseQuery(query)
            val accounts = output.lineSequence().mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size != 3) return@mapNotNull null
                val id = fields[0].toIntOrNull() ?: return@mapNotNull null
                val gmLevel = fields[2].toIntOrNull() ?: return@mapNotNull null
                RealmAccount(id, fields[1], gmLevel)
            }.toList()
            RealmRuntime.setAccounts(accounts)
        } catch (error: Exception) {
            RealmRuntime.appendLog("[accounts] Failed to load accounts: ${error.message}")
        }
    }

    private suspend fun loadRealmNetworkSettings() {
        if (mariadbProcess?.isAlive != true) {
            RealmRuntime.appendLog("[network] Start MariaDB or the realm before loading addresses")
            return
        }
        refreshRealmNetworkSettings()
    }

    private fun refreshRealmNetworkSettings() {
        try {
            val persistedSettings = readRealmDatabaseSettings()
            val settings = RealmNetworkSettings(
                realmAddress = persistedSettings.realmAddress,
                localAddress = persistedSettings.localAddress
            )
            RealmRuntime.setNetworkSettings(settings)
            RealmNetworkSettingsStore(this).save(settings)
        } catch (error: Exception) {
            RealmRuntime.appendLog("[network] Failed to load realm addresses: ${error.message}")
        }
    }

    private fun readRealmDatabaseSettings(): PersistedRealmSettings {
        val output = runAuthDatabaseQuery(
            "SELECT name, address, localAddress FROM realmlist WHERE id = 1;"
        )
        return parseRealmDatabaseSettings(output)
            ?: throw IllegalStateException("Realm row 1 was not found or contains invalid settings")
    }

    private fun saveRealmNetworkSettings(realmAddress: String, localAddress: String) {
        val address = realmAddress.trim()
        val local = localAddress.trim()
        if (!isValidRealmAddress(address) || !isValidRealmAddress(local)) {
            RealmRuntime.appendLog("[network] Enter a valid IPv4 address or hostname for both fields")
            return
        }
        applyRealmNetworkSettings(address, local)
    }

    private fun applySavedRealmNetworkSettings() {
        val settings = RealmNetworkSettingsStore(this).load()
        if (isValidRealmAddress(settings.realmAddress) && isValidRealmAddress(settings.localAddress)) {
            applyRealmNetworkSettings(settings.realmAddress, settings.localAddress)
        }
    }

    private fun applyRealmNetworkSettings(address: String, local: String) {
        try {
            runAuthDatabaseQuery(
                "UPDATE realmlist SET address = '$address', localAddress = '$local' WHERE id = 1;"
            )
            refreshRealmNetworkSettings()
            RealmRuntime.appendLog("[network] Realm addresses applied; reconnect clients to use the new values")
        } catch (error: Exception) {
            RealmRuntime.appendLog("[network] Failed to apply realm addresses: ${error.message}")
        }
    }

    private fun runAuthDatabaseQuery(query: String): String {
        val clientBinary = File(paths.nativeLibraryDir, "libpocket_mariadb_client.so")
        check(clientBinary.isFile) { "Embedded SQL client is unavailable" }
        val builder = ProcessBuilder(
            clientBinary.absolutePath,
            "--no-defaults",
            "-h", "127.0.0.1",
            "-P", "3306",
            "-u", "root",
            "--batch",
            "--skip-column-names",
            "--database", "acore_auth",
            "-e", query
        )
            .directory(paths.root)
            .also { configureMysqlEnvironment(it) }
        return runRealmDatabaseQuery(builder, cacheDir) { RealmRuntime.appendLog("[database-client] $it") }
    }

    private fun stopWorldserverGracefully() {
        val process = worldserverProcess ?: return
        if (!process.isAlive) return
        RealmRuntime.appendLog("[realm] Requesting graceful worldserver shutdown")
        val pid = worldserverReadiness?.pid
        if (pid != null) {
            try {
                // Keep the log pipes open, but release the core's blocking console read.
                Os.kill(pid, OsConstants.SIGTERM)
                try {
                    process.outputStream.close()
                } catch (error: IOException) {
                    RealmRuntime.appendLog("[realm] Could not close worldserver console: ${error.message}")
                }
            } catch (error: ErrnoException) {
                if (process.isAlive) {
                    RealmRuntime.appendLog("[realm] Could not signal worldserver: ${error.message}")
                    process.destroy()
                }
            }
        } else {
            RealmRuntime.appendLog("[realm] Worldserver PID unavailable; using process termination")
            process.destroy()
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            RealmRuntime.appendLog("[realm] Worldserver shutdown timed out; unfinished guild memories will retry on startup")
            process.destroyForcibly()
            process.waitFor()
        }
    }

    private fun stopRealm(resetState: Boolean = true) {
        stopProcessesOnly()
        if (resetState) {
            RealmRuntime.updateState {
                it.copy(
                    phase = RealmPhase.IDLE,
                    database = ComponentStatus.STOPPED,
                    authserver = ComponentStatus.STOPPED,
                    worldserver = ComponentStatus.STOPPED
                )
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopProcessesOnly() {
        try {
            llmChatterBridge?.stop()
        } catch (error: Exception) {
            RealmRuntime.appendLog("[llm-chatter] Could not stop bridge cleanly: ${error.message}")
        }
        llmChatterBridge = null
        ollamaMonitorJob?.cancel()
        ollamaMonitorJob = null
        ollama?.stop()
        ollama = null
        logTailJob?.cancel()
        logTailJob = null
        val processes = listOfNotNull(worldserverProcess, authserverProcess, mariadbProcess)
        processes.forEach { it.destroyForcibly() }
        processes.forEach { it.waitFor() }
        worldserverProcess = null
        worldserverReadiness = null
        authserverProcess = null
        mariadbProcess = null
    }

    private suspend fun startLlmChatter() {
        val configFile = File(paths.modulesConfigDir, "mod_llm_chatter.conf")
        val config = configFile.readText()
        val definition = RealmMods.all.single { it.id == "mod-llm-chatter" }
        if (!RealmModConfig.enabled(definition, config)) return
        check(!intentionalStop) { "Realm startup was stopped" }
        val runtimeRoot = File(paths.root, "llm-chatter")
        var runtimeConfig = RealmOllamaConfig.bridgeRuntimeConfig(config, runtimeRoot)
        try {
            if (RealmOllamaConfig.managed(config)) {
                val engine = RealmOllama(
                    File(paths.root, "ollama"), File(paths.nativeLibraryDir),
                    { RealmRuntime.appendLog("[ollama] $it") },
                    { status -> RealmRuntime.updateState { it.copy(ollama = status) } },
                    RealmOllamaDriverStore(File(filesDir, "ollama-drivers"), android.os.Build.VERSION.SDK_INT),
                    model = RealmOllamaConfig.selectedModel(config)
                )
                ollama = engine
                engine.start(RealmOllamaConfig.downloadAllowed(config))
                check(!intentionalStop) { "Realm startup was stopped" }
                runtimeConfig = RealmOllamaConfig.localRuntimeConfig(runtimeConfig)
                ollamaMonitorJob = scope.launch { engine.monitor() }
            }

            val playerbotsFile = File(paths.modulesConfigDir, RealmMods.playerbots.configFile)
            if (playerbotsFile.isFile) {
                playerbotsFile.writeText(RealmOllamaConfig.quietPlayerbotsConfig(playerbotsFile.readText()))
            }
            configFile.writeText(runtimeConfig)
            val bridge = RealmLlmChatterBridge(
                this,
                configFile,
                File(runtimeRoot, "tmp"),
                { RealmRuntime.appendLog("[llm-chatter] $it") }
            )
            bridge.start()
            llmChatterBridge = bridge
            RealmRuntime.appendLog("[llm-chatter] Feature-rich chat bridge started")
        } catch (cancelled: CancellationException) {
            ollama?.stop()
            ollama = null
            throw cancelled
        } catch (error: Exception) {
            llmChatterBridge?.stop()
            llmChatterBridge = null
            ollama?.stop()
            ollama = null
            RealmRuntime.updateState { it.copy(ollama = ComponentStatus.FAILED) }
            RealmRuntime.appendLog("[llm-chatter] AI chat could not start: ${error.message}. Gameplay will continue without AI chat.")
            configFile.writeText(RealmModConfig.replace(runtimeConfig, mapOf("LLMChatter.Enable" to "0")))
        }
    }

    private fun nativeBinary(name: String): String =
        RealmExecutableBinary.nativeLibraryPath(paths.nativeLibraryDir, name)

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
                ?: return@forEach
            if (sqlFiles.isNotEmpty()) {
                val runtimeDatabaseName = databaseNameMap[databaseDir.name] ?: databaseDir.name
                filesByDb[runtimeDatabaseName] = sqlFiles.toMutableList()
            }
        }
        return filesByDb
    }

    private fun databaseLibraryPath(): String =
        "${paths.mariadbLibDir.absolutePath}:${paths.nativeLibraryDir}"

    private fun configureMysqlEnvironment(builder: ProcessBuilder) {
        val socketPath = paths.socketFile.absolutePath
        val env = builder.environment()
        env["LD_LIBRARY_PATH"] = databaseLibraryPath()
        val versionedZlib = File(paths.mariadbLibDir, "libz.so.1")
        if (versionedZlib.isFile) {
            // Android may omit versioned .so names from the extracted JNI directory.
            // Preload the bundled copy so MariaDB's DT_NEEDED(libz.so.1) resolves.
            env["LD_PRELOAD"] = versionedZlib.absolutePath
        }
        env["OPENSSL_MODULES"] = paths.nativeLibraryDir
        env["MYSQL_UNIX_PORT"] = socketPath
        env["MYSQL_UNIX_ADDR"] = socketPath
        env["MARIADB_UNIX_PORT"] = socketPath
        env["MARIADB_UNIX_ADDR"] = socketPath
    }

    private fun streamOutput(process: Process, tag: String, readiness: ProcessReadiness) {
        scope.launch {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (tag == "worldserver" && readiness.pid == null && line.startsWith("BYGDOK_PID:")) {
                            readiness.pid = line.substringAfter(':').toIntOrNull()?.takeIf { it > 1 }
                        }
                        RealmRuntime.appendLog("[$tag] $line")
                        if (matchesReadinessSignal(tag, line)) {
                            readiness.firstSignal.complete(ProcessStartupSignal(ready = true))
                        }
                    }
                }
            } catch (_: IOException) {
                // Process pipes can close while the service is stopping.
            } finally {
                readiness.outputClosed.complete(Unit)
            }
        }
    }

    private fun startNativeLogTail() {
        logTailJob?.cancel()
        logTailJob = scope.launch {
            val files = listOf(
                File(paths.logsDir, "Auth.log") to "authserver-file",
                File(paths.logsDir, "Server.log") to "worldserver-file",
                File(paths.logsDir, "Errors.log") to "errors-file",
                File(paths.logsDir, "Playerbots.log") to "playerbots-file",
                File(paths.root, "ollama/ollama.log") to "ollama-native",
                RealmOllamaConfig.bridgeLogFile(File(paths.root, "llm-chatter")) to "llm-chatter"
            )
            val positions = files.associate { (file, tag) ->
                file to if (tag == "ollama-native") file.length() else 0L
            }.toMutableMap()
            while (isActive) {
                for ((file, tag) in files) {
                    if (!file.isFile) continue
                    RandomAccessFile(file, "r").use { reader ->
                        val position = positions[file] ?: 0L
                        if (file.length() < position) {
                            positions[file] = 0L
                            reader.seek(0)
                        } else {
                            reader.seek(position)
                        }
                        var line = reader.readLine()
                        while (line != null) {
                            RealmRuntime.appendLog("[$tag] $line")
                            line = reader.readLine()
                        }
                        positions[file] = reader.filePointer
                    }
                }
                kotlinx.coroutines.delay(250)
            }
        }
    }

    private fun clearNativeLogs() {
        paths.logsDir.listFiles()?.forEach { it.delete() }
    }

    private fun monitorExit(
        process: Process,
        tag: String,
        readiness: ProcessReadiness,
        onExit: () -> ComponentStatus
    ) {
        scope.launch {
            val exitCode = process.waitFor()
            readiness.firstSignal.complete(ProcessStartupSignal(ready = false, exitCode = exitCode))
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

    private suspend fun awaitProcessReadiness(
        process: Process,
        tag: String,
        readiness: ProcessReadiness,
        watchdogMillis: Long
    ) {
        val signal = try {
            withTimeout(watchdogMillis) { readiness.firstSignal.await() }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException("No readiness event received from $tag before watchdog expired", error)
        }
        if (!signal.ready) {
            withTimeoutOrNull(1_000) { readiness.outputClosed.await() }
        }
        check(signal.ready) { "$tag exited with code ${signal.exitCode} before readiness" }
        check(process.isAlive) { "$tag exited immediately after its readiness event" }
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
        const val ACTION_START_REALM = "com.denartes.bygdoketernal.realm.START_REALM"
        const val ACTION_START_DATABASE_ONLY = "com.denartes.bygdoketernal.realm.START_DATABASE_ONLY"
        const val ACTION_COMPLETE_INITIAL_SETUP = "com.denartes.bygdoketernal.realm.COMPLETE_INITIAL_SETUP"
        const val ACTION_IMPORT_REALM = "com.denartes.bygdoketernal.realm.IMPORT_REALM"
        const val ACTION_EXPORT_REALM = "com.denartes.bygdoketernal.realm.EXPORT_REALM"
        const val ACTION_CONSOLE_COMMAND = "com.denartes.bygdoketernal.realm.CONSOLE_COMMAND"
        const val ACTION_REFRESH_ACCOUNTS = "com.denartes.bygdoketernal.realm.REFRESH_ACCOUNTS"
        const val ACTION_BOT_ACCOUNTS = "com.denartes.bygdoketernal.realm.BOT_ACCOUNTS"
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_ACCOUNT_ID = "accountId"
        const val EXTRA_TARGET_ACCOUNT_ID = "targetAccountId"
        const val ACTION_CREATE_ACCOUNT = "com.denartes.bygdoketernal.realm.CREATE_ACCOUNT"
        const val ACTION_SET_ACCOUNT_GM_LEVEL = "com.denartes.bygdoketernal.realm.SET_ACCOUNT_GM_LEVEL"
        const val ACTION_LOAD_NETWORK_SETTINGS = "com.denartes.bygdoketernal.realm.LOAD_NETWORK_SETTINGS"
        const val ACTION_SAVE_NETWORK_SETTINGS = "com.denartes.bygdoketernal.realm.SAVE_NETWORK_SETTINGS"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_GM = "gm"
        const val EXTRA_GM_LEVEL = "gmLevel"
        const val EXTRA_REALM_ADDRESS = "realmAddress"
        const val EXTRA_LOCAL_ADDRESS = "localAddress"
        const val EXTRA_REALM_NAME = "realmName"
        private const val CHANNEL_ID = "realm"
        private const val NOTIFICATION_ID = 1
        private const val DATABASE_STARTUP_WATCHDOG_MS = 60_000L
        private const val AUTHSERVER_STARTUP_WATCHDOG_MS = 60_000L
        private const val WORLDSERVER_STARTUP_WATCHDOG_MS = 15 * 60_000L

        internal fun shouldImportDatabaseSchema(markerExists: Boolean, coreSchemaExists: Boolean): Boolean =
            !markerExists && !coreSchemaExists

        internal fun shouldSkipModuleDatabaseBootstrap(markerExists: Boolean, requiredTableExists: Boolean): Boolean =
            markerExists && requiredTableExists

        internal fun requiresTransmogSchemaTableCheck(moduleName: String, database: String): Boolean =
            moduleName == "mod-transmog-plus" && database == "acore_characters"
    }
}

internal fun isValidRealmAddress(address: String): Boolean =
    address.length in 1..253 && address.matches(Regex("[A-Za-z0-9.-]+"))

internal fun isValidRealmName(name: String): Boolean =
    name.length in 1..32 && name.none(Char::isISOControl)

internal fun matchesReadinessSignal(tag: String, line: String): Boolean = when (tag) {
    "mariadbd" -> line.contains("ready for connections", ignoreCase = true)
    "authserver" -> line.contains("BYGDOK_READY:authserver")
    "worldserver" -> line.contains("BYGDOK_READY:worldserver")
    else -> false
}

internal data class PersistedRealmSettings(
    val realmName: String,
    val realmAddress: String,
    val localAddress: String
)

internal fun parseRealmDatabaseSettings(output: String): PersistedRealmSettings? {
    val fields = output.lineSequence()
        .map { it.split('\t') }
        .firstOrNull { row ->
            row.size == 3 &&
                isValidRealmName(row[0]) &&
                isValidRealmAddress(row[1]) &&
                isValidRealmAddress(row[2])
        }
        ?: return null
    return PersistedRealmSettings(fields[0], fields[1], fields[2])
}

internal data class RealmModuleDatabaseBootstrapBatch(
    val moduleName: String,
    val database: String,
    val sqlFiles: List<File>
)

internal fun realmModuleDatabaseBootstrapFiles(modulesDir: File): List<RealmModuleDatabaseBootstrapBatch> {
    if (!modulesDir.isDirectory) return emptyList()
    val databaseNameMap = mapOf(
        "auth" to "acore_auth",
        "db-auth" to "acore_auth",
        "db_auth" to "acore_auth",
        "characters" to "acore_characters",
        "db-characters" to "acore_characters",
        "db_characters" to "acore_characters",
        "world" to "acore_world",
        "db-world" to "acore_world",
        "db_world" to "acore_world",
        "playerbots" to "acore_playerbots",
        "db-playerbots" to "acore_playerbots",
        "db_playerbots" to "acore_playerbots"
    )
    val batches = linkedMapOf<Pair<String, String>, MutableList<File>>()

    modulesDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { moduleDir ->
        for (sqlRootName in listOf("data/sql", "sql")) {
            val sqlRoot = File(moduleDir, sqlRootName)
            sqlRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { databaseDir ->
                val database = databaseNameMap[databaseDir.name] ?: return@forEach
                val baseDir = File(databaseDir, "base")
                val sqlDir = if (baseDir.isDirectory) baseDir else databaseDir
                val files = sqlDir.listFiles { file -> file.isFile && file.extension == "sql" }
                    ?.sortedBy { it.name }
                    ?: return@forEach
                if (files.isNotEmpty()) {
                    batches.getOrPut(moduleDir.name to database) { mutableListOf() }.addAll(files)
                }
            }
        }
    }

    return batches.map { (key, files) ->
        RealmModuleDatabaseBootstrapBatch(key.first, key.second, files.distinct().sortedBy { it.absolutePath })
    }
}
