package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PpkEvaluationTest {
    @Test
    fun parserUsesOnlyQ1AsFixedTruth() {
        val parsed = PpkPosParser.parse(
            """
            %  UTC latitude(deg) longitude(deg) height(m) Q ns sdn(m) sde(m)
            2025/01/02 03:04:05.000 37.0 127.0 10.0 1 15 0.1 0.1
            2025/01/02 03:04:06.000 37.0 127.0 10.0 2 14 0.2 0.2
            """.trimIndent(),
        )
        assertEquals(2, parsed.epochs.size)
        assertEquals(1, parsed.fixedCount)
    }

    @Test
    fun gpstUsesDateSpecificLeapSecondsAndFailsClosedAfterSupportedRange() {
        val before = parseSingle("GPST", "2016/01/02 03:04:05.000")
        val after = parseSingle("GPST", "2017/01/02 03:04:05.000")
        val localBefore = LocalDateTime.of(2016, 1, 2, 3, 4, 5).toInstant(ZoneOffset.UTC).toEpochMilli()
        val localAfter = LocalDateTime.of(2017, 1, 2, 3, 4, 5).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(localBefore - 17_000L, before.epochs.single().utcEpochMs)
        assertEquals(localAfter - 18_000L, after.epochs.single().utcEpochMs)
        assertThrows(PpkFormatException::class.java) { parseSingle("GPST", "2027/01/01 00:00:00.000") }
    }

    @Test
    fun rejectsUnknownTimeSystemAndInvalidQuality() {
        assertThrows(PpkFormatException::class.java) { parseSingle("JST", "2025/01/02 03:04:05.000") }
        assertThrows(PpkFormatException::class.java) {
            PpkPosParser.parse("% UTC latitude(deg) longitude(deg) height(m) Q ns\n2025/01/02 03:04:05 37 127 0 0 10")
        }
    }

    @Test
    fun rejectsImpossibleCalendarDatesInsteadOfNormalizingThem() {
        listOf("2025/02/29 03:04:05", "2025/02/30 03:04:05", "2025/01/02 24:00:00").forEach { timestamp ->
            assertThrows(PpkFormatException::class.java) { parseSingle("UTC", timestamp) }
        }
        val expected = LocalDateTime.of(2024, 2, 29, 3, 4, 5).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expected, parseSingle("UTC", "2024/02/29 03:04:05").epochs.single().utcEpochMs)
    }

    @Test
    fun exactlyThirtySecondNonAlignedWindowUsesThirtyGridPoints() {
        val start = 1_735_787_045_500L
        val samples = (0..30).map { index -> sample(index, start + index * 1_000L, POINT) }
        val firstGrid = 1_735_787_046_000L
        val epochs = (0 until 30).map { index -> epoch(firstGrid + index * 1_000L, 1) }
        val result = PositionEvaluator.evaluate(
            TraceSession(samples, start, start + 30_000L, POINT, 0.0),
            ParsedPpk("UTC", epochs, epochs.size),
        )
        assertEquals(EvaluationStatus.EVALUATED, result.status)
        assertEquals(30, result.gridCount)
        assertEquals(30, result.eligibleTruthCount)
    }

    @Test
    fun channelWithTooFewSamplesKeepsMetricsNullAndExplicitCount() {
        val start = 1_735_787_045_000L
        val samples = (0..30).map { index -> sample(index, start + index * 1_000L, if (index < 10) POINT else null) }
        val epochs = (0..30).map { index -> epoch(start + index * 1_000L, 1) }
        val result = PositionEvaluator.evaluate(
            TraceSession(samples, start, start + 30_000L, POINT, 0.0),
            ParsedPpk("UTC", epochs, epochs.size),
        )
        val matched = result.metrics.single { it.channel == PositionChannel.MATCHED }
        assertEquals(10, matched.availableCount)
        assertNull(matched.p50ErrorM)
    }

    @Test
    fun filteredCorrectionAtSameTimePreservesRawAndUsesLatestValueForItsOwnChannel() {
        val start = 1_735_787_045_000L
        val samples = (0..30).flatMap { index ->
            val at = start + index * 1_000L
            listOf(
                sample(index * 2, at, POINT).copy(filtered = GeoPoint(37.001, 127.0)),
                sample(index * 2 + 1, at, null).copy(
                    measurementTimeSource = "sensor_gnss_anchored",
                    routeMatchEvaluated = false,
                    raw = null,
                ),
            )
        }

        val result = evaluateSamples(samples, start)

        assertEquals(EvaluationStatus.EVALUATED, result.status)
        PositionChannel.entries.forEach { channel ->
            val metrics = result.metrics.single { it.channel == channel }
            assertEquals(31, metrics.availableCount)
            assertEquals(0.0, requireNotNull(metrics.maxErrorM), 0.001)
        }
    }

    @Test
    fun rawChannelUsesNoFutureSampleAndExpiresAtItsOwnTwoSecondAge() {
        val start = 1_735_787_045_000L
        val samples = (0..30).map { index ->
            sample(index, start + index * 1_000L, null).copy(
                measurementTimeSource = if (index == 1) "gnss" else "sensor_gnss_anchored",
                routeMatchEvaluated = index == 1,
                raw = POINT.takeIf { index == 1 },
            )
        }

        val result = evaluateSamples(samples, start)

        assertEquals(3, result.metrics.single { it.channel == PositionChannel.RAW }.availableCount)
        assertEquals(31, result.metrics.single { it.channel == PositionChannel.FILTERED }.availableCount)
        assertEquals(0, result.metrics.single { it.channel == PositionChannel.MATCHED }.availableCount)
    }

    @Test
    fun unmatchedGnssInvalidatesPriorMatchEvenWhenFollowedByFilteredOnlyCorrection() {
        val start = 1_735_787_045_000L
        val samples = (0..30).flatMap { index ->
            val at = start + index * 1_000L
            listOf(
                sample(index * 2, at, POINT.takeIf { index < 10 }),
                sample(index * 2 + 1, at, null).copy(
                    measurementTimeSource = "sensor_gnss_anchored",
                    routeMatchEvaluated = false,
                    raw = null,
                ),
            )
        }

        val result = evaluateSamples(samples, start)

        assertEquals(10, result.metrics.single { it.channel == PositionChannel.MATCHED }.availableCount)
        assertEquals(31, result.metrics.single { it.channel == PositionChannel.RAW }.availableCount)
    }

    @Test
    fun noFixedTruthAndForgedFixedCountFailClosed() {
        val start = 1_735_787_045_000L
        val samples = (0..30).map { index -> sample(index, start + index * 1_000L, POINT) }
        val floatEpochs = (0..30).map { index -> epoch(start + index * 1_000L, 2) }
        val trace = TraceSession(samples, start, start + 30_000L, POINT, 0.0)
        val noFix = PositionEvaluator.evaluate(trace, ParsedPpk("UTC", floatEpochs, 0))
        val forged = PositionEvaluator.evaluate(trace, ParsedPpk("UTC", floatEpochs, 1))
        assertEquals("PPK_NO_FIXED_SOLUTION", noFix.reasonCodes.single())
        assertEquals("PPK_FIXED_COUNT_MISMATCH", forged.reasonCodes.single())
    }

    @Test
    fun fileParserUsesBoundedValidatedPath() {
        val root = createTempDirectory("ppk-file-parser").toFile()
        try {
            val file = File(root, "reference.pos").apply {
                writeText(
                    "% UTC latitude(deg) longitude(deg) height(m) Q ns\n" +
                        "2025/01/02 03:04:05.000 37.0 127.0 10.0 1 15\n",
                    Charsets.US_ASCII,
                )
            }
            assertEquals(1, PpkPosParser.parse(file).fixedCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun haversineHandlesDateline() {
        val distance = PositionEvaluator.haversineMeters(GeoPoint(0.0, 179.999), GeoPoint(0.0, -179.999))
        assertEquals(222.39, distance, 0.5)
    }

    private fun parseSingle(system: String, timestamp: String): ParsedPpk = PpkPosParser.parse(
        "% $system latitude(deg) longitude(deg) height(m) Q ns\n$timestamp 37.0 127.0 10.0 1 15",
    )

    private fun sample(index: Int, utc: Long, matched: GeoPoint?) = TraceSample(
        index.toLong() + 1L, utc, index * 1_000_000_000L, "gnss", POINT, POINT, matched,
    )

    private fun epoch(utc: Long, quality: Int) = PpkEpoch(utc, POINT, 10.0, quality, 15, 0.1, 0.1, 5.0)

    private fun evaluateSamples(samples: List<TraceSample>, start: Long): EvaluationOutcome {
        val epochs = (0..30).map { index -> epoch(start + index * 1_000L, 1) }
        return PositionEvaluator.evaluate(
            TraceSession(samples, start, start + 30_000L, POINT, 0.0),
            ParsedPpk("UTC", epochs, epochs.size),
        )
    }

    private companion object { val POINT = GeoPoint(37.0, 127.0) }
}
