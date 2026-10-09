package ernest.nl2sh.helper

import org.junit.Assert.*
import org.junit.Test

class LocalDeviceConnectorTest {
    @Test fun discoveryRejectsRemoteDeviceEvenWithMatchingGuid() {
        assertFalse(localServiceMatches("adb-my-guid", "192.168.1.20", "my-guid", setOf("192.168.1.10")))
    }
    @Test fun discoveryRejectsOtherIdentityAndAcceptsLocalIpv6Scope() {
        assertFalse(localServiceMatches("adb-other", "192.168.1.10", "my-guid", setOf("192.168.1.10")))
        assertTrue(localServiceMatches("adb-my-guid (2)", "fe80::1%wlan0", "my-guid", setOf("fe80::1")))
    }
    @Test fun manualConnectionWithoutGuidHasStableSeparateHistoryIdentity() {
        val first = ConnectionRecord(ConnectionMode.LOCAL, LOCAL_ADB_HOST, 31000)
        assertEquals(first.identity, first.copy(port = 32000, guid = "new-guid").identity)
        assertNotEquals(first.identity, first.copy(mode = ConnectionMode.TCP).identity)
        assertEquals("http://127.0.0.1:32000/", webUrl(first.host, 32000))
    }
}
