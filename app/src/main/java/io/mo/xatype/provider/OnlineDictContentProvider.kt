package io.mo.xatype.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.mo.xatype.dict.OnlineDictStore
import java.io.FileNotFoundException

/** Hands the queued word list to the input method process, which cannot read module storage directly. */
class OnlineDictContentProvider : ContentProvider() {
    override fun onCreate() = true

    private fun checkCaller() = ProviderAccess.require(context, ProviderAccess.IME_ONLY)

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        checkCaller()
        val ctx = checkNotNull(context)
        return when (method) {
            "state" -> OnlineDictStore.snapshot(ctx)
            "report" -> { OnlineDictStore.report(ctx, extras); Bundle() }
            else -> super.call(method, arg, extras)
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        if (uri != WORDS_URI || mode != "r") throw FileNotFoundException("Read-only word list URI required")
        return OnlineDictStore.openWords(checkNotNull(context))
    }

    override fun getType(uri: Uri) = "text/plain"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        val CONTENT_URI: Uri = Uri.parse("content://io.mo.xatype.dictprovider")
        val WORDS_URI: Uri = Uri.parse("content://io.mo.xatype.dictprovider/words")
    }
}
