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
        val lookup = method("currentRouteFacingObservation")
        assertTrue(lookup.contains(
            "fieldSessionLog.recordEvent(\"route_compass_state_changed\", RouteGuidanceDiagnostics.compassState(facing, nowMs))"))
        val alignment = method("recordRouteAlignmentDiagnostic")
        assertTrue(alignment.contains("val values = RouteGuidanceDiagnostics.alignment(alignment, facing, positionUncertaintyM, nowMs)"))
        assertTrue(alignment.contains("fieldSessionLog.recordEvent(\"route_alignment\", values)"))
    }

    @Test
    fun generationIsRecordedAfterReservationAndTheStartCheckAtPlaybackStart() {
        val dispatch = method("tryDispatchRouteInstruction")
        val reserved = dispatch.indexOf("if (!routeNavigator.reserveInstruction(update)) return")
        val generated = dispatch.indexOf("\"route_guidance_generated\", RouteGuidanceDiagnostics.guidanceGenerated(update, SystemClock.elapsedRealtime())")
        assertTrue(reserved >= 0 && generated > reserved)
        assertTrue(generated < dispatch.indexOf("val accepted = dispatchNavigationSpeech("))
        val validator = dispatch.substringAfter("isInstructionStillCurrent = {").substringBefore("onCompleted = {")
        val current = validator.indexOf("routeNavigator.isInstructionCurrent(update, nowMs, currentRouteFacingObservation(nowMs)).also { current ->")
        assertTrue(current >= 0)
        assertTrue(validator.indexOf("\"route_guidance_started\", RouteGuidanceDiagnostics.guidanceStartChecked(update, nowMs, current)") > current)
        // Neither event carries the spoken sentence.
        val events = dispatch.substringBefore("onCompleted = {")
        assertFalse(Regex("""recordEvent\([^)]*instruction""").containsMatchIn(events))
    }

    /**
     * S08-34 (nav-F5/F6): the validator also runs for submission checks, so one cue has one start
     * record, written only at the engine's actual start check; before submission the only
     * synchronous field-log write is the generation record.
     */
    @Test
    fun oneCueHasOneStartRecordAtTheActualStartAndOneRecordBeforeSubmission() {
        val dispatch = method("tryDispatchRouteInstruction")
        assertEquals(1, Regex("\"route_guidance_started\"").findAll(dispatch).count())
        val validator = dispatch.substringAfter("isInstructionStillCurrent = {").substringBefore("onCompleted = {")
        val gate = validator.indexOf(
            "if (feedbackActuator?.validatingStartedSpeech == true && startRecorded.compareAndSet(false, true) &&")
        assertTrue(gate >= 0 && gate < validator.indexOf("\"route_guidance_started\""))
        assertTrue(dispatch.indexOf("val startRecorded = AtomicBoolean(false)") in 0 until dispatch.indexOf("isInstructionStillCurrent = {"))
        val beforeSubmission = dispatch.substringBefore("val accepted = dispatchNavigationSpeech(")
        assertEquals(1, Regex("recordEvent\\(").findAll(beforeSubmission).count())
    }
}
