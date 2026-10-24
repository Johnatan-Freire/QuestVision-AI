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

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OpenAiKeyRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val cipher = FakeSecretCipher()
    private val directory by lazy { File(folder.root, "secure") }
    private val repository by lazy { OpenAiKeyRepository(directory, cipher, dispatcher) }

    private val key = "sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"
    private val otherKey = "sk-proj-ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210"

    private fun storedBytes(): ByteArray = directory.listFiles()!!.single().readBytes()

    @Test
    fun noKey_isMissing() =
        runTest(dispatcher) {
            assertEquals(ApiKeyState.Missing, repository.refresh())
            assertNull(repository.useKey { it })
        }

    @Test
    fun savedKey_isEncryptedAtRest_andUsable() =
        runTest(dispatcher) {
            val state = repository.save(key).getOrThrow()

            assertEquals(ApiKeyState.Stored("sk-…6789"), state)
            // Only ciphertext on disk: neither the key nor a recognizable part of it.
            val onDisk = String(storedBytes(), Charsets.ISO_8859_1)
            assertFalse(onDisk.contains(key))
            assertFalse(onDisk.contains("sk-proj"))
            assertEquals(key, repository.useKey { it.value })
            // No temporary file left behind.
            assertEquals(listOf("openai_api_key.bin"), directory.list()!!.toList())
        }

    @Test
    fun keySurvivesARestart() =
        runTest(dispatcher) {
            repository.save(key).getOrThrow()

            val restarted = OpenAiKeyRepository(directory, cipher, dispatcher)

            assertEquals(ApiKeyState.Stored("sk-…6789"), restarted.refresh())
            assertEquals(key, restarted.useKey { it.value })
        }

    @Test
    fun replacingTheKey_keepsOnlyTheNewOne() =
        runTest(dispatcher) {
            repository.save(key).getOrThrow()
            repository.save(otherKey).getOrThrow()

            assertEquals(otherKey, repository.useKey { it.value })
            assertEquals(ApiKeyState.Stored("sk-…3210"), repository.state.value)
        }

    @Test
    fun removingTheKey_deletesTheFileAndTheKeystoreKey() =
        runTest(dispatcher) {
            repository.save(key).getOrThrow()
            val oldCiphertext = storedBytes()

            repository.clear()

            assertEquals(ApiKeyState.Missing, repository.state.value)
            assertTrue(directory.list()!!.isEmpty())
            assertTrue(cipher.destroyed)
            // A copy of the old file would no longer decrypt.
            assertTrue(runCatching { cipher.decrypt(SealedSecret.decode(oldCiphertext)) }.isFailure)
        }

    @Test
    fun invalidInput_isNotStored() =
        runTest(dispatcher) {
            val result = repository.save("not-a-key")

            assertTrue(result.exceptionOrNull() is InvalidApiKeyException)
            assertFalse(directory.exists() && directory.list()!!.isNotEmpty())
        }

    @Test
    fun undecryptableData_isReportedAsUnreadable_withoutCrashing() =
        runTest(dispatcher) {
            repository.save(key).getOrThrow()
            // E.g. the Keystore key was lost (factory reset, restore on another phone).
            cipher.destroyed = true

            assertEquals(ApiKeyState.Unreadable, repository.refresh())
            assertNull(repository.useKey { it })
        }

    @Test
    fun corruptedFile_isReportedAsUnreadable() =
        runTest(dispatcher) {
            repository.save(key).getOrThrow()
            directory.listFiles()!!.single().writeBytes(byteArrayOf(1, 12, 5, 5))

            assertEquals(ApiKeyState.Unreadable, repository.refresh())
        }

    @Test
    fun keystoreFailure_isReportedAndNothingIsWritten() =
        runTest(dispatcher) {
            cipher.failEncryption = true

            val result = repository.save(key)

            assertTrue(result.isFailure)
            assertFalse(
                result
                    .exceptionOrNull()!!
                    .message
                    .orEmpty()
                    .contains(key),
            )
            assertFalse(directory.exists() && directory.list()!!.isNotEmpty())
        }
}
