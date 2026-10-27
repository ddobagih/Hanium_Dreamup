package kr.co.hanium.dreamup.walksafe

import com.google.android.gms.location.LocationCallback
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.session.GuidanceRecoveryTiming
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual Main GPS publication/preparation/handoff and real policy/gate. General callback SDK
 * admission is assumed by the fixture; callback wiring is checked separately, not run on Android. */
class MainActivityContinuousLocationStartBehaviorTest {
    @After fun releaseSession() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun admittedHomeObservationPublishesOriginalGpsEvidenceBeforeStart() {
        val f = Fixture()
        f.main.recordGeneralLocationGpsObservation({ f.now - 100L }, true, 3.0, { f.now })
        val published = f.evidence
        assertNotNull("The current logged-in foreground stream must publish GPS quality in READY", published)
        assertEquals(EnvironmentEvidenceStatus.PASS, published?.status)
        assertEquals(f.now - 100L, published?.observedAtElapsedRealtimeMs)
        assertEquals(f.epoch, published?.epoch)
        assertSame(f.callback, f.field("locationCallback"))
        assertSame(f.binding, f.field("positioningCollectionBinding"))
        assertEquals(WalkSessionState.READY, f.lifecycle.snapshot().state)
    }

    @Test fun nativePreparationKeepsTheCurrentStreamsPassingMeasurement() {
        val f = Fixture()
        val passed = f.seedPassingMeasurement()
        f.nativeCheck()
        f.main.prepareOfficialEnvironmentGpsForPreflight { f.now }
        assertEquals("Native Start must not discard the current foreground measurement", passed, f.evidence)
        assertEquals(passed.observedAtElapsedRealtimeMs, f.evidence?.observedAtElapsedRealtimeMs)
        assertSame(f.callback, f.field("locationCallback"))
        assertSame(f.binding, f.field("positioningCollectionBinding"))
    }

    @Test fun activeHandoffDoesNotRestartAnAlreadyRunningSixPeriodAccuracyGrace() {
        val f = Fixture()
        val passed = f.seedPassingMeasurement()
        f.now = 1_100L
        val bad = f.sample(30.0)
        assertEquals(passed, f.gate.observe(bad, f.now))
        f.set("officialEnvironmentGpsEvidence", passed)
        val deadline = f.now + GuidanceRecoveryTiming.GPS_GRACE_MS
        assertEquals(19_100L, deadline)
        f.now = 3_100L
        assertEquals(deadline, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        f.activate()
        f.main.prepareOfficialEnvironmentGpsForRuntime(f.now)
        f.now = 4_100L
        assertEquals("Start must retain the original GOOD only while fresh", passed, f.gate.current(f.now))
        assertEquals(deadline, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        f.now = 11_001L
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.gate.current(f.now)?.status)
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RECHECKING, f.gate.gpsRecoveryStage(f.now))
        assertEquals(deadline, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        f.now = deadline
        assertEquals("Start must keep the latest bad sample and its original grace deadline",
            EnvironmentEvidenceStatus.FAIL, f.gate.current(f.now)?.status)
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RESTRICTED, f.gate.gpsRecoveryStage(f.now))
        assertEquals(bad.observedAtElapsedRealtimeMs, f.gate.latestRawObservation()?.observedAtElapsedRealtimeMs)
        assertSame(f.callback, f.field("locationCallback"))
    }

