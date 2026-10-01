package ernest.nl2sh.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseSelectionTest {
    @Test fun prefers64BitWhenBothAreAvailable() {
        assertEquals("arm64-v8a", selectAbi("armeabi-v7a,arm64-v8a"))
    }

    @Test fun accepts32BitOnlyDevice() {
        assertEquals("armeabi-v7a", selectAbi("armeabi-v7a,armeabi"))
    }

    @Test fun rejectsUnsupportedDevice() {
        assertThrows(IllegalStateException::class.java) { selectAbi("x86,x86_64") }
    }

    @Test fun parsesReleaseChecksum() {
        val hash = "4557678c93309632b6b8463364dc480b13b794bdbbd3bd065a7ee29c854261a8"
        assertEquals(hash, parseSha256("$hash  nl2sh-android-arm64-v8a\n"))
    }

    @Test fun rejectsMalformedChecksum() {
        assertThrows(IllegalArgumentException::class.java) { parseSha256("invalid") }
    }
}
