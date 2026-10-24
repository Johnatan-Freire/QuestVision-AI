/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.questvision.camera.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiSettingsUiState(
    val keyState: ApiKeyState = ApiKeyState.Checking,
    /** Why the last typed key was rejected, if it was. */
    val keyError: ApiKeyInputError? = null,
    /** Storing the key failed (Keystore or disk error). */
    val keySaveFailed: Boolean = false,
    /** A save or removal is in progress. */
    val busy: Boolean = false,
    val settings: AiSettings = AiSettings(),
)

/**
 * OpenAI settings: the encrypted API key and the model choices. No request is ever sent to OpenAI
 * from here (no credits are used); the key is only checked for shape.
 */
@HiltViewModel
class AiSettingsViewModel
    @Inject
    constructor(
        private val keyRepository: OpenAiKeyRepository,
        private val settingsStore: AiSettingsStore,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(AiSettingsUiState(settings = settingsStore.load()))
        val uiState: StateFlow<AiSettingsUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val keyState = keyRepository.refresh()
                _uiState.update { it.copy(keyState = keyState) }
            }
        }

        /** Stores [raw] encrypted, replacing any previous key. */
        fun saveKey(raw: String) {
            _uiState.update { it.copy(busy = true, keyError = null, keySaveFailed = false) }
            viewModelScope.launch {
                val result = keyRepository.save(raw)
                _uiState.update { state ->
                    result.fold(
                        onSuccess = { state.copy(keyState = it, busy = false) },
                        onFailure = { error ->
                            state.copy(
                                busy = false,
                                keyError = (error as? InvalidApiKeyException)?.reason,
                                keySaveFailed = error !is InvalidApiKeyException,
                            )
                        },
                    )
                }
            }
        }

        fun removeKey() {
            _uiState.update { it.copy(busy = true, keyError = null, keySaveFailed = false) }
            viewModelScope.launch {
                keyRepository.clear()
                _uiState.update { it.copy(keyState = ApiKeyState.Missing, busy = false) }
            }
        }

        fun dismissKeyError() {
            _uiState.update { it.copy(keyError = null, keySaveFailed = false) }
        }

        fun selectPrimaryModel(model: AiModel) = updateSettings { it.copy(primaryModel = model) }

        fun selectAdvancedModel(model: AiModel) = updateSettings { it.copy(advancedModel = model) }

        private fun updateSettings(transform: (AiSettings) -> AiSettings) {
            val updated = transform(_uiState.value.settings)
            settingsStore.save(updated)
            _uiState.update { it.copy(settings = updated) }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AiSettingsModule {
    @Binds
    abstract fun bindSecretCipher(cipher: AndroidKeystoreSecretCipher): SecretCipher

    @Binds
    abstract fun bindAiSettingsStore(store: SharedPreferencesAiSettingsStore): AiSettingsStore
}
