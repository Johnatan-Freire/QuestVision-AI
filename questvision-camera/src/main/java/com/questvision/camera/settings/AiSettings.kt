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
 * input / output) as published on developers.openai.com/api/docs/pricing on 2026-10-22. Used for
 * local cost estimates only; real usage comes from each response's token counts.
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
)

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
                .apply()
        }

        private companion object {
            const val PREFERENCES = "questvision_ai_settings"
            const val PRIMARY = "primary_model"
            const val ADVANCED = "advanced_model"
            const val AUTOMATIC = "process_automatic_captures"
            const val FLEX = "flex_processing"
            const val BUDGET = "monthly_budget_usd"
        }
    }
