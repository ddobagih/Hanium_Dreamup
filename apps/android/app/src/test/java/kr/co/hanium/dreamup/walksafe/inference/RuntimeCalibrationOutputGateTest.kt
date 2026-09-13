package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCalibrationOutputGateTest {
    private val config = ModelRuntimeConfig(
        "test", "unused", 768, listOf("person", "car"), emptySet(), mapOf("default" to 0.5f),
    )

    @Test
    fun unchangedEmptyFixturePassesOnlyWithAvailableFullRawOutput() {
        val raw = FloatArray(1_800)
        assertTrue(compare(raw, raw.copyOf()))
        assertFalse(compare(null, raw))
        assertFalse(compare(raw, null))
        for (size in listOf(0, 6, 1_799, 1_801, 1_806)) {
            assertFalse(compare(raw, FloatArray(size)))
            assertFalse(compare(FloatArray(size), raw))
        }
    }

    @Test
    fun rawAndParsedPermutationPreservesPositiveFixture() {
        val reference = tensor(row(100f, 0.8f), row(200f, 0.7f, 1f))
        val candidate = FloatArray(1_800)
        for (index in 0 until 300) {
            reference.copyInto(candidate, (299 - index) * 6, index * 6, index * 6 + 6)
        }
        assertTrue(compare(reference, candidate, parse(reference), parse(candidate)))
    }

    @Test
    fun rawMatchingReassignsEarlierPartnerWhenGreedyPairingWouldFail() {
        val reference = tensor(row(10f), row(10.08f))
        val candidate = tensor(row(10.04f), row(9.96f))
        assertTrue(compare(reference, candidate))
        assertTrue(compare(candidate, reference))
    }

    @Test
    fun rawDuplicateRowsRequireDistinctPartnersEvenWithNoParsedDetections() {
        val reference = tensor(row(10f), row(10.1f))
        val candidate = tensor(row(10f), row(10f))
        assertFalse(compare(reference, candidate))
        assertFalse(compare(candidate, reference))
    }

    @Test
    fun emptyParsedOutputCannotHideRawNumericOrExactClassMismatch() {
        val reference = tensor(row(10f))
        for (field in 0..5) {
            val candidate = reference.copyOf()
            candidate[field] += when (field) {
                4 -> 0.0011f
                5 -> 0.00001f
                else -> 0.051f
            }
            assertFalse("raw field $field", compare(reference, candidate))
        }
    }

    @Test
    fun everyRawFieldMustBeFiniteOnBothSides() {
        val valid = FloatArray(1_800)
        for (field in 0..5) {
            for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                val invalid = valid.copyOf().apply { this[1_794 + field] = value }
                assertFalse(compare(valid, invalid))
                assertFalse(compare(invalid, valid))
                assertFalse(compare(invalid, invalid.copyOf()))
            }
        }
    }

    @Test
    fun rawTolerancesUseExportedUnitsAndDoNotNormalizeCoordinates() {
        val reference = tensor(row(400f))
        val within = reference.copyOf().apply {
            this[0] += 0.049f
            this[4] += 0.0009f
        }
        assertTrue(compare(reference, within))
        assertFalse(compare(reference, reference.copyOf().apply { this[0] += 0.051f }))
        assertFalse(compare(reference, reference.copyOf().apply { this[4] += 0.0011f }))
        val rawBoundary = reference.copyOf().apply { this[0] = 0f }
        assertTrue(compare(rawBoundary, rawBoundary.copyOf().apply { this[0] = Math.nextDown(0.05f) }))
        assertFalse(compare(rawBoundary, rawBoundary.copyOf().apply { this[0] = 0.05f }))
        assertFalse(compare(rawBoundary, rawBoundary.copyOf().apply { this[0] = Math.nextUp(0.05f) }))
        val scoreBoundary = reference.copyOf().apply { this[4] = 0f }
        assertTrue(compare(scoreBoundary, scoreBoundary.copyOf().apply { this[4] = Math.nextDown(0.001f) }))
        assertFalse(compare(scoreBoundary, scoreBoundary.copyOf().apply { this[4] = 0.001f }))
        assertFalse(compare(scoreBoundary, scoreBoundary.copyOf().apply { this[4] = Math.nextUp(0.001f) }))
    }

    @Test
    fun thresholdCrossingFailsWithinRawToleranceEvenWhenBoxesProduceNoDetection() {
        val reference = FloatArray(1_800).apply { this[4] = 0.4999f }
        val atThreshold = reference.copyOf().apply { this[4] = 0.5f }
        assertTrue(parse(reference).isEmpty())
        assertTrue(parse(atThreshold).isEmpty())
        assertFalse(compare(reference, atThreshold))
        assertFalse(compare(atThreshold, reference))
    }

    @Test
    fun thresholdMembershipUsesClassOverridesAndAllowlist() {
        val custom = config.copy(thresholds = mapOf("default" to 0.5f, "car" to 0.6f))
        val reference = tensor(row(10f, 0.5999f, 1f))
        val candidate = tensor(row(10f, 0.6f, 1f))
        assertFalse(compare(reference, candidate, model = custom))
        assertTrue(compare(reference, candidate, model = custom.copy(allowlist = setOf("person"))))
        assertTrue(compare(reference, candidate, model = config))
    }

    @Test
    fun scoreZeroAndOutOfRangeAreExcludedFromThresholdMembership() {
        val zeroThreshold = config.copy(thresholds = mapOf("default" to 0f))
        assertFalse(compare(tensor(row(10f, 0f)), tensor(row(10f, 0.0001f)), model = zeroThreshold))
        assertFalse(compare(tensor(row(10f, 1f)), tensor(row(10f, 1.0001f))))
    }

    @Test
    fun unknownOrFractionalClassIdsDoNotAcquireThresholdMembership() {
        for (classId in listOf(2f, 0.5f)) {
            assertTrue(compare(tensor(row(10f, 0.4999f, classId)), tensor(row(10f, 0.5f, classId))))
        }
    }

    @Test
    fun rawSuccessCannotHideParsedClassGeometryOrConfidenceMismatch() {
        val raw = tensor(row(100f, 0.8f))
        val reference = detection()
        val mismatches = listOf(
            reference.copy(className = "car"),
            reference.copy(detectionConfidence = 0.82f),
            reference.copy(bboxNorm = reference.bboxNorm.copy(x = 0.12f)),
            reference.copy(bboxNorm = reference.bboxNorm.copy(y = 0.22f)),
            reference.copy(bboxNorm = reference.bboxNorm.copy(width = 0.32f)),
            reference.copy(bboxNorm = reference.bboxNorm.copy(height = 0.42f)),
        )
        for (candidate in mismatches) {
            assertFalse(compare(raw, raw, listOf(reference), listOf(candidate)))
        }
    }

    @Test
    fun parsedMatchingReassignsEarlierPartnerAndIgnoresOrder() {
        val raw = FloatArray(1_800)
        val reference = listOf(detection(x = 0.1f), detection(x = 0.116f))
        val candidate = listOf(detection(x = 0.108f), detection(x = 0.092f))
        assertTrue(compare(raw, raw, reference, candidate))
        assertTrue(compare(raw, raw, candidate, reference))
        assertTrue(compare(raw, raw, reference, reference.reversed()))
    }

    @Test
    fun parsedCountAndDuplicateMismatchCannotReuseOneCompatibleDetection() {
        val raw = FloatArray(1_800)
        val reference = listOf(detection(x = 0.1f), detection(x = 0.3f))
        val duplicate = listOf(detection(x = 0.1f), detection(x = 0.1f))
        assertFalse(compare(raw, raw, reference, duplicate))
        assertFalse(compare(raw, raw, duplicate, reference))
        assertFalse(compare(raw, raw, reference, reference.take(1)))
        assertFalse(compare(raw, raw, emptyList(), reference))
        assertFalse(compare(raw, raw, reference, emptyList()))
    }

    @Test
    fun parsedToleranceIncludesBoundaryAndRejectsNextFloatOutsideIt() {
        val raw = FloatArray(1_800)
        val reference = detection().copy(bboxNorm = RectNorm(0f, 0f, 0f, 0f), detectionConfidence = 0f)
        val atBoundary = reference.copy(bboxNorm = RectNorm(0.01f, 0.01f, 0.01f, 0.01f), detectionConfidence = 0.01f)
        assertTrue(compare(raw, raw, listOf(reference), listOf(atBoundary)))
        assertFalse(compare(raw, raw, listOf(reference), listOf(atBoundary.copy(detectionConfidence = Math.nextUp(0.01f)))))
        assertFalse(compare(raw, raw, listOf(reference), listOf(atBoundary.copy(bboxNorm = atBoundary.bboxNorm.copy(x = Math.nextUp(0.01f))))))
    }

    @Test
    fun everyParsedFieldMustBeFiniteOnBothSides() {
        val raw = FloatArray(1_800)
        val reference = detection()
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val invalid = listOf(
                reference.copy(detectionConfidence = value),
                reference.copy(bboxNorm = reference.bboxNorm.copy(x = value)),
                reference.copy(bboxNorm = reference.bboxNorm.copy(y = value)),
                reference.copy(bboxNorm = reference.bboxNorm.copy(width = value)),
                reference.copy(bboxNorm = reference.bboxNorm.copy(height = value)),
            )
            for (candidate in invalid) {
                assertFalse(compare(raw, raw, listOf(reference), listOf(candidate)))
                assertFalse(compare(raw, raw, listOf(candidate), listOf(reference)))
                assertFalse(compare(raw, raw, listOf(candidate), listOf(candidate)))
            }
        }
    }

    @Test
    fun comparisonDoesNotMutateRawInputs() {
        val reference = tensor(row(10f), row(10.08f))
        val candidate = tensor(row(10.04f), row(9.96f))
        val referenceBefore = reference.copyOf()
        val candidateBefore = candidate.copyOf()
        assertTrue(compare(reference, candidate))
        assertArrayEquals(referenceBefore, reference, 0f)
        assertArrayEquals(candidateBefore, candidate, 0f)
    }

    @Test
    fun positiveFixtureRequiresGroundTruthMatchInBothOutputs() {
        val person = detection()
        val truth = listOf(person.className to person.bboxNorm)
        assertTrue(matchesRoles(listOf(person), listOf(person), positive = true, truth = truth))
        assertFalse(matchesRoles(emptyList(), emptyList(), positive = true, truth = truth))
        assertFalse(matchesRoles(listOf(person), emptyList(), positive = true, truth = truth))
        assertFalse(matchesRoles(emptyList(), listOf(person), positive = true, truth = truth))
        assertFalse(matchesRoles(listOf(person), listOf(person), positive = true))
        val wrongClass = person.copy(className = "car")
        assertFalse(matchesRoles(listOf(wrongClass), listOf(wrongClass), positive = true, truth = truth))
        val wrongBox = person.copy(bboxNorm = RectNorm(0.7f, 0.7f, 0.1f, 0.1f))
        assertFalse(matchesRoles(listOf(wrongBox), listOf(wrongBox), positive = true, truth = truth))
    }

    @Test
    fun emptyFixtureRequiresBothDetectionListsToBeEmpty() {
        assertTrue(matchesRoles(emptyList(), emptyList(), empty = true))
        assertFalse(matchesRoles(listOf(detection()), emptyList(), empty = true))
        assertFalse(matchesRoles(emptyList(), listOf(detection()), empty = true))
        assertFalse(matchesRoles(listOf(detection()), listOf(detection()), empty = true))
        assertFalse(matchesRoles(emptyList(), emptyList(), positive = true, empty = true))
    }

    @Test
    fun fixtureGroundTruthIouAcceptsExactHalfAndRejectsBelowHalf() {
        val truth = listOf("person" to RectNorm(0f, 0f, 0.5f, 0.5f))
        val half = detection().copy(bboxNorm = RectNorm(0f, 0f, 0.25f, 0.5f))
        val belowHalf = half.copy(bboxNorm = half.bboxNorm.copy(width = Math.nextDown(0.25f)))
        assertTrue(matchesRoles(listOf(half), listOf(half), positive = true, truth = truth))
        assertFalse(matchesRoles(listOf(belowHalf), listOf(belowHalf), positive = true, truth = truth))
        assertFalse(matchesRoles(listOf(half), listOf(belowHalf), truth = truth))
    }

    @Test
    fun fixtureGroundTruthPreservesEachAnnotationInsteadOfOnlyTotalMatches() {
        val first = detection(x = 0f).copy(bboxNorm = RectNorm(0f, 0f, 0.25f, 0.25f))
        val second = first.copy(bboxNorm = first.bboxNorm.copy(x = 0.75f))
        val truth = listOf("person" to first.bboxNorm, "person" to second.bboxNorm)
        assertFalse(matchesRoles(listOf(first), listOf(second), positive = true, truth = truth))
        assertFalse(matchesRoles(listOf(first), listOf(second), truth = truth))
        assertTrue(matchesRoles(listOf(first), listOf(first), positive = true, truth = truth))
    }

    @Test
    fun fixtureGroundTruthRequiresDistinctPartnersForDuplicateAnnotations() {
        val person = detection()
        val truth = listOf("person" to person.bboxNorm, "person" to person.bboxNorm)
        assertFalse(matchesRoles(listOf(person), listOf(person, person), positive = true, truth = truth))
        assertFalse(matchesRoles(listOf(person, person), listOf(person), positive = true, truth = truth))
        assertTrue(matchesRoles(listOf(person), listOf(person), positive = true, truth = truth))
    }

    @Test
    fun fixtureGroundTruthMatchingUsesAugmentingPathsAndAllowsPredictionPermutation() {
        val first = detection().copy(bboxNorm = RectNorm(0.1f, 0f, 0.4f, 0.5f))
        val second = first.copy(bboxNorm = first.bboxNorm.copy(x = 0f))
        val restricted = first.copy(bboxNorm = first.bboxNorm.copy(x = 0.2f))
        val truth = listOf("person" to first.bboxNorm, "person" to restricted.bboxNorm)
        assertTrue(matchesRoles(listOf(first, second), listOf(first, restricted), positive = true, truth = truth))
        assertTrue(matchesRoles(listOf(first, second), listOf(second, first), positive = true, truth = truth))
        assertFalse(matchesRoles(listOf(first, second), listOf(first), positive = true, truth = truth))
    }

    @Test
    fun ambiguousDuplicateTruthKeepsTruthPriorityWhenPredictionsArePermuted() {
        val person = detection()
        val car = person.copy(className = "car")
        val truth = listOf("person" to person.bboxNorm, "person" to person.bboxNorm)
        assertTrue(matchesRoles(listOf(car, person), listOf(person, car), positive = true, truth = truth))
    }

    @Test
    fun fixtureRoleChecksDoNotReplaceRawAndParsedEquivalence() {
        val reference = detection()
        val candidate = reference.copy(detectionConfidence = 0.95f)
        val truth = listOf("person" to reference.bboxNorm)
        assertTrue(matchesRoles(listOf(reference), listOf(candidate), positive = true, truth = truth))
        val raw = tensor(row(10f, 0.8f))
        assertFalse(compare(raw, raw, listOf(reference), listOf(candidate)))
    }

    @Test
    fun fixtureRoleChecksRejectNonFiniteOutputsAndInvalidGroundTruth() {
        val person = detection()
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val invalid = person.copy(detectionConfidence = value)
            assertFalse(matchesRoles(listOf(invalid), listOf(person)))
            assertFalse(matchesRoles(listOf(person), listOf(invalid)))
            assertFalse(matchesRoles(emptyList(), emptyList(), truth = listOf("person" to person.bboxNorm.copy(x = value))))
        }
        assertFalse(matchesRoles(emptyList(), emptyList(), truth = listOf("person" to person.bboxNorm.copy(width = 0f))))
        assertFalse(matchesRoles(emptyList(), emptyList(), truth = listOf("person" to person.bboxNorm.copy(height = -1f))))
    }

    private fun matchesRoles(
        reference: List<DetectionCandidate>,
        candidate: List<DetectionCandidate>,
        positive: Boolean = false,
        empty: Boolean = false,
        truth: List<Pair<String, RectNorm>> = emptyList(),
    ): Boolean = RuntimeCalibrationOutputGate.matchesFixtureRoles(reference, candidate, positive, empty, truth)

    private fun compare(
        reference: FloatArray?,
        candidate: FloatArray?,
        referenceDetections: List<DetectionCandidate> = emptyList(),
        candidateDetections: List<DetectionCandidate> = emptyList(),
        model: ModelRuntimeConfig = config,
    ): Boolean = RuntimeCalibrationOutputGate.compare(reference, candidate, referenceDetections, candidateDetections, model)

    private fun parse(raw: FloatArray): List<DetectionCandidate> = YoloEndToEndOutputParser(
        config.inputSize, config::classNameForId, config::thresholdForClass, config::isAllowedClass,
    ).parse(raw)

    private fun detection(x: Float = 0.1f): DetectionCandidate =
        DetectionCandidate("person", 0.8f, RectNorm(x, 0.2f, 0.3f, 0.4f))

    private fun row(x: Float, score: Float = 0.1f, classId: Float = 0f): FloatArray =
        floatArrayOf(x, 10f, x + 100f, 210f, score, classId)

    private fun tensor(vararg rows: FloatArray): FloatArray = FloatArray(1_800).also { output ->
        rows.forEachIndexed { index, row -> row.copyInto(output, index * 6) }
    }
}
