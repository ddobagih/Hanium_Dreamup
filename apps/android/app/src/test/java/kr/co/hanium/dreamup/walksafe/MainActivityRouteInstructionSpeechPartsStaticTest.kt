package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ACTION/INFO callback wiring; this source check does not run TTS or TalkBack. */
class MainActivityRouteInstructionSpeechPartsStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun actionReservationStartDiagnosticCompletionAckAndFailureStayAtTheirExistingBoundaries() {
        val routeSource = MainActivityRefactorSource.function(source, "private fun tryDispatchRouteInstruction(")
        assertTrue(MainActivityRefactorSource.composition(source, "routeGuidanceController").contains("elapsedRealtimeMs = elapsedRealtimeMs"))
        assertTrue(MainActivityRefactorSource.factory(source, "createNavigationSessions").contains("elapsedRealtimeMs = SystemClock::elapsedRealtime"))
        val route = compact(routeSource)
        assertBefore(route, "if(!routeNavigator.reserveInstruction(update))return", "valaccepted=dispatchRouteInstructionSpeech(")
        assertBefore(route, "\"route_guidance_generated\"", "valaccepted=dispatchRouteInstructionSpeech(")
        assertTrue(route.contains("dispatchRouteInstructionSpeech(update=update,"))
        val actionCurrent = compact(braceBlock(routeSource, "isActionCurrent = {"))
        assertBefore(actionCurrent, "compassInput.facing(queryAtMs)", "valnowMs=maxOf(queryAtMs,elapsedRealtimeMs())")
        assertBefore(actionCurrent, "valnowMs=maxOf(queryAtMs,elapsedRealtimeMs())", "routeNavigator.isInstructionCurrent(update,nowMs,facing)")
        assertBefore(actionCurrent, "routeNavigator.isInstructionCurrent(update,nowMs,facing)", "\"route_guidance_started\"")
        assertBefore(actionCurrent, "speech.validatingStarted&&startRecorded.compareAndSet(false,true)", "\"route_guidance_started\"")
        val completed = compact(braceBlock(routeSource, "onActionCompleted = {"))
        assertBefore(completed, "isRouteSpeechCurrent(expectedWalkEpoch,requestGeneration,lifecycleGeneration)", "routeNavigator.acknowledgeInstruction(update,elapsedRealtimeMs())")
        assertBefore(completed, "routeNavigator.acknowledgeInstruction(update,elapsedRealtimeMs())", "scheduleNavigationSpeechRetry()")
        assertTrue(completed.contains("effects.showLastResult(instruction)"))
        val failed = compact(braceBlock(routeSource, "onActionFailed = {"))
        assertBefore(failed, "routeNavigator.releaseInstruction(update,elapsedRealtimeMs())", "scheduleNavigationSpeechRetry()")
        assertEquals(1, Regex(Regex.escape("routeNavigator.acknowledgeInstruction(")).findAll(route).count())
    }

    @Test
    fun optionalInformationUsesCurrentOwnersRiskAndCueAndFinishesOnlyItsOwnReservation() {
        val route = MainActivityRefactorSource.function(source, "private fun tryDispatchRouteInstruction(")
        val current = compact(braceBlock(route, "isInformationCurrent = {"))
        assertTrue(current.contains("valnowMs=elapsedRealtimeMs()"))
        assertTrue(current.contains("isRouteSpeechCurrent(expectedWalkEpoch,requestGeneration,lifecycleGeneration)&&speech.canSpeakNavigation(nowMs)&&routeNavigator.isInformationCurrent(information,nowMs)"))
        val reserve = compact(braceBlock(route, "reserveInformation = {"))
        assertTrue(reserve.contains("routeNavigator.reserveInformation(information,elapsedRealtimeMs())"))
        val finish = compact(braceBlock(route, "finishInformation = {"))
        assertTrue(finish.contains("routeNavigator.finishInformation(information)"))
        assertFalse(finish.contains("acknowledgeInstruction"))
        assertFalse(finish.contains("scheduleNavigationSpeechRetry"))
        val speak = compact(braceBlock(route, "speak = {"))
        assertTrue(speak.contains("dispatchRouteActionWithRecovery(message,recovery.takeIf{message==instruction},validator,completed,failed,"))
        assertTrue(speak.contains("speech.submitNavigation(message=text,isInstructionStillCurrent=current,onCompleted=done,onFailed=failure,"))
        assertTrue(MainActivityRefactorSource.composition(source, "routeGuidanceController").contains("val speech = guides.speech()"))
        val assembly = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val speechPort = ReportStaticSourceInspector.blockAfter(
            assembly.substringAfter("guides = GuidanceAssemblyBindings("), "speech =")
        assertTrue(speechPort.contains("GuidanceSpeechPeerAdapter("))
        assertTrue(speechPort.contains("output = { speechOutputController }"))
        val adapter = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/GuidanceSpeechPeerAdapter.kt").readText()
        val submission = compact(MainActivityRefactorSource.member(adapter, "submitNavigation"))
        assertTrue(submission.contains("output().dispatchNavigationSpeech(message,onCompleted,onFailed,isInstructionStillCurrent,"))
        val transport = compact(MainActivityRefactorSource.function(source, "private fun dispatchNavigationSpeech("))
        assertTrue(transport.contains("requiresExplicitTerminalCallback=true"))
        assertTrue(transport.contains("isStillValidAtStart={outputCurrent()&&isInstructionStillCurrent()}"))
        assertTrue(transport.contains("if(dispatched)failOnce()"))
        assertTrue(transport.contains("cancelled.set(true)failOnce()"))
    }

    private fun method(name: String): String {
        val marker = "private fun $name("
        val start = source.indexOf(marker)
        assertTrue("missing Main consumer: $name", start >= 0)
        val next = source.indexOf("\n    private fun ", start + marker.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }

    private fun compact(text: String): String = text.replace(Regex("\\s+"), "")

    private fun assertBefore(text: String, first: String, second: String) {
        val firstIndex = text.indexOf(first)
        val secondIndex = text.indexOf(second)
        assertTrue("$first must precede $second", firstIndex >= 0 && secondIndex > firstIndex)
    }

    private fun braceBlock(text: String, marker: String): String {
        val start = text.indexOf(marker)
        assertTrue("missing Main/helper connection: $marker", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth += 1
                '}' -> if (--depth == 0) return text.substring(start, index + 1)
            }
        }
        error("unterminated Main/helper connection: $marker")
    }
}
