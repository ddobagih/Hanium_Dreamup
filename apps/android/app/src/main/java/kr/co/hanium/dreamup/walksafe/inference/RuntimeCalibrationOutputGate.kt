package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Output equivalence for a fixed fixture with the same model, input and parsing contract.
 * Legacy end-to-end rows and parsed detections have distinct compatible partners. Raw YOLO11
 * channels retain fixed anchor order and compare every coordinate, score and threshold membership.
 * This gate neither retains tensors nor validates overall model accuracy or fixture completeness.
 */
object RuntimeCalibrationOutputGate {
    fun compare(
        referenceRaw: FloatArray?,
        candidateRaw: FloatArray?,
        referenceDetections: List<DetectionCandidate>,
        candidateDetections: List<DetectionCandidate>,
        config: ModelRuntimeConfig,
        diagnostic: (String) -> Unit = {},
    ): Boolean {
        fun reject(code: String): Boolean { runCatching { diagnostic(code) }; return false }
        if (referenceRaw == null || candidateRaw == null ||
            referenceRaw.size != config.outputTensorShape().fold(1) { size, axis -> size * axis } ||
            candidateRaw.size != referenceRaw.size ||
            referenceRaw.any { !it.isFinite() } || candidateRaw.any { !it.isFinite() }) return reject(if(referenceRaw == null || candidateRaw == null) "RAW_MISSING" else if(referenceRaw.size != candidateRaw.size || referenceRaw.size != config.outputTensorShape().fold(1) { size, axis -> size * axis }) "RAW_COUNT" else "RAW_NONFINITE")
        if (referenceDetections.size != candidateDetections.size ||
            referenceDetections.any { !it.hasFiniteOutput() } ||
            candidateDetections.any { !it.hasFiniteOutput() }) return reject("DETECTION_COUNT_OR_NONFINITE")

        val rawMatches = when (config.outputFormat) {
            YoloOutputFormat.RAW_XYWH_PIXELS, YoloOutputFormat.RAW_XYWH_NORMALIZED -> rawChannelsMatch(referenceRaw, candidateRaw, config, diagnostic)
            YoloOutputFormat.END_TO_END_XYXY, YoloOutputFormat.END_TO_END_XYXY_PIXELS -> endToEndRowsMatch(referenceRaw, candidateRaw, config, diagnostic)
        }
        if (!rawMatches) return reject("RAW_GATE")

        val detectionMatch = hasPerfectMatching(referenceDetections.size) { referenceIndex, candidateIndex ->
            val a = referenceDetections[referenceIndex]
            val b = candidateDetections[candidateIndex]
            a.className == b.className &&
                abs(a.detectionConfidence - b.detectionConfidence) <= DETECTION_SCORE_TOLERANCE &&
                abs(a.bboxNorm.x - b.bboxNorm.x) <= DETECTION_BOX_TOLERANCE &&
                abs(a.bboxNorm.y - b.bboxNorm.y) <= DETECTION_BOX_TOLERANCE &&
                abs(a.bboxNorm.width - b.bboxNorm.width) <= DETECTION_BOX_TOLERANCE &&
                abs(a.bboxNorm.height - b.bboxNorm.height) <= DETECTION_BOX_TOLERANCE
        }
        return detectionMatch || reject("DETECTION_CLASS_SCORE_BOX_MATCHING")
    }

    /** A candidate fixture label is not coverage until the current baseline produces such a value. */
    fun hasNearThresholdCandidate(values: FloatArray?, config: ModelRuntimeConfig): Boolean {
        if (values == null || values.any { !it.isFinite() } ||
            values.size != config.outputTensorShape().fold(1) { size, axis -> size * axis }) return false
        return when (config.outputFormat) {
            YoloOutputFormat.RAW_XYWH_PIXELS, YoloOutputFormat.RAW_XYWH_NORMALIZED -> YoloRawOutputParser(
                config.inputSize, config.classes.size, config::classNameForId,
                config::thresholdForClass, config::isAllowedClass,
                config.nmsIouThreshold, config.maxDetections,
                normalizedCoordinates = config.outputFormat == YoloOutputFormat.RAW_XYWH_NORMALIZED,
            ).hasNearThresholdCandidate(values, NEAR_THRESHOLD_WINDOW)
            YoloOutputFormat.END_TO_END_XYXY, YoloOutputFormat.END_TO_END_XYXY_PIXELS -> YoloEndToEndOutputParser(
                config.inputSize, config::classNameForId, config::thresholdForClass, config::isAllowedClass,
                thresholdForClassId = config::thresholdForClassId,
                pixelCoordinates = config.outputFormat == YoloOutputFormat.END_TO_END_XYXY_PIXELS,
            ).parseRaw(values).any { detection ->
                val name = config.classNameForId(detection.classId)
                name != null && config.isAllowedClass(name) &&
                    abs(detection.score - config.thresholdForClassId(detection.classId)) <= NEAR_THRESHOLD_WINDOW
            }
        }
    }

