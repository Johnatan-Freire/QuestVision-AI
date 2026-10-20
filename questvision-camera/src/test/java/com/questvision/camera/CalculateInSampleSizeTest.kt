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
package com.questvision.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculateInSampleSizeTest {
    @Test
    fun imageWithinLimit_isNotDownsampled() {
        assertEquals(1, calculateInSampleSize(width = 1920, height = 1440, maxDimension = 2048))
        assertEquals(1, calculateInSampleSize(width = 2048, height = 1536, maxDimension = 2048))
    }

    @Test
    fun largeImage_usesSmallestPowerOfTwoThatFits() {
        // 4000 / 2 = 2000 <= 2048
        assertEquals(2, calculateInSampleSize(width = 4000, height = 3000, maxDimension = 2048))
        // 8160 / 4 = 2040 <= 2048 (50 MP sensor)
        assertEquals(4, calculateInSampleSize(width = 8160, height = 6120, maxDimension = 2048))
    }

    @Test
    fun portraitImage_usesLongestEdge() {
        assertEquals(2, calculateInSampleSize(width = 3000, height = 4000, maxDimension = 2048))
    }
}
