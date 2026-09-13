package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TraceV2ParserTest {
    @Test
    fun parsesActualAndroidExporterFixtureUsingSourceField() {
        val result = TraceV2Parser.parse(File("../../../tests/fixtures/positioning_trace_contract_v2.jsonl"))
        assertEquals(1, result.samples.size)
        assertEquals("gnss", result.samples.single().measurementTimeSource)
        assertEquals(1_700_000_000_000L, result.startUtcEpochMs)
    }

    @Test
    fun acceptsUtcTiesAndExcludesMonotonicOnlySamplesFromJoining() {
        val records = listOf(
            position(1, 2_000, 2_000, "gnss", 1_700_000_000_000),
            position(2, 3_000, 2_000, "sensor_gnss_anchored", 1_700_000_000_000),
            position(3, 4_000, 4_000, "sensor_monotonic_only", null),
            checkpoint(4, 5_000, 5_000, "checkpoint_monotonic_only", null, 1),
        )
        val result = TraceV2Parser.parse(temporaryFile(validTrace(records)))
        assertEquals(2, result.samples.size)
        assertEquals(0.0, result.timeOffsetSpreadMs, 0.001)
    }

    @Test
    fun delayedGnssAfterSensorIsSortedByMeasurementUtcWithLaterCorrectionLast() {
        val origin = 1_700_000_000_000L
        val records = listOf(
            position(1, 2_100_000_000L, 2_000_000_000L, "sensor_gnss_anchored", origin + 2_000L),
            position(2, 2_200_000_000L, 1_000_000_000L, "gnss", origin + 1_000L),
            position(3, 2_300_000_000L, 2_000_000_000L, "sensor_gnss_anchored", origin + 2_000L),
        )

        val result = TraceV2Parser.parse(temporaryFile(validTrace(records)))

        assertEquals(listOf(2L, 1L, 3L), result.samples.map { it.sequence })
        assertEquals(origin + 1_000L, result.startUtcEpochMs)
        assertEquals(origin + 2_000L, result.endUtcEpochMs)
        assertEquals(0.0, result.timeOffsetSpreadMs, 0.001)
    }

    @Test
    fun fractionalNanosecondGnssReanchorNormalizesSameMeasurementWithoutChangingSealedFile() {
        val origin = 1_700_000_000_000L
        val records = listOf(
            position(1, 1_100_000_000L, 1_000_900_000L, "gnss", origin + 1_001L),
            position(2, 2_100_000_000L, 2_000_000_000L, "sensor_gnss_anchored", origin + 2_000L),
            position(3, 2_200_000_000L, 1_500_100_000L, "gnss", origin + 1_500L),
            position(4, 2_300_000_000L, 2_000_000_000L, "sensor_gnss_anchored", origin + 1_999L),
        )
        val file = temporaryFile(validTrace(records))
        val sealedBytes = file.readBytes()

        val result = TraceV2Parser.parse(file)

        assertEquals(listOf(1L, 3L, 2L, 4L), result.samples.map { it.sequence })
        assertEquals(listOf(origin + 2_000L, origin + 2_000L), result.samples.takeLast(2).map { it.utcEpochMs })
        assertEquals(1.1, result.timeOffsetSpreadMs, 0.001)
        assertArrayEquals(sealedBytes, file.readBytes())
    }

    @Test
    fun oneMillisecondReversalRetainsActualMeasurementOrderBeforeReceiptTieBreaking() {
        val origin = 1_700_000_000_000L
        val records = listOf(
            position(1, 1_100_000_000L, 1_000_200_000L, "gnss", origin + 1_001L),
            position(2, 1_200_000_000L, 1_000_100_000L, "gnss", origin + 1_002L),
        )

        val result = TraceV2Parser.parse(temporaryFile(validTrace(records)))

        assertEquals(listOf(2L, 1L), result.samples.map { it.sequence })
        assertEquals(listOf(origin + 1_002L, origin + 1_002L), result.samples.map { it.utcEpochMs })
    }

    @Test
    fun repeatedOneMillisecondRegressionsCannotAccumulateBehindRoundingClamp() {
        val origin = 1_700_000_000_000L
        assertReason(
            "TIME_ALIGNMENT_FAILED",
            validTrace(
                listOf(
                    position(1, 1_100_000_000L, 1_000_000_000L, "gnss", origin + 1_000L),
                    position(2, 1_200_000_000L, 1_000_100_000L, "gnss", origin + 999L),
                    position(3, 1_300_000_000L, 1_000_200_000L, "gnss", origin + 998L),
                ),
            ),
        )
    }

    @Test
    fun sameMeasurementGroupCannotHideTwoMillisecondClockChangeOrCompoundRounding() {
        val origin = 1_700_000_000_000L
        val first = position(1, 1_100_000_000L, 1_000_000_000L, "gnss", origin + 1_000L)
        listOf(1_000_000_000L, 1_000_100_000L).forEach { measurement ->
            assertReason(
                "TIME_ALIGNMENT_FAILED",
                validTrace(
                    listOf(
                        first,
                        position(2, 1_200_000_000L, measurement, "gnss", origin + 999L),
                        position(3, 1_300_000_000L, measurement, "gnss", origin + 998L),
                    ),
                ),
            )
        }
    }

    @Test
    fun routeMatchEvaluationFlagUsesStrictBooleanAndPreservesLegacySourceDefaults() {
        val origin = 1_700_000_000_000L
        val records = listOf(
            position(1, 1_100_000_000L, 1_000_000_000L, "gnss", origin + 1_000L),
            position(2, 1_200_000_000L, 1_000_000_000L, "sensor_gnss_anchored", origin + 1_000L),
            positionWithPoints(
                3, 1_300_000_000L, 1_000_000_000L, "sensor_gnss_anchored", origin + 1_000L,
                null, 37.5, null, routeMatchEvaluated = true,
            ),
            positionWithPoints(
                4, 1_400_000_000L, 1_000_000_000L, "sensor_gnss_anchored", origin + 1_000L,
                null, 37.5, null, routeMatchEvaluated = false,
            ),
        )
        val result = TraceV2Parser.parse(temporaryFile(validTrace(records)))
        assertEquals(listOf(true, false, true, false), result.samples.map { it.routeMatchEvaluated })
        listOf("\"true\"", "1", "null").forEach { invalid ->
            assertReason(
                "TRACE_FIELD_INVALID",
                validTrace(listOf(records.first().dropLast(1) + ",\"route_match_evaluated\":$invalid}")),
            )
        }
    }

    @Test
    fun millisecondNormalizedReplayUsesLatestMatchOrExplicitUnmatchedCorrection() {
        val origin = 1_700_000_000_000L
        val truthPoint = GeoPoint(37.5, 127.2)
        for (correctionHasMatch in listOf(true, false)) {
            val records = (1..31).flatMap { index ->
                val measurementNs = index * 1_000_000_000L
                val utc = origin + index * 1_000L
                listOf(
                    positionWithPoints(
                        index * 2 - 1, measurementNs + 100_000_000L, measurementNs, "sensor_gnss_anchored", utc,
                        null, 37.5, 37.6, routeMatchEvaluated = true,
                    ),
                    positionWithPoints(
                        index * 2, measurementNs + 200_000_000L, measurementNs, "sensor_gnss_anchored", utc - 1L,
                        null, 37.5, 37.5.takeIf { correctionHasMatch }, routeMatchEvaluated = true,
                    ),
                )
            }
            val trace = TraceV2Parser.parse(temporaryFile(validTrace(records)))
            val epochs = (1..31).map { index ->
                PpkEpoch(origin + index * 1_000L, truthPoint, 0.0, 1, 15, 0.1, 0.1, 5.0)
            }

            val result = PositionEvaluator.evaluate(trace, ParsedPpk("UTC", epochs, epochs.size))

            assertEquals(EvaluationStatus.EVALUATED, result.status)
            val matched = result.metrics.single { it.channel == PositionChannel.MATCHED }
            assertEquals(if (correctionHasMatch) 31 else 0, matched.availableCount)
            if (correctionHasMatch) assertEquals(0.0, requireNotNull(matched.maxErrorM), 0.001)
        }
    }

    @Test
    fun delayedPositionAfterAnchoredCheckpointIsAllowedWhenMeasurementClocksAgree() {
        val origin = 1_700_000_000_000L
        val records = listOf(
            checkpoint(1, 2_100_000_000L, 2_000_000_000L, "checkpoint_gnss_anchored", origin + 2_000L, 1),
            position(2, 2_200_000_000L, 1_000_000_000L, "gnss", origin + 1_000L),
        )

        val result = TraceV2Parser.parse(temporaryFile(validTrace(records)))

        assertEquals(1, result.samples.size)
        assertEquals(0.0, result.timeOffsetSpreadMs, 0.001)
    }

    @Test
    fun rejectsActualMeasurementUtcRegressionEvenWithinOffsetTolerance() {
        val origin = 1_700_000_000_000L
        assertReason(
            "TIME_ALIGNMENT_FAILED",
            validTrace(
                listOf(
                    position(1, 1_100_000_000L, 1_000_000_000L, "gnss", origin + 1_000L),
                    position(2, 1_200_000_000L, 1_050_000_000L, "gnss", origin + 990L),
                ),
            ),
        )
    }

    @Test
    fun rejectsAnchoredCheckpointWithInconsistentUtcClock() {
        val origin = 1_700_000_000_000L
        assertReason(
            "TIME_ALIGNMENT_FAILED",
            validTrace(
                listOf(
                    position(1, 1_100_000_000L, 1_000_000_000L, "gnss", origin + 1_000L),
                    checkpoint(2, 2_100_000_000L, 2_000_000_000L, "checkpoint_gnss_anchored", origin + 2_101L, 1),
                ),
            ),
        )
    }

    @Test
    fun measurementReorderingDoesNotRelaxReceiptSequenceOrElapsedValidation() {
        val origin = 1_700_000_000_000L
        val first = position(1, 2_100_000_000L, 2_000_000_000L, "sensor_gnss_anchored", origin + 2_000L)
        listOf(2_000_000_000L, 2_100_000_000L).forEach { receipt ->
            assertReason(
                "TIME_ALIGNMENT_FAILED",
                validTrace(listOf(first, position(2, receipt, 1_000_000_000L, "gnss", origin + 1_000L))),
            )
        }
        assertReason(
            "TRACE_SEQUENCE_INVALID",
            validTrace(listOf(first, position(3, 2_200_000_000L, 1_000_000_000L, "gnss", origin + 1_000L))),
        )
        assertReason(
            "TIME_ALIGNMENT_FAILED",
            validTrace(listOf(first, position(2, 2_200_000_000L, 2_300_000_000L, "gnss", origin + 2_300L))),
        )
    }

    @Test
    fun stationCenterUsesAnchoredFilteredMedianAndIgnoresRawMatchedAndMonotonicOnly() {
        val records = listOf(
            positionWithPoints(1, 2_000, 2_000, "gnss", 1_700_000_000_000, 35.0, 37.0, 39.0),
            positionWithPoints(2, 3_000, 3_000, "sensor_gnss_anchored", 1_700_000_000_001, 35.0, 37.1, 39.0),
            positionWithPoints(3, 4_000, 4_000, "gnss", 1_700_000_000_002, 35.0, 50.0, 39.0),
            positionWithPoints(4, 5_000, 5_000, "sensor_monotonic_only", null, 35.0, 80.0, 39.0),
        )

        val result = TraceV2Parser.parse(temporaryFile(validTrace(records)))

        assertEquals(37.1, result.center.latitudeDeg, 0.000001)
        assertEquals(127.1, result.center.longitudeDeg, 0.000001)
    }

    @Test
    fun stationCenterFallsBackToAnchoredRawMedian() {
        val records = listOf(
            positionWithPoints(1, 2_000, 2_000, "gnss", 1_700_000_000_000, 37.0, null, 39.0),
            positionWithPoints(2, 3_000, 3_000, "gnss", 1_700_000_000_001, 37.2, null, 39.0),
        )

        val result = TraceV2Parser.parse(temporaryFile(validTrace(records)))

        assertEquals(37.1, result.center.latitudeDeg, 0.000001)
        assertEquals(127.0, result.center.longitudeDeg, 0.000001)
    }

    @Test
    fun rejectsWrongRecordCountAndCheckpointContract() {
        val countMismatch = validTrace(listOf(position(1, 2_000, 2_000, "gnss", 1_700_000_000_000)), 2)
        assertReason("TRACE_RECORD_COUNT_MISMATCH", countMismatch)
        val invalidCheckpoint = validTrace(
            listOf(
                position(1, 2_000, 2_000, "gnss", 1_700_000_000_000),
                checkpoint(2, 3_000, 3_000, "checkpoint_monotonic_only", null, 2),
            ),
        )
        assertReason("TRACE_CHECKPOINT_INVALID", invalidCheckpoint)
    }

    @Test
    fun rejectsSourceUtcMismatchUnknownFieldAndTamperedHash() {
        assertReason(
            "TRACE_SOURCE_INVALID",
            validTrace(listOf(position(1, 2_000, 2_000, "sensor_monotonic_only", 1_700_000_000_000))),
        )
        assertReason(
            "TRACE_FIELD_INVALID",
            validTrace(
                listOf(
                    position(1, 2_000, 2_000, "gnss", 1_700_000_000_000)
                        .dropLast(1) + ",\"unexpected\":true}",
                ),
            ),
        )
        val valid = validTrace(listOf(position(1, 2_000, 2_000, "gnss", 1_700_000_000_000)))
        assertReason("TRACE_HASH_MISMATCH", valid.replace("37.5", "37.6"))
    }

    @Test
    fun rejectsV1WithoutUtc() {
        val file = temporaryFile("{\"record_type\":\"header\",\"schema_version\":\"walksafe.positioning_trace.v1\"}\n{}\n{}\n")
        val error = assertThrows(TraceFormatException::class.java) { TraceV2Parser.parse(file) }
        assertEquals("MISSING_ABSOLUTE_UTC", error.reasonCode)
    }

    private fun assertReason(expected: String, content: String) {
        val error = assertThrows(TraceFormatException::class.java) { TraceV2Parser.parse(temporaryFile(content)) }
        assertEquals(expected, error.reasonCode)
    }

    private fun validTrace(records: List<String>, recordCount: Int = records.size): String {
        val content = (listOf(HEADER) + records).joinToString("\n", postfix = "\n")
        val hash = MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
        return content + "{\"content_sha256\":\"$hash\",\"record_count\":$recordCount,\"record_type\":\"footer\",\"schema_version\":\"walksafe.positioning_trace.v2\"}\n"
    }

    private fun position(seq: Int, elapsed: Long, measurement: Long, source: String, utc: Long?): String =
        positionWithPoints(seq, elapsed, measurement, source, utc, 37.5, null, null)

    private fun positionWithPoints(
        seq: Int,
        elapsed: Long,
        measurement: Long,
        source: String,
        utc: Long?,
        rawLatitude: Double?,
        filteredLatitude: Double?,
        matchedLatitude: Double?,
        routeMatchEvaluated: Boolean? = null,
    ): String =
        "{\"elapsed_realtime_ns\":$elapsed,\"measurement_elapsed_realtime_ns\":$measurement," +
            (utc?.let { "\"measurement_utc_epoch_ms\":$it," } ?: "") +
            (routeMatchEvaluated?.let { "\"route_match_evaluated\":$it," } ?: "") +
            (rawLatitude?.let { "\"raw_position\":{\"latitude_deg\":$it,\"longitude_deg\":127.0}," } ?: "") +
            (filteredLatitude?.let { "\"filtered_position\":{\"latitude_deg\":$it,\"longitude_deg\":127.1}," } ?: "") +
            (matchedLatitude?.let { "\"matched_position\":{\"latitude_deg\":$it,\"longitude_deg\":127.2}," } ?: "") +
            "\"record_type\":\"position_sample\",\"seq\":$seq,\"source\":\"$source\"}"

    private fun checkpoint(seq: Int, elapsed: Long, measurement: Long, source: String, utc: Long?, ordinal: Int): String =
        "{\"checkpoint_id\":\"CP001\",\"elapsed_realtime_ns\":$elapsed,\"measurement_elapsed_realtime_ns\":$measurement," +
            (utc?.let { "\"measurement_utc_epoch_ms\":$it," } ?: "") +
            "\"ordinal\":$ordinal,\"record_type\":\"checkpoint_mark\",\"seq\":$seq,\"source\":\"$source\",\"stationary_duration_ms\":1500,\"stationary_state\":\"stationary\"}"

    private fun temporaryFile(value: String): File = File.createTempFile("trace", ".jsonl").apply {
        writeText(value)
        deleteOnExit()
    }

    private companion object {
        const val HEADER = "{\"android_api\":36,\"device_model\":\"SM-G981N\",\"direction\":\"forward\",\"environment\":\"outdoor\",\"mount\":\"PORTRAIT_BACK_OUT_TOP_UP_CHEST_CENTER\",\"record_type\":\"header\",\"route_id\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"scenario\":\"open_sky\",\"schema_version\":\"walksafe.positioning_trace.v2\",\"session_id\":\"11111111-1111-4111-8111-111111111111\",\"source_kind\":\"ANDROID_DEBUG_RECORDER\",\"synthetic_contract_only\":false,\"timebase\":\"ANDROID_ELAPSED_REALTIME_NANOS\"}"
    }
}
