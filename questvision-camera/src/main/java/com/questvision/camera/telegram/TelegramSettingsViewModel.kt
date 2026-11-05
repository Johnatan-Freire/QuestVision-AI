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
package com.questvision.camera.telegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questvision.camera.settings.AiSettings
import com.questvision.camera.settings.AiSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The outcome of the last "detect chat" or "send test" tap. */
sealed interface TelegramCheck {
    data class ChatFound(
        val firstName: String?,
    ) : TelegramCheck

    data object NoMessages : TelegramCheck

    data object TestSent : TelegramCheck

    /** The stored token can't be read anymore (see [TelegramTokenState.Unreadable]). */
    data object NoToken : TelegramCheck

    data class Failed(
        val error: TelegramError,
    ) : TelegramCheck
}

data class TelegramSettingsUiState(
    val tokenState: TelegramTokenState = TelegramTokenState.Checking,
    val tokenError: TelegramTokenError? = null,
    val tokenSaveFailed: Boolean = false,
    /** A token change or a Bot API call is in progress. */
    val busy: Boolean = false,
    val enabled: Boolean = false,
    val framingHints: Boolean = false,
    val chatId: Long? = null,
    val check: TelegramCheck? = null,
    val lastDelivery: TelegramDelivery? = null,
) {
    val tokenStored: Boolean get() = tokenState is TelegramTokenState.Stored

    /** Sending can be switched on only with a usable token and a chat. */
    val canEnable: Boolean get() = tokenStored && chatId != null
}

/**
 * The Telegram section of the settings. Nothing here sends on its own: the Bot API is called only
 * when the user taps "detect chat" or "send test"; answers go out only once sending is switched on.
 */
@HiltViewModel
class TelegramSettingsViewModel
    @Inject
    constructor(
        private val tokens: TelegramTokenRepository,
        private val notifier: TelegramNotifier,
        private val settingsStore: AiSettingsStore,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(TelegramSettingsUiState().withSettings(settingsStore.load()))
        val uiState: StateFlow<TelegramSettingsUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val state = tokens.refresh()
                _uiState.update { it.copy(tokenState = state) }
            }
            viewModelScope.launch {
                notifier.lastDelivery.collect { delivery -> _uiState.update { it.copy(lastDelivery = delivery) } }
            }
        }

        fun saveToken(raw: String) {
            _uiState.update { it.copy(busy = true, tokenError = null, tokenSaveFailed = false) }
            viewModelScope.launch {
                val result = tokens.save(raw)
                _uiState.update { state ->
                    result.fold(
                        onSuccess = { state.copy(busy = false, tokenState = it, check = null) },
                        onFailure = { exc ->
                            if (exc is InvalidTelegramTokenException) {
                                state.copy(busy = false, tokenError = exc.reason)
                            } else {
                                state.copy(busy = false, tokenSaveFailed = true)
                            }
                        },
                    )
                }
            }
        }

        /** Removes the token and switches sending off. */
        fun removeToken() {
            _uiState.update { it.copy(busy = true) }
            viewModelScope.launch {
                tokens.clear()
                updateSettings { it.copy(telegramEnabled = false) }
                _uiState.update { it.copy(busy = false, tokenState = TelegramTokenState.Missing, check = null) }
            }
        }

        fun dismissTokenError() {
            _uiState.update { it.copy(tokenError = null, tokenSaveFailed = false) }
        }

        /** One `getUpdates` call: the chat of the user's latest message to the bot becomes the destination. */
        fun detectChat() {
            if (_uiState.value.busy || !_uiState.value.tokenStored) return
            _uiState.update { it.copy(busy = true, check = null) }
            viewModelScope.launch {
                val check =
                    when (val result = notifier.findChat()) {
                        null -> {
                            TelegramCheck.NoToken
                        }

                        is TelegramChatResult.Found -> {
                            updateSettings { it.copy(telegramChatId = result.chatId) }
                            TelegramCheck.ChatFound(result.firstName)
                        }

                        TelegramChatResult.NoMessages -> {
                            TelegramCheck.NoMessages
                        }

                        is TelegramChatResult.Failed -> {
                            TelegramCheck.Failed(result.error)
                        }
                    }
                _uiState.update { it.copy(busy = false, check = check) }
            }
        }

        /** One test message to the chosen chat. */
        fun sendTest() {
            val chatId = _uiState.value.chatId ?: return
            if (_uiState.value.busy || !_uiState.value.tokenStored) return
            _uiState.update { it.copy(busy = true, check = null) }
            viewModelScope.launch {
                val check =
                    when (val result = notifier.sendTest(chatId)) {
                        null -> TelegramCheck.NoToken
                        is TelegramSendResult.Sent -> TelegramCheck.TestSent
                        is TelegramSendResult.Failed -> TelegramCheck.Failed(result.error)
                    }
                _uiState.update { it.copy(busy = false, check = check) }
            }
        }

        fun setEnabled(enabled: Boolean) {
            if (enabled && !_uiState.value.canEnable) return
            updateSettings { it.copy(telegramEnabled = enabled) }
        }

        fun setFramingHints(enabled: Boolean) {
            if (enabled && !_uiState.value.canEnable) return
            updateSettings { it.copy(telegramFramingHints = enabled) }
        }

        private fun updateSettings(transform: (AiSettings) -> AiSettings) {
            val updated = transform(settingsStore.load())
            settingsStore.save(updated)
            _uiState.update { it.withSettings(updated) }
        }

        private fun TelegramSettingsUiState.withSettings(settings: AiSettings) =
            copy(enabled = settings.telegramEnabled, framingHints = settings.telegramFramingHints, chatId = settings.telegramChatId)
    }
