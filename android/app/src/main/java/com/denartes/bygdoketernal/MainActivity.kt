package com.denartes.bygdoketernal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.denartes.bygdoketernal.realm.ComponentStatus
import com.denartes.bygdoketernal.realm.RealmAccount
import com.denartes.bygdoketernal.realm.RealmForegroundService
import com.denartes.bygdoketernal.realm.RealmNetworkSettings
import com.denartes.bygdoketernal.realm.RealmNetworkSettingsStore
import com.denartes.bygdoketernal.realm.RealmPhase
import com.denartes.bygdoketernal.realm.RealmRuntime
import com.denartes.bygdoketernal.realm.RealmSetupStore
import com.denartes.bygdoketernal.realm.isValidRealmAddress
import com.denartes.bygdoketernal.realm.isValidRealmName

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op: notification is optional */ }
    private val importArchiveLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startRealmImport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        val networkSettingsStore = RealmNetworkSettingsStore(this)
        val savedNetworkSettings = networkSettingsStore.load()
        RealmRuntime.setNetworkSettings(savedNetworkSettings)
        val setupStore = RealmSetupStore(this)
        RealmRuntime.setSetupComplete(setupStore.isComplete(), setupStore.realmName())
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by RealmRuntime.state.collectAsState()
                    val setupComplete by RealmRuntime.setupComplete.collectAsState()
                    if (setupComplete) DashboardScreen(
                        onStart = {
                            RealmRuntime.beginStart()
                            startForegroundService(
                                Intent(this, RealmForegroundService::class.java)
                                    .setAction(RealmForegroundService.ACTION_START_REALM)
                            )
                        },
                        onStop = {
                            RealmRuntime.resetToDefault()
                            startService(
                                Intent(this, RealmForegroundService::class.java)
                                    .setAction(RealmForegroundService.ACTION_STOP)
                            )
                        },
                        onConsoleCommand = { command ->
                            sendRealmAction(RealmForegroundService.ACTION_CONSOLE_COMMAND) {
                                putExtra(RealmForegroundService.EXTRA_COMMAND, command)
                            }
                        },
                        onRefreshAccounts = {
                            sendRealmAction(RealmForegroundService.ACTION_REFRESH_ACCOUNTS)
                        },
                        onCreateAccount = { username, password, makeGm ->
                            sendRealmAction(RealmForegroundService.ACTION_CREATE_ACCOUNT) {
                                putExtra(RealmForegroundService.EXTRA_USERNAME, username)
                                putExtra(RealmForegroundService.EXTRA_PASSWORD, password)
                                putExtra(RealmForegroundService.EXTRA_GM, makeGm)
                            }
                        },
                        onSetAccountGmLevel = { username, gmLevel ->
                            sendRealmAction(RealmForegroundService.ACTION_SET_ACCOUNT_GM_LEVEL) {
                                putExtra(RealmForegroundService.EXTRA_USERNAME, username)
                                putExtra(RealmForegroundService.EXTRA_GM_LEVEL, gmLevel)
                            }
                        },
                        onLoadNetworkSettings = {
                            sendRealmAction(RealmForegroundService.ACTION_LOAD_NETWORK_SETTINGS)
                        },
                        onSaveNetworkSettings = { realmAddress, localAddress ->
                            networkSettingsStore.save(
                                RealmNetworkSettings(realmAddress, localAddress)
                            )
                            RealmRuntime.setNetworkSettings(RealmNetworkSettings(realmAddress, localAddress))
                        },
                        onApplyNetworkSettings = { realmAddress, localAddress ->
                            sendRealmAction(RealmForegroundService.ACTION_SAVE_NETWORK_SETTINGS) {
                                putExtra(RealmForegroundService.EXTRA_REALM_ADDRESS, realmAddress)
                                putExtra(RealmForegroundService.EXTRA_LOCAL_ADDRESS, localAddress)
                            }
                        },
                        onImportRealm = ::chooseRealmImport
                    ) else InitialSetupScreen(
                        state = state,
                        onStartDatabase = {
                            RealmRuntime.beginDatabaseOnlyStart()
                            sendRealmAction(RealmForegroundService.ACTION_START_DATABASE_ONLY)
                        },
                        onComplete = { realmName, realmAddress, localAddress, username, password ->
                            sendRealmAction(RealmForegroundService.ACTION_COMPLETE_INITIAL_SETUP) {
                                putExtra(RealmForegroundService.EXTRA_REALM_NAME, realmName)
                                putExtra(RealmForegroundService.EXTRA_REALM_ADDRESS, realmAddress)
                                putExtra(RealmForegroundService.EXTRA_LOCAL_ADDRESS, localAddress)
                                putExtra(RealmForegroundService.EXTRA_USERNAME, username)
                                putExtra(RealmForegroundService.EXTRA_PASSWORD, password)
                            }
                        },
                        onImportRealm = ::chooseRealmImport
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun sendRealmAction(action: String, configure: Intent.() -> Unit = {}) {
        val intent = Intent(this, RealmForegroundService::class.java)
            .setAction(action)
            .apply(configure)
        startService(intent)
    }

    private fun chooseRealmImport() {
        importArchiveLauncher.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/gzip",
                "application/x-gzip",
                "application/x-tar",
                "application/octet-stream"
            )
        )
    }

    private fun startRealmImport(uri: Uri) {
        val intent = Intent(this, RealmForegroundService::class.java)
            .setAction(RealmForegroundService.ACTION_IMPORT_REALM)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startForegroundService(intent)
    }
}

