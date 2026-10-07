package ernest.nl2sh.helper

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpgradeTransactionTest {
    @Test fun failedTargetRestoresOldServiceAndReportsTheFailure() = runBlocking {
        var running = "old"
        val backup = "old"
        val failure = runCatching {
            guardedUpgrade(true, promote = {
                running = "new"
                error("new PID version mismatch")
            }, restore = { running = backup })
        }.exceptionOrNull()
        assertEquals("old", running)
        assertTrue(requireNotNull(failure).message!!.contains("已恢复旧程序"))
        assertTrue(failure.message!!.contains("version mismatch"))
    }

    @Test fun successfulTargetNeverRestoresPrevious() = runBlocking {
        var restored = false
        assertEquals("ready", guardedUpgrade(true, { "ready" }, { restored = true }))
        assertFalse(restored)
    }

    @Test fun cancellationWaitsForRestorationBeforeReturning() = runBlocking {
        var promoted = false
        var restored = false
        val operation = launch {
            guardedUpgrade(true, promote = {
                promoted = true
                delay(60_000)
            }, restore = { delay(20); restored = true })
        }
        while (!promoted) delay(1)
        operation.cancelAndJoin()
        assertTrue(restored)
    }

    @Test fun failedRollbackIsReportedInsteadOfClaimingRecovery() = runBlocking {
        val failure = runCatching {
            guardedUpgrade(true, { error("startup") }, { error("backup digest") })
        }.exceptionOrNull()
        assertTrue(requireNotNull(failure).message!!.contains("回滚未完成"))
        assertTrue(failure.message!!.contains("backup digest"))
    }

    @Test fun incompleteOrInvalidNativeIdentityIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { NativeServiceState("ready", null, "1.0.6", 9999) }
        assertThrows(IllegalArgumentException::class.java) { NativeServiceState("ready", 123, "1.0.6", 0) }
        assertThrows(IllegalArgumentException::class.java) { NativeServiceState("unknown", 123, "1.0.6", 9999) }
        assertEquals("stopped", NativeServiceState("stopped", null, null, null).state)
    }
}
