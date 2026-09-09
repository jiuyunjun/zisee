package com.lazydoglab.zisee.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lazydoglab.zisee.auth.DisplayName
import com.lazydoglab.zisee.auth.IdentityRepository
import com.lazydoglab.zisee.auth.LocalIdentity
import com.lazydoglab.zisee.auth.remote.BackendRunner
import com.lazydoglab.zisee.auth.remote.ConnectionState
import com.lazydoglab.zisee.core.AppContainer
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface IdentityState {
    data object Loading : IdentityState
    data object Welcome : IdentityState
    data class Ready(val identity: LocalIdentity) : IdentityState
    data object ReadError : IdentityState
}

enum class SaveError { INVALID_NAME, STORAGE }
data class SaveState(val busy: Boolean = false, val error: SaveError? = null)

class ZiseeViewModel(
    private val repository: IdentityRepository,
    private val logger: AppLogger,
    private val backend: BackendRunner? = null,
) : ViewModel() {
    private val mutableIdentity = MutableStateFlow<IdentityState>(IdentityState.Loading)
    val identity = mutableIdentity.asStateFlow()
    private val mutableSave = MutableStateFlow(SaveState())
    val save = mutableSave.asStateFlow()
    private var loadJob: Job? = null
    private val mutableConnection = MutableStateFlow(
        if (backend == null) ConnectionState.NOT_CONFIGURED else ConnectionState.DISCONNECTED,
    )
    val connection = mutableConnection.asStateFlow()
    private var connectionJob: Job? = null
    private var connectionRequested = false
    private var foreground = false
    private var generation = 0

    fun setForeground(value: Boolean) {
        foreground = value
        if (value && connectionRequested) connectBackend() else if (!value) stopConnection()
    }

    fun connectBackend() {
        if (backend == null || !foreground || connectionJob?.isActive == true) return
        val identity = (mutableIdentity.value as? IdentityState.Ready)?.identity ?: return
        connectionRequested = true
        val current = ++generation
        connectionJob = viewModelScope.launch {
            backend.run(identity) { if (current == generation) mutableConnection.value = it }
            // Terminal errors require an explicit user retry, even after a foreground transition.
            if (current == generation) connectionRequested = false
        }
    }

    fun disconnectBackend() {
        connectionRequested = false
        stopConnection()
    }

    private fun stopConnection() {
        generation++
        connectionJob?.cancel()
        connectionJob = null
        mutableConnection.value = if (backend == null) ConnectionState.NOT_CONFIGURED else ConnectionState.DISCONNECTED
    }

    init { load() }

    fun load() {
        loadJob?.cancel()
        mutableIdentity.value = IdentityState.Loading
        loadJob = viewModelScope.launch {
            try {
                repository.identity.collect { identity ->
                    mutableIdentity.value = identity?.let(IdentityState::Ready) ?: IdentityState.Welcome
                }
            } catch (error: IOException) {
                logger.error(AppEvent.IDENTITY_READ_FAILED)
                mutableIdentity.value = IdentityState.ReadError
            }
        }
    }

    fun saveName(name: String) {
        if (mutableSave.value.busy) return
        val state = mutableIdentity.value
        if (state !is IdentityState.Ready && state != IdentityState.Welcome) return
        mutableSave.value = SaveState(busy = true)
        viewModelScope.launch {
            try {
                val normalized = DisplayName.normalize(name)
                if (state is IdentityState.Ready) repository.rename(normalized)
                else repository.create(normalized)
                mutableSave.value = SaveState()
            } catch (error: IllegalArgumentException) {
                mutableSave.value = SaveState(error = SaveError.INVALID_NAME)
            } catch (error: IOException) {
                logger.error(AppEvent.IDENTITY_WRITE_FAILED)
                mutableSave.value = SaveState(error = SaveError.STORAGE)
            } finally {
                mutableSave.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ZiseeViewModel::class.java))
                    @Suppress("UNCHECKED_CAST")
                    return ZiseeViewModel(container.identityRepository, container.logger, container.backend) as T
                }
            }
    }
}
