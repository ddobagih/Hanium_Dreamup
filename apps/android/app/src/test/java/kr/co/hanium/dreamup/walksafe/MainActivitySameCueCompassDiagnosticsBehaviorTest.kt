package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.*
import org.junit.Test

/**
 * Executes the Main generation formatter used after reservation with real compass policy and
 * Navigator inputs. Private Main automatic/manual/retry dispatch and sensor/Field/TTS effects
 * remain SOURCE observations; these tests never substitute a currentness result for a guard.
 * Diagnostic fixtures disable only the recheck reaction delay so an actual cue is available;
 * RouteDirectionRecheckGraceTest exercises the production 1,500ms default separately.
 */
class MainActivitySameCueCompassDiagnosticsBehaviorTest {
    private val main = MainActivity()

    @Test fun mainGenerationFormatterKeepsTheExactRejectedChecksOfItsNavigatorCue() {
        val compass = compass(magneticY = 40.0)
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY, compass.reason)
        val navigator = navigator()
        val update = update(navigator, compass)
        val before = navigator.currentRouteId()
        val fields = main.routeGuidanceGeneratedValues(update, 1_010L, compass)
        assertEquals(-53, fields["magnetic_inclination_deg"])
        assertEquals(53, fields["expected_magnetic_inclination_deg"])
        assertEquals(106, fields["magnetic_inclination_residual_deg"])
        assertEquals(30, fields["magnetic_inclination_allowance_deg"])
        assertEquals("MAGNETIC_FIELD_ANOMALY", fields["compass_reason"])
        assertEquals("MAGNETIC_FIELD_INCLINATION", fields["compass_rejected_check"])
        assertEquals("GRAVITY_MAGNETIC", fields["compass_source"])
        assertEquals(10L, fields["compass_input_age_ms"])
        assertEquals("CURRENT_SEGMENT", fields["reason"])
        assertEquals("UNKNOWN", fields["expression_level"])
        assertNull(fields["relative_angle_deg"])
        assertEquals(cueId(update), fields["cue_id"])
        assertEquals(before, navigator.currentRouteId())
    }

    @Test fun sameReasonAndSourceDoNotReplaceEachCuesActualNumbersWithAStatusCache() {
        val first = compass()
        val second = compass(magneticY = -30.0, magneticZ = -40.0, nowMs = 1_100L)
        assertEquals(first.reason, second.reason)
        assertEquals(first.checkEvidence?.source, second.checkEvidence?.source)
        val navigator = navigator()
        val firstUpdate = update(navigator, first)
        val firstFields = main.routeGuidanceGeneratedValues(firstUpdate, 1_010L, first)
        val secondUpdate = requireNotNull(navigator.currentGuidance(1_100L, second.observation, second.reason))
        val secondFields = main.routeGuidanceGeneratedValues(secondUpdate, 1_110L, second)
        assertEquals(53, firstFields["magnetic_inclination_deg"])
        assertEquals(37, secondFields["magnetic_inclination_deg"])
        assertEquals(cueId(firstUpdate), firstFields["cue_id"])
        assertEquals(cueId(secondUpdate), secondFields["cue_id"])
        assertEquals(10L, firstFields["compass_input_age_ms"])
        assertEquals(10L, secondFields["compass_input_age_ms"])
        assertEquals(first, compass())
    }

    @Test fun delayedFormatterUsesItsCapturedResultEvenAfterTheNextCompassInputArrives() {
        val captured = compass(magneticY = 40.0)
        val update = update(navigator(), captured)
        var fields: Map<String, Any?>? = null
        val queuedFormatting = Runnable { fields = main.routeGuidanceGeneratedValues(update, 1_700L, captured) }
        val latest = compass(magneticY = -30.0, magneticZ = -40.0, nowMs = 1_600L)
        queuedFormatting.run()
        val values = requireNotNull(fields)
        assertEquals(RouteCompassHeadingReason.VALID, latest.reason)
        assertEquals(-53, values["magnetic_inclination_deg"])
        assertEquals("MAGNETIC_FIELD_ANOMALY", values["compass_reason"])
        assertEquals(700L, values["compass_input_age_ms"])
        assertEquals(cueId(update), values["cue_id"])
        // This queues only the production formatter; it is not a Handler/dispatch admission test.
    }

    @Test fun nullCompassRetainsTheGenericDiagnosticAndInventsNoSensorFacts() {
        val update = update(navigator(), null)
        val fields = main.routeGuidanceGeneratedValues(update, 1_234L, null)
        assertEquals(cueId(update), fields["cue_id"])
        assertEquals(1_234L, fields["elapsed_realtime_ms"])
        assertEquals("CURRENT_SEGMENT", fields["reason"])
        assertFalse(fields.containsKey("compass_reason"))
        assertFalse(fields.containsKey("magnetic_inclination_deg"))
        assertEquals("UNKNOWN", fields["expression_level"])
    }

    @Test fun generationAndActualNavigatorStartChecksKeepOneCueAndTheirDifferentTimes() {
        val compass = compass(magneticY = 40.0)
        val navigator = navigator()
        val update = update(navigator, compass)
        assertTrue(navigator.reserveInstruction(update))
        val generated = main.routeGuidanceGeneratedValues(update, 1_010L, compass)
        val current = navigator.isInstructionCurrent(update, 1_020L, compass.observation)
        assertTrue(current)
        val checked = RouteGuidanceDiagnostics.guidanceStartChecked(update, 1_020L, current)
        assertEquals(generated["cue_id"], checked["cue_id"])
        assertEquals(1_010L, generated["elapsed_realtime_ms"])
        assertEquals(1_020L, checked["elapsed_realtime_ms"])
        assertEquals("started", checked["result"])
        assertFalse(checked.containsKey("magnetic_inclination_deg"))
        assertFalse(navigator.reserveInstruction(update))
        navigator.acknowledgeInstruction(update, 1_021L)
        assertFalse(navigator.reserveInstruction(update))
        // "started" here names the original start-check schema, not physical TTS playback.
    }

    @Test fun staleAndCancelledNavigatorCuesRemainRejectedWithoutChangingCapturedDiagnostics() {
        val compass = compass(magneticY = 40.0)
        val navigator = navigator()
        val update = update(navigator, compass)
        assertTrue(navigator.reserveInstruction(update))
        val captured = main.routeGuidanceGeneratedValues(update, 1_010L, compass)
        assertFalse(navigator.isInstructionCurrent(update, 20_000L, compass.observation))
        navigator.clear()
        assertFalse(navigator.isInstructionCurrent(update, 1_020L, compass.observation))
        assertFalse(navigator.reserveInstruction(update))
        assertEquals(captured, main.routeGuidanceGeneratedValues(update, 1_010L, compass))
        assertNull(navigator.currentRouteId())
    }

    @Test fun retryUsesItsOwnCompassWithTheOriginalReleaseCadenceAndCueOwnership() {
        val first = compass(magneticY = 40.0)
        val navigator = navigator()
        val initial = update(navigator, first)
        assertTrue(navigator.reserveInstruction(initial))
        navigator.releaseInstruction(initial, 1_001L)
        val retryAt = 1_001L + RouteNavigatorConfig().speechRetryIntervalMs
        assertEquals("guidance_retry_wait", navigator.retryGuidance(retryAt - 1L)?.reason)
        val retryCompass = compass(nowMs = retryAt)
        val retry = requireNotNull(navigator.retryGuidance(retryAt, retryCompass.observation, retryCompass.reason))
        assertTrue(navigator.reserveInstruction(retry))
        assertFalse(navigator.reserveInstruction(initial))
        val fields = main.routeGuidanceGeneratedValues(retry, retryAt + 10L, retryCompass)
        assertEquals(53, fields["magnetic_inclination_deg"])
        assertEquals("VALID", fields["compass_reason"])
        assertEquals(10L, fields["compass_input_age_ms"])
        assertEquals(cueId(retry), fields["cue_id"])
    }

    @Test fun sourceAllThreeNavigatorCallersPassTheirSameLocalResultToTheGeneratedRecord() {
        val source = source()
        val manual = MainActivityRefactorSource.function(source, "private fun speakNextNavigationInstruction(")
        assertTrue(manual.contains("val compass = route.currentRouteCompassHeadingResult(nowMs)"))
        assertTrue(manual.contains("routeRun().navigator.currentGuidance(nowMs, compass?.observation, compass?.reason)"))
        assertTrue(manual.contains("route.tryDispatchRouteInstruction(update, expectedWalkEpoch, compass)"))
        val voiceWiring = MainActivityRefactorSource.member(source, "createVoiceInteractionControllers")
        assertTrue(voiceWiring.contains("route = VoiceRoutePeerAdapter("))
        assertTrue(voiceWiring.contains("guidance = { routeGuidanceController }, compass = { routeCompassSession }"))
        val voicePeer = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        assertTrue(MainActivityRefactorSource.member(voicePeer, "currentRouteCompassHeadingResult").contains("compass().currentRouteCompassHeadingResult(nowMs)"))
        assertTrue(MainActivityRefactorSource.member(voicePeer, "tryDispatchRouteInstruction").contains("guidance().tryDispatchRouteInstruction(update, epoch, compass)"))
        val automatic = NavigationPolicySource.guidance(source)
        assertTrue(ReportStaticSourceInspector.appearsInOrder(automatic,
            "val compass = compassInput.heading(queryAtMs)",
            "val nowMs = maxOf(queryAtMs, elapsedRealtimeMs())",
            "routeNavigator.update("))
        assertTrue(automatic.contains("val facing = compass?.observation"))
        assertTrue(automatic.contains("facingObservation = facing,"))
        assertTrue(automatic.contains("facingUnavailableReason = compass?.reason,"))
        assertTrue(automatic.contains("tryDispatchRouteInstruction(update, expectedWalkEpoch, compass)"))
        val retry = MainActivityRefactorSource.function(source, "private fun retryCurrentNavigationSpeech(")
        assertTrue(retry.contains("val compass = compassInput.heading(nowMs)"))
        assertTrue(retry.contains("retryAdmittedNavigationSpeech(nowMs, epoch, compass)"))
        val route = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt").readText()
        val admitted = MainActivityRefactorSource.member(route, "retryAdmittedNavigationSpeech", 1)
        assertTrue(admitted.contains("routeNavigator.retryGuidance("))
        assertTrue(admitted.contains("facingObservation = compass?.observation,"))
        assertTrue(admitted.contains("facingUnavailableReason = compass?.reason,"))
        assertTrue(MainActivityRefactorSource.member(route, "retryAdmittedNavigationSpeech").contains("= ::tryDispatchRouteInstruction"))
        assertTrue(admitted.contains("dispatch(update, epoch, compass)"))
        assertTrue(MainActivityRefactorSource.composition(source, "routeGuidanceController").contains("override fun heading(nowMs: Long) = compass.value.heading(nowMs)"))
        assertTrue(MainActivityRefactorSource.composition(source, "routeCompassSession").contains("RouteCompassSession(run,"))
        val compass = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteCompassSession.kt").readText()
        assertTrue(MainActivityRefactorSource.member(compass, "heading").contains("currentRouteCompassHeadingResult(nowMs)"))
    }

    @Test fun sourceRealDispatchRetainsReservationLoggerGuardAndFreshStartValidation() {
        val source = source()
        val dispatch = MainActivityRefactorSource.function(source, "private fun tryDispatchRouteInstruction(")
        val reserve = dispatch.indexOf("if (!routeNavigator.reserveInstruction(update)) return")
        val generated = dispatch.indexOf("routeGuidanceGeneratedValues(update, elapsedRealtimeMs(), compass)")
        assertTrue(reserve >= 0 && generated > reserve)
        assertTrue(generated < dispatch.indexOf("val accepted = dispatchRouteInstructionSpeech("))
        assertTrue(dispatch.contains("if (diagnostics.recording) diagnostics.record("))
        assertTrue(dispatch.contains("if (update.cancelStaleNavigationSpeech) speech.cancelNavigation()"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(dispatch,
            "val facing = if (compassOwned) compassInput.facing(queryAtMs) else null",
            "val nowMs = maxOf(queryAtMs, elapsedRealtimeMs())",
            "routeNavigator.isInstructionCurrent(update, nowMs, facing)"))
        assertTrue(dispatch.contains("routeNavigator.prepareRetainedDirectionStart(update, nowMs, facing)"))
        assertTrue(dispatch.contains("speech.validatingStarted && startRecorded.compareAndSet(false, true)"))
        val routeWiring = MainActivityRefactorSource.composition(source, "routeGuidanceController")
        assertTrue(routeWiring.contains("val speech = guides.speech()"))
        assertTrue(routeWiring.contains("diagnostics = diagnostics.value"))
        val assembly = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val speechPort = ReportStaticSourceInspector.blockAfter(
            assembly.substringAfter("guides = GuidanceAssemblyBindings("), "speech =")
        assertTrue(speechPort.contains("GuidanceSpeechPeerAdapter("))
        assertTrue(speechPort.contains("output = { speechOutputController }"))
        val adapter = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/GuidanceSpeechPeerAdapter.kt").readText()
        assertTrue(adapter.contains("override val validatingStarted get() = output().currentActuatorOrNull()?.validatingStartedSpeech == true"))
        assertTrue(adapter.contains("override fun cancelNavigation() { output().currentActuatorOrNull()?.cancelNavigationSpeech() }"))
        assertTrue(MainActivityRefactorSource.property(source, "routeGuidanceDiagnostics").contains("by navigationSessions.diagnostics"))
        val compositionSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/NavigationSessionComposition.kt").readText()
        assertTrue(MainActivityRefactorSource.property(compositionSource, "diagnostics").contains("resources.diagnostics()"))
        val diagnostics = ReportStaticSourceInspector.blockAfter(
            assembly.substringAfter("resources = NavigationAssemblyResources("), "diagnostics =")
        assertTrue(diagnostics.contains("override val recording get() = ::fieldSessionLog.isInitialized"))
        assertTrue(diagnostics.contains("fieldSessionLog.recordEvent(event, values)"))
        val compass = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteCompassSession.kt").readText()
        val lookup = MainActivityRefactorSource.member(compass, "currentRouteCompassHeadingResult")
        assertTrue(lookup.indexOf("source.headingAllowed(") in 0 until lookup.indexOf("val facing = tracker.heading(nowMs)"))
        assertTrue(lookup.contains("return facing"))
        val compassWiring = MainActivityRefactorSource.composition(source, "routeCompassSession")
        assertTrue(compassWiring.contains("RouteCompassSession("))
        assertTrue(compassWiring.contains("activity.currentCollectionOwner() == positioning.value.collectionOwner"))
        val compassBindings = assembly.substringAfter("bearings = CompassAssemblyBindings(")
        val compassAuthority = ReportStaticSourceInspector.blockAfter(compassBindings, "authority =")
        assertTrue(compassAuthority.contains("override fun currentCollectionOwner() = currentLocationCollectionOwnerOrNull()"))
        assertTrue(compassBindings.contains("tracker = { createNavigationAndroidSources() }"))
        val androidWire = MainActivityRefactorSource.factory(source, "createNavigationAndroidSources")
        assertTrue(androidWire.contains("orientation = { earthOrientationTracker }"))
        val androidSources = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt").readText()
        assertTrue(MainActivityRefactorSource.member(androidSources, "heading")
            .contains("orientation().latestRouteCompassHeading(nowMs)"))
        assertTrue(MainActivityRefactorSource.member(source, "routeGuidanceGeneratedValues")
            .contains("routeGuidanceController.routeGuidanceGeneratedValues(update, nowMs, compass)"))
        MainActivityRefactorSource.composition(source, "routeGuidanceController")
        val guidanceOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(guidanceOwner, "routeGuidanceGeneratedValues")
            .contains("RouteGuidanceDiagnostics.guidanceGenerated(update, nowMs, compass)"))
    }

    private fun compass(magneticY: Double = -40.0, magneticZ: Double = -30.0, nowMs: Long = 1_000L) =
        RouteCompassHeading.evaluateFallback(
            gravity = RouteCompassVectorSample(0.0, 9.80665, 0.0, nowMs, EarthOrientationAccuracy.HIGH),
            accelerometer = null,
            magnetic = RouteCompassVectorSample(0.0, magneticY, magneticZ, nowMs, EarthOrientationAccuracy.HIGH),
            reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0), nowMs = nowMs,
        )
    private fun navigator() = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
            listOf(point(0.0), point(400.0)),
            listOf(WalkingRouteGuidePoint(0, point(200.0), "안내", 200, 200, turnType = 13))))
    }
    private fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)
    private fun update(navigator: RouteNavigator, compass: RouteCompassHeadingResult?) =
        point(50.0).let { navigator.update(TrustedLocation(it.latitude, it.longitude, 2f, 1_000L), 1_000L,
            false, facingObservation = compass?.observation, facingUnavailableReason = compass?.reason) }
    private fun cueId(update: RouteNavigatorUpdate) = requireNotNull(update.speechCueToken).let {
        "${it.routeRevision}:${it.cueRevision}"
    }
    private fun source() = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private fun method(source: String, name: String) = source.substringAfter("private fun $name(")
        .let { it.substringBefore("\n    private fun ") }
}
