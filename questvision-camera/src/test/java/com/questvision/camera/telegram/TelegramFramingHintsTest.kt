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

import com.questvision.camera.ai.FramingHint
import com.questvision.camera.ai.InMemoryAiSettingsStore
import com.questvision.camera.settings.AiSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramFramingHintsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val api = FakeTelegramApi()
    private val settings =
        InMemoryAiSettingsStore(AiSettings(telegramEnabled = true, telegramChatId = 42L, telegramFramingHints = true))
    private var now = 1_000_000L

    private fun hints(scope: TestScope) = TelegramFramingHints(FakeTokenSource(), api, settings, FakeTelegramTexts, scope) { now }

    @Test
    fun aTip_isShort_titledFraming() =
        runTest(dispatcher) {
            hints(this).report(FramingHint.LEFT)

            assertEquals(listOf(42L to "Enquadramento\nLEFT"), api.sent)
        }

    @Test
    fun tips_areThrottled() =
        runTest(dispatcher) {
            val hints = hints(this)
            hints.report(FramingHint.LEFT)
            now += 5_000
            hints.report(FramingHint.DOWN) // too soon after the last one
            now += FRAMING_MIN_INTERVAL_MS
            hints.report(FramingHint.DOWN)
            now += FRAMING_MIN_INTERVAL_MS
            hints.report(FramingHint.DOWN) // the same tip again: only after a minute
            now += FRAMING_REPEAT_MS
            hints.report(FramingHint.DOWN)

            assertEquals(listOf("LEFT", "DOWN", "DOWN"), api.sent.map { it.second.substringAfter("\n") })
        }

    // Once a capture is fine, the next problem (even the same one) is news.
    @Test
    fun aGoodCapture_resetsTheRepeatRule() =
        runTest(dispatcher) {
            val hints = hints(this)
            hints.report(FramingHint.DOWN)
            hints.report(null)
            now += FRAMING_MIN_INTERVAL_MS
            hints.report(FramingHint.DOWN)

            assertEquals(2, api.sent.size)
        }

    @Test
    fun nothingIsSent_unlessTelegramAndTipsAreOn() =
        runTest(dispatcher) {
            for (config in listOf(
                settings.settings.copy(telegramFramingHints = false),
                settings.settings.copy(telegramEnabled = false),
                settings.settings.copy(telegramChatId = null),
            )) {
                settings.settings = config
                hints(this).report(FramingHint.UP)
            }
            assertTrue(api.sent.isEmpty())
        }
}
