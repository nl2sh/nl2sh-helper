package ernest.nl2sh.helper

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Once promotion starts, cancellation must also complete restoration before closing the ADB client. */
internal suspend fun <T> guardedUpgrade(hasPrevious: Boolean, promote: suspend () -> T,
                                       restore: suspend () -> Unit): T {
    try { return promote() }
    catch (failure: Throwable) {
        val restored = withContext(NonCancellable) { runCatching { restore() } }
        if (failure is CancellationException) {
            restored.exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
        val detail = when {
            restored.isFailure -> "回滚未完成：${restored.exceptionOrNull()?.message}；保留 previous/failed 文件，请检查。"
            hasPrevious -> "已恢复旧程序并验证旧服务。"
            else -> "首次安装未成功，失败程序已隔离。"
        }
        throw IllegalStateException("更新未成功：${failure.message}\n$detail", failure)
    }
}
