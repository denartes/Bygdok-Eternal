package com.denartes.bygdoketernal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.denartes.bygdoketernal.realm.ComponentStatus
import com.denartes.bygdoketernal.realm.RealmForegroundService
import com.denartes.bygdoketernal.realm.RealmPhase
import com.denartes.bygdoketernal.realm.RealmRuntime

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op: notification is optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(
                        onStart = {
                            RealmRuntime.beginStart()
                            startForegroundService(Intent(this, RealmForegroundService::class.java))
                        },
                        onStop = {
                            RealmRuntime.resetToDefault()
                            startService(
                                Intent(this, RealmForegroundService::class.java)
                                    .setAction(RealmForegroundService.ACTION_STOP)
                            )
                        }
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
}

@Composable
private fun DashboardScreen(onStart: () -> Unit, onStop: () -> Unit) {
    val state by RealmRuntime.state.collectAsState()
    val logLines by RealmRuntime.logLines.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("BYGDOK ETERNAL", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        StatusCard("Database", state.database)
        StatusCard("Authserver", state.authserver)
        StatusCard("Worldserver", state.worldserver)
        Button(
            onClick = if (state.isRunning) onStop else onStart,
            enabled = !state.isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
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
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Server Log", style = MaterialTheme.typography.titleLarge)
                    TextButton(
                        onClick = {
                            val exportedLog = logLines.joinToString("\n")
                            clipboardManager.setText(AnnotatedString(exportedLog))
                            exportedLog.chunked(3500).forEach { chunk ->
                                Log.i("BYGDOK_EXPORT", chunk)
                            }
                        },
                        enabled = logLines.isNotEmpty()
                    ) {
                        Text("COPY LOG")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logLines) { line ->
                        Text(text = line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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
    RealmPhase.STARTING_AUTHSERVER -> "Starting authserver..."
    RealmPhase.PREPARING_CLIENT_DATA -> "Preparing client data..."
    RealmPhase.STARTING_WORLDSERVER -> "Starting worldserver..."
    else -> "Starting realm..."
}