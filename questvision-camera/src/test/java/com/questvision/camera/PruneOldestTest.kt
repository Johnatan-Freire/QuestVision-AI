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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PruneOldestTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun capture(
        name: String,
        modified: Long,
    ): File = folder.newFile(name).apply { setLastModified(modified) }

    @Test
    fun keepsNewestAndDeletesTheRest() {
        val oldest = capture("a.jpg", 1_000_000L)
        val middle = capture("b.jpg", 2_000_000L)
        val newest = capture("c.jpg", 3_000_000L)

        val deleted = pruneOldest(listOf(newest, oldest, middle), keep = 2)

        assertEquals(1, deleted)
        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun underTheLimit_deletesNothing() {
        val files = listOf(capture("a.jpg", 1_000_000L), capture("b.jpg", 2_000_000L))

        assertEquals(0, pruneOldest(files, keep = MAX_RECENT_CAPTURES))
        assertTrue(files.all { it.exists() })
    }
}
