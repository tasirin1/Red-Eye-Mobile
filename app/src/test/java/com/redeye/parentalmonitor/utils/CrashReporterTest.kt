package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Nilai harapan dibuktikan lebih dulu lewat porting 1:1
// kedua fungsi ke Python (aturan uji logika murni repo).
class CrashReporterTest {

    @Test
    fun stripElapsedDropsNumericFirstLine() {
        assertEquals("ABC", CrashReporter.stripElapsed("123\nABC"))
        assertEquals("", CrashReporter.stripElapsed("456\n"))
    }

    @Test
    fun stripElapsedKeepsNonNumericOrMissingFirstLine() {
        assertEquals("ABC\nDEF", CrashReporter.stripElapsed("ABC\nDEF"))
        assertEquals("123", CrashReporter.stripElapsed("123"))
        assertEquals("\nABC", CrashReporter.stripElapsed("\nABC"))
        assertEquals("12a\nX", CrashReporter.stripElapsed("12a\nX"))
        assertEquals("", CrashReporter.stripElapsed(""))
    }

    private fun withTempFile(content: String?, block: (File) -> Unit) {
        val dir = File(System.getProperty("java.io.tmpdir"), "crash-uji-" + System.nanoTime())
        dir.mkdirs()
        try {
            val f = File(dir, "pending.txt")
            if (content != null) f.writeText(content)
            block(f)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun keepRemainderSameContentWritesRestWithElapsedPrefix() {
        withTempFile("RAW") { f ->
            CrashReporter.keepRemainder(f, "RAW", "R2", 1000L)
            assertEquals("1000\nR2", f.readText())
        }
    }

    @Test
    fun keepRemainderSameContentBlankRestDeletesFile() {
        withTempFile("RAW") { f ->
            CrashReporter.keepRemainder(f, "RAW", "", 1000L)
            assertFalse(f.exists())
        }
    }

    @Test
    fun keepRemainderAppendedContentIsKeptAfterRest() {
        withTempFile("RAW\n---\nAPP") { f ->
            CrashReporter.keepRemainder(f, "RAW", "R2", 1000L)
            assertEquals("1000\nR2\n---\nAPP", f.readText())
        }
        withTempFile("RAW\n---\nAPP") { f ->
            CrashReporter.keepRemainder(f, "RAW", "", 1000L)
            assertEquals("1000\nAPP", f.readText())
        }
    }

    @Test
    fun keepRemainderDifferentContentMergesStrippedCurrentWithRest() {
        withTempFile("999\nLAIN") { f ->
            CrashReporter.keepRemainder(f, "RAW", "R2", 1000L)
            assertEquals("1000\nLAIN\n---\nR2", f.readText())
        }
        withTempFile("999\nLAIN") { f ->
            CrashReporter.keepRemainder(f, "RAW", "", 1000L)
            assertEquals("1000\nLAIN", f.readText())
        }
    }

    @Test
    fun keepRemainderMissingFileWritesRestOnly() {
        withTempFile(null) { f ->
            CrashReporter.keepRemainder(f, "RAW", "R2", 1000L)
            assertTrue(f.exists())
            assertEquals("1000\nR2", f.readText())
        }
        withTempFile(null) { f ->
            CrashReporter.keepRemainder(f, "RAW", "", 1000L)
            assertFalse(f.exists())
        }
    }
}
