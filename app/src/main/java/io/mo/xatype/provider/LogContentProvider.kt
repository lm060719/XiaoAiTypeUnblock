package io.mo.xatype.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
class LogContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "io.mo.xatype.logprovider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val METHOD_GET_CONFIG = "get_config"
        const val BG_IMAGE_FILENAME = "bg_image.png"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val result = Bundle()
        when (method) {
            METHOD_GET_CONFIG -> {
                val ctx = context
                if (ctx != null) {
                    val sp = ctx.getSharedPreferences(io.mo.xatype.config.ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
                    result.putBoolean(io.mo.xatype.config.ConfigManager.KEY_AI_SAFETY, sp.getBoolean(io.mo.xatype.config.ConfigManager.KEY_AI_SAFETY, true))
                    result.putBoolean(io.mo.xatype.config.ConfigManager.KEY_VOICE_MODERATION, sp.getBoolean(io.mo.xatype.config.ConfigManager.KEY_VOICE_MODERATION, true))
                    result.putBoolean(io.mo.xatype.config.ConfigManager.KEY_CLOUD_BLACKLIST, sp.getBoolean(io.mo.xatype.config.ConfigManager.KEY_CLOUD_BLACKLIST, true))
                    result.putBoolean(io.mo.xatype.config.ConfigManager.KEY_CLIPBOARD_SENSITIVE, sp.getBoolean(io.mo.xatype.config.ConfigManager.KEY_CLIPBOARD_SENSITIVE, true))
                    result.putBoolean(io.mo.xatype.config.ConfigManager.KEY_CLIPBOARD_PERMANENT, sp.getBoolean(io.mo.xatype.config.ConfigManager.KEY_CLIPBOARD_PERMANENT, true))
                    result.putBoolean(io.mo.xatype.config.ConfigManager.KEY_OS_VERSION_UNBLOCK, sp.getBoolean(io.mo.xatype.config.ConfigManager.KEY_OS_VERSION_UNBLOCK, true))
                    result.putBoolean(io.mo.xatype.config.ConfigManager.KEY_STYLE_ENABLED, sp.getBoolean(io.mo.xatype.config.ConfigManager.KEY_STYLE_ENABLED, true))
                    result.putInt(io.mo.xatype.config.ConfigManager.KEY_CORNER_RADIUS, sp.getInt(io.mo.xatype.config.ConfigManager.KEY_CORNER_RADIUS, 16))
                    result.putInt(io.mo.xatype.config.ConfigManager.KEY_OPACITY, sp.getInt(io.mo.xatype.config.ConfigManager.KEY_OPACITY, 85))
                    result.putInt(io.mo.xatype.config.ConfigManager.KEY_BLUR_RADIUS, sp.getInt(io.mo.xatype.config.ConfigManager.KEY_BLUR_RADIUS, 50))
                    result.putInt(io.mo.xatype.config.ConfigManager.KEY_BG_TYPE, sp.getInt(io.mo.xatype.config.ConfigManager.KEY_BG_TYPE, 0))
                    result.putString(io.mo.xatype.config.ConfigManager.KEY_BG_COLOR, sp.getString(io.mo.xatype.config.ConfigManager.KEY_BG_COLOR, "#1E1E2E") ?: "#1E1E2E")
                    result.putLong(io.mo.xatype.config.ConfigManager.KEY_BG_IMAGE_VERSION, sp.getLong(io.mo.xatype.config.ConfigManager.KEY_BG_IMAGE_VERSION, 0L))
                }
            }
        }
        return result
    }

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        val ctx = context ?: return null
        val file = java.io.File(ctx.filesDir, BG_IMAGE_FILENAME)
        if (file.exists() && file.canRead()) {
            return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        return null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
