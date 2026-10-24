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

import android.content.Context
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
import javax.inject.Singleton

private const val SECURE_DIRECTORY = "secure"
private const val KEY_FILE = "openai_api_key.bin"

/** What the app knows about the stored key, without holding it. */
sealed interface ApiKeyState {
    /** Not read yet. */
    data object Checking : ApiKeyState

    data object Missing : ApiKeyState

    /** A key is stored; [masked] (e.g. `sk-…AbCd`) is all the UI ever shows. */
    data class Stored(
        val masked: String,
    ) : ApiKeyState

    /**
     * Something is stored but can't be decrypted (Keystore key lost after a reset or restore, or the
     * file was altered). The user must enter the key again.
     */
    data object Unreadable : ApiKeyState
}

/**
 * Stores the personal OpenAI API key encrypted at rest. Only the [SecretCipher] output reaches disk,
 * in `noBackupFilesDir/secure/`, which Android never includes in cloud backups or device transfers.
 * The plaintext key exists only in memory while [useKey] runs (and while the user types it).
 */
@Singleton
class OpenAiKeyRepository internal constructor(
    private val directory: File,
    private val cipher: SecretCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        cipher: SecretCipher,
    ) : this(File(context.noBackupFilesDir, SECURE_DIRECTORY), cipher)

    private val keyFile = File(directory, KEY_FILE)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ApiKeyState>(ApiKeyState.Checking)
    val state: StateFlow<ApiKeyState> = _state.asStateFlow()

    /** Reads the stored key's status (decrypting it briefly to build the mask). */
    suspend fun refresh(): ApiKeyState =
        mutex.withLock {
            withContext(ioDispatcher) { readState() }.also { _state.value = it }
        }

    /** Validates locally (no network) and stores [raw] encrypted, replacing any previous key. */
    suspend fun save(raw: String): Result<ApiKeyState> {
        val key = parseApiKey(raw).getOrElse { return Result.failure(it) }
        return mutex.withLock {
            withContext(ioDispatcher) {
                runCatching {
                    val sealed = cipher.encrypt(key.value.toByteArray(Charsets.UTF_8))
                    writeAtomically(sealed.encode())
                    ApiKeyState.Stored(key.masked)
                }
            }.onSuccess { _state.value = it }
        }
    }

    /** Deletes the encrypted file and the Keystore key, so no copy of the old key stays decryptable. */
    suspend fun clear() {
        mutex.withLock {
            withContext(ioDispatcher) {
                keyFile.delete()
                runCatching { cipher.destroyKey() }
            }
            _state.value = ApiKeyState.Missing
        }
    }

    /**
     * Runs [block] with the decrypted key, or returns null when there is no usable key. Callers must
     * not keep, log or persist the key beyond [block].
     */
    suspend fun <T> useKey(block: suspend (ApiKey) -> T): T? {
        val key = mutex.withLock { withContext(ioDispatcher) { decryptOrNull() } } ?: return null
        return block(key)
    }

    private fun readState(): ApiKeyState {
        if (!keyFile.exists()) return ApiKeyState.Missing
        val key = decryptOrNull() ?: return ApiKeyState.Unreadable
        return ApiKeyState.Stored(key.masked)
    }

    private fun decryptOrNull(): ApiKey? {
        if (!keyFile.exists()) return null
        return try {
            val sealed = SealedSecret.decode(keyFile.readBytes())
            ApiKey(String(cipher.decrypt(sealed), Charsets.UTF_8))
        } catch (exc: Exception) {
            // Never include the payload in logs or messages; the state reports the problem instead.
            null
        }
    }

    /** Writes to a temp file then renames it, so a crash never leaves a half-written key file. */
    private fun writeAtomically(bytes: ByteArray) {
        directory.mkdirs()
        val temp = File(directory, "$KEY_FILE.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(keyFile)) {
            temp.delete()
            throw IOException("Could not store the key")
        }
    }
}
