package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUpdate
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision
import org.junit.Assert.*
import org.junit.Test

/** Real decision creation and controller entry; speech terminals and output generation are controlled. */
class RouteDecisionOwnerRecoveryRegressionTest {
    @Test fun aFailedOldOutputOwnerCannotSuppressTheSameRerouteQuestionAfterRecovery() {
        val f = GuidanceFixture().apply { captureStatus = true; install() }
        val question = f.openRerouteQuestion()
        val token = requireNotNull(f.nav.pendingDecisionToken())
        assertTrue(f.controller.applyRouteDeviationSafetyUpdate(question))
        val old = f.statusSent.single()
        assertTrue(old.current())

        // Main's GPS restriction advances feedback generation before closing the speech actuator.
        f.feedbackGeneration++
        old.failed()
        assertFalse(old.current())
        assertTrue(f.pending.isEmpty())
        assertEquals(token, f.nav.pendingDecisionToken())

        assertTrue(f.controller.applyRouteDeviationSafetyUpdate(question))
        assertEquals("The recovered owner must actually submit the unanswered question", 2, f.statusSent.size)
        val recovered = f.statusSent.last()
        assertTrue(recovered.current())
        old.done()
        old.failed()
        assertTrue("Retired callbacks cannot consume the replacement question", recovered.current())
        recovered.done()
        f.controller.applyRouteDeviationSafetyUpdate(question)
        assertEquals("A real DONE suppresses repetition for this same decision", 2, f.statusSent.size)
    }

    @Test fun anOldScheduledRetryCannotLeaveTheSameDecisionPermanentlyReserved() {
        val f = GuidanceFixture().apply { captureStatus = true; install() }
        val question = f.openRerouteQuestion()
        val token = requireNotNull(f.nav.pendingDecisionToken())
        f.controller.applyRouteDeviationSafetyUpdate(question)
        val old = f.statusSent.single()
        old.failed()
        val retry = f.pending.single()

        f.feedbackGeneration++
        f.pending.remove(retry)
        f.now = retry.first
        retry.second.run()
        assertEquals(1, f.statusSent.size)
        assertTrue(f.pending.isEmpty())
        assertEquals(token, f.nav.pendingDecisionToken())

        f.controller.applyRouteDeviationSafetyUpdate(question)
        assertEquals("The stale timer must retire its reservation, not the unanswered decision", 2, f.statusSent.size)
        assertTrue(f.statusSent.last().current())
        retry.second.run()
        old.done()
        old.failed()
        assertTrue(f.statusSent.last().current())
        assertEquals(2, f.statusSent.size)
    }

    @Test fun lateFailureAndDoneFromAReplacedRouteCannotConsumeItsNewQuestion() {
        val f = GuidanceFixture().apply { captureStatus = true; install() }
        f.controller.applyRouteDeviationSafetyUpdate(f.openRerouteQuestion())
        val old = f.statusSent.single()
        val oldToken = f.nav.pendingDecisionToken()

        f.install()
        val replacementQuestion = f.openRerouteQuestion()
        assertNotEquals(oldToken, f.nav.pendingDecisionToken())
        f.controller.applyRouteDeviationSafetyUpdate(replacementQuestion)
        val replacement = f.statusSent.last()
        assertEquals(2, f.statusSent.size)
        old.failed()
        old.done()
        assertFalse(old.current())
        assertTrue(replacement.current())
        assertTrue(f.pending.isEmpty())
        replacement.done()
        f.controller.applyRouteDeviationSafetyUpdate(replacementQuestion)
        assertEquals(2, f.statusSent.size)
    }

    @Test fun aRecoveredOwnerCanReclaimABusyQuestionBeforeItsOldRetryRuns() {
        val f = GuidanceFixture().apply { captureStatus = true; install() }
        val question = f.openRerouteQuestion()
        val token = requireNotNull(f.nav.pendingDecisionToken())
        val oldGeneration = f.feedbackGeneration
        assertFalse(f.controller.speakCurrentRouteDecision(requireNotNull(question.instruction), token,
            isScopeCurrent = { f.controller.isRouteSpeechCurrent(f.epoch, f.requestGeneration, oldGeneration) },
            outputBusy = { true }))
        val oldRetry = f.pending.single()
        assertTrue(f.statusSent.isEmpty())

        f.feedbackGeneration++
        f.controller.applyRouteDeviationSafetyUpdate(question)
        val recovered = f.statusSent.single()
        assertTrue(recovered.current())
        assertTrue("Reclaiming the question must remove the old timer", f.pending.isEmpty())
        f.now = oldRetry.first
        oldRetry.second.run()
        assertTrue(recovered.current())
        assertEquals(1, f.statusSent.size)
        recovered.done()
        f.controller.applyRouteDeviationSafetyUpdate(question)
        assertEquals(1, f.statusSent.size)
    }

    @Test fun failedTemporaryAdmissionCannotReviveATerminatedQuestionReservation() {
        val f = GuidanceFixture().apply { captureStatus = true; install() }
        val question = f.openRerouteQuestion()
        f.controller.applyRouteDeviationSafetyUpdate(question)
        val old = f.statusSent.single()
        f.feedbackCurrent = false
        old.failed()
        f.feedbackCurrent = true

        f.controller.applyRouteDeviationSafetyUpdate(question)
        assertEquals("Recovery under the same generation still needs a live speech attempt", 2, f.statusSent.size)
        val recovered = f.statusSent.last()
        old.done()
        old.failed()
        assertTrue(recovered.current())
        assertTrue(f.pending.isEmpty())
    }

    private fun GuidanceFixture.openRerouteQuestion(): RouteNavigatorUpdate {
        val offRoute = fix.copy(latitude = fix.latitude + 100.0 / 111_195.0, elapsedRealtimeMs = now)
        nav.update(offRoute, now, false)
        now += 1_000L
        val question = nav.update(offRoute.copy(elapsedRealtimeMs = now), now, false)
        assertEquals(RouteNavigatorUserDecision.REROUTE, question.pendingUserDecision)
        assertNotNull(question.instruction)
        return question
    }
}
