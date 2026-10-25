package kr.co.hanium.dreamup.walksafe

import java.util.concurrent.Executor
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.startup.interaction.PostLoginDeviceFacts
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Main's real owners with admitted SDK scalar observations and controlled network/TTS ports.
 * This assumes the existing SDK-admitted owner belongs to one stable current session. Main's
 * collection-owner publication/session replacement/deletion admission is outside this fixture.
 * It retains the actual Guide scope and acquisition request/epoch/session checks, but does not
 * validate native SDK permission queries. No native button, HTTP, heading or acoustic TTS claim. */
class MainActivityColdFirstDirectionFlowIntegrationTest {
    @After fun retireGateway() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun coldOnRouteStartConsumesOneIntentAndAcknowledgesItsFirstClockMetresCueOnlyAtDone() {
        val f = ColdFixture()
        f.acquire()
        f.requestAndInstall(false)
        f.installIntro()
        f.dispatchFirstCue()
        assertEquals(1, f.speech.intros.size)
        assertTrue("Summary terminal must precede the first action", f.speech.requests.isEmpty())
        val intro = f.speech.intros.single()
        assertTrue(intro.current())
        assertTrue(f.speech.requests.isEmpty())
        intro.done()
        f.retry()
        f.assertClockMetresThenDone()
    }

