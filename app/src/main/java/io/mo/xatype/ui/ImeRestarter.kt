package io.mo.xatype.ui

import android.app.Activity
import android.widget.Toast
import java.io.DataOutputStream

/** Force-stops the input method processes through root so new settings load. */
internal object ImeRestarter {
    fun restart(activity: Activity, onFinished: (() -> Unit)? = null) {
        Thread {
            val success = try {
                val process = Runtime.getRuntime().exec("su")
                DataOutputStream(process.outputStream).use { os ->
                    os.writeBytes("am force-stop com.miui.phrase\n")
                    os.writeBytes("am force-stop com.xiaomi.type\n")
                    os.writeBytes("exit\n")
                    os.flush()
                }
                process.waitFor() == 0
            } catch (_: Throwable) {
                false
            }
            activity.runOnUiThread {
                onFinished?.invoke()
                Toast.makeText(
                    activity,
                    if (success) "超级小爱输入法已成功通过 Root 权限重启！" else "请授权 Root 权限后操作！",
                    if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }
}
