package ernest.nl2sh.helper

import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeElfTest {
    @Test fun rejectsScriptsAndWrongAbiBeforeTargetExecution() {
        val file = File.createTempFile("runtime-", ".test")
        try {
            file.writeText("#!/system/bin/sh\n".repeat(10))
            assertThrows(IllegalArgumentException::class.java) { validateRuntimeElf(file, "x86_64") }
            val header = ByteArray(64)
            header[0] = 0x7f; header[1] = 'E'.code.toByte(); header[2] = 'L'.code.toByte(); header[3] = 'F'.code.toByte()
            header[4] = 2; header[5] = 1; header[18] = 62
            file.writeBytes(header)
            validateRuntimeElf(file, "x86_64")
            assertThrows(IllegalArgumentException::class.java) { validateRuntimeElf(file, "arm64-v8a") }
        } finally { file.delete() }
    }
}
