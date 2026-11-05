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

import com.questvision.camera.settings.FakeSecretCipher
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramTokenRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val secure get() = File(folder.root, "secure")

    @Test
    fun theToken_isStoredEncrypted_inItsOwnFile() =
        runTest(dispatcher) {
            val repository = TelegramTokenRepository(secure, FakeSecretCipher(), dispatcher)

            assertEquals(TelegramTokenState.Stored(TEST_TOKEN.masked), repository.save(TEST_TOKEN.value).getOrThrow())
            val file = File(secure, "telegram_bot_token.bin")
            assertTrue(file.exists())
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(TEST_TOKEN.value))
            assertEquals(TEST_TOKEN, repository.useToken { it })
            // A new instance (a restart) reads it back.
            assertEquals(
                TelegramTokenState.Stored(TEST_TOKEN.masked),
                TelegramTokenRepository(secure, FakeSecretCipher(), dispatcher).refresh(),
            )
        }

    @Test
    fun removingTheToken_leavesTheOpenAiKeyAlone() =
        runTest(dispatcher) {
            val openAiKey =
                File(secure, "openai_api_key.bin").apply {
                    parentFile!!.mkdirs()
                    writeBytes(byteArrayOf(1, 2, 3))
                }
            val cipher = FakeSecretCipher()
            val repository = TelegramTokenRepository(secure, cipher, dispatcher)
            repository.save(TEST_TOKEN.value)

            repository.clear()

            assertEquals(TelegramTokenState.Missing, repository.state.value)
            assertNull(repository.useToken { it })
            assertTrue(cipher.destroyed)
            assertTrue(openAiKey.exists())
        }

    @Test
    fun aLostKeystoreKey_makesItUnreadable() =
        runTest(dispatcher) {
            val cipher = FakeSecretCipher()
            val repository = TelegramTokenRepository(secure, cipher, dispatcher)
            repository.save(TEST_TOKEN.value)
            cipher.destroyed = true

            assertEquals(TelegramTokenState.Unreadable, repository.refresh())
        }

    @Test
    fun aBadToken_isRejected_andNothingIsWritten() =
        runTest(dispatcher) {
            val repository = TelegramTokenRepository(secure, FakeSecretCipher(), dispatcher)
            val error = repository.save("not-a-token").exceptionOrNull() as InvalidTelegramTokenException

            assertEquals(TelegramTokenError.INVALID_FORMAT, error.reason)
            assertFalse(File(secure, "telegram_bot_token.bin").exists())
        }
}
