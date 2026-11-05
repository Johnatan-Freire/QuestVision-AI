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

import com.questvision.camera.ai.FramingHint
import com.questvision.camera.settings.AiSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** No two tips closer than this. */
internal const val FRAMING_MIN_INTERVAL_MS = 20_000L

/** The same tip again only after this long (it is still the problem). */
internal const val FRAMING_REPEAT_MS = 30_000L

/** Told what the monitoring sees wrong with the framing; a seam so the camera screen can be tested. */
interface FramingHints {
    /** [hint] is the problem now; null: the last photo was fine (the next problem is sent right away). */
    fun report(hint: FramingHint?)

    companion object {
        val None =
            object : FramingHints {
                override fun report(hint: FramingHint?) = Unit
            }
    }
}

/**
 * Sends short framing tips ("Enquadramento / vire ← esquerda") to the user's Telegram chat while
 * monitoring, when both Telegram and the tips are switched on. At most one every
 * [FRAMING_MIN_INTERVAL_MS], the same one at most every [FRAMING_REPEAT_MS]; only the tip leaves
 * the phone, never what is on the screen. A failed tip is dropped (the next problem sends another).
 */
@Singleton
class TelegramFramingHints internal constructor(
    private val tokens: TelegramTokenSource,
    private val api: TelegramApi,
    private val settings: AiSettingsStore,
    private val texts: TelegramTexts,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : FramingHints {
    @Inject
    constructor(
        tokens: TelegramTokenSource,
        api: TelegramApi,
        settings: AiSettingsStore,
        texts: TelegramTexts,
    ) : this(tokens, api, settings, texts, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val mutex = Mutex()
    private var lastHint: FramingHint? = null
    private var lastSentAt = Long.MIN_VALUE

    override fun report(hint: FramingHint?) {
        if (hint == null) {
            scope.launch { mutex.withLock { lastHint = null } }
            return
        }
        val config = settings.load()
        val chatId = config.telegramChatId
        if (!config.telegramEnabled || !config.telegramFramingHints || chatId == null) return
        scope.launch { send(hint, chatId) }
    }

    internal suspend fun send(
        hint: FramingHint,
        chatId: Long,
    ): Boolean =
        mutex.withLock {
            val now = clock()
            val sinceLast = if (lastSentAt == Long.MIN_VALUE) Long.MAX_VALUE else now - lastSentAt
            if (sinceLast < FRAMING_MIN_INTERVAL_MS) return false
            if (hint == lastHint && sinceLast < FRAMING_REPEAT_MS) return false
            lastHint = hint
            lastSentAt = now
            tokens.useToken { api.sendMessage(it, chatId, texts.framing(hint)) } is TelegramSendResult.Sent
        }
}
