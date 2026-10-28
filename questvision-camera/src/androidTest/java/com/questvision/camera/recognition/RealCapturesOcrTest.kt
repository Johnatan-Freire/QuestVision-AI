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
package com.questvision.camera.recognition

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

private const val TAG = "QuestVisionOcrReal"

/**
 * Runs the real ML Kit recognizer and the session's text comparison over real captures, in order,
 * on a device. Not part of the regular suite: it only runs when photos were copied to the test
 * app's `files/real_captures` (see docs/PROJECT_STATUS.md), and reports instead of asserting, so
 * thresholds can be checked against field data. The logcat gets only metrics; the recognized words go
 * only to the report in the test app's private files, read with `run-as` and deleted after use.
 */
@RunWith(AndroidJUnit4::class)
class RealCapturesOcrTest {
    @Test
    fun reportVerdictsForRealCaptures() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val folder = File(context.filesDir, "real_captures")
            val photos = folder.listFiles { file -> file.extension == "jpg" }?.sortedBy { it.name }.orEmpty()
            assumeTrue("No photos in $folder", photos.isNotEmpty())

            val engine = MlKitTextRecognitionEngine(context)
            val matcher = QuestionTextMatcher()
            val accepted = ArrayList<TextFingerprint>()
            val report = StringBuilder()
            for (photo in photos) {
                val started = System.nanoTime()
                val text = TextFingerprint.of(engine.recognize(photo))
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val comparison = matcher.compare(text, accepted)
                if (comparison.verdict == TextVerdict.NEW_QUESTION) accepted += text
                val line =
                    String.format(
                        Locale.US,
                        "%s %s ocr=%dms words=%d novel=%d ratio=%.3f run=%d closest=%d accepted=%d",
                        photo.name,
                        comparison.verdict,
                        elapsedMs,
                        comparison.confidentTokens,
                        comparison.novelTokens,
                        comparison.novelRatio,
                        comparison.longestNovelRun,
                        comparison.closestIndex,
                        accepted.size,
                    )
                val detail =
                    "  run: ${comparison.longestRunWords.joinToString(" ")}\n" +
                        "  novel: ${comparison.novelWords.joinToString(" ")}\n" +
                        "  lines: " +
                        text.lines.joinToString(" | ") { line -> line.joinToString(" ") { it.value + if (it.confident) "" else "?" } }
                Log.i(TAG, line)
                report.appendLine(line).appendLine(detail)
            }
            File(context.filesDir, "real_captures_report.txt").writeText(report.toString())
        }
}
