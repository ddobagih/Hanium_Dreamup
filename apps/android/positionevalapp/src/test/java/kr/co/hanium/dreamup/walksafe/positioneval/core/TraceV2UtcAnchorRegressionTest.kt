package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TraceV2UtcAnchorRegressionTest {
    @Test
    fun measurementAnchorStableAllowsVariableCallbackLag() {
        val originUtcMs = 1_700_000_000_000L
        val trace = traceFile(
            Sample(elapsedMs = 1_000L, measurementElapsedMs = 900L, utcMs = originUtcMs + 900L),
            Sample(elapsedMs = 2_000L, measurementElapsedMs = 1_500L, utcMs = originUtcMs + 1_500L),
        )
        try {
            val parsed = TraceV2Parser.parse(trace)
            assertEquals(0.0, parsed.timeOffsetSpreadMs, 0.001)
        } finally {
            trace.delete()
        }
    }

    @Test
    fun callbackAnchorStableRejectsUnstableMeasurementUtcAnchor() {
        val originUtcMs = 1_700_000_000_000L
        val trace = traceFile(
            Sample(elapsedMs = 1_000L, measurementElapsedMs = 900L, utcMs = originUtcMs + 1_000L),
            Sample(elapsedMs = 2_000L, measurementElapsedMs = 1_500L, utcMs = originUtcMs + 2_000L),
        )
        try {
            try {
                TraceV2Parser.parse(trace)
                fail("400ms measurement-to-UTC offset spread must be rejected")
            } catch (error: TraceFormatException) {
                assertEquals("TIME_ALIGNMENT_FAILED", error.reasonCode)
            }
        } finally {
            trace.delete()
        }
    }

    private fun traceFile(vararg samples: Sample): File {
        val content = mutableListOf(
            JSONObject()
                .put("schema_version", TraceV2Parser.SCHEMA)
                .put("record_type", "header")
                .put("session_id", UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .put("route_id", UUID.fromString("00000000-0000-0000-0000-000000000002").toString())
                .put("scenario", "outdoor")
                .put("environment", "open_sky")
                .put("direction", "forward")
                .put("device_model", "Pixel-4")
                .put("android_api", 35)
                .put("mount", "PORTRAIT_BACK_OUT_TOP_UP_CHEST_CENTER")
                .put("source_kind", "ANDROID_DEBUG_RECORDER")
                .put("synthetic_contract_only", false)
                .put("timebase", "ANDROID_ELAPSED_REALTIME_NANOS")
                .toString(),
        )
        samples.forEachIndexed { index, sample ->
            content += JSONObject()
                .put("record_type", "position_sample")
                .put("seq", index + 1L)
                .put("elapsed_realtime_ns", sample.elapsedMs * 1_000_000L)
                .put("measurement_elapsed_realtime_ns", sample.measurementElapsedMs * 1_000_000L)
                .put("source", "gnss")
                .put("measurement_utc_epoch_ms", sample.utcMs)
                .put(
                    "filtered_position",
                    JSONObject().put("latitude_deg", 37.5665).put("longitude_deg", 126.9780),
                )
                .toString()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        content.forEach { line ->
            digest.update(line.toByteArray(StandardCharsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        content += JSONObject()
            .put("record_type", "footer")
            .put("schema_version", TraceV2Parser.SCHEMA)
            .put("record_count", samples.size)
            .put("content_sha256", hash)
            .toString()
        return File.createTempFile("trace-v2-utc-anchor-", ".jsonl").apply {
            writeText(content.joinToString(separator = "\n", postfix = "\n"), StandardCharsets.UTF_8)
        }
    }

    private data class Sample(
        val elapsedMs: Long,
        val measurementElapsedMs: Long,
        val utcMs: Long,
    )
}
