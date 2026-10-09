package ernest.nl2sh.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingOverlayPositionTest {
    @Test fun notificationRequiresDistinctPortAndSixDigitCode() {
        assertEquals(31000 to "012345", parseNotificationPairing(" 31000  012345 "))
        assertNull(parseNotificationPairing("0 012345"))
        assertNull(parseNotificationPairing("65536 012345"))
        assertNull(parseNotificationPairing("31000 12345"))
        assertNull(parseNotificationPairing("31000 012345 extra"))
    }
    @Test fun draggingCannotLoseWindowOffscreen() {
        assertEquals(0 to 0, clampOverlayPosition(-500, -200, 1080, 1920, 840, 600))
        assertEquals(240 to 1320, clampOverlayPosition(5000, 5000, 1080, 1920, 840, 600))
    }
    @Test fun rotationAndLargeFontsKeepOversizedWindowReachable() {
        assertEquals(0 to 0, clampOverlayPosition(900, 1500, 640, 320, 840, 600))
        assertEquals(100 to 0, clampOverlayPosition(100, 1500, 1920, 640, 840, 640))
    }
}
