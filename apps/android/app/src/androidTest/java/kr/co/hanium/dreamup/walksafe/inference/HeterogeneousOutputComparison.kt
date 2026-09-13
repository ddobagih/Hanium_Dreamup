package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Test-only comparison; never retains tensors or emits scene geometry or class labels. */
internal object HeterogeneousOutputComparison {
    /** Maximum bipartite matching avoids treating reordered/nearby predictions as a mismatch. */
    fun compareDetections(reference: List<DetectionCandidate>, candidate: List<DetectionCandidate>): JSONObject {
        val assigned = IntArray(candidate.size) { -1 }
        fun boxDelta(a: DetectionCandidate, b: DetectionCandidate): Float = maxOf(
            abs(a.bboxNorm.x - b.bboxNorm.x), abs(a.bboxNorm.y - b.bboxNorm.y),
            abs(a.bboxNorm.width - b.bboxNorm.width), abs(a.bboxNorm.height - b.bboxNorm.height))
        fun match(referenceIndex: Int, visited: BooleanArray): Boolean {
            for (candidateIndex in candidate.indices) {
                if (visited[candidateIndex]) continue
                val a = reference[referenceIndex]
                val b = candidate[candidateIndex]
                val finite = listOf(a.bboxNorm.x, a.bboxNorm.y, a.bboxNorm.width, a.bboxNorm.height,
                    b.bboxNorm.x, b.bboxNorm.y, b.bboxNorm.width, b.bboxNorm.height,
                    a.detectionConfidence, b.detectionConfidence).all { it.isFinite() }
                if (!finite || a.className != b.className || boxDelta(a, b) > BOX_TOLERANCE ||
                    abs(a.detectionConfidence - b.detectionConfidence) > SCORE_TOLERANCE) continue
                visited[candidateIndex] = true
                if (assigned[candidateIndex] < 0 || match(assigned[candidateIndex], visited)) {
                    assigned[candidateIndex] = referenceIndex
                    return true
                }
            }
            return false
        }
        val matched = reference.indices.count { match(it, BooleanArray(candidate.size)) }
        val pairs = assigned.indices.filter { assigned[it] >= 0 }.map { reference[assigned[it]] to candidate[it] }
        return JSONObject().put("referenceCount", reference.size).put("candidateCount", candidate.size)
            .put("matchedCount", matched).put("allDetectionsMatched", matched == reference.size && matched == candidate.size)
            .put("maximumMatchedBoxDelta", pairs.maxOfOrNull { boxDelta(it.first, it.second) } ?: JSONObject.NULL)
            .put("maximumMatchedScoreDelta", pairs.maxOfOrNull { abs(it.first.detectionConfidence - it.second.detectionConfidence) } ?: JSONObject.NULL)
            .put("boxAbsoluteTolerance", BOX_TOLERANCE).put("scoreAbsoluteTolerance", SCORE_TOLERANCE)
            .put("groundTruthAccuracyValidated", false)
    }

