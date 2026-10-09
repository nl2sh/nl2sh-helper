package ernest.nl2sh.helper

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class TransferProgressTest {
    @Test fun downloadPreservesBytesAndReportsExactInitialAndFinalCounters() {
        val bytes = ByteArray(130_000) { (it % 251).toByte() }
        val events = mutableListOf<TransferProgress>()
        val output = ByteArrayOutputStream()
        copyRelease(ByteArrayInputStream(bytes), output, bytes.size.toLong(), 140_000, "下载", events::add)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(0L, events.first().bytes)
        assertEquals(bytes.size.toLong(), events.last().bytes)
        assertEquals(1f, events.last().fraction)
        assertTrue(events.last().description.endsWith("100%"))
    }
    @Test fun truncatedAndOversizedDownloadsNeverReportSuccessfulTransfer() {
        val events = mutableListOf<TransferProgress>()
        assertTrue(runCatching {
            copyRelease(ByteArrayInputStream(ByteArray(3)), ByteArrayOutputStream(), 4, 10, "下载", events::add)
        }.isFailure)
        assertFalse(events.any { it.fraction == 1f })
        events.clear()
        val output = ByteArrayOutputStream()
        assertTrue(runCatching {
            copyRelease(ByteArrayInputStream(ByteArray(5)), output, 4, 10, "下载", events::add)
        }.isFailure)
        assertEquals(0, output.size())
        assertFalse(events.any { it.fraction == 1f })
    }
    @Test fun adbStreamCountsMixedReadsOnceAndClosesUnderlyingFile() {
        val events = mutableListOf<TransferProgress>()
        var closed = false
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
            override fun close() { closed = true; super.close() }
        }
        ProgressInputStream(source, "推送", 4, events::add).use { stream ->
            assertEquals(1, stream.read())
            val buffer = ByteArray(2)
            assertEquals(2, stream.read(buffer))
            assertArrayEquals(byteArrayOf(2, 3), buffer)
            assertEquals(4, stream.read())
            assertEquals(-1, stream.read())
        }
        assertTrue(closed)
        assertEquals(listOf(0L, 4L), events.map { it.bytes })
    }
    @Test fun cancellationAndReadErrorsDoNotInventCompletedProgress() {
        val events = mutableListOf<TransferProgress>()
        ProgressInputStream(ByteArrayInputStream(ByteArray(4)), "推送", 4, events::add,
            checkpoint = { throw kotlinx.coroutines.CancellationException("cancelled") }).use { stream ->
            assertTrue(runCatching { stream.read(ByteArray(4)) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        }
        assertFalse(events.any { it.fraction == 1f })
        val broken = object : InputStream() { override fun read(): Int = throw IOException("network failed") }
        assertTrue(runCatching { copyRelease(broken, ByteArrayOutputStream(), 4, 10, "下载", events::add) }.isFailure)
        assertFalse(events.any { it.fraction == 1f })
    }
}
