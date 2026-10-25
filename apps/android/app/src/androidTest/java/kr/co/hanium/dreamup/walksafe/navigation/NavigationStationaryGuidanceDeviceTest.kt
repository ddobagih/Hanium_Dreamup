package kr.co.hanium.dreamup.walksafe.navigation

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.sqrt
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.MainActivityControllerTestFixture
import kr.co.hanium.dreamup.walksafe.device.ApprovedCameraFrameQualityProfile
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentEvidenceStore
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputDiagnostics
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputEvent
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputKind
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputOutcome
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteGuidanceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt in with allowNavigationStationaryGuidanceTest=true on a stationary physical phone.
 * Only this Activity's camera pitch range and its route State's projection axis are changed.
 * Fresh measured posture selects REAR_CAMERA (-Z) or diagnostic TOP_EDGE (+Y) once before start.
 * That axis stays fixed through later posture changes. TOP_EDGE is not rear-camera/body direction.
 * Native sensor values,
 * original times, quality/field checks, GPS, server search/route and native TTS remain real.
 * Existing login/onboarding are required: no credentials, account creation or evidence injection.
 * Initial road joining is observed separately from installed-route and actual ACTION completion.
 * This does not establish audibility, absolute bearing error, walking or arrival correctness.
 */
@RunWith(AndroidJUnit4::class)
class NavigationStationaryGuidanceDeviceTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 360_000L)
    fun existingAccountHomeStartsRealRouteAndMaintainsStationaryGuidanceForTwoMinutes() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit stationary posture-excluded device test opt-in is required",
            arguments.getString("allowNavigationStationaryGuidanceTest") == "true")
        assertTrue("The instance-only pitch seam is debug-only", BuildConfig.DEBUG)
        assertFalse("The broad guidance-start bypass must remain OFF", BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
        assertFalse("This test must preserve the existing account without fixture login",
            arguments.getString("allowDeviceTestAccountUiLogin") == "true")
        report("scope=REAL_MAIN_STATIONARY posture=EXCLUDED forward_axis=SELECT_ONCE_FROM_FRESH_POSTURE " +
            "gps_sensor_heading_route_tts=REAL sensor_pass_injected=false " +
            "initial_route_join=OBSERVATION_ONLY " +
            "human_audibility=UNVERIFIED absolute_bearing_error=UNVERIFIED human_walk=UNVERIFIED")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            val observation = Observation(::report)
            var pose: PoseLease? = null
            var ownsStart = false
            var homeCancelled = false
            var primaryFailure: Throwable? = null
            try {
                await("Existing signed-in, education-complete HOME was unavailable", 25_000L) {
                    onMain { activity.hasWindowFocus() &&
                        call(activity, "firstRunOnboardingComplete") == true &&
                        call(activity, "nativeHomeFeatureContextAvailable") == true &&
                        field(activity, "nativeUiPage").toString() == "HOME" }
                }
                onMain {
                    assertEquals("Do not interrupt an existing walk", "READY", walkState(activity))
                    assertFalse("Do not interrupt an existing route", field(activity, "isRouteActive") as Boolean)
                    assertFalse("Do not interrupt a real recording", field(activity, "voiceRecognitionActive") as Boolean)
                    pose = installPoseLease(activity)
                    observation.pose = pose
                    // The production factory creates the real engine; no SpeechRuntime is replaced.
                    call(activity, "ensureFeedbackActuator")
                    observation.attach(activity)
                    val homeCard = descendants(activity.window.decorView).filterIsInstance<TextView>()
                        .firstOrNull { it.isShown && it.text.toString() == "길라잡이" }
                        ?.let(::clickableAncestor)
                    assertNotNull("Real HOME must expose the 길라잡이 card", homeCard)
                    assertTrue(requireNotNull(homeCard).performClick())
                    assertEquals("DESTINATION_SEARCH", field(activity, "nativeUiPage").toString())
                    (field(activity, "destinationQueryInput") as EditText).setText("편의점")
                    assertTrue((field(activity, "destinationSearchButton") as Button).performClick())
                }
                await("Live destination search returned no selectable result", 45_000L) {
                    onMain {
                        observation.attach(activity)
                        field(activity, "destinationSearchQuery") == "편의점" &&
                            (field(activity, "destinationSearchResults") as List<*>).isNotEmpty() &&
                            resultButtons(activity).any { it.isShown && it.isEnabled }
                    }
                }
                val poseLease = requireNotNull(pose)
                // Poll only from the instrumentation thread; native sensor callbacks remain free to arrive.
                await("FRESH_POSTURE_UNAVAILABLE: no fresh qualified gravity/RV posture for axis selection", 10_000L,
                    detail = poseLease::diagnostic) { poseLease.freshPosture() != null }
                val destination = onMain {
                    assertTrue(resultButtons(activity).first { it.isShown && it.isEnabled }.performClick())
                    assertEquals("DESTINATION_CONFIRM", field(activity, "nativeUiPage").toString())
                    val selected = requireNotNull(field(activity, "pendingUiDestination"))
                    val start = field(activity, "nativeDestinationStartButton") as Button
                    assertTrue("Real destination start must be enabled", start.isShown && start.isEnabled)
                    poseLease.selectOnce()
                    report("pose_axis_selected ${poseLease.selectionDiagnostic()}")
                    observation.attach(activity)
                    ownsStart = true
                    assertTrue(start.performClick())
                    assertEquals("GUIDANCE", field(activity, "nativeUiPage").toString())
                    selected
                }
                await("Live start did not reach ACTIVE with an installed route for the selected destination; measured block is not success", 90_000L,
                    detail = { onMain { observation.sample(activity) } }) {
                    onMain {
                        observation.sample(activity)
                        val navigator = field(activity, "routeNavigator") as RouteNavigator
                        // A real initial approach ACTION is supported while onRoute remains false.
                        // run.active alone is insufficient: it becomes true before the server responds.
                        val active = walkState(activity) == "ACTIVE" &&
                            field(activity, "isRouteActive") == true &&
                            navigator.hasRoute()
                        if (active) assertTrue("The selected live destination must be preserved",
                            call(destination, "getPoint") == field(activity, "currentDestination"))
                        active
                    }
                }
                val activeAtMs = SystemClock.elapsedRealtime()
                report("route_active=true route_installed=true active_at_elapsed_ms=$activeAtMs")
                await("No completed real route ACTION/native navigation START and DONE", 45_000L,
                    detail = { onMain { observation.sample(activity) } }) {
                    onMain {
                        observation.sample(activity)
                        val navigator = field(activity, "routeNavigator") as RouteNavigator
                        hasCompletedRouteAction(navigator) &&
                            observation.speech.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.STARTED) > 0 &&
                            observation.speech.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE) > 0
                    }
                }
                report("first_route_action_completed=true native_navigation_started_and_done=true")
                val maintainStartedAtMs = SystemClock.elapsedRealtime()
                val maintainUntilMs = maintainStartedAtMs + 120_000L
                var nextReportMs = maintainStartedAtMs
                while (SystemClock.elapsedRealtime() < maintainUntilMs) {
                    val sample = onMain {
                        val detail = observation.sample(activity)
                        assertTrue("Main lost foreground during stationary observation; $detail", activity.hasWindowFocus())
                        assertEquals("Walk did not remain ACTIVE; $detail", "ACTIVE", walkState(activity))
                        assertEquals("Guidance page was lost; $detail", "GUIDANCE", field(activity, "nativeUiPage").toString())
                        assertTrue("Live route was cleared; $detail", field(activity, "isRouteActive") as Boolean)
                        detail
                    }
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs >= nextReportMs) {
                        report("maintain_elapsed_ms=${nowMs - maintainStartedAtMs} $sample")
                        nextReportMs = nowMs + 10_000L
                    }
                    SystemClock.sleep(200L)
                }
                report("maintained_ms=${SystemClock.elapsedRealtime() - maintainStartedAtMs} " +
                    "${observation.summary()} naturally_observed_recovery_only=true " +
                    "unobserved_fault_recovery=NOT_TESTED human_walk=UNVERIFIED absolute_bearing_error=UNVERIFIED")
            } catch (failure: Throwable) {
                primaryFailure = failure
                report("result=FAILED ${observation.summary()}")
                throw failure
            } finally {
                var cleanupFailure: Throwable? = null
                try {
                    if (ownsStart) {
                        onMain { call(activity, "cancelNativeGuidanceAndReturnHome") }
                        await("Owned guidance did not return to HOME after cancellation", 15_000L) {
                            onMain { field(activity, "nativeUiPage").toString() == "HOME" &&
                                field(activity, "isRouteActive") == false &&
                                field(activity, "nativePhoneMountingCheckRequest") == null }
                        }
                        homeCancelled = true
                    }
                } catch (failure: Throwable) {
                    cleanupFailure = failure
                } finally {
                    try {
                        onMain {
                            try { observation.restoreObservers() } finally { pose?.close() }
                        }
                        report("cleanup_home_cancelled=$homeCancelled pose_seams_restored=true ${observation.summary()}")
                    } catch (failure: Throwable) {
                        cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
                    }
                }
                cleanupFailure?.let { failure ->
                    primaryFailure?.addSuppressed(failure) ?: throw failure
                }
            }
        }
    }

    /** Main thread, before route start. Reset the real Tracker at an explicit registration boundary. */
    private fun installPoseLease(activity: MainActivity): PoseLease {
        val pitch = MainActivity::class.java.getDeclaredField("mountPitchRangeForInstrumentation").apply { isAccessible = true }
        val originalPitch = pitch.get(activity)
        assertEquals("Another instrumentation session must not own pitch", null, originalPitch)
        val originalProfile = call(activity, "getActiveCameraFrameQualityProfile") as ApprovedCameraFrameQualityProfile
        val tracker = rawField(activity, "earthOrientationTracker") as AndroidEarthOrientationTracker
        val state = rawField(tracker, "routeCompassState") as RouteCompassSensorState
        val axis = RouteCompassSensorState::class.java.getDeclaredField("forwardAxisForInstrumentation").apply { isAccessible = true }
        val originalAxis = axis.get(state)
        assertEquals(RouteCompassForwardAxis.REAR_CAMERA, originalAxis)
        val startedByTest = synchronized(tracker) { !state.isStarted }
        val lease = PoseLease(tracker, state, select = { selected ->
            tracker.stop()
            axis.set(state, selected)
            assertTrue("SENSOR_UNAVAILABLE: real registration failed after axis selection", tracker.start())
        }, restore = {
            // Pitch restoration must still happen if native sensor restart fails.
            try {
                synchronized(tracker) {
                    val restart = state.isStarted && !startedByTest
                    tracker.stop()
                    axis.set(state, originalAxis)
                    if (restart) assertTrue("Real sensor registration failed after seam restoration", tracker.start())
                }
            } finally {
                pitch.set(activity, originalPitch)
            }
        })
        try {
            pitch.set(activity, -90.0..90.0)
            val diagnostic = call(activity, "getActiveCameraFrameQualityProfile") as ApprovedCameraFrameQualityProfile
            assertEquals(-90.0, diagnostic.minimumMountPitchDegrees, 0.0)
            assertEquals(90.0, diagnostic.maximumMountPitchDegrees, 0.0)
            assertEquals("Only camera pitch bounds may differ", originalProfile,
                diagnostic.copy(minimumMountPitchDegrees = originalProfile.minimumMountPitchDegrees,
                    maximumMountPitchDegrees = originalProfile.maximumMountPitchDegrees))
            synchronized(tracker) {
                assertTrue("SENSOR_UNAVAILABLE: real registration failed before posture measurement", tracker.start())
            }
            return lease
        } catch (failure: Throwable) {
            try { lease.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private data class PostureSample(val source: String, val observedAtMs: Long,
        val quality: EarthOrientationAccuracy, val upX: Double, val upY: Double, val upZ: Double) {
        val rearHorizontal get() = sqrt((1.0 - upZ * upZ).coerceIn(0.0, 1.0))
        val topHorizontal get() = sqrt((1.0 - upY * upY).coerceIn(0.0, 1.0))
        val preferredAxis get() = if (rearHorizontal >= topHorizontal)
            RouteCompassForwardAxis.REAR_CAMERA else RouteCompassForwardAxis.TOP_EDGE
        fun tiltChangeDegrees(other: PostureSample): Double = Math.toDegrees(
            acos((upX * other.upX + upY * other.upY + upZ * other.upZ).coerceIn(-1.0, 1.0)))
    }

    private class PoseLease(private val tracker: AndroidEarthOrientationTracker,
        private val state: RouteCompassSensorState, private val select: (RouteCompassForwardAxis) -> Unit,
        private val restore: () -> Unit) {
        private var initial: PostureSample? = null
        private var selectedAxis: RouteCompassForwardAxis? = null
        private var maximumTiltChangeDegrees = 0.0

        fun freshPosture(): PostureSample? = synchronized(tracker) {
            val nowMs = SystemClock.elapsedRealtime()
            if (!state.isStarted) return@synchronized null
            fun fresh(atMs: Long) = atMs >= 0L && nowMs >= atMs && nowMs - atMs <= 500L
            val gravity = rawField(state, "latestGravity") as? RouteCompassVectorSample
            if (gravity != null && fresh(gravity.observedAtMs) && gravity.hasValidSensorTimestamp() &&
                gravity.accuracy >= EarthOrientationAccuracy.MEDIUM &&
                gravity.norm().isFinite() && abs(gravity.norm() - 9.80665) <= 2.5
            ) {
                val norm = gravity.norm()
                return@synchronized PostureSample("GRAVITY", gravity.observedAtMs, gravity.accuracy,
                    gravity.x / norm, gravity.y / norm, gravity.z / norm)
            }
            val orientation = rawField(state, "latestRouteOrientation") as? DeviceEarthOrientation
                ?: return@synchronized null
            if (!fresh(orientation.observedAtElapsedRealtimeMs) || orientation.accuracy < EarthOrientationAccuracy.MEDIUM ||
                !orientation.deviceToMagneticEnu.isOrthonormal()) return@synchronized null
            // Matrix tilt can be measured when RV azimuth error is unavailable. This grants no heading quality.
            val r = orientation.deviceToMagneticEnu
            val norm = sqrt(r.m20.toDouble() * r.m20 + r.m21.toDouble() * r.m21 + r.m22.toDouble() * r.m22)
            PostureSample("ROTATION_VECTOR", orientation.observedAtElapsedRealtimeMs, orientation.accuracy,
                r.m20 / norm, r.m21 / norm, r.m22 / norm)
        }

        fun selectOnce() = synchronized(tracker) {
            check(selectedAxis == null) { "The trial projection axis must never switch after selection" }
            val sample = requireNotNull(freshPosture()) { "FRESH_POSTURE_UNAVAILABLE at axis selection" }
            select(sample.preferredAxis) // The only selection writes occur at this pre-start registration boundary.
            initial = sample
            selectedAxis = sample.preferredAxis
        }

        fun selectionDiagnostic(): String {
            val sample = requireNotNull(initial)
            return "forward_axis=$selectedAxis axis_locked=true posture_source=${sample.source} " +
                "posture_observed_ms=${sample.observedAtMs} posture_quality=${sample.quality} " +
                "rear_horizontal=${sample.rearHorizontal} top_horizontal=${sample.topHorizontal}"
        }

        fun diagnostic(): String {
            val sample = freshPosture()
            val change = sample?.let { current -> initial?.tiltChangeDegrees(current) }
            if (change != null) maximumTiltChangeDegrees = maxOf(maximumTiltChangeDegrees, change)
            // Gravity-up tilt observes pitch/roll, not yaw. The threshold is diagnostic only and never switches axes.
            return "forward_axis=$selectedAxis axis_locked=${selectedAxis != null} posture_fresh=${sample != null} " +
                "posture_source=${sample?.source} posture_quality=${sample?.quality} " +
                "posture_age_ms=${sample?.let { SystemClock.elapsedRealtime() - it.observedAtMs }} " +
                "rear_horizontal=${sample?.rearHorizontal} top_horizontal=${sample?.topHorizontal} " +
                "posture_tilt_change_deg=$change posture_large_tilt_now=${change?.let { it >= 30.0 }} " +
                "posture_max_tilt_change_deg=$maximumTiltChangeDegrees posture_large_tilt_seen=${maximumTiltChangeDegrees >= 30.0}"
        }

        fun close() = restore()
    }

    private class SpeechBinding(val previous: ((SpeechOutputEvent) -> Unit)?, val wrapper: (SpeechOutputEvent) -> Unit)
    private class CompassBinding(val previous: (RouteCompassObservationEvent) -> Unit,
        val wrapper: (RouteCompassObservationEvent) -> Unit)
    private class StatusBinding(val previous: RouteGuidanceController.SpeechPort,
        val wrapper: RouteGuidanceController.SpeechPort)
    private data class DirectionStatusEvent(val attempt: Long, val phase: String, val atMs: Long)

    private class Observation(private val report: (String) -> Unit) {
        val speech = SpeechOutputDiagnostics()
        var pose: PoseLease? = null
        private val listeners = IdentityHashMap<AndroidFeedbackActuator, SpeechBinding>()
        private val compassListeners = IdentityHashMap<AndroidEarthOrientationTracker, CompassBinding>()
        private val statusListeners = IdentityHashMap<RouteGuidanceController, StatusBinding>()
        private val directionStatusEvents = mutableListOf<DirectionStatusEvent>()
        private var directionStatusAttempt = 0L
        private val latestCompassEvent = AtomicReference<RouteCompassObservationEvent?>()
        private val transitions = linkedMapOf<String, Int>()
        private val previous = mutableMapOf<String, String>()
        private val warningTimes = mutableMapOf<String, Long>()
        private val warningCounts = linkedMapOf<String, Int>()
        private var lastSample = "none"
        private var lastReportedAtMs: Long? = null

        fun attach(activity: MainActivity) {
            attachCompass(activity)
            attachDirectionStatus(activity)
            val actuator = field(activity, "feedbackActuator") as? AndroidFeedbackActuator ?: return
            if (actuator in listeners) return
            val delegate = actuator.guidanceDiagnosticListener
            val wrapper: (SpeechOutputEvent) -> Unit = { event ->
                try { delegate?.invoke(event) } finally { speech.record(event) }
            }
            listeners[actuator] = SpeechBinding(delegate, wrapper)
            actuator.guidanceDiagnosticListener = wrapper
        }

        private fun attachCompass(activity: MainActivity) {
            val tracker = rawField(activity, "earthOrientationTracker") as AndroidEarthOrientationTracker
            @Suppress("UNCHECKED_CAST")
            val delegate = rawField(tracker, "routeObservationListener") as? ((RouteCompassObservationEvent) -> Unit)
                ?: return
            if (compassListeners[tracker]?.wrapper === delegate) return
            val wrapper: (RouteCompassObservationEvent) -> Unit = { event ->
                // Observe the actual production result; never evaluate, replace or retime input.
                latestCompassEvent.set(event)
                delegate(event)
            }
            compassListeners[tracker] = CompassBinding(delegate, wrapper)
            tracker.setRouteObservationListener(wrapper)
        }

        private fun attachDirectionStatus(activity: MainActivity) {
            val controller = call(activity, "getRouteGuidanceController") as RouteGuidanceController
            val slot = RouteGuidanceController::class.java.getDeclaredField("speech").apply { isAccessible = true }
            val delegate = slot.get(controller) as RouteGuidanceController.SpeechPort
            if (statusListeners[controller]?.wrapper === delegate) return
            val wrapper = object : RouteGuidanceController.SpeechPort by delegate {
                override fun submitStatus(message: String, current: () -> Boolean, completed: () -> Unit,
                    failed: (() -> Unit)?): Boolean {
                    // Fixed classification only. No text is retained/output, and current is never evaluated here.
                    if (message != "방향을 다시 확인하고 있습니다.") {
                        return delegate.submitStatus(message, current, completed, failed)
                    }
                    val attempt = synchronized(directionStatusEvents) {
                        val next = ++directionStatusAttempt
                        directionStatusEvents += DirectionStatusEvent(next, "SUBMITTED", SystemClock.elapsedRealtime())
                        next
                    }
                    val terminalObserved = AtomicBoolean(false)
                    val observedCompleted = {
                        val atMs = SystemClock.elapsedRealtime()
                        val firstTerminal = terminalObserved.compareAndSet(false, true)
                        try { completed() } finally {
                            if (firstTerminal) recordDirectionStatus(attempt, "DONE", atMs)
                        }
                    }
                    val observedFailed = failed?.let { original ->
                        {
                            val atMs = SystemClock.elapsedRealtime()
                            val firstTerminal = terminalObserved.compareAndSet(false, true)
                            try { original() } finally {
                                if (firstTerminal) recordDirectionStatus(attempt, "FAILED_OR_CANCELLED", atMs)
                            }
                        }
                    }
                    // Deduplicate accounting only: every real callback still reaches its original delegate.
                    val accepted = delegate.submitStatus(message, current, observedCompleted, observedFailed)
                    recordDirectionStatus(attempt, if (accepted) "ACCEPTED" else "REJECTED", SystemClock.elapsedRealtime())
                    return accepted
                }
            }
            statusListeners[controller] = StatusBinding(delegate, wrapper)
            slot.set(controller, wrapper)
            assertTrue("The real status port must retain the observation wrapper", slot.get(controller) === wrapper)
        }

        private fun recordDirectionStatus(attempt: Long, phase: String, atMs: Long) {
            synchronized(directionStatusEvents) { directionStatusEvents += DirectionStatusEvent(attempt, phase, atMs) }
        }

        private fun directionStatusSummary(): String = synchronized(directionStatusEvents) {
            // DONE is the real, owned status completion callback; native TTS outcomes remain separate below.
            "direction_recheck_port_counts=${directionStatusEvents.groupingBy { it.phase }.eachCount()} " +
                "direction_recheck_port_events=[${directionStatusEvents.joinToString(",") { "${it.attempt}:${it.phase}@${it.atMs}" }}]"
        }

        fun restoreObservers() {
            statusListeners.forEach { (controller, binding) ->
                val slot = RouteGuidanceController::class.java.getDeclaredField("speech").apply { isAccessible = true }
                if (slot.get(controller) === binding.wrapper) slot.set(controller, binding.previous)
            }
            listeners.forEach { (actuator, binding) ->
                if (actuator.guidanceDiagnosticListener === binding.wrapper) {
                    actuator.guidanceDiagnosticListener = binding.previous
                }
            }
            compassListeners.forEach { (tracker, binding) ->
                if (rawField(tracker, "routeObservationListener") === binding.wrapper) {
                    tracker.setRouteObservationListener(binding.previous)
                }
            }
        }

        fun sample(activity: MainActivity): String {
            attach(activity)
            val nowMs = SystemClock.elapsedRealtime()
            val walk = walkState(activity)
            val compass = field(activity, "latestRouteFacingUnavailableReason").toString()
            val guard = field(activity, "officialEnvironmentRuntimeGuard")
            val decision = guard?.let { call(it, "decision") }
            val gpsNavigation = decision?.let { call(it, "getNavigationOutputsAllowed") }.toString()
            val cameraOutputs = decision?.let { call(it, "getCameraOutputsAllowed") }.toString()
            val enabledMeasured = decision?.let { call(it, "getEnabledMeasuredFactors") }.toString()
            val factors = field(activity, "lastOfficialEnvironmentUnavailableFactors").toString()
            val mounting = field(activity, "phoneMountingOutputsAllowed").toString()
            track("walk", walk); track("compass", compass); track("gps_navigation", gpsNavigation)
            track("unavailable", factors); track("mounting_outputs", mounting)
            val runtime = (rawField(activity, "officialEnvironmentRuntimeController\$delegate") as Lazy<*>).value!!
            @Suppress("UNCHECKED_CAST")
            val delivered = rawField(runtime, "lastWarningAtMs") as Map<Any, Long>
            delivered.forEach { (factor, atMs) ->
                val key = factor.toString()
                if (warningTimes.put(key, atMs) != atMs) warningCounts[key] = (warningCounts[key] ?: 0) + 1
            }
            val camera = field(activity, "officialEnvironmentCameraDiagnosticObservation") as? CameraFrameQualityObservation
            val rawGps = field(activity, "latestRawRouteLocation") as? TrustedLocation
            val evidence = (rawField(activity, "officialEnvironmentEvidenceStore\$delegate") as Lazy<*>).value as OfficialEnvironmentEvidenceStore
            val rawGpsEvidence = synchronized(evidence.observationLock) { evidence.gpsQualityGate.latestRawObservation() }
            val rawCameraEvidence = synchronized(evidence.observationLock) { evidence.cameraQualityGate.latestRawObservation() }
            val tracker = rawField(activity, "earthOrientationTracker") as AndroidEarthOrientationTracker
            val event = latestCompassEvent.get()?.takeIf(tracker::isRouteObservationCurrent)
            val heading = event?.heading
            val headingAt = heading?.observation?.observedAtElapsedRealtimeMs ?: heading?.checkEvidence?.observedAtElapsedRealtimeMs
            val checks = heading?.checkEvidence
            val navigator = field(activity, "routeNavigator") as RouteNavigator
            val routeActive = field(activity, "isRouteActive") == true
            val onRoute = field(activity, "latestTmapOnRoute") == true
            val initialJoinPending = navigator.isWaitingForInitialRouteJoin()
            val routeRequestPending = call(requireNotNull(call(activity, "getRouteAcquisitionController")), "getRequestPending")
            val selectedDestination = field(activity, "pendingUiDestination")
            val destinationMatches = selectedDestination != null &&
                call(selectedDestination, "getPoint") == field(activity, "currentDestination")
            val installedStart = field(activity, "installedRouteStartSpeechCoordinator") as InstalledRouteStartSpeechCoordinator
            val direction = navigator.currentDirectionGraceSnapshot()?.mode.toString()
            track("direction", direction)
            track("route_active", routeActive.toString()); track("route_on_route", onRoute.toString())
            track("initial_join_pending", initialJoinPending.toString())
            lastSample = "walk=$walk compass=$compass direction=$direction gps_navigation=$gpsNavigation " +
                "${pose?.diagnostic()} " +
                "route_active=$routeActive route_installed=${navigator.hasRoute()} route_on_route=$onRoute " +
                "initial_join_pending=$initialJoinPending route_request_pending=$routeRequestPending destination_matches=$destinationMatches " +
                "route_action_pending=${navigator.hasPendingInstruction()} route_action_completed=${hasCompletedRouteAction(navigator)} " +
                "initial_join_action_completed=${rawField(navigator, "lastCompletedInitialJoinReference") != null} " +
                "start_announcement_pending=${installedStart.pending != null} prewalk_stage=${field(activity, "prewalkGuidanceStage")} " +
                "preflight_phase=${field(activity, "officialEnvironmentPreflightPhase")} " +
                "enabled_measured=$enabledMeasured unavailable=$factors mounting_outputs=$mounting camera_outputs=$cameraOutputs " +
                "gps_raw_age_ms=${rawGps?.let { nowMs - it.elapsedRealtimeMs }} gps_raw_accuracy_m=${rawGps?.accuracyM} " +
                "gps_measured_status=${rawGpsEvidence?.status} gps_measured_age_ms=${rawGpsEvidence?.observedAtElapsedRealtimeMs?.let { nowMs - it }} " +
                "gps_measured_detail=${rawGpsEvidence?.detail} camera_measured_status=${rawCameraEvidence?.status} " +
                "gps_preflight_accuracy_m=${field(activity, "officialEnvironmentGpsDiagnosticAccuracyMeters")} " +
                "gps_detail=${field(activity, "officialEnvironmentGpsDiagnosticDetail")} " +
                "camera_reason=${field(activity, "officialEnvironmentCameraDiagnosticReason")} " +
                "real_mount_pitch_deg=${camera?.mountPitchDegrees} " +
                "compass_event_reason=${heading?.reason} compass_source=${heading?.observation?.source ?: checks?.source} " +
                "compass_measurement_age_ms=${headingAt?.let { nowMs - it }} compass_receipt_age_ms=${event?.let { nowMs - it.receivedAtMs }} " +
                "compass_quality=${heading?.observation?.sensorQuality} magnetic_ut=${checks?.magneticFieldMicrotesla} " +
                "expected_magnetic_ut=${checks?.expectedMagneticFieldMicrotesla} magnetic_allowance_ut=${checks?.magneticFieldAllowanceMicrotesla} " +
                "dip_residual_deg=${checks?.magneticInclinationResidualDegrees} rejected_check=${checks?.rejectedCheck}"
            if (lastReportedAtMs?.let { nowMs - it >= 1_000L } != false) {
                lastReportedAtMs = nowMs
                report("sensor_snapshot elapsed_ms=$nowMs $lastSample")
            }
            return lastSample
        }

        private fun track(kind: String, value: String) {
            val old = previous.put(kind, value)
            if (old != value) {
                val key = "$kind:$value"
                transitions[key] = (transitions[key] ?: 0) + 1
            }
        }

        fun summary(): String = "observed_state_entries=$transitions observed_warning_done_counts=$warningCounts " +
            "${directionStatusSummary()} native_tts_counts=${speech.snapshot()} sampled_poll_ms=200 last=[$lastSample]"
    }

    companion object {
        private fun field(target: Any, name: String): Any? = MainActivityControllerTestFixture.field(name).get(target)
        private fun rawField(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(target)
        private fun call(target: Any, name: String): Any? = target.javaClass.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(target)
        // Both markers require an acknowledged real ACTION and reset with the installed route.
        // The initial reference survives later cue invalidation; a start summary sets neither marker.
        private fun hasCompletedRouteAction(navigator: RouteNavigator): Boolean =
            rawField(navigator, "completedSpeechCue") != null ||
                rawField(navigator, "lastCompletedInitialJoinReference") != null
        private fun walkState(activity: MainActivity): String =
            call(requireNotNull(call(requireNotNull(field(activity, "walkSessionLifecycle")), "snapshot")), "getState").toString()
        private fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        private fun clickableAncestor(view: View): View? {
            var candidate: View? = view
            while (candidate != null && !candidate.isClickable) candidate = candidate.parent as? View
            return candidate
        }
        private fun resultButtons(activity: MainActivity): List<Button> =
            descendants(field(activity, "destinationSearchResultsContainer") as View).filterIsInstance<Button>()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun await(message: String, timeoutMs: Long, detail: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(200L)
        }
        throw AssertionError("$message; ${detail()}")
    }

    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "STATIONARY_GUIDANCE $message\n")
    })
}
