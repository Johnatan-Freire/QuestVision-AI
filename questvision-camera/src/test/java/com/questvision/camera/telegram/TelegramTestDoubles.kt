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

/** A syntactically valid, fake token: never a real one. */
internal val TEST_TOKEN = TelegramToken("123456789:AAFakeTokenNotReal_0123456789abcdefXYZ")

internal class FakeTokenSource(
    var token: TelegramToken? = TEST_TOKEN,
) : TelegramTokenSource {
    override suspend fun <T> useToken(block: suspend (TelegramToken) -> T): T? = token?.let { block(it) }
}

/** Plays the Bot API: answers each call from [results] (the last one repeats). */
internal class FakeTelegramApi(
    vararg results: TelegramSendResult,
) : TelegramApi {
    private val queue = ArrayDeque(results.toList().ifEmpty { listOf(TelegramSendResult.Sent(1L)) })
    val sent = mutableListOf<Pair<Long, String>>()
    var chat: TelegramChatResult = TelegramChatResult.NoMessages

    override suspend fun sendMessage(
        token: TelegramToken,
        chatId: Long,
        text: String,
    ): TelegramSendResult {
        sent += chatId to text
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    override suspend fun latestPrivateChat(token: TelegramToken) = chat
}

internal object FakeTelegramTexts : TelegramTexts {
    override fun numbered(
        number: Int,
        body: String,
    ) = "Questão $number: $body"

    override fun unnumbered(body: String) = "Questão: $body"

    override fun needsReview(message: String) = "$message (revisar)"

    override val noneCorrect = "nenhuma alternativa correta"
    override val cannotDetermine = "não foi possível determinar"
    override val test = "teste"

    override fun framing(hint: FramingHint) = "Enquadramento\n${hint.name}"
}
