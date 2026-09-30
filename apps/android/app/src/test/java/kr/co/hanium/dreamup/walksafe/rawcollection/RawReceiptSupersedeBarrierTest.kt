package kr.co.hanium.dreamup.walksafe.rawcollection

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1617-14-F2: the continue/revoke decision for a replaced receipt runs on the raw executor after
 * the new confirmation is visible. A capture queued in between must neither drop the earlier
 * receipt's aggregate nor let the admission cleanup delete its segments as an unproven change.
 * MainActivity marks the earlier receipt with [RawCollectionRuntimeCoordinator.holdReceiptUntilDecided]
 * before it applies the new confirmation.
 */
class RawReceiptSupersedeBarrierTest {
    private val next = "d".repeat(64)

    @Test
    fun aHeldReceiptKeepsItsAggregateUntilTheContinuityIsRecorded() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        f.coordinator.holdReceiptUntilDecided(NP_CONSENT_SHA)
        f.supersede(next)
        // A capture that runs before the decision task: the earlier aggregate is sealed, kept, and
        // not selectable yet; the new receipt still gets a segment.
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(1))
        assertEquals(2, f.storage.collectionCount)
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        assertEquals(RawReadyStatus.Count(1), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        assertTrue(f.coordinator.onConsentContinued(NP_CONSENT_SHA, next))
        assertEquals(RawReadyStatus.Count(2), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        assertEquals(
            listOf(NP_CONSENT_SHA, next),
            f.store.readyManifests(NP_OWNER).sortedBy { it.capturedStartedAtEpochMs }.map { it.consentReceiptSha256 },
        )
    }

    @Test
    fun aHeldReceiptThatIsThenRevokedIsDeleted() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        f.coordinator.holdReceiptUntilDecided(NP_CONSENT_SHA)
        f.supersede(next, revision = 3L)
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(1))
        assertEquals(2, f.storage.collectionCount)
        assertTrue(f.coordinator.onConsentRevoked(NP_CONSENT_SHA))
        // Only the new receipt's aggregate remains, and later cleanups keep it.
        assertEquals(1, f.storage.collectionCount)
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(2))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        assertEquals(listOf(next), f.store.readyManifests(NP_OWNER).map { it.consentReceiptSha256 })
    }

    @Test
    fun withoutTheHoldAQueuedCaptureCannotShowContinuityAndDropsTheEarlierAggregate() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        f.supersede(next)
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(1))
        assertEquals(1, f.storage.collectionCount)
    }

    @Test
    fun aPauseSealKeepsAHeldOrContinuedReceiptsAggregate() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        f.coordinator.holdReceiptUntilDecided(NP_CONSENT_SHA)
        f.supersede(next)
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        assertTrue(f.coordinator.onConsentContinued(NP_CONSENT_SHA, next))
        assertEquals(RawReadyStatus.Count(1), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        assertEquals(
            RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
            f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }?.execute(),
        )
    }

    @Test
    fun anUnrecordedContinuityKeepsTheSegmentForThisProcessButNeverMakesItSelectable() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        f.coordinator.holdReceiptUntilDecided(NP_CONSENT_SHA)
        f.supersede(next)
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(1))
        // A decision that could not be recorded leaves the receipt held (not deleted, not selectable).
        assertTrue(!f.coordinator.onConsentContinued(NP_CONSENT_SHA, NP_CONSENT_SHA))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(2))
        assertEquals(3, f.storage.collectionCount)
        assertEquals(RawReadyStatus.Count(1), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
    }

    @Test
    fun aHoldIgnoresMalformedReceipts() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        f.coordinator.holdReceiptUntilDecided("not-a-receipt")
        f.supersede(next)
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(1))
        assertEquals(1, f.storage.collectionCount)
    }
}