@Composable
private fun InitialSetupScreen(
    state: com.denartes.bygdoketernal.realm.RealmState,
    onStartDatabase: () -> Unit,
    onComplete: (String, String, String, String, String) -> Unit,
    onImportRealm: () -> Unit
) {
    val networkSettings by RealmRuntime.networkSettings.collectAsState()
    val logLines by RealmRuntime.logLines.collectAsState()
    var realmName by rememberSaveable { mutableStateOf("") }
    var realmAddress by rememberSaveable { mutableStateOf(networkSettings.realmAddress) }
    var localAddress by rememberSaveable { mutableStateOf(networkSettings.localAddress) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val lastSetupMessage = logLines.lastOrNull { it.startsWith("[setup]") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("BYGDOK ETERNAL", style = MaterialTheme.typography.headlineMedium)
        Text("First-run setup", style = MaterialTheme.typography.titleLarge)
        if (state.database != ComponentStatus.RUNNING) {
            Text(statusLabel(state.database), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onStartDatabase,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(phaseLabel(state.phase))
                } else {
                    Text("START MARIADB")
                }
            }
            OutlinedButton(
                onClick = onImportRealm,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("IMPORT EXISTING REALM") }
        } else {
            OutlinedTextField(
                value = realmName,
                onValueChange = { realmName = it },
                label = { Text("Realm name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = realmAddress,
                onValueChange = { realmAddress = it },
                label = { Text("Realm address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = localAddress,
                onValueChange = { localAddress = it },
                label = { Text("Local address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Initial GM username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Initial GM password") },
                supportingText = { Text("3-16 printable ASCII characters") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    onComplete(realmName.trim(), realmAddress.trim(), localAddress.trim(), username.trim(), password)
                },
                enabled = !state.isBusy &&
                    isValidRealmName(realmName.trim()) &&
                    isValidRealmAddress(realmAddress.trim()) &&
                    isValidRealmAddress(localAddress.trim()) &&
                    username.trim().matches(Regex("[A-Za-z0-9_]{3,16}")) &&
                    password.length in 3..16 && password.all { it.code in 33..126 },
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Saving setup...")
                } else {
                    Text("SAVE SETUP AND CREATE GM")
                }
            }
        }
        if (lastSetupMessage != null) {
            Text(lastSetupMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DashboardScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConsoleCommand: (String) -> Unit,
    onRefreshAccounts: () -> Unit,
    onCreateAccount: (String, String, Boolean) -> Unit,
    onSetAccountGmLevel: (String, Int) -> Unit,
    onLoadNetworkSettings: () -> Unit,
    onSaveNetworkSettings: (String, String) -> Unit,
    onApplyNetworkSettings: (String, String) -> Unit,
    onImportRealm: () -> Unit
) {
    val state by RealmRuntime.state.collectAsState()
    val logLines by RealmRuntime.logLines.collectAsState()
    val accounts by RealmRuntime.accounts.collectAsState()
    val networkSettings by RealmRuntime.networkSettings.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(state.database) {
        if (state.database == ComponentStatus.RUNNING) onLoadNetworkSettings()
    }
    LaunchedEffect(state.worldserver) {
        if (state.worldserver == ComponentStatus.RUNNING) onRefreshAccounts()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("BYGDOK ETERNAL", style = MaterialTheme.typography.headlineMedium)
        TabRow(selectedTabIndex = selectedTab) {
            listOf("Overview", "Accounts", "Network", "Console", "Import").forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = {
                        selectedTab = index
                        if (index == 1 && state.worldserver == ComponentStatus.RUNNING) onRefreshAccounts()
                        if (index == 2 && state.database == ComponentStatus.RUNNING) onLoadNetworkSettings()
                    },
                    text = { Text(title) }
                )
            }
        }
        when (selectedTab) {
            1 -> AccountsPanel(
                accounts = accounts,
                enabled = state.worldserver == ComponentStatus.RUNNING,
                onRefresh = onRefreshAccounts,
                onCreate = onCreateAccount,
                onSetGmLevel = onSetAccountGmLevel,
                modifier = Modifier.weight(1f)
            )
            2 -> NetworkPanel(
                settings = networkSettings,
                databaseRunning = state.database == ComponentStatus.RUNNING,
                onReload = onLoadNetworkSettings,
                onSave = { realmAddress, localAddress ->
                    onSaveNetworkSettings(realmAddress, localAddress)
                    if (state.database == ComponentStatus.RUNNING) {
                        onApplyNetworkSettings(realmAddress, localAddress)
                    }
                },
                modifier = Modifier.weight(1f)
            )
            3 -> ConsolePanel(
                logLines = logLines,
                enabled = state.worldserver == ComponentStatus.RUNNING,
                onSend = onConsoleCommand,
                modifier = Modifier.weight(1f)
            )
            4 -> ImportRealmPanel(
                logLines = logLines,
                enabled = !state.isBusy,
                onImport = onImportRealm,
                modifier = Modifier.weight(1f)
            )
            else -> OverviewPanel(
                state = state,
                logLines = logLines,
                onStart = onStart,
                onStop = onStop,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NetworkPanel(
    settings: RealmNetworkSettings,
    databaseRunning: Boolean,
    onReload: () -> Unit,
    onSave: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var realmAddress by rememberSaveable { mutableStateOf(settings.realmAddress) }
    var localAddress by rememberSaveable { mutableStateOf(settings.localAddress) }

    LaunchedEffect(settings) {
        realmAddress = settings.realmAddress
        localAddress = settings.localAddress
    }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Network", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = realmAddress,
            onValueChange = { realmAddress = it },
            label = { Text("Realm address") },
            supportingText = { Text("Address returned to clients outside the local subnet") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = localAddress,
            onValueChange = { localAddress = it },
            label = { Text("Local address") },
            supportingText = { Text("Address returned to clients on the local subnet") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(realmAddress.trim(), localAddress.trim()) },
                enabled = isValidRealmAddress(realmAddress.trim()) && isValidRealmAddress(localAddress.trim()),
                modifier = Modifier.weight(1f)
            ) { Text("Save addresses") }
            OutlinedButton(onClick = onReload, enabled = databaseRunning) { Text("Reload") }
        }
        Text(
            if (databaseRunning) {
                "Saved addresses apply to new login sessions."
            } else {
                "Saved for next start; clients receive the addresses after the realm starts."
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun OverviewPanel(
    state: com.denartes.bygdoketernal.realm.RealmState,
    logLines: List<String>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val logListState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(logLines) {
        if (logLines.isNotEmpty()) logListState.scrollToItem(logLines.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusCard("Database", state.database)
        StatusCard("Authserver", state.authserver)
        StatusCard("Worldserver", state.worldserver)
        Button(
            onClick = if (state.isRunning) onStop else onStart,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = if (state.isRunning) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            }
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(phaseLabel(state.phase))
            } else {
                Text(if (state.isRunning) "STOP REALM" else "START REALM")
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Server Log", style = MaterialTheme.typography.titleLarge)
                    TextButton(
                        onClick = {
                            val exportedLog = logLines.joinToString("\n")
                            clipboardManager.setText(AnnotatedString(exportedLog))
                            exportedLog.chunked(3500).forEachIndexed { index, chunk ->
                                Log.i("BYGDOK_EXPORT", "BYGDOK_EXPORT[$index] $chunk")
                            }
                        },
                        enabled = logLines.isNotEmpty()
                    ) { Text("COPY LOG") }
                }
                LazyColumn(state = logListState, modifier = Modifier.fillMaxSize()) {
                    items(logLines) { line -> Text(text = line, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun AccountsPanel(
    accounts: List<RealmAccount>,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onCreate: (String, String, Boolean) -> Unit,
    onSetGmLevel: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var makeGm by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Accounts", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = enabled,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = makeGm, onCheckedChange = { makeGm = it }, enabled = enabled)
            Text("Grant GM level 3")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onCreate(username.trim(), password, makeGm)
                    password = ""
                },
                enabled = enabled && username.length >= 3 && password.length >= 3,
                modifier = Modifier.weight(1f)
            ) { Text("Create account") }
            OutlinedButton(onClick = onRefresh, enabled = enabled) { Text("Refresh") }
        }
        Text("${accounts.size} accounts", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(accounts, key = { it.id }) { account ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(account.username, style = MaterialTheme.typography.bodyLarge)
                        Text("ID ${account.id} · GM ${account.gmLevel}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        onClick = { onSetGmLevel(account.username, if (account.gmLevel >= 3) 0 else 3) },
                        enabled = enabled
                    ) { Text(if (account.gmLevel >= 3) "Remove GM" else "Make GM") }
                }
            }
        }
    }
}

@Composable
private fun ConsolePanel(
    logLines: List<String>,
    enabled: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var command by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(logLines) {
        if (logLines.isNotEmpty()) listState.scrollToItem(logLines.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Worldserver Console", style = MaterialTheme.typography.titleLarge)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            items(logLines) { line -> Text(text = line, style = MaterialTheme.typography.bodySmall) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it.replace("\n", "").replace("\r", "") },
                label = { Text("Command") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { onSend(command); command = "" },
                enabled = enabled && command.isNotBlank()
            ) { Text("Send") }
        }
    }
}

@Composable
private fun StatusCard(label: String, status: ComponentStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(statusLabel(status), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun statusLabel(status: ComponentStatus): String = when (status) {
    ComponentStatus.NOT_CONFIGURED -> "Not configured"
    ComponentStatus.STARTING -> "Starting"
    ComponentStatus.RUNNING -> "Running"
    ComponentStatus.STOPPED -> "Stopped"
    ComponentStatus.FAILED -> "Failed"
}

private fun phaseLabel(phase: RealmPhase): String = when (phase) {
    RealmPhase.STARTING_DATABASE -> "Starting database..."
    RealmPhase.DATABASE_READY -> "MariaDB ready"
    RealmPhase.CONFIGURING_INITIAL_SETUP -> "Saving setup..."
    RealmPhase.IMPORTING_REALM -> "Importing realm..."
    RealmPhase.STARTING_AUTHSERVER -> "Starting authserver..."
    RealmPhase.PREPARING_CLIENT_DATA -> "Preparing client data..."
    RealmPhase.STARTING_WORLDSERVER -> "Starting worldserver..."
    else -> "Starting realm..."
}

@Composable
private fun ImportRealmPanel(
    logLines: List<String>,
    enabled: Boolean,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val importMessage = logLines.lastOrNull { it.startsWith("[import]") }
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Import Realm", style = MaterialTheme.typography.titleLarge)
        Text("Restore a Bygdok logical backup. The current realm is retained until validation succeeds.")
        Button(onClick = onImport, enabled = enabled, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text("SELECT BACKUP ARCHIVE")
        }
        if (importMessage != null) Text(importMessage, style = MaterialTheme.typography.bodySmall)
    }
}