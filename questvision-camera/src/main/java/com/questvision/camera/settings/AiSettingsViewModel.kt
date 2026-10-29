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
import com.questvision.camera.ai.AUTO_TRIAL_ID
import com.questvision.camera.ai.TRIAL_MAX_REQUESTS_PER_SESSION
import com.questvision.camera.data.QuestionRepository
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
import javax.inject.Named

data class AiSettingsUiState(
    val keyState: ApiKeyState = ApiKeyState.Checking,
    /** Why the last typed key was rejected, if it was. */
    val keyError: ApiKeyInputError? = null,
    /** Storing the key failed (Keystore or disk error). */
    val keySaveFailed: Boolean = false,
    /** A save or removal is in progress. */
    val busy: Boolean = false,
    val settings: AiSettings = AiSettings(),
    /** This is the trial build: the switch to arm real automatic requests is shown. */
    val trialAvailable: Boolean = false,
    /** Requests this trial still allows (its one allowance minus those logged since it was first armed). */
    val trialRequestsLeft: Int = TRIAL_MAX_REQUESTS_PER_SESSION,
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
        private val questions: QuestionRepository,
        @Named(AUTO_TRIAL_ID) private val trialId: Int,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(AiSettingsUiState(settings = settingsStore.load(), trialAvailable = trialId > 0))
        val uiState: StateFlow<AiSettingsUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val keyState = keyRepository.refresh()
                _uiState.update { it.copy(keyState = keyState) }
            }
            if (trialId > 0) viewModelScope.launch { refreshTrial() }
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

        fun selectRequestsPerDay(limit: Int) = updateSettings { it.copy(requestsPerDay = limit) }

        fun selectAnalysesPerSession(limit: Int) = updateSettings { it.copy(analysesPerSession = limit) }

        /**
         * Trial build only: arms or disarms the trial of real automatic requests (each session still
         * asks for consent). The first arming starts its one allowance of
         * [TRIAL_MAX_REQUESTS_PER_SESSION]; once that is used up it can't be armed again.
         */
        fun setAutoTrialArmed(
            armed: Boolean,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            if (trialId <= 0) return
            if (!armed) {
                updateSettings { it.copy(autoTrialArmed = false) }
                return
            }
            viewModelScope.launch {
                if (refreshTrial() > 0) updateSettings { it.armingTrial(trialId, nowMs) }
            }
        }

        private suspend fun refreshTrial(): Int {
            val left =
                settingsStore.load().trialStartedAt(trialId)?.let { since ->
                    (TRIAL_MAX_REQUESTS_PER_SESSION - questions.aiRequestsSince(since)).coerceAtLeast(0)
                } ?: TRIAL_MAX_REQUESTS_PER_SESSION
            _uiState.update { it.copy(trialRequestsLeft = left) }
            return left
        }

        fun selectMaxOutputTokens(tokens: Int) = updateSettings { it.copy(maxOutputTokens = tokens) }

        fun selectImageMaxDimension(pixels: Int) = updateSettings { it.copy(imageMaxDimension = pixels) }

        private fun updateSettings(transform: (AiSettings) -> AiSettings) {
            // From the store, not the cached copy: the analysis panel also writes settings (first-call confirmation).
            val updated = transform(settingsStore.load())
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

    @Binds
    abstract fun bindApiKeySource(repository: OpenAiKeyRepository): ApiKeySource
}
