package com.denartes.bygdoketernal.realm

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
            phase == RealmPhase.STARTING_AUTHSERVER ||
            phase == RealmPhase.PREPARING_CLIENT_DATA ||
            phase == RealmPhase.STARTING_WORLDSERVER
}

/** In-process shared state between [RealmForegroundService] and the dashboard UI. */
object RealmRuntime {
    private const val MAX_LOG_LINES = 500

    private val _state = MutableStateFlow(RealmState())
    val state = _state.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines = _logLines.asStateFlow()

    fun updateState(transform: (RealmState) -> RealmState) {
        _state.update(transform)
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