    @Test fun coldTwentyTwoMetresFromRouteOffersClosestPointWithoutClaimingAnAcceptedJoin() {
        val f = ColdFixture()
        f.acquire()
        f.requestAndInstall(true)
        f.faceAt(f.base.now)
        f.guide.updateRouteGuidance(f.fix, requireNotNull(f.positions.rawRouteLocation))
        assertTrue("One cold nominal observation cannot confirm a direction", f.speech.requests.isEmpty())
        f.base.now += 300L
        f.faceAt(f.base.now)
        f.guide.updateRouteGuidance(f.fix, requireNotNull(f.positions.rawRouteLocation))
        val reference = f.speech.requests.single()
        assertTrue(reference.message, reference.message.contains("약 3시 방향"))
        assertFalse(reference.message, reference.message.contains("시에서"))
        assertFalse(reference.message, reference.message.contains("시 사이 방향"))
        assertTrue(reference.message, reference.message.contains("직선거리 약 22미터"))
        val nominal = read<RouteFacingGuidanceResult>(f.run.navigator, "offeredFacing")
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, nominal.clockReferenceKind)
        assertFalse("The nominal direction must not promote the reported precision", nominal.clockPrecisionAvailable)
        assertEquals(0.1, read<RouteFacingObservation>(f, "compass").reportedHeadingAccuracyDegrees!!, 0.0)
        assertFalse(reference.message, reference.message.contains("이동하세요"))
        assertTrue(f.run.navigator.isWaitingForInitialRouteJoin())
        val initialTarget = requireNotNull(f.run.navigator.currentRouteApproachTarget(f.base.now))
        assertTrue(initialTarget.distanceM in 20.0..24.0)
        assertEquals(6_000L, initialTarget.observedAtElapsedRealtimeMs)
        assertNull(f.run.navigator.currentAcceptedRouteMatchFor(6_000L))
        f.assertClockMetresThenDone()
        assertTrue("A delivered clock reference is not an accepted join", f.run.navigator.isWaitingForInitialRouteJoin())
        f.refine()
        f.dispatchFirstCue()
        val target = requireNotNull(f.run.navigator.currentRouteApproachTarget(f.base.now))
        assertTrue(target.distanceM in 20.0..24.0)
        assertEquals(8_000L, target.observedAtElapsedRealtimeMs)
        val facing = read<RouteFacingObservation>(f, "compass")
        if (f.speech.requests.size == 1) {
            // An explicit repeat bypasses only cadence and keeps the new actual precision evidence.
            val repeat = requireNotNull(f.run.navigator.currentGuidance(f.base.now, facing))
            f.guide.tryDispatchRouteInstruction(repeat, f.base.epoch,
                RouteCompassHeadingResult(facing, RouteCompassHeadingReason.VALID))
        }
        assertEquals(2, f.speech.requests.size)
        val precise = f.speech.requests.last()
        assertTrue(precise.message, precise.message.contains("약 3시 방향"))
        assertTrue(precise.message, precise.message.contains("직선거리 약 22미터"))
        assertFalse(precise.message, precise.message.contains("시 사이 방향"))
        val previousDone = f.completedCue()
        f.speech.validating = true
        assertTrue(precise.current())
        f.speech.validating = false
        assertEquals(previousDone, f.completedCue())
        precise.done()
        assertNotNull(f.completedCue())
        assertNotEquals(previousDone, f.completedCue())
        assertNull(f.run.navigator.currentAcceptedRouteMatchFor(8_000L))
        assertTrue("Delivered approach is not an accepted route join", f.run.navigator.isWaitingForInitialRouteJoin())
    }

    @Test fun coldCueWaitsForMicrophoneAndActualRetryThenOldDoneCannotCompleteAnEndedEpoch() {
        val f = ColdFixture()
        f.acquire()
        f.requestAndInstall(false)
        f.speech.input = true
        f.dispatchFirstCue()
        assertTrue(f.speech.requests.isEmpty())
        f.speech.input = false
        f.faceAt(f.base.now)
        f.retry()
        assertEquals(1, f.speech.requests.size)
        val request = f.speech.requests.single()
        assertTrue(request.current())
        assertNull(f.completedCue())
        f.base.lifecycle.handle(WalkSessionEvent.EndRequested)
        assertFalse(f.guide.isRouteSpeechCurrent(f.base.epoch, f.acquisition.requestGeneration, f.scope.feedbackGeneration))
        // The transport callback parameter is the action validator, not the whole epoch guard.
        // The actual completed callback performs the full owner/epoch check again.
        request.done()
        assertNull("Retired epoch cannot acknowledge its old cue", f.completedCue())
    }

    @Test fun deniedLocationObservationCannotBeHiddenByThePassedDeviceSnapshot() {
        val f = ColdFixture()
        f.acquire()
        f.requestAndInstall(false)
        f.locationGranted = false
        assertFalse(f.scope.navigationAllowed)
        f.dispatchFirstCue()
        assertTrue(f.speech.requests.isEmpty())
    }

    @Test fun typicalEightTenAndFifteenMetreLowGpsReachesFirstClockMetresCueWithoutAHighSeed() {
        for (accuracyM in listOf(8.0, 10.0, 15.0)) {
            val f = ColdFixture()
            f.acquire(accuracyM)
            assertEquals(PositionQuality.LOW, f.acquired.quality)
            assertEquals(accuracyM, f.acquired.raw?.reportedHorizontalAccuracyM ?: Double.NaN, 0.0)
            assertTrue(requireNotNull(f.acquired.filtered).horizontalUncertaintyM in 5.0..15.0)
            assertEquals(6_000L, f.acquired.raw?.elapsedRealtimeMs)
            assertEquals(6_000L, f.acquired.filtered?.elapsedRealtimeMs)
            assertEquals(f.generation, f.coordinator.currentInputGeneration())
            f.requestAndInstall(false)
            val raw = requireNotNull(f.positions.rawRouteLocation)
            assertEquals(accuracyM.toFloat(), raw.accuracyM, 0f)
            assertEquals(6_000L, raw.elapsedRealtimeMs)
            assertEquals(f.acquired.filtered?.horizontalUncertaintyM?.toFloat(), f.fix.accuracyM)
            f.installIntro()
            f.dispatchFirstCue()
            assertEquals(1, f.speech.intros.size)
            assertEquals("Intro REQUEST/START must precede any first action", 0, f.speech.requests.size)
            val intro = f.speech.intros.single()
            assertTrue(intro.current())
            assertEquals(0, f.speech.requests.size)
            assertNull(f.completedCue())
            intro.done()
            assertEquals("Intro DONE schedules the actual retry instead of bypassing it", 0, f.speech.requests.size)
            f.retry()
            val request = f.speech.requests.single()
            assertTrue(request.message, request.message.contains("12시"))
            assertEquals(accuracyM.toFloat(), requireNotNull(f.positions.rawRouteLocation).accuracyM, 0f)
            assertEquals(6_000L, requireNotNull(f.positions.rawRouteLocation).elapsedRealtimeMs)
            assertEquals(6_000L, f.fix.elapsedRealtimeMs)
            f.trace += "TYPICAL_GPS_${accuracyM.toInt()}M_LOW_ORIGINAL_6000"
            f.assertClockMetresThenDone()
        }
    }

    private class ColdFixture {
        val base = MainActivityContinuousLocationStartBehaviorTest.Fixture()
        val coordinator = base.field("positioningCoordinator") as PositioningCoordinator
        val generation = coordinator.currentInputGeneration()
        val positions = MainActivityControllerTestFixture.controller(base.main, "positioningSessionController") as PositioningSessionController
        val guide = MainActivityControllerTestFixture.controller(base.main, "routeGuidanceController") as RouteGuidanceController
        val acquisition = MainActivityControllerTestFixture.controller(base.main, "routeAcquisitionController") as RouteAcquisitionController
        val run = read<NavigationRunState>(guide, "navigationRunState")
        val scope = read<RouteGuidanceController.ScopeReader>(guide, "scope")
        val callbacks = mutableListOf<Pair<Runnable, Long>>()
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val speech = SpeechRecorder()
        val destination = RoutePoint(37.5027, 127.0, "destination")
        val trace = mutableListOf<String>()
        lateinit var acquired: PositioningSnapshot
        lateinit var fix: TrustedLocation
        var locationGranted = true
        private var compass: RouteFacingObservation? = null
        private var acquiredAccuracyM = 5.0

        init {
            assertNull(base.evidence)
            assertNull(positions.trustedLocation)
            assertFalse(run.navigator.hasRoute())
            trace += "COLD_NO_GPS_NO_ROUTE"
            val interaction = MainActivityControllerTestFixture.controller(base.main, "postLoginDeviceInteraction")
            val actual = read<PostLoginDeviceFacts>(interaction, "facts")
            put(interaction, "facts", object : PostLoginDeviceFacts by actual {
                // Raw platform observations; actual feature restrictions still evaluate them.
                override fun cameraPermission() = true
                override fun locationPermission() = locationGranted
                override fun recordAudioPermission() = true
                override fun locationServiceEnabled() = true
                override fun notificationPermission() = true
            })
            val originalScope = read<PositioningSessionController.Scope>(positions, "scope")
            put(positions, "scope", object : PositioningSessionController.Scope by originalScope {
                // Stable-session SDK-admitted owner assumption, not Main's publication or
                // actor-switch/deletion admission. Denial here tests the injected boundary.
                override fun currentOwner() = if (locationGranted)
                    base.field("locationCollectionOwner") as LocationCollectionOwner? else null
            })
            // The initial-start recovery now rechecks the same actual Android permission leaf.
            // Keep its denial controllable; all request/actor/session/epoch ownership remains real.
            val acquisitionScope = read<RouteAcquisitionController.Facts>(acquisition, "facts")
            put(acquisition, "facts", object : RouteAcquisitionController.Facts by acquisitionScope {
                override val locationPermissionReady get() = locationGranted
                // Existing stable SDK-admitted account assumption: enrollment/profile publication
                // is outside this fixture, while actual current session/actor identity still fences it.
                override val actorId get() = acquisitionScope.rawActorId?.takeIf { it == acquisitionScope.gateway.session?.actorId }
                override fun sessionCurrent(session: GatewayFieldSession) = acquisitionScope.gateway.session === session &&
                    session.isUsableFor(acquisitionScope.rawActorId)
            })
            val deletionCoordinator = read<AccountDeletionProcessCoordinator>(base.main, "accountDeletionProcessCoordinator")
            base.set("accountDeletionActivityLease", deletionCoordinator.attach())
            put(positions, "elapsedRealtimeMs", { base.now })
            put(positions, "postToUi", { action: () -> Unit -> action() })
            put(guide, "elapsedRealtimeMs", { base.now })
            put(guide, "isMainThread", { true })
            put(guide, "postToUi", { action: () -> Unit -> action() })
            put(acquisition, "elapsedRealtimeMs", { base.now })
            put(run.navigator, "nowElapsedRealtimeMs", { base.now })
            put(guide, "speech", speech)
            put(guide, "compassInput", object : RouteGuidanceController.CompassReader {
                override val revision = 0L
                override val invalidationPending = false
                override fun heading(nowMs: Long) = compass?.let { RouteCompassHeadingResult(it, RouteCompassHeadingReason.VALID) }
                override fun facing(nowMs: Long) = compass
            })
            put(guide, "callbacks", object : RouteCallbackScheduler {
                override fun post(task: Runnable) { callbacks += task to 0L }
                override fun postDelayed(task: Runnable, delayMs: Long) { callbacks += task to delayMs }
                override fun remove(task: Runnable) { callbacks.removeAll { it.first === task } }
            })
            put(guide, "diagnostics", object : RouteGuidanceController.Diagnostics {
                override val debug = false
                override val recording = true
                override fun log(tag: String, message: String) = Unit
                override fun record(event: String, values: Map<String, Any?>) { events += event to values }
                override fun confidenceLine(snapshot: PositioningSnapshot) = ""
            })
            val effects = read<RouteGuidanceController.Effects>(guide, "effects")
            put(guide, "effects", object : RouteGuidanceController.Effects by effects {
                override fun navigationStatus(text: String) { trace += text }
                override fun progressBeep(nowMs: Long, offRoute: Boolean, arrived: Boolean) = Unit
                override fun pausedHaptic() = Unit
                override fun deviationHaptic() = Unit
                override fun decisionActions(decision: RouteNavigatorUserDecision?) = Unit
                override fun preparationStage(stage: String) = Unit
                override fun showLastResult(message: String) = Unit
                override fun clearWaitingFailure(current: () -> Boolean) = Unit
            })
        }

        fun acquire(freshAccuracyM: Double = 5.0) {
            acquiredAccuracyM = freshAccuracyM
            val expectedQuality = if (freshAccuracyM <= 5.0) PositionQuality.MEDIUM else PositionQuality.LOW
            base.nativeCheck()
            base.main.prepareOfficialEnvironmentGpsForPreflight { base.now }
            assertNull(base.evidence)
            trace += "START_WAITS_FOR_GPS"
            base.now = 1_100L
            val coarse = coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 30.0, 1_000L, 1_100L))
            base.main.recordPositioningGpsObservation(coarse, 1_000L, 30.0, { base.now }, {})
            base.now = 4_100L
            val confirmed = coordinator.evaluate(base.now)
            assertTrue(confirmed.confidence.guidancePaused)
            guide.applyPositionConfidenceDecision(confirmed, emitAnnouncements = false)
            assertTrue(guide.positionPaused)
            trace += "INITIAL_LOW_CONFIRMED_AFTER_3S"
            listOf(5_000L, 6_000L).forEach { measuredAt ->
                base.now = measuredAt + 100L
                acquired = coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, freshAccuracyM, measuredAt, base.now))
                assertEquals(expectedQuality, acquired.quality)
                base.main.recordPositioningGpsObservation(acquired, measuredAt, freshAccuracyM, { base.now }, {})
                guide.applyPositionConfidenceDecision(acquired, emitAnnouncements = false)
            }
            assertFalse("Cold acquisition must finish with two fresh $expectedQuality fixes", acquired.confidence.guidancePaused)
            assertFalse(guide.positionPaused)
            assertEquals(generation, coordinator.currentInputGeneration())
            assertEquals(6_000L, acquired.raw?.elapsedRealtimeMs)
            assertEquals(6_000L, base.evidence?.observedAtElapsedRealtimeMs)
            assertEquals(EnvironmentEvidenceStatus.PASS, base.evidence?.status)
            assertNull("The consumer callback has not published this fix yet", positions.trustedLocation)
            trace += "${expectedQuality.name}_5000_6000_SAME_COLLECTION"
            // Native UI/other passed device observations are fixed boundaries. GPS readiness is
            // supplied only after the real observations above; there is no route/HIGH seed.
            base.activate()
            val session = requireNotNull(GatewaySessionProcessCoordinator.snapshot().session)
            val authority = GatewayWalkAuthorityController(elapsedClock = { base.now })
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
                authority.completeStart(requireNotNull(authority.beginStart(base.epoch)),
                    GatewayWalkStartResult.Granted(GatewayWalkLease("GRANTED", session.actorId,
                        "cold-device", base.epoch.walkSessionId, "cold-lease", 1L,
                        100_000L, 160_000L, 100_000L, base.now + 60_000L))))
            base.set("gatewayWalkAuthorityController", authority)
            val device = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL, emptyList(), emptyList(), "device-pass")
            base.set("startupCapabilityDecision", device)
            base.set("confirmedStartupCapabilityDecision", device)
            val guard = OfficialEnvironmentRuntimeGuard(base.epoch, base.profile)
            guard.onAssessment(OfficialEnvironmentAssessment(base.epoch, base.now, base.profile.profileId,
                OfficialEnvironmentFactor.entries.associateWith {
                    if (it == OfficialEnvironmentFactor.GPS_QUALITY) requireNotNull(base.evidence).status else EnvironmentEvidenceStatus.PASS
                }, true), base.now)
            base.set("officialEnvironmentRuntimeGuard", guard)
            base.set("officialEnvironmentOutputsAllowed", guard.decision().navigationOutputsAllowed)
            assertFalse(scope.developmentBypass)
            assertTrue(scope.runtimeActive)
            assertTrue(scope.navigationAllowed)
        }

        private fun prepareArchive() {
            val archive = read<RouteArchiveController>(acquisition, "archive")
            val original = read<RouteArchiveController.Facts>(archive, "facts")
            put(archive, "facts", object : RouteArchiveController.Facts by original {
                override fun activityCurrent() = base.lifecycle.snapshot().isForeground
            })
            val tasks = mutableListOf<Runnable>()
            val lane = RouteSnapshotIoLane(Executor { tasks += it })
            put(archive, "lane", lane)
            put(archive, "callbacks", object : RouteCallbackScheduler {
                override fun post(task: Runnable) { tasks += task }
                override fun postDelayed(task: Runnable, delayMs: Long) = Unit
                override fun remove(task: Runnable) { tasks.remove(task) }
            })
            put(archive, "onPrepared", {})
            val keys = mutableMapOf<String, SecretKey>()
            archive.initialize {
                EncryptedRouteSnapshotStore(object : RouteSnapshotStorage {
                    override fun read(): String? = null
                    override fun clear() = true
                    override fun write(value: String) = error("No archive save is injected by this response seam")
                }, VersionedLocalAead(AeadKeyPolicy("cold.route.v", 1, setOf(1)), object : LocalAeadKeyProvider {
                    override fun getOrCreate(alias: String) = keys.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
                    override fun getExisting(alias: String) = keys[alias]
                    override fun delete(alias: String) = keys.remove(alias).let { true }
                    override fun createFreshAfterVerifiedPurge(alias: String) = getOrCreate(alias)
                }), testOnly = Unit, elapsedRealtimeMs = { base.now }, bootCount = { 7 }, processCoordinator = RouteSnapshotProcessCoordinator())
            }
            while (tasks.isNotEmpty()) tasks.removeAt(0).run()
            assertFalse(archive.preparationPending)
            assertFalse(archive.purgeFailed)
        }

        fun requestAndInstall(approach: Boolean) {
            prepareArchive()
            run.selectDestination(destination)
            base.set("pendingUiDestination", DestinationSearchResult("cold", "destination", destination, null, null, null, null))
            val page = Class.forName("kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController\$NativeUiPage")
                .enumConstants.first { (it as Enum<*>).name == "GUIDANCE" }
            base.set("nativeUiPage", page)
            assertTrue(acquisition.retainPendingExplicitRouteStart(destination, null))
            val pending = requireNotNull(acquisition.explicitStart)
            assertEquals(base.epoch, pending.context.walkEpoch)
            assertEquals(destination, pending.context.destination)
            assertTrue(pending.keepUntilCancelled)
            var called = 0
            base.main.continueAdmittedExplicitRouteStartIfReady({ base.now }, { null }) { _, _, _, _ -> called++ }
            assertEquals(0, called)
            val ownerFacts = read<RouteAcquisitionController.Facts>(acquisition, "facts")
            val ownerSession = requireNotNull(pending.context.gatewaySession)
            val checks = mapOf("foreground" to ownerFacts.foreground, "destroyed" to ownerFacts.activityDestroyed,
                "permission" to ownerFacts.locationPermissionReady, "epoch" to base.lifecycle.isRuntimeEpochCurrent(pending.context.walkEpoch),
                "gateway" to (ownerFacts.gateway.session === ownerSession),
                "rawActor" to (ownerFacts.rawActorId == ownerSession.actorId), "actor" to (ownerFacts.actorId == ownerSession.actorId),
                "sessionCurrent" to ownerFacts.sessionCurrent(ownerSession), "destination" to (run.destination == pending.context.destination),
                "selected" to (ownerFacts.selectedDestination === pending.context.selectedDestination),
                "deletionBlocked" to ownerFacts.deletionOwner.processingBlocked(),
                "deletionLease" to runCatching { ownerFacts.deletionOwner.activityLeaseIsCurrent(ownerFacts.deletionActivityLease) }.getOrNull())
            assertSame("Current start owner $checks", pending, acquisition.explicitStart)
            trace += "EXPLICIT_START_RETAINED_NO_PUBLISHED_FIX"
            positions.applyPositioningMotionSnapshot(acquired, base.binding as PositioningCollectionBinding)
            fix = requireNotNull(positions.currentRouteLocationOrNull())
            assertEquals(6_000L, fix.elapsedRealtimeMs)
            put(positions, "latestRawRouteLocation", positions.rawAndroidRouteLocation(37.5, 127.0, acquiredAccuracyM.toFloat(), 6_000L))
            val session = requireNotNull(GatewaySessionProcessCoordinator.snapshot().session)
            val gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            base.main.continueAdmittedExplicitRouteStartIfReady({ base.now }, { positions.currentRouteLocationOrNull() }) { point, reason, retain, _ ->
                called++
                assertEquals(destination, point)
                assertEquals("user_destination", reason)
                assertTrue(retain)
                val east = if (approach) 22.0 / (111_195.0 * kotlin.math.cos(Math.toRadians(37.5))) else 0.0
                val first = RoutePoint(37.5, 127.0 + east)
                val end = RoutePoint(37.5027, 127.0 + east)
                val route = WalkingRoute("TEST", WalkingRouteSummary(300, 240), listOf(first, end),
                    listOf(WalkingRouteGuidePoint(0, end, "안내", 300, 300, turnType = 13)))
                var executed = 0
                val call = CancellableNetworkCall(executeBlock = { executed++; route })
                assertTrue(scope.navigationAllowed)
                assertFalse("No route is installed before the accepted request", run.navigator.hasRoute())
                // The controlled request sink replaces the transport, so publish the same
                // actual run-owner transition made by requestRoute immediately before it.
                run.markRequestActive()
                assertTrue(run.active)
                trace += "REQUEST_ACCEPTED_RUN_ACTIVE"
                base.main.trackRouteRequest(call)
                (base.field("routeRequestInFlight") as java.util.concurrent.atomic.AtomicBoolean).set(true)
                base.set("routeRequestGeneration", 1)
                assertTrue(base.main.routeResponseInstallLeaseCurrent(1) { session.isUsableFor(session.actorId) && base.lifecycle.isRuntimeEpochCurrent(base.epoch) })
                trace += "ROUTE_REQUEST_CURRENT_ACTOR_EPOCH"
                val captured = call.execute()
                assertEquals(1, executed)
                base.main.completeRouteRequest(call)
                val status = base.main.routeResponseLocationStatus(captured, fix, fix, base.now)
                assertEquals(if (approach) "APPROACH_REQUIRED" else "CONNECTED", status.name)
                assertEquals(gatewayGeneration, GatewaySessionProcessCoordinator.snapshot().generation)
                assertSame(session, GatewaySessionProcessCoordinator.snapshot().session)
                assertEquals(destination, run.destination)
                assertTrue(base.main.routeResponseInstallLeaseCurrent(1) { base.lifecycle.isRuntimeEpochCurrent(base.epoch) && session.isUsableFor(session.actorId) })
                // Successful response consumes loading after currentness admission. The failure
                // terminal helper would mark the run inactive and must not be used for success.
                assertTrue((base.field("routeRequestInFlight") as java.util.concurrent.atomic.AtomicBoolean).compareAndSet(true, false))
                base.main.installRequestedRoute(captured, destination, fix, null, "user_destination")
                trace += "ROUTE_INSTALLED_FROM_COMPLETED_RESPONSE"
            }
            base.main.continueAdmittedExplicitRouteStartIfReady({ base.now }, { fix }) { _, _, _, _ -> called++ }
            assertEquals("The explicit request is consumed once", 1, called)
            assertNull(acquisition.explicitStart)
            assertTrue(run.navigator.hasRoute())
            assertEquals(approach, run.navigator.isWaitingForInitialRouteJoin())
        }

        fun installIntro() {
            val intro = read<InstalledRouteStartSpeechCoordinator>(guide, "installedRouteStartSpeechCoordinator")
            val requestId = acquisition.requestGeneration
            val feedbackId = scope.feedbackGeneration
            intro.install("목적지까지 길안내를 시작합니다.") {
                guide.isRouteSpeechCurrent(base.epoch, requestId, feedbackId)
            }
        }
        fun refine() {
            val runtime = MainActivityControllerTestFixture.controller(base.main, "officialEnvironmentRuntimeController")
            put(runtime, "nowMs", { base.now })
            put(runtime, "schedule", { delay: Long, action: () -> Unit -> callbacks += Runnable(action) to delay })
            listOf(7_000L, 8_000L).forEach { measuredAt ->
                base.now = measuredAt + 100L
                val snapshot = coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 3.0, measuredAt, base.now))
                assertEquals(PositionQuality.MEDIUM, snapshot.quality)
                assertEquals(generation, coordinator.currentInputGeneration())
                base.main.recordPositioningGpsObservation(snapshot, measuredAt, 3.0, { base.now }, {})
                guide.applyPositionConfidenceDecision(snapshot, emitAnnouncements = false)
                positions.applyPositioningMotionSnapshot(snapshot, base.binding as PositioningCollectionBinding)
                fix = requireNotNull(positions.currentRouteLocationOrNull())
                assertEquals(measuredAt, fix.elapsedRealtimeMs)
                put(positions, "latestRawRouteLocation", positions.rawAndroidRouteLocation(37.5, 127.0, 3f, measuredAt))
            }
            trace += "ACTUAL_NEW_3M_MEDIUM_7000_8000"
        }
        fun faceAt(time: Long) { compass = RouteFacingObservation(0.0, 0.1, time) }
        fun retry() {
            val current = requireNotNull(read<Runnable?>(guide, "navigationSpeechRetryRunnable"))
            val posted = callbacks.single { it.first === current }
            assertSame("Execute the same current runnable registered by the actual controller", current, posted.first)
            callbacks.remove(posted)
            base.now += posted.second
            faceAt(base.now)
            posted.first.run()
            trace += "ACTUAL_POSTED_RETRY_CALLBACK"
        }
        fun dispatchFirstCue() {
            faceAt(base.now)
            guide.updateRouteGuidance(fix, requireNotNull(positions.rawRouteLocation))
            // A later sensor/time retry is not a replay of the original GPS measurement.
            if (speech.requests.isEmpty() && speech.intros.isEmpty() && read<Runnable?>(guide, "navigationSpeechRetryRunnable") != null) {
                base.now += 1_500L
                retry()
            }
        }
        fun completedCue(): Any? = read<Any?>(run.navigator, "completedSpeechCue")
        fun assertClockMetresThenDone() {
            assertEquals("Exactly one first action reaches the speech transport", 1, speech.requests.size)
            val request = speech.requests.single()
            assertTrue(request.message, Regex("(?:[1-9]|1[0-2])시.*[0-9]+(?:미터|m)").containsMatchIn(request.message))
            trace += "REQUESTED_CLOCK_METRES"
            assertNull(completedCue())
            assertEquals(0, events.count { it.first == "route_guidance_started" })
            speech.validating = true
            assertTrue(request.current())
            speech.validating = false
            trace += "START_CURRENT_CUE"
            assertNull(completedCue())
            assertEquals(1, events.count { it.first == "route_guidance_started" })
            request.done()
            trace += "DONE_ACK"
            assertNotNull(completedCue())
            println(trace.joinToString(" -> "))
        }
    }

    private class SpeechRecorder : RouteGuidanceController.SpeechPort {
        data class Request(val message: String, val current: () -> Boolean, val done: () -> Unit, val failed: () -> Unit)
        val requests = mutableListOf<Request>()
        val intros = mutableListOf<Request>()
        var input = false
        var validating = false
        override val commandGeneration = 0L
        override val commandPending = false
        override val validatingStarted get() = validating
        override val statusText: String? = null
        override fun busy() = input
        override fun outputBusy() = false
        override fun inputActive() = input
        override fun canSpeakNavigation(nowMs: Long) = !input
        override fun cancelNavigation() = Unit
        override fun cancelCommand() = Unit
        override fun failCommand(generation: Long) = Unit
        override fun submitNavigation(message: String, isInstructionStillCurrent: () -> Boolean, onCompleted: () -> Unit, onFailed: () -> Unit): Boolean {
            if (input || !isInstructionStillCurrent()) return false
            requests += Request(message, isInstructionStillCurrent, onCompleted, onFailed)
            return true
        }
        override fun submitStatus(message: String, current: () -> Boolean, completed: () -> Unit, failed: (() -> Unit)?) = false
        override fun submitInstalledStart(message: String, current: () -> Boolean, terminal: () -> Unit): Boolean {
            if (input || !current()) return false
            intros += Request(message, current, terminal, terminal)
            return true
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun <T> read(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
        private fun put(owner: Any, name: String, value: Any?) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
