package kr.co.hanium.dreamup.walksafe.rawcollection

import java.util.UUID
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.MotionStateSummary
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateReason
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.BackendAccountDeviceCookieBinding
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkLease
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItemVersions
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSelections
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSession
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionMode
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessObservation
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessPlan
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessRequirement
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionRecoveryStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16-17 #1 (walk coverage), #2/#4 (v2 runtime stats), #8 (clocks), #12/#60 (outcome reasons). */
class RawCollectionRuntimeWalkCoverageTest {
    @Test
    fun laterWindowsOfOneActivePeriodAreKeptInOneSegment() {
        val f = CoverageFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 0))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 1, inferenceMs = 90L, maxMs = 1_500L))
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            f.capture(window = 2, inferenceMs = 60L, maxMs = 400L, thermal = true),
        )
        // Nothing is sent while the walk is ACTIVE.
        assertNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ACTIVE) })
        assertEquals(0, f.client.calls)

        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        val ready = f.store.readyManifests(OWNER)
        assertEquals(1, ready.size)
        assertEquals(f.windowStart(0), ready.single().capturedStartedAtEpochMs)
        assertEquals(f.windowEnd(2), ready.single().capturedEndedAtEpochMs)
        val detection = f.payload(ready.single(), 0)
        val performance = f.payload(ready.single(), 1)
        assertEquals(RAW_RUNTIME_METADATA_SCHEMA, detection.getString("schema_version"))
        assertEquals(3, detection.getInt("window_count"))
        assertEquals(90, detection.getInt("completed_inference_count"))
        assertEquals(1_500L, detection.getLong("max_inference_ms"))
        assertEquals(60L, detection.getLong("average_inference_ms"))
        assertEquals(90, performance.getInt("completed_inference_count"))
        assertTrue(performance.getBoolean("thermal_throttled"))
        assertEquals(1, performance.getInt("thermal_throttled_window_count"))
        assertEquals(
            90,
            (0 until RAW_RUNTIME_LATENCY_BUCKET_COUNT).sumOf {
                detection.getJSONObject("inference_ms_histogram").getJSONArray("counts").getInt(it)
            },
        )
    }

    @Test
    fun mergedRewritesReplaceTheStoredAggregateWithoutLeavingTheOldCollection() {
        val f = CoverageFixture()
        (0..2).forEach { assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = it)) }
        // RAW-CORE-F1: write-new-then-drop-old leaves exactly one stored aggregate.
        assertEquals(1, f.storage.collectionCount)
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        assertEquals(3, f.payload(f.store.readyManifests(OWNER).single(), 0).getInt("window_count"))
    }

    @Test
    fun appendBatchFailureAfterMergingTheThirdWindowKeepsTheTwoWindowAggregateSealable() {
        val f = CoverageFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 0))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 1))
        f.storage.failChunkWrites = true
        assertEquals(RawMetadataCaptureResult.STORAGE_FAILURE, f.capture(window = 2))
        f.storage.failChunkWrites = false
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        val ready = f.store.readyManifests(OWNER)
        assertEquals(1, ready.size)
        assertEquals(2, f.payload(ready.single(), 0).getInt("window_count"))
        assertEquals(60, f.payload(ready.single(), 0).getInt("completed_inference_count"))
        assertEquals(f.windowStart(0), ready.single().capturedStartedAtEpochMs)
        assertEquals(f.windowEnd(1), ready.single().capturedEndedAtEpochMs)
    }

    @Test
    fun theAggregateKeepsPointingAtTheKeptSegmentAfterAFailedRewrite() {
        val f = CoverageFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 0))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 1))
        f.storage.failPointerWrites = true
        assertEquals(RawMetadataCaptureResult.STORAGE_FAILURE, f.capture(window = 2))
        f.storage.failPointerWrites = false
        // Only the failed window is lost; the next window merges into the kept 2-window aggregate.
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 3))
        assertEquals(1, f.storage.collectionCount)
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        val ready = f.store.readyManifests(OWNER).single()
        assertEquals(3, f.payload(ready, 0).getInt("window_count"))
        assertEquals(f.windowStart(0), ready.capturedStartedAtEpochMs)
        assertEquals(f.windowEnd(3), ready.capturedEndedAtEpochMs)
    }

    @Test
    fun modelChangeClosesTheEarlierSegmentInsteadOfDroppingIt() {
        val f = CoverageFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 0, model = "model-a"))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 1, model = "model-b"))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        val ready = f.store.readyManifests(OWNER)
        assertEquals(2, ready.size)
        assertEquals(listOf("model-a", "model-b"), ready.map { f.payload(it, 0).getString("model_revision") })
        assertEquals(1L, f.coordinator.outcomeCountersSnapshot()["segment_sealed_at_boundary"])
    }

    @Test
    fun windowWithoutTheExecutedDetectorIdentityIsRejectedAsAnInvalidWindow() {
        val f = CoverageFixture()
        assertEquals(RawMetadataCaptureResult.INVALID_WINDOW, f.capture(window = 0, identity = null))
        assertEquals(0, f.storage.collectionCount)
        assertEquals(1L, f.coordinator.outcomeCountersSnapshot()["capture_invalid_window"])
    }

    @Test
    fun delegateOrHashChangeSealsTheEarlierSegmentAndKeepsEachIdentity() {
        // Three exact segments need room for more than one sealed segment per walk.
        val f = CoverageFixture(capacityProfile = CoverageFixture.TEST_CAPACITY_PROFILE)
        val cpuFallback = CoverageFixture.RUNTIME_IDENTITY.copy(activeDelegate = "cpu", delegateFallbackUsed = true)
        val otherModel = CoverageFixture.RUNTIME_IDENTITY.copy(modelArtifactSha256 = "4".repeat(64))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 0))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 1, identity = cpuFallback))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 2, identity = otherModel))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        val payloads = f.store.readyManifests(OWNER).sortedBy { it.capturedStartedAtEpochMs }
            .map { f.payload(it, 0) }
        assertEquals(3, payloads.size)
        assertEquals(listOf("gpu", "cpu", "gpu"), payloads.map { it.getString("active_delegate") })
        assertEquals(listOf(false, true, false), payloads.map { it.getBoolean("delegate_fallback_used") })
        assertEquals(
            listOf("1".repeat(64), "1".repeat(64), "4".repeat(64)),
            payloads.map { it.getString("model_artifact_sha256") },
        )
        assertEquals(listOf(1, 1, 1), payloads.map { it.getInt("window_count") })
        assertEquals(2L, f.coordinator.outcomeCountersSnapshot()["segment_sealed_at_boundary"])
    }

    @Test
    fun identityPayloadCarriesNoPathAccountOrDeviceValue() {
        val f = CoverageFixture()
        f.prepareOneReadySegment()
        val text = f.payload(f.store.readyManifests(OWNER).single(), 0).toString()
        listOf(CoverageFixture.ACTOR_ID, CoverageFixture.DEVICE_ID, CoverageFixture.WALK_ID, ".tflite", "models/", "/")
            .forEach { forbidden -> assertTrue(forbidden, !text.contains(forbidden)) }
    }

    @Test
    fun withoutAnApprovedProfileABoundaryKeepsTheSealedSegmentAndDoesNotHoldTheWalk() {
        // 16-17 #18 / S1617-18-F2: no injected profile -> "sealed 1 + active 1" per walk; the first
        // boundary seals, a later one is merged and counted; nothing unsent is deleted or held.
        val f = CoverageFixture(capacityProfile = null)
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 0))
        val other = CoverageFixture.RUNTIME_IDENTITY.copy(activeDelegate = "cpu")
        val third = CoverageFixture.RUNTIME_IDENTITY.copy(modelArtifactSha256 = "5".repeat(64))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 1, identity = other))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 2, identity = third))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        val ready = f.store.readyManifests(OWNER).sortedBy { it.capturedStartedAtEpochMs }
        assertEquals(2, ready.size)
        assertEquals(f.windowEnd(0), ready.first().capturedEndedAtEpochMs)
        assertEquals(listOf(0, 1), ready.map { f.payload(it, 0).getInt("boundary_window_count") })
        assertEquals(listOf("gpu", "cpu"), ready.map { f.payload(it, 0).getString("active_delegate") })
        val counters = f.coordinator.outcomeCountersSnapshot()
        assertEquals(null, counters["capture_capacity_hold"])
        assertEquals(1L, counters["segment_sealed_at_boundary"])
        assertEquals(1L, counters["segment_merged_at_boundary"])
    }

    @Test
    fun profileSegmentLimitHoldsNewSegmentsWithoutDeletingUnsentOnes() {
        val f = CoverageFixture(capacityProfile = ApprovedRawQueueCapacityProfile(2, 16L * RAW_MAX_COLLECTION_BYTES))
        val b = CoverageFixture.RUNTIME_IDENTITY.copy(activeDelegate = "cpu")
        val c = CoverageFixture.RUNTIME_IDENTITY.copy(modelArtifactSha256 = "5".repeat(64))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 0))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 1, identity = b))
        assertEquals(RawMetadataCaptureResult.CAPACITY_HOLD, f.capture(window = 2, identity = c))
        // RAW-CORE-F4: boundary seals no longer create segments without bound.
        assertEquals(2, f.storage.collectionCount)
        // Both earlier segments were sealed at their boundaries and stay ready for upload.
        assertEquals(RawSegmentSealResult.NOTHING_TO_SEAL, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        assertEquals(2, f.store.readyManifests(OWNER).size)
    }

    @Test
    fun lowOrUnknownDeviceSpaceHoldsOnlyRawAndIoFailureIsRecordedInternally() {
        // 14 #39: allocatable space must cover one segment plus its temp copy.
        val f = CoverageFixture()
        f.allocatableBytes = 2L * RAW_MAX_COLLECTION_BYTES - 1L
        assertEquals(RawMetadataCaptureResult.CAPACITY_HOLD, f.capture(window = 0))
        f.allocatableBytes = null
        assertEquals(RawMetadataCaptureResult.CAPACITY_HOLD, f.capture(window = 1))
        assertEquals(0, f.storage.collectionCount)
        f.allocatableBytes = 2L * RAW_MAX_COLLECTION_BYTES
        f.storage.failChunkWrites = true
        assertEquals(RawMetadataCaptureResult.STORAGE_FAILURE, f.capture(window = 2))
        val counters = f.coordinator.outcomeCountersSnapshot()
        assertEquals(1L, counters["storage_hold_raw_collection_insufficient_allocatable_space"])
        assertEquals(1L, counters["storage_hold_raw_collection_allocatable_space_unknown"])
        assertEquals(1L, counters["storage_hold_raw_collection_io_failure"])
        f.storage.failChunkWrites = false
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 3))
        // A merged rewrite also needs room for its second copy.
        f.allocatableBytes = 0L
        assertEquals(RawMetadataCaptureResult.CAPACITY_HOLD, f.capture(window = 4))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        assertEquals(1, f.payload(f.store.readyManifests(OWNER).single(), 0).getInt("window_count"))
    }

    @Test
    fun motionStateAggregateIsStoredAsClosedCountsAndMergedAcrossWindows() {
        val f = CoverageFixture()
        val motion = MotionStateSummary(
            stationaryMs = 12_000L,
            movingMs = 15_000L,
            unknownMs = 3_000L,
            snapshotCount = 30,
            unknownSnapshotCount = 3,
            transitionCount = 2,
            reasonCounts = mapOf(UserMotionStateReason.STEP_OBSERVED to 15, UserMotionStateReason.STATIONARY_CONFIRMED to 12),
            stationaryEntryLatencyHistogram = listOf(0, 0, 0, 1, 0, 0, 0),
            stationaryReleaseLatencyHistogram = listOf(0, 1, 0, 0, 0, 0, 0),
        )
        (0..1).forEach { window ->
            f.nowEpochMs = f.windowEnd(window)
            if (window > 0) f.elapsedMs += 30_000L
            assertEquals(
                RawMetadataCaptureResult.CAPTURED,
                f.coordinator.captureMetadataBatch(
                    f.context(WalkSessionState.ACTIVE),
                    f.detection(f.windowStart(window), f.windowEnd(window)),
                    f.performance(f.windowStart(window), f.windowEnd(window)).copy(motion = motion),
                ),
            )
        }
        f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED))
        val stored = f.payload(f.store.readyManifests(OWNER).single(), 1).getJSONObject("motion_state")
        assertEquals(24_000L, stored.getLong("stationary_ms"))
        assertEquals(30_000L, stored.getLong("moving_ms"))
        assertEquals(6, stored.getInt("unknown_snapshot_count"))
        assertEquals(4, stored.getInt("transition_count"))
        assertEquals(30, stored.getJSONObject("reason_counts").getInt("step_observed"))
        assertEquals(2, stored.getJSONObject("stationary_entry_latency_ms_histogram").getJSONArray("counts").getInt(3))
        assertEquals(
            setOf(
                "stationary_ms", "moving_ms", "unknown_ms", "snapshot_count", "unknown_snapshot_count",
                "transition_count", "reason_counts", "stationary_entry_latency_ms_histogram",
                "stationary_release_latency_ms_histogram",
            ),
            stored.keySet(),
        )
        val text = stored.toString()
        listOf("latitude", "longitude", "speed", "heading", "acceleration", "gyroscope").forEach { assertTrue(it, !text.contains(it)) }
    }

    @Test
    fun rawContinuedAcrossAReceiptChangeKeepsEarlierSegmentsWithTheirOriginalReceipt() {
        // 16-17 #14: automatic reporting changed, RAW stayed granted -> server-accepted continuity.
        val f = CoverageFixture()
        f.prepareOneReadySegment()
        val previous = f.confirmation()
        val next = f.supersedeConsent(receipt = "d".repeat(64))
        assertEquals(RawConsentSupersede.RAW_CONTINUED, rawConsentSupersede(previous, next, currentRawEffective = true))
        assertTrue(f.coordinator.onConsentContinued(previous.backendConsentReceiptSha256, next.backendConsentReceiptSha256))

        assertEquals(RawReadyStatus.Count(1), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        assertEquals(
            RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
            requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).execute(),
        )
        // The bound receipt of the segment is sent unchanged; nothing is re-stamped.
        assertEquals(listOf(CoverageFixture.CONSENT_SHA to CoverageFixture.CONSENT_SHA), f.client.sentReceipts)
    }

    @Test
    fun aContinuedActiveAggregateIsSealedUnderItsOwnReceiptInsteadOfDropped() {
        val f = CoverageFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 0))
        val next = f.supersedeConsent(receipt = "d".repeat(64))
        f.coordinator.onConsentContinued(CoverageFixture.CONSENT_SHA, next.backendConsentReceiptSha256)
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 1))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        assertEquals(
            listOf(CoverageFixture.CONSENT_SHA, "d".repeat(64)),
            f.store.readyManifests(OWNER).sortedBy { it.capturedStartedAtEpochMs }.map { it.consentReceiptSha256 },
        )
        assertEquals(RawReadyStatus.Count(2), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
    }

    @Test
    fun aRawWithdrawalAfterContinuityRevokesTheWholeChain() {
        val f = CoverageFixture()
        f.prepareOneReadySegment()
        val next = f.supersedeConsent(receipt = "d".repeat(64))
        f.coordinator.onConsentContinued(CoverageFixture.CONSENT_SHA, next.backendConsentReceiptSha256)
        assertTrue(f.coordinator.onConsentRevoked(next.backendConsentReceiptSha256))
        assertTrue(f.store.readyManifests(OWNER).isEmpty())
        assertEquals(RawReadyStatus.Count(0), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        assertNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
        // A revoked receipt cannot be continued again.
        assertTrue(!f.coordinator.onConsentContinued(CoverageFixture.CONSENT_SHA, "e".repeat(64)))
    }

    @Test
    fun withoutContinuityAnEarlierReceiptIsNotSelectable() {
        val f = CoverageFixture()
        f.prepareOneReadySegment()
        f.supersedeConsent(receipt = "d".repeat(64))
        assertEquals(RawReadyStatus.Count(0), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        assertNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
    }

    @Test
    fun supersedeIsContinuedOnlyForAnUnbrokenRawGrantUnderTheSameVersions() {
        val f = CoverageFixture()
        val previous = f.confirmation()
        val next = previous.copy(revision = 2L, clientRevision = 2L, backendConsentReceiptSha256 = "d".repeat(64))
        assertEquals(RawConsentSupersede.RAW_CONTINUED, rawConsentSupersede(previous, next, true))
        assertEquals(RawConsentSupersede.RAW_REVOKED, rawConsentSupersede(null, next, true))
        assertEquals(RawConsentSupersede.RAW_REVOKED, rawConsentSupersede(previous, next, currentRawEffective = false))
        assertEquals(
            RawConsentSupersede.RAW_REVOKED,
            rawConsentSupersede(previous, next.copy(selections = IntegratedConsentSelections(rawSourceCollection = false)), true),
        )
        assertEquals(
            RawConsentSupersede.RAW_REVOKED,
            rawConsentSupersede(previous.copy(selections = IntegratedConsentSelections(rawSourceCollection = false)), next, true),
        )
        assertEquals(RawConsentSupersede.RAW_REVOKED, rawConsentSupersede(previous, next.copy(revision = 1L), true))
        // S1617-14-F1: a skipped revision may hide a RAW withdrawal the server will see.
        assertEquals(RawConsentSupersede.RAW_REVOKED, rawConsentSupersede(previous, next.copy(revision = 3L), true))
        assertEquals(RawConsentSupersede.RAW_REVOKED, rawConsentSupersede(previous, next.copy(installationId = "device-installation-00000009"), true))
        assertEquals(RawConsentSupersede.RAW_REVOKED, rawConsentSupersede(previous, previous, true))
    }

    @Test
    fun continuityRecordsDoNotBlockAVerifiedNewEnrollment() {
        val f = CoverageFixture()
        f.prepareOneReadySegment()
        f.coordinator.onConsentContinued(CoverageFixture.CONSENT_SHA, "d".repeat(64))
        assertTrue(f.coordinator.onAccountDeleted())
        assertTrue(f.coordinator.resetForNewEnrollment(f.sessionForTest(), 7L, f.deviceCheck))
    }

    @Test
    fun wallClockRegressionIsNotRateLimitedWhenTheMonotonicClockAdvanced() {
        val f = CoverageFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 0))
        f.elapsedMs += 30_000L
        val regressed = f.windowEnd(0) - 10 * 60_000L
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            f.coordinator.captureMetadataBatch(
                f.context(WalkSessionState.ACTIVE),
                f.detection(regressed - 30_000L, regressed),
                f.performance(regressed - 30_000L, regressed),
            ),
        )
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        // The regression is a segment boundary: both windows are kept with consistent ranges.
        assertEquals(2, f.store.readyManifests(OWNER).size)
    }

    @Test
    fun deviceWallClockMovedBackKeepsTheEarlierSegmentAndTheNewWindow() {
        val f = CoverageFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 0))
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 1))
        f.elapsedMs += 30_000L
        val regressed = f.windowStart(0) - 60 * 60_000L
        f.nowEpochMs = regressed
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            f.coordinator.captureMetadataBatch(
                f.context(WalkSessionState.ACTIVE),
                f.detection(regressed - 30_000L, regressed),
                f.performance(regressed - 30_000L, regressed),
            ),
        )
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        f.nowEpochMs = f.windowEnd(1) + 1L
        val ready = f.store.readyManifests(OWNER)
        assertEquals(2, ready.size)
        assertEquals(setOf(2, 1), ready.map { f.payload(it, 0).getInt("window_count") }.toSet())
    }

    @Test
    fun sameBootMonotonicIntervalBelowTheMinimumIsRateLimitedEvenIfTheWallClockJumped() {
        val f = CoverageFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 0))
        f.elapsedMs += 10_000L
        val jumped = f.windowEnd(0) + 60 * 60_000L
        assertEquals(
            RawMetadataCaptureResult.RATE_LIMITED,
            f.coordinator.captureMetadataBatch(
                f.context(WalkSessionState.ACTIVE),
                f.detection(jumped - 30_000L, jumped),
                f.performance(jumped - 30_000L, jumped),
            ),
        )
        assertEquals(1L, f.coordinator.outcomeCountersSnapshot()["capture_rate_limited"])
    }

    @Test
    fun aNewProcessHasNoMonotonicReferenceAndKeepsThePreviousSegment() {
        val f = CoverageFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 0))
        val restarted = f.newCoordinator(elapsedAfterBoot = 1_000L)
        val earlier = f.windowEnd(0) - 5 * 60_000L
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            restarted.captureMetadataBatch(
                f.context(WalkSessionState.ACTIVE),
                f.detection(earlier - 30_000L, earlier),
                f.performance(earlier - 30_000L, earlier),
            ),
        )
        assertEquals(RawSegmentSealResult.SEALED, restarted.sealActiveSegment(f.context(WalkSessionState.ENDED)))
        assertEquals(2, f.store.readyManifests(OWNER).size)
    }

    @Test
    fun mismatchedCompletedInferenceCountsAreAnInvalidWindowNotAStorageFailure() {
        val f = CoverageFixture()
        assertEquals(
            RawMetadataCaptureResult.INVALID_WINDOW,
            f.coordinator.captureMetadataBatch(
                f.context(WalkSessionState.ACTIVE),
                f.detection(f.windowStart(0), f.windowEnd(0)),
                f.performance(f.windowStart(0), f.windowEnd(0)).copy(processedFrameCount = 29),
            ),
        )
        assertEquals(0, f.storage.collectionCount)
    }

    @Test
    fun payloadKeysAreTheFixedAggregateSetWithoutLocationTextOrIdentifiers() {
        val f = CoverageFixture()
        assertEquals(RawMetadataCaptureResult.CAPTURED, f.capture(window = 0))
        f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED))
        val manifest = f.store.readyManifests(OWNER).single()
        assertEquals(
            setOf(
                "schema_version", "window_started_at_epoch_ms", "captured_at_epoch_ms", "kind",
                "window_count", "completed_inference_count", "detection_count",
                "average_inference_ms", "max_inference_ms", "inference_ms_histogram",
                "detector_available", "model_revision",
                // 16-17 #6: the exact executed detector, as fixed hashes and the delegate path.
                "model_artifact_sha256", "runtime_config_sha256", "class_list_sha256",
                "active_delegate", "delegate_fallback_used",
                // S1617-18-F2: windows merged from another runtime (0 for one exact runtime).
                "boundary_window_count",
            ),
            f.payload(manifest, 0).keySet(),
        )
        assertEquals(
            setOf(
                "schema_version", "window_started_at_epoch_ms", "captured_at_epoch_ms", "kind",
                "window_count", "completed_inference_count", "busy_dropped_frame_count",
                "frame_interval_count", "average_frame_interval_ms", "max_frame_interval_ms",
                "frame_interval_ms_histogram", "thermal_throttled", "thermal_throttled_window_count",
                // ADD-MOTION-045: null when no motion state was observed in the window.
                "motion_state",
            ),
            f.payload(manifest, 1).keySet(),
        )
        assertEquals(
            listOf(33L, 66L, 100L, 200L, 400L, 800L),
            f.payload(manifest, 0).getJSONObject("inference_ms_histogram")
                .getJSONArray("upper_bounds_ms").let { array -> (0 until array.length()).map(array::getLong) },
        )
    }

    @Test
    fun readyStatusSeparatesBlockedGatesFromAnEmptyQueue() {
        val f = CoverageFixture()
        assertEquals(RawReadyStatus.Count(0), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.WALK_STATE),
            f.coordinator.readyStatus(f.context(WalkSessionState.ACTIVE)),
        )
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.CONSENT),
            f.coordinator.readyStatus(f.context(WalkSessionState.ENDED).copy(consentSession = IntegratedConsentSession())),
        )
        // Owner decision 3(b): a walk in progress, a still-held Gateway walk lease and a phone that
        // is not charging are gates, never an empty queue.
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.WALK_STATE),
            f.coordinator.readyStatus(f.context(WalkSessionState.PAUSED)),
        )
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.GATEWAY_WALK_LEASE),
            f.coordinator.readyStatus(
                f.context(WalkSessionState.ENDED).copy(
                    gatewayWalkLease = f.context(WalkSessionState.ACTIVE).gatewayWalkLease,
                ),
            ),
        )
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.CHARGING),
            f.coordinator.uploadStatus(f.context(WalkSessionState.ENDED).copy(charging = false)),
        )
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.OWNER),
            f.coordinator.readyStatus(
                f.context(WalkSessionState.ENDED).copy(
                    deviceCheck = f.deviceCheck.copy(state = PostLoginDeviceCheckState.FAIL),
                ),
            ),
        )
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.NETWORK),
            f.coordinator.uploadStatus(
                f.context(WalkSessionState.ENDED).copy(networkTransport = ActiveNetworkTransport.CELLULAR),
            ),
        )
        assertEquals(
            RawMetadataCaptureResult.GATE_BLOCKED,
            f.coordinator.captureMetadataBatch(
                f.context(WalkSessionState.ACTIVE).copy(consentSession = IntegratedConsentSession()),
                f.detection(f.windowStart(0), f.windowEnd(0)),
                f.performance(f.windowStart(0), f.windowEnd(0)),
            ),
        )
        assertEquals(1L, f.coordinator.outcomeCountersSnapshot()["capture_gate_consent"])
        assertEquals(1L, f.coordinator.outcomeCountersSnapshot()["capture_gate_blocked"])
    }

    @Test
    fun serverDisabledIngestStopsRetriesButKeepsTheSegment() {
        val f = CoverageFixture()
        f.prepareOneReadySegment()
        f.client.failure = RawCollectionHttpException(503, """{"detail":{"code":"raw_ingest_disabled","message":"x"}}""")
        val call = requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
        assertEquals(RawCollectionUploadOutcome.SERVER_DISABLED, call.execute())
        assertNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
        assertEquals(1, f.client.calls)
        assertEquals(
            RawReadyStatus.GateBlocked(RawCollectionGateReason.SERVER_DISABLED),
            f.coordinator.uploadStatus(f.context(WalkSessionState.ENDED)),
        )
        assertEquals(RawReadyStatus.Count(1), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        val counters = f.coordinator.outcomeCountersSnapshot()
        // One refused upload plus one upload not attempted afterwards.
        assertEquals(2L, counters["upload_server_disabled"])
        assertEquals(1L, counters["upload_gate_server_disabled"])
    }

    @Test
    fun otherServerUnavailableStaysRetryableAndConsentRejectionIsNamed() {
        val f = CoverageFixture()
        f.prepareOneReadySegment()
        f.client.failure = RawCollectionHttpException(503, """{"detail":{"code":"raw_ingest_storage_unavailable"}}""")
        assertEquals(
            RawCollectionUploadOutcome.RETRYABLE_FAILURE,
            requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).execute(),
        )
        // The retryable failure kept the segment for the next attempt.
        assertEquals(RawReadyStatus.Count(1), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        f.client.failure = RawCollectionHttpException(409, """{"detail":{"code":"raw_source_collection_consent_interrupted"}}""")
        assertEquals(
            RawCollectionUploadOutcome.CONSENT_REJECTED,
            requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).execute(),
        )
        // S1617-18-F1: a refused consent binding is never retried; the segment is deleted.
        assertEquals(RawReadyStatus.Count(0), f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)))
        assertEquals(0, f.storage.collectionCount)
        assertEquals(2L, f.coordinator.outcomeCountersSnapshot()["upload_failed"])
    }

    @Test
    fun expiredDiscardIsCountedInternally() {
        val f = CoverageFixture()
        f.prepareOneReadySegment()
        f.nowEpochMs += RAW_COLLECTION_TTL_MS + 1L
        assertEquals(1, f.coordinator.pruneExpired())
        assertEquals(1L, f.coordinator.outcomeCountersSnapshot()["expired_discard"])
    }

    /**
     * S1617-18-F2: the default is the production setting (no approved queue profile in any build),
     * so the core scenarios run exactly as shipped; multi-segment scenarios opt into
     * [TEST_CAPACITY_PROFILE] explicitly.
     */
    private class CoverageFixture(
        capacityProfile: ApprovedRawQueueCapacityProfile? = null,
    ) {
        var nowEpochMs = BASE_EPOCH_MS
        var elapsedMs = 1_000_000L
        var allocatableBytes: Long? = Long.MAX_VALUE
        val storage = CoverageFakeStorage()
        val aead = CoverageFakeAead()
        val store = RawCollectionStore(storage, aead, { nowEpochMs }, testOnly = Unit, capacityProfile = capacityProfile)
        val client = CoverageFakeClient()
        private var nextId = 0
        private val idFactory: () -> UUID = { UUID.nameUUIDFromBytes("collection-${nextId++}".toByteArray()) }
        val coordinator = newCoordinator()
        val deviceCheck = PostLoginDeviceCheckSnapshot(
            state = PostLoginDeviceCheckState.FULL,
            actorId = ACTOR_ID,
            sessionGeneration = 7L,
            attemptGeneration = 1L,
        )
        val consentSession = IntegratedConsentSession().also { assertTrue(it.accept(confirmation())) }

        /** A newer server confirmation with its own receipt (e.g. only automatic reporting changed). */
        fun supersedeConsent(receipt: String, raw: Boolean = true, revision: Long = 2L): IntegratedConsentConfirmation {
            val next = confirmation().copy(
                revision = revision,
                clientRevision = revision,
                backendConsentReceiptSha256 = receipt,
                selections = IntegratedConsentSelections(rawSourceCollection = raw, automaticReporting = revision % 2L == 0L),
            )
            assertTrue(consentSession.accept(next))
            return next
        }
        private val session = GatewayFieldSession.backendAccountDeviceSession(
            gatewayBaseUrl = "http://127.0.0.1:8081",
            binding = BackendAccountDeviceCookieBinding(
                actorId = ACTOR_ID,
                accountGeneration = 1L,
                authEpoch = 1L,
                deviceId = DEVICE_ID,
                sessionId = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
            ),
            cookiePair = "${GatewayFieldSession.COOKIE_NAME}=test-v7-cookie",
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
        )
        private val epoch = WalkRuntimeEpoch(WALK_ID, recoveryGeneration = 0L)
        private val binding = IntegratedConsentNetworkBinding.forTest(IntegratedConsentNetworkTransport.WIFI) { url ->
            url.openConnection()
        }

        fun newCoordinator(elapsedAfterBoot: Long? = null): RawCollectionRuntimeCoordinator {
            if (elapsedAfterBoot != null) elapsedMs = elapsedAfterBoot
            return RawCollectionRuntimeCoordinator(
                store = store,
                client = client,
                idFactory = idFactory,
                elapsedRealtimeMs = { elapsedMs },
                minimumCaptureIntervalMs = 30_000L,
                testOnly = Unit,
                allocatableBytes = { allocatableBytes },
            )
        }

        fun windowStart(window: Int) = BASE_EPOCH_MS - 30_000L + window * 30_000L
        fun windowEnd(window: Int) = BASE_EPOCH_MS + window * 30_000L

        fun detection(
            start: Long,
            end: Long,
            inferenceMs: Long = 30L,
            maxMs: Long = 50L,
            model: String = "model-v1",
            identity: RawDetectorRuntimeIdentity? = RUNTIME_IDENTITY,
        ) =
            RawDetectionMetadataSample(
                windowStartedAtEpochMs = start,
                capturedAtEpochMs = end,
                processedFrameCount = 30,
                detectionCount = 3,
                averageInferenceMs = inferenceMs,
                detectorAvailable = true,
                modelRevision = model,
                maxInferenceMs = maxMs,
                // 29 fast inferences plus one at the maximum.
                inferenceMsHistogram = MutableList(RAW_RUNTIME_LATENCY_BUCKET_COUNT) { 0 }.also {
                    it[0] += 29
                    it[rawRuntimeLatencyBucket(maxMs)] += 1
                },
                runtimeIdentity = identity,
            )

        fun performance(start: Long, end: Long, thermal: Boolean? = false) = RawPerformanceMetadataSample(
            windowStartedAtEpochMs = start,
            capturedAtEpochMs = end,
            processedFrameCount = 30,
            droppedFrameCount = 1,
            averageFrameDurationMs = 100L,
            thermalThrottled = thermal,
            maxFrameDurationMs = 150L,
            frameDurationMsHistogram = listOf(0, 0, 10, 19, 0, 0, 0),
            frameDurationSampleCount = 29,
        )

        fun capture(
            window: Int,
            inferenceMs: Long = 30L,
            maxMs: Long = 50L,
            thermal: Boolean? = false,
            model: String = "model-v1",
            identity: RawDetectorRuntimeIdentity? = RUNTIME_IDENTITY,
        ): RawMetadataCaptureResult {
            nowEpochMs = windowEnd(window)
            if (window > 0) elapsedMs += 30_000L
            return coordinator.captureMetadataBatch(
                context(WalkSessionState.ACTIVE),
                detection(windowStart(window), windowEnd(window), inferenceMs, maxMs, model, identity),
                performance(windowStart(window), windowEnd(window), thermal),
            )
        }

        fun sessionForTest(): GatewayFieldSession = session

        fun prepareOneReadySegment() {
            assertEquals(RawMetadataCaptureResult.CAPTURED, capture(window = 0))
            assertEquals(RawSegmentSealResult.SEALED, coordinator.sealActiveSegment(context(WalkSessionState.ENDED)))
        }

        fun payload(manifest: RawCollectionManifest, ordinal: Int): JSONObject {
            val chunk = store.readUploadChunk(manifest.collectionId, OWNER, ordinal)
            assertNotNull(chunk)
            return JSONObject(String(requireNotNull(chunk).plaintext, Charsets.UTF_8))
        }

        fun context(state: WalkSessionState): RawCollectionRuntimeContext = RawCollectionRuntimeContext(
            session = session,
            sessionGeneration = 7L,
            deviceCheck = deviceCheck,
            consentSession = consentSession,
            walk = when (state) {
                WalkSessionState.ACTIVE -> coverageActiveWalk(epoch)
                WalkSessionState.PAUSED -> WalkSessionSnapshot(
                    epoch = epoch,
                    state = WalkSessionState.PAUSED,
                    mode = WalkSessionMode.FULL,
                    recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED,
                    isForeground = true,
                    readiness = null,
                )
                // Owner decision 3(b): after the walk (ENDED) the post-walk upload runs.
                WalkSessionState.ENDED -> WalkSessionSnapshot(
                    epoch = epoch,
                    state = WalkSessionState.ENDED,
                    mode = WalkSessionMode.UNAVAILABLE,
                    recoveryStage = null,
                    isForeground = true,
                    readiness = null,
                )
                else -> error("unsupported")
            },
            gatewayWalkLease = if (state == WalkSessionState.ENDED) null else GatewayWalkLease(
                result = "ACQUIRED",
                actorId = ACTOR_ID,
                deviceId = DEVICE_ID,
                walkId = WALK_ID,
                leaseId = "lease-id",
                fencingToken = 1L,
                acquiredAtEpochMs = BASE_EPOCH_MS,
                leaseExpiresAtEpochMs = BASE_EPOCH_MS + 90_000L,
                serverTimeEpochMs = BASE_EPOCH_MS,
                localDeadlineElapsedMs = Long.MAX_VALUE / 4,
            ),
            networkTransport = ActiveNetworkTransport.WIFI,
            networkBinding = binding,
            charging = true,
        )

        fun confirmation() = IntegratedConsentConfirmation(
            schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
            policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
            installationId = DEVICE_ID,
            requestId = "request_id_1234567890",
            itemVersions = IntegratedConsentItemVersions(),
            clientRevision = 1L,
            revision = 1L,
            selections = IntegratedConsentSelections(rawSourceCollection = true),
            confirmedAt = "2026-08-29T00:00:00.000Z",
            gatewayAuditRecordSha256 = "a".repeat(64),
            backendConsentReceiptSha256 = CONSENT_SHA,
            controlSecret = "b".repeat(64),
        )

        companion object {
            const val WALK_ID = "123e4567-e89b-42d3-a456-426614174001"
            const val ACTOR_ID = "123e4567-e89b-42d3-a456-426614174002"
            const val DEVICE_ID = "device-installation-00000001"
            const val BASE_EPOCH_MS = 1_800_000_000_000L
            val CONSENT_SHA = "c".repeat(64)
            /** Test-only limits large enough for the multi-segment scenarios; not product values. */
            val TEST_CAPACITY_PROFILE = ApprovedRawQueueCapacityProfile(
                maxSegments = 16,
                maxTotalBytes = 16L * RAW_MAX_COLLECTION_BYTES,
            )
            val RUNTIME_IDENTITY = RawDetectorRuntimeIdentity(
                modelArtifactSha256 = "1".repeat(64),
                runtimeConfigSha256 = "2".repeat(64),
                classListSha256 = "3".repeat(64),
                activeDelegate = "gpu",
                delegateFallbackUsed = false,
            )
        }
    }

    private companion object {
        val OWNER = RawCollectionOwner(CoverageFixture.ACTOR_ID, 1L, CoverageFixture.DEVICE_ID)
    }


    private class CoverageFakeClient : RawCollectionNetworkClient {
        var calls = 0
        var failure: RawCollectionHttpException? = null
        val sentReceipts = mutableListOf<Pair<String, String>>()

        override fun uploadCall(
            session: GatewayFieldSession,
            consent: IntegratedConsentConfirmation,
            networkBinding: IntegratedConsentNetworkBinding,
            localManifest: RawCollectionManifest,
            backendManifest: BackendRawManifest,
            chunks: List<RawPlaintextChunk>,
            isCurrent: () -> Boolean,
        ): CancellableNetworkCall<RawCollectionReceipt> {
            calls += 1
            sentReceipts += consent.backendConsentReceiptSha256 to localManifest.consentReceiptSha256
            return CancellableNetworkCall.blocking {
                failure?.let { throw it }
                val unsigned = RawCollectionReceipt(
                    schemaVersion = BACKEND_RECEIPT_SCHEMA,
                    collectionId = backendManifest.collectionId,
                    manifestSha256 = backendManifest.manifestSha256,
                    purpose = BACKEND_RAW_PURPOSE,
                    persistenceMarker = RAW_RECEIPT_PERSISTENCE_MARKER,
                    objectCount = backendManifest.objects.size,
                    chunkCount = backendManifest.chunkCount,
                    totalBytes = backendManifest.totalBytes,
                    objects = backendManifest.objects,
                    retentionClass = BACKEND_RETENTION_CLASS,
                    committedAt = "2026-08-29T00:00:00Z",
                    quarantineExpiresAt = "2026-09-12T00:00:00Z",
                    receiptSha256 = "0".repeat(64),
                )
                unsigned.copy(receiptSha256 = rawReceiptSha256(unsigned))
            }
        }
    }

    private class CoverageFakeStorage : RawCollectionStorage {
        private var activeId: String? = null
        private val manifests = mutableMapOf<String, String>()
        private val chunks = mutableMapOf<Pair<String, Int>, String>()
        private val fences = mutableMapOf<String, String>()
        val collectionCount: Int
            get() = manifests.size
        /** RAW-CORE-F1 fault injection: ENOSPC/IO failure on chunk or pointer writes. */
        var failChunkWrites = false
        var failPointerWrites = false

        override fun readActiveCollectionId(): String? = activeId
        override fun writeActiveCollectionIdAtomically(collectionId: String): Boolean {
            if (failPointerWrites) return false
            activeId = collectionId
            return true
        }
        override fun clearActiveCollectionId(): Boolean {
            activeId = null
            return true
        }
        override fun readManifest(collectionId: String): String? = manifests[collectionId]
        override fun writeManifestAtomically(collectionId: String, envelope: String): Boolean {
            manifests[collectionId] = envelope
            return true
        }
        override fun readChunk(collectionId: String, ordinal: Int): String? = chunks[collectionId to ordinal]
        override fun writeChunkAtomically(collectionId: String, ordinal: Int, envelope: String): Boolean {
            if (failChunkWrites) return false
            chunks[collectionId to ordinal] = envelope
            return true
        }
        override fun deleteChunk(collectionId: String, ordinal: Int): Boolean =
            chunks.remove(collectionId to ordinal) != null
        override fun listCollectionIds(): Set<String> = manifests.keys.toSet()
        override fun deleteCollection(collectionId: String): Boolean {
            manifests.remove(collectionId)
            chunks.keys.removeAll { it.first == collectionId }
            return true
        }
        override fun deleteAllCollections(): Boolean {
            manifests.clear()
            chunks.clear()
            return true
        }
        override fun verifiedEmptyAfterAccountPurge(): Boolean = manifests.isEmpty() && chunks.isEmpty()
        override fun hasFence(name: String): Boolean = name in fences
        override fun readFence(name: String): String? = fences[name]
        override fun writeFenceAtomically(name: String, value: String): Boolean {
            fences[name] = value
            return true
        }
        override fun listFenceNames(): Set<String> = fences.keys
        override fun clearAllFences(): Boolean {
            fences.clear()
            return true
        }
        override fun measureUsage(): RawStorageUsage = RawStorageUsage(
            liveCollections = manifests.size,
            quarantinedCollections = 0,
            totalBytes = (manifests.values + chunks.values).sumOf { it.length.toLong() },
        )
    }

    private class CoverageFakeAead : LocalAead {
        private val values = mutableMapOf<String, ByteArray>()
        private var ordinal = 0

        override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits): AeadSealResult {
            val envelope = "coverage-envelope-${ordinal++}"
            values[envelope] = plaintext.copyOf()
            return AeadSealResult.Sealed(envelope)
        }
        override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
            values[envelope]?.copyOf()?.let { AeadOpenResult.Opened(it, 1, false) }
                ?: AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
        override fun destroyVersion(version: Int): Boolean = true
        override fun destroyKnownVersions(): Boolean {
            values.clear()
            return true
        }
        override fun createFreshAfterVerifiedPurge(): Boolean = true
    }

    private fun JSONObject.keySet(): Set<String> = buildSet { keys().forEach(::add) }
}

private fun coverageActiveWalk(epoch: WalkRuntimeEpoch): WalkSessionSnapshot {
    val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
    val observations = WalkSessionReadinessRequirement.entries.associateWith { requirement ->
        WalkSessionReadinessObservation(
            epoch = epoch,
            requirement = requirement,
            status = if (plan.requires(requirement)) {
                WalkSessionReadinessStatus.READY
            } else {
                WalkSessionReadinessStatus.NOT_REQUIRED
            },
        )
    }
    return WalkSessionSnapshot(
        epoch = epoch,
        state = WalkSessionState.ACTIVE,
        mode = WalkSessionMode.FULL,
        recoveryStage = null,
        isForeground = true,
        readiness = WalkSessionReadinessSnapshot(epoch, plan, 1L, observations),
    )
}
