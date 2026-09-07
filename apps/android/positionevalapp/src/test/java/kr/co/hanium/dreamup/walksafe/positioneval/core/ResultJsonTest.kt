package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ResultJsonTest {
    @Test
    fun outputIsDeterministicAndSeparatesFixedEpochsFromInterpolatedGrid() {
        val metric = ChannelMetrics(PositionChannel.MATCHED, 30, 10, null, null, null, null, 8, 8.0 / 30.0, 10.0 / 30.0)
        val outcome = EvaluationOutcome(EvaluationStatus.EVALUATED, emptyList(), 31, 30, listOf(metric))
        val ppk = ParsedPpk("UTC", listOf(epoch(1_000), epoch(2_000)), 2)
        val first = ResultJson.evaluation(outcome, artifact("trace"), artifact("gnss"), STATION, emptyList(), ppk)
        val second = ResultJson.evaluation(outcome, artifact("trace"), artifact("gnss"), STATION, emptyList(), ppk)

        assertEquals(first, second)
        assertFalse(first.contains("generated_at_utc"))
        val root = JSONObject(first)
        assertEquals(2, root.getJSONObject("truth").getInt("fixed_epoch_count_q1"))
        assertEquals(30, root.getJSONObject("truth").getInt("interpolated_fixed_grid_count"))
        val channel = root.getJSONArray("channels").getJSONObject(0)
        assertEquals("insufficient_samples", channel.getString("status"))
        assertEquals("CHANNEL_SAMPLE_COUNT_TOO_LOW", channel.getJSONArray("reason_codes").getString(0))
        assertEquals(PositionEvaluator.POLICY_VERSION, root.getJSONObject("evaluation_policy").getString("policy_version"))
        assertEquals(Demo5JniPpkEngine.COMMIT, root.getJSONObject("components").getJSONObject("ppk_engine").getString("source_commit"))
        assertEquals("NOT_INDEPENDENT", root.getJSONObject("reference_provenance").getString("independence"))
    }

    @Test
    fun processingFailureIsDeterministicWithoutWallClockInjection() {
        val first = ResultJson.processingFailure("ENGINE_NOT_AVAILABLE", "정답 부족", null, null, null)
        val second = ResultJson.processingFailure("ENGINE_NOT_AVAILABLE", "정답 부족", null, null, null)
        assertEquals(first, second)
    }

    @Test
    fun preEvaluationReferenceShortageUsesTruthInsufficientWithoutComponentDetail() {
        val value = ResultJson.processingFailure(
            "NGII_REFERENCE_INCOMPLETE",
            "정답 부족",
            null,
            null,
            null,
            truthInsufficient = true,
        )

        val root = JSONObject(value)
        assertEquals("truth_insufficient", root.getString("status"))
        val components = root.getJSONObject("components")
        assertFalse(components.getJSONObject("gnsslogger_txt_to_rinex").has("detail"))
        assertFalse(components.getJSONObject("ppk_engine").has("detail"))
    }

    private fun artifact(name: String) = ImportedArtifact(name, File(name), 1, "a".repeat(64))
    private fun epoch(utc: Long) = PpkEpoch(utc, GeoPoint(37.0, 127.0), 10.0, 1, 15, 0.1, 0.1, 5.0)

    private companion object {
        val STATION = BaseStation("SEOU", "서울", GeoPoint(37.0, 127.0), true, true)
    }
}
