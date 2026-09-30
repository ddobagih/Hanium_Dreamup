package kr.co.hanium.dreamup.walksafe.rawcollection

import java.util.UUID
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * S1617-18-F1/F2, S1617-14-F1/F2 (w22 review_android_b probes p1/p3/p4/p5/p7): the production
 * setting has no approved RAW queue profile (capacityProfile = null in every build). Data that can
 * no longer be uploaded (an earlier walk, a revoked or refused receipt, an unproven receipt change,
 * an unreadable copy) must not hold the slot, and a boundary inside a walk must not end the
 * walk's collection. Nothing unsent of a selectable receipt is deleted for space.
 */
class RawNoProfileQueueCoverageTest {
    @Test
    fun p1_endedWalksKeepTheirSegmentsAndTheRetainedBoundHoldsANewWalkUntilTheOldestIsSent() {
        // S1617-13 / S1617-18 / owner decision 3(b): segments wait for the post-walk upload and
        // count for every walk; nothing unsent is deleted for space.
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        f.endWalk()
        assertEquals(1, f.storage.collectionCount)
        f.walkId = NP_WALK_2
        // Walk 2 opens the last retained slot; its later windows merge into it.
        assertEquals(List(4) { RawMetadataCaptureResult.CAPTURED }, (1..4).map { f.capture(it) })
        f.endWalk()
        assertEquals(RawReadyStatus.Count(2), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        f.walkId = NP_WALK_3
        assertEquals(RawMetadataCaptureResult.CAPACITY_HOLD, f.capture(5))
        assertEquals(1L, f.coordinator.outcomeCountersSnapshot()["capture_capacity_hold"])
        assertEquals(2, f.storage.collectionCount)
        // After walk 3 ends, the oldest segment is sent first under its own walk id and frees a slot.
        f.endWalk()
        assertEquals(
            RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
            f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }?.execute(),
        )
        assertEquals(listOf(NP_WALK_2), f.store.readyManifests(NP_OWNER).map(RawCollectionManifest::walkSessionId))
        f.walkId = "123e4567-e89b-42d3-a456-426614174011"
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(6))
    }

    @Test
    fun p1b_aWalkWhoseProcessEndedIsSealedForThePostWalkUploadInsteadOfDropped() {
        // The process ended during walk 1: its ACTIVE pointer and aggregate are still stored.
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(1))
        f.walkId = NP_WALK_2
        assertEquals(List(3) { RawMetadataCaptureResult.CAPTURED }, (2..4).map { f.capture(it) })
        // S1617-13: walk 1's aggregate was sealed under walk 1's id; walk 2 uses the last slot.
        assertEquals(2, f.storage.collectionCount)
        assertEquals(listOf(NP_WALK_1), f.store.readyManifests(NP_OWNER).map(RawCollectionManifest::walkSessionId))
        f.endWalk()
        assertEquals(RawReadyStatus.Count(2), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
    }

    @Test
    fun p3_aRevokedReceiptsSegmentsAreDeletedAndTheNewReceiptCollectsAtOnce() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        assertTrue(f.coordinator.onConsentRevoked(NP_CONSENT_SHA))
        // FP-015/FP-034: the withdrawal drops what was not sent instead of keeping it 30 days.
        assertEquals(0, f.storage.collectionCount)
        f.supersede("d".repeat(64), revision = 3L)
        assertEquals(RawReadyStatus.Count(0), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        assertEquals(List(3) { RawMetadataCaptureResult.CAPTURED }, (1..3).map { f.capture(it) })
    }

    @Test
    fun p3b_anExplicitRawWithdrawalDeletesTheCurrentReceiptsUnsentSegmentsAndActiveAggregate() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(1))
        assertEquals(2, f.storage.collectionCount)
        assertTrue(f.coordinator.onConsentRevoked(NP_CONSENT_SHA))
        assertEquals(0, f.storage.collectionCount)
    }

    @Test
    fun p4_anUnprovenReceiptChangeDeletesTheOrphanInsteadOfHoldingTheQueue() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        // Accepted, but neither a continuity nor a revocation was recorded (e.g. the process ended).
        f.supersede("d".repeat(64))
        assertEquals(RawReadyStatus.Count(0), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        assertEquals(List(2) { RawMetadataCaptureResult.CAPTURED }, (1..2).map { f.capture(it) })
        // Only the new receipt's aggregate is left.
        assertEquals(1, f.storage.collectionCount)
        f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED))
        assertEquals(RawReadyStatus.Count(1), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
    }

    @Test
    fun p5_aContinuedAggregateSealedAtTheReceiptChangeLeavesRoomForTheNewReceipt() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        f.supersede("d".repeat(64))
        assertTrue(f.coordinator.onConsentContinued(NP_CONSENT_SHA, "d".repeat(64)))
        assertEquals(List(3) { RawMetadataCaptureResult.CAPTURED }, (1..3).map { f.capture(it) })
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        assertEquals(RawReadyStatus.Count(2), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
    }

    @Test
    fun p7_aRetryableUploadFailureKeepsTheSegmentForTheNextPostWalkUploadOldestFirst() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        f.endWalk()
        f.client.failure = RawCollectionHttpException(503, """{"detail":{"code":"raw_ingest_storage_unavailable"}}""")
        assertEquals(
            RawCollectionUploadOutcome.RETRYABLE_FAILURE,
            f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }?.execute(),
        )
        // The failed segment is kept for the next post-walk attempt.
        assertEquals(RawReadyStatus.Count(1), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        f.client.failure = null
        f.walkId = NP_WALK_2
        assertEquals(List(2) { RawMetadataCaptureResult.CAPTURED }, (1..2).map { f.capture(it) })
        f.endWalk()
        assertEquals(RawReadyStatus.Count(2), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        assertEquals(
            RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
            f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }?.execute(),
        )
        // Oldest first: walk 1's segment went, walk 2's waits.
        assertEquals(listOf(NP_WALK_2), f.store.readyManifests(NP_OWNER).map(RawCollectionManifest::walkSessionId))
    }

    @Test
    fun p7b_aServerDisabledUploadKeepsTheSameWalkCollectingAfterResume() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        f.client.failure = RawCollectionHttpException(503, """{"detail":{"code":"raw_ingest_disabled"}}""")
        assertEquals(
            RawCollectionUploadOutcome.SERVER_DISABLED,
            f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }?.execute(),
        )
        assertEquals(List(3) { RawMetadataCaptureResult.CAPTURED }, (1..3).map { f.capture(it) })
    }

    @Test
    fun aConsentRejectedSegmentIsDeletedAndDoesNotHoldTheWalk() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        f.client.failure = RawCollectionHttpException(409, """{"detail":{"code":"raw_source_collection_consent_interrupted"}}""")
        assertEquals(
            RawCollectionUploadOutcome.CONSENT_REJECTED,
            f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }?.execute(),
        )
        assertEquals(0, f.storage.collectionCount)
        assertEquals(RawReadyStatus.Count(0), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        f.client.failure = null
        assertEquals(List(2) { RawMetadataCaptureResult.CAPTURED }, (1..2).map { f.capture(it) })
    }

    @Test
    fun theFirstBoundarySealsAndLaterBoundariesAreMergedAndCountedInsteadOfHoldingTheWalk() {
        val f = NoProfileRawFixture()
        val cpu = NP_IDENTITY.copy(activeDelegate = "cpu", delegateFallbackUsed = true)
        val otherModel = NP_IDENTITY.copy(modelArtifactSha256 = "4".repeat(64))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0, identity = cpu))
        // Bootstrap CPU -> GPU: the CPU window keeps its exact identity in a sealed segment.
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(1))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(2))
        // The walk budget is now in use: further boundaries are merged, not sealed and held.
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(3, identity = otherModel))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(4, model = "model-v2"))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(5, detectorAvailable = false))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(6))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        val payloads = f.store.readyManifests(NP_OWNER).sortedBy { it.capturedStartedAtEpochMs }.map { f.payload(it, 0) }
        assertEquals(2, payloads.size)
        assertEquals(listOf("cpu", "gpu"), payloads.map { it.getString("active_delegate") })
        assertEquals(listOf(1, 6), payloads.map { it.getInt("window_count") })
        assertEquals(listOf(0, 3), payloads.map { it.getInt("boundary_window_count") })
        // The merged aggregate keeps the runtime it was opened with.
        assertEquals("1".repeat(64), payloads[1].getString("model_artifact_sha256"))
        assertEquals("model-v1", payloads[1].getString("model_revision"))
        assertEquals(true, payloads[1].getBoolean("detector_available"))
        val counters = f.coordinator.outcomeCountersSnapshot()
        assertEquals(1L, counters["segment_sealed_at_boundary"])
        assertEquals(3L, counters["segment_merged_at_boundary"])
        assertEquals(null, counters["capture_capacity_hold"])
    }

    @Test
    fun aWallClockRegressionSealsOnceAndTheWalkKeepsCollecting() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(1))
        f.elapsedMs += 30_000L
        val regressed = NP_BASE_EPOCH_MS - 60 * 60_000L
        f.nowEpochMs = regressed
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.captureAt(regressed - 30_000L, regressed))
        f.elapsedMs += 30_000L
        f.nowEpochMs = regressed + 30_000L
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.captureAt(regressed, regressed + 30_000L))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        f.nowEpochMs = NP_BASE_EPOCH_MS + 10 * 30_000L
        assertEquals(setOf(2), f.store.readyManifests(NP_OWNER).map { f.payload(it, 0).getInt("window_count") }.toSet())
    }

    @Test
    fun aWalkKeepsAtMostTwoUploadableSegmentsAndNeverDeletesThemForSpace() {
        val f = NoProfileRawFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(1))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        // Two sealed segments wait for this walk's Wi-Fi upload: the no-profile budget is used.
        assertEquals(RawMetadataCaptureResult.CAPACITY_HOLD, f.capture(2))
        assertEquals(RawReadyStatus.Count(2), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        assertEquals(2, f.storage.collectionCount)
        // A completed upload frees the budget again.
        assertEquals(
            RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
            f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }?.execute(),
        )
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(3))
    }

    @Test
    fun anUnreadableQuarantinedCopyIsOutsideTheSlotAndOnlyItsOwnBoundHolds() {
        val f = NoProfileRawFixture()
        f.storage.plantUnreadable(UUID.randomUUID().toString())
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(0))
        assertEquals(1, f.storage.quarantinedCount)
        f.storage.plantUnreadable(UUID.randomUUID().toString())
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        // A second unreadable copy reaches the quarantine bound: new segments are held (fail closed).
        assertEquals(RawMetadataCaptureResult.CAPACITY_HOLD, f.capture(1))
        assertEquals(2, f.storage.quarantinedCount)
        assertEquals(RawReadyStatus.Count(1), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
    }

    @Test
    fun aRevisionGapIsNotRawContinuity() {
        val f = NoProfileRawFixture()
        val previous = f.confirmation()
        val next = previous.copy(revision = 2L, clientRevision = 2L, backendConsentReceiptSha256 = "e".repeat(64))
        assertEquals(RawConsentSupersede.RAW_CONTINUED, rawConsentSupersede(previous, next, currentRawEffective = true))
        // Server: an unseen revision in between may have dropped RAW (409 ..._interrupted).
        assertEquals(RawConsentSupersede.RAW_REVOKED, rawConsentSupersede(previous, next.copy(revision = 3L), true))
        assertEquals(RawConsentSupersede.RAW_REVOKED, rawConsentSupersede(previous, next.copy(revision = 9L), true))
        // Revisions are positive, so the next revision after Long.MAX_VALUE never matches.
        assertEquals(
            RawConsentSupersede.RAW_REVOKED,
            rawConsentSupersede(previous.copy(revision = Long.MAX_VALUE), next.copy(revision = Long.MAX_VALUE), true),
        )
    }
}
