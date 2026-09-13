package kr.co.hanium.dreamup.walksafe.positioneval

import java.io.File
import kr.co.hanium.dreamup.walksafe.positioneval.core.EcefPoint
import kr.co.hanium.dreamup.walksafe.positioneval.core.ImportedArtifact
import kr.co.hanium.dreamup.walksafe.positioneval.core.NgiiException
import kr.co.hanium.dreamup.walksafe.positioneval.core.RinexHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AnalysisFailurePolicyTest {
    @Test
    fun `reference gaps are truth insufficient but engine setup errors are not`() {
        assertTrue(AnalysisFailurePolicy.isTruthInsufficient("BASE_DATA_UNAVAILABLE"))
        assertTrue(AnalysisFailurePolicy.isTruthInsufficient("INSUFFICIENT_VALID_MEASUREMENTS"))
        assertTrue(AnalysisFailurePolicy.isTruthInsufficient("BASE_STATION_TOO_FAR"))
        assertFalse(AnalysisFailurePolicy.isTruthInsufficient("ENGINE_NOT_AVAILABLE"))
    }

    @Test
    fun `export artifact name is redacted`() {
        val original = ImportedArtifact("private-route-name.jsonl", File("/private/path"), 12, "abc")
        val redacted = AnalysisFailurePolicy.redacted(original)
        assertEquals("omitted", redacted.displayName)
        assertEquals(original.sha256, redacted.sha256)
        assertEquals(original.byteCount, redacted.byteCount)
    }

    @Test
    fun `unexpected reason code and message are sanitized`() {
        val error = NgiiException("BAD/path", "/data/user/0/private")
        val code = AnalysisFailurePolicy.reasonCode(error)
        assertEquals("ANALYSIS_FAILED", code)
        assertFalse(AnalysisFailurePolicy.publicMessage(code).contains("/data/"))
    }

    @Test
    fun `pipeline component reason must be in fixed allowlist`() {
        assertEquals("ENGINE_NOT_AVAILABLE", AnalysisFailurePolicy.sanitize("ENGINE_NOT_AVAILABLE"))
        assertEquals("ANALYSIS_FAILED", AnalysisFailurePolicy.sanitize("SECRET_INTERNAL_DIAGNOSTIC"))
    }

    @Test
    fun `starting an import immediately invalidates the old result`() {
        val current = AnalysisSnapshot(hasResult = true, inputsReady = true)

        val importing = AnalysisStatePolicy.begin(current, "importing", clearResult = true)

        assertTrue(importing.busy)
        assertFalse(importing.hasResult)
    }

    @Test
    fun `manual base readiness allows keyless analysis while automatic mode still requires key`() {
        val state = AnalysisSnapshot(inputsReady = true, manualBaseReady = true)
        assertTrue(state.canAnalyzeManual)
        assertFalse(state.canAnalyze)
        assertFalse(state.copy(busy = true).canAnalyzeManual)
        assertFalse(state.copy(manualBaseReady = false).canAnalyzeManual)
        assertTrue(state.copy(keyStored = true).canAnalyze)
    }

    @Test
    fun `base observation set rejects mixed station and long outage but accepts short gaps`() {
        val continuous = listOf(
            header("SUWN", 0L, 1_000L, 1_000L),
            header("SUWN", 61_000L, 180_000L, 60_000L),
        )
        BaseObservationSetPolicy.validate(continuous, 0L, 180_000L)

        val mixed = continuous.mapIndexed { index, value ->
            if (index == 0) value else value.copy(markerName = "SEOU")
        }
        val mixedError = assertThrows(NgiiException::class.java) {
            BaseObservationSetPolicy.validate(mixed, 0L, 180_000L)
        }
        assertEquals("BASE_DATA_INVALID", mixedError.reasonCode)

        val outage = continuous.mapIndexed { index, value ->
            if (index == 0) value else value.copy(firstObservationUtcMs = 122_000L)
        }
        assertThrows(NgiiException::class.java) {
            BaseObservationSetPolicy.validate(outage, 0L, 180_000L)
        }
    }

    private fun header(marker: String, start: Long, end: Long, maxGap: Long) = RinexHeader(
        version = 3.04,
        type = 'O',
        firstObservationUtcMs = start,
        lastObservationUtcMs = end,
        markerName = marker,
        approximatePosition = EcefPoint(3_000_000.0, 4_000_000.0, 4_000_000.0),
        maxObservationGapMs = maxGap,
    )
}
