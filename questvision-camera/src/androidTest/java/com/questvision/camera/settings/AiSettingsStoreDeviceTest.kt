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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The trial's arming survives a restart (a new store over the same preferences). Runs in the test
 * APK's own preferences, never the app's.
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
    fun theTrialArming_isPersisted_andOffByDefault() {
        val store = SharedPreferencesAiSettingsStore(context)
        store.save(AiSettings())
        with(SharedPreferencesAiSettingsStore(context).load()) {
            assertFalse(autoTrialArmed)
            assertNull(autoTrialStartedAt)
        }

        store.save(store.load().armingTrial(trialId = 1, nowMs = 1_234_567L).copy(analysesPerSession = 3))
        val restarted = SharedPreferencesAiSettingsStore(context).load()
        assertTrue(restarted.autoTrialArmed)
        assertEquals(1, restarted.autoTrialId)
        assertEquals(1_234_567L, restarted.autoTrialStartedAt)
        assertEquals(3, restarted.analysesPerSession)

        // Disarming keeps when the allowance started: arming again doesn't renew it.
        store.save(restarted.copy(autoTrialArmed = false))
        val rearmed = SharedPreferencesAiSettingsStore(context).load().armingTrial(trialId = 1, nowMs = 9_999_999L)
        assertEquals(1_234_567L, rearmed.autoTrialStartedAt)
    }
}
