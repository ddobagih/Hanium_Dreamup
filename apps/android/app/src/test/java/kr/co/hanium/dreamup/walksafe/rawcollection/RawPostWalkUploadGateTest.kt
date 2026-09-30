package kr.co.hanium.dreamup.walksafe.rawcollection

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner decision 3(b) / S1617-13 / 16-17 #60: the post-walk upload gates are reported as reasons,
 * never as an empty queue, and the walk-end disposition of every segment is counted.
 */
class RawPostWalkUploadGateTest {
    @Test
    fun notChargingHoldsTheRawUploadWithTheChargingReason() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        assertEquals(RawSegmentSealResult.SEALED, rig.coordinator.onWalkEnded(rig.after(NP_WALK_1)))

        val unplugged = rig.after(NP_WALK_1, charging = false)
        assertNull(rig.coordinator.startNextUpload { unplugged })
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.CHARGING),
            rig.coordinator.uploadStatus(unplugged),
        )
        assertEquals(RawReadyStatus.Count(1), rig.coordinator.readyStatus(unplugged))
        assertEquals(1L, rig.coordinator.outcomeCountersSnapshot()["upload_gate_charging"])
    }

    @Test
    fun aWalkInProgressOrAHeldGatewayLeaseIsAGateNotAnEmptyQueue() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        assertEquals(
            RawSegmentSealResult.GATE_BLOCKED,
            rig.coordinator.sealActiveSegment(rig.paused(NP_WALK_1)),
        )
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.WALK_STATE),
            rig.coordinator.uploadStatus(rig.paused(NP_WALK_1)),
        )
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.WALK_STATE),
            rig.coordinator.readyStatus(rig.active(NP_WALK_1)),
        )
        val leaseStillHeld = rig.after(NP_WALK_1).copy(gatewayWalkLease = rig.active(NP_WALK_1).gatewayWalkLease)
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.GATEWAY_WALK_LEASE),
            rig.coordinator.uploadStatus(leaseStillHeld),
        )
        assertNull(rig.coordinator.startNextUpload { leaseStillHeld })
        assertEquals(0, rig.client.calls)
    }

    @Test
    fun walkEndKeepsTheCompleteSegmentAndCountsAnUnsealableRemainder() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        assertEquals(RawSegmentSealResult.SEALED, rig.coordinator.onWalkEnded(rig.after(NP_WALK_1)))
        assertEquals(0, rig.coordinator.discardEndedPartials(rig.after(NP_WALK_1)))
        assertEquals(1, rig.store.readyManifests(NP_OWNER).size)
        assertEquals(1L, rig.coordinator.outcomeCountersSnapshot()["segment_sealed_at_walk_end"])

        // An incomplete remainder (a PARTIAL without the runtime batch) cannot be sealed: deleted and counted.
        assertTrue(
            rig.store.open(
                collectionId = "123e4567-e89b-42d3-a456-426614174777",
                owner = NP_OWNER,
                walkSessionId = NP_WALK_2,
                consentReceiptSha256 = NP_CONSENT_SHA,
                capturedStartedAtEpochMs = rig.nowEpochMs - 1_000L,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
            ),
        )
        assertEquals(
            RawSegmentSealResult.STORAGE_FAILURE,
            rig.coordinator.onWalkEnded(rig.after(NP_WALK_2, state = WalkSessionState.SAFE_STOP)),
        )
        assertEquals(1L, rig.coordinator.outcomeCountersSnapshot()["segment_discarded_at_walk_end"])
        assertEquals(1, rig.storage.collectionCount)
    }

    @Test
    fun aWalkTheGatewayWillNeverAcceptIsDeletedWhileAWalkInProgressIsRetried() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        rig.coordinator.onWalkEnded(rig.after(NP_WALK_1))

        rig.client.failure = RawCollectionHttpException(409, gatewayError("raw_collection_walk_in_progress"))
        // transfer-F7: kept, and reported apart so MainActivity schedules one delayed retry.
        assertEquals(
            RawCollectionUploadOutcome.WALK_IN_PROGRESS,
            requireNotNull(rig.coordinator.startNextUpload { rig.after(NP_WALK_1) }).execute(),
        )
        assertEquals(1, rig.storage.collectionCount)

        rig.client.failure = RawCollectionHttpException(409, gatewayError("raw_collection_walk_not_uploadable"))
        assertEquals(
            RawCollectionUploadOutcome.WALK_REJECTED,
            requireNotNull(rig.coordinator.startNextUpload { rig.after(NP_WALK_1) }).execute(),
        )
        assertEquals(0, rig.storage.collectionCount)
        assertEquals(1L, rig.coordinator.outcomeCountersSnapshot()["upload_walk_rejected"])
        assertNull(rig.coordinator.startNextUpload { rig.after(NP_WALK_1) })
    }

    @Test
    fun aNewWalksFirstSegmentSealsTheOrphanedSegmentOfTheWalkWhoseProcessEnded() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        rig.restartProcess()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_2))

        val ready = rig.store.readyManifests(NP_OWNER)
        assertEquals(listOf(NP_WALK_1), ready.map(RawCollectionManifest::walkSessionId))
        assertEquals(2, rig.storage.collectionCount)
    }

    private fun gatewayError(code: String) = """{"detail":{"code":"$code","message":"refused"}}"""
}
