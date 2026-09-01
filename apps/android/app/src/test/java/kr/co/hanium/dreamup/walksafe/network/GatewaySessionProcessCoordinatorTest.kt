package kr.co.hanium.dreamup.walksafe.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingAttemptRequest
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.FirstRunOpaqueActorBinding
import kr.co.hanium.dreamup.walksafe.session.FirstRunReceiptHash
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewaySessionProcessCoordinatorTest {
    private val firstOwner = Any()
    private val secondOwner = Any()

    @Before
    fun clearProcessState() {
        val current = GatewaySessionProcessCoordinator.snapshot()
        assertNotNull(GatewaySessionProcessCoordinator.clear(current.generation))
    }

    @After
    fun detachOwnersAndClearProcessState() {
        GatewaySessionProcessCoordinator.detach(firstOwner)
        GatewaySessionProcessCoordinator.detach(secondOwner)
        val current = GatewaySessionProcessCoordinator.snapshot()
        GatewaySessionProcessCoordinator.clear(current.generation)
    }

    @Test
    fun twoOwnersShareOneInFlightOperation() {
        val firstSnapshots = mutableListOf<GatewaySessionProcessSnapshot>()
        val secondSnapshots = mutableListOf<GatewaySessionProcessSnapshot>()
        GatewaySessionProcessCoordinator.attach(firstOwner, firstSnapshots::add)
        GatewaySessionProcessCoordinator.attach(secondOwner, secondSnapshots::add)

        val operation = GatewaySessionProcessCoordinator.beginOperation()

        assertNotNull(operation)
        assertNull(GatewaySessionProcessCoordinator.beginOperation())
        assertEquals(operation?.operationId, firstSnapshots.last().inFlightOperationId)
        assertEquals(operation?.operationId, secondSnapshots.last().inFlightOperationId)
        assertEquals(
            operation?.operationId,
            GatewaySessionProcessCoordinator.snapshot().inFlightOperationId,
        )
    }

    @Test
    fun detachingOwnersPreservesStateAndExecutor() {
        val session = verifiedSession(rotation = 1L)
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        assertTrue(
            GatewaySessionProcessCoordinator.publishVerified(
                operation = operation,
                session = session,
                firstRunSnapshot = FirstRunOnboardingPolicy.initial(epoch = 11L),
            ),
        )
        val attached = GatewaySessionProcessCoordinator.attach(firstOwner) {}

        GatewaySessionProcessCoordinator.detach(firstOwner)
        val executed = CountDownLatch(1)
        GatewaySessionProcessCoordinator.executor.execute(executed::countDown)

        assertTrue(executed.await(2, TimeUnit.SECONDS))
        val afterDetach = GatewaySessionProcessCoordinator.snapshot()
        assertSame(session, afterDetach.session)
        assertEquals(attached.generation, afterDetach.generation)
        assertTrue(session.isUsableFor(ACTOR_ID, nowEpochMs = NOW_EPOCH_MS))
    }

    @Test
    fun staleCompletionAndClearCannotReplaceLatestOperationState() {
        val firstOperation = requireNotNull(
            GatewaySessionProcessCoordinator.beginOperation(),
        )
        val restored = restoredSession(rotation = 2L)
        val firstRun = FirstRunOnboardingPolicy.initial(epoch = 12L)
        val continuedOperation = requireNotNull(
            GatewaySessionProcessCoordinator.publishRestoredUnverified(
                operation = firstOperation,
                session = restored,
                firstRunSnapshot = firstRun,
            ),
        )

        assertEquals(firstOperation.operationId, continuedOperation.operationId)
        assertNotEquals(firstOperation.generation, continuedOperation.generation)
        assertFalse(
            GatewaySessionProcessCoordinator.publishVerified(
                operation = firstOperation,
                session = verifiedSession(rotation = 3L),
                firstRunSnapshot = firstRun,
            ),
        )
        assertNull(GatewaySessionProcessCoordinator.clear(firstOperation))
        assertNull(GatewaySessionProcessCoordinator.clear(firstOperation.generation))
        assertSame(restored, GatewaySessionProcessCoordinator.snapshot().session)

        val verified = verifiedSession(rotation = 3L)
        assertTrue(
            GatewaySessionProcessCoordinator.publishVerified(
                operation = continuedOperation,
                session = verified,
                firstRunSnapshot = firstRun,
            ),
        )
        val latest = GatewaySessionProcessCoordinator.snapshot()
        assertSame(verified, latest.session)
        assertSame(firstRun, latest.restoredFirstRunSnapshot)
        assertNull(latest.inFlightOperationId)
        assertNull(GatewaySessionProcessCoordinator.clear(continuedOperation))
        assertNull(GatewaySessionProcessCoordinator.clear(continuedOperation.generation))
        assertSame(verified, GatewaySessionProcessCoordinator.snapshot().session)
    }

    @Test
    fun subscriberIsNotifiedForAcceptedChangesUntilDetached() {
        val snapshots = mutableListOf<GatewaySessionProcessSnapshot>()
        GatewaySessionProcessCoordinator.attach(firstOwner, snapshots::add)
        val initialGeneration = snapshots.single().generation
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        val restoredOperation = requireNotNull(
            GatewaySessionProcessCoordinator.publishRestoredUnverified(
                operation = operation,
                session = restoredSession(rotation = 4L),
                firstRunSnapshot = FirstRunOnboardingPolicy.initial(epoch = 13L),
            ),
        )
        assertTrue(
            GatewaySessionProcessCoordinator.publishVerified(
                operation = restoredOperation,
                session = verifiedSession(rotation = 5L),
                firstRunSnapshot = FirstRunOnboardingPolicy.initial(epoch = 13L),
            ),
        )

        assertEquals(4, snapshots.size)
        assertTrue(snapshots.zipWithNext().all { (first, second) ->
            first.generation < second.generation
        })
        assertTrue(snapshots.last().generation > initialGeneration)
        assertNull(snapshots.last().inFlightOperationId)

        GatewaySessionProcessCoordinator.detach(firstOwner)
        val latestGeneration = GatewaySessionProcessCoordinator.snapshot().generation
        assertNotNull(GatewaySessionProcessCoordinator.clear(latestGeneration))
        assertEquals(4, snapshots.size)
    }

    @Test
    fun newerFirstRunProgressUpdatesWithoutReplacingTheSessionGeneration() {
        val actorId = BACKEND_ACTOR_ID
        val session = verifiedSession(
            rotation = 20L,
            actorId = actorId,
            nowEpochMs = System.currentTimeMillis(),
        )
        val firstRun = verifiedEmailFirstRun(epoch = 20L, actorId = actorId)
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        assertTrue(
            GatewaySessionProcessCoordinator.publishVerified(
                operation = operation,
                session = session,
                firstRunSnapshot = firstRun,
            ),
        )
        val observed = mutableListOf<GatewaySessionProcessSnapshot>()
        val published = GatewaySessionProcessCoordinator.attach(firstOwner, observed::add)
        val advanced = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
            snapshot = firstRun,
            expectedEpoch = firstRun.epoch,
            expectedRevision = firstRun.revision,
        ).current

        assertTrue(
            GatewaySessionProcessCoordinator.updateFirstRunSnapshot(
                expectedGeneration = published.generation,
                expectedActorId = actorId,
                firstRunSnapshot = advanced,
            ),
        )

        val latest = GatewaySessionProcessCoordinator.snapshot()
        assertEquals(published.generation, latest.generation)
        assertSame(session, latest.session)
        assertSame(advanced, latest.restoredFirstRunSnapshot)
        assertEquals(2, observed.size)
        assertEquals(observed.first().generation, observed.last().generation)
        assertSame(advanced, observed.last().restoredFirstRunSnapshot)
    }

    @Test
    fun staleOrActorMismatchedFirstRunProgressCannotReplaceCurrentProgress() {
        val actorId = BACKEND_ACTOR_ID
        val session = verifiedSession(
            rotation = 21L,
            actorId = actorId,
            nowEpochMs = System.currentTimeMillis(),
        )
        val firstRun = verifiedEmailFirstRun(epoch = 21L, actorId = actorId)
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        assertTrue(
            GatewaySessionProcessCoordinator.publishVerified(
                operation = operation,
                session = session,
                firstRunSnapshot = firstRun,
            ),
        )
        val generation = GatewaySessionProcessCoordinator.snapshot().generation
        val advanced = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
            snapshot = firstRun,
            expectedEpoch = firstRun.epoch,
            expectedRevision = firstRun.revision,
        ).current
        assertTrue(
            GatewaySessionProcessCoordinator.updateFirstRunSnapshot(
                generation,
                actorId,
                advanced,
            ),
        )

        assertFalse(
            GatewaySessionProcessCoordinator.updateFirstRunSnapshot(
                generation,
                actorId,
                firstRun,
            ),
        )
        assertFalse(
            GatewaySessionProcessCoordinator.updateFirstRunSnapshot(
                generation,
                "00000000-0000-4000-8000-000000000099",
                advanced,
            ),
        )
        assertSame(
            advanced,
            GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot,
        )
    }

    @Test
    fun pendingRevocationClearsTheCredentialButRetainsTheExactOperation() {
        val session = verifiedSession(rotation = 8L)
        val loginOperation =
            requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        assertTrue(
            GatewaySessionProcessCoordinator.publishVerified(
                operation = loginOperation,
                session = session,
                firstRunSnapshot = FirstRunOnboardingPolicy.initial(epoch = 15L),
            ),
        )
        val logoutOperation =
            requireNotNull(GatewaySessionProcessCoordinator.beginOperation())

        val pendingOperation = requireNotNull(
            GatewaySessionProcessCoordinator.publishPendingRevocation(logoutOperation),
        )

        assertEquals(logoutOperation.operationId, pendingOperation.operationId)
        assertNotEquals(logoutOperation.generation, pendingOperation.generation)
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertFalse(session.isRenewableFor(ACTOR_ID, nowEpochMs = NOW_EPOCH_MS))
        assertNull(GatewaySessionProcessCoordinator.clear(logoutOperation))
        assertNotNull(GatewaySessionProcessCoordinator.clear(pendingOperation))
    }

    @Test
    fun storageBlockAndToStringRemainFailClosedAndRedacted() {
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        val restoredOperation = requireNotNull(
            GatewaySessionProcessCoordinator.publishRestoredUnverified(
                operation = operation,
                session = restoredSession(rotation = 6L),
                firstRunSnapshot = FirstRunOnboardingPolicy.initial(epoch = 14L),
            ),
        )
        val beforeBlocked = GatewaySessionProcessCoordinator.snapshot()

        assertFalse(beforeBlocked.toString().contains(ACTOR_ID))
        assertFalse(beforeBlocked.toString().contains(ACCESS_SECRET))
        assertFalse(beforeBlocked.toString().contains(REFRESH_SECRET))
        assertFalse(beforeBlocked.toString().contains(restoredOperation.operationId))
        assertFalse(restoredOperation.toString().contains(restoredOperation.operationId))
        assertTrue(GatewaySessionProcessCoordinator.markStorageBlocked(restoredOperation))

        val blocked = GatewaySessionProcessCoordinator.snapshot()
        assertTrue(blocked.storageBlocked)
        assertNull(blocked.session)
        assertNull(blocked.restoredFirstRunSnapshot)
        assertNull(blocked.inFlightOperationId)
    }

    @Test
    fun guardedGeneralOperationRejectsNewDeletionRecoveryOrStorageBlock() {
        val generalSession = verifiedSession(rotation = 30L)
        val generalLogin = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        assertTrue(
            GatewaySessionProcessCoordinator.publishVerified(
                operation = generalLogin,
                session = generalSession,
                firstRunSnapshot = FirstRunOnboardingPolicy.initial(epoch = 30L),
            ),
        )
        val beforeRecovery = GatewaySessionProcessCoordinator.snapshot()
        val recoveryOperation =
            requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        val recoverySession = deletionRecoveryVerifiedSession(rotation = 30L)
        assertTrue(
            GatewaySessionProcessCoordinator.publishDeletionRecoveryVerified(
                operation = recoveryOperation,
                session = recoverySession,
                expectedActorId = ACTOR_ID,
                expectedGatewayBaseUrl = GATEWAY_ORIGIN,
            ),
        )

        assertNull(
            GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(
                expectedGeneration = beforeRecovery.generation,
                expectedSession = generalSession,
            ),
        )
        val afterRecovery = GatewaySessionProcessCoordinator.snapshot()
        assertSame(recoverySession, afterRecovery.session)
        assertTrue(afterRecovery.deletionRecoveryOnly)

        assertNotNull(GatewaySessionProcessCoordinator.clear(afterRecovery.generation))
        val secondGeneralSession = verifiedSession(rotation = 31L)
        val secondLogin = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        assertTrue(
            GatewaySessionProcessCoordinator.publishVerified(
                operation = secondLogin,
                session = secondGeneralSession,
                firstRunSnapshot = FirstRunOnboardingPolicy.initial(epoch = 31L),
            ),
        )
        val beforeStorageBlock = GatewaySessionProcessCoordinator.snapshot()
        val storageOperation =
            requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        assertTrue(GatewaySessionProcessCoordinator.markStorageBlocked(storageOperation))

        assertNull(
            GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(
                expectedGeneration = beforeStorageBlock.generation,
                expectedSession = secondGeneralSession,
            ),
        )
        val afterStorageBlock = GatewaySessionProcessCoordinator.snapshot()
        assertTrue(afterStorageBlock.storageBlocked)
        assertNull(afterStorageBlock.session)
    }

    @Test
    fun deletionRecoveryPublishAcceptsExactVerifiedSessionWithoutFirstRunState() {
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        val session = deletionRecoveryVerifiedSession(rotation = 9L)

        assertTrue(
            GatewaySessionProcessCoordinator.publishDeletionRecoveryVerified(
                operation = operation,
                session = session,
                expectedActorId = ACTOR_ID,
                expectedGatewayBaseUrl = GATEWAY_ORIGIN,
            ),
        )

        val published = GatewaySessionProcessCoordinator.snapshot()
        assertSame(session, published.session)
        assertNull(published.restoredFirstRunSnapshot)
        assertTrue(published.deletionRecoveryOnly)
        assertNull(published.inFlightOperationId)
    }

    @Test
    fun deletionRecoveryPublishRejectsMismatchesAndKeepsOperationInFlight() {
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        val session = deletionRecoveryVerifiedSession(rotation = 10L)

        assertFalse(
            GatewaySessionProcessCoordinator.publishDeletionRecoveryVerified(
                operation = operation,
                session = session,
                expectedActorId = "other-actor",
                expectedGatewayBaseUrl = GATEWAY_ORIGIN,
            ),
        )
        assertFalse(
            GatewaySessionProcessCoordinator.publishDeletionRecoveryVerified(
                operation = operation,
                session = session,
                expectedActorId = ACTOR_ID,
                expectedGatewayBaseUrl = "https://other-gateway.example.test",
            ),
        )
        assertFalse(
            GatewaySessionProcessCoordinator.publishDeletionRecoveryVerified(
                operation = operation,
                session = verifiedSession(rotation = 10L),
                expectedActorId = ACTOR_ID,
                expectedGatewayBaseUrl = GATEWAY_ORIGIN,
            ),
        )

        val unchanged = GatewaySessionProcessCoordinator.snapshot()
        assertEquals(operation.generation, unchanged.generation)
        assertEquals(operation.operationId, unchanged.inFlightOperationId)
        assertNull(unchanged.session)
        assertFalse(unchanged.deletionRecoveryOnly)
    }

    @Test
    fun normalPublishDoesNotSetDeletionRecoveryOnly() {
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        assertTrue(
            GatewaySessionProcessCoordinator.publishVerified(
                operation = operation,
                session = verifiedSession(rotation = 11L),
                firstRunSnapshot = FirstRunOnboardingPolicy.initial(epoch = 16L),
            ),
        )

        assertFalse(GatewaySessionProcessCoordinator.snapshot().deletionRecoveryOnly)
    }

    @Test
    fun normalPublishRejectsDeletionRecoveryScopeAndKeepsOperationInFlight() {
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())

        assertFalse(
            GatewaySessionProcessCoordinator.publishVerified(
                operation = operation,
                session = deletionRecoveryVerifiedSession(rotation = 13L),
                firstRunSnapshot = FirstRunOnboardingPolicy.initial(epoch = 17L),
            ),
        )

        val unchanged = GatewaySessionProcessCoordinator.snapshot()
        assertEquals(operation.operationId, unchanged.inFlightOperationId)
        assertNull(unchanged.session)
    }

    @Test
    fun clearResetsDeletionRecoveryOnly() {
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        val session = deletionRecoveryVerifiedSession(rotation = 12L)
        assertTrue(
            GatewaySessionProcessCoordinator.publishDeletionRecoveryVerified(
                operation = operation,
                session = session,
                expectedActorId = ACTOR_ID,
                expectedGatewayBaseUrl = GATEWAY_ORIGIN,
            ),
        )

        val beforeClear = GatewaySessionProcessCoordinator.snapshot()
        assertTrue(beforeClear.deletionRecoveryOnly)
        val cleared = requireNotNull(
            GatewaySessionProcessCoordinator.clear(beforeClear.generation),
        )

        assertFalse(cleared.deletionRecoveryOnly)
        assertNull(cleared.session)
        assertNull(cleared.restoredFirstRunSnapshot)
        assertFalse(GatewaySessionProcessCoordinator.snapshot().deletionRecoveryOnly)
    }

    private fun restoredSession(rotation: Long): GatewayFieldSession {
        val persistence = GatewayFieldSessionPersistence(
            gatewayBaseUrl = GATEWAY_ORIGIN,
            actorId = ACTOR_ID,
            accessCookiePair = "${GatewayFieldSession.COOKIE_NAME}=$ACCESS_SECRET",
            refreshToken = REFRESH_SECRET,
            deviceId = DEVICE_ID,
            familyId = FAMILY_ID,
            rotation = rotation,
            accessExpiresAtEpochMs = NOW_EPOCH_MS + 60_000L,
            idleExpiresAtEpochMs = NOW_EPOCH_MS + 120_000L,
            absoluteExpiresAtEpochMs = NOW_EPOCH_MS + 180_000L,
        )
        return requireNotNull(
            GatewayFieldSession.restore(
                snapshot = persistence,
                expectedGatewayBaseUrl = GATEWAY_ORIGIN,
                expectedActorId = ACTOR_ID,
                expectedDeviceId = DEVICE_ID,
                nowEpochMs = NOW_EPOCH_MS,
            ),
        )
    }

    private fun verifiedSession(
        rotation: Long,
        actorId: String = ACTOR_ID,
        nowEpochMs: Long = NOW_EPOCH_MS,
    ): GatewayFieldSession =
        GatewayFieldSession.longLivedSession(
            gatewayBaseUrl = GATEWAY_ORIGIN,
            actorId = actorId,
            deviceId = DEVICE_ID,
            familyId = FAMILY_ID,
            rotation = rotation,
            cookiePair = "${GatewayFieldSession.COOKIE_NAME}=$ACCESS_SECRET",
            refreshToken = REFRESH_SECRET,
            accessExpiresAtEpochMs = nowEpochMs + 60_000L,
            idleExpiresAtEpochMs = nowEpochMs + 120_000L,
            absoluteExpiresAtEpochMs = nowEpochMs + 180_000L,
        )

    private fun verifiedEmailFirstRun(
        epoch: Long,
        actorId: String,
    ) = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(
        snapshot = FirstRunOnboardingPolicy.initialEmailAccount(epoch),
        actorBinding = FirstRunOpaqueActorBinding.fromProvider(actorId),
        receiptHash = FirstRunReceiptHash.fromSha256Hex("1".repeat(64)),
    ).current.let { awaitingSafety ->
        FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(
            snapshot = awaitingSafety,
            request = FirstRunOnboardingAttemptRequest.forSnapshot(
                snapshot = awaitingSafety,
                requestId = "req_${"2".repeat(64)}",
                attemptId = "att_${"3".repeat(64)}",
            ),
            receiptHash = FirstRunReceiptHash.fromSha256Hex("4".repeat(64)),
        ).current
    }

    private fun deletionRecoveryVerifiedSession(rotation: Long): GatewayFieldSession {
        val nowEpochMs = System.currentTimeMillis()
        return GatewayFieldSession.legacyVerified(
            gatewayBaseUrl = GATEWAY_ORIGIN,
            actorId = ACTOR_ID,
            deviceId = DEVICE_ID,
            cookiePair = "${GatewayFieldSession.COOKIE_NAME}=$ACCESS_SECRET",
            expiresAtEpochMs = nowEpochMs + 60_000L + rotation,
            sessionScope = GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
        )
    }

    private companion object {
        const val GATEWAY_ORIGIN = "https://gateway.example.test"
        const val ACTOR_ID = "actor-test"
        const val BACKEND_ACTOR_ID = "00000000-0000-4000-8000-000000000001"
        const val DEVICE_ID = "device-installation-00000001"
        const val NOW_EPOCH_MS = 1_000_000L
        const val ACCESS_SECRET = "access-token-value-0000000000000001"
        const val REFRESH_SECRET =
            "refresh-token-value-000000000000000000000000000000000000000000000000"
        val FAMILY_ID = "f".repeat(32)
    }
}
