package kr.co.hanium.dreamup.walksafe.feedback.delivery

import android.speech.tts.TextToSpeech
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import org.junit.Assert.*
import org.junit.Test

/** Real delivery policy and actuator: haptic success cannot consume an undelivered caution sentence. */
class CautionSpeechContinuityRegressionTest {
    @Test fun cautionWaitsForAFreshObservationAndCannotBeConfirmedByVibrationWhileRouteSpeechPlays() {
        val f = RiskFixture()
        f.runtime.acceptsVibration = true
        f.runtime.ready()
        var routeDone = 0
        f.actor.speakNavigation("경로를 따라 이동하세요.", onCompleted = { routeDone++ })
        val route = f.runtime.spoken.single()
        f.runtime.start(route.id)
        val rejected = f.deliver(level = MessageLevel.CAUTION)
        assertFalse(f.policy.hasClaimedFeedbackDelivery(rejected))
        assertTrue(f.timers.isEmpty())
        assertEquals(1, f.runtime.spoken.size)
        assertFalse(f.events.contains("risk_feedback_emitted"))
        f.runtime.advanceBy(500L)
        f.runtime.done(route.id)
        assertEquals(1, routeDone)
        val fresh = f.deliver(level = MessageLevel.CAUTION)
        assertTrue(f.policy.hasClaimedFeedbackDelivery(fresh))
        assertEquals(2, f.runtime.spoken.size)
        val caution = f.runtime.spoken.last()
        f.runtime.start(caution.id)
        f.runtime.done(caution.id)
        assertEquals(1, f.events.count { it == "risk_feedback_emitted" })
        f.actor.close()
    }

    @Test fun urgentWarningAndStopStillPreemptRouteSpeech() {
        for (level in listOf(MessageLevel.WARNING, MessageLevel.STOP)) {
            val f = RiskFixture()
            f.runtime.ready()
            var cancelled = 0
            var routeDone = 0
            f.actor.speakNavigation("경로 안내", onCompleted = { routeDone++ },
                requiresExplicitTerminalCallback = true, onCancelled = { cancelled++ })
            val route = f.runtime.spoken.single()
            f.runtime.start(route.id)
            f.deliver(level = level)
            assertEquals(level.name, 1, cancelled)
            assertEquals(level.name, 2, f.runtime.spoken.size)
            val urgent = f.runtime.spoken.last()
            assertEquals(level.name, TextToSpeech.QUEUE_FLUSH, urgent.queueMode)
            f.runtime.start(urgent.id)
            f.runtime.done(route.id)
            assertEquals(level.name, 0, routeDone)
            assertFalse(f.events.contains("risk_feedback_emitted"))
            f.runtime.done(urgent.id)
            assertEquals(level.name, 1, f.events.count { it == "risk_feedback_emitted" })
            f.actor.close()
        }
    }
}
