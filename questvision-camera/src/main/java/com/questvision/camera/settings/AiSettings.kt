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

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI models the app can use, with official Standard prices in USD per 1M tokens (input / cached
 * input / output) as published on developers.openai.com/api/docs/pricing (checked again on
 * 2026-10-28). Used for local cost calculations only; the bill is what OpenAI counts.
 */
enum class AiModel(
    val id: String,
    val inputPerMillion: Double,
    val cachedInputPerMillion: Double,
    val outputPerMillion: Double,
) {
    GPT_6_LUNA("gpt-6-luna", 0.10, 0.01, 0.50),
    GPT_5_6_LUNA("gpt-5.6-luna", 0.20, 0.02, 1.20),
    GPT_6_1_SOL("gpt-6.1-sol", 2.00, 0.10, 10.00),
    GPT_6_ASTRA("gpt-6-astra", 10.00, 1.00, 50.00),
    ;

    companion object {
        /** Used automatically for every new automatic capture. */
        val primaryOptions = listOf(GPT_6_LUNA, GPT_5_6_LUNA)

        /** Used only on demand (e.g. to re-check an uncertain answer). */
        val advancedOptions = listOf(GPT_6_1_SOL, GPT_6_ASTRA)

        fun fromId(id: String?): AiModel? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How the (future) AI processing behaves. Nothing here is secret; the API key lives in
 * [OpenAiKeyRepository].
 */
data class AiSettings(
    val primaryModel: AiModel = AiModel.GPT_6_LUNA,
    val advancedModel: AiModel = AiModel.GPT_6_1_SOL,
    /** Automatic captures go to the primary model without asking; manual ones only on command. */
    val processAutomaticCaptures: Boolean = true,
    /** Flex processing (50% cheaper, slower); off until measured. */
    val flexProcessing: Boolean = false,
    /** Planned monthly spend. The hard cap must also be set on the OpenAI project. */
    val monthlyBudgetUsd: Double = 5.0,
    /**
     * Requests allowed per 24 hours, counted in the request log (so restarting the app resets nothing).
     * A local brake, not a spend cap: the hard cap is the OpenAI project's limit.
     */
    val requestsPerDay: Int = 75,
    /**
     * Automatic sending: each monitoring session, once the user confirms its terms at the start, sends
     * its new complete questions to OpenAI on its own (off by default). [requestsPerDay] and the stop
     * rules (errors, credit, unknown cost) still apply; there is no cap per session.
     */
    val autoSendEnabled: Boolean = false,
    /** `max_output_tokens`: visible answer plus reasoning. Too low ends in an incomplete answer. */
    val maxOutputTokens: Int = 1_500,
    /** Longest edge of the photo sent in image mode. */
    val imageMaxDimension: Int = 2_048,
    /** The user confirmed the first real request (model, mode, cost) once. */
    val firstCallConfirmed: Boolean = false,
    /** Send each new answer to the user's Telegram chat (off by default; needs a bot token and [telegramChatId]). */
    val telegramEnabled: Boolean = false,
    /** The chat the bot sends to, found from the user's message to the bot. */
    val telegramChatId: Long? = null,
    /** While monitoring, also send short framing tips ("vire ← esquerda") to that chat (off by default). */
    val telegramFramingHints: Boolean = false,
) {
    companion object {
        val requestsPerDayOptions = listOf(10, 20, 50, 75)
        val maxOutputTokensOptions = listOf(800, 1_500, 3_000)
        val imageMaxDimensionOptions = listOf(1_280, 1_600, 2_048)
    }
}

interface AiSettingsStore {
    fun load(): AiSettings

    fun save(settings: AiSettings)
}

/** Plain SharedPreferences: model choices and budget are not sensitive. */
@Singleton
class SharedPreferencesAiSettingsStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AiSettingsStore {
        private val preferences: SharedPreferences =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

        override fun load(): AiSettings {
            val defaults = AiSettings()
            return AiSettings(
                primaryModel =
                    AiModel
                        .fromId(preferences.getString(PRIMARY, null))
                        ?.takeIf { it in AiModel.primaryOptions } ?: defaults.primaryModel,
                advancedModel =
                    AiModel
                        .fromId(preferences.getString(ADVANCED, null))
                        ?.takeIf { it in AiModel.advancedOptions } ?: defaults.advancedModel,
                processAutomaticCaptures = preferences.getBoolean(AUTOMATIC, defaults.processAutomaticCaptures),
                flexProcessing = preferences.getBoolean(FLEX, defaults.flexProcessing),
                monthlyBudgetUsd =
                    preferences.getString(BUDGET, null)?.toDoubleOrNull() ?: defaults.monthlyBudgetUsd,
                requestsPerDay =
                    preferences
                        .getInt(REQUESTS_PER_DAY, defaults.requestsPerDay)
                        .takeIf { it in AiSettings.requestsPerDayOptions } ?: defaults.requestsPerDay,
                autoSendEnabled = preferences.getBoolean(AUTO_SEND, false),
                maxOutputTokens =
                    preferences
                        .getInt(MAX_OUTPUT_TOKENS, defaults.maxOutputTokens)
                        .takeIf { it in AiSettings.maxOutputTokensOptions } ?: defaults.maxOutputTokens,
                imageMaxDimension =
                    preferences
                        .getInt(IMAGE_MAX_DIMENSION, defaults.imageMaxDimension)
                        .takeIf { it in AiSettings.imageMaxDimensionOptions } ?: defaults.imageMaxDimension,
                firstCallConfirmed = preferences.getBoolean(FIRST_CALL_CONFIRMED, defaults.firstCallConfirmed),
                telegramEnabled = preferences.getBoolean(TELEGRAM_ENABLED, false),
                telegramChatId = if (preferences.contains(TELEGRAM_CHAT_ID)) preferences.getLong(TELEGRAM_CHAT_ID, 0L) else null,
                telegramFramingHints = preferences.getBoolean(TELEGRAM_FRAMING_HINTS, false),
            )
        }

        override fun save(settings: AiSettings) {
            preferences
                .edit()
                .putString(PRIMARY, settings.primaryModel.id)
                .putString(ADVANCED, settings.advancedModel.id)
                .putBoolean(AUTOMATIC, settings.processAutomaticCaptures)
                .putBoolean(FLEX, settings.flexProcessing)
                .putString(BUDGET, settings.monthlyBudgetUsd.toString())
                .putInt(REQUESTS_PER_DAY, settings.requestsPerDay)
                .putInt(MAX_OUTPUT_TOKENS, settings.maxOutputTokens)
                .putInt(IMAGE_MAX_DIMENSION, settings.imageMaxDimension)
                .putBoolean(FIRST_CALL_CONFIRMED, settings.firstCallConfirmed)
                .putBoolean(AUTO_SEND, settings.autoSendEnabled)
                .putBoolean(TELEGRAM_ENABLED, settings.telegramEnabled)
                .putBoolean(TELEGRAM_FRAMING_HINTS, settings.telegramFramingHints)
                .also { editor ->
                    settings.telegramChatId?.let { editor.putLong(TELEGRAM_CHAT_ID, it) } ?: editor.remove(TELEGRAM_CHAT_ID)
                }.apply()
        }

        private companion object {
            const val PREFERENCES = "questvision_ai_settings"
            const val PRIMARY = "primary_model"
            const val ADVANCED = "advanced_model"
            const val AUTOMATIC = "process_automatic_captures"
            const val FLEX = "flex_processing"
            const val BUDGET = "monthly_budget_usd"

            // A new key with automatic sending (2026-11-04): the user chose 75 a day; the old key held 10.
            const val REQUESTS_PER_DAY = "requests_per_day_v2"
            const val MAX_OUTPUT_TOKENS = "max_output_tokens"
            const val IMAGE_MAX_DIMENSION = "image_max_dimension"
            const val FIRST_CALL_CONFIRMED = "first_call_confirmed"
            const val AUTO_SEND = "auto_send_enabled"
            const val TELEGRAM_ENABLED = "telegram_enabled"
            const val TELEGRAM_CHAT_ID = "telegram_chat_id"
            const val TELEGRAM_FRAMING_HINTS = "telegram_framing_hints"
        }
    }
