package io.mo.xatype.diagnostics

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exportedSnapshotIsStableWhileCaptureContinues() {
        val log = temporary.newFile()
        val token = DiagnosticLog.begin(log)
        try {
            DiagnosticLog.append(token, "引擎初始化失败")
            val copy = DiagnosticLog.snapshot(log, temporary.newFolder())
            DiagnosticLog.append(token, "later event")
            assertEquals("引擎初始化失败\n", copy.readText())
            assertTrue(log.readText().contains("later event"))
        } finally { DiagnosticLog.finish(token, "test complete") }
    }

    @Test fun oldSessionCannotAppendToOrStopNewSession() {
        val log = temporary.newFile()
        val oldToken = DiagnosticLog.begin(log)
        DiagnosticLog.append(oldToken, "old")
        val current = DiagnosticLog.begin(log)
        try {
            assertFalse(DiagnosticLog.append(oldToken, "stale worker"))
            DiagnosticLog.finish(oldToken, "stale stop")
            assertTrue(DiagnosticLog.running)
            assertTrue(DiagnosticLog.append(current, "new"))
            assertEquals("new\n", log.readText())
        } finally { DiagnosticLog.finish(current, "test complete") }
    }

    @Test fun utf8ByteLimitStopsCaptureAndPreservesExistingEvidence() {
        val log = temporary.newFile()
        val token = DiagnosticLog.begin(log)
        try {
            var accepted = 0
            while (DiagnosticLog.append(token, "中".repeat(16_384))) {
                accepted++
                check(accepted < 1000)
            }
            assertTrue(accepted > 0)
            assertFalse(DiagnosticLog.running)
            assertTrue(DiagnosticLog.status.contains("8 MB"))
            assertTrue(log.length() <= DiagnosticLog.MAX_BYTES + 256)
            assertTrue(log.readText().contains("已自动停止"))
        } finally { DiagnosticLog.finish(token, "test complete") }
    }

    @Test fun stopFlushesClosesAndRejectsLaterWrites() {
        val log = temporary.newFile()
        val token = DiagnosticLog.begin(log)
        DiagnosticLog.append(token, "failure evidence")
        DiagnosticLog.finish(token, "stopped")
        val saved = log.readText()
        assertTrue(saved.contains("failure evidence"))
        assertTrue(saved.contains("stopped"))
        assertFalse(DiagnosticLog.append(token, "after stop"))
        DiagnosticLog.finish(token, "duplicate stop")
        assertEquals(saved, log.readText())
    }

    @Test(expected = IllegalStateException::class)
    fun missingSessionCannotExportAnEmptySuccessFile() {
        DiagnosticLog.snapshot(File(temporary.root, "missing.txt"), temporary.newFolder())
    }
}
