package ernest.nl2sh.helper

import org.junit.Assert.*
import org.junit.Test

class DeviceDoctorTest {
    @Test fun missingProtocolEvidenceIsUnknownRatherThanStoppedOrHealthy() {
        for (state in listOf(null, "", "unexpected", "unknown")) {
            assertEquals(DoctorState.UNKNOWN, doctorProtocolCheck(state).state)
        }
        assertEquals(DoctorState.ATTENTION, doctorProtocolCheck("stopped").state)
        assertTrue(doctorProtocolCheck("running").detail.contains("未测试"))
    }
    @Test fun webIdentityRequiresExactPidPortAndVersion() {
        val expected = DoctorIdentity(42, "1.1.0", 9999)
        assertNotEquals(expected, expected.copy(pid = 43))
        assertNotEquals(expected, expected.copy(port = 10000))
        assertNotEquals(expected, expected.copy(version = "1.2.0"))
        assertThrows(IllegalArgumentException::class.java) { DoctorIdentity(0, "1.1.0", 9999) }
        assertThrows(IllegalArgumentException::class.java) { DoctorIdentity(42, "invalid version", 9999) }
        assertThrows(IllegalArgumentException::class.java) { DoctorIdentity(42, "1.1.0", 0) }
    }
    @Test fun partialReportsKeepUnknownChecksAndRequireAttention() {
        val record = ConnectionRecord(ConnectionMode.TCP, "127.0.0.1", 5555)
        val report = DoctorReport(record, listOf(DoctorCheck("ADB", DoctorState.OK, "connected"),
            DoctorCheck("Web", DoctorState.UNKNOWN, "not verified")))
        assertTrue(report.needsAttention)
        assertNull(report.verifiedWebUrl)
        assertEquals(record, report.target)
    }
}
