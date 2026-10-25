package kr.co.hanium.dreamup.walksafe.rawcollection

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
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
import kr.co.hanium.dreamup.walksafe.session.WalkSessionMode
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessObservation
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessPlan
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessRequirement
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionRecoveryStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RawCollectionRuntimeCoordinatorTest {
    @Test
    fun captureNeedsV7DeviceConsentAndActiveWalkThenSealsOnlyAfterTheWalk() {
        val fixture = fixture()
        val sample = detectionSample()

        assertEquals(
            RawMetadataCaptureResult.GATE_BLOCKED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE).copy(
                    deviceCheck = fixture.deviceCheck.copy(state = PostLoginDeviceCheckState.FAIL),
                ),
                sample,
                performanceSample(),
            ),
        )
        assertEquals(
            RawMetadataCaptureResult.RATE_LIMITED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                sample.copy(windowStartedAtEpochMs = NOW_EPOCH_MS - 1_000L),
                performanceSample().copy(windowStartedAtEpochMs = NOW_EPOCH_MS - 1_000L),
            ),
        )
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                sample,
                performanceSample(),
            ),
        )
        assertEquals(
            RawSegmentSealResult.GATE_BLOCKED,
            fixture.coordinator.sealActiveSegment(fixture.context(WalkSessionState.ACTIVE)),
        )
        // Owner decision 3(b): PAUSED is still the walk.
        assertEquals(
            RawSegmentSealResult.GATE_BLOCKED,
            fixture.coordinator.sealActiveSegment(fixture.context(WalkSessionState.PAUSED)),
        )
        assertEquals(
            RawSegmentSealResult.SEALED,
            fixture.coordinator.sealActiveSegment(fixture.context(WalkSessionState.ENDED)),
        )
        assertEquals(RawReadyStatus.Count(1), fixture.coordinator.readyStatus(fixture.context(WalkSessionState.ENDED)))
        assertNull(fixture.coordinator.startNextUpload { fixture.context(WalkSessionState.ACTIVE) })
        assertNull(fixture.coordinator.startNextUpload { fixture.context(WalkSessionState.PAUSED) })
    }

    @Test
    fun postWalkWifiUploadIsSingleFlightAndDeletesOnlyAfterExactReceipt() {
        val fixture = fixture()
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(thermalThrottled = null),
            ),
        )
        assertEquals(
            RawSegmentSealResult.SEALED,
            fixture.coordinator.sealActiveSegment(fixture.context(WalkSessionState.ENDED)),
        )
        assertNull(
            fixture.coordinator.startNextUpload {
                fixture.context(WalkSessionState.ENDED).copy(
                    networkTransport = ActiveNetworkTransport.CELLULAR,
                )
            },
        )

        val call = fixture.coordinator.startNextUpload {
            fixture.context(WalkSessionState.ENDED)
        }
        assertNotNull(call)
        assertNull(
            fixture.coordinator.startNextUpload {
                fixture.context(WalkSessionState.ENDED)
            },
        )
        assertEquals(
            RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
            requireNotNull(call).execute(),
        )
        assertEquals(1, fixture.client.calls)
        assertEquals(2, fixture.client.lastPlaintexts.size)
        assertEquals(
            listOf(0, 1),
            fixture.client.lastChunkMetadata.map(RawChunkMetadata::ordinal),
        )
        assertEquals(
            listOf(RawChunkType.DETECTION, RawChunkType.PERFORMANCE),
            fixture.client.lastChunkMetadata.map(RawChunkMetadata::type),
        )
        assertEquals(2, fixture.client.lastPayloadSnapshots.size)
        assertFalse(
            fixture.client.lastPayloadSnapshots[0]
                .contentEquals(fixture.client.lastPayloadSnapshots[1]),
        )
        assertFalse(
            fixture.client.lastChunkMetadata[0].sha256 ==
                fixture.client.lastChunkMetadata[1].sha256,
        )
        assertEquals(
            fixture.client.lastChunkMetadata.map(RawChunkMetadata::sha256),
            fixture.client.lastPayloadSnapshots.map(::sha256Hex),
        )
        assertTrue(
            fixture.client.lastPlaintexts.all { plaintext ->
                plaintext.all { it == 0.toByte() }
            },
        )
        assertEquals(RawReadyStatus.Count(0), fixture.coordinator.readyStatus(fixture.context(WalkSessionState.ENDED)))
    }

    @Test
    fun walkEndKeepsTheSegmentForThePostWalkUploadAndANewActiveWalkCancelsIt() {
        val fixture = fixture()
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(),
            ),
        )
        // S1617-13 / owner decision 3(b): the ended walk's segment is sealed, not discarded.
        assertEquals(
            RawSegmentSealResult.SEALED,
            fixture.coordinator.onWalkEnded(fixture.context(WalkSessionState.ENDED)),
        )
        assertEquals(0, fixture.coordinator.discardEndedPartials(fixture.context(WalkSessionState.ENDED)))
        assertEquals(RawReadyStatus.Count(1), fixture.coordinator.readyStatus(fixture.context(WalkSessionState.ENDED)))
        assertNotNull(fixture.coordinator.startNextUpload { fixture.context(WalkSessionState.ENDED) })

        val uploadFixture = fixture()
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            uploadFixture.coordinator.captureMetadataBatch(
                uploadFixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(),
            ),
        )
        assertEquals(
            RawSegmentSealResult.SEALED,
            uploadFixture.coordinator.sealActiveSegment(
                uploadFixture.context(WalkSessionState.ENDED),
            ),
        )
        val call = requireNotNull(
            uploadFixture.coordinator.startNextUpload {
                uploadFixture.context(WalkSessionState.ENDED)
            },
        )
        assertFalse(
            uploadFixture.coordinator.revalidate(
                uploadFixture.context(WalkSessionState.ACTIVE),
            ),
        )
        assertTrue(call.isCancelled())
        assertEquals(RawReadyStatus.Count(1), uploadFixture.coordinator.readyStatus(uploadFixture.context(WalkSessionState.ENDED)))
    }

    @Test
    fun failedSecondBatchWriteDiscardsPartialBeforeTheNextCapture() {
        val fixture = fixture()
        fixture.storage.failChunkOrdinal = 1

        assertEquals(
            RawMetadataCaptureResult.STORAGE_FAILURE,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(),
            ),
        )
        assertNull(fixture.storage.activeCollectionId)
        assertEquals(0, fixture.storage.collectionCount)

        fixture.storage.failChunkOrdinal = null
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(),
            ),
        )
    }

    @Test
    fun mismatchedBatchWindowIsRejectedBeforeOpeningACollection() {
        val fixture = fixture()

        assertEquals(
            RawMetadataCaptureResult.INVALID_WINDOW,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample().copy(capturedAtEpochMs = NOW_EPOCH_MS - 1L),
            ),
        )
        assertNull(fixture.storage.activeCollectionId)
        assertEquals(0, fixture.storage.collectionCount)
    }

    @Test
    fun executorDelayAfterObservationDoesNotRejectTheCapturedBatch() {
        val fixture = fixture(storeNowEpochMs = NOW_EPOCH_MS + 1_000L)

        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(),
            ),
        )
        assertEquals(
            RawSegmentSealResult.SEALED,
            fixture.coordinator.sealActiveSegment(fixture.context(WalkSessionState.ENDED)),
        )
        val ready = requireNotNull(fixture.store.readyManifests(OWNER).singleOrNull())
        assertEquals(
            30_000L,
            requireNotNull(ready.capturedEndedAtEpochMs) - ready.capturedStartedAtEpochMs,
        )
    }

    @Test
    fun staleConsentEmptyPartialIsDiscardedBeforeCapturingTheCurrentBatch() {
        val fixture = fixture()
        assertTrue(
            fixture.store.open(
                collectionId = COLLECTION_ID,
                owner = OWNER,
                walkSessionId = WALK_ID,
                consentReceiptSha256 = "d".repeat(64),
                capturedStartedAtEpochMs = NOW_EPOCH_MS - 30_000L,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
            ),
        )

        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(),
            ),
        )
        assertEquals(
            CONSENT_SHA,
            fixture.store.activePartialManifest(OWNER, WALK_ID)?.consentReceiptSha256,
        )
    }

    @Test
    fun walkEndSealDiscardsAnIncompletePartialCollection() {
        val fixture = fixture()
        assertTrue(
            fixture.store.open(
                collectionId = COLLECTION_ID,
                owner = OWNER,
                walkSessionId = WALK_ID,
                consentReceiptSha256 = CONSENT_SHA,
                capturedStartedAtEpochMs = NOW_EPOCH_MS - 30_000L,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
            ),
        )

        assertEquals(
            RawSegmentSealResult.STORAGE_FAILURE,
            fixture.coordinator.sealActiveSegment(fixture.context(WalkSessionState.ENDED)),
        )
        assertNull(fixture.storage.activeCollectionId)
        assertEquals(0, fixture.storage.collectionCount)
    }

    @Test
    fun walkEndSealDiscardsALegacySingleChunkPartialCollection() {
        val fixture = fixture()
        seedLegacySingleChunkPartial(fixture)

        assertEquals(
            RawSegmentSealResult.STORAGE_FAILURE,
            fixture.coordinator.sealActiveSegment(fixture.context(WalkSessionState.ENDED)),
        )
        assertNull(fixture.storage.activeCollectionId)
        assertEquals(0, fixture.storage.collectionCount)
    }

    @Test
    fun captureDiscardsLegacySingleChunkPartialBeforeWritingTheExactBatch() {
        val fixture = fixture()
        seedLegacySingleChunkPartial(fixture, LEGACY_COLLECTION_ID)
        assertEquals(
            listOf(RawChunkType.DETECTION),
            fixture.store.activePartialManifest(OWNER, WALK_ID)?.chunks?.map(RawChunkMetadata::type),
        )

        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(),
            ),
        )

        assertNull(fixture.storage.readManifest(LEGACY_COLLECTION_ID))
        assertEquals(COLLECTION_ID, fixture.storage.activeCollectionId)
        val replacement = requireNotNull(fixture.store.activePartialManifest(OWNER, WALK_ID))
        assertEquals(listOf(0, 1), replacement.chunks.map(RawChunkMetadata::ordinal))
        assertEquals(
            listOf(RawChunkType.DETECTION, RawChunkType.PERFORMANCE),
            replacement.chunks.map(RawChunkMetadata::type),
        )
    }

    private fun seedLegacySingleChunkPartial(
        fixture: RuntimeFixture,
        collectionId: String = COLLECTION_ID,
    ) {
        val legacyPartial = RawCollectionManifest(
            collectionId = collectionId,
            owner = OWNER,
            walkSessionId = WALK_ID,
            consentReceiptSha256 = CONSENT_SHA,
            capturedStartedAtEpochMs = NOW_EPOCH_MS - 30_000L,
            capturedEndedAtEpochMs = null,
            expiresAtEpochMs = NOW_EPOCH_MS - 30_000L + RAW_COLLECTION_TTL_MS,
            state = RawManifestState.PARTIAL,
            chunks = listOf(
                RawChunkMetadata(
                    ordinal = 0,
                    type = RawChunkType.DETECTION,
                    capturedAtEpochMs = NOW_EPOCH_MS,
                    sizeBytes = 1,
                    sha256 = "a".repeat(64),
                ),
            ),
        )
        val plaintext = encodeRawManifest(legacyPartial)
        val envelope = try {
            requireNotNull(
                fixture.aead.seal(
                    plaintext,
                    byteArrayOf(),
                    AeadLimits(1_024 * 1_024, 1_024 * 1_024 + 16, 2 * 1_024 * 1_024),
                ) as? AeadSealResult.Sealed,
            ).envelope
        } finally {
            plaintext.fill(0)
        }
        assertTrue(fixture.storage.writeManifestAtomically(collectionId, envelope))
        assertTrue(fixture.storage.writeActiveCollectionIdAtomically(collectionId))
    }

    @Test
    fun unstartedCancellationReleasesSlotWithoutReadingPlaintext() {
        val f = fixture()
        prepareUpload(f)
        val call = requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
        call.cancel()
        assertThrows(CancellationException::class.java) { call.execute() }
        assertEquals(0, f.client.calls)
        requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).cancel()
    }

    @Test
    fun executingCancellationRetainsSlotAndLateReceiptCannotDelete() {
        val f = fixture()
        prepareUpload(f)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.client.beforeReturn = {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }
        val call = requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<RawCollectionUploadOutcome> { call.execute() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            call.cancel()
            assertNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
            release.countDown()
            assertEquals(RawCollectionUploadOutcome.CANCELLED, result.get(3, TimeUnit.SECONDS))
            assertEquals(1, f.store.readyManifests(OWNER).size)
            assertTrue(f.client.lastPlaintexts.all { bytes -> bytes.all { it == 0.toByte() } })
            requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).cancel()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun exactReceiptWithDeletionFailureRetriesOnlyLocalPurge() {
        val f = fixture()
        prepareUpload(f)
        f.storage.failDelete = true
        assertEquals(RawCollectionUploadOutcome.ACKNOWLEDGED_PURGE_PENDING,
            requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).execute())
        assertEquals(RawUploadDisposition.ACKNOWLEDGED, f.store.readyManifests(OWNER).single().uploadDisposition)
        f.storage.failDelete = false
        assertEquals(RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
            requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).execute())
        assertEquals(1, f.client.calls)
        assertEquals(0, f.storage.collectionCount)
    }

    @Test
    fun permanentFailureKeepsBytesButRemovesHeadFromUploadCandidates() {
        val f = fixture()
        prepareUpload(f)
        f.client.httpFailure = 422
        assertEquals(RawCollectionUploadOutcome.QUARANTINED,
            requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).execute())
        assertEquals(1, f.storage.collectionCount)
        assertEquals(0, f.store.readyManifests(OWNER).size)
        assertNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
    }

    @Test
    fun authorityFailurePreservesRetryableRawSegment() {
        val f = fixture()
        prepareUpload(f)
        f.client.httpFailure = 403
        assertEquals(RawCollectionUploadOutcome.AUTHORITY_BLOCKED,
            requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).execute())
        assertEquals(1, f.store.readyManifests(OWNER).size)
    }

    @Test
    fun expiredFullPartialDoesNotPermanentlyBlockFutureCapture() {
        var now = NOW_EPOCH_MS
        val f = fixture(clock = { now })
        assertEquals(RawMetadataCaptureResult.CAPTURED,
            f.coordinator.captureMetadataBatch(f.context(WalkSessionState.ACTIVE), detectionSample(), performanceSample()))
        now += RAW_COLLECTION_TTL_MS + 1L
        assertEquals(RawMetadataCaptureResult.CAPTURED,
            f.coordinator.captureMetadataBatch(
                f.context(WalkSessionState.ACTIVE),
                detectionSample().copy(windowStartedAtEpochMs = now - 30_000L, capturedAtEpochMs = now),
                performanceSample().copy(windowStartedAtEpochMs = now - 30_000L, capturedAtEpochMs = now),
                // The monotonic clock advances with the wall clock in this scenario.
                observedAtElapsedRealtimeMs = NOW_ELAPSED_MS + RAW_COLLECTION_TTL_MS + 1L,
            ))
    }

    @Test
    fun unrelatedWalkEndAndConsentRevocationCannotCancelCurrentUpload() {
        val f = fixture()
        prepareUpload(f)
        val call = requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
        val ended = f.context(WalkSessionState.ENDED)
        f.coordinator.onWalkEnded(ended.copy(walk = ended.walk.copy(
            epoch = WalkRuntimeEpoch(LEGACY_COLLECTION_ID, 0L),
        )))
        f.coordinator.onConsentRevoked("d".repeat(64))
        assertFalse(call.isCancelled())
        assertEquals(RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT, call.execute())
    }

    @Test
    fun sessionGenerationChangeBeforeExecuteNeverOpensRawClient() {
        val f = fixture()
        prepareUpload(f)
        var current = f.context(WalkSessionState.ENDED)
        val call = requireNotNull(f.coordinator.startNextUpload { current })
        current = current.copy(sessionGeneration = current.sessionGeneration + 1L)
        assertEquals(RawCollectionUploadOutcome.CANCELLED, call.execute())
        assertEquals(0, f.client.calls)
        assertEquals(1, f.store.readyManifests(OWNER).size)
    }

    @Test fun discardFailureKeepsBothConsentAndWalkRejectionsAsLocalFailure() {
        for (code in listOf("raw_source_collection_consent_required", "raw_collection_walk_not_uploadable")) {
            val f = fixture(); prepareUpload(f)
            f.storage.failDelete = true
            f.client.httpFailure = 409
            f.client.errorBody = "{\"detail\":{\"code\":\"$code\"}}"
            assertEquals(RawCollectionUploadOutcome.LOCAL_READ_FAILED,
                requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).execute())
            assertEquals(1, f.storage.collectionCount)
            assertTrue(f.client.lastPlaintexts.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    @Test fun unexpectedClientFactoryFailureReleasesUploadAndErasesPlaintextForRetry() {
        val f = fixture(); prepareUpload(f); f.client.factoryFailure = true
        assertEquals(RawCollectionUploadOutcome.RETRYABLE_FAILURE,
            requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).execute())
        assertEquals(1, f.storage.collectionCount)
        assertTrue(f.client.lastPlaintexts.all { bytes -> bytes.all { it == 0.toByte() } })
        f.client.factoryFailure = false
        assertNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
        f.coordinator.cancelActiveUpload()
    }

    @Test fun unexpectedLocalDecryptFailureIsLocalFailureAndDoesNotCallTransport() {
        val f = fixture(); prepareUpload(f); f.aead.throwOnOpen = true
        assertEquals(RawCollectionUploadOutcome.LOCAL_READ_FAILED,
            requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).execute())
        assertEquals(0, f.client.calls)
        assertEquals(1, f.storage.collectionCount)
    }

    @Test fun retryAfterHoldsOwnerUntilMonotonicDeadlineWithoutDeletingSegment() {
        var elapsed = NOW_ELAPSED_MS
        val f = fixture(elapsedClock = { elapsed }); prepareUpload(f)
        f.client.httpFailure = 429; f.client.retryAfter = "120"
        assertEquals(RawCollectionUploadOutcome.RETRYABLE_FAILURE,
            requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).execute())
        assertEquals(120_000L, f.coordinator.nextUploadRetryDelayMs(f.context(WalkSessionState.ENDED)))
        assertNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
        assertEquals(RawReadyStatus.GateBlocked(RawCollectionGateReason.RETRY_DELAY),
            f.coordinator.uploadStatus(f.context(WalkSessionState.ENDED)))
        elapsed += 119_999L
        assertNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
        elapsed += 1L; f.client.httpFailure = null
        assertEquals(1, f.storage.collectionCount)
        assertEquals(RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
            requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).execute())
    }

    @Test fun missingOrDifferentSnapshotIdentityCannotCreateRawSegment() {
        val f = fixture()
        for (sample in listOf(detectionSample().copy(windowIdentity = null),
            detectionSample().copy(windowIdentity = RawRuntimeWindowIdentity()))) {
            assertEquals(RawMetadataCaptureResult.INVALID_WINDOW,
                f.coordinator.captureMetadataBatch(f.context(WalkSessionState.ACTIVE), sample, performanceSample()))
        }
        assertEquals(0, f.storage.collectionCount)
    }

    @Test fun deletionDeadlineIncludesAStalledTransportCancellationCallback() {
        val f = fixture(); prepareUpload(f)
        val entered = CountDownLatch(1); val releaseUpload = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1); val releaseCancel = CountDownLatch(1)
        f.client.beforeReturn = { entered.countDown(); check(releaseUpload.await(3L, TimeUnit.SECONDS)) }
        f.client.onCancel = { cancelEntered.countDown(); check(releaseCancel.await(3L, TimeUnit.SECONDS)) }
        val call = requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
        val worker = Executors.newFixedThreadPool(2)
        try {
            val upload = worker.submit<RawCollectionUploadOutcome> { call.execute() }
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            val elapsed = AtomicLong()
            val deletion = worker.submit<Boolean> {
                val started = System.nanoTime()
                f.coordinator.onAccountDeleted(timeoutMs = 100L).also { elapsed.set(System.nanoTime() - started) }
            }
            assertTrue(cancelEntered.await(350L, TimeUnit.MILLISECONDS))
            assertFalse(deletion.get(350L, TimeUnit.MILLISECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(elapsed.get()) < 350L)
            println("RAW stalled cancel termination_ms=" + TimeUnit.NANOSECONDS.toMillis(elapsed.get()))
            assertTrue(call.isCancelled())
            assertEquals(0, f.storage.purgeCalls)
            assertTrue(f.client.lastPlaintexts.any { bytes -> bytes.any { it != 0.toByte() } })
            releaseCancel.countDown(); releaseUpload.countDown()
            assertEquals(RawCollectionUploadOutcome.CANCELLED, upload.get(2L, TimeUnit.SECONDS))
            assertTrue(f.client.lastPlaintexts.all { bytes -> bytes.all { it == 0.toByte() } })
            assertTrue(f.coordinator.onAccountDeleted(timeoutMs = 500L))
            assertEquals(1, f.storage.purgeCalls)
        } finally { releaseCancel.countDown(); releaseUpload.countDown(); worker.shutdownNow() }
    }

    @Test fun uploadTerminationDeadlineDoesNotAcquireHeldCoordinatorMonitorBeforeCancellingSocket() {
        val f = fixture(); prepareUpload(f)
        val entered = CountDownLatch(1); val releaseUpload = CountDownLatch(1)
        val monitorHeld = CountDownLatch(1); val releaseMonitor = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        f.client.beforeReturn = { entered.countDown(); check(releaseUpload.await(3L, TimeUnit.SECONDS)) }
        f.client.onCancel = { cancelEntered.countDown() }
        val call = requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
        val worker = Executors.newFixedThreadPool(3)
        try {
            val upload = worker.submit<RawCollectionUploadOutcome> { call.execute() }
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            val held = worker.submit { synchronized(f.coordinator) {
                monitorHeld.countDown(); check(releaseMonitor.await(3L, TimeUnit.SECONDS))
            } }
            assertTrue(monitorHeld.await(2L, TimeUnit.SECONDS))
            val elapsed = AtomicLong()
            val termination = worker.submit<Boolean> {
                val started = System.nanoTime()
                f.coordinator.cancelAndAwaitUploadTermination(100L).also { elapsed.set(System.nanoTime() - started) }
            }
            assertTrue(cancelEntered.await(350L, TimeUnit.MILLISECONDS))
            assertFalse(termination.get(350L, TimeUnit.MILLISECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(elapsed.get()) < 350L)
            println("RAW held monitor termination_ms=" + TimeUnit.NANOSECONDS.toMillis(elapsed.get()))
            assertTrue(call.isCancelled()); assertEquals(0, f.storage.purgeCalls)
            releaseMonitor.countDown(); releaseUpload.countDown()
            held.get(2L, TimeUnit.SECONDS)
            assertEquals(RawCollectionUploadOutcome.CANCELLED, upload.get(2L, TimeUnit.SECONDS))
            assertTrue(f.coordinator.cancelAndAwaitUploadTermination(500L))
        } finally { releaseMonitor.countDown(); releaseUpload.countDown(); worker.shutdownNow() }
    }

    @Test fun recreatedDeletionDeadlineIncludesMetadataProducerOutsideUploadRegistry() {
        val f = fixture()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.storage.beforeListing = { entered.countDown(); check(release.await(3L, TimeUnit.SECONDS)) }
        val worker = Executors.newFixedThreadPool(2)
        try {
            val capture = worker.submit<RawMetadataCaptureResult> {
                f.coordinator.captureMetadataBatch(f.context(WalkSessionState.ACTIVE), detectionSample(), performanceSample())
            }
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            val recreated = RawCollectionRuntimeCoordinator(f.store, RuntimeFakeClient(), UUID::randomUUID,
                { NOW_ELAPSED_MS }, 30_000L, testOnly = Unit)
            val elapsed = AtomicLong()
            val deletion = worker.submit<Boolean> {
                val started = System.nanoTime()
                recreated.onAccountDeleted(100L).also { elapsed.set(System.nanoTime() - started) }
            }
            assertFalse(deletion.get(350L, TimeUnit.MILLISECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(elapsed.get()) < 350L)
            println("RAW metadata producer termination_ms=" + TimeUnit.NANOSECONDS.toMillis(elapsed.get()))
            assertEquals(0, f.storage.purgeCalls)
            release.countDown(); capture.get(2L, TimeUnit.SECONDS)
            f.storage.beforeListing = null
            assertTrue(recreated.onAccountDeleted(500L))
            assertEquals(1, f.storage.purgeCalls)
            assertEquals(0, f.storage.collectionCount)
            assertEquals(RawMetadataCaptureResult.GATE_BLOCKED,
                f.coordinator.captureMetadataBatch(f.context(WalkSessionState.ACTIVE), detectionSample(), performanceSample()))
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun collectedFirstPathOwnerCannotRemoveSurvivingCoordinatorFromProducerRegistry() {
        val f = fixture()
        val scopeText = "/isolated/raw-registry-" + UUID.randomUUID()
        val old = registerFirstPathOwner(f.store, scopeText)
        val survivingScope = String(scopeText.toCharArray())
        val surviving = RawCollectionRuntimeCoordinator(f.store, RuntimeFakeClient(), UUID::randomUUID,
            { NOW_ELAPSED_MS }, 30_000L, testOnly = Unit, processScope = survivingScope)
        repeat(100) { if (old.first.get() != null) { System.gc(); Thread.sleep(10L) } }
        assertNull("isolated first coordinator must have been collected for this counterexample", old.first.get())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.storage.beforeListing = { entered.countDown(); check(release.await(3L, TimeUnit.SECONDS)) }
        val worker = Executors.newFixedThreadPool(2)
        try {
            val capture = worker.submit<RawMetadataCaptureResult> {
                surviving.captureMetadataBatch(f.context(WalkSessionState.ACTIVE), detectionSample(), performanceSample())
            }
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            val latest = RawCollectionRuntimeCoordinator(f.store, RuntimeFakeClient(), UUID::randomUUID,
                { NOW_ELAPSED_MS }, 30_000L, testOnly = Unit, processScope = String(scopeText.toCharArray()))
            val deletion = worker.submit<Boolean> { latest.onAccountDeleted(100L) }
            assertFalse(deletion.get(350L, TimeUnit.MILLISECONDS))
            assertNotNull("canonical path must remain strongly held while an equal-path owner is alive", old.second.get())
            assertEquals(0, f.storage.purgeCalls)
            release.countDown(); capture.get(2L, TimeUnit.SECONDS)
            f.storage.beforeListing = null
            assertTrue(latest.onAccountDeleted(500L))
            assertEquals(0, f.storage.collectionCount)
        } finally { release.countDown(); worker.shutdownNow() }
    }

    private fun registerFirstPathOwner(store: RawCollectionStore, scopeText: String):
        Pair<java.lang.ref.WeakReference<RawCollectionRuntimeCoordinator>, java.lang.ref.WeakReference<String>> {
        val firstScope = String(scopeText.toCharArray())
        val first = RawCollectionRuntimeCoordinator(store, RuntimeFakeClient(), UUID::randomUUID,
            { NOW_ELAPSED_MS }, 30_000L, testOnly = Unit, processScope = firstScope)
        return java.lang.ref.WeakReference(first) to java.lang.ref.WeakReference(firstScope)
    }

    @Test fun deletionEnteringDuringResetStorageAckCannotReleaseNewActorAdmission() {
        val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        var defer = false
        val control = Executor { if (defer) queued.add(it) else it.run() }
        val f = fixture(cancellationExecutor = control)
        assertTrue(f.coordinator.onAccountDeleted(500L))
        val oldActivity = RawCollectionRuntimeCoordinator(f.store, RuntimeFakeClient(), UUID::randomUUID,
            { NOW_ELAPSED_MS }, 30_000L, testOnly = Unit, cancellationExecutor = control)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.storage.beforeClearFences = { entered.countDown(); check(release.await(3L, TimeUnit.SECONDS)) }
        val context = f.context(WalkSessionState.ACTIVE)
        val newSession = v7Session(actorId = "123e4567-e89b-42d3-a456-426614174009")
        val newDeviceCheck = context.deviceCheck.copy(actorId = newSession.actorId)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val reset = worker.submit<Boolean> {
                f.coordinator.resetForNewEnrollment(newSession, context.sessionGeneration, newDeviceCheck)
            }
            assertTrue(entered.await(2L, TimeUnit.SECONDS)); defer = true
            assertFalse(oldActivity.onAccountDeleted(0L))
            assertEquals(2, queued.size)
            release.countDown()
            assertFalse(reset.get(2L, TimeUnit.SECONDS))
            val freshContext = context.copy(session = newSession, deviceCheck = newDeviceCheck,
                gatewayWalkLease = requireNotNull(context.gatewayWalkLease).copy(actorId = newSession.actorId))
            assertEquals(RawMetadataCaptureResult.GATE_BLOCKED,
                f.coordinator.captureMetadataBatch(freshContext, detectionSample(), performanceSample()))
            while (true) (queued.poll() ?: break).run()
            f.storage.beforeClearFences = null; defer = false
            assertTrue(oldActivity.onAccountDeleted(500L))
            assertTrue(f.coordinator.resetForNewEnrollment(newSession, context.sessionGeneration, newDeviceCheck))
            assertEquals(RawMetadataCaptureResult.CAPTURED,
                f.coordinator.captureMetadataBatch(freshContext, detectionSample(), performanceSample()))
            assertEquals(1, f.storage.collectionCount)
        } finally { release.countDown(); worker.shutdownNow(); while (true) (queued.poll() ?: break).run() }
    }

    @Test fun immediateAdmissionFenceInvalidatesResetStorageAckWithoutQueuingPurge() {
        val f = fixture()
        assertTrue(f.coordinator.onAccountDeleted(500L))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.storage.beforeClearFences = { entered.countDown(); check(release.await(3L, TimeUnit.SECONDS)) }
        val worker = Executors.newFixedThreadPool(2)
        val context = f.context(WalkSessionState.ACTIVE)
        try {
            val reset = worker.submit<Boolean> {
                f.coordinator.resetForNewEnrollment(requireNotNull(context.session), context.sessionGeneration, context.deviceCheck)
            }
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            worker.submit { f.coordinator.fenceAccountDeletionAdmission() }.get(350L, TimeUnit.MILLISECONDS)
            assertEquals(1, f.storage.purgeCalls) // fence only; no second purge is queued.
            release.countDown(); assertFalse(reset.get(2L, TimeUnit.SECONDS))
            assertEquals(RawMetadataCaptureResult.GATE_BLOCKED,
                f.coordinator.captureMetadataBatch(context, detectionSample(), performanceSample()))
            f.storage.beforeClearFences = null
            assertTrue(f.coordinator.onAccountDeleted(500L))
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun secondActivityCannotResetWhileFirstResetStorageAckIsPending() {
        val f = fixture()
        assertTrue(f.coordinator.onAccountDeleted(500L))
        val other = RawCollectionRuntimeCoordinator(f.store, RuntimeFakeClient(), UUID::randomUUID,
            { NOW_ELAPSED_MS }, 30_000L, testOnly = Unit)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.storage.beforeClearFences = { entered.countDown(); check(release.await(3L, TimeUnit.SECONDS)) }
        val worker = Executors.newFixedThreadPool(2)
        val context = f.context(WalkSessionState.ACTIVE)
        try {
            val first = worker.submit<Boolean> {
                f.coordinator.resetForNewEnrollment(requireNotNull(context.session), context.sessionGeneration, context.deviceCheck)
            }
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            val competing = worker.submit<Boolean> {
                other.resetForNewEnrollment(requireNotNull(context.session), context.sessionGeneration, context.deviceCheck)
            }
            assertFalse(competing.get(350L, TimeUnit.MILLISECONDS))
            release.countDown(); assertTrue(first.get(2L, TimeUnit.SECONDS))
            assertEquals(RawMetadataCaptureResult.CAPTURED,
                f.coordinator.captureMetadataBatch(context, detectionSample(), performanceSample()))
            assertEquals(1, f.storage.collectionCount)
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun resetEnteringDuringDeletionStorageAckCannotPublishFreshActor() {
        val f = fixture()
        val otherActivity = RawCollectionRuntimeCoordinator(f.store, RuntimeFakeClient(), UUID::randomUUID,
            { NOW_ELAPSED_MS }, 30_000L, testOnly = Unit)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.storage.beforePurge = { entered.countDown(); check(release.await(3L, TimeUnit.SECONDS)) }
        val worker = Executors.newFixedThreadPool(2)
        try {
            val deletion = worker.submit<Boolean> { otherActivity.onAccountDeleted(500L) }
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            val context = f.context(WalkSessionState.ACTIVE)
            val reset = worker.submit<Boolean> {
                f.coordinator.resetForNewEnrollment(requireNotNull(context.session), context.sessionGeneration, context.deviceCheck)
            }
            assertFalse(reset.get(350L, TimeUnit.MILLISECONDS))
            release.countDown(); assertTrue(deletion.get(2L, TimeUnit.SECONDS))
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun pendingDeletionBarrierCannotRunAfterNewEnrollment() {
        val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        var defer = false
        val control = Executor { if (defer) queued.add(it) else it.run() }
        val f = fixture(cancellationExecutor = control)
        assertTrue(f.coordinator.onAccountDeleted(500L))
        defer = true
        val deferred = RawCollectionRuntimeCoordinator(f.store, RuntimeFakeClient(), UUID::randomUUID,
            { NOW_ELAPSED_MS }, 30_000L, testOnly = Unit, cancellationExecutor = control)
        assertFalse(deferred.onAccountDeleted(0L))
        assertFalse(deferred.onAccountDeleted(0L))
        assertEquals(2, queued.size) // one barrier per live coordinator; retry joins both.
        val context = f.context(WalkSessionState.ACTIVE)
        val newSession = v7Session(actorId = "123e4567-e89b-42d3-a456-426614174009")
        val newDeviceCheck = context.deviceCheck.copy(actorId = newSession.actorId)
        assertFalse(deferred.resetForNewEnrollment(newSession, context.sessionGeneration, newDeviceCheck))
        while (true) (queued.poll() ?: break).run()
        assertTrue(deferred.resetForNewEnrollment(newSession, context.sessionGeneration, newDeviceCheck))
        val newContext = context.copy(session = newSession, deviceCheck = newDeviceCheck,
            gatewayWalkLease = requireNotNull(context.gatewayWalkLease).copy(actorId = newSession.actorId))
        assertEquals(RawMetadataCaptureResult.CAPTURED,
            deferred.captureMetadataBatch(newContext, detectionSample(), performanceSample()))
        assertTrue(queued.isEmpty())
        assertEquals(1, f.storage.collectionCount)
        assertEquals(RawSegmentSealResult.SEALED,
            deferred.sealActiveSegment(newContext.copy(walk = f.context(WalkSessionState.ENDED).walk,
                gatewayWalkLease = null)))
        assertEquals(1, f.storage.collectionCount)
    }

    @Test fun recreatedCoordinatorCannotProveDeletionUntilOldUploadActuallyErasesPlaintext() {
        val f = fixture(); prepareUpload(f)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.client.beforeReturn = { entered.countDown(); check(release.await(3L, TimeUnit.SECONDS)) }
        val call = requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<RawCollectionUploadOutcome> { call.execute() }
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            f.coordinator.cancelActiveUpload()
            val recreated = RawCollectionRuntimeCoordinator(f.store, RuntimeFakeClient(), UUID::randomUUID,
                { NOW_ELAPSED_MS }, 30_000L, testOnly = Unit)
            assertFalse(recreated.onAccountDeleted(timeoutMs = 0L))
            assertEquals(1, f.storage.collectionCount)
            assertTrue(f.client.lastPlaintexts.any { bytes -> bytes.any { it != 0.toByte() } })
            assertNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
            release.countDown()
            assertEquals(RawCollectionUploadOutcome.CANCELLED, result.get(2L, TimeUnit.SECONDS))
            assertTrue(f.client.lastPlaintexts.all { bytes -> bytes.all { it == 0.toByte() } })
            assertTrue(recreated.onAccountDeleted(timeoutMs = 500L))
            assertEquals(0, f.storage.collectionCount)
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun deletionBeforeExecutionReleasesLeaseWithoutOpeningPlaintextOrNetwork() {
        val f = fixture(); prepareUpload(f)
        val call = requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) })
        assertTrue(f.coordinator.onAccountDeleted(timeoutMs = 500L))
        assertThrows(CancellationException::class.java) { call.execute() }
        assertEquals(0, f.client.calls)
    }

    @Test fun remainingRawCopyPreventsDeletionSuccessEvenWhenDeleteApiReturnsTrue() {
        val f = fixture(); prepareUpload(f); f.storage.failEmptyVerification = true
        assertFalse(f.coordinator.onAccountDeleted())
    }

    private fun prepareUpload(f: RuntimeFixture) {
        assertEquals(RawMetadataCaptureResult.CAPTURED,
            f.coordinator.captureMetadataBatch(f.context(WalkSessionState.ACTIVE), detectionSample(), performanceSample()))
        assertEquals(RawSegmentSealResult.SEALED, f.coordinator.sealActiveSegment(f.context(WalkSessionState.ENDED)))
    }

    @Test
    fun unreadableListingIsNotReportedAsAnEmptyUploadQueue() {
        val f = fixture()
        prepareUpload(f)
        f.storage.failListing = true
        assertEquals(RawCollectionUploadOutcome.LOCAL_READ_FAILED,
            requireNotNull(f.coordinator.startNextUpload { f.context(WalkSessionState.ENDED) }).execute())
        assertEquals(0, f.client.calls)
        // 16-17 #12: an unreadable store is ReadFailed, not an exception or an empty count.
        assertEquals(
            RawReadyStatus.ReadFailed,
            f.coordinator.readyStatus(f.context(WalkSessionState.ENDED)),
        )
    }

    @Test
    fun captureListingFailureIsAStorageFailureNotAnEmptyNewSegment() {
        val f = fixture()
        f.storage.failListing = true
        assertEquals(RawMetadataCaptureResult.STORAGE_FAILURE,
            f.coordinator.captureMetadataBatch(f.context(WalkSessionState.ACTIVE), detectionSample(), performanceSample()))
        assertEquals(0, f.storage.collectionCount)
    }

    private fun fixture(
        storeNowEpochMs: Long = NOW_EPOCH_MS,
        clock: () -> Long = { storeNowEpochMs },
        elapsedClock: () -> Long = { NOW_ELAPSED_MS },
        cancellationExecutor: Executor? = null,
    ): RuntimeFixture {
        val storage = RuntimeFakeStorage()
        val aead = RuntimeFakeAead()
        val store = RawCollectionStore(storage, aead, clock, testOnly = Unit)
        val client = RuntimeFakeClient()
        val coordinator = if (cancellationExecutor == null) RawCollectionRuntimeCoordinator(
            store = store,
            client = client,
            idFactory = { UUID.fromString(COLLECTION_ID) },
            elapsedRealtimeMs = elapsedClock,
            minimumCaptureIntervalMs = 30_000L,
            testOnly = Unit,
        ) else RawCollectionRuntimeCoordinator(
            store = store,
            client = client,
            idFactory = { UUID.fromString(COLLECTION_ID) },
            elapsedRealtimeMs = elapsedClock,
            minimumCaptureIntervalMs = 30_000L,
            testOnly = Unit,
            cancellationExecutor = cancellationExecutor,
        )
        val consentSession = IntegratedConsentSession()
        assertTrue(consentSession.accept(confirmation()))
        return RuntimeFixture(
            coordinator,
            client,
            store,
            aead,
            storage,
            consentSession,
            v7Session(),
            deviceCheck(),
        )
    }

    private fun detectionSample() = RawDetectionMetadataSample(
        windowIdentity = RAW_TEST_WINDOW_IDENTITY,
        runtimeIdentity = RawDetectorRuntimeIdentity(
            modelArtifactSha256 = "1".repeat(64),
            runtimeConfigSha256 = "2".repeat(64),
            classListSha256 = "3".repeat(64),
            activeDelegate = "cpu",
            delegateFallbackUsed = false,
        ),
        windowStartedAtEpochMs = NOW_EPOCH_MS - 30_000L,
        capturedAtEpochMs = NOW_EPOCH_MS,
        processedFrameCount = 30,
        detectionCount = 4,
        averageInferenceMs = 25L,
        detectorAvailable = true,
        modelRevision = "model-v1",
    )

    private fun performanceSample(
        thermalThrottled: Boolean? = false,
    ) = RawPerformanceMetadataSample(
        windowIdentity = RAW_TEST_WINDOW_IDENTITY,
        windowStartedAtEpochMs = NOW_EPOCH_MS - 30_000L,
        capturedAtEpochMs = NOW_EPOCH_MS,
        processedFrameCount = 30,
        droppedFrameCount = 2,
        averageFrameDurationMs = 40L,
        thermalThrottled = thermalThrottled,
    )

    private fun v7Session(actorId: String = ACTOR_ID): GatewayFieldSession = GatewayFieldSession.backendAccountDeviceSession(
        gatewayBaseUrl = "http://127.0.0.1:8081",
        binding = BackendAccountDeviceCookieBinding(
            actorId = actorId,
            accountGeneration = 1L,
            authEpoch = 1L,
            deviceId = DEVICE_ID,
            sessionId = "s".repeat(32),
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
        ),
        cookiePair = "${GatewayFieldSession.COOKIE_NAME}=test-v7-cookie",
        expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
    )

    private fun deviceCheck() = PostLoginDeviceCheckSnapshot(
        state = PostLoginDeviceCheckState.FULL,
        actorId = ACTOR_ID,
        sessionGeneration = SESSION_GENERATION,
        attemptGeneration = 1L,
    )

    private fun confirmation() = IntegratedConsentConfirmation(
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

    private companion object {
        const val COLLECTION_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val LEGACY_COLLECTION_ID = "123e4567-e89b-42d3-a456-426614174004"
        const val WALK_ID = "123e4567-e89b-42d3-a456-426614174001"
        const val ACTOR_ID = "123e4567-e89b-42d3-a456-426614174002"
        const val DEVICE_ID = "device-installation-00000001"
        const val SESSION_GENERATION = 7L
        const val NOW_EPOCH_MS = 1_800_000_000_000L
        const val NOW_ELAPSED_MS = 50_000L
        val CONSENT_SHA = "c".repeat(64)
        val OWNER = RawCollectionOwner(ACTOR_ID, 1L, DEVICE_ID)
    }
}

private class RuntimeFixture(
    val coordinator: RawCollectionRuntimeCoordinator,
    val client: RuntimeFakeClient,
    val store: RawCollectionStore,
    val aead: RuntimeFakeAead,
    val storage: RuntimeFakeStorage,
    private val consentSession: IntegratedConsentSession,
    private val session: GatewayFieldSession,
    val deviceCheck: PostLoginDeviceCheckSnapshot,
) {
    private val epoch = WalkRuntimeEpoch(
        "123e4567-e89b-42d3-a456-426614174001",
        recoveryGeneration = 0L,
    )
    private val networkBinding = IntegratedConsentNetworkBinding.forTest(
        IntegratedConsentNetworkTransport.WIFI,
    ) { url -> url.openConnection() }

    fun context(state: WalkSessionState): RawCollectionRuntimeContext {
        val walk = when (state) {
            WalkSessionState.ACTIVE -> activeWalkSnapshot(epoch)
            WalkSessionState.PAUSED -> pausedWalkSnapshot(epoch)
            WalkSessionState.ENDED -> endedWalkSnapshot(epoch)
            else -> error("unsupported test state")
        }
        return RawCollectionRuntimeContext(
            session = session,
            sessionGeneration = 7L,
            deviceCheck = deviceCheck,
            consentSession = consentSession,
            walk = walk,
            // Owner decision 3(b): after the walk (ENDED) this device holds no Gateway walk lease.
            gatewayWalkLease = if (state == WalkSessionState.ENDED) null else GatewayWalkLease(
                result = "ACQUIRED",
                actorId = session.actorId,
                deviceId = session.deviceId,
                walkId = epoch.walkSessionId,
                leaseId = "lease-id",
                fencingToken = 1L,
                acquiredAtEpochMs = 1_800_000_000_000L,
                leaseExpiresAtEpochMs = 1_800_000_090_000L,
                serverTimeEpochMs = 1_800_000_000_000L,
                localDeadlineElapsedMs = 100_000L,
            ),
            networkTransport = ActiveNetworkTransport.WIFI,
            networkBinding = networkBinding,
            charging = true,
        )
    }
}

private fun activeWalkSnapshot(epoch: WalkRuntimeEpoch): WalkSessionSnapshot {
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

private fun pausedWalkSnapshot(epoch: WalkRuntimeEpoch): WalkSessionSnapshot = WalkSessionSnapshot(
    epoch = epoch,
    state = WalkSessionState.PAUSED,
    mode = WalkSessionMode.FULL,
    recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED,
    isForeground = true,
    readiness = null,
)

private fun endedWalkSnapshot(epoch: WalkRuntimeEpoch): WalkSessionSnapshot = WalkSessionSnapshot(
    epoch = epoch,
    state = WalkSessionState.ENDED,
    mode = WalkSessionMode.UNAVAILABLE,
    recoveryStage = null,
    isForeground = true,
    readiness = null,
)

private class RuntimeFakeClient : RawCollectionNetworkClient {
    var calls = 0
    var beforeReturn: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var httpFailure: Int? = null
    var errorBody = ""
    var retryAfter: String? = null
    var factoryFailure = false
    var lastPlaintexts: List<ByteArray> = emptyList()
    var lastPayloadSnapshots: List<ByteArray> = emptyList()
    var lastChunkMetadata: List<RawChunkMetadata> = emptyList()

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
        lastPlaintexts = chunks.map(RawPlaintextChunk::plaintext)
        lastPayloadSnapshots = chunks.map { it.plaintext.copyOf() }
        lastChunkMetadata = chunks.map(RawPlaintextChunk::metadata)
        if (factoryFailure) throw IllegalStateException("controlled client factory failure")
        return CancellableNetworkCall(
            executeBlock = {
                check(isCurrent())
                beforeReturn?.invoke()
                httpFailure?.let { throw RawCollectionHttpException(it, errorBody, retryAfter) }
                exactRuntimeReceipt(backendManifest)
            },
            cancelBlock = { onCancel?.invoke() },
        )
    }
}

private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

private fun exactRuntimeReceipt(manifest: BackendRawManifest): RawCollectionReceipt {
    val unsigned = RawCollectionReceipt(
        schemaVersion = BACKEND_RECEIPT_SCHEMA,
        collectionId = manifest.collectionId,
        manifestSha256 = manifest.manifestSha256,
        purpose = BACKEND_RAW_PURPOSE,
        persistenceMarker = RAW_RECEIPT_PERSISTENCE_MARKER,
        objectCount = manifest.objects.size,
        chunkCount = manifest.chunkCount,
        totalBytes = manifest.totalBytes,
        objects = manifest.objects,
        retentionClass = BACKEND_RETENTION_CLASS,
        committedAt = "2026-08-29T00:00:00Z",
        quarantineExpiresAt = "2026-09-12T00:00:00Z",
        receiptSha256 = "0".repeat(64),
    )
    return unsigned.copy(receiptSha256 = rawReceiptSha256(unsigned))
}

private class RuntimeFakeStorage : RawCollectionStorage {
    private var activeId: String? = null
    private val manifests = mutableMapOf<String, String>()
    private val chunks = mutableMapOf<Pair<String, Int>, String>()
    private val fences = mutableMapOf<String, String>()
    var failChunkOrdinal: Int? = null
    var failDelete = false
    var failListing = false
    var failEmptyVerification = false
    var beforeListing: (() -> Unit)? = null
    var beforeClearFences: (() -> Unit)? = null
    var beforePurge: (() -> Unit)? = null
    var purgeCalls = 0
    val activeCollectionId: String?
        get() = activeId
    val collectionCount: Int
        get() = manifests.size

    override fun readActiveCollectionId(): String? = activeId
    override fun writeActiveCollectionIdAtomically(collectionId: String): Boolean {
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
        if (ordinal == failChunkOrdinal) return false
        chunks[collectionId to ordinal] = envelope
        return true
    }
    override fun deleteChunk(collectionId: String, ordinal: Int): Boolean =
        chunks.remove(collectionId to ordinal) != null
    override fun listCollectionIds(): Set<String> {
        beforeListing?.invoke()
        if (failListing) throw RawCollectionStorageReadException("test listing")
        return manifests.keys.toSet()
    }
    override fun deleteCollection(collectionId: String): Boolean {
        if (failDelete) return false
        manifests.remove(collectionId)
        chunks.keys.removeAll { it.first == collectionId }
        return true
    }
    override fun deleteAllCollections(): Boolean {
        beforePurge?.invoke()
        purgeCalls += 1
        manifests.clear()
        chunks.clear()
        return true
    }
    override fun verifiedEmptyAfterAccountPurge(): Boolean = !failEmptyVerification && manifests.isEmpty() && chunks.isEmpty()
    override fun hasFence(name: String): Boolean = name in fences
    override fun readFence(name: String): String? = fences[name]
    override fun writeFenceAtomically(name: String, value: String): Boolean {
        fences[name] = value
        return true
    }
    override fun listFenceNames(): Set<String> = fences.keys
    override fun clearAllFences(): Boolean {
        beforeClearFences?.invoke()
        fences.clear()
        return true
    }
}

private class RuntimeFakeAead : LocalAead {
    var throwOnOpen = false
    private val values = mutableMapOf<String, ByteArray>()
    private var ordinal = 0

    override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits): AeadSealResult {
        val envelope = "runtime-envelope-${ordinal++}"
        values[envelope] = plaintext.copyOf()
        return AeadSealResult.Sealed(envelope)
    }
    override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult {
        if (throwOnOpen) throw IllegalStateException("controlled decrypt failure")
        return values[envelope]?.copyOf()?.let { AeadOpenResult.Opened(it, 1, false) }
            ?: AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
    }
    override fun destroyVersion(version: Int): Boolean = true
    override fun destroyKnownVersions(): Boolean {
        values.clear()
        return true
    }
    override fun createFreshAfterVerifiedPurge(): Boolean = true
}

private val RAW_TEST_WINDOW_IDENTITY = RawRuntimeWindowIdentity()
