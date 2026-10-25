package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.BufferedReader
import java.io.File
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class GnssConversionCancellationTest {
    @Test
    fun scanCancellationClosesReaderBeforeReadingRemainingMeasurements() = cancelDuringPass(1)

    @Test
    fun bodyCancellationClosesReadersAndDeletesStagedOutput() = cancelDuringPass(2)

    @Test
    fun interruptedThreadStopsConverterWithoutChangingInput() {
        val root = createTempDirectory("rinex-interrupt-").toFile()
        val input = File(requireNotNull(javaClass.getResource("/fixtures/gnsslogger_minimal.txt")).toURI())
        val before = input.readBytes()
        try {
            Thread.currentThread().interrupt()
            assertThrows(AnalysisCancelledException::class.java) {
                PinnedAndroidRinexConverter().convert(input, root, AnalysisCancellation())
            }
            assertFalse(File(root, "rover.obs").exists())
            assertArrayEquals(before, input.readBytes())
        } finally {
            Thread.interrupted()
            root.deleteRecursively()
        }
    }

    private fun cancelDuringPass(cancelPass: Int) {
        val root = createTempDirectory("rinex-cancel-").toFile()
        val input = File(requireNotNull(javaClass.getResource("/fixtures/gnsslogger_minimal.txt")).toURI())
        val before = input.readBytes()
        val output = File(root, "rover.obs")
        val part = File(root, "rover.obs.part")
        val cancellation = AnalysisCancellation()
        val readers = mutableListOf<BufferedReader>()
        var closed = 0
        var readsAfterCancellation = 0
        var partPresentAtCancellation = false
        try {
            assertThrows(AnalysisCancelledException::class.java) {
                GnssLoggerRinex3Converter.convert(input, output, GENERATED_AT, cancellation) { file ->
                    val pass = readers.size + 1
                    object : BufferedReader(file.reader(Charsets.UTF_8)) {
                        private var rawCount = 0
                        override fun readLine(): String? {
                            if (cancellation.isCancelled()) readsAfterCancellation++
                            val line = super.readLine()
                            if (line?.startsWith("Raw,") == true) rawCount++
                            // The sixth body row is after the first epoch was processed and written.
                            val target = if (cancelPass == 1) 3 else 6
                            if (pass == cancelPass && rawCount == target) {
                                partPresentAtCancellation = part.isFile
                                cancellation.cancel()
                            }
                            return line
                        }
                        override fun close() {
                            closed++
                            super.close()
                        }
                    }.also { readers += it }
                }
            }
            assertEquals(cancelPass, readers.size)
            assertEquals(readers.size, closed)
            assertEquals(0, readsAfterCancellation)
            assertEquals(cancelPass == 2, partPresentAtCancellation)
            assertFalse(output.exists())
            assertFalse(part.exists())
            assertArrayEquals(before, input.readBytes())
            // A later conversion on this worker can use the same source and output path.
            val next = PinnedAndroidRinexConverter().convert(input, root, AnalysisCancellation())
            assertTrue(next.observationFile.isFile)
            assertArrayEquals(before, input.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private val GENERATED_AT = LocalDateTime.of(2026, 10, 10, 0, 0)
    }
}
