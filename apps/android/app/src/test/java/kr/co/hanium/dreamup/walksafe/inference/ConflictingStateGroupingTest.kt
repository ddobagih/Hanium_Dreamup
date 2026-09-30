package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S10-17 (originals/10.txt:193-199): strict same-object grouping only inside conflicting-state families. */
class ConflictingStateGroupingTest {
    private val box = RectNorm(0.30f, 0.40f, 0.20f, 0.20f)

    @Test
    fun sameAreaNormalAndDamagedPavingIsOneObjectWithAnUncertainStateAndBothDetectionsStay() {
        val detections = listOf(
            DetectionCandidate("linear_tactile_paving", 0.80f, box),
            DetectionCandidate("damaged_linear_tactile_paving", 0.70f, RectNorm(0.31f, 0.40f, 0.20f, 0.20f)),
            DetectionCandidate("bollard", 0.60f, RectNorm(0.70f, 0.40f, 0.05f, 0.20f)),
        )
        assertEquals(setOf(0, 1), ConflictingStateGrouping.stateUncertainIndices(detections))
        assertEquals(3, detections.size)
    }

    @Test
    fun abandonedAndMovingScooterOnTheSameBoxAreGrouped() {
        assertTrue(ConflictingStateGrouping.isConflictingStatePair("abandoned_e_scooter", box, "moving_e_scooter", box))
    }

    @Test
    fun overlappingDifferentObjectsAreNeverGrouped() {
        // A person on a bicycle overlaps almost completely; class-agnostic suppression would lose one.
        assertFalse(ConflictingStateGrouping.isConflictingStatePair("person", box, "bicycle", box))
        // Different paving shapes are different objects, not two states of one.
        assertFalse(ConflictingStateGrouping.isConflictingStatePair("normal_tactile_block", box, "damaged_dot_tactile_paving", box))
        assertFalse(ConflictingStateGrouping.isConflictingStatePair("linear_tactile_paving", box, "linear_tactile_paving", box))
    }

    @Test
    fun overlapAndSizeBoundariesAreStrict() {
        val normal = "normal_tactile_block"
        val damaged = "damaged_tactile_block"
        // Horizontal shift of two equal 0.2 squares at which IoU == 0.7 exactly (about 0.0353).
        val shift = 0.2f * (1f - 0.7f) / (1f + 0.7f)
        assertTrue(ConflictingStateGrouping.isConflictingStatePair(normal, box, damaged, RectNorm(0.30f + shift * 0.99f, 0.40f, 0.20f, 0.20f)))
        assertFalse(ConflictingStateGrouping.isConflictingStatePair(normal, box, damaged, RectNorm(0.30f + shift * 1.05f, 0.40f, 0.20f, 0.20f)))
        // Area ratio outside 0.8..1.25 is not the same object even when one box contains the other.
        val larger = RectNorm(0.29f, 0.39f, 0.23f, 0.23f) // area ratio 1.3225
        assertFalse(ConflictingStateGrouping.isConflictingStatePair(normal, box, damaged, larger))
        val slightlyLarger = RectNorm(0.295f, 0.395f, 0.21f, 0.21f) // area ratio 1.1025, IoU 0.907
        assertTrue(ConflictingStateGrouping.isConflictingStatePair(normal, box, damaged, slightlyLarger))
    }
}
