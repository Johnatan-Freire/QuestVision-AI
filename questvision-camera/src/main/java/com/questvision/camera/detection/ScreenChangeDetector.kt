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

enum class DetectorPhase {
    /** Content matches the last accepted content. */
    WATCHING,

    /** Content differs from the accepted content and is still moving. */
    CHANGED,

    /** Content differs and has stopped moving; waiting out the stabilization time. */
    STABILIZING,
}

enum class DetectionOutcome {
    NONE,

    /** New, settled content that hasn't been captured before: capture it. */
    CANDIDATE,

    /** Settled content that matches an earlier capture; adopted as the reference without capturing. */
    DUPLICATE,

    /** The scene settled back onto the accepted content (e.g. the camera was only bumped). */
    REVERTED,
}

/** Why a settled window did not lead to a decision on this sample (it is retried on the next one). */
enum class DecisionHold {
    NONE,

    /** This sample moved more than the still threshold from the previous one. */
    MOTION,

    /** The previous sample was a tolerated outlier; decide only on two consistent samples in a row. */
    AFTER_OUTLIER,
}

/** Per-frame numbers logged so the thresholds can be tuned on a real device. */
data class FrameMetrics(
    val timestampMs: Long,
    /** Structure change versus the previous sample (NaN for the first sample). */
    val motion: Float,
    /** Median of the last few [motion] values. */
    val medianMotion: Float,
    /** Fine high-pass change versus the previous sample: the pre-fix metric, for comparison only. */
    val fineMotion: Float,
    /** Structure change versus the anchor of the current still window (0 when there is none). */
    val drift: Float,
    /** 25th percentile of recent [motion]: what a still scene measures on this device. */
    val noiseFloor: Float,
    /** Still threshold in effect for this sample (adaptive, with hysteresis applied once stable). */
    val stillThreshold: Float,
    /** Structure change versus the accepted content (1 when there is none yet). */
    val change: Float,
    /** Change threshold in effect: the configured one, raised to [stillThreshold] in noisy scenes. */
    val changeThreshold: Float,
    /** Duplicate threshold in effect, raised the same way. */
    val duplicateThreshold: Float,
    /** At a decision: smallest change versus the remembered captures (NaN otherwise). */
    val nearestCapture: Float,
    val hold: DecisionHold,
    val stableForMs: Long,
    val outliers: Int,
    val meanLuma: Float,
    val sharpness: Float,
    val phase: DetectorPhase,
    val outcome: DetectionOutcome,
)

/**
 * Decides when the observed screen shows new, settled content. Feed it sampled frames in timestamp
 * order; it is not thread-safe and is meant to be driven from a single thread.
 *
 * **Stability** uses a temporal window rather than consecutive pairs alone: it starts when two
 * consecutive samples match (structure motion within the still threshold), and that earlier sample
 * becomes the window's anchor. Every later sample must stay within the threshold *of the anchor*, so
 * a slow scroll that looks still frame to frame still breaks the window. One off-threshold sample per
 * window is tolerated (an autofocus blip, a passing reflection) but never counts as stable; a second
 * one restarts the window. The threshold adapts to the measured noise floor within fixed bounds, and
 * gains [DetectionConfig.stillHysteresis] once stable.
 *
 * A frame becomes a [DetectionOutcome.CANDIDATE] once it differs from the accepted content by more
 * than [DetectionConfig.changeThreshold] and the window has lasted [DetectionConfig.stabilizationMs].
 * The caller confirms a candidate with [accept] once it has actually been captured.
 */
