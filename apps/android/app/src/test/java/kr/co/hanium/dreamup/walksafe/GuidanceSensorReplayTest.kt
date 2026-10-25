package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.session.GuidanceRecoveryTiming
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Deterministic admitted-walk replay; neither home entry nor physical sensor/acoustic proof. */
@RunWith(Parameterized::class)
internal class GuidanceSensorReplayTest(private val case: GuidanceSensorReplayCase) {
    @Test fun replayActualGuidanceOwners() {
        var harness: GuidanceSensorReplayHarness? = null
        var failure: Throwable? = null
        try {
            harness = GuidanceSensorReplayHarness(case)
            try {
                harness.replay()
            } finally {
                harness.close()
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            val report = harness?.report(failure) ?: JSONObject()
                .put("case_id", case.id).put("scope", case.scope)
                .put("status", if (failure?.message?.contains("SDK_INPUT_ADAPTER_GAP:") == true) "INPUT_ADAPTER_GAP" else "FIXTURE_ERROR").put("failure", failure?.toString())
            replaySetting("OUTPUT_DIR", "outputDir")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                File(directory, "${case.id}.json").writeText(report.toString(2) + "\n")
            }
            println("SENSOR_REPLAY ${case.id} ${report.optString("status")}")
            GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val file = replaySetting("CASE_FILE", "caseFile")
            val content = if (file != null) File(file).readText() else {
                requireNotNull(requireNotNull(GuidanceSensorReplayTest::class.java.classLoader)
                    .getResourceAsStream("navigation/guidance_sensor_replay_v2_20261010.json")) {
                    "Missing navigation/guidance_sensor_replay_v2_20261010.json"
                }.bufferedReader().use { it.readText() }
            }
            val document = JSONObject(content)
            require(document.getString("schema") == "walksafe.guidance_sensor_replay.v2")
            val policy = document.getJSONObject("policy")
            require(policy.getLong("gps_period_ms") == GuidanceRecoveryTiming.GPS_RECHECK_PERIOD_MS &&
                policy.getInt("gps_confirmations") == GuidanceRecoveryTiming.GPS_RECHECK_PERIODS)
            require(policy.getLong("direction_grace_ms") * policy.getInt("direction_confirmations") == GuidanceRecoveryTiming.DIRECTION_GRACE_MS)
            require(policy.getLong("camera_grace_ms") == GuidanceRecoveryTiming.CAMERA_GRACE_MS)
            require(policy.getLong("raw_location_ttl_ms") == 10_000L)
            require(policy.getLong("direction_observation_ms") == 300L)
            val selector = replaySetting("CASE_FILTER", "caseFilter")?.let(::Regex)
            val input = document.getJSONArray("cases")
            val parsed = (0 until input.length()).map { GuidanceSensorReplayCase(input.getJSONObject(it)) }
            require(parsed.map { it.id }.distinct().size == parsed.size) { "Duplicate replay case id" }
            val selected = parsed.filter { selector == null || selector.matches(it.id) }
            require(selected.isNotEmpty()) { "No replay cases match selector" }
            return selected.map { arrayOf<Any>(it) }
        }

        internal fun replaySetting(environmentSuffix: String, propertySuffix: String): String? =
            System.getenv("WALKSAFE_SENSOR_REPLAY_$environmentSuffix")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("walksafe.sensorReplay.$propertySuffix")?.takeIf { it.isNotBlank() }
    }
}
