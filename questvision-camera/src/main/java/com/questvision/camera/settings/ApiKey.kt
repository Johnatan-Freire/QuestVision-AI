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

/** Characters of the key shown after a mask, so the user can tell which key is stored. */
private const val VISIBLE_SUFFIX = 4

/**
 * A personal OpenAI API key, held only while it is being used. [toString] never reveals it, so an
 * accidental log line, exception message or debugger view prints the mask instead of the secret.
 */
@JvmInline
value class ApiKey(
    val value: String,
) {
    /** e.g. `sk-…AbCd`. */
    val masked: String get() = maskApiKey(value)

    override fun toString(): String = "ApiKey($masked)"
}

/** Why a typed key was not accepted. Checked locally only: no request is sent to OpenAI. */
enum class ApiKeyInputError {
    EMPTY,
    WRONG_PREFIX,
    TOO_SHORT,
    INVALID_CHARACTERS,
}

/** Minimum length of a plausible key; real keys are much longer. */
private const val MIN_KEY_LENGTH = 20

private val KEY_CHARACTERS = Regex("""^[A-Za-z0-9_\-]+$""")

/**
 * Trims surrounding whitespace (pasting often adds a newline) and checks the shape of an OpenAI key.
 * Returns the key, or the reason it was rejected.
 */
internal fun parseApiKey(raw: String): Result<ApiKey> {
    val trimmed = raw.trim()
    val error =
        when {
            trimmed.isEmpty() -> ApiKeyInputError.EMPTY
            !trimmed.startsWith("sk-") -> ApiKeyInputError.WRONG_PREFIX
            trimmed.length < MIN_KEY_LENGTH -> ApiKeyInputError.TOO_SHORT
            !KEY_CHARACTERS.matches(trimmed) -> ApiKeyInputError.INVALID_CHARACTERS
            else -> null
        }
    return if (error == null) Result.success(ApiKey(trimmed)) else Result.failure(InvalidApiKeyException(error))
}

/** Carries an [ApiKeyInputError]; its message never contains the key. */
internal class InvalidApiKeyException(
    val reason: ApiKeyInputError,
) : IllegalArgumentException("Invalid API key: $reason")

internal fun maskApiKey(key: String): String = if (key.length <= VISIBLE_SUFFIX + 3) "sk-…" else "sk-…${key.takeLast(VISIBLE_SUFFIX)}"