    fun compareRaw(reference: FloatArray?, candidate: FloatArray?, config: ModelRuntimeConfig?,
        referenceInputSize: Int? = null): JSONObject {
        if (reference == null || candidate == null || config == null) {
            return JSONObject().put("available", false).put("withinTolerance", false)
                .put("reason", "UNIFIED_OUTPUT_UNAVAILABLE")
        }
        if (reference.size != 1_800 || candidate.size != 1_800) {
            return JSONObject().put("available", false).put("withinTolerance", false)
                .put("reason", "OUTPUT_SHAPE_MISMATCH")
        }
        val normalizeCoordinates = referenceInputSize != null && referenceInputSize != config.inputSize
        val coordinateTolerance = if (normalizeCoordinates) 0.001 else RAW_COORDINATE_TOLERANCE
        fun scaleForRow(values: FloatArray, offset: Int, inputSize: Int): Double =
            if ((0..3).all { values[offset + it] in -0.05f..1.05f }) 1.0 else inputSize.toDouble()
        var nonFinite = 0
        var coordinateMax = 0.0
        var coordinateTotal = 0.0
        var coordinateCount = 0
        var scoreMax = 0.0
        var scoreTotal = 0.0
        var scoreCount = 0
        var classChangedRows = 0
        var thresholdCrossingRows = 0
        fun survivesThreshold(values: FloatArray, offset: Int): Boolean {
            val score = values[offset + 4]
            val id = values[offset + 5]
            if (!score.isFinite() || score !in 0f..1f || score == 0f || !id.isFinite()) return false
            if (abs(id - id.roundToInt()) > 1e-4f) return false
            val className = config.classNameForId(id.roundToInt()) ?: return false
            return config.isAllowedClass(className) && score >= config.thresholdForClass(className)
        }
        for (index in reference.indices) {
            val a = reference[index]
            val b = candidate[index]
            if (!a.isFinite() || !b.isFinite()) {
                nonFinite += 1
                continue
            }
            val row = index / 6 * 6
            val error = if (normalizeCoordinates && index % 6 < 4) {
                abs(a / scaleForRow(reference, row, requireNotNull(referenceInputSize)) -
                    b / scaleForRow(candidate, row, config.inputSize))
            } else abs(a.toDouble() - b.toDouble())
            when (index % 6) {
                in 0..3 -> {
                    coordinateMax = maxOf(coordinateMax, error)
                    coordinateTotal += error
                    coordinateCount += 1
                }
                4 -> {
                    scoreMax = maxOf(scoreMax, error)
                    scoreTotal += error
                    scoreCount += 1
                }
                5 -> if (a != b) classChangedRows += 1
            }
        }
        for (row in 0 until 300) {
            if (survivesThreshold(reference, row * 6) != survivesThreshold(candidate, row * 6)) thresholdCrossingRows += 1
        }
        return JSONObject().put("available", true).put("rows", 300).put("nonFiniteValues", nonFinite)
            .put("coordinateMaxAbsError", coordinateMax)
            .put("coordinateMeanAbsError", if (coordinateCount > 0) coordinateTotal / coordinateCount else JSONObject.NULL)
            .put("scoreMaxAbsError", scoreMax)
            .put("scoreMeanAbsError", if (scoreCount > 0) scoreTotal / scoreCount else JSONObject.NULL)
            .put("classChangedRows", classChangedRows).put("thresholdCrossingRows", thresholdCrossingRows)
            .put("coordinateAbsoluteTolerance", coordinateTolerance)
            .put("scoreAbsoluteTolerance", RAW_SCORE_TOLERANCE)
            .put("coordinateUnits", if (normalizeCoordinates) "NORMALIZED_MODEL_COORDINATES" else "EXPORTED_RAW_TENSOR_UNITS")
            .put("comparison", "SAME_ROW_INDEX")
            .put("withinTolerance", nonFinite == 0 && coordinateMax <= coordinateTolerance &&
                scoreMax <= RAW_SCORE_TOLERANCE && classChangedRows == 0 && thresholdCrossingRows == 0)
            .put("groundTruthAccuracyValidated", false)
    }

