package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S12-21 original-producer/immutable-evaluation diagnostic wiring; no physical-output claim. */
class MainActivityFeedbackRejectionDiagnosticsStaticTest {
    private val source = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun originalProducerGuardsTheFrameReasonsBeforeActionAndOnlyReasonAndTimeAreLogged() {
        val emit = compact(delegatedOwner("emitTactileFrameFeedback", "riskFeedbackComponents.delivery", "feedback/delivery/RiskFeedbackDeliveryController.kt", "emitTactileFrameFeedback", "= this@MainActivity.riskFeedbackComponents.delivery.emitTactileFrameFeedback(dispatch, producerScope, startIsCurrent, producerIsCurrent)"))
        val event = "\"feedback_output_rejected\""
        assertTrue("the dispatch's rejection snapshot must reach the diagnostic consumer", emit.contains(event))
        assertBefore(emit, "admission.lifecycleCurrent(feedbackGeneration)", event)
        assertBefore(emit, "producerIsCurrent?.invoke()", event)
        assertBefore(emit, "producerIsCurrent?.invoke()", "latestFeedbackDeliveryState=FeedbackDeliveryState(")
        assertBefore(emit, "admission.lifecycleCurrent(feedbackGeneration)", "latestFeedbackDeliveryState=FeedbackDeliveryState(")
        assertBefore(emit, "outputEvaluation.evaluatedAtMs==dispatch.policyEvaluatedAtMs", event)
        assertBefore(emit, "outputEvaluation.deviceGateAllowsAlerts==dispatch.deviceGateAllowsAlerts", event)
        assertBefore(emit, event, "reconcileTalkBackRiskTracks(activeRiskTrackIds)")
        assertBefore(emit, event, "feedbackAction?.let{emitFeedbackAction(it,elapsedRealtimeMs,startIsCurrent)}")
        assertTrue(emit.contains("dispatch.outputEvaluation"))
        assertTrue(emit.contains("outputEvaluation.rejectionsByTrack.values.flatten()"))
        assertTrue(emit.contains("listOfNotNull(outputEvaluation.frameRejection)"))
        assertTrue(emit.contains(".distinct()"))
        assertFalse(emit.contains("lastOutputEvaluation()"))
        assertFalse(emit.contains("action.level"))
        assertFalse(emit.contains("isFeedbackActionStillDeliverable"))

        val recordStart = emit.indexOf("log.recordEvent(")
        assertTrue(recordStart >= 0)
        val record = balancedBlock(recordStart, '(', ')', emit)
        val keys = Regex("\"([a-z_]+)\"to").findAll(record).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("reason", "elapsed_realtime_ms"), keys)
        assertTrue(record.contains("\"reason\"toreason.name"))
        assertTrue(record.contains("\"elapsed_realtime_ms\"tooutputEvaluation.evaluatedAtMs"))

