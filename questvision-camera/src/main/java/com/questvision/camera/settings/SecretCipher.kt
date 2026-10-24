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

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Encryption output: the random nonce (IV) and the ciphertext with its authentication tag. */
class SealedSecret(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    /** `[version][iv length][iv][ciphertext]`. */
    fun encode(): ByteArray = byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ciphertext

    companion object {
        private const val FORMAT_VERSION: Byte = 1

        /** Parses [encode]'s output; throws [IllegalArgumentException] on anything malformed. */
        fun decode(bytes: ByteArray): SealedSecret {
            require(bytes.size > 2 && bytes[0] == FORMAT_VERSION) { "Unknown secret format" }
            val ivLength = bytes[1].toInt() and 0xFF
            require(ivLength in 12..16 && bytes.size > 2 + ivLength) { "Malformed secret" }
            return SealedSecret(
                iv = bytes.copyOfRange(2, 2 + ivLength),
                ciphertext = bytes.copyOfRange(2 + ivLength, bytes.size),
            )
        }
    }
}

/** Authenticated encryption of small secrets with a key that never leaves secure hardware. */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): SealedSecret

    /** Throws if the key is gone or the data was tampered with (GCM authentication fails). */
    fun decrypt(sealed: SealedSecret): ByteArray

    /** Deletes the key: everything it encrypted becomes permanently undecryptable. */
    fun destroyKey()
}

/**
 * AES-256-GCM with a key generated inside the Android Keystore (StrongBox when the device has it,
 * otherwise the TEE). The key material is non-exportable: the app can ask the Keystore to encrypt or
 * decrypt, but neither the app nor a copy of its files can read the key itself.
 *
 * The key deliberately does not require user authentication or an unlocked device, so automatic
 * processing can use it in the background. See docs/PROJECT_STATUS.md for what this does and does
 * not protect against.
 */
@Singleton
class AndroidKeystoreSecretCipher
    @Inject
    constructor() : SecretCipher {
        override fun encrypt(plaintext: ByteArray): SealedSecret {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // The Keystore generates a fresh random IV for every encryption.
            cipher.init(Cipher.ENCRYPT_MODE, keyOrCreate())
            return SealedSecret(cipher.iv, cipher.doFinal(plaintext))
        }

        override fun decrypt(sealed: SealedSecret): ByteArray {
            val key = existingKey() ?: throw java.security.KeyStoreException("Key not found")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
            return cipher.doFinal(sealed.ciphertext)
        }

        override fun destroyKey() {
            keyStore().deleteEntry(KEY_ALIAS)
        }

        private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

        private fun keyOrCreate(): SecretKey = existingKey() ?: generateKey()

        private fun generateKey(): SecretKey {
            fun spec(strongBox: Boolean) =
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
                    .build()

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    generator.init(spec(strongBox = true))
                    return generator.generateKey()
                } catch (exc: ProviderException) {
                    // StrongBoxUnavailableException (a ProviderException): fall back to the TEE.
                }
            }
            generator.init(spec(strongBox = false))
            return generator.generateKey()
        }

        private companion object {
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
            const val KEY_ALIAS = "questvision_openai_api_key_v1"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val KEY_BITS = 256
            const val TAG_BITS = 128
        }
    }
