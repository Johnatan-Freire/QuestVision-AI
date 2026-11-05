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

/** Characters of the token shown after the mask. */
private const val VISIBLE_SUFFIX = 4

/**
 * A Telegram bot token (`123456:ABC…`, from @BotFather), held only while it is used. [toString]
 * never reveals it. It is part of every Bot API URL, so no URL built from it may be logged.
 */
@JvmInline
value class TelegramToken(
    val value: String,
) {
    /** e.g. `…AbCd`. */
    val masked: String get() = "…" + value.takeLast(VISIBLE_SUFFIX)

    override fun toString(): String = "TelegramToken($masked)"
}

enum class TelegramTokenError {
    EMPTY,
    INVALID_FORMAT,
}

/** `<bot id>:<secret>`, as @BotFather issues it. Checked locally: nothing is sent to Telegram. */
private val TOKEN_SHAPE = Regex("""^\d{5,15}:[A-Za-z0-9_\-]{30,64}$""")

/** Trims what pasting adds and checks the token's shape. */
internal fun parseTelegramToken(raw: String): Result<TelegramToken> {
    val trimmed = raw.trim()
    val error =
        when {
            trimmed.isEmpty() -> TelegramTokenError.EMPTY
            !TOKEN_SHAPE.matches(trimmed) -> TelegramTokenError.INVALID_FORMAT
            else -> null
        }
    return if (error == null) Result.success(TelegramToken(trimmed)) else Result.failure(InvalidTelegramTokenException(error))
}

/** Carries a [TelegramTokenError]; its message never contains the token. */
internal class InvalidTelegramTokenException(
    val reason: TelegramTokenError,
) : IllegalArgumentException("Invalid Telegram token: $reason")