    @Test fun originalQualityGateKeepsSixPeriodOnsetButOriginalTtlWinsAndFreshRecoveryIsAutomatic() {
        val f = Fixture()
        val passed = f.seedPassingMeasurement()
        f.now = 1_100L
        val bad = f.sample(30.0)
        assertEquals(passed, f.gate.observe(bad, f.now))
        val deadline = f.now + GuidanceRecoveryTiming.GPS_GRACE_MS
        assertEquals(passed, f.gate.current(4_099L))
        assertEquals(passed, f.gate.current(4_100L))
        assertEquals(passed, f.gate.current(11_000L))
        assertEquals(bad, f.gate.current(11_001L))
        assertEquals(1_000L, passed.observedAtElapsedRealtimeMs)
        assertEquals(deadline, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        listOf(14_100L, 17_100L).forEach { at ->
            f.now = at
            val measuredBad = f.sample(30.0)
            assertEquals(measuredBad, f.gate.observe(measuredBad, at))
            assertEquals(deadline, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RECHECKING, f.gate.gpsRecoveryStage(deadline - 1L))
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RESTRICTED, f.gate.gpsRecoveryStage(deadline))
        f.now = deadline + 100L
        val recovered = f.sample(3.0)
        assertEquals(recovered, f.gate.observe(recovered, f.now))
        assertEquals(f.now, recovered.observedAtElapsedRealtimeMs)
        assertNull(f.gate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test fun oneRejectedJumpDoesNotDiscardTheOriginalFreshGpsPassBeforeItsExistingGrace() {
        val f = Fixture()
        val coordinator = f.field("positioningCoordinator") as PositioningCoordinator
        fun publish(latitude: Double, longitude: Double) {
            val observation = kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssPositionObservation(
                latitude, longitude, 3.0, f.now)
            val snapshot = coordinator.observeGnss(observation)
            if (latitude == 37.6) {
                assertTrue(snapshot.singleOutlierAgainstFreshEstimate)
                assertFalse(snapshot.confidence.guidancePaused)
                assertEquals(37.5, requireNotNull(snapshot.filtered).coordinate.latitude, 1e-4)
            }
            f.main.recordPositioningGpsObservation(snapshot, f.now, 3.0, { f.now }, {})
        }
        publish(37.5, 127.0)
        f.now = 2_000L
        publish(37.5, 127.00001)
        val passing = requireNotNull(f.evidence)
        assertEquals(EnvironmentEvidenceStatus.PASS, passing.status)
        assertEquals(2_000L, passing.observedAtElapsedRealtimeMs)
        f.now = 3_000L
        publish(37.6, 127.0)
        assertEquals("The rejected point must never be promoted to PASS", EnvironmentEvidenceStatus.FAIL,
            f.gate.latestRawObservation()?.status)
        assertEquals(3_000L, f.gate.latestRawObservation()?.observedAtElapsedRealtimeMs)
        assertEquals("The existing single-outlier policy must retain only the old fresh evidence, not the jump",
            passing, f.evidence)
        assertEquals(3_000L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertSame(f.callback, f.field("locationCallback"))
        assertSame(f.binding, f.field("positioningCollectionBinding"))
    }

    @Test fun productionHooksKeepSdkAndSourceAdmissionBeforeGpsPublication() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val location = NavigationPolicySource.observation(source)
        val before = location.substringBefore("recordPositioningGpsObservation(")
        val actualPositioning = MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(actualPositioning.contains("val scope = positions.scope()"))
        assertTrue(actualPositioning.contains("val sources = positions.sources()"))
        assertTrue(actualPositioning.contains("val mounting = positions.mounting()"))
        assertTrue(actualPositioning.contains("PositioningSessionController(run, scope, sources, mounting,"))
        val navigationFactory = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertEquals(1, Regex(Regex.escape("positions = PositioningAssemblyBindings(")).findAll(navigationFactory).count())
        val positioningPorts = ReportStaticSourceInspector.blockAfter(navigationFactory, "positions = PositioningAssemblyBindings(")
        assertEquals(1, Regex(Regex.escape("mounting = { object : PositioningSessionController.Mounting")).findAll(navigationFactory).count())
        val mountingPorts = ReportStaticSourceInspector.blockAfter(navigationFactory, "mounting = { object : PositioningSessionController.Mounting")
        assertTrue(MainActivityRefactorSource.member(source, "handleLocationUpdate")
            .contains("positioningSessionController.handleLocationUpdate(location, owner, locationGeneration, binding, receivedAtElapsedRealtimeMs)"))
        assertTrue(positioningPorts.contains("override fun currentOwner() = currentLocationCollectionOwnerOrNull()"))
        assertTrue(navigationFactory.contains("sources = { createNavigationAndroidSources() }"))
        val androidBinding = MainActivityRefactorSource.factory(source, "createNavigationAndroidSources")
        assertTrue(androidBinding.contains("AndroidNavigationSources("))
        assertTrue(androidBinding.contains("context = this"))
        assertTrue(androidBinding.contains("fusedAvailableNow = { ::fusedLocationClient.isInitialized }, fusedClient = { fusedLocationClient }"))
        assertTrue(androidBinding.contains("runOnUiThread = { action -> runOnUiThread(action) }"))
        assertTrue(androidBinding.contains("locationIntervalMs = LOCATION_UPDATE_INTERVAL_MS, fastestIntervalMs = LOCATION_FASTEST_INTERVAL_MS"))
        val androidSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt").readText()
        val fused = MainActivityRefactorSource.member(androidSource, "fusedSource")
        val requestUpdates = MainActivityRefactorSource.member(androidSource, "requestUpdates")
        assertTrue(fused.contains("FusedLocationUpdateSource(fusedClient(), { android.os.SystemClock.elapsedRealtime() }, current, onEvent)"))
        assertTrue(requestUpdates.contains("source.requestUpdates(request, Looper.getMainLooper(), Executor { runOnUiThread(it) })"))
        // The shared guard is checked at both SDK and immutable-policy entries above.
        assertTrue(before.contains("if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return"))
        assertTrue(location.contains("recordPositioningGpsObservation(positioningSnapshot, elapsedMs, accuracy?.toDouble())"))
        val publication = MainActivityRefactorSource.function(source, "internal fun recordPositioningGpsObservation(")
        assertTrue(publication.contains("continuousGpsSession.recordPositioningGpsObservation(snapshot, observedAtElapsedRealtimeMs, horizontalAccuracyMeters, elapsedRealtimeMs, onPrewalkEvidenceChanged)"))
        val publisher = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/ContinuousGpsSession.kt").readText(), "recordPositioningGpsObservation")
        assertTrue(publisher.contains("observedAtElapsedRealtimeMs = { observedAtElapsedRealtimeMs }"))
        assertTrue(publisher.contains("trustedFixAvailable = !hardRejected && snapshot.filtered != null"))
        assertTrue(publisher.contains("horizontalAccuracyMeters = horizontalAccuracyMeters"))
        assertTrue(publisher.contains("singleOutlierAgainstFreshEstimate = snapshot.singleOutlierAgainstFreshEstimate"))
        val start = MainActivityRefactorSource.function(source, "private fun startLocationUpdatesIfAllowed(")
        assertTrue(start.contains("locationCallback != null && locationCollectionOwner == owner"))
        assertTrue(start.contains("positioningCollectionBinding?.let(::isPositioningCollectionCurrent) == true"))
        assertTrue(start.indexOf("if (!forceRestart") < start.indexOf("sources.requestUpdates(fusedLocationSource)"))
        val sdkSource = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/location/FusedLocationUpdateSource.kt",
        ).readText()
        assertTrue(sdkSource.contains("client.requestLocationUpdates(request, callback, looper)"))
        val sdkReceipt = ReportStaticSourceInspector.functionBlock(sdkSource, "override fun onLocationResult")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(sdkReceipt,
            "val receivedAt = receivedAtMs()", "if (!callbackIsCurrent(this)) return",
            "result.lastLocation?.let", "FusedLocationUpdateEvent.LocationReceived(location, receivedAt)",
        ))
        val currentAdmission = ReportStaticSourceInspector.blockAfter(start, "current =")
        assertTrue(currentAdmission.contains("isLocationCallbackCurrent(owner, generation, callback)"))
        assertTrue(currentAdmission.contains("isPositioningCollectionCurrent(binding)"))
        val received = start.substringAfter("is FusedLocationUpdateEvent.LocationReceived ->")
            .substringBefore("is FusedLocationUpdateEvent.AvailabilityChanged ->")
        assertTrue(received.contains("handleLocationUpdate("))
        assertTrue(received.contains("event.location, owner, generation, binding, event.receivedAtMs"))
        assertTrue(start.contains("val callback = fusedLocationSource.callback"))
        assertTrue(start.contains("locationCallback = callback"))
        assertTrue(start.contains("recordGeneralLocationGpsObservation("))
        val controller = File("src/main/java/kr/co/hanium/dreamup/walksafe/device/environment/OfficialEnvironmentController.kt").readText()
        val evidence = File("src/main/java/kr/co/hanium/dreamup/walksafe/device/environment/OfficialEnvironmentEvidenceStore.kt").readText()
        val controllerPorts = ReportStaticSourceInspector.blockAfter(source, "private val officialEnvironmentController:")
        assertTrue(controllerPorts.contains("override fun nativeCheckRequested() = nativePhoneMountingCheckRequest != null"))
        assertTrue(controllerPorts.contains("override fun nativeGpsCheckCurrent(epoch: WalkRuntimeEpoch) = nativeGpsCheckIsCurrent(epoch)"))
        assertTrue(controllerPorts.contains("this@MainActivity.requestContinuousGpsForNativeCheck(epoch)"))
        assertTrue(MainActivityRefactorSource.member(source, "confirmOfficialEnvironmentConditions")
            .contains("officialEnvironmentController.confirmConditions()"))
        val confirm = MainActivityRefactorSource.member(controller, "confirmConditions")
        assertTrue(confirm.contains("prepareGpsForPreflight()"))
        assertTrue(confirm.contains("requestGpsForCheck(lifecycle.epoch)"))
        assertFalse(confirm.contains("requestOfficialEnvironmentGpsPreflight("))
        val requests = MainActivityRefactorSource.member(controller, "requestGpsForCheck")
        assertTrue(requests.indexOf("if (facts.nativeCheckRequested())") < requests.indexOf("requestSeparate(epoch, clearExistingEvidence)"))
        assertTrue(requests.contains("if (facts.nativeGpsCheckCurrent(epoch)) requestGeneral(epoch)"))
        assertTrue(requests.contains("requestGeneral: (WalkRuntimeEpoch) -> Unit = effects::requestContinuousGpsForNativeCheck"))
        assertTrue(requests.contains("requestSeparate: (WalkRuntimeEpoch, Boolean) -> Unit = preflight::requestGps"))
        val handoff = MainActivityRefactorSource.member(evidence, "prepareGpsForRuntime")
        assertTrue(handoff.indexOf("continuousGpsEvidence(") < handoff.indexOf("gpsQualityGate.reset()"))
        val mainHandoff = MainActivityRefactorSource.member(source, "prepareOfficialEnvironmentGpsForRuntime")
        assertTrue(mainHandoff.contains("officialEnvironmentEvidenceStore.prepareGpsForRuntime("))
        assertTrue(mainHandoff.contains("{ continuousGpsSource != null }, ::currentContinuousGpsEvidence"))
        val assessment = MainActivityRefactorSource.member(evidence, "currentAssessment")
        assertTrue(assessment.indexOf("continuousGpsEvidence(") < assessment.indexOf("OfficialEnvironmentPolicy.assess("))
        assertTrue(MainActivityRefactorSource.member(source, "currentOfficialEnvironmentAssessment")
            .contains("officialEnvironmentAssessmentFacts, ::currentContinuousGpsEvidence"))
        val general = MainActivityRefactorSource.function(source, "internal fun recordGeneralLocationGpsObservation(")
        assertTrue(general.contains("onPrewalkEvidenceChanged: () -> Unit = actions::prewalkEvidenceChanged"))
        assertTrue(general.indexOf("evidence.record(") < general.indexOf("onPrewalkEvidenceChanged()"))
        val gpsPorts = MainActivityRefactorSource.composition(source, "continuousGpsSession")
        assertTrue(gpsPorts.contains("evidence = gpsBindings.evidence()"))
        assertTrue(gpsPorts.contains("override fun prewalkEvidenceChanged() = gpsBindings.prewalkEvidenceChanged()"))
        assertTrue(navigationFactory.contains("gpsBindings = GpsAssemblyBindings("))
        assertTrue(navigationFactory.contains("recordOfficialEnvironmentGpsObservationAt(epoch, observedAtMs, trustedFixAvailable,"))
        assertTrue(navigationFactory.contains("prewalkEvidenceChanged = ::refreshStartupCapabilityUi"))
        val refresh = MainActivityRefactorSource.member(source, "refreshStartupCapabilityUi")
        assertTrue(refresh.contains("startupCapabilityCoordinator.refreshStartupCapabilityUi()"))
        val startup = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/startup/capability/StartupCapabilityCoordinator.kt",
        ).readText()
        assertTrue(MainActivityRefactorSource.member(startup, "refreshStartupCapabilityUi")
            .contains("ui.updatePrewalk("))
        val startupPorts = MainActivityRefactorSource.composition(source, "startupCapabilityCoordinator")
        assertTrue(startupPorts.contains(
            "override fun updatePrewalk(epoch: WalkRuntimeEpoch, officialReady: Boolean, mountingReady: Boolean) ="))
        assertTrue(startupPorts.contains("updatePrewalkGuidance(epoch, officialReady, mountingReady)"))
        assertTrue(startupPorts.contains(
            "this@MainActivity.prewalkGuidanceProgress.updatePrewalkGuidance(epoch, officialReady, mountingReady)"))
        val prewalkCore = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/startup/capability/PrewalkGuidanceProgress.kt",
        ).readText()
        val prewalk = MainActivityRefactorSource.member(prewalkCore, "updatePrewalkGuidance")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(prewalk,
            "if (port.nativeStartPending)", "port.continueNativeStart()", "!port.preflightActive",
        ))
        val prewalkPorts = MainActivityRefactorSource.composition(source, "prewalkGuidanceProgress")
        assertTrue(prewalkPorts.contains("override val nativeStartPending get() = nativePrewalkStartEpoch != null"))
        assertTrue(prewalkPorts.contains("override fun continueNativeStart() = continueNativePrewalkIfReady()"))
        assertTrue(prewalkPorts.contains("override fun snapshot() = walkSessionLifecycle.snapshot()"))
        assertTrue(MainActivityRefactorSource.member(source, "activateOfficialEnvironmentRuntime")
            .contains("officialEnvironmentRuntimeController.activate()"))
        val activate = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/device/environment/OfficialEnvironmentRuntimeController.kt").readText(), "activate")
        assertTrue(activate.contains("evidence.prepareGpsForRuntime("))
        val runtimePorts = ReportStaticSourceInspector.blockAfter(source, "private val officialEnvironmentRuntimeController:")
        assertTrue(runtimePorts.contains("override fun continuousGpsEvidence(epoch: WalkRuntimeEpoch, nowMs: Long) = currentContinuousGpsEvidence(epoch, nowMs)"))
    }

    @Test fun readyChecksExpireTheSameGraceWithoutANewCallbackAndRecoveryKeepsTheSource() {
        val f = Fixture()
        val passed = f.seedPassingMeasurement()
        f.now = 1_100L
        f.observe(30.0)
        f.now = 3_100L
        f.nativeCheck()
        f.main.prepareOfficialEnvironmentGpsForPreflight { f.now }
        assertEquals(passed, f.evidence)
        assertEquals(passed, f.main.currentContinuousGpsEvidence(f.epoch, 4_099L))
        assertEquals(passed, f.main.currentContinuousGpsEvidence(f.epoch, 4_100L))
        val deadline = 1_100L + GuidanceRecoveryTiming.GPS_GRACE_MS
        assertEquals(deadline, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        f.now = 11_001L
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.main.currentContinuousGpsEvidence(f.epoch, f.now)?.status)
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RECHECKING, f.gate.gpsRecoveryStage(f.now))
        assertEquals(deadline, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        f.now = deadline
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.main.currentContinuousGpsEvidence(f.epoch, f.now)?.status)
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RESTRICTED, f.gate.gpsRecoveryStage(f.now))
        f.now = deadline + 100L
        var continuations = 0
        f.observe(3.0, changed = { continuations++ })
        assertEquals(1, continuations)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.evidence?.status)
        assertEquals(f.now, f.evidence?.observedAtElapsedRealtimeMs)
        assertSame(f.callback, f.field("locationCallback"))
        assertSame(f.binding, f.field("positioningCollectionBinding"))
    }

    @Test fun nativeCheckSelectsGeneralStreamOnlyAndManualDiagnosticKeepsItsOwnRequest() {
        val f = Fixture()
        f.seedPassingMeasurement()
        f.nativeCheck()
        var general = 0
        var separate = 0
        f.main.requestOfficialEnvironmentGpsForCheck(f.epoch,
            requestGeneral = { assertEquals(f.epoch, it); general++ },
            requestSeparate = { _, _ -> separate++ })
        assertEquals(1, general)
        assertEquals("Native Start must not register a second GPS callback", 0, separate)
        assertSame(f.callback, f.field("locationCallback"))
        assertSame(f.binding, f.field("positioningCollectionBinding"))
        f.set("nativePhoneMountingCheckRequest", null)
        f.main.requestOfficialEnvironmentGpsForCheck(f.epoch, clearExistingEvidence = false,
            requestGeneral = { general++ }, requestSeparate = { epoch, clear ->
                assertEquals(f.epoch, epoch); assertFalse(clear); separate++
            })
        assertEquals(1, general)
        assertEquals(1, separate)
    }

    @Test fun manualPassingDisplayCannotHideTheLatestGeneralStreamFailureAtNativeStart() {
        val f = Fixture()
        val passed = f.seedPassingMeasurement()
        var manualCancelled = false
        val manual = object : kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentGpsRequest {
            override fun start() = error("The manually owned request is already installed by this fixture")
            override fun cancel() { manualCancelled = true }
        }
        f.set("officialEnvironmentGpsRequest", manual)
        f.now = 1_100L
        f.observe(null, trusted = false)
        assertEquals("Manual display is independent while its callback owns the diagnostic", passed, f.evidence)
        assertEquals("TRUSTED_FIX_UNAVAILABLE", f.gate.latestRawObservation()?.detail)
        f.nativeCheck()
        f.main.prepareOfficialEnvironmentGpsForPreflight { f.now }
        assertNull(f.field("officialEnvironmentGpsRequest"))
        assertTrue("General-stream preflight releases the manual request", manualCancelled)
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.evidence?.status)
        assertEquals("TRUSTED_FIX_UNAVAILABLE", f.evidence?.detail)
        assertEquals("TRUSTED_FIX_UNAVAILABLE", f.field("officialEnvironmentGpsDiagnosticDetail"))
        assertNull(f.field("officialEnvironmentGpsDiagnosticAccuracyMeters"))
        assertEquals(f.now, f.evidence?.observedAtElapsedRealtimeMs)
        assertSame(f.callback, f.field("locationCallback"))
    }

    @Test fun staleFutureUnknownAndUntrustedObservationsCannotBecomePassingStartEvidence() {
        for (kind in listOf("stale", "future", "negative", "unknown", "invalid_accuracy", "untrusted")) {
            val f = Fixture()
            f.seedPassingMeasurement()
            f.now += f.profile.maximumMeasuredEvidenceAgeMs + 1L
            when (kind) {
                "stale" -> f.observe(3.0, observedAt = 1_000L)
                "future" -> f.observe(3.0, observedAt = f.now + 1L)
                "negative" -> f.observe(3.0, observedAt = -1L)
                "unknown" -> f.observe(3.0, trusted = null)
                "invalid_accuracy" -> f.observe(Double.NaN)
                "untrusted" -> f.observe(3.0, trusted = false)
            }
            f.nativeCheck()
            f.main.prepareOfficialEnvironmentGpsForPreflight { f.now }
            val assessed = OfficialEnvironmentPolicy.assess(f.epoch, f.now, f.evidence, null, null, f.profile)
            assertNotEquals(kind, EnvironmentEvidenceStatus.PASS,
                assessed.factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY])
            assertFalse(kind, assessed.canStartWalk)
        }
    }

    @Test fun latestBadObservationCannotBeReplacedByAnOlderOrDuplicatePassingSample() {
        val f = Fixture()
        f.seedPassingMeasurement()
        f.now = 1_100L
        f.observe(null, trusted = false)
        val bad = f.gate.latestRawObservation()
        f.now = 1_200L
        f.observe(3.0, observedAt = 1_000L)
        f.observe(3.0, observedAt = 1_100L)
        f.nativeCheck()
        f.main.prepareOfficialEnvironmentGpsForPreflight { f.now }
        assertEquals(bad, f.evidence)
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.evidence?.status)
        f.observe(3.0, observedAt = f.now)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.evidence?.status)
        assertEquals(f.now, f.evidence?.observedAtElapsedRealtimeMs)
    }

    @Test fun changedSourceActorSessionForegroundAndEpochRejectTheOldHistory() {
        for (change in listOf("binding", "location_generation", "input_generation", "actor", "session", "foreground", "epoch", "stopped")) {
            val f = Fixture()
            f.seedPassingMeasurement()
            when (change) {
                "binding" -> f.set("positioningCollectionBinding", null)
                "location_generation" -> f.set("locationCallbackGeneration", 4)
                "input_generation" -> (f.field("positioningCoordinator") as PositioningCoordinator).reset()
                "actor" -> f.set("reporterUserId", "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                "session" -> GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
                "foreground" -> f.set("isActivityForeground", false)
                "epoch" -> f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
                "stopped" -> f.set("locationCallback", null)
            }
            assertNull(change, f.main.currentContinuousGpsEvidence(f.lifecycle.snapshot().epoch, f.now))
            assertNull(change, f.gate.latestRawObservation())
            assertNull(change, f.evidence)
        }
    }

    @Test fun changedApprovedProfileCannotReuseTheOldPassingHistory() {
        val f = Fixture()
        f.seedPassingMeasurement()
        val type = Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.ContinuousGpsSource")
        val mismatched = type.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(f.binding, f.epoch, f.profile.copy(profileId = "previous-profile"))
        f.set("continuousGpsSource", mismatched)
        assertNull(f.main.currentContinuousGpsEvidence(f.epoch, f.now))
        assertNull(f.evidence)
        assertNull(f.gate.latestRawObservation())
    }

    @Test fun staleNativeCheckNeverRegistersEitherLocationProducer() {
        for (change in listOf("actor", "session", "foreground", "epoch")) {
            val f = Fixture()
            f.nativeCheck()
            when (change) {
                "actor" -> f.set("reporterUserId", "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                "session" -> GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
                "foreground" -> f.set("isActivityForeground", false)
                "epoch" -> f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
            }
            var registrations = 0
            f.main.requestOfficialEnvironmentGpsForCheck(f.epoch,
                requestGeneral = { registrations++ }, requestSeparate = { _, _ -> registrations++ })
            assertEquals(change, 0, registrations)
        }
    }

    @Test fun sourceCleanupAndCameraTimeoutRemainSeparateAndOldAndroidObservationEntryExists() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val stop = MainActivityRefactorSource.function(source, "private fun stopLocationUpdatesInternal(")
        assertTrue(stop.contains("clearContinuousGpsHistory()"))
        val coordinateClear = MainActivityRefactorSource.function(source, "private fun clearTrustedLocation(")
        assertFalse(coordinateClear.contains("clearContinuousGpsHistory"))
        assertFalse(coordinateClear.contains("runtimeGpsQualityGate.reset"))
        val prewalkPorts = MainActivityRefactorSource.composition(source, "prewalkGuidanceProgress")
        assertTrue(prewalkPorts.contains(
            "override fun scheduleReadyTimeout(epoch: WalkRuntimeEpoch, generation: Long, delayMs: Long) ="))
        assertTrue(prewalkPorts.contains(
            "this@MainActivity.officialEnvironmentPreflightController.scheduleCameraTimeout(epoch, generation, delayMs)"))
        val prewalk = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/startup/capability/PrewalkGuidanceProgress.kt",
        ).readText(), "updatePrewalkGuidance")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(prewalk,
            "port.claimReadyGrace()", "port.scheduleReadyTimeout(", "epoch = epoch,",
            "generation = port.preflightGeneration,", "delayMs = readyGraceMs,",
        ))
        val preflight = File("src/main/java/kr/co/hanium/dreamup/walksafe/device/environment/OfficialEnvironmentPreflightController.kt").readText()
        val timeout = MainActivityRefactorSource.member(preflight, "scheduleCameraTimeout")
        assertTrue(timeout.contains("timeout !== action || !isCameraCurrent(epoch, generation)"))
        assertTrue(timeout.contains("synchronized(evidence.observationLock)"))
        assertTrue(timeout.contains("if (!facts.nativeCheckRequested()) evidence.clearGpsEvidenceOnly()"))
        val preflightPorts = ReportStaticSourceInspector.blockAfter(source, "private val officialEnvironmentPreflightController:")
        assertTrue(preflightPorts.contains("override fun nativeCheckRequested() = nativePhoneMountingCheckRequest != null"))
        val evidence = File("src/main/java/kr/co/hanium/dreamup/walksafe/device/environment/OfficialEnvironmentEvidenceStore.kt").readText()
        assertTrue(MainActivityRefactorSource.member(evidence, "clearGpsEvidenceOnly").contains("gpsEvidence = null"))
        val nativeRequest = source.substringAfter("private fun requestContinuousGpsForNativeCheck(")
            .substringBefore("private fun startOfficialEnvironmentCameraPreflight(")
        assertTrue(nativeRequest.contains("startLocationUpdatesIfAllowed()"))
        assertFalse(nativeRequest.contains("requestLocationUpdates("))
        assertFalse(nativeRequest.contains("forceRestart = true"))
        MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup()).findVirtual(
            MainActivity::class.java, "recordOfficialEnvironmentGpsObservation",
            MethodType.methodType(Void.TYPE, WalkRuntimeEpoch::class.java, java.lang.Long.TYPE,
                java.lang.Boolean::class.java, java.lang.Double::class.java))
    }

    @Test fun freshRecoveryRequestsOncePerFailureAndUsesTheOriginalLocationObject() {
        val f = Fixture()
        f.seedPassingMeasurement()
        var requests = 0
        var cancelled = 0
        var callback: ((android.location.Location?) -> Unit)? = null
        val delivered = mutableListOf<android.location.Location>()
        val request: ((android.location.Location?) -> Unit) -> (() -> Unit) = { result ->
            requests++; callback = result; { cancelled++ }
        }
        fun publish(accuracy: Double, observed: Long = f.now) = f.main.recordGeneralLocationGpsObservation(
            { observed }, true, accuracy, { f.now }, {}, requestFreshGps = request,
            consumeFreshGps = { raw, receipt -> assertEquals(f.now, receipt); delivered += raw })
        f.now = 1_100L; publish(30.0)
        assertEquals(1, requests)
        assertEquals(1_100L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        f.now = 1_500L; publish(30.0)
        assertEquals(1, requests)
        assertEquals(1_100L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        val raw = originalLocationObject()
        callback!!.invoke(raw)
        callback!!.invoke(raw)
        assertEquals(1, delivered.size)
        assertSame(raw, delivered.single())
        f.now = 2_000L; publish(30.0)
        assertEquals("A completed request is still the same bad episode", 1, requests)
        publish(3.0, observed = 1_500L)
        assertEquals("Old PASS cannot end the bad episode", 1, requests)
        f.now = 2_100L; publish(3.0)
        assertNull(f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        f.now = 2_200L; publish(30.0)
        assertEquals(2, requests)
        assertEquals(2_200L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        f.now = 2_300L; publish(3.0)
        assertEquals("Actual fresh PASS cancels an outstanding request", 1, cancelled)
        callback!!.invoke(raw)
        assertEquals(1, delivered.size)
        assertSame(f.callback, f.field("locationCallback"))
        assertSame(f.binding, f.field("positioningCollectionBinding"))
    }

    @Test fun recoveryRequestRejectsChangedOwnerAndHandlesSynchronousCompletionWithoutASecondRequest() {
        for (change in listOf("epoch", "session", "binding", "location_generation", "foreground")) {
            val f = Fixture()
            f.seedPassingMeasurement()
            var callback: ((android.location.Location?) -> Unit)? = null
            var delivered = 0
            var cancelled = 0
            f.now = 1_100L
            f.main.recordGeneralLocationGpsObservation({ f.now }, false, null, { f.now }, {},
                requestFreshGps = { result -> callback = result; { cancelled++ } },
                consumeFreshGps = { _, _ -> delivered++ })
            when (change) {
                "epoch" -> f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
                "session" -> GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
                "binding" -> f.set("positioningCollectionBinding", null)
                "location_generation" -> f.set("locationCallbackGeneration", 7)
                "foreground" -> f.set("isActivityForeground", false)
            }
            // Actual source invalidation owns cancellation; late SDK completion is harmless.
            f.main.currentContinuousGpsEvidence(f.lifecycle.snapshot().epoch, f.now)
            assertEquals(change, 1, cancelled)
            callback!!.invoke(originalLocationObject())
            assertEquals(change, 0, delivered)
        }
        val f = Fixture(); f.seedPassingMeasurement(); f.now = 1_100L
        var requests = 0
        var cancelled = 0
        val request: ((android.location.Location?) -> Unit) -> (() -> Unit) = { result ->
            requests++; result(null); { cancelled++ }
        }
        repeat(3) {
            f.main.recordGeneralLocationGpsObservation({ f.now }, false, null, { f.now }, {},
                requestFreshGps = request, consumeFreshGps = { _, _ -> fail() })
        }
        assertEquals(1, requests)
        assertEquals(1, cancelled)
    }

    @Test fun repeatedJumpKeepsTheDeadlineUntilPersistentBurstAndMockRemainsImmediatelyLimited() {
        val f = Fixture()
        val coordinator = f.field("positioningCoordinator") as PositioningCoordinator
        fun observe(lat: Double, at: Long, mock: Boolean = false): kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot {
            f.now = at
            val raw = kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssPositionObservation(lat, 127.0, 3.0, at, mock = mock)
            val snapshot = coordinator.observeGnss(raw)
            f.main.recordPositioningGpsObservation(snapshot, at, 3.0, { f.now }, {})
            return snapshot
        }
        observe(37.5, 1_000L); observe(37.5, 2_000L)
        val original = f.evidence
        val single = observe(37.6, 3_000L)
        assertTrue(single.singleOutlierAgainstFreshEstimate)
        assertEquals(1, single.consecutiveCorruptJumpCount)
        assertEquals(original, f.evidence)
        assertEquals("GPS_SINGLE_OUTLIER_AGAINST_FRESH_ESTIMATE", f.gate.latestRawObservation()?.detail)
        val duplicate = observe(37.6, 3_000L)
        assertTrue(duplicate.singleOutlierAgainstFreshEstimate)
        assertEquals(1, duplicate.consecutiveCorruptJumpCount)
        assertEquals(3_000L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        val distinct = observe(37.6001, 4_000L)
        assertFalse(distinct.singleOutlierAgainstFreshEstimate)
        assertEquals(2, distinct.consecutiveCorruptJumpCount)
        assertTrue(distinct.transientOutlierAgainstFreshEstimate)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.evidence?.status)
        assertEquals(3_000L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        val persistent = observe(37.6002, 6_000L)
        assertFalse(persistent.transientOutlierAgainstFreshEstimate)
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.evidence?.status)
        assertEquals("GPS_CORRUPT_JUMP", f.evidence?.detail)
        assertNull(f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        observe(37.5, 6_500L)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.evidence?.status)
        observe(37.5, 7_000L, mock = true)
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.evidence?.status)
        assertEquals("TRUSTED_FIX_UNAVAILABLE", f.evidence?.detail)
    }

    private fun originalLocationObject(): android.location.Location {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        // Identity-only SDK object: no mocked coordinates, timestamps or Android methods are read.
        return unsafe.allocateInstance(android.location.Location::class.java) as android.location.Location
    }

    internal class Fixture {
        val main = MainActivity()
        var now = 1_000L
        var locationPermissionGranted = true
        var locationServiceEnabled = true
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "continuous-location-start" },
            clock = { now }, elapsedRealtimeClock = { now })
        val epoch: WalkRuntimeEpoch
        val profile = requireNotNull(WalkSafeEnvironmentProfiles.active(BuildConfig.DEBUG)).officialEnvironment
        val gate get() = field("runtimeGpsQualityGate") as RuntimeMeasuredQualityGate
        val evidence get() = field("officialEnvironmentGpsEvidence") as MeasuredEnvironmentEvidence?
        val callback = object : LocationCallback() {}
        val binding: Any
        init {
            // The admitted-walk fixture owns the observed SDK permission fact.
            set("locationPermissionCheck", { locationPermissionGranted })
            set("locationServiceEnabledReader", { locationServiceEnabled })
            GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
            val actor = "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            val onboarding = FirstRunOnboardingPolicy.initialEmailAccount().copy(
                stage = FirstRunOnboardingStage.COMPLETE, ageBand = FirstRunAgeBand.VERIFIED_14_PLUS,
                completedReceiptHashes = listOf(FirstRunOnboardingStage.VERIFIED_LOGIN,
                    FirstRunOnboardingStage.PURPOSE_AND_SAFETY, FirstRunOnboardingStage.FP004_TRAINING)
                    .associateWith { FirstRunReceiptHash.fromSha256Hex("a".repeat(64)) },
                verifiedActorBinding = FirstRunOpaqueActorBinding.fromProvider(actor))
            val session = GatewayFieldSession.verified("https://gateway.example.test", actor,
                "walksafe_field_session=v2.continuous-location", Long.MAX_VALUE)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            val generation = GatewaySessionProcessCoordinator.snapshot().generation
            set("reporterUserId", actor)
            set("firstRunOnboardingSnapshot", onboarding)
            set("postLoginDeviceCheckSnapshot", PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL,
                actor, generation, now))
            set("isActivityForeground", true)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            epoch = lifecycle.snapshot().epoch
            set("walkSessionLifecycle", lifecycle)
            val ownerType = Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.LocationCollectionOwner")
            val owner = ownerType.declaredConstructors.single { it.parameterCount == 5 }
                .apply { isAccessible = true }.newInstance(actor, session, generation, null, null)
            set("locationCollectionOwner", owner)
            set("locationCallback", callback)
            set("locationCallbackGeneration", 3)
            val coordinator = field("positioningCoordinator") as PositioningCoordinator
            val bindingType = Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding")
            binding = bindingType.declaredConstructors.single().apply { isAccessible = true }
                .newInstance(coordinator, coordinator.currentInputGeneration(), owner, 3, now)
            set("positioningCollectionBinding", binding)
        }
        fun sample(accuracy: Double) = OfficialEnvironmentPolicy.assessGpsQuality(epoch, now,
            GpsQualityObservation(epoch, now, true, accuracy), profile)
        fun seedPassingMeasurement(): MeasuredEnvironmentEvidence {
            observe(3.0)
            return requireNotNull(evidence).also { assertEquals(EnvironmentEvidenceStatus.PASS, it.status) }
        }
        fun observe(accuracy: Double?, trusted: Boolean? = true, observedAt: Long = now,
            changed: () -> Unit = {}) = main.recordGeneralLocationGpsObservation({ observedAt },
                trusted, accuracy, { now }, changed)
        fun nativeCheck() {
            val type = Class.forName("kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController\$NativePhoneMountingCheckRequest")
            val request = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(
                field("reporterUserId"), GatewaySessionProcessCoordinator.snapshot().generation, null,
                PhoneMountingCheckRequest(epoch, 1L, now))
            set("nativePhoneMountingCheckRequest", request)
            set("nativePrewalkStartEpoch", epoch)
            set("nativePrewalkActorId", field("reporterUserId"))
        }
        fun activate() {
            val collector = WalkSessionReadinessCollector(epoch,
                WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
            collector.plan.requiredRequirements.forEach {
                collector.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY))
            }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(now)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertTrue(lifecycle.isRuntimeEpochCurrent(epoch))
        }
        fun field(name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(main)
        fun set(name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
    }
}
