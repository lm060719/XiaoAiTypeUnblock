package io.mo.xatype.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.mo.xatype.shape.ShapeStore
import java.io.FileNotFoundException

/** Streams dictionary files, avoiding Binder's transaction-size limit for large code tables. */
class ShapeContentProvider : ContentProvider() {
    override fun onCreate() = true

    private fun checkCaller() = ProviderAccess.require(context, ProviderAccess.IME_ONLY)

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        checkCaller()
        val ctx = checkNotNull(context)
        return when (method) {
            "snapshot" -> ShapeStore.snapshot(ctx)
            "visibility" -> ShapeStore.pickerSnapshot(ctx)
            "prepare" -> { ShapeStore.ensureBuiltin(ctx); ShapeStore.snapshot(ctx) }
            "report" -> {
                val sp = ShapeStore.prefs(ctx)
                // Ignore results from an engine still running the previous settings.
                if (extras != null && extras.getLong(ShapeStore.REVISION, -1) == sp.getLong(ShapeStore.REVISION, 0)) {
                    sp.edit().putString(ShapeStore.STATUS, extras.getString(ShapeStore.STATUS).orEmpty().take(500))
                        .putLong(ShapeStore.STATUS_TIME, System.currentTimeMillis()).apply()
                }
                Bundle()
            }
            else -> super.call(method, arg, extras)
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        if (uri != DICTIONARY_URI || mode != "r") throw FileNotFoundException("Read-only dictionary URI required")
        return ShapeStore.openReadOnly(checkNotNull(context))
    }

    override fun getType(uri: Uri) = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        val CONTENT_URI: Uri = Uri.parse("content://io.mo.xatype.shapeprovider")
        val DICTIONARY_URI: Uri = Uri.parse("content://io.mo.xatype.shapeprovider/dictionary")
    }
}
