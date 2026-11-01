package io.mo.xatype.hooks

import android.app.Application
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.util.concurrent.atomic.AtomicBoolean

/** Runs in System UI, which already has media access. No polling or new app permissions. */
object ScreenshotClipboardHook {
    private val installed = AtomicBoolean(false)

    fun install(module: XposedInterface) {
        if (Build.VERSION.SDK_INT < 30) return
        // Xiaomi relocates its Application class between OS versions. Use the framework lifecycle.
        val onCreate = Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
        module.hook(onCreate).intercept { chain ->
            val result = chain.proceed()
            val context = chain.getArg(0) as? Application
            if (context != null && Application.getProcessName() == "com.android.systemui" &&
                installed.compareAndSet(false, true)) {
                val worker = HandlerThread("XatScreenshotClipboard").apply { start() }
                Handler(worker.looper).let { handler ->
                    handler.post { Monitor(module, context, handler).start() }
                }
            }
            result
        }
        XposedUtils.log(module, "[Screenshot Clipboard] Application lifecycle hook installed")
    }

    private class Monitor(
        val module: XposedInterface,
        val context: Context,
        val handler: Handler
    ) {
        val resolver = context.contentResolver
        val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        var enabled = false
        var baselineId = Long.MAX_VALUE
        var startedAt = System.currentTimeMillis()
        var retry = 0
        var failedLogged = false
        val scan = Runnable { scanSafely() }
        val mediaObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!enabled) return
                retry = 0
                handler.removeCallbacks(scan)
                handler.postDelayed(scan, 350)
            }
        }
        val configObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { refreshConfig() }
        }
        val unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { refreshConfig() }
        }

        fun start() {
            try {
                resolver.registerContentObserver(ConfigManager.SCREENSHOT_CONFIG_URI, false, configObserver)
                resolver.registerContentObserver(collection, true, mediaObserver)
                val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(unlockReceiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(unlockReceiver, filter, null, handler)
                }
                refreshConfig()
                XposedUtils.log(module, "[Screenshot Clipboard] Observer ready; enabled=$enabled")
            } catch (t: Throwable) {
                resolver.unregisterContentObserver(mediaObserver)
                resolver.unregisterContentObserver(configObserver)
                runCatching { context.unregisterReceiver(unlockReceiver) }
                XposedUtils.logError(module, "[Screenshot Clipboard] Observer unavailable", t)
                handler.looper.quitSafely()
            }
        }

        fun refreshConfig() {
            handler.removeCallbacks(scan)
            ConfigManager.syncFromProvider(context)
            enabled = false
            baselineId = Long.MAX_VALUE
            startedAt = System.currentTimeMillis()
            retry = 0
            if (!ConfigManager.isScreenshotClipboardEnabled()) return
            try {
                // Seed from the current library on every enable/restart: no old-image import.
                resolver.query(collection, arrayOf("_id"), queryArgs(null, null, 1), null)?.use {
                    baselineId = if (it.moveToFirst()) it.getLong(0) else 0L
                    enabled = true
                }
                if (!enabled) error("MediaStore did not return a cursor")
                failedLogged = false
                XposedUtils.log(module, "[Screenshot Clipboard] Enabled for new screenshots")
            } catch (t: Throwable) {
                XposedUtils.logError(module, "[Screenshot Clipboard] Media access unavailable", t)
            }
        }

        fun queryArgs(selection: String?, args: Array<String>?, limit: Int) = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf("_id"))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        }

        fun scanSafely() {
            if (!enabled) return
            try {
                ConfigManager.syncFromProvider(context)
                if (!ConfigManager.isScreenshotClipboardEnabled()) { enabled = false; return }
                val now = System.currentTimeMillis()
                val projection = arrayOf("_id", "owner_package_name", "relative_path", "mime_type",
                    "date_added", "datetaken", "is_pending", "is_trashed", "_size")
                val args = queryArgs("_id > ? AND date_added >= ?", arrayOf(baselineId.toString(),
                    maxOf(startedAt / 1000, (now - ScreenshotClipboardPolicy.MAX_AGE_MS) / 1000).toString()), 32)
                var newest: ScreenshotClipboardPolicy.Image? = null
                resolver.query(collection, projection, args, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val candidate = ScreenshotClipboardPolicy.Image(cursor.getLong(0), cursor.getString(1).orEmpty(),
                            cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty(), cursor.getLong(4),
                            cursor.getLong(5), cursor.getInt(6) != 0, cursor.getInt(7) != 0, cursor.getLong(8))
                        if (ScreenshotClipboardPolicy.isNewScreenshot(candidate, baselineId, startedAt, now)) {
                            newest = candidate
                            break
                        }
                    }
                }
                val candidate = newest ?: return
                if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) {
                    baselineId = candidate.id
                    return
                }
                if (!ScreenshotClipboardPolicy.isReady(candidate)) {
                    scheduleRetry()
                    return
                }
                val uri = ContentUris.withAppendedId(collection, candidate.id)
                // Confirm access before replacing the user's clipboard. Do not decode full-size bitmaps.
                resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    if (descriptor.length == 0L) error("Screenshot is empty")
                } ?: error("Screenshot cannot be opened")
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                    ?: error("Clipboard service unavailable")
                clipboard.setPrimaryClip(ClipData.newUri(resolver, "Screenshot", uri))
                baselineId = candidate.id
                failedLogged = false
                XposedUtils.log(module, "[Screenshot Clipboard] Copied saved screenshot id=${candidate.id}")
            } catch (t: Throwable) {
                if (!failedLogged) {
                    failedLogged = true
                    XposedUtils.logError(module, "[Screenshot Clipboard] Could not copy screenshot", t)
                }
                scheduleRetry()
            }
        }

        fun scheduleRetry() {
            if (++retry <= 4) handler.postDelayed(scan, retry * 500L)
        }
    }
}
