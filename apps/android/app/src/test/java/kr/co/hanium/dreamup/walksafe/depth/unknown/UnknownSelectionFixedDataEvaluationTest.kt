package kr.co.hanium.dreamup.walksafe.depth.unknown

import java.io.File
import java.util.Base64
import kr.co.hanium.dreamup.walksafe.depth.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paired replay adapter: invokes the compiled production policy, never a second implementation. */
class UnknownSelectionFixedDataEvaluationTest {
    @Test fun evaluateFixedInputsUsingCompiledProductionPolicy() {
        val inputPath = System.getProperty("walksafe.unknownSelection.input")
        val inputText = if (inputPath != null) File(inputPath).readText() else
            checkNotNull(javaClass.getResourceAsStream("/unknown-selection/fixtures.json"))
                .bufferedReader().use { it.readText() }
        val input = JSONObject(inputText)
        val defaults = input.optJSONObject("defaults") ?: JSONObject()
        val rows = JSONArray()
        val policies = listOf("baseline" to BaselineUnknownWalkingObstaclePolicySnapshot, "current" to UnknownWalkingObstaclePolicy)
        val repetitions = System.getProperty("walksafe.unknownSelection.repetitions", "25").toInt()
        require(repetitions > 0)
        val inputs = input.getJSONArray("cases")
        val expectationMode = System.getProperty("walksafe.unknownSelection.expectations", "v6-range-contract")
        require(expectationMode in listOf("historical", "v6-range-contract", "report-only"))
        val historicalConflicts = JSONArray()
        for (index in 0 until inputs.length()) {
            val values = JSONObject(defaults.toString())
            val item = inputs.getJSONObject(index)
            item.keys().forEach { key -> values.put(key, item.get(key)) }
            val mask = mask(values)
            val tracked = output(values, mask)
            val row = JSONObject().put("id", values.getString("id")).put("maskArea", mask.area)
            for ((name, policy) in if (index % 2 == 0) policies else policies.reversed()) {
                val method = policy.javaClass.methods.single { it.name == "select" }
                val arguments = mutableListOf<Any?>(mask, tracked, values.optInt("quarterTurns", 0), null)
                if (method.parameterCount >= 5) arguments.add(extent(values, method.parameterTypes[4]))
                // Frozen historical inputs contain no gravity, world points, floor or path evidence.
                // Never manufacture a spatial label from their old image-space keep/suppress labels.
                if (method.parameterCount == 6) arguments.add(null)
                assertEquals("Unsupported policy signature", method.parameterCount, arguments.size)
                val args = arguments.toTypedArray()
                repeat(5) { method.invoke(policy, *args) }
                val elapsed = LongArray(repetitions)
                var result: UnknownObstacleSelection? = null
                repeat(repetitions) { iteration ->
                    val started = System.nanoTime()
                    result = method.invoke(policy, *args) as UnknownObstacleSelection
                    elapsed[iteration] = System.nanoTime() - started
                }
                val selected = checkNotNull(result)
                val sorted = elapsed.sorted()
                row.put(name, JSONObject().put("show", selected.show).put("warningCandidate", selected.warningCandidate)
                    .put("reason", selected.reason).put("selectionP50Ns", sorted[sorted.size / 2])
                    .put("selectionP95Ns", sorted[kotlin.math.ceil(sorted.size * .95).toInt() - 1]))
            }
            rows.put(row)
            val selected = row.getJSONObject("current")
            val expectedShow = when {
                item.has("expectedShow") -> item.getBoolean("expectedShow")
                item.optString("label") in listOf("keep_collision_relevant", "retain_uncertain") -> true
                item.optString("label") == "suppress_verified_nuisance" -> false
                else -> null
            }
            if (expectedShow != null && selected.getBoolean("show") != expectedShow) {
                historicalConflicts.put(JSONObject().put("id", item.getString("id"))
                    .put("field", "show").put("historicalExpected", expectedShow)
                    .put("actual", selected.getBoolean("show")).put("reason", selected.getString("reason")))
            }
        }
        assertEquals(inputs.length(), rows.length())
        assertTrue(rows.length() > 0)
        val report = JSONObject().put("schema", "unknown-selection-replay-v1")
            .put("adapter", javaClass.name).put("baseline", "1509a19")
            .put("inputPath", inputPath ?: "classpath:unknown-selection/fixtures.json")
            .put("inputSha256", java.security.MessageDigest.getInstance("SHA-256").digest(inputText.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) })
            .put("expectationMode", expectationMode).put("historicalExpectationConflicts", historicalConflicts)
            .put("stageScope", "Raw masks and selection visibility/warning-candidate decisions only; message policy, alert queue and speech are not exercised.")
            .put("timingScope", "Host JVM production select plus reflection only; excludes model, depth, camera, decoding and device runtime.")
            .put("warmupCallsPerCase", 5).put("measuredCallsPerCase", repetitions).put("rows", rows)
        System.getProperty("walksafe.unknownSelection.output")?.let { path ->
            File(path).apply { parentFile?.mkdirs() }.writeText(report.toString(2) + "\n")
        }
        for (index in 0 until inputs.length()) {
            val case = inputs.getJSONObject(index)
            val selected = rows.getJSONObject(index).getJSONObject("current")
            if (expectationMode == "report-only") continue
            if (expectationMode == "v6-range-contract") {
                val values = JSONObject(defaults.toString())
                case.keys().forEach { key -> values.put(key, case.get(key)) }
                val distance = if (values.isNull("distanceM")) null else values.getDouble("distanceM")
                val hasInRangeMetric = DepthSource.valueOf(values.getString("source")).metric &&
                    distance != null && distance.isFinite() && distance > 0.0 && distance <= 3.0
                if (!hasInRangeMetric) {
                    assertEquals("${case.getString("id")} lacks current measured range within 3 m", false, selected.getBoolean("show"))
                    assertEquals(case.getString("id"), false, selected.getBoolean("warningCandidate"))
                }
                continue
            }
            if (case.optString("label") in listOf("keep_collision_relevant", "retain_uncertain")) {
                assertTrue("${case.getString("id")} must remain observable", selected.getBoolean("show"))
            }
            if (case.has("expectedWarningCandidate")) {
                assertEquals(case.getString("id"), case.getBoolean("expectedWarningCandidate"),
                    selected.getBoolean("warningCandidate"))
            }
            if (case.has("expectedShow")) {
                assertEquals(case.getString("id"), case.getBoolean("expectedShow"), selected.getBoolean("show"))
            }
        }
        println("UNKNOWN_SELECTION_REPLAY cases=${rows.length()} paired=true")
    }

    private fun mask(values: JSONObject): BinaryImageMask {
        val width = values.getInt("width"); val height = values.getInt("height")
        values.optJSONObject("packedRoi")?.let { packed ->
            return BinaryImageMask.fromPackedRoi(width, height, packed.getInt("left"), packed.getInt("top"),
                packed.getInt("width"), packed.getInt("height"), Base64.getDecoder().decode(packed.getString("base64")))
        }
        val rectangles = values.getJSONArray("rectangles")
        val left = (0 until rectangles.length()).minOf { rectangles.getJSONArray(it).getInt(0) }
        val top = (0 until rectangles.length()).minOf { rectangles.getJSONArray(it).getInt(1) }
        val right = (0 until rectangles.length()).maxOf { rectangles.getJSONArray(it).getInt(2) }
        val bottom = (0 until rectangles.length()).maxOf { rectangles.getJSONArray(it).getInt(3) }
        val roiWidth = right - left; val roiHeight = bottom - top
        val bytes = ByteArray((roiWidth * roiHeight + 7) / 8)
        for (i in 0 until rectangles.length()) {
            val rectangle = rectangles.getJSONArray(i)
            for (y in rectangle.getInt(1) until rectangle.getInt(3))
                for (x in rectangle.getInt(0) until rectangle.getInt(2)) {
                    val bit = (y - top) * roiWidth + x - left
                    bytes[bit / 8] = (bytes[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
                }
        }
        return BinaryImageMask.fromPackedRoi(width, height, left, top, roiWidth, roiHeight, bytes)
    }

    private fun extent(values: JSONObject, type: Class<*>): Any? {
        val dimensions = values.optJSONArray("extentM") ?: return null
        val constructor = type.constructors.single { it.parameterCount == 6 }
        val width = dimensions.getDouble(0).toFloat(); val height = dimensions.getDouble(1).toFloat()
        val conservative = values.optJSONArray("conservativeExtentM") ?: dimensions
        return constructor.newInstance(width, height, conservative.getDouble(0).toFloat(), conservative.getDouble(1).toFloat(),
            values.optBoolean("canRejectSmall", false) && values.optBoolean("fullCoverage", false), "synthetic_contract")
    }

    private fun output(values: JSONObject, mask: BinaryImageMask): TrackedObjectDepth {
        fun floatOrNull(key: String) = if (values.isNull(key)) null else values.getDouble(key).toFloat()
        val distance = floatOrNull("distanceM")
        val box = RectNorm(mask.left.toFloat() / mask.originalWidth, mask.top.toFloat() / mask.originalHeight,
            mask.width.toFloat() / mask.originalWidth, mask.height.toFloat() / mask.originalHeight)
        val confidence = values.optDouble("confidence", .95).toFloat()
        return TrackedObjectDepth(
            frameId = 6L, timestampMs = 1200L, trackId = values.getString("id"), className = UNNAMED_OBSTACLE_CLASS,
            detectionConfidence = confidence, bboxNorm = box, polygonNorm = emptyList(),
            maskAreaNorm = mask.area.toFloat() / (mask.originalWidth.toLong() * mask.originalHeight),
            centerNorm = box.center, bottomContactNorm = null, source = DepthSource.valueOf(values.getString("source")),
            zDistanceM = distance, rayDistanceM = distance, groundDistanceM = null, riskDistanceM = distance,
            validSampleCount = values.optInt("validSampleCount", 100), validSampleRatio = values.optDouble("validSampleRatio", .95).toFloat(),
            depthMedianM = distance, depthP20M = distance, depthIqrM = floatOrNull("depthIqrM"),
            trend = Trend.valueOf(values.optString("trend", "UNKNOWN")), approachScore = 0f, approachSpeedMps = null,
            timeToCollisionMs = if (values.isNull("timeToCollisionMs")) null else values.getLong("timeToCollisionMs"),
            confidence = DepthConfidenceBreakdown(confidence, confidence, confidence, confidence, confidence, confidence, confidence, confidence),
            userFacing = UserFacingDepth(null, MessageLevel.NONE, null), trackAgeFrames = values.optInt("trackAgeFrames", 6),
            trackStableMs = values.optLong("trackStableMs", 1200L))
    }
}