    /** Additional diagnostic only: never replaces the strict SAME_ROW_INDEX result above. */
    fun compareRawSet(reference: FloatArray?, candidate: FloatArray?, config: ModelRuntimeConfig?,
        referenceInputSize: Int? = null): JSONObject {
        if (reference == null || candidate == null || config == null) {
            return JSONObject().put("available", false).put("withinTolerance", false)
                .put("reason", "UNIFIED_OUTPUT_UNAVAILABLE")
        }
        if (reference.size != 1_800 || candidate.size != 1_800) {
            return JSONObject().put("available", false).put("withinTolerance", false)
                .put("reason", "OUTPUT_SHAPE_MISMATCH")
        }
        val normalized = referenceInputSize != null && referenceInputSize != config.inputSize
        val coordinateTolerance = if (normalized) 0.001 else RAW_COORDINATE_TOLERANCE
        val referenceRows = rawRows(reference, config, referenceInputSize ?: config.inputSize, normalized)
        val candidateRows = rawRows(candidate, config, config.inputSize, normalized)
        fun coordinateError(a: RawRow, b: RawRow): Double =
            (0..3).maxOf { abs(a.coordinates[it] - b.coordinates[it]) }
        fun compatible(a: RawRow, b: RawRow): Boolean = a.finite && b.finite && a.classId == b.classId &&
            a.retained == b.retained && abs(a.score - b.score) <= RAW_SCORE_TOLERANCE &&
            coordinateError(a, b) <= coordinateTolerance
        val edges = referenceRows.mapIndexed { index, row ->
            // Prefer identity when possible so duplicate padding does not imply needless movement.
            candidateRows.indices.filter { compatible(row, candidateRows[it]) }
                .sortedWith(compareBy<Int> { if (it == index) 0 else 1 }.thenBy { it })
        }
        val assigned = IntArray(300) { -1 }
        fun match(referenceIndex: Int, visited: BooleanArray): Boolean {
            for (candidateIndex in edges[referenceIndex]) {
                if (visited[candidateIndex]) continue
                visited[candidateIndex] = true
                if (assigned[candidateIndex] < 0 || match(assigned[candidateIndex], visited)) {
                    assigned[candidateIndex] = referenceIndex
                    return true
                }
            }
            return false
        }
        referenceRows.indices.forEach { match(it, BooleanArray(300)) }
        val pairs = assigned.indices.filter { assigned[it] >= 0 }.map { assigned[it] to it }
        val matchedReferenceIndices = pairs.map { it.first }.toSet()
        val failedSameRows = referenceRows.indices.filter { !compatible(referenceRows[it], candidateRows[it]) }
        val coordinateErrors = pairs.flatMap { (a, b) ->
            (0..3).map { abs(referenceRows[a].coordinates[it] - candidateRows[b].coordinates[it]) }
        }
        val scoreErrors = pairs.map { (a, b) -> abs(referenceRows[a].score - candidateRows[b].score) }
        val failedSummary = JSONObject().put("rowCount", failedSameRows.size)
            .put("reference", failedRowConfidence(failedSameRows.map { referenceRows[it] }))
            .put("candidate", failedRowConfidence(failedSameRows.map { candidateRows[it] }))
            .put("bothBelowClassThresholdRows", failedSameRows.count {
                referenceRows[it].belowThreshold == true && candidateRows[it].belowThreshold == true })
        return JSONObject().put("available", true).put("rows", 300)
            .put("comparison", "UNORDERED_ROWS_MAXIMUM_ONE_TO_ONE_MATCHING")
            .put("matchedRows", pairs.size).put("unmatchedReferenceRows", 300 - pairs.size)
            .put("unmatchedCandidateRows", 300 - pairs.size)
            .put("matchedNonIdentityPairs", pairs.count { it.first != it.second })
            .put("nonIdentityPairCountIsUniquePermutation", false)
            .put("errorStatisticsScope", "MATCHED_PAIRS_ONLY")
            .put("unmatchedReferenceConfidence", failedRowConfidence(referenceRows.indices
                .filter { it !in matchedReferenceIndices }.map { referenceRows[it] }))
            .put("unmatchedCandidateConfidence", failedRowConfidence(assigned.indices
                .filter { assigned[it] < 0 }.map { candidateRows[it] }))
            .put("coordinateMaxAbsError", coordinateErrors.maxOrNull() ?: JSONObject.NULL)
            .put("coordinateMeanAbsError", if (coordinateErrors.isNotEmpty()) coordinateErrors.average() else JSONObject.NULL)
            .put("scoreMaxAbsError", scoreErrors.maxOrNull() ?: JSONObject.NULL)
            .put("scoreMeanAbsError", if (scoreErrors.isNotEmpty()) scoreErrors.average() else JSONObject.NULL)
            .put("coordinateAbsoluteTolerance", coordinateTolerance).put("scoreAbsoluteTolerance", RAW_SCORE_TOLERANCE)
            .put("classIdComparison", "EXACT").put("thresholdMembershipMustMatch", true)
            .put("thresholdMembershipMeaning", "CLASS_SCORE_GATE_ONLY_NO_BBOX_VALIDITY_TEST")
            .put("coordinateUnits", if (normalized) "NORMALIZED_MODEL_COORDINATES" else "EXPORTED_RAW_TENSOR_UNITS")
            .put("sameRowFailureConfidence", failedSummary)
            .put("withinTolerance", pairs.size == 300).put("replacesStrictSameRowComparison", false)
            .put("groundTruthAccuracyValidated", false)
    }

    private data class RawRow(val coordinates: DoubleArray, val score: Double, val classId: Float,
        val finite: Boolean, val retained: Boolean, val belowThreshold: Boolean?)

