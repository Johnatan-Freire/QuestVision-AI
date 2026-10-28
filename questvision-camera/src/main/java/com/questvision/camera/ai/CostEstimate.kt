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

/** Rough characters per token for Portuguese text: an approximation, not a tokenizer. */
private const val CHARS_PER_TOKEN = 3

/** Cache writes are billed at 1.25x the uncached input rate (OpenAI pricing page, 2026-10-28). */
private const val CACHE_WRITE_MULTIPLIER = 1.25

/**
 * Price multiplier of the `service_tier` that served a request, over the Standard prices in [AiModel]
 * (OpenAI pricing page, 2026-10-28): Flex at 50%, Fast (reported as `priority`) at 2x. Null for a tier
 * whose price the app does not know, so no cost is made up for it.
 */
internal fun tierMultiplier(serviceTier: String?): Double? =
    when (serviceTier) {
        "default" -> 1.0
        "flex" -> 0.5
        "priority", "fast" -> 2.0
        else -> null
    }

/**
 * What one request is expected to cost before it is sent (USD). An estimate, never a cap: the bill is
 * whatever OpenAI counts.
 */
data class CostEstimate(
    /** Instructions, schema and question at [CHARS_PER_TOKEN] characters per token, uncached. Approximate. */
    val textInputUsd: Double,
    /** `max_output_tokens` at the output price: the output (reasoning included) can't be billed more. */
    val maxOutputUsd: Double,
    /**
     * Image mode: the photo's own tokens are not included. OpenAI documents no image token count for
     * the models used here (images-vision guide, 2026-10-28), so none is guessed; the observed cost of
     * the first image request is the reference.
     */
    val excludesImage: Boolean,
) {
    val totalUsd: Double get() = textInputUsd + maxOutputUsd
}

fun estimateCost(
    model: AiModel,
    mode: AnalysisMode,
    questionChars: Int,
    maxOutputTokens: Int,
    flex: Boolean,
): CostEstimate {
    val multiplier = if (flex) 0.5 else 1.0
    val promptChars = if (mode == AnalysisMode.IMAGE) IMAGE_PROMPT.length else questionChars
    val textTokens = (AI_INSTRUCTIONS.length + promptChars + ANSWER_SCHEMA.toString().length) / CHARS_PER_TOKEN
    return CostEstimate(
        textInputUsd = textTokens * model.inputPerMillion * multiplier / 1_000_000.0,
        maxOutputUsd = maxOutputTokens * model.outputPerMillion * multiplier / 1_000_000.0,
        excludesImage = mode == AnalysisMode.IMAGE,
    )
}

/**
 * The cost of a request computed from the token counts OpenAI reported for it and the published prices
 * of the tier that served it (USD). Null when the counts or the tier are missing or unknown. It is a
 * calculation, not the bill: OpenAI's usage dashboard is authoritative.
 */
fun observedCostUsd(
    model: AiModel?,
    usage: TokenUsage?,
    serviceTier: String?,
): Double? {
    val input = usage?.inputTokens ?: return null
    val output = usage.outputTokens ?: return null
    val multiplier = tierMultiplier(serviceTier) ?: return null
    if (model == null) return null
    val cached = usage.cachedTokens ?: 0
    val written = usage.cacheWriteTokens ?: 0
    val plain = (input - cached - written).coerceAtLeast(0)
    val usd =
        plain * model.inputPerMillion +
            cached * model.cachedInputPerMillion +
            written * model.inputPerMillion * CACHE_WRITE_MULTIPLIER +
            output * model.outputPerMillion
    return usd * multiplier / 1_000_000.0
}
