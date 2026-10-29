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
package com.android.camera.catalog

import com.questvision.camera.ai.AUTO_TRIAL_ID
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/** What the build variant allows; only the `trial` build type can make real automatic requests. */
@Module
@InstallIn(SingletonComponent::class)
object BuildVariantModule {
    @Provides
    @Named(AUTO_TRIAL_ID)
    fun autoTrialId(): Int = BuildConfig.AUTO_TRIAL_ID
}
