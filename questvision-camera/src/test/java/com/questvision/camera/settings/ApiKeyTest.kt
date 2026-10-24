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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyTest {
    private val key = "sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"

    @Test
    fun validKey_isTrimmedAndAccepted() {
        assertEquals(key, parseApiKey("  $key\n").getOrThrow().value)
    }

    @Test
    fun invalidKeys_areRejectedWithAReason() {
        fun reason(raw: String) = (parseApiKey(raw).exceptionOrNull() as InvalidApiKeyException).reason

        assertEquals(ApiKeyInputError.EMPTY, reason("   "))
        assertEquals(ApiKeyInputError.WRONG_PREFIX, reason("pk-AbCdEfGhIjKlMnOpQrSt"))
        assertEquals(ApiKeyInputError.TOO_SHORT, reason("sk-abc"))
        assertEquals(ApiKeyInputError.INVALID_CHARACTERS, reason("sk-AbCdEfGh IjKlMnOpQrSt"))
    }

    @Test
    fun theKeyNeverAppearsInToStringMasksOrErrors() {
        val apiKey = ApiKey(key)

        assertFalse(apiKey.toString().contains(key))
        assertEquals("sk-…6789", apiKey.masked)
        assertTrue(apiKey.toString().contains("sk-…6789"))
        val error = parseApiKey("sk-$key with space").exceptionOrNull()!!
        assertFalse(error.message!!.contains("with space"))
    }

    @Test
    fun sealedSecret_roundTripsAndRejectsMalformedData() {
        val sealed = SealedSecret(ByteArray(12) { it.toByte() }, byteArrayOf(9, 8, 7))
        val decoded = SealedSecret.decode(sealed.encode())

        assertTrue(decoded.iv.contentEquals(sealed.iv))
        assertTrue(decoded.ciphertext.contentEquals(sealed.ciphertext))
        assertTrue(runCatching { SealedSecret.decode(byteArrayOf(2, 12, 0)) }.isFailure)
        assertTrue(runCatching { SealedSecret.decode(byteArrayOf(1, 40, 0, 0)) }.isFailure)
    }

    @Test
    fun defaults_matchTheApprovedDecisions() {
        val settings = AiSettings()

        assertEquals("gpt-6-luna", settings.primaryModel.id)
        assertEquals("gpt-6.1-sol", settings.advancedModel.id)
        assertTrue(settings.processAutomaticCaptures)
        assertFalse(settings.flexProcessing)
        assertEquals(5.0, settings.monthlyBudgetUsd, 0.0)
    }
}
