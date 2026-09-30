package kr.co.hanium.dreamup.walksafe.rawcollection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S1617-6-F1: windows split at a detector identity change become an internal outcome count. */
class RawIdentitySplitCounterTest {
    private val gpu = RawDetectorRuntimeIdentity("1".repeat(64), "2".repeat(64), "3".repeat(64), "gpu", false)

    @Test
    fun splitWindowsAreHandedOverOnceAndCountedInTheOutcomeSnapshot() {
        val accumulator = RawRuntimeWindowAccumulator()
        accumulator.recordCompletedInference(NP_WALK_1, 0L, 1, 40L, 0, "model-v1", gpu)
        accumulator.recordCompletedInference(NP_WALK_1, 100L, 1, 40L, 0, "model-v1", gpu.copy(activeDelegate = "cpu"))
        accumulator.recordCompletedInference(NP_WALK_1, 200L, 1, 40L, 0, "model-v1", null)
        val f = NoProfileRawFixture()
        assertNull(f.coordinator.outcomeCountersSnapshot()["identity_split_window"])
        f.coordinator.recordIdentitySplitWindows(accumulator.takeIdentitySplitCount())
        assertEquals(0, accumulator.takeIdentitySplitCount())
        f.coordinator.recordIdentitySplitWindows(accumulator.takeIdentitySplitCount())
        assertEquals(2L, f.coordinator.outcomeCountersSnapshot()["identity_split_window"])
    }
}
