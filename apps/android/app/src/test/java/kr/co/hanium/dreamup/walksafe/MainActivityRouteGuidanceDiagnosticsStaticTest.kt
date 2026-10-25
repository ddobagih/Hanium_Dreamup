package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-34: MainActivity records the route guidance diagnostic chain with the allowlisted builders only. */
class MainActivityRouteGuidanceDiagnosticsStaticTest {
    private val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private fun method(name: String) = main.substringAfter("private fun $name(").let { body ->
        body.substring(0, body.indexOf("\n    private fun ").takeIf { it >= 0 } ?: body.length)
    }

    @Test
    fun compassAndAlignmentEventsUseTheAllowlistedChainFields() {
        MainActivityRefactorSource.composition(main, "routeGuidanceDiagnostics")
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteCompassSession.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val lookup = method("currentRouteCompassHeadingResult")
        assertTrue(lookup.contains("diagnostics.record(\"route_compass_state_changed\", RouteGuidanceDiagnostics.compassState(facing, nowMs))"))
        val guidance = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt")
        val alignment = MainActivityRefactorSource.member(guidance, "recordRouteAlignmentDiagnostic")
        assertTrue(alignment.contains("val values = RouteGuidanceDiagnostics.alignment(alignment, facing, positionUncertaintyM, nowMs)"))
        assertTrue(alignment.contains("diagnostics.record(\"route_alignment\", values)"))
        val diagnostics = MainActivityRefactorSource.factory(main, "createNavigationSessions")
        assertTrue(diagnostics.contains("fieldSessionLog.recordEvent(event, values)"))
        assertTrue(MainActivityRefactorSource.composition(main, "routeCompassSession").contains("diagnostics = diagnostics.value"))
        assertTrue(MainActivityRefactorSource.composition(main, "routeGuidanceController").contains("diagnostics = diagnostics.value"))
    }

    @Test
    fun generationIsRecordedAfterReservationAndTheStartCheckAtPlaybackStart() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val dispatch = method("tryDispatchRouteInstruction")
        val reserved = dispatch.indexOf("if (!routeNavigator.reserveInstruction(update)) return")
        val generated = dispatch.indexOf("\"route_guidance_generated\", routeGuidanceGeneratedValues(update, elapsedRealtimeMs(), compass)")
        assertTrue(method("routeGuidanceGeneratedValues").contains("RouteGuidanceDiagnostics.guidanceGenerated(update, nowMs, compass)"))
        assertTrue(reserved >= 0 && generated > reserved)
        assertTrue(generated < dispatch.indexOf("val accepted = dispatchRouteInstructionSpeech("))
        val validator = dispatch.substringAfter("isActionCurrent = {").substringBefore("isInformationCurrent = {")
        val observation = validator.indexOf("val facing = if (compassOwned) compassInput.facing(queryAtMs) else null")
        val current = validator.indexOf("routeNavigator.isInstructionCurrent(update, nowMs, facing)")
        val validationClock = validator.indexOf("val nowMs = maxOf(queryAtMs, elapsedRealtimeMs())")
        assertTrue(observation >= 0 && validationClock > observation && current > validationClock)
        assertTrue(validator.contains("routeNavigator.prepareRetainedDirectionStart(update, nowMs, facing)"))
        assertTrue(current >= 0)
        assertTrue(validator.indexOf("\"route_guidance_started\", RouteGuidanceDiagnostics.guidanceStartChecked(update, nowMs, current)") > current)
        assertTrue(dispatch.indexOf("val compassRevision = compassInput.revision") in 0 until reserved)
        assertTrue(validator.contains("!compassInput.invalidationPending && compassInput.revision == compassRevision"))
        val events = dispatch.substringBefore("onActionCompleted = {")
        assertFalse(Regex("""diagnostics\.record\([^)]*instruction""").containsMatchIn(events))
        val wire = MainActivityRefactorSource.composition(main, "routeGuidanceController")
        assertTrue(wire.contains("override val revision get() = compass.value.revision"))
        assertTrue(wire.contains("override fun facing(nowMs: Long) = compass.value.facing(nowMs)"))
        assertTrue(MainActivityRefactorSource.member(main, "tryDispatchRouteInstruction").contains("routeGuidanceController.tryDispatchRouteInstruction(update, expectedWalkEpoch, compass)"))
    }

    /**
     * S08-34 (nav-F5/F6): the validator also runs for submission checks, so one cue has one start
     * record, written only at the engine's actual start check; before submission the only
     * synchronous field-log write is the generation record.
     */
    @Test
    fun oneCueHasOneStartRecordAtTheActualStartAndOneRecordBeforeSubmission() {
        MainActivityRefactorSource.composition(main, "routeGuidanceController")
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val dispatch = method("tryDispatchRouteInstruction")
        assertEquals(1, Regex("\"route_guidance_started\"").findAll(dispatch).count())
        val validator = dispatch.substringAfter("isActionCurrent = {").substringBefore("isInformationCurrent = {")
        val gate = validator.indexOf("if (speech.validatingStarted && startRecorded.compareAndSet(false, true) &&")
        assertTrue(gate >= 0 && gate < validator.indexOf("\"route_guidance_started\""))
        assertTrue(dispatch.indexOf("val startRecorded = AtomicBoolean(false)") in 0 until dispatch.indexOf("isActionCurrent = {"))
        val beforeSubmission = dispatch.substringBefore("val accepted = dispatchRouteInstructionSpeech(")
        assertEquals(1, Regex("diagnostics\\.record\\(").findAll(beforeSubmission).count())
        val binding = MainActivityRefactorSource.factory(main, "createNavigationSessions")
        assertTrue(binding.contains("GuidanceSpeechPeerAdapter("))
        assertTrue(binding.contains("output = { speechOutputController }"))
        val adapter = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/GuidanceSpeechPeerAdapter.kt")
        assertTrue(adapter.contains("override val validatingStarted get() = output().currentActuatorOrNull()?.validatingStartedSpeech == true"))
    }
}