    private fun rawRows(values: FloatArray, config: ModelRuntimeConfig, inputSize: Int, normalized: Boolean): List<RawRow> =
        (0 until 300).map { index ->
            val offset = index * 6
            val score = values[offset + 4]
            val classId = values[offset + 5]
            val validClassId = classId.isFinite() && abs(classId - classId.roundToInt()) <= 1e-4f
            val className = if (validClassId) config.classNameForId(classId.roundToInt()) else null
            val threshold = className?.let(config::thresholdForClass)
            val retained = score.isFinite() && score in 0f..1f && score > 0f && className != null &&
                config.isAllowedClass(className) && score >= requireNotNull(threshold)
            val scale = if (!normalized || (0..3).all { values[offset + it] in -0.05f..1.05f }) 1.0 else inputSize.toDouble()
            RawRow(DoubleArray(4) { values[offset + it] / scale }, score.toDouble(), classId,
                (0..5).all { values[offset + it].isFinite() }, retained,
                if (score.isFinite() && threshold != null) score < threshold else null)
        }

    private fun failedRowConfidence(rows: List<RawRow>): JSONObject {
        val scores = rows.map { it.score }.filter { it.isFinite() }
        return JSONObject().put("finiteConfidenceRows", scores.size)
            .put("confidenceMin", scores.minOrNull() ?: JSONObject.NULL)
            .put("confidenceMax", scores.maxOrNull() ?: JSONObject.NULL)
            .put("confidenceMean", if (scores.isNotEmpty()) scores.average() else JSONObject.NULL)
            .put("belowClassThresholdRows", rows.count { it.belowThreshold == true })
            .put("undefinedBelowThresholdComparisonRows", rows.count { it.belowThreshold == null })
            .put("excludedByClassScoreGateRows", rows.count { !it.retained })
    }

    private const val BOX_TOLERANCE = 0.01f
    private const val SCORE_TOLERANCE = 0.01f
    private const val RAW_COORDINATE_TOLERANCE = 0.05
    private const val RAW_SCORE_TOLERANCE = 0.001
}

/** Stateful test admission gate; caller holds its monitor through the matching depth update. */
internal class HeterogeneousResultGate(private val maximumAgeMs: Long = 800L) {
    private var newestAcceptedCameraNs = 0L
    fun admit(cameraNs: Long, sourceFrameNs: Long, latestCameraNs: Long, latestFrameNs: Long,
        capturedMs: Long, completedMs: Long, sourceAligned: Boolean): String {
        if (!sourceAligned || cameraNs <= 0L || sourceFrameNs <= 0L) return "SOURCE_MISMATCH"
        if (completedMs - capturedMs !in 0L..maximumAgeMs ||
            latestCameraNs - cameraNs !in 0L..maximumAgeMs * 1_000_000L ||
            latestFrameNs - sourceFrameNs !in 0L..maximumAgeMs * 1_000_000L) return "STALE"
        if (cameraNs <= newestAcceptedCameraNs) return "OLDER_THAN_COMPLETED"
        newestAcceptedCameraNs = cameraNs
        return "NONE"
    }
}

/** Approved test assets only; production selection never rewrites its configuration. */
internal object HeterogeneousModelCandidates {
    val names = setOf("production", "gpu_compatible_768", "fp16_768", "fp32_640")
    fun select(production: TwoModelRuntimeConfig, model: String): TwoModelRuntimeConfig {
        require(model in names) { "Unsupported model candidate" }
        if (model == "production") return production
        val unified = requireNotNull(production.unifiedWalksafe)
        val candidate = when (model) {
            "gpu_compatible_768" -> unified.copy(
                asset = "runtime-candidates/walksafe_unified_yolo26n_768_float32_gpu_compatible.tflite",
                artifactSha256 = "25ef119d4f07e1bffbd4ac6827fa46a44a0fa3deaaedfb0c867e7c655f813559", inputSize = 768)
            "fp16_768" -> unified.copy(
                asset = "runtime-candidates/walksafe_unified_yolo26n_768_float16.tflite",
                artifactSha256 = "e8478f5646fd2b6b64e2fd0748be7ce7d696e6abada013e6ca7687eb00876dfc", inputSize = 768)
            "fp32_640" -> unified.copy(
                asset = "runtime-candidates/walksafe_unified_yolo26n_640_float32.tflite",
                artifactSha256 = "96ff3ac1fb1a3f23f821995dbabd4ca819fd34e32eb0482da571290d35345902", inputSize = 640)
            else -> error("Unsupported model candidate")
        }
        return production.copy(primaryModelKey = TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
            unifiedWalksafe = candidate, fallbackModelKey = null, customTactile = null, cocoGeneral = null)
    }

}
