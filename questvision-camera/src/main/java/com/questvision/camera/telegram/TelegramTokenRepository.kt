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

import android.content.Context
import com.questvision.camera.settings.SealedSecret
import com.questvision.camera.settings.SecretCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private const val SECURE_DIRECTORY = "secure"
private const val TOKEN_FILE = "telegram_bot_token.bin"

/** Named [SecretCipher] for the bot token: its own Keystore key, apart from the OpenAI key's. */
const val TELEGRAM_CIPHER = "telegramCipher"

sealed interface TelegramTokenState {
    data object Checking : TelegramTokenState

    data object Missing : TelegramTokenState

    data class Stored(
        val masked: String,
    ) : TelegramTokenState

    /** Stored but no longer decryptable (Keystore key lost): it must be entered again. */
    data object Unreadable : TelegramTokenState
}

/** Lends the decrypted token for one use; a seam for tests. */
interface TelegramTokenSource {
    suspend fun <T> useToken(block: suspend (TelegramToken) -> T): T?
}

/**
 * The bot token, encrypted at rest in `noBackupFilesDir/secure/` with its own Keystore key, like the
 * OpenAI key. The plaintext exists only in memory while [useToken] runs.
 */
@Singleton
class TelegramTokenRepository internal constructor(
    private val directory: File,
    private val cipher: SecretCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TelegramTokenSource {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @Named(TELEGRAM_CIPHER) cipher: SecretCipher,
    ) : this(File(context.noBackupFilesDir, SECURE_DIRECTORY), cipher)

    private val tokenFile = File(directory, TOKEN_FILE)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<TelegramTokenState>(TelegramTokenState.Checking)
    val state: StateFlow<TelegramTokenState> = _state.asStateFlow()

    suspend fun refresh(): TelegramTokenState =
        mutex.withLock {
            withContext(ioDispatcher) { readState() }.also { _state.value = it }
        }

    /** Validates locally (no network) and stores [raw] encrypted, replacing any previous token. */
    suspend fun save(raw: String): Result<TelegramTokenState> {
        val token = parseTelegramToken(raw).getOrElse { return Result.failure(it) }
        return mutex.withLock {
            withContext(ioDispatcher) {
                runCatching {
                    writeAtomically(cipher.encrypt(token.value.toByteArray(Charsets.UTF_8)).encode())
                    TelegramTokenState.Stored(token.masked)
                }
            }.onSuccess { _state.value = it }
        }
    }

    /** Deletes the file and the token's Keystore key (the OpenAI key is untouched). */
    suspend fun clear() {
        mutex.withLock {
            withContext(ioDispatcher) {
                tokenFile.delete()
                runCatching { cipher.destroyKey() }
            }
            _state.value = TelegramTokenState.Missing
        }
    }

    override suspend fun <T> useToken(block: suspend (TelegramToken) -> T): T? {
        val token = mutex.withLock { withContext(ioDispatcher) { decryptOrNull() } } ?: return null
        return block(token)
    }

    private fun readState(): TelegramTokenState {
        if (!tokenFile.exists()) return TelegramTokenState.Missing
        val token = decryptOrNull() ?: return TelegramTokenState.Unreadable
        return TelegramTokenState.Stored(token.masked)
    }

    private fun decryptOrNull(): TelegramToken? {
        if (!tokenFile.exists()) return null
        return try {
            TelegramToken(String(cipher.decrypt(SealedSecret.decode(tokenFile.readBytes())), Charsets.UTF_8))
        } catch (exc: Exception) {
            // Never include the payload anywhere; the state reports the problem.
            null
        }
    }

    private fun writeAtomically(bytes: ByteArray) {
        directory.mkdirs()
        val temp = File(directory, "$TOKEN_FILE.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(tokenFile)) {
            temp.delete()
            throw IOException("Could not store the token")
        }
    }
}
