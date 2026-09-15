package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.ConfidenceImage8
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DepthImage16
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTestDepthSourceGateTest {
    @Test fun repeatedCpuImageDoesNotAdvanceDistanceHistoryEvenIfFrameClockAdvanced() {
        val gate = CameraTestDepthSourceGate()
        assertNotNull(gate.qualify(snapshot()))
        assertNull(gate.qualify(snapshot().copy(frameTimestampNs = 110L)))
    }

    @Test fun reusedRawIsRemovedButFreshFullFallbackSurvives() {
        val gate = CameraTestDepthSourceGate()
        assertNotNull(gate.qualify(snapshot()))
        val next = requireNotNull(gate.qualify(snapshot().copy(frameTimestampNs = 110L,
            cameraImageTimestampNs = 210L, fullDepthTimestampNs = 210L)))
        assertNull(next.rawDepth)
        assertNull(next.rawConfidence)
        assertTrue(next.hasFreshFullDepth)
    }

    @Test fun fullOnlyFreshFrameIsKeptWithoutRawSupport() {
        val qualified = requireNotNull(CameraTestDepthSourceGate().qualify(snapshot().copy(
            rawDepth = null, rawConfidence = null, rawDepthTimestampNs = null, rawConfidenceTimestampNs = null)))
        assertTrue(qualified.hasFreshFullDepth)
        assertNull(qualified.rawDepth)
    }

    @Test fun freshRawRequiresMatchingConfidenceObservation() {
        val fresh = requireNotNull(CameraTestDepthSourceGate().qualify(snapshot()))
        assertTrue(fresh.hasFreshMetricRawDepth)
        val mismatched = requireNotNull(CameraTestDepthSourceGate().qualify(snapshot().copy(rawConfidenceTimestampNs = 199L)))
        assertNull(mismatched.rawDepth)
        assertTrue(mismatched.hasFreshFullDepth)
    }

    @Test fun frameAndCpuClocksRemainDistinctAndStaleDepthCannotBecomeMetricEvidence() {
        val gate = CameraTestDepthSourceGate()
        val qualified = requireNotNull(gate.qualify(snapshot().copy(rawDepthTimestampNs = 100L,
            rawConfidenceTimestampNs = 100L, fullDepthTimestampNs = 100L)))
        assertNull(qualified.rawDepth)
        assertNull(qualified.fullDepth)
        assertNull(gate.qualify(snapshot().copy(frameTimestampNs = 99L, cameraImageTimestampNs = 220L)))
        assertNull(CameraTestDepthSourceGate().qualify(snapshot().copy(cameraImageTimestampNs = null)))
    }

    private fun snapshot() = DepthFrameSnapshot(
        frameTimestampNs = 100L,
        rawDepth = DepthImage16(2, 2, intArrayOf(1_000, 1_000, 1_000, 1_000)),
        rawConfidence = ConfidenceImage8(2, 2, byteArrayOf(-1, -1, -1, -1)),
        fullDepth = DepthImage16(2, 2, intArrayOf(1_100, 1_100, 1_100, 1_100)),
        rawDepthTimestampNs = 200L, fullDepthTimestampNs = 200L,
        cameraImageTimestampNs = 200L, rawConfidenceTimestampNs = 200L,
    )
}
