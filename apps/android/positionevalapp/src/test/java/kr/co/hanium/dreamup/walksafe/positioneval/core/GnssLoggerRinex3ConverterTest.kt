package kr.co.hanium.dreamup.walksafe.positioneval.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class GnssLoggerRinex3ConverterTest {
    @Test
    fun convertsGpsMultiEpochTimeOffsetCodeTypeAndAdrSign() {
        val output = File("build/generated/rinex-fixtures/gnsslogger_multiepoch.obs").apply {
            parentFile?.mkdirs()
            delete()
        }

        val summary = GnssLoggerRinex3Converter.convert(fixture(), output, GENERATED_AT)
        val lines = output.readLines(Charsets.US_ASCII)

        assertEquals(8, summary.rawMeasurementCount)
        assertEquals(8, summary.acceptedMeasurementCount)
        assertEquals(2, summary.epochCount)
        assertEquals(4, summary.satelliteCount)
        assertEquals(8, summary.carrierPhaseCount)
        assertTrue(summary.excludedByReason.isEmpty())
        assertTrue(lines.any { it.startsWith("G    4 C1C L1C D1C S1C") })
        assertTrue(lines.any { it.startsWith("DBHZ") && it.contains("SIGNAL STRENGTH UNIT") })
        assertEquals(2, lines.count { it.startsWith("> 2021 12 07 19 23") })

        val gpsOne = lines.filter { it.startsWith("G01") }
        assertEquals(2, gpsOne.size)
        assertTrue(observation(gpsOne[0], 1) > 0.0)
        assertTrue(observation(gpsOne[1], 1) > observation(gpsOne[0], 1))
        assertEquals(22_922_199.978, observation(gpsOne[0], 0), 0.02)
        assertEquals(observation(gpsOne[0], 0), observation(gpsOne[1], 0), 0.02)
        assertEquals(6, gpsOne[0].substring(34, 35).toInt())
    }

    @Test
    fun rejectsNegativeUncertainty() {
        assertReason("NEGATIVE_UNCERTAINTY", mutateRaw(0, 15, "-1"))
    }

    @Test
    fun excludesModernUnsupportedRowsWhenSupportedMinimumRemains() {
        val lines = fixture().readLines().toMutableList()
        val template = lines.last { it.startsWith("Raw,") }.split(',')
        fun append(field: Int, value: String) {
            lines.add(template.toMutableList().also { it[field] = value }.joinToString(","))
        }
        append(28, "7")
        append(22, "")
        append(35, "")
        append(13, "16415")

        val summary = GnssLoggerRinex3Converter.convert(
            temporaryLog(lines),
            File(temporaryDirectory(), "rover.obs"),
            GENERATED_AT,
        )

        assertEquals(12, summary.rawMeasurementCount)
        assertEquals(8, summary.acceptedMeasurementCount)
        assertEquals(1, summary.excludedByReason["UNSUPPORTED_CONSTELLATION"])
        assertEquals(1, summary.excludedByReason["MISSING_CARRIER_FREQUENCY"])
        assertEquals(1, summary.excludedByReason["UNSUPPORTED_CODE_TYPE"])
        assertEquals(1, summary.excludedByReason["MSEC_AMBIGUOUS"])
    }

    @Test
    fun rejectsDuplicateObservableInOneEpoch() {
        val lines = fixture().readLines().toMutableList()
        val firstRaw = lines.first { it.startsWith("Raw,") }
        lines.add(lines.indexOf(firstRaw) + 1, firstRaw)
        assertReason("DUPLICATE_OBSERVABLE", temporaryLog(lines))
    }

    @Test
    fun rejectsFullBiasJumpWithoutDiscontinuity() {
        assertReason("CLOCK_BIAS_CHANGED_WITHOUT_DISCONTINUITY", mutateRaw(4, 5, "-1322936691943238371"))
    }

    @Test
    fun receiverUptimeLongerThanGpsWeekPreservesTheSameObservations() {
        val shifted = fixture().readLines().map { line ->
            if (!line.startsWith("Raw,")) line else line.split(',').toMutableList().also { fields ->
                val sevenWeeksNanos = 7L * 604_800_000_000_000L
                fields[2] = (fields[2].toLong() + sevenWeeksNanos).toString()
                fields[5] = (fields[5].toLong() + sevenWeeksNanos).toString()
            }.joinToString(",")
        }
        val baseline = File(temporaryDirectory(), "baseline.obs")
        val output = File(temporaryDirectory(), "long-uptime.obs")

        GnssLoggerRinex3Converter.convert(fixture(), baseline, GENERATED_AT)
        val summary = GnssLoggerRinex3Converter.convert(temporaryLog(shifted), output, GENERATED_AT)

        assertEquals(8, summary.carrierPhaseCount)
        assertEquals(baseline.readText(), output.readText())
    }

    @Test
    fun acceptsNanosecondFullBiasUpdatesBetweenEpochsWithoutInventingDiscontinuity() {
        val lines = fixture().readLines().map { line ->
            if (!line.startsWith("Raw,")) line else line.split(',').toMutableList().also { fields ->
                if (fields[2] == "3499506000000") fields[5] = (fields[5].toLong() + 29L).toString()
            }.joinToString(",")
        }

        val summary = GnssLoggerRinex3Converter.convert(
            temporaryLog(lines), File(temporaryDirectory(), "rover.obs"), GENERATED_AT,
        )

        assertEquals(8, summary.acceptedMeasurementCount)
        assertEquals(8, summary.carrierPhaseCount)
        assertEquals(2, summary.epochCount)
    }

    @Test
    fun rejectsLargeUnannouncedClockJumpEvenWhenRecordedUtcFollowsIt() {
        val lines = fixture().readLines().map { line ->
            if (!line.startsWith("Raw,")) line else line.split(',').toMutableList().also { fields ->
                if (fields[2] == "3499506000000") {
                    fields[5] = (fields[5].toLong() + 1_000_000_000L).toString()
                    fields[1] = (fields[1].toLong() - 1_000L).toString()
                }
            }.joinToString(",")
        }

        assertReason("CLOCK_BIAS_CHANGED_WITHOUT_DISCONTINUITY", temporaryLog(lines))
    }

    @Test
    fun stillRejectsDeviceUtcMismatchWithAContinuousReceiverClock() {
        assertReason("UTC_GPS_TIME_MISMATCH", mutateRaw(4, 1, "1638904973849"))
    }

    @Test
    fun rejectsHundredNanosecondDifferenceInsideAnEpoch() {
        // Whole-GPS-time Double arithmetic collapses these two corrected times to one value.
        assertReason("INCONSISTENT_EPOCH", mutateRaw(1, 12, "100.0"))
    }

    @Test
    fun retainsSeparateHundredNanosecondEpochsAndExactRinexFractions() {
        val fixtureLines = fixture().readLines()
        val firstEpoch = fixtureLines.filter { it.startsWith("Raw,") }.take(4)
        val nextEpoch = firstEpoch.map { line ->
            line.split(',').toMutableList().also { fields ->
                fields[2] = (fields[2].toLong() + 100L).toString()
                fields[14] = (fields[14].toLong() + 100L).toString()
            }.joinToString(",")
        }
        val input = temporaryLog(fixtureLines.filterNot { it.startsWith("Raw,") } + firstEpoch + nextEpoch)
        val output = File(temporaryDirectory(), "nanosecond-epochs.obs")

        val summary = GnssLoggerRinex3Converter.convert(input, output, GENERATED_AT)
        val epochs = output.readLines().filter { it.startsWith("> ") }

        assertEquals(2, summary.epochCount)
        assertTrue(epochs[0].contains("10.4492394"))
        assertTrue(epochs[1].contains("10.4492395"))
    }

    @Test
    fun clockSegmentSuppressesPhaseAndMarksNextValidPhaseWithLli() {
        val lines = fixture().readLines().toMutableList()
        val rawIndices = lines.indices.filter { lines[it].startsWith("Raw,") }
        rawIndices.drop(4).forEach { lineIndex ->
            val fields = lines[lineIndex].split(',').toMutableList()
            fields[10] = "161"
            lines[lineIndex] = fields.joinToString(",")
        }
        appendThirdEpoch(lines)
        val output = File(temporaryDirectory(), "rover.obs")

        GnssLoggerRinex3Converter.convert(temporaryLog(lines), output, GENERATED_AT)
        val gpsOne = output.readLines().filter { it.startsWith("G01") }

        assertTrue(gpsOne[1].substring(19, 35).isBlank())
        assertEquals("1", gpsOne[2].substring(33, 34))
    }

    @Test
    fun resetAndCycleSlipMarkNextValidPhaseWithLli() {
        val lines = fixture().readLines().toMutableList()
        val rawIndices = lines.indices.filter { lines[it].startsWith("Raw,") }
        listOf(27, 29).forEachIndexed { index, state ->
            val fields = lines[rawIndices[index]].split(',').toMutableList()
            fields[19] = state.toString()
            lines[rawIndices[index]] = fields.joinToString(",")
        }
        val output = File(temporaryDirectory(), "rover.obs")

        GnssLoggerRinex3Converter.convert(temporaryLog(lines), output, GENERATED_AT)
        val observations = output.readLines()

        assertEquals("1", observations.filter { it.startsWith("G01") }[1].substring(33, 34))
        assertEquals("1", observations.filter { it.startsWith("G03") }[1].substring(33, 34))
    }

    @Test
    fun missingSignalEpochMarksReturningPhaseWithLli() {
        val lines = fixture().readLines().toMutableList()
        appendThirdEpoch(lines)
        val missingIndex = lines.indexOfFirst { line ->
            line.startsWith("Raw,") && line.split(',').let { it[2] == "3499506000000" && it[11] == "1" }
        }
        lines.removeAt(missingIndex)
        val output = File(temporaryDirectory(), "rover.obs")

        GnssLoggerRinex3Converter.convert(temporaryLog(lines), output, GENERATED_AT)
        val gpsOne = output.readLines().filter { it.startsWith("G01") }

        assertEquals(2, gpsOne.size)
        assertEquals("1", gpsOne[1].substring(33, 34))
    }

    @Test
    fun mi8ModelDoesNotBypassUnresolvedHalfCycle() {
        val lines = fixture().readLines().map { line ->
            when {
                line.startsWith("# Version:") -> line.replace("Model: Pixel 5", "Model: MI 8")
                line.startsWith("Raw,") && line.split(',')[11] == "1" -> line.split(',').toMutableList().also { it[19] = "17" }.joinToString(",")
                else -> line
            }
        }
        val output = File(temporaryDirectory(), "rover.obs")

        GnssLoggerRinex3Converter.convert(temporaryLog(lines), output, GENERATED_AT)

        output.readLines().filter { it.startsWith("G01") }.forEach { satelliteLine ->
            assertTrue(satelliteLine.substring(19, 35).isBlank())
        }
    }

    @Test
    fun rejectsLeapSecondMismatch() {
        assertReason("LEAP_SECOND_MISMATCH", mutateRaw(0, 3, "17"))
    }

    private fun assertReason(expected: String, input: File) {
        val error = assertThrows(GnssLoggerConversionException::class.java) {
            GnssLoggerRinex3Converter.convert(input, File(temporaryDirectory(), "rover.obs"), GENERATED_AT)
        }
        assertEquals(expected, error.reasonCode)
    }

    private fun observation(line: String, index: Int): Double = line.substring(3 + index * 16, 17 + index * 16).trim().toDouble()

    private fun mutateRaw(rawIndex: Int, fieldIndex: Int, value: String): File {
        val lines = fixture().readLines().toMutableList()
        val rawIndices = lines.indices.filter { lines[it].startsWith("Raw,") }
        val fields = lines[rawIndices[rawIndex]].split(',').toMutableList()
        fields[fieldIndex] = value
        lines[rawIndices[rawIndex]] = fields.joinToString(",")
        return temporaryLog(lines)
    }

    private fun temporaryLog(lines: List<String>): File = File.createTempFile("gnsslogger-test", ".txt").apply {
        writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun appendThirdEpoch(lines: MutableList<String>) {
        val previousEpoch = lines.filter { it.startsWith("Raw,") }.takeLast(4)
        previousEpoch.forEach { line ->
            val fields = line.split(',').toMutableList()
            fields[1] = (fields[1].toLong() + 1_000L).toString()
            fields[2] = (fields[2].toLong() + 1_000_000_000L).toString()
            fields[14] = (fields[14].toLong() + 1_000_000_000L).toString()
            fields[20] = (fields[20].toDouble() + fields[17].toDouble()).toString()
            lines.add(fields.joinToString(","))
        }
    }

    private fun fixture(): File = File(requireNotNull(javaClass.getResource("/fixtures/gnsslogger_minimal.txt")).toURI())

    private fun temporaryDirectory(): File = File.createTempFile("rinex-output", ".dir").apply {
        assertTrue(delete())
        assertTrue(mkdirs())
    }

    private companion object {
        val GENERATED_AT: LocalDateTime = LocalDateTime.of(2026, 9, 4, 0, 0, 0)
    }
}
