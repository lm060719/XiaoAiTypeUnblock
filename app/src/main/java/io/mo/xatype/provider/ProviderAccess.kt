package io.mo.xatype.provider

import android.content.Context
import android.os.Binder
import android.os.Process

/** Caller check shared by the exported providers: the module itself plus a fixed set of hooked packages. */
object ProviderAccess {
    const val IME = "com.xiaomi.type"
    const val PHRASE = "com.miui.phrase"
    const val SYSTEM_UI = "com.android.systemui"

    val IME_ONLY: Set<String> = setOf(IME)
    /** Every process in the module's scope: they all read settings through [LogContentProvider]. */
    val SCOPE: Set<String> = setOf(IME, PHRASE, SYSTEM_UI)

    fun isAllowed(context: Context, uid: Int, allowed: Set<String>): Boolean {
        if (uid == Process.myUid()) return true
        val packages = context.packageManager.getPackagesForUid(uid) ?: return false
        return packages.any { it in allowed }
    }

    fun require(context: Context?, allowed: Set<String>) {
        val ctx = checkNotNull(context)
        if (!isAllowed(ctx, Binder.getCallingUid(), allowed)) {
            throw SecurityException("Caller is not allowed to use this provider")
        }
    }
}
