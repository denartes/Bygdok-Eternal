package com.denartes.bygdoketernal.realm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ComponentStatus {
    NOT_CONFIGURED,
    STARTING,
    RUNNING,
    STOPPED,
    FAILED
}

enum class RealmPhase {
    IDLE,
    STARTING_DATABASE,
    DATABASE_READY,
    CONFIGURING_INITIAL_SETUP,
    IMPORTING_REALM,
    STARTING_AUTHSERVER,
    PREPARING_CLIENT_DATA,
    STARTING_WORLDSERVER,
    RUNNING,
    FAILED
}

data class RealmState(
    val phase: RealmPhase = RealmPhase.IDLE,
    val database: ComponentStatus = ComponentStatus.NOT_CONFIGURED,
    val authserver: ComponentStatus = ComponentStatus.NOT_CONFIGURED,
    val worldserver: ComponentStatus = ComponentStatus.NOT_CONFIGURED
) {
    val isRunning: Boolean
        get() = phase == RealmPhase.RUNNING &&
            authserver == ComponentStatus.RUNNING &&
            worldserver == ComponentStatus.RUNNING

    val isBusy: Boolean
        get() = phase == RealmPhase.STARTING_DATABASE ||
            phase == RealmPhase.CONFIGURING_INITIAL_SETUP ||
            phase == RealmPhase.IMPORTING_REALM ||
            phase == RealmPhase.STARTING_AUTHSERVER ||
            phase == RealmPhase.PREPARING_CLIENT_DATA ||
            phase == RealmPhase.STARTING_WORLDSERVER
}

data class RealmAccount(
    val id: Int,
    val username: String,
    val gmLevel: Int
)

data class RealmNetworkSettings(
    val realmAddress: String = "",
    val localAddress: String = ""
)

class RealmNetworkSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): RealmNetworkSettings = RealmNetworkSettings(
        realmAddress = preferences.getString(REALM_ADDRESS_KEY, "") ?: "",
        localAddress = preferences.getString(LOCAL_ADDRESS_KEY, "") ?: ""
    )

    fun save(settings: RealmNetworkSettings) {
        preferences.edit()
            .putString(REALM_ADDRESS_KEY, settings.realmAddress)
            .putString(LOCAL_ADDRESS_KEY, settings.localAddress)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "realm-network-settings"
        private const val REALM_ADDRESS_KEY = "realm-address"
        private const val LOCAL_ADDRESS_KEY = "local-address"
    }
}

class RealmSetupStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isComplete(): Boolean = preferences.getBoolean(SETUP_COMPLETE_KEY, false)

    fun realmName(): String = preferences.getString(REALM_NAME_KEY, "AzerothCore") ?: "AzerothCore"

    fun markComplete(realmName: String) {
        preferences.edit()
            .putString(REALM_NAME_KEY, realmName)
            .putBoolean(SETUP_COMPLETE_KEY, true)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "realm-setup"
        private const val SETUP_COMPLETE_KEY = "setup-complete"
        private const val REALM_NAME_KEY = "realm-name"
    }
}

/** In-process shared state between [RealmForegroundService] and the dashboard UI. */
object RealmRuntime {
    private const val MAX_LOG_LINES = 500

    private val _state = MutableStateFlow(RealmState())
    val state = _state.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines = _logLines.asStateFlow()

    private val _accounts = MutableStateFlow<List<RealmAccount>>(emptyList())
    val accounts = _accounts.asStateFlow()

    private val _networkSettings = MutableStateFlow(RealmNetworkSettings())
    val networkSettings = _networkSettings.asStateFlow()

    private val _setupComplete = MutableStateFlow(false)
    val setupComplete = _setupComplete.asStateFlow()

    private val _realmName = MutableStateFlow("AzerothCore")
    val realmName = _realmName.asStateFlow()

    fun updateState(transform: (RealmState) -> RealmState) {
        _state.update(transform)
    }

    fun setAccounts(accounts: List<RealmAccount>) {
        _accounts.value = accounts
    }

    fun setNetworkSettings(settings: RealmNetworkSettings) {
        _networkSettings.value = settings
    }

    fun setSetupComplete(complete: Boolean, realmName: String = _realmName.value) {
        _realmName.value = realmName
        _setupComplete.value = complete
    }

    fun beginDatabaseOnlyStart() {
        clearLog()
        _state.value = RealmState(
            phase = RealmPhase.STARTING_DATABASE,
            database = ComponentStatus.STARTING
        )
    }

    fun beginStart() {
        clearLog()
        _state.value = RealmState(
            phase = RealmPhase.STARTING_DATABASE,
            database = ComponentStatus.STARTING
        )
    }

    fun resetToDefault() {
        _state.value = RealmState()
    }

    fun appendLog(line: String) {
        _logLines.update { (it + line).takeLast(MAX_LOG_LINES) }
    }

    fun clearLog() {
        _logLines.value = emptyList()
    }
}
