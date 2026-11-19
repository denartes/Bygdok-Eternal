package com.denartes.bygdoketernal

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.denartes.bygdoketernal.realm.RealmModConfig
import com.denartes.bygdoketernal.realm.RealmModDefinition
import com.denartes.bygdoketernal.realm.RealmMods
import com.denartes.bygdoketernal.realm.RealmRuntime
import com.denartes.bygdoketernal.realm.RealmOllamaConfig
import com.denartes.bygdoketernal.realm.RealmOllamaDriverStore
import com.denartes.bygdoketernal.realm.realmModConfigStore
import java.io.IOException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModsScreen(editable: Boolean, onEditingChanged: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { realmModConfigStore(context) }
    val drivers = remember(context) {
        RealmOllamaDriverStore(File(context.filesDir, "ollama-drivers"), Build.VERSION.SDK_INT)
    }
    val scope = rememberCoroutineScope()
    var configs by remember { mutableStateOf<Map<RealmModDefinition, String>>(emptyMap()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var baseText by rememberSaveable { mutableStateOf("") }
    val draftSaver = remember {
        listSaver<Map<String, String>, String>(
            save = { values -> values.flatMap { listOf(it.key, it.value) } },
            restore = { values -> values.chunked(2).associate { it[0] to it[1] } }
        )
    }
    var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var search by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var driverName by remember { mutableStateOf<String?>(null) }

    fun perform(action: suspend () -> Unit) {
        working = true
        message = null
        failed = false
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                failed = true
                message = "Could not read or save module settings: ${error.message}"
            } catch (error: IllegalArgumentException) {
                failed = true
                message = error.message ?: "Invalid module configuration"
            } catch (error: IllegalStateException) {
                failed = true
                message = error.message ?: "Module configuration is unavailable"
            } finally {
                if (failed) RealmRuntime.appendLog("[mods] $message")
                working = false
            }
        }
    }

    val importDriver = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            val driver = withContext(Dispatchers.IO) {
                var name = ""
                RealmRuntime.editModSettings {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not open the selected driver ZIP")
                    input.use { name = drivers.install(it).name }
                }
                name
            }
            driverName = driver
            message = "Custom driver imported: $driver. Ollama will verify it on the next realm start."
        }
    }

    fun load() = perform {
        configs = withContext(Dispatchers.IO) { RealmMods.all.associateWith(store::load) }
        driverName = withContext(Dispatchers.IO) { drivers.selected()?.name }
    }

    LaunchedEffect(store) { load() }
    val selected = RealmMods.all.find { it.id == selectedId }
    val dirty = baseText.isNotEmpty() && RealmModConfig.values(baseText) != draft
    LaunchedEffect(dirty) { onEditingChanged(dirty) }
    val canEdit = editable && !working && configs.size == RealmMods.all.size
    val botsEnabled = configs[RealmMods.playerbots]?.let { RealmModConfig.enabled(RealmMods.playerbots, it) } ?: false

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mods", style = MaterialTheme.typography.titleLarge)
        Text(
            "Changes apply on the next server start. Saved settings survive compatible app updates, not uninstall or Clear data.",
            style = MaterialTheme.typography.bodySmall
        )
        if (!editable) Text("Stop the server before changing mods.", color = MaterialTheme.colorScheme.primary)
        if (dirty) Text("Unsaved settings. Save or discard before starting the server.", color = MaterialTheme.colorScheme.primary)
        if (working) Text("Loading / saving settings...")
        message?.let {
            Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        if (configs.isEmpty() && !working) {
            Button(onClick = ::load) { Text("Retry loading settings") }
        } else if (selected == null || baseText.isEmpty()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RealmMods.all, key = { it.id }) { mod ->
                    val config = configs[mod]
                    val requested = config?.let { RealmModConfig.enabled(mod, it) } ?: false
                    val blocked = mod.requiresPlayerbots && !botsEnabled
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(mod.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Switch(
                                    checked = requested && !blocked,
                                    enabled = canEdit && !blocked,
                                    onCheckedChange = { enabled ->
                                        perform {
                                            withContext(Dispatchers.IO) {
                                                RealmRuntime.editModSettings { store.setEnabled(mod, enabled) }
                                            }
                                            configs = configs + (mod to withContext(Dispatchers.IO) { store.load(mod) })
                                            message = "${mod.title} saved."
                                        }
                                    }
                                )
                            }
                            if (blocked) Text(
                                "Requires Playerbots. Your own enable preference is retained.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (mod.warning.isNotEmpty()) Text(mod.warning, style = MaterialTheme.typography.bodySmall)
                            if (mod.id == "mod-ollama-chat" && config != null) {
                                val managed = RealmOllamaConfig.managed(config)
                                Text(
                                    if (managed) "On-device Ollama: qwen2.5:0.5b. Missing model files download at the next realm start."
                                    else "Remote Ollama selected. On-device mode runs inside Bygdok without Termux.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(
                                    enabled = canEdit,
                                    onClick = {
                                        perform {
                                            withContext(Dispatchers.IO) {
                                                RealmRuntime.editModSettings {
                                                    store.save(mod, RealmOllamaConfig.selectManaged(config, !managed))
                                                }
                                            }
                                            configs = configs + (mod to withContext(Dispatchers.IO) { store.load(mod) })
                                            message = if (managed) "Remote Ollama selected. Saved endpoint settings retained."
                                                else "On-device Ollama selected. Enable Ollama Chat, then start the realm to download the model."
                                        }
                                    }
                                ) { Text(if (managed) "Use remote Ollama" else "Use on-device Ollama (allows ~400 MB download)") }
                                Text(
                                   driverName?.let { "Custom Vulkan driver: $it" } ?: "Vulkan driver: Android system",
                                   style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                   "Import only trusted AdrenoTools driver ZIPs: native driver code runs with Ollama's app permissions. Only local AI uses this driver; failed verification uses CPU.",
                                   style = MaterialTheme.typography.bodySmall
                                )
                                Row {
                                   TextButton(
                                       enabled = canEdit,
                                       onClick = { importDriver.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
                                   ) { Text("Import driver ZIP") }
                                   TextButton(
                                       enabled = canEdit,
                                       onClick = {
                                           perform {
                                               withContext(Dispatchers.IO) {
                                                   RealmRuntime.editModSettings { drivers.remove() }
                                               }
                                               driverName = null
                                               message = "Custom driver removed. Ollama will use the system driver."
                                           }
                                       }
                                   ) { Text("Use system driver") }
                                }
                            }
                            TextButton(
                                enabled = config != null && !working,
                                onClick = {
                                    baseText = config.orEmpty()
                                    draft = RealmModConfig.values(baseText)
                                    selectedId = mod.id
                                    search = ""
                                    message = null
                                }
                            ) { Text("Configure") }
                        }
                    }
                }
            }
        } else {
            Text(selected.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "Advanced settings: use the existing value format (including quotes). Invalid values or paths can prevent startup.",
                style = MaterialTheme.typography.bodySmall
            )
            if (selected.requiresPlayerbots && !botsEnabled) {
                Text("This mod stays inactive until Playerbots is enabled.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search settings") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canEdit,
                    onClick = {
                        perform {
                            val text = RealmModConfig.replace(baseText, draft)
                            withContext(Dispatchers.IO) {
                                RealmRuntime.editModSettings { store.save(selected, text) }
                            }
                            configs = configs + (selected to withContext(Dispatchers.IO) { store.load(selected) })
                            baseText = configs.getValue(selected)
                            message = "${selected.title} settings saved."
                        }
                    }
                ) { Text("Save settings") }
                TextButton(enabled = !working, onClick = { selectedId = null; baseText = ""; message = null }) {
                    Text("Back / discard edits")
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selected.id == "mod-guild-mate") {
                    item {
                        GuildMateGuildSelector(
                            value = draft["GuildMate.GuildIds"].orEmpty(),
                            enabled = canEdit,
                            onChange = { draft = draft + ("GuildMate.GuildIds" to it) }
                        )
                    }
                }
                items(draft.keys.filter { it != RealmModConfig.ENABLED_KEY && it.contains(search, ignoreCase = true) }, key = { it }) { key ->
                    OutlinedTextField(
                        value = draft.getValue(key),
                        onValueChange = { value -> draft = draft + (key to value) },
                        enabled = canEdit,
                        label = { Text(key) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
