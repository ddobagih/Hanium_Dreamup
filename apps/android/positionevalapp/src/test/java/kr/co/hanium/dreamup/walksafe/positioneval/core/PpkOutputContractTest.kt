package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PpkOutputContractTest {
    @Test
    fun streamsUtcLlhWithoutLoadingWholeFile() = withOutput(
        "%  UTC latitude(deg) longitude(deg) height(m) Q\n" +
            "2026/09/04 01:02:03.000 37.0 127.0 42.0 1\n" +
            "2026/09/04 01:02:04.000 37.0 127.0 42.0 2\n",
    ) { file ->
        assertEquals(PpkOutputSummary(2, 1), PpkOutputContract.validate(file))
    }

    @Test
    fun rejectsOversizedLineAndExcessEpochs() = withOutput(
        "% UTC latitude(deg) longitude(deg) height(m)\n" +
            "2026/09/04 01:02:03.000 37.0 127.0 42.0 1\n",
    ) { file ->
        assertThrows(IllegalStateException::class.java) {
            PpkOutputContract.validate(file, maxLineChars = 8)
        }
        assertThrows(IllegalStateException::class.java) {
            PpkOutputContract.validate(file, maxDataLines = 0)
        }
    }

    @Test
    fun rejectsFileBeforeReadingWhenByteCapIsExceeded() = withOutput("0123456789") { file ->
        assertThrows(IllegalArgumentException::class.java) {
            PpkOutputContract.validate(file, maxOutputBytes = 5)
        }
    }

    private fun withOutput(content: String, block: (File) -> Unit) {
        val root = createTempDirectory("ppk-output-contract").toFile()
        try {
            val file = File(root, "solution.pos").apply { writeText(content, Charsets.US_ASCII) }
            block(file)
        } finally {
            root.deleteRecursively()
        }
    }
}
