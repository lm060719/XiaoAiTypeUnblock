package io.mo.xatype.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.mo.xatype.config.AppearanceSchemeFile
import java.util.concurrent.Executors

/** Keeps document I/O and its pending result alive while the activity is recreated. */
class AppearanceSchemeTransfer : ViewModel() {
    sealed class Result {
        data class Imported(val document: AppearanceSchemeFile.Document) : Result()
        object Exported : Result()
        data class Failed(val action: String, val message: String) : Result()
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val outcome = MutableLiveData<Result?>()
    val result: LiveData<Result?> get() = outcome
    var busy = false
        private set

    fun importFile(context: Context, uri: Uri) {
        val resolver = context.applicationContext.contentResolver
        submit("导入") {
            val input = resolver.openInputStream(uri) ?: error("无法读取所选文件")
            Result.Imported(input.use { AppearanceSchemeFile.read(it) })
        }
    }

    fun exportFile(context: Context, uri: Uri, content: String) {
        val resolver = context.applicationContext.contentResolver
        submit("导出") {
            val output = resolver.openOutputStream(uri, "wt") ?: error("无法写入所选文件")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            Result.Exported
        }
    }

    fun consumeResult() {
        outcome.value = null
        busy = false
    }

    private fun submit(action: String, work: () -> Result) {
        check(!busy)
        busy = true
        worker.execute {
            val result = runCatching(work).getOrElse { Result.Failed(action, it.message ?: "请重试") }
            outcome.postValue(result)
        }
    }

    override fun onCleared() {
        worker.shutdownNow()
    }
}