    // Operational definition for calibration coverage, not a measured accuracy threshold.
    const val NEAR_THRESHOLD_WINDOW = 0.02f

    private fun endToEndRowsMatch(referenceRaw: FloatArray, candidateRaw: FloatArray, config: ModelRuntimeConfig, diagnostic: (String) -> Unit): Boolean {
        val referenceMembership = thresholdMembership(referenceRaw, config)
        val candidateMembership = thresholdMembership(candidateRaw, config)
        val matches = hasPerfectMatching(RAW_ROWS) { referenceIndex, candidateIndex ->
            val a = referenceIndex * RAW_COLUMNS
            val b = candidateIndex * RAW_COLUMNS
            referenceRaw[a + 5] == candidateRaw[b + 5] &&
                referenceMembership[referenceIndex] == candidateMembership[candidateIndex] &&
                abs(referenceRaw[a + 4].toDouble() - candidateRaw[b + 4]) <= RAW_SCORE_TOLERANCE &&
                (0..3).all { abs(referenceRaw[a + it].toDouble() - candidateRaw[b + it]) <= RAW_COORDINATE_TOLERANCE }
        }
        if (!matches) {
            fun report(code: String) { runCatching { diagnostic(code) } }
            val predicates = listOf<Pair<String,(Int,Int)->Boolean>>(
                "RAW_ROW_CLASS_MATCHING" to { a,b -> referenceRaw[a*6+5] == candidateRaw[b*6+5] },
                "RAW_ROW_MEMBERSHIP_MATCHING" to { a,b -> referenceMembership[a] == candidateMembership[b] },
                "RAW_ROW_SCORE_TOLERANCE_MATCHING" to { a,b -> abs(referenceRaw[a*6+4].toDouble()-candidateRaw[b*6+4]) <= RAW_SCORE_TOLERANCE },
                "RAW_ROW_COORDINATE_TOLERANCE_MATCHING" to { a,b -> (0..3).all { abs(referenceRaw[a*6+it].toDouble()-candidateRaw[b*6+it]) <= RAW_COORDINATE_TOLERANCE } })
            predicates.forEach { (code,predicate) -> if(!hasPerfectMatching(RAW_ROWS,predicate)) report(code) }
            report("RAW_ROW_COMBINED_PERFECT_MATCHING")
        }
        return matches
    }

    private fun rawChannelsMatch(reference: FloatArray, candidate: FloatArray, config: ModelRuntimeConfig, diagnostic: (String) -> Unit): Boolean {
        fun reject(code: String): Boolean { runCatching { diagnostic(code) }; return false }
        val anchors = config.outputTensorShape()[2]
        val thresholds = config.classes.map(config::thresholdForClass)
        val allowed = config.classes.map(config::isAllowedClass)
        for (anchor in 0 until anchors) {
            for (coordinate in 0..3) {
                val index = coordinate * anchors + anchor
                val coordinateScale = if (config.outputFormat == YoloOutputFormat.RAW_XYWH_NORMALIZED) config.inputSize else 1
                if (abs(reference[index].toDouble() - candidate[index]) * coordinateScale > RAW_COORDINATE_TOLERANCE) return reject("RAW_COORDINATE_TOLERANCE")
            }
            var referenceClass = -1
            var candidateClass = -1
            var referenceBest = 0f
            var candidateBest = 0f
            for (classIndex in config.classes.indices) {
                val index = (4 + classIndex) * anchors + anchor
                val a = reference[index]
                val b = candidate[index]
                if (a !in 0f..1f || b !in 0f..1f || abs(a.toDouble() - b) > RAW_SCORE_TOLERANCE) return reject("RAW_SCORE_RANGE_OR_TOLERANCE")
                val aIncluded = a > 0f && allowed[classIndex] && a >= thresholds[classIndex]
                val bIncluded = b > 0f && allowed[classIndex] && b >= thresholds[classIndex]
                if (aIncluded != bIncluded) return reject("RAW_THRESHOLD_MEMBERSHIP")
                if (a > referenceBest) { referenceBest = a; referenceClass = classIndex }
                if (b > candidateBest) { candidateBest = b; candidateClass = classIndex }
            }
            val referenceIncluded = referenceClass >= 0 && allowed[referenceClass] && referenceBest >= thresholds[referenceClass]
            val candidateIncluded = candidateClass >= 0 && allowed[candidateClass] && candidateBest >= thresholds[candidateClass]
            if (referenceIncluded != candidateIncluded || (referenceIncluded && referenceClass != candidateClass)) return reject("RAW_BEST_CLASS_MEMBERSHIP")
        }
        return true
    }