        val factory = compact(MainActivityRefactorSource.member(source, "createRiskFeedbackComponents"))
        assertTrue(factory.contains("overridefunlifecycleCurrent(generation:Int)=isFeedbackLifecycleCurrent(generation)"))
        assertTrue(factory.contains("overridefunrecordEvent(name:String,fields:Map<String,Any?>)=fieldSessionLog.recordEvent(name,fields)"))
        val composition = compact(MainActivityRefactorSource.property(source, "framePipelineController"))
        assertTrue(MainActivityRefactorSource.member(source, "onDrawFrame").contains("framePipelineController.draw()"))
        assertTrue(composition.contains("valtactile=frameTactileResults"))
        assertTrue(composition.contains("valrisk=frameRiskOutputs"))
        assertTrue(composition.contains("FramePublicationController(frameStateLock,runtime::ownsFrame,runtime::metricOutputAllowsWork,"))
        assertTrue(composition.contains("runtime::visualOutputAllowsWork,tactile,risk,recording,report,{framePresentation},clock)"))
        assertTrue(composition.contains("tactile,risk,recording,{framePresentation},publication,"))
        val pipeline = ownerSource("runtime/vision/frame/FramePipelineController.kt")
        val draw = MainActivityRefactorSource.member(pipeline, "draw")
        assertBefore(draw, "synthesize(qualified)", "publication.publish(outputs)")
        val synthesis = compact(MainActivityRefactorSource.member(pipeline, "synthesize"))
        assertTrue(synthesis.contains("valsourceCurrent={tactile.resultIsCurrent(token)&&runtime.ownsFrame(generation,epoch)&&runtime.leaseIsCurrent(lease)}"))
        assertTrue(synthesis.contains("result,token,sourceLease.owner,sourceCurrent,frozen,"))
        assertTrue(composition.contains("overridefunownsFrame(generation:Int,epoch:WalkRuntimeEpoch)=isCurrentFrameGeneration(generation,epoch)"))
        assertTrue(composition.contains("overridefunleaseIsCurrent(lease:ArSessionLease)=isArSessionLeaseCurrent(lease)"))
        val publication = ownerSource("runtime/vision/frame/FramePublicationController.kt")
        for (name in listOf("publish", "publishQualitative")) {
            val branch = compact(MainActivityRefactorSource.member(publication, name))
            assertTrue(branch.contains("FeedbackRejectionProducerScope(scope.walkEpoch,scope.frameGeneration,scope.arGeneration)"))
            assertTrue(branch.contains("tactile.dispatchFeedback(input.receipt,"))
            assertTrue(branch.contains("TactileFrameFeedbackActuator{dispatch->risk.emitFeedback(dispatch,producer,input.sourceIsCurrent,input.sourceIsCurrent)}"))
        }
        val tactile = compact(MainActivityRefactorSource.property(source, "frameTactileResults"))
        assertTrue(tactile.contains("dispatchConsumedTactileFrameFeedback(receipt,frame,stale,deviceGateAllowsAlerts,nowMs,actuator,"))
        val risk = compact(MainActivityRefactorSource.property(source, "frameRiskOutputs"))
        assertTrue(risk.contains("emitTactileFrameFeedback(dispatch,scope,startIsCurrent,producerIsCurrent)"))
    }

    @Test
    fun diagnosticConsumerUsesOnlyTheCachedSessionAndRecordsChangedScopeOrReasons() {
        val emit = compact(delegatedOwner("emitTactileFrameFeedback", "riskFeedbackComponents.delivery", "feedback/delivery/RiskFeedbackDeliveryController.kt", "emitTactileFrameFeedback", "= this@MainActivity.riskFeedbackComponents.delivery.emitTactileFrameFeedback(dispatch, producerScope, startIsCurrent, producerIsCurrent)"))
        assertFalse("a frame must not query the log's disk-backed active state", emit.contains(".activeSessionId()"))
        assertFalse("a frame must not query the log's disk-backed active state", emit.contains(".isActive()"))
        assertTrue(emit.contains("feedbackRejectionFieldSessionId!=null"))
        val record = "log.recordEvent("
        listOf(
            "lastFeedbackRejectionProducerScope!=producerScope",
            "lastFeedbackRejectionLifecycleGeneration!=feedbackGeneration",
            "lastFeedbackRejectionReasons!=reasons",
        ).forEach { assertBefore(emit, it, record) }
        assertBefore(emit, "feedbackRejectionFieldSessionId!=null", "lastFeedbackRejectionReasons=reasons")
    }

    @Test
    fun realLogStartStopAndSessionChangeResetTheReasonDedupe() {
        val toggle = compact(method("toggleFieldSessionLog"))
        assertTrue(toggle.contains("fieldSessionLog.stop()this@MainActivity.riskFeedbackComponents.delivery.clearRejectionFieldSession()resetFeedbackOutputRejectionDiagnostics()"))
        assertTrue(toggle.contains("riskFeedbackComponents.delivery.setRejectionFieldSession(fieldSessionLog.startAfterUserConfirmation())resetFeedbackOutputRejectionDiagnostics()"))
        assertTrue(compact(method("clearRejectionFieldSession")).contains("feedbackRejectionFieldSessionId=null"))
        assertTrue(compact(method("setRejectionFieldSession")).contains("feedbackRejectionFieldSessionId=sessionId"))
        assertTrue(compact(method("rejectionFieldSessionId")).contains("=feedbackRejectionFieldSessionId"))
        val button = compact(method("updateFieldSessionLogButton"))
        assertEquals(1, Regex(Regex.escape("fieldSessionLog.activeSessionId()")).findAll(button).count())
        assertFalse(button.contains("fieldSessionLog.isActive()"))
        assertTrue(button.contains("if(this@MainActivity.riskFeedbackComponents.delivery.rejectionFieldSessionId()!=sessionId){this@MainActivity.riskFeedbackComponents.delivery.setRejectionFieldSession(sessionId)resetFeedbackOutputRejectionDiagnostics()"))
        val reset = compact(delegatedOwner("resetFeedbackOutputRejectionDiagnostics", "riskFeedbackComponents.delivery", "feedback/delivery/RiskFeedbackDeliveryController.kt", "resetFeedbackOutputRejectionDiagnostics", "= this@MainActivity.riskFeedbackComponents.delivery.resetFeedbackOutputRejectionDiagnostics()"))
        assertTrue(reset.contains("lastFeedbackRejectionProducerScope=null"))
        assertTrue(reset.contains("lastFeedbackRejectionLifecycleGeneration=null"))
        assertTrue(reset.contains("lastFeedbackRejectionReasons=emptySet()"))
    }

    private fun method(name: String): String =
        MainActivityRefactorSource.function(source, "private fun $name(")

    private fun ownerSource(path: String): String =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText()

    private fun assertBefore(text: String, first: String, second: String) {
        val firstIndex = text.indexOf(first)
        val secondIndex = text.indexOf(second)
        assertTrue("$first must precede $second", firstIndex >= 0 && secondIndex > firstIndex)
    }

    private fun compact(text: String): String = text.replace(Regex("\\s+"), "")

    private fun balancedBlock(start: Int, opening: Char, closing: Char, text: String = source): String {
        val open = text.indexOf(opening, start)
        assertTrue(open >= start)
        var depth = 0
        for (index in open until text.length) {
            if (text[index] == opening) depth += 1
            if (text[index] == closing && --depth == 0) return text.substring(start, index + 1)
        }
        error("unterminated source block at $start")
    }

    // Structural wiring and owner policy are checked separately; this does not execute Main.
    private fun delegatedOwner(name: String, owner: String, path: String, target: String, expectedBody: String): String {
        val caller = MainActivityRefactorSource.factory(source, name)
        // Match the whole caller, including additional effects; a guarded call or lambda is different.
        val declaration = caller.filterNot(Char::isWhitespace)
        val body = Regex.escape(expectedBody.filterNot(Char::isWhitespace))
        val expected = Regex("""^(?:private|internal|public|override)*fun$name\([^{}]*\)(?::[\w?<>]+)?$body$""")
        assertTrue("Main caller changed for $owner.$target: $caller", expected.matches(declaration))
        assertTrue(expectedBody.contains("$owner.$target("))
        MainActivityRefactorSource.composition(source, owner)
        return MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), target,
        )
    }
}
