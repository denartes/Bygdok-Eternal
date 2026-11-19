package com.denartes.bygdoketernal

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.denartes.bygdoketernal.realm.BotAccountState
import com.denartes.bygdoketernal.realm.RealmBotAccounts
import com.denartes.bygdoketernal.realm.RealmForegroundService
import com.denartes.bygdoketernal.realm.RealmRuntime

private fun requestBotAccounts(context: Context, operation: String, account: Int = 0, target: Int = 0) {
    try {
        context.startForegroundService(
            Intent(context, RealmForegroundService::class.java)
                .setAction(RealmForegroundService.ACTION_BOT_ACCOUNTS)
                .putExtra(RealmForegroundService.EXTRA_OPERATION, operation)
                .putExtra(RealmForegroundService.EXTRA_ACCOUNT_ID, account)
                .putExtra(RealmForegroundService.EXTRA_TARGET_ACCOUNT_ID, target)
        )
    } catch (error: IllegalStateException) {
        val message = "Could not start database management: ${error.message}"
        RealmRuntime.appendLog("[bot-accounts] $message")
        RealmRuntime.setBotAccounts(BotAccountState(error = message))
    } catch (error: SecurityException) {
        val message = "Database management permission denied: ${error.message}"
        RealmRuntime.appendLog("[bot-accounts] $message")
        RealmRuntime.setBotAccounts(BotAccountState(error = message))
    }
}

