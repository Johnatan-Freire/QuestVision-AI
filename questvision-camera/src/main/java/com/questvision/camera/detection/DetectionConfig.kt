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
package com.questvision.camera.detection

/**
 * Tunables for screen-change detection. These are starting points meant to be adjusted from the
 * metrics logged during real tests (tag `QuestVisionDetect`), not final constants.
 *
 * Unless noted, "fractions" are the share of structure-map cells ([LumaFrame.structure]) whose
 * value changed by more than [structureCellThreshold] between two frames. The defaults were chosen
 * from rendered test scenes (`RenderedScreens`, `StructureMetricSeparationTest`): a still screen
 * with sub-pixel jitter, focus breathing, exposure drift and noise measures ≤ 0.021 between
 * samples, scrolling ≥ 0.10 (≥ 0.26 from 2 px per sample), a different question ≈ 0.13.
 */
data class DetectionConfig(
    /** Minimum time between analyzed frames (≈ 2 fps). */
    val sampleIntervalMs: Long = 500,
    /** How long the scene must stay still before a change is considered settled. */
    val stabilizationMs: Long = 1_000,
    /** Size of the compact luminance grid each frame is reduced to. */
    val gridWidth: Int = 128,
    val gridHeight: Int = 96,
    /** Share of each image edge ignored (bezel, frame borders). */
    val marginFraction: Float = 0.05f,
    /** Radius, in cells, of the local mean subtracted by the high-pass filter. */
    val highPassRadius: Int = 3,
    /** Side, in cells, of the sliding window averaging high-pass energy into the structure map. */
    val structureWindow: Int = 2,
    /** A structure cell counts as changed when its energy (mean 1) moves by more than this. */
    val structureCellThreshold: Float = 0.5f,
    /** Base fraction at or below which two samples count as the same still scene. */
    val stillThreshold: Float = 0.04f,
    /** Extra tolerance once stable, so values hovering around the threshold don't flap (hysteresis). */
    val stillHysteresis: Float = 0.02f,
    /**
     * The still threshold adapts to the measured noise floor (this multiple of it) but never exceeds
     * [maxStillThreshold], which stays far below any scroll.
     */
    val noiseFloorMultiplier: Float = 2f,
    val maxStillThreshold: Float = 0.08f,
    /** Samples used to estimate the noise floor (25th percentile of recent motion). */
    val noiseFloorWindowSamples: Int = 10,
    /** Off-threshold samples tolerated within one stable window (an autofocus blip, a reflection). */
    val maxOutliersPerWindow: Int = 1,
    /** Fraction versus the last accepted content that counts as new content. */
    val changeThreshold: Float = 0.05f,
    /** Fraction versus a previous capture below which content counts as repeated. */
    val duplicateThreshold: Float = 0.05f,
    /** Change and duplicate comparisons tolerate drift of up to this many cells (≈ 5 px each). */
    val maxShiftCells: Int = 2,
    /** How many past captures are remembered for duplicate detection. */
    val duplicateHistorySize: Int = 8,
    /** Consecutive failed automatic captures after which the session stops with an error. */
    val maxConsecutiveCaptureFailures: Int = 3,
    /** Per-cell threshold (in σ) of the fine high-pass comparison, logged as `fine` for diagnosis. */
    val cellDiffThreshold: Float = 0.5f,
)
