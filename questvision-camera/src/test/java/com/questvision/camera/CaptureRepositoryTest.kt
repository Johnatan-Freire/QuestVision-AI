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

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CaptureRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val root by lazy { File(folder.root, "captures") }

    private fun repository() = CaptureRepository(root, dispatcher)

    /** Simulates CameraX writing [file] at [timeMs]. */
    private fun write(
        file: File,
        timeMs: Long,
    ): File =
        file.apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(timeMs)
        }

    @Test
    fun fileNames_roundTripOriginSessionAndSequence() {
        val time = 1_792_000_000_000L
        val auto = parseCaptureEntry(File(captureFileName(time, CaptureOrigin.AUTOMATIC, "20261022-141503", 7)), false)
        val manual = parseCaptureEntry(File(captureFileName(time, CaptureOrigin.MANUAL, null, null)), false)

        assertEquals(CaptureOrigin.AUTOMATIC, auto.origin)
        assertEquals("20261022-141503", auto.session)
        assertEquals(7, auto.sequence)
        assertEquals(time, auto.capturedAtMs)
        assertEquals(CaptureOrigin.MANUAL, manual.origin)
        assertNull(manual.sequence)
    }

    @Test
    fun earlierUnlabeledFiles_areListedAsUnknown() =
        runTest(dispatcher) {
            root.mkdirs()
            val legacy = write(File(root, "capture_20261020_101500_123.jpg"), 1_000L)

            val entry = repository().history().single()

            assertEquals(legacy, entry.file)
            assertEquals(CaptureOrigin.UNKNOWN, entry.origin)
            // The time comes from the name, not from the file's modification time.
            assertTrue(entry.capturedAtMs > 1_000L)
        }

    @Test
    fun history_listsAllCapturesNewestFirst_includingPreserved() =
        runTest(dispatcher) {
            val repository = repository()
            val first = write(repository.newManualCaptureFile(nowMs = 1_000_000L), 1_000_000L)
            val second = write(repository.newAutomaticCaptureFile("s", 1, nowMs = 2_000_000L), 2_000_000L)
            val third = write(repository.newAutomaticCaptureFile("s", 2, nowMs = 3_000_000L), 3_000_000L)
            val kept = repository.preserve(first)

            val history = repository.history()

            assertEquals(listOf(third.name, second.name, kept.name), history.map { it.file.name })
            assertEquals(listOf(2, 1, null), history.map { it.sequence })
            assertEquals(listOf(false, false, true), history.map { it.isPreserved })
        }

    @Test
    fun history_survivesARestart() =
        runTest(dispatcher) {
            val before = repository()
            write(before.newAutomaticCaptureFile("s", 1, nowMs = 1_000_000L), 1_000_000L)
            write(before.newAutomaticCaptureFile("s", 2, nowMs = 2_000_000L), 2_000_000L)

            // A new instance (as after the app restarts) reads everything back from disk.
            val after = repository().history()

            assertEquals(2, after.size)
            assertTrue(after.all { it.origin == CaptureOrigin.AUTOMATIC && it.session == "s" })
        }

    @Test
    fun retention_prunesOnlyOldUnpreservedCaptures() =
        runTest(dispatcher) {
            val repository = repository()
            val keptFiles =
                (1..2).map { i ->
                    repository.preserve(write(repository.newManualCaptureFile(nowMs = i * 1_000L), i * 1_000L))
                }
            val recent =
                (1..MAX_RECENT_CAPTURES + 5).map { i ->
                    val time = 100_000L + i * 1_000L
                    write(repository.newAutomaticCaptureFile("s", i, nowMs = time), time)
                }

            val deleted = repository.pruneRecentCaptures()

            assertEquals(5, deleted)
            assertTrue(recent.take(5).none { it.exists() })
            assertTrue(recent.drop(5).all { it.exists() })
            // Preserved captures are older than every pruned one, and still untouched.
            assertTrue(keptFiles.all { it.exists() })
            assertEquals(MAX_RECENT_CAPTURES + 2, repository.history().size)
        }

    @Test
    fun completeCapture_requiresANonEmptyFile() =
        runTest(dispatcher) {
            val repository = repository()
            val missing = repository.newManualCaptureFile(nowMs = 1L)
            val empty = repository.newManualCaptureFile(nowMs = 2L).apply { createNewFile() }
            val written = write(repository.newManualCaptureFile(nowMs = 3L), 3L)

            assertFalse(repository.isCompleteCapture(missing))
            assertFalse(repository.isCompleteCapture(empty))
            assertTrue(repository.isCompleteCapture(written))
        }
}
