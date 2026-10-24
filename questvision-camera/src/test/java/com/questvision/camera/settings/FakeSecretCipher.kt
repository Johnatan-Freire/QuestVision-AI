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

import java.security.GeneralSecurityException
import kotlin.random.Random

/**
 * Stand-in for the Keystore cipher on the JVM: a random IV per call, a keystream that hides the
 * plaintext, and an integrity byte so tampering or a destroyed key makes decryption fail.
 */
internal class FakeSecretCipher : SecretCipher {
    var keyGeneration = 1
    var destroyed = false
    var failEncryption = false

    override fun encrypt(plaintext: ByteArray): SealedSecret {
        if (failEncryption) throw GeneralSecurityException("Keystore unavailable")
        destroyed = false
        val iv = Random.nextBytes(12)
        val body = transform(plaintext, iv)
        return SealedSecret(iv, body + checksum(plaintext))
    }

    override fun decrypt(sealed: SealedSecret): ByteArray {
        if (destroyed) throw GeneralSecurityException("Key not found")
        val body = sealed.ciphertext.copyOf(sealed.ciphertext.size - 1)
        val plaintext = transform(body, sealed.iv)
        if (checksum(plaintext).single() != sealed.ciphertext.last()) throw GeneralSecurityException("Tag mismatch")
        return plaintext
    }

    override fun destroyKey() {
        destroyed = true
        keyGeneration++
    }

    private fun transform(
        data: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        val stream = Random(iv.contentHashCode() * 31 + keyGeneration)
        return ByteArray(data.size) { i -> (data[i].toInt() xor stream.nextInt(256)).toByte() }
    }

    private fun checksum(data: ByteArray) = byteArrayOf(data.fold(keyGeneration) { acc, b -> acc * 31 + b }.toByte())
}
