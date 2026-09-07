package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.File
import java.security.MessageDigest
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
    ): String =
        "{\"elapsed_realtime_ns\":$elapsed,\"measurement_elapsed_realtime_ns\":$measurement," +
            (utc?.let { "\"measurement_utc_epoch_ms\":$it," } ?: "") +
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