    /**
     * Checks declared fixture roles and preserves each non-crowd annotation's one-to-one match status.
     * Callers supply non-crowd ground truth and also require [compare]; this is not output equivalence.
     * Detection and ground-truth boxes must share normalized original-image coordinates after letterbox removal.
     */
    fun matchesFixtureRoles(
        reference: List<DetectionCandidate>,
        candidate: List<DetectionCandidate>,
        expectPositive: Boolean,
        expectEmpty: Boolean,
        groundTruth: List<Pair<String, RectNorm>>,
    ): Boolean {
        if (reference.any { !it.hasFiniteOutput() } || candidate.any { !it.hasFiniteOutput() } ||
            groundTruth.any { (_, box) -> !box.hasFiniteCoordinates() || box.width <= 0f || box.height <= 0f }) return false
        if (expectEmpty && (reference.isNotEmpty() || candidate.isNotEmpty())) return false

        val referenceMatches = matchedGroundTruth(reference, groundTruth)
        val candidateMatches = matchedGroundTruth(candidate, groundTruth)
        if (expectPositive && (!referenceMatches.any { it } || !candidateMatches.any { it })) return false
        return referenceMatches.contentEquals(candidateMatches)
    }

    /** Maximum cardinality matching, with the same fixed class/IoU contract as the device fixture test. */
    private fun matchedGroundTruth(
        predictions: List<DetectionCandidate>,
        groundTruth: List<Pair<String, RectNorm>>,
    ): BooleanArray {
        val edges = groundTruth.map { (className, box) ->
            predictions.indices.filter {
                predictions[it].className == className && iou(box, predictions[it].bboxNorm) >= GROUND_TRUTH_IOU_THRESHOLD
            }.sortedByDescending { iou(box, predictions[it].bboxNorm) }
        }
        val assigned = IntArray(predictions.size) { -1 }
        fun match(truthIndex: Int, visited: BooleanArray): Boolean {
            for (predictionIndex in edges[truthIndex]) {
                if (visited[predictionIndex]) continue
                visited[predictionIndex] = true
                if (assigned[predictionIndex] < 0 || match(assigned[predictionIndex], visited)) {
                    assigned[predictionIndex] = truthIndex
                    return true
                }
            }
            return false
        }
        groundTruth.indices.forEach { match(it, BooleanArray(predictions.size)) }
        return BooleanArray(groundTruth.size).also { matched ->
            assigned.filter { it >= 0 }.forEach { matched[it] = true }
        }
    }

    private fun iou(a: RectNorm, b: RectNorm): Float {
        val overlap = (minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)).coerceAtLeast(0f) *
            (minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)).coerceAtLeast(0f)
        return overlap / maxOf(a.area + b.area - overlap, 1e-12f)
    }

    /** Class/score membership deliberately excludes bbox validity, matching the raw comparison contract. */
    private fun thresholdMembership(values: FloatArray, config: ModelRuntimeConfig): BooleanArray =
        BooleanArray(RAW_ROWS) { index ->
            val offset = index * RAW_COLUMNS
            val score = values[offset + 4]
            val rawClassId = values[offset + 5]
            val classId = rawClassId.roundToInt()
            val className = if (abs(rawClassId - classId) <= CLASS_ID_TOLERANCE) {
                config.classNameForId(classId)
            } else null
            score in 0f..1f && score > 0f && className != null &&
                config.isAllowedClass(className) && score >= config.thresholdForClassId(classId)
        }

    private fun DetectionCandidate.hasFiniteOutput(): Boolean =
        detectionConfidence.isFinite() && bboxNorm.hasFiniteCoordinates()

    private fun RectNorm.hasFiniteCoordinates(): Boolean =
        x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()

    /** Augmenting paths can reassign a previous pair when a greedy match would lose a valid partner. */
    private fun hasPerfectMatching(size: Int, compatible: (Int, Int) -> Boolean): Boolean {
        val assigned = IntArray(size) { -1 }
        val unmatched = ArrayList<Int>()
        for (index in 0 until size) {
            if (compatible(index, index)) assigned[index] = index else unmatched.add(index)
        }
        // Identical rows, including repeated padding, need no graph construction or recursive search.
        if (unmatched.isEmpty()) return true
        val edges = List(size) { referenceIndex ->
            (0 until size).filter { compatible(referenceIndex, it) }.toIntArray()
        }
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
        return unmatched.all { match(it, BooleanArray(size)) }
    }

    private const val RAW_ROWS = 300
    private const val RAW_COLUMNS = 6
    private const val RAW_COORDINATE_TOLERANCE = 0.05
    private const val RAW_SCORE_TOLERANCE = 0.001
    private const val CLASS_ID_TOLERANCE = 1e-4f
    private const val DETECTION_BOX_TOLERANCE = 0.01f
    private const val DETECTION_SCORE_TOLERANCE = 0.01f
    private const val GROUND_TRUTH_IOU_THRESHOLD = 0.5f
}
