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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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

private val RealmGold = Color(0xFFE0B44F)
private val RealmBackground = Color(0xFF070C10)
private val RealmSurface = Color(0xFF0D151B)
private val RealmSurfaceRaised = Color(0xFF111D25)
private val RealmOutline = Color(0xFF1C2B34)
private val RealmMuted = Color(0xFF91A5B3)

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op: notification is optional */ }
    private val importArchiveLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startRealmImport(uri)
        }
    private val exportArchiveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                startForegroundService(Intent(this, RealmForegroundService::class.java)
                    .setAction(RealmForegroundService.ACTION_EXPORT_REALM)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            }
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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = RealmGold,
                    onPrimary = Color(0xFF211707),
                    secondary = Color(0xFF72B8E9),
                    background = RealmBackground,
                    surface = RealmSurface,
                    surfaceVariant = RealmSurfaceRaised,
                    onSurface = Color(0xFFE6EDF2),
                    onSurfaceVariant = RealmMuted,
                    outline = RealmOutline,
                    error = Color(0xFFCF6559)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = RealmBackground) {
                    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        val state by RealmRuntime.state.collectAsState()
                        val setupComplete by RealmRuntime.setupComplete.collectAsState()
                        val restoreRevision by RealmRuntime.restoreRevision.collectAsState()
                        key(restoreRevision) {
                            if (setupComplete) DashboardScreen(
                                onStart = {
                                    RealmRuntime.beginStart()
                                    startForegroundService(
                                        Intent(this@MainActivity, RealmForegroundService::class.java)
                                            .setAction(RealmForegroundService.ACTION_START_REALM)
                                    )
                                },
                                onStop = {
                                    startService(
                                        Intent(this@MainActivity, RealmForegroundService::class.java)
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
                                    if (!RealmRuntime.state.value.isBusy) {
                                        networkSettingsStore.save(
                                            RealmNetworkSettings(realmAddress, localAddress)
                                        )
                                        RealmRuntime.setNetworkSettings(RealmNetworkSettings(realmAddress, localAddress))
                                    }
                                },
                                onApplyNetworkSettings = { realmAddress, localAddress ->
                                    sendRealmAction(RealmForegroundService.ACTION_SAVE_NETWORK_SETTINGS) {
                                        putExtra(RealmForegroundService.EXTRA_REALM_ADDRESS, realmAddress)
                                        putExtra(RealmForegroundService.EXTRA_LOCAL_ADDRESS, localAddress)
                                    }
                                },
                                onImportRealm = ::chooseRealmImport,
                                onExportRealm = {
                                    exportArchiveLauncher.launch("bygdok-realm-${java.time.LocalDate.now()}.zip")
                                }
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
        android.app.AlertDialog.Builder(this)
            .setTitle("Restore realm backup?")
            .setMessage("A full backup replaces the entire stopped realm, saved settings, AI models and imported drivers. " +
                "Export your current realm first. Only restore a backup you trust.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose backup") { _, _ -> launchRealmImportPicker() }
            .show()
    }

    private fun launchRealmImportPicker() {
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
    val lastSetupMessage = logLines.lastOrNull { it.startsWith("[setup]") || it.startsWith("[import]") }

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
            Text("For a fresh-install restore, import your full backup before starting MariaDB.")
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
    onImportRealm: () -> Unit,
    onExportRealm: () -> Unit
) {
    val state by RealmRuntime.state.collectAsState()
    val logLines by RealmRuntime.logLines.collectAsState()
    val accounts by RealmRuntime.accounts.collectAsState()
    val networkSettings by RealmRuntime.networkSettings.collectAsState()
    val botAccountState by RealmRuntime.botAccounts.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var unsavedMods by rememberSaveable { mutableStateOf(false) }
    val tabState = rememberSaveableStateHolder()

    LaunchedEffect(state.database) {
        if (state.database == ComponentStatus.RUNNING) onLoadNetworkSettings()
    }
    LaunchedEffect(state.worldserver) {
        if (state.worldserver == ComponentStatus.RUNNING) onRefreshAccounts()
    }

    fun selectTab(index: Int) {
        selectedTab = index
        if (index == 1 && state.worldserver == ComponentStatus.RUNNING) onRefreshAccounts()
        if (index == 2 && state.database == ComponentStatus.RUNNING) onLoadNetworkSettings()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RealmBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(RealmGold.copy(alpha = 0.14f))
                    .border(1.dp, RealmGold.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("B", color = RealmGold, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text("BYGDOK", color = RealmGold, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("AzerothCore · 3.3.5a", color = RealmMuted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { selectTab(2) }) {
                Text("⚙", color = RealmMuted, style = MaterialTheme.typography.headlineSmall)
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                1 -> AccountsPanel(
                    accounts = accounts,
                    enabled = state.worldserver == ComponentStatus.RUNNING,
                    onRefresh = onRefreshAccounts,
                    onCreate = onCreateAccount,
                    onSetGmLevel = onSetAccountGmLevel,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
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
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
                )
                3 -> ConsolePanel(
                    logLines = logLines,
                    enabled = state.worldserver == ComponentStatus.RUNNING,
                    onSend = onConsoleCommand,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
                )
                4 -> ImportRealmPanel(
                    logLines = logLines,
                    enabled = state.canBackup && !botAccountState.busy && !unsavedMods,
                    onImport = onImportRealm,
                    onExport = onExportRealm,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
                )
                5 -> tabState.SaveableStateProvider("mods") {
                    ModsScreen(
                        editable = state.canEditMods,
                        onEditingChanged = { unsavedMods = it },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                else -> OverviewPanel(
                    state = state,
                    logLines = logLines,
                    managementBusy = botAccountState.busy,
                    onStart = { if (!botAccountState.busy) { if (unsavedMods) selectTab(5) else onStart() } },
                    onStop = { if (!botAccountState.busy) onStop() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RealmSurface)
                .border(width = 1.dp, color = RealmOutline)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("⌂" to "Home", "▤" to "Accounts", "◎" to "Network", ">_" to "Console", "⇧" to "Backup", "+" to "Mods")
                .forEachIndexed { index, (icon, label) ->
                    val selected = selectedTab == index
                    TextButton(
                        onClick = { selectTab(index) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(icon, color = if (selected) RealmGold else RealmMuted, style = MaterialTheme.typography.titleLarge)
                            Text(
                                label,
                                color = if (selected) RealmGold else RealmMuted,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
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
    managementBusy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(222.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF51251C), Color(0xFF201A20), Color(0xFF101A20))
                    )
                )
                .border(1.dp, RealmOutline, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.BottomStart
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD070C10))))
                    .padding(18.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Text("WORLD ARTWORK PLACEHOLDER", color = RealmGold, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("Azeroth awaits", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("BYGDOK ETERNAL · SINGLE-PLAYER REALM", color = RealmMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(
                modifier = Modifier.weight(1f).heightIn(min = 100.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = RealmSurface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("REALM", color = RealmMuted, style = MaterialTheme.typography.labelMedium)
                    Text(if (state.isRunning) "Online" else "Offline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (state.isRunning) "Server is running" else "Server is stopped", color = RealmMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(
                modifier = Modifier.weight(1f).heightIn(min = 100.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = RealmSurface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("CURRENT STATUS", color = RealmMuted, style = MaterialTheme.typography.labelMedium)
                    Text(statusLabel(state.worldserver), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (state.isBusy) phaseLabel(state.phase) else "Worldserver", color = RealmMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Button(
            onClick = if (state.isRunning) onStop else onStart,
            enabled = !state.isBusy && !managementBusy,
            modifier = Modifier.fillMaxWidth().height(58.dp),
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
                Text(if (state.isRunning) "STOP SERVER" else "START SERVER", fontWeight = FontWeight.Bold)
            }
        }
        if (!state.isRunning && state.database == ComponentStatus.RUNNING) {
            TextButton(onClick = onStop, enabled = !state.isBusy && !managementBusy) {
                Text("Stop database")
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = RealmSurface)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("SERVER COMPONENTS", color = RealmMuted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text("3", color = RealmGold, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.height(8.dp))
                StatusLine("Database (MariaDB)", state.database)
                StatusLine("Authserver", state.authserver)
                StatusLine("Worldserver", state.worldserver)
                StatusLine("On-device Ollama", state.ollama)
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = RealmSurface)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("LATEST ACTIVITY", color = RealmMuted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = {
                            val exportedLog = logLines.joinToString("\n")
                            clipboardManager.setText(AnnotatedString(exportedLog))
                            exportedLog.chunked(3500).forEachIndexed { index, chunk ->
                                Log.i("BYGDOK_EXPORT", "BYGDOK_EXPORT[$index] $chunk")
                            }
                        },
                        enabled = logLines.isNotEmpty()
                    ) { Text("COPY LOG", color = RealmGold) }
                }
                if (logLines.isEmpty()) {
                    Text("No server activity yet", color = RealmMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    logLines.takeLast(3).forEach { line ->
                        Text(
                            text = line,
                            color = RealmMuted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, status: ComponentStatus) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(50.dp))
                    .background(if (status == ComponentStatus.RUNNING) Color(0xFF55C98A) else RealmMuted)
            )
            Text(statusLabel(status), color = RealmMuted, style = MaterialTheme.typography.bodySmall)
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
    var botManagement by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = { botManagement = !botManagement }) {
            Text(if (botManagement) "Back to account creation / GM controls" else "Bot links / Guild Mate exclusions")
        }
        if (botManagement) {
            BotAccountsScreen(Modifier.weight(1f))
            return@Column
        }
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
    RealmPhase.EXPORTING_REALM -> "Exporting realm..."
    RealmPhase.STARTING_AUTHSERVER -> "Starting authserver..."
    RealmPhase.PREPARING_CLIENT_DATA -> "Preparing client data..."
    RealmPhase.STARTING_WORLDSERVER -> "Starting worldserver..."
    RealmPhase.STOPPING -> "Stopping realm..."
    else -> "Starting realm..."
}

@Composable
private fun ImportRealmPanel(
    logLines: List<String>,
    enabled: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val importMessage = logLines.lastOrNull { it.startsWith("[import]") || it.startsWith("[backup]") }
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Realm Backup", style = MaterialTheme.typography.titleLarge)
        Text("Stop all components and save mod edits first. Export includes every realm database, client data, " +
            "AI models and memories, imported GPU drivers, configurations and saved app settings.")
        Text("Keep the ZIP outside app storage and keep a copy of this APK. Backups contain credentials and " +
            "provider keys and are not encrypted. Large realms need several GB of space.")
        Button(onClick = onExport, enabled = enabled, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text("EXPORT FULL REALM")
        }
        Text("Restore replaces the stopped realm after checksum validation. The same MariaDB runtime is required. " +
            "Legacy logical backups remain supported.")
        Button(onClick = onImport, enabled = enabled, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text("SELECT BACKUP ARCHIVE")
        }
        if (importMessage != null) Text(importMessage, style = MaterialTheme.typography.bodySmall)
    }
}