@Composable
internal fun BotDatabaseControls(enabled: Boolean = true, showHelp: Boolean = true) {
    val context = LocalContext.current
    val state by RealmRuntime.state.collectAsState()
    val data by RealmRuntime.botAccounts.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showHelp) Text("Uses MariaDB only. Authserver and worldserver must remain stopped.", style = MaterialTheme.typography.bodySmall)
        Button(
            enabled = enabled && state.canEditMods && !data.busy,
            onClick = { requestBotAccounts(context, RealmBotAccounts.LOAD) }
        ) { Text(if (data.busy) "Loading / saving..." else "Load / refresh database") }
        if (!state.canEditMods) Text("Stop the realm before managing bot accounts or guilds.", color = MaterialTheme.colorScheme.primary)
        data.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (showHelp) {
            data.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("MariaDB stays available after loading. Use Home → Stop to stop it.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun BotAccountsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by RealmRuntime.state.collectAsState()
    val data by RealmRuntime.botAccounts.collectAsState()
    val snapshot = data.snapshot
    var mainId by rememberSaveable { mutableStateOf(0) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var mainSearch by rememberSaveable { mutableStateOf("") }
    var pendingOperation by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAccount by rememberSaveable { mutableStateOf(0) }
    var pendingMain by rememberSaveable { mutableStateOf(0) }
    val main = snapshot?.accounts?.find { it.id == mainId }
    val editable = state.canEditMods && !data.busy && snapshot != null

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bot accounts", style = MaterialTheme.typography.titleLarge)
        BotDatabaseControls(showHelp = false)
        TextButton(enabled = editable, onClick = { choosing = true }) {
            Text("Main account: ${main?.name ?: "Select account"}")
        }
        OutlinedTextField(
            value = search, onValueChange = { search = it },
            label = { Text("Search accounts or characters") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        if (snapshot != null) {
            val characters = snapshot.characters.groupBy { it.accountId }
            val guilds = snapshot.guilds.associateBy { it.id }
            val visible = snapshot.accounts.filter { account ->
                account.name.contains(search, true) ||
                    characters[account.id].orEmpty().any { it.name.contains(search, true) }
            }
            Text("${visible.size} accounts", style = MaterialTheme.typography.bodySmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { account ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${account.name} (ID ${account.id})", style = MaterialTheme.typography.titleMedium)
                            Text(characters[account.id].orEmpty().joinToString("\n") {
                                "${it.name} · level ${it.level} · ${guilds[it.guildId]?.name ?: "No guild"}"
                            }.ifEmpty { "No characters" }, style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Exclude from Guild Mate", modifier = Modifier.weight(1f))
                                Switch(checked = account.excluded, enabled = editable, onCheckedChange = { excluded ->
                                    pendingAccount = account.id
                                    pendingMain = 0
                                    pendingOperation = if (excluded) RealmBotAccounts.EXCLUDE else RealmBotAccounts.INCLUDE
                                })
                            }
                            if (main != null && main.id != account.id) {
                                val linked = snapshot.linked(main.id, account.id)
                                val partial = (main.id to account.id) in snapshot.links || (account.id to main.id) in snapshot.links
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (!linked && partial) "Incomplete link: enable to repair" else "Linked to ${main.name}", modifier = Modifier.weight(1f))
                                    Switch(checked = linked, enabled = editable, onCheckedChange = { link ->
                                        pendingAccount = account.id
                                        pendingMain = main.id
                                        pendingOperation = if (link) RealmBotAccounts.LINK else RealmBotAccounts.UNLINK
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (choosing && snapshot != null) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Select your main account") },
            text = {
                Column {
                    OutlinedTextField(value = mainSearch, onValueChange = { mainSearch = it }, label = { Text("Search accounts") })
                    LazyColumn(Modifier.heightIn(max = 350.dp)) {
                        items(snapshot.accounts.filter { it.name.contains(mainSearch, true) }, key = { it.id }) { account ->
                            TextButton(onClick = { mainId = account.id; choosing = false }) {
                                Text("${account.name} (${account.id})")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } }
        )
    }
    pendingOperation?.let { operation ->
        val targetName = snapshot?.accounts?.find { it.id == pendingAccount }?.name ?: "account $pendingAccount"
        val mainName = snapshot?.accounts?.find { it.id == pendingMain }?.name ?: "account $pendingMain"
        AlertDialog(
            onDismissRequest = { pendingOperation = null },
            title = { Text("Confirm account change") },
            text = { Text(when (operation) {
                RealmBotAccounts.LINK -> "Allow $mainName and $targetName to control each other's characters as trusted bots? This does not change guild membership."
                RealmBotAccounts.UNLINK -> "Remove trusted bot access between $mainName and $targetName in both directions?"
                RealmBotAccounts.EXCLUDE -> "Prevent Guild Mate from automatically logging in characters on $targetName? This does not remove account links."
                else -> "Allow Guild Mate to auto-login characters on $targetName when they belong to configured guilds? Do not do this for a human account."
            }) },
            confirmButton = {
                TextButton(enabled = editable, onClick = {
                    requestBotAccounts(
                        context, operation,
                        if (operation in setOf(RealmBotAccounts.LINK, RealmBotAccounts.UNLINK)) pendingMain else pendingAccount,
                        pendingAccount
                    )
                    pendingOperation = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pendingOperation = null }) { Text("Cancel") } }
        )
    }
}

@Composable
internal fun GuildMateGuildSelector(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val data by RealmRuntime.botAccounts.collectAsState()
    val state by RealmRuntime.state.collectAsState()
    val selected = try {
        RealmBotAccounts.guildIds(value)
    } catch (error: IllegalArgumentException) {
        Text(error.message ?: "Invalid guild IDs", color = MaterialTheme.colorScheme.error)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Guild Mate guilds", style = MaterialTheme.typography.titleMedium)
        BotDatabaseControls(enabled)
        Text("Select existing guilds, then Save settings above. Membership is unchanged. Random-bot filtering and human-account exclusions still apply.", style = MaterialTheme.typography.bodySmall)
        data.snapshot?.let { snapshot ->
            if (snapshot.guilds.isEmpty()) Text("No guilds exist yet. Create a guild in-game first.")
            val known = snapshot.guilds.associateBy { it.id }
            for (id in (known.keys + selected).sorted()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = id in selected,
                        enabled = enabled && state.canEditMods && !data.busy,
                        onCheckedChange = { checked ->
                            onChange(RealmBotAccounts.guildValue(if (checked) selected + id else selected - id))
                        }
                    )
                    val members = snapshot.characters.count { it.guildId == id }
                    Text("${known[id]?.name ?: "Missing guild"} (ID $id) · $members members")
                }
            }
        }
    }
}
