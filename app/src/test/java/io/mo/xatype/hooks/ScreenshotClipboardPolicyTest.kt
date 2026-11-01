package io.mo.xatype.hooks

import org.junit.Assert.*
import org.junit.Test

class ScreenshotClipboardPolicyTest {
    private val start = 1_800_000_000_000L
    private val image = ScreenshotClipboardPolicy.Image(101, "com.miui.screenshot", "DCIM/Screenshots/",
        "image/png", start / 1000 + 1, start + 1000, false, false, 9000)
    private fun accepted(value: ScreenshotClipboardPolicy.Image = image, baseline: Long = 100,
                         now: Long = start + 2000) =
        ScreenshotClipboardPolicy.isNewScreenshot(value, baseline, start, now)

    @Test fun acceptsNewSystemScreenshots() {
        assertTrue(accepted())
        assertTrue(accepted(image.copy(owner = "com.android.systemui", relativePath = "Pictures/Screenshots/", mimeType = "image/jpeg")))
    }
    @Test fun ignoresExistingAndRepeatedImages() {
        assertFalse(accepted(baseline = 101))
        assertFalse(accepted(image.copy(id = 20)))
    }
    @Test fun rejectsDownloadsAndCopiedOldScreenshots() {
        assertFalse(accepted(image.copy(owner = "com.android.providers.downloads")))
        assertFalse(accepted(image.copy(takenMillis = start - 86_400_000)))
        assertFalse(accepted(image.copy(addedSeconds = start / 1000 - 1)))
    }
    @Test fun rejectsPhotosAndMisleadingFolderNames() {
        assertFalse(accepted(image.copy(relativePath = "DCIM/Camera/")))
        assertFalse(accepted(image.copy(relativePath = "Download/MyScreenshotsBackup/")))
        assertFalse(accepted(image.copy(mimeType = "video/mp4")))
        assertFalse(accepted(image.copy(owner = "")))
    }
    @Test fun waitsForCompletedNonemptyFile() {
        assertTrue(accepted(image.copy(pending = true)))
        assertFalse(ScreenshotClipboardPolicy.isReady(image.copy(pending = true)))
        assertFalse(ScreenshotClipboardPolicy.isReady(image.copy(size = 0)))
        assertFalse(ScreenshotClipboardPolicy.isReady(image.copy(trashed = true)))
        assertTrue(ScreenshotClipboardPolicy.isReady(image))
    }
    @Test fun doesNotReplayDelayedOrFutureImages() {
        assertFalse(accepted(now = start + 32_000))
        assertFalse(accepted(image.copy(takenMillis = start + 60_000)))
    }
    @Test fun handlesSecondResolutionAndMissingDateTaken() {
        assertTrue(accepted(image.copy(takenMillis = 0)))
        assertTrue(ScreenshotClipboardPolicy.isNewScreenshot(image.copy(addedSeconds = start / 1000, takenMillis = start),
            100, start + 500, start + 2000))
    }
    @Test fun reenableBaselineExcludesScreenshotsTakenWhileDisabled() {
        assertFalse(accepted(image.copy(id = 120), baseline = 120))
        assertTrue(accepted(image.copy(id = 121), baseline = 120))
    }
}