class ScreenChangeDetector(
    private val config: DetectionConfig,
) {
    var phase: DetectorPhase = DetectorPhase.WATCHING
        private set

    private var previous: LumaFrame? = null
    private var accepted: LumaFrame? = null
    private var anchor: LumaFrame? = null
    private var outliers = 0

    // Whether the previous sample still matched the accepted content.
    private var previousMatchedAccepted = false

    // Whether the previous sample was an outlier tolerated inside the current window.
    private var previousWasOutlier = false
    private val recentMotion = ArrayDeque<Float>()
    private val history = ArrayDeque<LumaFrame>()

    fun process(frame: LumaFrame): FrameMetrics {
        val last = previous
        previous = frame
        val motion = if (last == null) Float.NaN else structureDifference(last, frame, maxShift = 0)
        if (!motion.isNaN()) {
            recentMotion.addLast(motion)
            while (recentMotion.size > config.noiseFloorWindowSamples) recentMotion.removeFirst()
        }
        val noiseFloor = percentile(recentMotion, 0.25f)
        val enterThreshold =
            (noiseFloor * config.noiseFloorMultiplier)
                .coerceIn(config.stillThreshold, config.maxStillThreshold)
        val stayThreshold = enterThreshold + config.stillHysteresis
        // Whatever counts as "the same still scene" must also count as "the same content": in a
        // noisy scene the still tolerance rises, and the content thresholds may never sit below it.
        val changeThreshold = maxOf(config.changeThreshold, stayThreshold)
        val duplicateThreshold = maxOf(config.duplicateThreshold, stayThreshold)

        // Extend, tolerate an outlier in, or break the current still window.
        var isStill = false
        var isOutlier = false
        var drift = 0f
        val currentAnchor = anchor
        if (currentAnchor != null) {
            drift = structureDifference(currentAnchor, frame, maxShift = 0)
            when {
                drift <= stayThreshold -> {
                    isStill = true
                }

                outliers < config.maxOutliersPerWindow -> {
                    outliers++
                    isOutlier = true
                }

                else -> {
                    anchor = null
                }
            }
        }
        // Open a new window when this sample matches the previous one.
        if (anchor == null && last != null && motion <= enterThreshold) {
            anchor = last
            outliers = 0
            isStill = true
            drift = motion
        }

        val reference = accepted
        val change = if (reference == null) 1f else difference(reference, frame)
        // When new content starts, the stability window restarts with it: time the old screen spent
        // still must not count toward settling the new one (e.g. a page mid-transition).
        val changeStarted = previousMatchedAccepted && change > changeThreshold
        previousMatchedAccepted = change <= changeThreshold
        if (changeStarted && currentAnchor != null) {
            val reopen = last != null && motion <= enterThreshold
            anchor = if (reopen) last else null
            outliers = 0
            isStill = reopen
            drift = if (reopen) motion else 0f
        }
        val stableForMs = if (isStill) frame.timestampMs - anchor!!.timestampMs else 0L
        phase =
            when {
                phase == DetectorPhase.WATCHING && change <= changeThreshold -> DetectorPhase.WATCHING
                isStill -> DetectorPhase.STABILIZING
                else -> DetectorPhase.CHANGED
            }

        var outcome = DetectionOutcome.NONE
        var hold = DecisionHold.NONE
        var nearestCapture = Float.NaN
        if (phase == DetectorPhase.STABILIZING && stableForMs >= config.stabilizationMs) {
            // Decide only on a sample that is itself still and follows a consistent one, never on a
            // frame that is still moving or right after an outlier.
            hold =
                when {
                    motion > stayThreshold -> DecisionHold.MOTION
                    previousWasOutlier -> DecisionHold.AFTER_OUTLIER
                    else -> DecisionHold.NONE
                }
            if (hold == DecisionHold.NONE) {
                phase = DetectorPhase.WATCHING
                nearestCapture = history.minOfOrNull { difference(it, frame) } ?: Float.NaN
                outcome =
                    when {
                        change <= changeThreshold -> {
                            DetectionOutcome.REVERTED
                        }

                        nearestCapture <= duplicateThreshold -> {
                            accepted = frame
                            DetectionOutcome.DUPLICATE
                        }

                        else -> {
                            DetectionOutcome.CANDIDATE
                        }
                    }
            }
        }
        previousWasOutlier = isOutlier

        return FrameMetrics(
            timestampMs = frame.timestampMs,
            motion = motion,
            medianMotion = percentile(recentMotion.takeLast(MEDIAN_SAMPLES), 0.5f),
            fineMotion =
                if (last == null) {
                    Float.NaN
                } else {
                    fineDifference(last, frame, config.cellDiffThreshold, config.maxShiftCells)
                },
            drift = drift,
            noiseFloor = noiseFloor,
            stillThreshold = if (currentAnchor != null) stayThreshold else enterThreshold,
            change = change,
            changeThreshold = changeThreshold,
            duplicateThreshold = duplicateThreshold,
            nearestCapture = nearestCapture,
            hold = hold,
            stableForMs = stableForMs,
            outliers = outliers,
            meanLuma = frame.meanLuma,
            sharpness = frame.sharpness,
            phase = phase,
            outcome = outcome,
        )
    }

    /** Records [frame] (a captured candidate) as the accepted content and remembers it for duplicates. */
    fun accept(frame: LumaFrame) {
        accepted = frame
        history.addLast(frame)
        while (history.size > config.duplicateHistorySize) history.removeFirst()
        discardWindow()
    }

    /**
     * Ends the current stability window without accepting anything (e.g. the capture failed). The
     * window that led to a capture is spent either way: frames were skipped while the photo was
     * taken and the camera may still be re-converging (exposure, focus), so whatever is on screen
     * now must settle in a fresh window before it can be decided.
     */
    fun discardWindow() {
        previous = null
        anchor = null
        outliers = 0
        previousWasOutlier = false
        previousMatchedAccepted = true
        phase = DetectorPhase.WATCHING
    }

    /** Forgets motion tracking (e.g. after a pause) while keeping the accepted content and history. */
    fun resetMotion() {
        previous = null
        previousMatchedAccepted = false
        previousWasOutlier = false
        anchor = null
        outliers = 0
        recentMotion.clear()
        phase = DetectorPhase.WATCHING
    }

    private fun difference(
        a: LumaFrame,
        b: LumaFrame,
    ): Float = structureDifference(a, b, config.structureCellThreshold, config.maxShiftCells)

    private fun structureDifference(
        a: LumaFrame,
        b: LumaFrame,
        maxShift: Int,
    ): Float = structureDifference(a, b, config.structureCellThreshold, maxShift)

    private companion object {
        const val MEDIAN_SAMPLES = 3
    }
}

/**
 * The [fraction] percentile (0–1, nearest-rank, upper on ties) of [values]; 0 when empty. Robust to
 * occasional extreme samples, unlike a mean.
 */
internal fun percentile(
    values: Collection<Float>,
    fraction: Float,
): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val index = (fraction * sorted.size).toInt().coerceIn(0, sorted.size - 1)
    return sorted[index]
}
