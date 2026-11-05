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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Automatic sending and the daily limit survive a restart (a new store over the same preferences).
 * Runs in the test APK's own preferences, never the app's.
 */
@RunWith(AndroidJUnit4::class)
class AiSettingsStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var before: AiSettings

    @Before
    fun remember() {
        before = SharedPreferencesAiSettingsStore(context).load()
    }

    @After
    fun restore() = SharedPreferencesAiSettingsStore(context).save(before)

    @Test
    fun automaticSending_isPersisted_andOffByDefault() {
        val store = SharedPreferencesAiSettingsStore(context)
        store.save(AiSettings())
        with(SharedPreferencesAiSettingsStore(context).load()) {
            assertFalse(autoSendEnabled)
            assertEquals(75, requestsPerDay)
        }

        store.save(store.load().copy(autoSendEnabled = true, requestsPerDay = 20, telegramChatId = 42L))
        val restarted = SharedPreferencesAiSettingsStore(context).load()
        assertTrue(restarted.autoSendEnabled)
        assertEquals(20, restarted.requestsPerDay)
        assertEquals(42L, restarted.telegramChatId)
    }
}
