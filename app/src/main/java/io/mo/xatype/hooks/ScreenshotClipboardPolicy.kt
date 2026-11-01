package io.mo.xatype.hooks

/** MediaStore metadata only: never inspect unrelated image contents. */
internal object ScreenshotClipboardPolicy {
    const val MAX_AGE_MS = 30_000L

    data class Image(
        val id: Long,
        val owner: String,
        val relativePath: String,
        val mimeType: String,
        val addedSeconds: Long,
        val takenMillis: Long,
        val pending: Boolean,
        val trashed: Boolean,
        val size: Long
    )

    fun isNewScreenshot(image: Image, baselineId: Long, startedAt: Long, now: Long): Boolean {
        if (image.id <= baselineId || image.addedSeconds < startedAt / 1000) return false
        if (image.owner !in setOf("com.miui.screenshot", "com.android.systemui")) return false
        if (!image.relativePath.trim('/').split('/').any { it.equals("Screenshots", true) }) return false
        if (!image.mimeType.startsWith("image/")) return false
        val createdAt = image.takenMillis.takeIf { it > 0 } ?: image.addedSeconds * 1000
        // MediaStore DATE_ADDED has one-second precision.
        return createdAt >= startedAt - 1000 && now - createdAt in -1000..MAX_AGE_MS
    }

    fun isReady(image: Image): Boolean = !image.pending && !image.trashed && image.size > 0
}
