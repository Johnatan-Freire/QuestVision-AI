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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real Android Keystore cipher on a device. Uses a test key, never sends anything anywhere,
 * and removes what it created.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretCipherTest {
    private val cipher = AndroidKeystoreSecretCipher()
    private val secret = "sk-test-NotARealKey-0123456789abcdef".toByteArray()

    @After
    fun tearDown() = cipher.destroyKey()

    @Test
    fun encryptsAndDecrypts_withAFreshIvEachTime() {
        val first = cipher.encrypt(secret)
        val second = cipher.encrypt(secret)

        assertTrue(cipher.decrypt(first).contentEquals(secret))
        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
        assertFalse(String(first.ciphertext, Charsets.ISO_8859_1).contains("sk-test"))
    }

    @Test
    fun tamperedCiphertext_isRejected() {
        val sealed = cipher.encrypt(secret)
        sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 1).toByte()

        assertTrue(runCatching { cipher.decrypt(sealed) }.isFailure)
    }

    @Test
    fun destroyedKey_makesOldDataUndecryptable() {
        val sealed = cipher.encrypt(secret)

        cipher.destroyKey()

        assertTrue(runCatching { cipher.decrypt(sealed) }.isFailure)
    }

    @Test
    fun repository_storesOnlyCiphertext_inNoBackupStorage() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = OpenAiKeyRepository(context, cipher)
            val key = "sk-test-NotARealKey-0123456789abcdef"

            repository.save(key).getOrThrow()

            val file = File(context.noBackupFilesDir, "secure/openai_api_key.bin")
            assertTrue(file.exists())
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("sk-test"))
            assertEquals(key, repository.useKey { it.value })
            repository.clear()
            assertFalse(file.exists())
        }
}
