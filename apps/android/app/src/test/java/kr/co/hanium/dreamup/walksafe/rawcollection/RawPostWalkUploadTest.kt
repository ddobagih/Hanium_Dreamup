package kr.co.hanium.dreamup.walksafe.rawcollection

import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner decision 3(b) (2026-09-29): RAW segments are sent only after the walk has ended, on Wi-Fi
 * while charging (FP-035-DETAIL-04 interim), under the original walk id and consent receipt
 * (16-17 #13), and a walk that becomes ACTIVE again stops the transfer.
 */
class RawPostWalkUploadTest {
    @Test
    fun pausedWalkStartsNoRawUploadEvenOnWifiWhileCharging() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        rig.coordinator.sealActiveSegment(rig.paused(NP_WALK_1))

        assertNull(rig.coordinator.startNextUpload { rig.paused(NP_WALK_1) })
        assertEquals(0, rig.client.calls)
    }

    @Test
    fun endedWalkOnWifiWhileChargingUploadsItsSegmentUnderTheOriginalWalkIdAndReceipt() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        rig.coordinator.onWalkEnded(rig.after(NP_WALK_1))

        val call = rig.coordinator.startNextUpload { rig.after(NP_WALK_1) }
        assertNotNull(call)
        assertEquals(RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT, requireNotNull(call).execute())
        assertEquals(listOf(NP_WALK_1), rig.client.sentWalkIds)
        assertEquals(listOf(NP_WALK_1), rig.client.sentManifestWalkIds)
        assertEquals(listOf(NP_CONSENT_SHA), rig.client.sentConsentReceipts)
        assertEquals(0, rig.storage.collectionCount)
    }

    @Test
    fun safeStopSealsTheSegmentButSendsItOnlyAfterTheUserEndsTheWalk() {
        // Root decision R4 (transfer-F2): SAFE_STOP is a system stop, not an explicit end.
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        val stopped = rig.after(NP_WALK_1, state = WalkSessionState.SAFE_STOP)
        assertEquals(RawSegmentSealResult.SEALED, rig.coordinator.onWalkEnded(stopped))
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.WALK_STATE),
            rig.coordinator.readyStatus(stopped),
        )
        assertNull(rig.coordinator.startNextUpload { stopped })
        assertEquals(0, rig.client.calls)
        assertEquals(1, rig.store.readyManifests(NP_OWNER).size)
        val ended = rig.after(NP_WALK_1, state = WalkSessionState.ENDED)
        assertEquals(
            RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
            requireNotNull(rig.coordinator.startNextUpload { ended }).execute(),
        )
        assertEquals(listOf(NP_WALK_1), rig.client.sentWalkIds)
    }

    @Test
    fun aNewWalkRequestAfterSafeStopAlsoSendsTheSealedSegment() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        rig.coordinator.onWalkEnded(rig.after(NP_WALK_1, state = WalkSessionState.SAFE_STOP))
        val ready = rig.after(NP_WALK_2, state = WalkSessionState.READY)
        assertEquals(
            RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
            requireNotNull(rig.coordinator.startNextUpload { ready }).execute(),
        )
        assertEquals(listOf(NP_WALK_1), rig.client.sentWalkIds)
    }

    @Test
    fun mobileDataWithoutTheWifiConditionHoldsTheRawUpload() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        rig.coordinator.onWalkEnded(rig.after(NP_WALK_1))

        assertNull(
            rig.coordinator.startNextUpload {
                rig.after(NP_WALK_1, transport = ActiveNetworkTransport.CELLULAR)
            },
        )
        assertEquals(0, rig.client.calls)
        assertEquals(1, rig.storage.collectionCount)
    }

    @Test
    fun aNewActiveWalkCancelsTheInFlightUploadAndStartsNoNewOne() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        rig.coordinator.onWalkEnded(rig.after(NP_WALK_1))
        val call = requireNotNull(rig.coordinator.startNextUpload { rig.after(NP_WALK_1) })

        assertFalse(rig.coordinator.revalidate(rig.active(NP_WALK_2)))
        assertTrue(call.isCancelled())
        assertNull(rig.coordinator.startNextUpload { rig.active(NP_WALK_2) })
        assertEquals(1, rig.store.readyManifests(NP_OWNER).size)
    }

    @Test
    fun anEarlierWalksSegmentIsUploadedUnderItsOwnWalkIdAfterAProcessRestart() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        // The process ends during the walk: no ENDED transition ever ran for walk 1.
        rig.restartProcess()
        val ready = rig.after(NP_WALK_2, state = WalkSessionState.READY)
        rig.coordinator.sealActiveSegment(ready)

        assertEquals(
            RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
            requireNotNull(rig.coordinator.startNextUpload { ready }).execute(),
        )
        assertEquals(listOf(NP_WALK_1), rig.client.sentWalkIds)
        assertEquals(listOf(NP_WALK_1), rig.client.sentManifestWalkIds)
    }

    @Test
    fun aFailedUploadIsKeptAndResumesAfterARestartWhenTheConditionsHoldAgain() {
        val rig = PostWalkRawRig()
        assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
        rig.coordinator.onWalkEnded(rig.after(NP_WALK_1))
        rig.client.failure = RawCollectionHttpException(503)
        assertEquals(
            RawCollectionUploadOutcome.RETRYABLE_FAILURE,
            requireNotNull(rig.coordinator.startNextUpload { rig.after(NP_WALK_1) }).execute(),
        )
        assertEquals(1, rig.storage.collectionCount)

        rig.client.failure = null
        rig.restartProcess()
        val ready = rig.after(NP_WALK_2, state = WalkSessionState.READY)
        assertEquals(
            RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
            requireNotNull(rig.coordinator.startNextUpload { ready }).execute(),
        )
        assertEquals(listOf(NP_WALK_1, NP_WALK_1), rig.client.sentWalkIds)
        assertEquals(0, rig.storage.collectionCount)
    }
}
