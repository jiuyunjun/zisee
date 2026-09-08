package com.zisee.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zisee.app.auth.DisplayName
import com.zisee.app.auth.IdentityRepository
import com.zisee.app.auth.LocalIdentity
import com.zisee.app.core.AppContainer
import com.zisee.app.core.logging.AppEvent
import com.zisee.app.core.logging.AppLogger
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
) : ViewModel() {
    private val mutableIdentity = MutableStateFlow<IdentityState>(IdentityState.Loading)
    val identity = mutableIdentity.asStateFlow()
    private val mutableSave = MutableStateFlow(SaveState())
    val save = mutableSave.asStateFlow()
    private var loadJob: Job? = null

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
                    return ZiseeViewModel(container.identityRepository, container.logger) as T
                }
            }
    }
}
