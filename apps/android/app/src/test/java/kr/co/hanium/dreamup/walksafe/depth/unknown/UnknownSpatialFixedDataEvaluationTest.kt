package kr.co.hanium.dreamup.walksafe.depth.unknown

import java.io.File
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

/** Fixed synthetic world samples go through the real spatial, selection, message and queue policies. */
class UnknownSpatialFixedDataEvaluationTest {
    @Test fun evaluatePreregisteredPhysicalScenariosThroughProductionPolicies() {
        val inputPath = System.getProperty("walksafe.unknownSpatial.input")
        val text = if (inputPath == null) checkNotNull(javaClass.getResourceAsStream("/unknown-selection/spatial-v6.json"))
            .bufferedReader().use { it.readText() } else File(inputPath).readText()
        val fixture = JSONObject(text)
        val cases = fixture.getJSONArray("cases")
        val rows = JSONArray()
        val baselineCases = JSONArray()
        for (index in 0 until cases.length()) {
            val item = cases.getJSONObject(index)
            val values = JSONObject(fixture.getJSONObject("defaults").toString())
            item.keys().forEach { key -> values.put(key, item.get(key)) }
            val position = vector(values.getJSONArray("cameraPosition"))
            val forward = vector(values.getJSONArray("cameraForward"))
            val up = values.optJSONArray("gravityUp")?.let(::vector)
            val now = values.getLong("timestampMs")
            val pose = CameraPoseEvidence(1L, now, position.x, position.y, position.z,
                forward.x, forward.y, forward.z,
                CameraImageProjection(640, 640, 320f, 320f, 320f, 320f, 1f, 0f, 0f, 0f, 1f, 0f),
                up, listOf(CapturedHorizontalPlane(1L, now, listOf(
                    Vec3(-1.6f, 0f, -.5f), Vec3(1.6f, 0f, -.5f), Vec3(1.6f, 0f, 3.5f), Vec3(-1.6f, 0f, 3.5f)))))
            val bounds = values.getJSONArray("maskBoundsM")
            val points = grid(vector(bounds.getJSONArray(0)), vector(bounds.getJSONArray(1)),
                values.getJSONArray("maskGrid"), values.optString("surfaceAxes", "xy"))
            val floor = values.getJSONObject("sceneFloor")
            val scenePoints = grid(Vec3(floor.getJSONArray("x").getDouble(0).toFloat(), 0f,
                floor.getJSONArray("z").getDouble(0).toFloat()),
                Vec3(floor.getJSONArray("x").getDouble(1).toFloat(), 0f,
                    floor.getJSONArray("z").getDouble(1).toFloat()), floor.getJSONArray("grid"), "xz")
            val samples = points.map { UnknownSpatialSample(it, .95f) }
            val spatial = UnknownSpatialRelevance.evaluate(samples,
                UnknownSpatialContext(pose, up, now, values.getBoolean("independentDepth"), values.getBoolean("fullyCovered")),
                scenePoints.map { UnknownSpatialSample(it, .95f) })
            val projected = points.map { point ->
                val delta = point - position
                Point2(320f + 320f * delta.x / delta.z, 320f - 320f * delta.y / delta.z)
            }
            val left = floor(projected.minOf { it.x }).toInt().coerceIn(0, 639)
            val top = floor(projected.minOf { it.y }).toInt().coerceIn(0, 639)
            val right = ceil(projected.maxOf { it.x }).toInt().coerceIn(left + 1, 640)
            val bottom = ceil(projected.maxOf { it.y }).toInt().coerceIn(top + 1, 640)
            val bytes = ByteArray(((right - left) * (bottom - top) + 7) / 8) { 0xff.toByte() }
            val mask = BinaryImageMask.fromPackedRoi(640, 640, left, top, right - left, bottom - top, bytes)
            val range = points.map { (it - position).norm() }.sorted().let { it[it.size / 5] }
            val axial = points.map { (it - position).dot(forward) }.sorted().let { it[it.size / 5] }
            val independent = values.getBoolean("independentDepth")
            val tracked = output(values, mask, if (independent) range else null, if (independent) axial else null)
            val dimensions = values.getJSONArray("extentM")
            val extent = ObservedMaskExtent(dimensions.getDouble(0).toFloat(), dimensions.getDouble(1).toFloat(),
                dimensions.getDouble(0).toFloat(), dimensions.getDouble(1).toFloat(),
                values.getBoolean("canRejectSmall") && values.getBoolean("fullyCovered"), "stipulated_synthetic_extent")
            val selection = UnknownWalkingObstaclePolicy.select(mask, tracked, 0, null, extent, spatial)
            val selectedOutput = tracked.copy(walkingObstacleCandidate = selection.warningCandidate)
            val epoch = WalkRuntimeEpoch("synthetic-spatial-evaluation", 0L)
            val admitted = UnknownObjectFeedbackPolicy().admit(listOf(selectedOutput), now * 1_000_000L, now,
                now * 1_000_000L, now + 10L, epoch, epoch, now + 10L)
            val feedbackCandidates = admitted?.outputs.orEmpty().mapNotNull { it.toFeedbackCandidate() }
            val action = WalkSafeFeedbackPolicy().evaluateCandidates(feedbackCandidates, true, now + 10L)
            rows.put(JSONObject().put("id", item.getString("id")).put("label", item.getString("label"))
                .put("rawCandidateCount", 1).put("worldSampleCount", samples.size).put("maskArea", mask.area)
                .put("show", selection.show).put("warningCandidate", selection.warningCandidate).put("selectionReason", selection.reason)
                .put("spatialDisposition", spatial.disposition.name).put("spatialReason", spatial.reason)
                .put("nearestReliableRangeM", spatial.nearestReliableRangeM ?: JSONObject.NULL)
                .put("feedbackAdmittedCount", admitted?.outputs?.size ?: 0).put("feedbackCandidateCount", feedbackCandidates.size)
                .put("queueActionCount", if (action == null) 0 else 1).put("queueMessage", action?.message ?: JSONObject.NULL)
                .put("expectedShow", item.getBoolean("expectedShow")).put("expectedWarningCandidate", item.getBoolean("expectedWarningCandidate")))
            baselineCases.put(JSONObject().put("id", item.getString("id")).put("label", item.getString("label"))
                .put("width", 640).put("height", 640).put("rectangles", JSONArray().put(JSONArray(listOf(left, top, right, bottom))))
                .put("source", tracked.source.name).put("distanceM", tracked.rayDistanceM ?: JSONObject.NULL)
                .put("depthIqrM", .02).put("trend", tracked.trend.name).put("timeToCollisionMs", tracked.timeToCollisionMs ?: JSONObject.NULL)
                .put("extentM", dimensions).put("canRejectSmall", extent.canRejectSmall).put("fullCoverage", values.getBoolean("fullyCovered")))
        }
        val report = JSONObject().put("schema", "unknown-spatial-production-replay-v6").put("rows", rows)
            .put("inputSha256", MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) })
            .put("scope", "Synthetic world samples and stipulated range/trend; real production spatial evaluator, selector, UNKNOWN feedback admission, message policy and queue reservation. No model inference, temporal motion estimation, delivery claim, TTS or physical speech.")
        System.getProperty("walksafe.unknownSpatial.output")?.let { File(it).writeText(report.toString(2) + "\n") }
        System.getProperty("walksafe.unknownSpatial.baselineInput")?.let {
            File(it).writeText(JSONObject().put("schema", "spatial-v6-projected-baseline-input").put("cases", baselineCases).toString(2) + "\n")
        }
        assertEquals(16, rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (row.getString("label") == "hazard") {
                assertTrue("${row.getString("id")} visibility: ${row.getString("selectionReason")}", row.getBoolean("show"))
                assertTrue("${row.getString("id")} warning candidate", row.getBoolean("warningCandidate"))
            }
            if (row.getString("id") in listOf("broad_connected_floor", "left_side_wall", "right_side_wall",
                    "frontal_just_outside_3m", "approaching_outside_3m", "reused_depth_frontal_obstacle")) {
                assertEquals("${row.getString("id")}: ${row.getString("selectionReason")}", false, row.getBoolean("show"))
                assertEquals(row.getString("id"), false, row.getBoolean("warningCandidate"))
                assertEquals(row.getString("id"), 0, row.getInt("queueActionCount"))
            }
        }
        println("UNKNOWN_SPATIAL_REPLAY cases=${rows.length()} realProductionPolicies=true physicalSpeechMeasured=false")
    }

    private fun vector(value: JSONArray) = Vec3(value.getDouble(0).toFloat(), value.getDouble(1).toFloat(), value.getDouble(2).toFloat())

    private fun grid(first: Vec3, last: Vec3, size: JSONArray, axes: String): List<Vec3> = buildList {
        val a = size.getInt(0); val b = size.getInt(1)
        for (i in 0 until a) for (j in 0 until b) {
            val u = i.toFloat() / (a - 1); val v = j.toFloat() / (b - 1)
            add(when (axes) {
                "xz" -> Vec3(first.x + (last.x - first.x) * u, first.y, first.z + (last.z - first.z) * v)
                "yz" -> Vec3(first.x, first.y + (last.y - first.y) * u, first.z + (last.z - first.z) * v)
                else -> Vec3(first.x + (last.x - first.x) * u, first.y + (last.y - first.y) * v, first.z)
            })
        }
    }

    private fun output(values: JSONObject, mask: BinaryImageMask, range: Float?, axial: Float?): TrackedObjectDepth {
        val box = RectNorm(mask.left / 640f, mask.top / 640f, mask.width / 640f, mask.height / 640f)
        val now = values.getLong("timestampMs")
        return TrackedObjectDepth(now * 1_000_000L, now, values.getString("id"), UNNAMED_OBSTACLE_CLASS, .95f,
            box, emptyList(), mask.area / (640f * 640f), box.center, null,
            if (range == null) DepthSource.UNKNOWN else DepthSource.ARCORE_RAW_DEPTH, axial, range, null, range,
            if (range == null) 0 else 81, if (range == null) 0f else .95f, range, range, .02f,
            Trend.valueOf(values.getString("trend")), 0f, null,
            if (values.has("timeToCollisionMs")) values.getLong("timeToCollisionMs") else null,
            DepthConfidenceBreakdown(.95f, .95f, .95f, .95f, .95f, .95f, .95f, .95f),
            UserFacingDepth(null, MessageLevel.NONE, null), values.getInt("trackAgeFrames"), values.getLong("trackStableMs"))
    }
}
