package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.voice.output.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class SpeechOutputOwnershipAdditionalTest {
    @Test fun generalDisabledSpeechReportsFailureOnceWithoutSubmittingNativeSpeech() {
        val f = SpeechOutputControllerBehaviorTest.Fixture()
        f.homeAvailable = false; f.nativeSpeechAllowed = { false }
        f.controller.ensureFeedbackActuator(); f.runtime.ready()
        var done = 0; var failed = 0; var cancelled = 0
        assertFalse(f.controller.speakCommandResponse("보행을 종료할까요?", onCompleted = { done++ },
            onFailed = { failed++ }, onCancelled = { cancelled++ }))
        assertEquals(0, done); assertEquals(1, failed); assertEquals(0, cancelled)
        assertTrue(f.runtime.spoken.isEmpty()); assertFalse(f.controller.hasPendingCommandResponse())
    }

    @Test fun staleGeneralStartCancelsOnceWithoutReportingCompletionOrEngineFailure() {
        val f = SpeechOutputControllerBehaviorTest.Fixture(); f.homeAvailable = false
        f.controller.ensureFeedbackActuator(); f.runtime.ready()
        var done = 0; var failed = 0; var cancelled = 0
        assertTrue(f.controller.speakCommandResponse("보행을 종료할까요?", onCompleted = { done++ },
            onFailed = { failed++ }, onCancelled = { cancelled++ }))
        val id = f.runtime.spoken.single().id
        f.context = f.context.copy(walkEpoch = WalkRuntimeEpoch("replacement", 1))
        f.runtime.start(id); f.runtime.start(id); f.runtime.done(id)
        assertEquals(0, done); assertEquals(0, failed); assertEquals(1, cancelled)
        assertFalse(f.controller.hasPendingCommandResponse())
    }

    @Test fun closedGeneralSpeechCancelsOnceAndLateDoneCannotPublishSuccess() {
        val f = SpeechOutputControllerBehaviorTest.Fixture(); f.homeAvailable = false
        f.controller.ensureFeedbackActuator(); f.runtime.ready()
        var done = 0; var failed = 0; var cancelled = 0
        f.controller.speakCommandResponse("보행을 종료할까요?", onCompleted = { done++ },
            onFailed = { failed++ }, onCancelled = { cancelled++ })
        val id = f.runtime.spoken.single().id; f.runtime.start(id)
        f.controller.closeActuator(); f.runtime.done(id)
        assertEquals(0, done); assertEquals(0, failed); assertEquals(1, cancelled)
        assertFalse(f.controller.hasPendingCommandResponse())
    }

    @Test fun advisoryInvalidatedAfterAdmissionReportsFailureExactlyOnce() {
        val f = SpeechOutputControllerBehaviorTest.Fixture()
        f.controller.ensureFeedbackActuator(); f.runtime.ready()
        var checks = 0; var failed = 0; var done = 0
        assertFalse(f.controller.speakAdvisory("주변을 확인하세요.", f.clock.now + 5_000,
            { ++checks <= 2 }, { done++ }, { failed++ }))
        assertTrue(checks >= 3)
        assertEquals(0, done); assertEquals(1, failed)
        assertTrue(f.runtime.spoken.isEmpty())
    }
}
