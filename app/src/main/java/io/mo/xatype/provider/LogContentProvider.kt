package io.mo.xatype.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.mo.xatype.compat.AdaptationStatus
import io.mo.xatype.config.AppearanceProfiles
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.config.ConfigSchema
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** Settings, the keyboard background image and adaptation reports for the hooked processes. */
class LogContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "io.mo.xatype.logprovider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val METHOD_GET_CONFIG = "get_config"
        const val BG_IMAGE_FILENAME = "bg_image.png"
        private const val BG_IMAGE_PATH = "/$BG_IMAGE_FILENAME"
    }

    /** Held here because SharedPreferences only keeps weak references to listeners. */
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        // Every settings write in this process reaches the hooked processes as one notification.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            ctx.contentResolver.notifyChange(ConfigManager.CONFIG_URI, null)
        }
        ctx.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
        settingsListener = listener
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        ProviderAccess.require(context, ProviderAccess.SCOPE)
        return when (method) {
            AdaptationStatus.METHOD_REPORT -> { saveAdaptationReport(extras); Bundle() }
            METHOD_GET_CONFIG -> readConfig(extras)
            else -> super.call(method, arg, extras)
        }
    }

    private fun readConfig(extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle()
        val local = ctx.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        AppearanceProfiles.initialize(local)
        val systemDark = extras?.getBoolean(AppearanceProfiles.EXTRA_DARK)
            ?: ((ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES)
        val profile = AppearanceProfiles.selected(local, systemDark)
        return ConfigSchema.toBundle(ConfigSchema.read(local, profile))
    }

    private fun saveAdaptationReport(extras: Bundle?) {
        val ctx = context ?: return
        val caller = callingPackage
        val reported = extras?.getString(AdaptationStatus.EXTRA_PACKAGE) ?: return
        val trusted = setOf(AdaptationStatus.IME_PACKAGE, AdaptationStatus.PHRASE_PACKAGE)
        if (caller !in trusted || reported !in trusted) return
        val json = JSONObject()
            .put(AdaptationStatus.EXTRA_VERSION_CODE, extras.getLong(AdaptationStatus.EXTRA_VERSION_CODE, -1L))
            .put(AdaptationStatus.EXTRA_MODULE_STAMP, extras.getString(AdaptationStatus.EXTRA_MODULE_STAMP))
            .put(AdaptationStatus.EXTRA_SOURCE, extras.getString(AdaptationStatus.EXTRA_SOURCE))
            .put(
                AdaptationStatus.EXTRA_MISSING,
                JSONArray(extras.getStringArrayList(AdaptationStatus.EXTRA_MISSING).orEmpty())
            )
        ctx.getSharedPreferences(AdaptationStatus.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(reported, json.toString()).apply()
    }

    /** Only the keyboard background image, read-only, and only for the input method. */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        ProviderAccess.require(context, ProviderAccess.IME_ONLY)
        if (uri.path != BG_IMAGE_PATH || mode != "r") throw FileNotFoundException("Read-only background image URI required")
        val file = File(checkNotNull(context).filesDir, BG_IMAGE_FILENAME)
        if (!file.isFile || !file.canRead()) throw FileNotFoundException("No background image")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = if (uri.path == BG_IMAGE_PATH) "image/png" else null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
