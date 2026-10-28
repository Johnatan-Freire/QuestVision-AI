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
package com.questvision.camera.ai

import com.questvision.camera.data.AnalysisMode
import com.questvision.camera.data.TokenUsage
import com.questvision.camera.settings.AiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CostEstimateTest {
    private val usage =
        TokenUsage(inputTokens = 2_000, cachedTokens = 500, outputTokens = 300, reasoningTokens = 100, cacheWriteTokens = 1_000)

    @Test
    fun observedCost_usesTheReportedTokens_andTheTierThatServedTheRequest() {
        // gpt-6-luna Standard: input 0.10, cached 0.01, output 0.50; cache writes 1.25x input.
        val standard = (500 * 0.10 + 500 * 0.01 + 1_000 * 0.10 * 1.25 + 300 * 0.50) / 1_000_000.0

        assertEquals(standard, observedCostUsd(AiModel.GPT_6_LUNA, usage, "default")!!, 1e-15)
        assertEquals(standard / 2, observedCostUsd(AiModel.GPT_6_LUNA, usage, "flex")!!, 1e-15)
        assertEquals(standard * 2, observedCostUsd(AiModel.GPT_6_LUNA, usage, "priority")!!, 1e-15)
    }

    @Test
    fun observedCost_isNeverMadeUp() {
        assertNull(observedCostUsd(AiModel.GPT_6_LUNA, usage, null))
        assertNull(observedCostUsd(AiModel.GPT_6_LUNA, usage, "scale"))
        assertNull(observedCostUsd(AiModel.GPT_6_LUNA, null, "default"))
        assertNull(observedCostUsd(AiModel.GPT_6_LUNA, usage.copy(outputTokens = null), "default"))
        assertNull(observedCostUsd(null, usage, "default"))
    }

    @Test
    fun theEstimate_boundsTheOutput_andSaysWhenItLeavesTheImageOut() {
        val text = estimateCost(AiModel.GPT_6_LUNA, AnalysisMode.TEXT, questionChars = 600, maxOutputTokens = 1_500, flex = false)
        val image = estimateCost(AiModel.GPT_6_LUNA, AnalysisMode.IMAGE, questionChars = 600, maxOutputTokens = 1_500, flex = false)
        val flex = estimateCost(AiModel.GPT_6_LUNA, AnalysisMode.TEXT, questionChars = 600, maxOutputTokens = 1_500, flex = true)

        assertEquals(1_500 * 0.50 / 1_000_000.0, text.maxOutputUsd, 1e-15)
        assertFalse(text.excludesImage)
        assertTrue(image.excludesImage)
        assertEquals(text.totalUsd / 2, flex.totalUsd, 1e-15)
    }
}
