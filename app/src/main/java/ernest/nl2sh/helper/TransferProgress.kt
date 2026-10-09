package ernest.nl2sh.helper

import java.io.InputStream
import java.io.OutputStream

/** Transfer counters describe local bytes read/written, not remote installation success. */
internal data class TransferProgress(val label: String, val bytes: Long? = null, val total: Long? = null) {
    init {
        require(bytes == null || bytes >= 0)
        require(total == null || total > 0)
        require(bytes == null || total == null || bytes <= total)
    }
    val fraction: Float? get() = if (bytes != null && total != null) bytes.toFloat() / total else null
    val description: String get() = if (bytes != null && total != null)
        "${bytes.toDecimalBytes()} / ${total.toDecimalBytes()} · ${(bytes * 100 / total)}%" else ""
}

private fun Long.toDecimalBytes(): String = java.text.NumberFormat.getIntegerInstance().format(this) + " 字节"

/** Throttle UI updates while always emitting the initial and final transfer counts. */
private class TransferCounter(private val label: String, private val total: Long,
                              private val report: (TransferProgress) -> Unit) {
    private var bytes = 0L
    private var lastReport = System.nanoTime()
    init { report(TransferProgress(label, 0, total)) }
    fun add(count: Int) {
        bytes += count
        require(bytes <= total) { "Transfer exceeds verified asset size" }
        val now = System.nanoTime()
        if (bytes == total || now - lastReport >= 100_000_000L) {
            report(TransferProgress(label, bytes, total)); lastReport = now
        }
    }
}

/** Count bytes consumed by ADB without buffering or replacing its SYNC protocol. */
internal class ProgressInputStream(private val source: InputStream, label: String, total: Long,
                                  report: (TransferProgress) -> Unit,
                                  private val checkpoint: () -> Unit = {}) : InputStream() {
    private val counter = TransferCounter(label, total, report)
    override fun read(): Int {
        checkpoint()
        return source.read().also { if (it >= 0) counter.add(1) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkpoint()
        return source.read(buffer, offset, length).also { if (it > 0) counter.add(it) }
    }
    override fun close() = source.close()
}

/** Stream a release into its staged file with the same signed size and bounded I/O checks. */
internal fun copyRelease(input: InputStream, output: OutputStream, expectedSize: Long, maximum: Int,
                         label: String, report: (TransferProgress) -> Unit,
                         checkpoint: () -> Unit = {}) {
    require(expectedSize in 1..maximum.toLong()) { "Invalid release size" }
    val counter = TransferCounter(label, expectedSize, report)
    val buffer = ByteArray(64 * 1024)
    var bytes = 0L
    while (true) {
        checkpoint()
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        bytes += count
        check(bytes <= maximum && bytes <= expectedSize) { "Download exceeds size limit" }
        output.write(buffer, 0, count)
        counter.add(count)
    }
    check(bytes == expectedSize) { "Release size mismatch" }
}
