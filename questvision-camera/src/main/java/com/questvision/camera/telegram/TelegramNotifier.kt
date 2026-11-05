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

import com.questvision.camera.ai.storedAiAnswer
import com.questvision.camera.data.QuestionRepository
import com.questvision.camera.data.TelegramStatus
import com.questvision.camera.settings.AiSettingsStore
import com.questvision.camera.settings.AndroidKeystoreSecretCipher
import com.questvision.camera.settings.SecretCipher
import com.questvision.camera.settings.TELEGRAM_KEY_ALIAS
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Told about every answer the AI analysis stores (manual or automatic). */
fun interface AnswerListener {
    fun onAnswered(questionId: String)

    companion object {
        val None = AnswerListener { }
    }
}

/** The last delivery, for the settings screen: no text, only what happened. */
data class TelegramDelivery(
    val questionNumber: Int?,
    val status: TelegramStatus,
    val error: TelegramError? = null,
    val at: Long,
)

/**
 * Sends each new answer to the user's Telegram chat, when enabled. Guarantees: at most one message
 * per question, ever (an atomic claim in SQLite, kept across restarts); one at a time; a failure or a
 * lost reply is recorded and never resent automatically; answers stored before Telegram was enabled
 * are never sent. Only the question number, the letter and the alternative's text leave the phone.
 */
@Singleton
class TelegramNotifier internal constructor(
    private val questions: QuestionRepository,
    private val tokens: TelegramTokenSource,
    private val api: TelegramApi,
    private val settings: AiSettingsStore,
    private val texts: TelegramTexts,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : AnswerListener {
    @Inject
    constructor(
        questions: QuestionRepository,
        tokens: TelegramTokenSource,
        api: TelegramApi,
        settings: AiSettingsStore,
        texts: TelegramTexts,
    ) : this(questions, tokens, api, settings, texts, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val mutex = Mutex()

    private val _lastDelivery = MutableStateFlow<TelegramDelivery?>(null)
    val lastDelivery: StateFlow<TelegramDelivery?> = _lastDelivery.asStateFlow()

    override fun onAnswered(questionId: String) {
        val config = settings.load()
        if (!config.telegramEnabled || config.telegramChatId == null) return
        scope.launch { deliver(questionId) }
    }

    /** Sends [questionId]'s answer once; returns how it ended, or null when nothing was sent. */
    internal suspend fun deliver(questionId: String): TelegramStatus? =
        mutex.withLock {
            val config = settings.load()
            val chatId = config.telegramChatId
            if (!config.telegramEnabled || chatId == null) return null
            val question = questions.question(questionId) ?: return null
            val answer = storedAiAnswer(question.aiResponse) ?: return null
            if (!questions.claimTelegram(questionId)) return null
            val text = telegramText(answer, question.questionNumber, question.ocrText, texts)
            val result = tokens.useToken { api.sendMessage(it, chatId, text) }
            val (status, error) =
                when (result) {
                    null -> {
                        TelegramStatus.FAILED to null
                    }

                    is TelegramSendResult.Sent -> {
                        TelegramStatus.SENT to null
                    }

                    is TelegramSendResult.Failed -> {
                        (
                            if (result.error ==
                                TelegramError.NETWORK
                            ) {
                                TelegramStatus.UNKNOWN
                            } else {
                                TelegramStatus.FAILED
                            }
                        ) to
                            result.error
                    }
                }
            withContext(NonCancellable) { questions.finishTelegram(questionId, status) }
            _lastDelivery.value = TelegramDelivery(answer.questionNumber ?: question.questionNumber, status, error, clock())
            status
        }

    /** The user's test message; null when no token is stored. */
    suspend fun sendTest(chatId: Long): TelegramSendResult? = tokens.useToken { api.sendMessage(it, chatId, texts.test) }

    /** Finds the chat from the user's latest message to the bot; null when no token is stored. */
    suspend fun findChat(): TelegramChatResult? = tokens.useToken { api.latestPrivateChat(it) }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TelegramModule {
    @Binds
    abstract fun bindTelegramApi(client: TelegramClient): TelegramApi

    @Binds
    abstract fun bindTokenSource(repository: TelegramTokenRepository): TelegramTokenSource

    @Binds
    abstract fun bindTexts(texts: ResourceTelegramTexts): TelegramTexts

    @Binds
    abstract fun bindAnswerListener(notifier: TelegramNotifier): AnswerListener

    @Binds
    abstract fun bindFramingHints(hints: TelegramFramingHints): FramingHints

    companion object {
        @Provides
        @Singleton
        @Named(TELEGRAM_CIPHER)
        fun provideTelegramCipher(): SecretCipher = AndroidKeystoreSecretCipher(TELEGRAM_KEY_ALIAS)
    }
}
