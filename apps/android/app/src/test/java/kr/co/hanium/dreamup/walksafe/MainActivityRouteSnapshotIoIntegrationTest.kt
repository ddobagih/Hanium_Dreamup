package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.RouteSnapshotIoLane
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssPositionObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import org.json.JSONObject
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionPolicy
import org.junit.Assert.*
import org.junit.Test

/** Actual Main storage/ownership bodies + real Store/AES, not Main.onCreate/Android UI execution. */
class MainActivityRouteSnapshotIoIntegrationTest {
    private val main = MainActivity()
    private val owner = RouteSnapshotOwner("snapshot-actor", "account:1")
    private val scope = RouteSnapshotScope(owner, "walk-a")
    private val destination = RoutePoint(0.0, 0.001, "목적지")
    private val route = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(111, 90),
        listOf(RoutePoint(0.0, 0.0), destination), listOf(
            WalkingRouteGuidePoint(0, RoutePoint(0.0, 0.0), "출발", 0, 111, 90f, 200, "start"),
            WalkingRouteGuidePoint(1, destination, "도착", 111, 0, 90f, 201, "arrival"),
        ), providerRouteId = "first-route")

    @Test fun actualResponseStorageCallReturnsWhileTheArchiveWriteIsPending() {
        val io = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        val storage = Storage()
        val speechFiles = kotlin.io.path.createTempDirectory("main-route-io-speech").toFile()
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
        ExistingSpeechCallerFixture(speechFiles).use { speech ->
            try {
                val activity = speech.main
                val lane = RouteSnapshotIoLane(io)
                val fixture = Fixture(storage)
                val lease = lane.attach(fixture::store).let { (lease, result) ->
                    assertTrue(result.get(5, TimeUnit.SECONDS)?.purged == true)
                    lease
                }
                assertTrue(activity.submitRouteSnapshotOwnershipStorage(lane, lease, owner, scope, null,
                    { lane.isCurrent(lease, lease.operation.get()) }).get(5, TimeUnit.SECONDS)?.ready == true)
                storage.blockWrite = true
                val returned = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                caller.execute {
                    try {
                        val saving = activity.submitRouteSnapshotResponseStorage(lane, lease,
                            { lane.isCurrent(lease, lease.operation.get()) }, scope, route, destination, null)
                        assertTrue(storage.writeEntered.await(5, TimeUnit.SECONDS))
                        assertFalse(saving.isDone)
                        // Existing typed Caller422 fixture executes actual Main ensure/listener,
                        // real Actor and its platform runtime callbacks; no listener is copied.
                        speech.ensure()
                        val id = speech.speakNavigation()
                        speech.start(id, 1_011L)
                        assertTrue(activity.isHandsFreeVoiceSpeechOutputActive({ speech.actor }))
                        val positioning = field("positioningCoordinator").get(activity) as PositioningCoordinator
                        val navigator = field("routeNavigator").get(activity) as RouteNavigator
                        navigator.setRoute(route)
                        val routeId = navigator.currentRouteId()
                        val projectionFix = TrustedLocation(0.0, 0.0, 3f, 1_000L)
                        assertNotNull(navigator.currentProjection(projectionFix))
                        val consume = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                            .findVirtual(MainActivity::class.java, "applyPositionConfidenceDecision",
                                MethodType.methodType(java.lang.Boolean.TYPE,
                                    PositioningSnapshot::class.java, java.lang.Boolean.TYPE)).bindTo(activity)
                        listOf(1_000L to 1_000L, 2_000L to 13_001L, 14_000L to 14_000L,
                            16_000L to 16_000L).zip(listOf(false, true, true, false)).forEachIndexed { index, (times, paused) ->
                            val snapshot = positioning.observeGnss(GnssPositionObservation(37.5, 127.0, 1.5,
                                times.first, times.second))
                            // This is the existing production confidence consumer with announcements
                            // disabled, not Fused Location/handleLocationUpdate or whole UI execution.
                            assertEquals(paused, consume.invokeWithArguments(snapshot, false))
                            assertEquals(paused, field("positionGuidancePaused").get(activity))
                            if (index == 1) {
                                // Actual Main LOW/UNAVAILABLE transition interrupts the real
                                // Navigator's evidence without changing its installed route.
                                assertNull(navigator.currentProjection(projectionFix))
                                assertTrue(navigator.hasRoute())
                                assertEquals(routeId, navigator.currentRouteId())
                            }
                        }
                        speech.done(id, 1_019L)
                        assertFalse(activity.isHandsFreeVoiceSpeechOutputActive({ speech.actor },
                            { 1_019L }, { false }))
                        speech.awaitAppendCount(3)
                        val rows = speech.rows()
                        assertEquals(listOf("REQUESTED", "STARTED", "DONE"), rows.map { it.getString("state") })
                        assertEquals(listOf(1_000L, 1_011L, 1_019L), rows.map { it.getLong("elapsed_realtime_ms") })
                        assertFalse(saving.isDone)
                        assertEquals(1L, storage.releaseWrite.count)
                        assertNull(storage.envelope)
                    } catch (caught: Throwable) { failure.set(caught) }
                    finally { returned.countDown() }
                }
                assertTrue("Actual archive write was reached", storage.writeEntered.await(5, TimeUnit.SECONDS))
                assertTrue("Same caller must process speech and Main position confidence while archive write is blocked",
                    returned.await(1, TimeUnit.SECONDS))
                failure.get()?.let { throw it }
            } finally {
                storage.releaseWrite.countDown()
                caller.shutdown(); io.shutdown()
                caller.awaitTermination(5, TimeUnit.SECONDS); io.awaitTermination(5, TimeUnit.SECONDS)
                GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
            }
        }
    }

    @Test fun pendingHostFileWriteThenActualPurgeCannotBeRegeneratedByTheOldResponse() {
        val directory = kotlin.io.path.createTempDirectory("main-route-io-file").toFile()
        val archive = File(directory, "route.aead")
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val writes = AtomicInteger()
        val storage = object : RouteSnapshotStorage {
            override fun read(): String? = archive.takeIf(File::isFile)?.readText()
            override fun write(envelope: String): Boolean {
                archive.writeText(envelope)
                writes.incrementAndGet()
                writeEntered.countDown()
                check(releaseWrite.await(5, TimeUnit.SECONDS))
                return read() == envelope
            }
            override fun clear(): Boolean = !archive.exists() || archive.delete()
        }
        val io = Executors.newSingleThreadExecutor()
        val lane = RouteSnapshotIoLane(io)
        val keys = Keys()
        val process = RouteSnapshotProcessCoordinator()
        try {
            val (lease, initialized) = lane.attach {
                // Real host AES, with a test-only key provider; AndroidKeyStore is not run.
                EncryptedRouteSnapshotStore(storage,
                    VersionedLocalAead(AeadKeyPolicy("test.main.route.file.v", 1, setOf(1)), keys),
                    testOnly = Unit, elapsedRealtimeMs = { 1_000L }, bootCount = { 7 },
                    processCoordinator = process)
            }
            assertTrue(initialized.get(5, TimeUnit.SECONDS)?.purged == true)
            val current = { lane.isCurrent(lease, lease.operation.get()) }
            assertTrue(main.submitRouteSnapshotOwnershipStorage(lane, lease, owner, scope, null,
                current).get(5, TimeUnit.SECONDS)?.ready == true)
            val response = lease.response.get()
            val responseCurrent = { current() && lease.response.get() === response }
            val first = main.submitRouteSnapshotResponseStorage(lane, lease, responseCurrent,
                scope, route, destination, null)
            assertTrue(writeEntered.await(5, TimeUnit.SECONDS))
            assertTrue(archive.isFile)
            assertFalse(first.isDone)
            assertEquals(1L, releaseWrite.count)
            lane.invalidateResponse(lease)
            val purge = main.submitRouteSnapshotScopePurge(lane, lease, scope)
            val late = main.submitRouteSnapshotResponseStorage(lane, lease, responseCurrent,
                scope, route, destination, null)
            assertFalse(purge.isDone)
            assertTrue(archive.isFile)
            releaseWrite.countDown()
            assertNotNull(first.get(5, TimeUnit.SECONDS)) // Actual Store save/load opens host ciphertext.
            assertTrue(main.awaitRouteSnapshotIoPurge(purge)) // clear/delete and verified readback.
            assertNull(late.get(5, TimeUnit.SECONDS))
            assertFalse(archive.exists())
            assertEquals(1, writes.get())
            println("host_route_archive=${archive.absolutePath}; exists_after_purge=${archive.exists()}; writes=${writes.get()}")
        } finally {
            releaseWrite.countDown()
            io.shutdown()
            assertTrue(io.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun aRevokedResponseIsRejectedBeforeAnyArchiveWrite() {
        val f = QueuedFixture()
        val current = AtomicBoolean(true)
        val saved = main.submitRouteSnapshotResponseStorage(f.lane, f.lease, current::get,
            scope, route, destination, null)
        current.set(false)
        f.drain()
        assertNull(saved.get())
        assertEquals(0, f.storage.writes)
    }

    @Test fun pendingWriteThenTheActualMainScopePurgeLeavesNoArchiveAndCannotRegenerate() {
        val f = QueuedFixture()
        val response = f.lease.response.get()
        val saved = main.submitRouteSnapshotResponseStorage(f.lane, f.lease,
            { f.lease.response.get() === response }, scope, route, destination, null)
        // Execute the save before cancel, exactly as an already running write can finish first.
        f.queue.runNext()
        assertNotNull(saved.get())
        f.lane.invalidateResponse(f.lease)
        val purged = main.submitRouteSnapshotScopePurge(f.lane, f.lease, scope)
        val late = main.submitRouteSnapshotResponseStorage(f.lane, f.lease,
            { f.lease.response.get() === response }, scope, route, destination, null)
        f.drain()
        assertTrue(purged.get())
        assertNull(late.get())
        assertNull(f.storage.envelope)
        assertEquals(1, f.storage.writes)
    }

    @Test fun aQueuedSaveCancelledBeforeStartingCannotOutrunItsOrderedPurge() {
        val f = QueuedFixture()
        val response = f.lease.response.get()
        val saved = main.submitRouteSnapshotResponseStorage(f.lane, f.lease,
            { f.lease.response.get() === response }, scope, route, destination, null)
        f.lane.invalidateResponse(f.lease)
        val purge = main.submitRouteSnapshotScopePurge(f.lane, f.lease, scope)
        f.drain()
        assertNull(saved.get())
        assertTrue(purge.get())
        assertEquals(0, f.storage.writes)
        assertNull(f.storage.envelope)
    }

    @Test fun activityReplacementRetiresTheOldLeaseBeforeTheSameWalkIsClaimed() {
        val f = QueuedFixture()
        val old = f.lease
        val (next, initialized) = f.lane.attach(f.fixture::store)
        val claimed = main.submitRouteSnapshotOwnershipStorage(f.lane, next, owner, scope, null,
            { f.lane.isCurrent(next, next.operation.get()) })
        f.lane.detach(old) // Delayed old destroy must not release the replacement lease.
        f.drain()
        assertTrue(initialized.get()?.purged == true)
        assertTrue(claimed.get()?.ready == true)
        val saved = main.submitRouteSnapshotResponseStorage(f.lane, next,
            { f.lane.isCurrent(next, next.operation.get()) }, scope, route, destination, null)
        f.drain()
        assertNotNull(saved.get())
        val oldPurge = main.submitRouteSnapshotScopePurge(f.lane, old, scope)
        f.drain()
        assertFalse(oldPurge.get())
        assertNotNull(f.storage.envelope)
    }

    @Test fun sameAccountRotationAndReroutePreserveTheFirstArchiveAndItsAbsoluteTtl() {
        val f = QueuedFixture()
        val first = f.save(route)
        val boundAgain = main.submitRouteSnapshotOwnershipStorage(f.lane, f.lease, owner.copy(), scope,
            null, { f.current() })
        f.drain()
        assertTrue(boundAgain.get()?.ready == true)
        f.fixture.elapsed += 200L
        val replacement = f.save(route.copy(providerRouteId = "reroute"))
        assertEquals("first-route", replacement.route.providerRouteId)
        assertEquals(first.savedAtEpochMs, replacement.savedAtEpochMs)
        assertEquals(first.savedAtElapsedRealtimeMs, replacement.savedAtElapsedRealtimeMs)
        assertEquals(first.expiresAtEpochMs, replacement.expiresAtEpochMs)
        assertEquals(ROUTE_SNAPSHOT_TTL_MS, first.expiresAtEpochMs - first.savedAtEpochMs)
        assertEquals(1, f.storage.writes)
    }

    @Test fun realEndWalkPrecedesNewClaimAndLateOldEndCannotEndTheNewWalk() {
        val f = QueuedFixture()
        f.save(route)
        val next = scope.copy(walkSessionId = "walk-b")
        val started = main.submitRouteSnapshotOwnershipStorage(f.lane, f.lease, owner, next,
            scope.walkSessionId, { f.current() })
        f.drain()
        assertTrue(started.get()?.ready == true)
        val late = main.submitRouteSnapshotOwnershipStorage(f.lane, f.lease, owner, next,
            scope.walkSessionId, { f.current() })
        f.drain()
        assertTrue(late.get()?.ready == true)
        val saved = main.submitRouteSnapshotResponseStorage(f.lane, f.lease, { f.current() }, next,
            route.copy(providerRouteId = "walk-b"), destination, null)
        f.drain()
        assertEquals("walk-b", saved.get()?.route?.providerRouteId)
    }

    @Test fun realVerifiedFalseIsNotDeletionSuccessAndRevokesThePreviousStoreLease() {
        val f = QueuedFixture()
        f.save(route)
        f.storage.pretendClear = true
        val purged = main.submitRouteSnapshotAccountPurgeStorage(f.lane, f.lease, { f.current() })
        f.drain()
        assertFalse(main.awaitRouteSnapshotIoPurge(purged))
        assertTrue(f.lease.purgeFailed)
        val late = main.submitRouteSnapshotResponseStorage(f.lane, f.lease, { f.current() }, scope,
            route, destination, null)
        f.drain()
        assertNull(late.get())
        assertNotNull(f.storage.envelope)
    }

    @Test fun pendingRealAccountPurgeIsAwaitedOnlyOnTheExternalCleanupWorker() {
        val f = QueuedFixture()
        f.save(route)
        val future = main.submitRouteSnapshotAccountPurgeStorage(f.lane, f.lease, { f.current() })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = CountDownLatch(1)
            val result = executor.submit<Boolean> {
                waiting.countDown()
                main.awaitRouteSnapshotIoPurge(future)
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            assertFalse(future.isDone)
            assertFalse(result.isDone)
            f.drain()
            assertTrue(result.get(5, TimeUnit.SECONDS))
            assertNull(f.storage.envelope)
        } finally { executor.shutdown(); executor.awaitTermination(5, TimeUnit.SECONDS) }
    }

    @Test fun enqueueRejectionIsARealFalseResultAndNeverADeletionAck() {
        val f = QueuedFixture()
        f.queue.reject = true
        val future = main.submitRouteSnapshotAccountPurgeStorage(f.lane, f.lease, { f.current() })
        assertTrue(future.isDone)
        assertFalse(main.awaitRouteSnapshotIoPurge(future))
    }

    @Test fun capturedPolylineStepPointsAndProviderProvenanceCannotBeRetaggedAfterSubmission() {
        val f = QueuedFixture()
        val points = route.polyline.toMutableList()
        val stepPoints = route.polyline.toMutableList()
        val candidates = mutableListOf(DestinationCoordinateCandidate(DestinationCoordinateKind.FRONT, destination))
        val response = route.copy(polyline = points, steps = listOf(
            WalkingRouteStep(0, 111, 90, stepPoints, "직진", "길", 11, null, 7)))
        val saved = main.submitRouteSnapshotResponseStorage(f.lane, f.lease, { f.current() }, scope,
            response, destination, DestinationCoordinateEvidence(DestinationCoordinateKind.FRONT, candidates))
        points.clear(); stepPoints.clear(); candidates.clear()
        f.drain()
        val snapshot = requireNotNull(saved.get())
        assertEquals(route.polyline, snapshot.route.polyline)
        assertEquals(route.polyline, snapshot.route.steps.single().points)
        assertEquals(7, snapshot.route.steps.single().roadType)
        assertEquals(destination, snapshot.destinationCoordinateEvidence?.candidates?.single()?.point)
    }

    @Test fun storageWallAndMonotonicClocksBelongToActualExecutionNotSubmission() {
        val f = QueuedFixture()
        val submittedWall = System.currentTimeMillis()
        val submittedElapsed = f.fixture.elapsed
        val result = main.submitRouteSnapshotResponseStorage(f.lane, f.lease, { f.current() }, scope,
            route, destination, null)
        f.fixture.elapsed = submittedElapsed + 2_000L
        val beforeActualSave = System.currentTimeMillis()
        f.drain()
        val afterActualSave = System.currentTimeMillis()
        val saved = requireNotNull(result.get())
        assertEquals(submittedElapsed + 2_000L, saved.savedAtElapsedRealtimeMs)
        assertTrue(saved.savedAtEpochMs in beforeActualSave..afterActualSave)
        assertTrue(saved.savedAtEpochMs >= submittedWall)
    }

    @Test fun optionalRawConsentChangeKeepsTheSameActualRequestLeaseAndAuthenticatedStorageAdmission() {
        val f = QueuedFixture()
        val permissions = PermissionSessionPolicy().also { it.authenticated(owner.actorId); it.setRawCollectionConsent(true) }
        field("routeRequestGeneration").set(main, 1)
        val inFlight = field("routeRequestInFlight").get(main) as AtomicBoolean
        inFlight.set(true)
        val saved = main.submitRouteSnapshotResponseStorage(f.lane, f.lease,
            { f.current() && main.routeResponseInstallLeaseCurrent(1) {
                main.routeSnapshotNavigationPermissionCurrent(permissions, owner.actorId)
            } }, scope, route, destination, null)
        permissions.setRawCollectionConsent(false) // Existing optional policy mutation, no new navigation gate.
        f.drain()
        assertNotNull(saved.get())
        assertTrue(inFlight.get()) // Storage itself never clears a request's UI installation lease.
    }

    @Test fun oldActualRequestLeaseCannotClearTheNextRequestOrWriteItsResponse() {
        val f = QueuedFixture()
        field("routeRequestGeneration").set(main, 1)
        val inFlight = field("routeRequestInFlight").get(main) as AtomicBoolean
        inFlight.set(true)
        val permissions = field("permissionSessionPolicy").get(main) as PermissionSessionPolicy
        permissions.authenticated(owner.actorId)
        val old = main.submitRouteSnapshotResponseStorage(f.lane, f.lease,
            { f.current() && main.routeResponseInstallLeaseCurrent(1) {
                main.routeSnapshotNavigationPermissionCurrent(permissions, owner.actorId)
            } }, scope, route, destination, null)
        main.invalidateActiveRouteRequestLease()
        field("routeRequestGeneration").set(main, 3)
        inFlight.set(true)
        f.drain()
        assertNull(old.get())
        assertEquals(3, field("routeRequestGeneration").get(main))
        assertTrue(inFlight.get())
        assertEquals(0, f.storage.writes)
    }

    @Test fun oldAccountPurgeAckCannotClearANewPreparationAfterItsCurrentRead() {
        val f = QueuedFixture()
        f.save(route)
        lateinit var old: Any
        val oldPurge = main.submitRouteSnapshotAccountPurgeStorage(f.lane, f.lease, { f.current() },
            onAdmission = { old = main.beginRouteSnapshotPreparation(f.lane, f.lease) })
        f.drain()
        val oldPurged = main.awaitRouteSnapshotIoPurge(oldPurge)
        assertTrue(oldPurged)
        assertNull(f.storage.envelope)
        val verified = AtomicInteger()
        lateinit var next: Any
        lateinit var nextPurge: CompletableFuture<Boolean>
        val committed = main.publishRouteSnapshotAccountPurgeResult(f.lane, f.lease, old, oldPurged,
            current = {
                val read = f.lane.isCurrent(f.lease, old)
                // Exact same Main memory-preparation body runs between the old read and ACK.
                f.storage.throwClear = true
                nextPurge = main.submitRouteSnapshotScopePurge(f.lane, f.lease, scope,
                    onAdmission = { next = main.beginRouteSnapshotPreparation(f.lane, f.lease) })
                read
            }, markVerified = { verified.incrementAndGet() })
        assertFalse(committed)
        assertEquals(0, verified.get())
        assertEquals(true, field("routeSnapshotPreparationPending").get(main))
        f.drain()
        val nextPurged = main.awaitRouteSnapshotIoPurge(nextPurge)
        assertFalse(nextPurged)
        assertTrue(main.publishRouteSnapshotAccountPurgeResult(f.lane, f.lease, next, nextPurged,
            { f.lane.isCurrent(f.lease, next) }, {}))
        assertEquals(true, field("routeSnapshotPurgeFailed").get(main))
    }

    @Test fun claimedOldExpiryCannotSupersedeAnAdmittedAccountPreparation() {
        val f = QueuedFixture()
        f.save(route)
        val old = f.lease.operation.get()
        val expiry = Runnable { }
        @Suppress("UNCHECKED_CAST")
        val schedule = field("routeSnapshotExpiryRunnable").get(main) as AtomicReference<Runnable?>
        schedule.set(expiry)
        lateinit var accountOperation: Any
        lateinit var accountPurge: CompletableFuture<Boolean>
        val submitted = main.submitRouteSnapshotExpiryPurge(f.lane, f.lease, old, scope,
            expiryClaimIsCurrent = {
                val read = schedule.compareAndSet(expiry, null) && f.lane.isCurrent(f.lease, old)
                // Same actual account-purge/preparation leaves between the original claim
                // read and expiry enqueue; no Handler Runnable is manufactured as UI proof.
                accountPurge = main.submitRouteSnapshotAccountPurgeStorage(f.lane, f.lease,
                    { f.current() }, onAdmission = {
                        accountOperation = main.beginRouteSnapshotPreparation(f.lane, f.lease)
                    })
                read
            })
        assertNull(submitted)
        assertSame(accountOperation, f.lease.operation.get())
        assertEquals(true, field("routeSnapshotPreparationPending").get(main))
        f.drain()
        val accountPurged = main.awaitRouteSnapshotIoPurge(accountPurge)
        assertTrue(accountPurged)
        assertTrue(main.publishRouteSnapshotAccountPurgeResult(f.lane, f.lease, accountOperation, accountPurged,
            { f.lane.isCurrent(f.lease, accountOperation) }, {}))
        assertEquals(false, field("routeSnapshotPreparationPending").get(main))
        assertEquals(false, field("routeSnapshotPurgeFailed").get(main))
        assertNull(f.storage.envelope)
    }

    @Test fun currentExpiryAdmitsOneOrderedPurgeAndDuplicateClaimQueuesNothing() {
        val f = QueuedFixture()
        f.save(route)
        val old = f.lease.operation.get()
        val expiry = Runnable { }
        @Suppress("UNCHECKED_CAST")
        val schedule = field("routeSnapshotExpiryRunnable").get(main) as AtomicReference<Runnable?>
        schedule.set(expiry)
        val claim = { schedule.compareAndSet(expiry, null) && f.lane.isCurrent(f.lease, old) }
        val submitted = requireNotNull(main.submitRouteSnapshotExpiryPurge(f.lane, f.lease, old, scope, claim))
        assertNull(main.submitRouteSnapshotExpiryPurge(f.lane, f.lease, old, scope, claim))
        assertEquals(1, f.queue.tasks.size)
        assertFalse(submitted.future.isDone)
        assertEquals(true, field("routeSnapshotPreparationPending").get(main))
        f.drain()
        assertTrue(submitted.future.get())
        assertNull(f.storage.envelope)
    }

    @Test fun actualAuthenticatedPermissionOwnerExpiryRejectsItsQueuedStorageWork() {
        val f = QueuedFixture()
        val permissions = PermissionSessionPolicy().also { it.authenticated(owner.actorId) }
        val result = main.submitRouteSnapshotResponseStorage(f.lane, f.lease,
            { f.current() && main.routeSnapshotNavigationPermissionCurrent(permissions, owner.actorId) },
            scope, route, destination, null)
        permissions.authenticationExpired()
        f.drain()
        assertNull(result.get())
        assertEquals(0, f.storage.writes)
    }

    @Test fun destroyFencesQueuedResponseAndDetachRunsOnTheSameLaneWithoutWaiting() {
        val f = QueuedFixture()
        val result = main.submitRouteSnapshotResponseStorage(f.lane, f.lease, { f.current() },
            scope, route, destination, null)
        f.lane.detach(f.lease)
        assertTrue(f.lease.closed.get())
        assertFalse(result.isDone)
        f.drain()
        assertNull(result.get())
        assertEquals(0, f.storage.writes)
    }

    @Test fun actualStorageBoundaryThrowBecomesFalseAndKeepsCleanupFailureRecorded() {
        val f = QueuedFixture()
        f.save(route)
        f.storage.throwClear = true
        val result = main.submitRouteSnapshotAccountPurgeStorage(f.lane, f.lease, { f.current() })
        f.drain()
        assertFalse(main.awaitRouteSnapshotIoPurge(result))
        assertTrue(f.lease.purgeFailed)
        assertNotNull(f.storage.envelope)
    }

    @Test fun completionLocationPolicyUsesTheNewFixAndActualCompletionElapsed() {
        val origin = TrustedLocation(0.0, 0.0, 3f, 1_000L)
        assertEquals(RouteResponseInstallLocationStatus.CONNECTED,
            routeResponseInstallLocationStatus(route, origin, origin, 1_100L))
        assertEquals(RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE,
            routeResponseInstallLocationStatus(route, origin, null, 1_100L))
        assertEquals(RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED,
            routeResponseInstallLocationStatus(route, origin, TrustedLocation(0.001, 0.0005, 3f, 2_000L), 2_100L))
        assertEquals(RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE,
            routeResponseInstallLocationStatus(route, origin, origin, 11_001L))
    }

    @Test fun sourceAllArchiveIoIsDispatchedAndDestroyDoesNotShutdownTheProcessLane() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteArchiveController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val main = source()
        MainActivityRefactorSource.composition(main, "routeArchiveController")
        val create = MainActivityRefactorSource.member(main, "onCreate")
        assertTrue(create.contains("initializeRouteSnapshotOnIoWorker()"))
        assertFalse(create.contains("routeSnapshotStore.purgeExpired("))
        val init = MainActivityRefactorSource.member(main, "initializeRouteSnapshotOnIoWorker")
        assertTrue(init.contains("routeArchiveController.initialize {"))
        assertTrue(method("initialize").contains("lane.attach { factory().also { routeSnapshotStore = it } }"))
        assertTrue(init.contains("context.getSharedPreferences(\"walksafe_route_snapshot\""))
        assertTrue(main.contains("val ROUTE_SNAPSHOT_IO_LANE = RouteSnapshotIoLane(Executors.newSingleThreadExecutor())"))
        assertTrue(MainActivityRefactorSource.member(main, "onDestroy").contains("routeArchiveController.detach()"))
        assertTrue(method("detach").contains("lane.detach(routeSnapshotIoLease)"))
        assertFalse(main.contains("ROUTE_SNAPSHOT_IO_LANE.shutdown"))
        assertFalse(owned.contains("lane.shutdown"))
        val expired = method("onEncryptedRouteSnapshotExpired")
        assertTrue(expired.contains("submitRouteSnapshotExpiryPurge(lane, lease,"))
        val schedule = method("scheduleEncryptedRouteSnapshotExpiry")
        assertTrue(schedule.contains("routeSnapshotExpiryRunnable.compareAndSet(expiry, null)"))
        assertTrue(schedule.contains("onEncryptedRouteSnapshotExpired(scope, lease, operation)"))
        val admission = method("submitRouteSnapshotExpiryPurge")
        assertTrue(admission.contains("lane.commit(lease, expiryOperation, expiryClaimIsCurrent)"))
        assertTrue(admission.indexOf("lane.commit(") < admission.indexOf("submitRouteSnapshotScopePurge("))
        assertFalse(admission.contains("reportCleanupCallbackHandler"))
        assertTrue(MainActivityRefactorSource.factory(main, "createNavigationSessions").contains("lane = { ROUTE_SNAPSHOT_IO_LANE }"))
    }

    @Test fun sourceActualResponseConsumesTheSharedBodyAndRechecksOwnerAndLocationOnUiCompletion() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val request = method("requestRoute", 1)
        val dispatch = request.indexOf("archive.submitRouteSnapshotResponseStorage(")
        val completion = request.indexOf("postToUi completeSnapshot@")
        val location = request.indexOf("val completedLocation = facts.currentLocation")
        val evaluation = request.indexOf("route, origin, completedLocation, this.elapsedRealtimeMs()")
        val install = request.indexOf("installRequestedRoute(route, destination, origin,")
        assertTrue(dispatch in 0 until completion)
        assertTrue(completion in 0 until location)
        assertTrue(location in 0 until evaluation)
        assertTrue(evaluation in 0 until install)
        assertTrue(request.contains("gateway.session === gatewaySession"))
        assertTrue(request.contains("lifecycleOwner.isRuntimeEpochCurrent(expectedWalkEpoch)"))
        assertTrue(request.contains("routeRequestGeneration == requestId"))
        assertTrue(request.contains("!facts.navigationAllowed"))
        assertTrue(request.contains("archive.onEncryptedRouteSnapshotExpired(scope, lease, operation, ::storageAdmissionCurrent)"))
        assertFalse(request.contains("onEncryptedRouteSnapshotExpired(scope)"))
        assertFalse(request.substring(dispatch).contains(".join()"))
        assertFalse(request.substring(dispatch).contains(".get() == true"))
        val compatibility = MainActivityRefactorSource.member(owned, "installRequestedRoute")
        assertTrue(compatibility.contains("installRequestedRoute(route, destination, origin, coordinateEvidence, reason,"))
        assertTrue(compatibility.contains("routeResponseLocationStatus(route, origin, origin, origin.elapsedRealtimeMs) =="))
        assertTrue(compatibility.contains("RouteResponseInstallLocationStatus.APPROACH_REQUIRED"))
        assertTrue(request.contains("completedLocationStatus == RouteResponseInstallLocationStatus.APPROACH_REQUIRED)"))
        val installer = method("installRequestedRoute", 1)
        assertTrue(installer.contains("approachRequired: Boolean"))
        assertTrue(installer.contains("routeNavigator.setRoute("))
        assertTrue(installer.contains("origin = origin"))
        assertTrue(installer.contains("waitForInitialRouteJoin = approachRequired"))
        assertTrue(method("routeResponseLocationStatus").contains("allowInitialStartConnector = false"))
        val archive = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteArchiveController.kt")
        val storage = MainActivityRefactorSource.member(archive, "submitRouteSnapshotResponseStorage")
        assertTrue(storage.contains("lane.submit<StoredRouteSnapshot?>(lease, null, current)"))
        assertTrue(storage.contains("routeSnapshotResponseArchive(store, scope, captured, destination, provenance)"))
        val wire = MainActivityRefactorSource.composition(source(), "routeAcquisitionController")
        assertTrue(wire.contains("val archive = this.archive.value"))
        assertTrue(wire.contains("RouteAcquisitionController(run, archive,"))
        assertTrue(wire.contains("override val currentLocation get() = positioning.value.currentRouteLocationOrNull()"))
        assertTrue(wire.contains("override val navigationAllowed: Boolean get() = activityFacts.navigationAllowed"))
        assertTrue(ReportStaticSourceInspector.blockAfter(MainActivityRefactorSource.factory(source(), "createNavigationSessions"), "facts = { object : AcquisitionActivityFacts").contains("override val navigationAllowed get() = currentNavigationCollectionAllowsWork()"))
    }

    @Test fun sourceOptionalRawConsentIsNotANewNavigationReceiptGateAndExactResetConsumesPreparedAck() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val main = source()
        val request = method("requestRoute", 1)
        assertFalse(request.contains("RAW_SOURCE_COLLECTION"))
        assertFalse(request.contains("integratedConsentSession.currentConfirmationOrNull"))
        val resetSource = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/reset/AccountDeletionResetStorage.kt")
        val reset = MainActivityRefactorSource.member(resetSource, "clearPlainResetArtifacts")
        val privacy = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/session/privacy/PrivacyAuthorityComposition.kt")
        val resetWire = MainActivityRefactorSource.member(privacy, "createAccountDeletionResetStorage")
        assertTrue(MainActivityRefactorSource.property(main, "accountDeletionResetStorage").contains("privacyAuthority.createAccountDeletionResetStorage()"))
        MainActivityRefactorSource.composition(main, "accountDeletionResetStorage")
        MainActivityRefactorSource.composition(main, "routeArchiveController")
        val privacyFactory = MainActivityRefactorSource.factory(main, "createPrivacyAuthorityComposition")
        assertTrue(privacyFactory.contains("PrivacyAuthorityComposition("))
        assertTrue(privacyFactory.contains("routeArchive = { routeArchiveController }"))
        assertTrue(resetWire.contains("preparation.verified.get()"))
        assertTrue(resetWire.contains("routeSnapshotResetPreparationIsCurrent(preparation)"))
        assertFalse(reset.contains("purgeEncryptedRouteSnapshotForAccount()"))
        assertFalse(reset.contains(".get() == true"))
        assertTrue(reset.contains("val routeSnapshotPurged = routePurgeVerified()"))
        assertTrue(reset.contains("if (!routeSnapshotPurged) return false"))
        val workerSource = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/reset/AccountDeletionInstallationResetController.kt")
        val worker = MainActivityRefactorSource.member(workerSource, "resetAfterConfirmedDeletion")
        assertTrue(worker.indexOf("prepared.awaitOriginalAcknowledgements()") in 0 until worker.indexOf("coordinator.beginAndReconcile()"))
        assertTrue(worker.contains("purged && prepared.isCurrent()"))
        val participants = MainActivityRefactorSource.member(privacy, "captureInstallationResetParticipants")
        assertTrue(MainActivityRefactorSource.member(main, "captureInstallationResetParticipants").contains("privacyAuthority.captureInstallationResetParticipants()"))
        assertTrue(participants.contains("awaitRoute = { preparation -> routeArchive().awaitRouteSnapshotAccountPurgeOnWorker(preparation) }"))
        assertTrue(participants.contains("routeIsCurrent = { preparation -> routeArchive().routeSnapshotResetPreparationIsCurrent(preparation) }"))
        val startupSource = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/startup/PrivacyStartupInspectionController.kt")
        assertTrue(startupSource.contains("val routePurged = awaitRoute(route)"))
        assertTrue(startupSource.contains("return speechPurgedAndDrained && routePurged"))
        assertTrue(MainActivityRefactorSource.member(main, "capturePrivacyStartupResetPreparation").contains("awaitRoute = ::awaitRouteSnapshotAccountPurgeOnWorker"))
        assertTrue(MainActivityRefactorSource.member(main, "startPrivacyStartupInspection").contains("privacyStartupInspectionController.startInspection(prepare, inspect, onReady)"))
    }

    /** Reuse accepted Caller422 typed owners/runtime unchanged; reflection is test-only access. */
    private class ExistingSpeechCallerFixture(files: File) : AutoCloseable {
        private val type = Class.forName(
            "kr.co.hanium.dreamup.walksafe.MainActivitySpeechOutputStorageCallerIntegrationTest\$Fixture")
        private val fixture = type.declaredConstructors.single { constructor ->
            constructor.parameterTypes.size == 2 && constructor.parameterTypes[0] == File::class.java
        }.apply { isAccessible = true }.newInstance(files, null)
        val main: MainActivity get() = invoke("getMain") as MainActivity
        val actor: AndroidFeedbackActuator get() = invoke("getActor") as AndroidFeedbackActuator
        private val runtime: Any get() = invoke("getRuntime")!!
        fun ensure() { invoke("ensure") }
        fun speakNavigation(): String = invoke("speakNavigation") as String
        fun start(id: String, time: Long) = runtimeCallback("start", id, time)
        fun done(id: String, time: Long) = runtimeCallback("done", id, time)
        fun awaitAppendCount(count: Int) { invoke("awaitAppendCount", arrayOf(Int::class.javaPrimitiveType!!), count) }
        @Suppress("UNCHECKED_CAST")
        fun rows(): List<JSONObject> = invoke("rows") as List<JSONObject>
        private fun runtimeCallback(name: String, id: String, time: Long) {
            val target = runtime
            target.javaClass.getDeclaredMethod("setNow", java.lang.Long.TYPE).apply { isAccessible = true }
                .invoke(target, time)
            target.javaClass.getDeclaredMethod(name, String::class.java).apply { isAccessible = true }.invoke(target, id)
        }
        private fun invoke(name: String, types: Array<Class<*>> = emptyArray(), vararg values: Any?): Any? =
            type.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(fixture, *values)
        override fun close() { (fixture as AutoCloseable).close() }
    }
    private inner class QueuedFixture {
        val queue = Queue()
        val lane = RouteSnapshotIoLane(queue)
        val storage = Storage()
        val fixture = Fixture(storage)
        val lease = lane.attach(fixture::store).let { (lease, result) ->
            drain(); check(result.get()?.purged == true); lease
        }
        init {
            val result = main.submitRouteSnapshotOwnershipStorage(lane, lease, owner, scope, null, ::current)
            drain(); check(result.get()?.ready == true)
        }
        fun current(): Boolean = lane.isCurrent(lease, lease.operation.get())
        fun drain() = queue.drain()
        fun save(route: WalkingRoute): StoredRouteSnapshot {
            val result = main.submitRouteSnapshotResponseStorage(lane, lease, ::current, scope, route, destination, null)
            drain(); return requireNotNull(result.get())
        }
    }
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        var reject = false
        override fun execute(command: Runnable) {
            if (reject) throw java.util.concurrent.RejectedExecutionException("test-owned snapshot queue")
            tasks.addLast(command)
        }
        fun runNext() = tasks.removeFirst().run()
        fun drain() { while (tasks.isNotEmpty()) runNext() }
    }
    private class Storage : RouteSnapshotStorage {
        var envelope: String? = null
        var pretendClear = false
        var throwClear = false
        var writes = 0
        @Volatile var blockWrite = false
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        override fun read(): String? = envelope
        override fun write(envelope: String): Boolean {
            writeEntered.countDown()
            if (blockWrite) check(releaseWrite.await(5, TimeUnit.SECONDS))
            writes += 1; this.envelope = envelope; return true
        }
        override fun clear(): Boolean {
            if (throwClear) throw IllegalStateException("test-owned storage clear fault")
            if (!pretendClear) envelope = null
            return true
        }
    }
    private class Fixture(val storage: Storage) {
        val process = RouteSnapshotProcessCoordinator()
        val keys = Keys()
        var elapsed = 1_000L
        fun store() = EncryptedRouteSnapshotStore(storage,
            VersionedLocalAead(AeadKeyPolicy("test.main.snapshot.io.v", 1, setOf(1)), keys),
            testOnly = Unit, elapsedRealtimeMs = { elapsed }, bootCount = { 7 }, processCoordinator = process)
    }
    private class Keys : LocalAeadKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        override fun getOrCreate(alias: String): SecretKey = keys.getOrPut(alias) { key() }
        override fun getExisting(alias: String): SecretKey? = keys[alias]
        override fun delete(alias: String): Boolean { keys.remove(alias); return true }
        override fun createFreshAfterVerifiedPurge(alias: String): SecretKey = key().also { keys[alias] = it }
        private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
    private fun field(name: String) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }
    private fun source(): String = listOf(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
        "app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).map(::File).first { it.isFile }.readText()
    private fun body(source: String, start: String, end: String = "private fun "): String =
        source.substringAfter(start).substringBefore("\n    $end")
}
