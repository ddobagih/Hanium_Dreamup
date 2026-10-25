package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision
import org.junit.Assert.*
import org.junit.Test

/** Actual question owner and retry callbacks; command/native speech are controlled terminal ports. */
class RouteDecisionSpeechCoordinatorTest {
    @Test fun constructionDoesNotReadTheDecisionOrTouchSpeechAndScheduling() {
        val speech = object : RouteDecisionSpeechCoordinator.SpeechPort {
            override val commandGeneration: Long get() = error("construction read command generation")
            override val commandPending: Boolean get() = error("construction read pending command")
            override fun outputBusy(): Boolean = error("construction read output")
            override fun inputActive(): Boolean = error("construction read input")
            override fun cancelCommand(): Unit = error("construction cancelled command")
            override fun failCommand(generation: Long): Unit = error("construction failed command")
            override fun submitStatus(message: String, current: () -> Boolean, completed: () -> Unit,
                failed: (() -> Unit)?): Boolean = error("construction submitted speech")
        }
        val scheduler = object : RouteCallbackScheduler {
            override fun post(task: Runnable): Unit = error("construction posted callback")
            override fun postDelayed(task: Runnable, delayMs: Long): Unit = error("construction posted retry")
            override fun remove(task: Runnable): Unit = error("construction removed callback")
        }
        val coordinator = RouteDecisionSpeechCoordinator(speech, scheduler) { error("construction read decision") }
        assertNull(coordinator.state.pendingToken)
        assertNull(coordinator.state.announcedToken)
        assertNull(coordinator.state.responseGeneration)
        assertFalse(coordinator.state.retryPending)
    }

    @Test fun acceptanceAndStartValidationDoNotAnnounceAndOnlyDoneAllowsDuplicateSuppression() {
        val f = Fixture()
        assertTrue(f.speak())
        val question = f.speech.submissions.single()
        assertTrue(question.current())
        assertEquals(f.token, f.coordinator.state.pendingToken)
        assertNull(f.coordinator.state.announcedToken)
        assertTrue(f.speak())
        assertEquals(1, f.speech.submissions.size)

        f.speech.done(question)
        assertEquals(f.token, f.coordinator.state.announcedToken)
        assertNull(f.coordinator.state.pendingToken)
        assertNull(f.coordinator.state.responseGeneration)
        assertTrue(f.speak())
        assertEquals(1, f.speech.submissions.size)
        assertTrue(f.speak(repeatRequested = true))
        assertEquals(2, f.speech.submissions.size)
        assertNull(f.coordinator.state.announcedToken)
    }

    @Test fun pendingCommandInputAndOutputEachDeferForOneSecondWithoutLosingTheQuestion() {
        for (blocker in listOf("command", "input", "output")) {
            val f = Fixture()
            when (blocker) {
                "command" -> f.speech.commandPending = true
                "input" -> f.speech.input = true
                else -> f.speech.output = true
            }
            assertFalse(blocker, f.speak())
            assertEquals(f.token, f.coordinator.state.pendingToken)
            assertNull(f.coordinator.state.announcedToken)
            assertEquals(1_000L, f.scheduler.tasks.single().delayMs)
            assertTrue(f.speech.submissions.isEmpty())
            assertEquals(0, f.speech.cancellations)
            f.scheduler.runNext()
            assertEquals(1_000L, f.scheduler.tasks.single().delayMs)
            assertTrue(f.speech.submissions.isEmpty())
            f.speech.commandPending = false
            f.speech.input = false
            f.speech.output = false
            f.scheduler.runNext()
            assertEquals(1, f.speech.submissions.size)
            assertEquals(f.message, f.speech.submissions.single().message)
            assertFalse(f.coordinator.state.retryPending)
        }
    }

    @Test fun cancellationStopsOnlyItsOwnCommandAndLateTerminalsCannotRecreateIt() {
        for (newerCommand in listOf(false, true)) {
            val f = Fixture()
            assertTrue(f.speak())
            val old = f.speech.submissions.single()
            assertEquals(old.generation, f.coordinator.state.responseGeneration)
            if (newerCommand) f.speech.commandGeneration++
            f.coordinator.cancel()
            assertEquals(if (newerCommand) 0 else 1, f.speech.cancellations)
            assertEquals(newerCommand, f.speech.commandPending)
            assertFalse(old.current())
            old.done(); old.failed()
            assertNull(f.coordinator.state.pendingToken)
            assertNull(f.coordinator.state.announcedToken)
            assertNull(f.coordinator.state.responseGeneration)
            assertTrue(f.scheduler.tasks.isEmpty())
        }
    }

    @Test fun removedRetryCannotRunAgainAfterCancellationOrReplaceANewerQuestion() {
        val f = Fixture()
        f.speech.input = true
        assertFalse(f.speak())
        val removed = f.scheduler.tasks.single().runnable
        f.coordinator.cancel()
        assertEquals(listOf(removed), f.scheduler.removed)
        assertTrue(f.scheduler.tasks.isEmpty())
        f.speech.input = false
        assertTrue(f.speak())
        val current = f.speech.submissions.single()
        removed.run()
        assertEquals(1, f.speech.submissions.size)
        assertTrue(current.current())
        assertEquals(current.generation, f.coordinator.state.responseGeneration)
        assertEquals(0, f.speech.cancellations)
    }

    @Test fun callbackFailurePlusRejectedSubmissionSchedulesOneRetryAndIgnoresLateDone() {
        val f = Fixture()
        f.speech.onSubmit = { submission ->
            f.speech.fail(submission)
            submission.failed()
            false
        }
        assertFalse(f.speak())
        val old = f.speech.submissions.single()
        assertEquals(1, f.scheduler.tasks.size)
        assertNull(f.coordinator.state.responseGeneration)
        old.done()
        assertNull(f.coordinator.state.announcedToken)
        f.speech.onSubmit = { true }
        f.scheduler.runNext()
        val replacement = f.speech.submissions.last()
        assertNotSame(old, replacement)
        old.failed(); old.done()
        assertTrue(replacement.current())
        assertEquals(replacement.generation, f.coordinator.state.responseGeneration)
        assertNull(f.coordinator.state.announcedToken)
        assertTrue(f.scheduler.tasks.isEmpty())
        f.speech.done(replacement)
        assertEquals(f.token, f.coordinator.state.announcedToken)
    }

    @Test fun rejectedSubmissionWithoutCallbackStillRetriesOnceAndCannotLaterAnnounce() {
        val f = Fixture()
        f.speech.onSubmit = { false }
        assertFalse(f.speak())
        val rejected = f.speech.submissions.single()
        rejected.done(); rejected.failed()
        assertEquals(1, f.scheduler.tasks.size)
        assertNull(f.coordinator.state.announcedToken)
        assertNull(f.coordinator.state.responseGeneration)
    }

    @Test fun synchronousDoneDoesNotRetainACommandGenerationOrCancelCompletedSpeech() {
        val f = Fixture()
        f.speech.onSubmit = { submission -> f.speech.done(submission); true }
        assertTrue(f.speak())
        assertEquals(f.token, f.coordinator.state.announcedToken)
        assertNull(f.coordinator.state.pendingToken)
        assertNull(f.coordinator.state.responseGeneration)
        f.speech.submissions.single().failed()
        assertTrue(f.scheduler.tasks.isEmpty())
        f.coordinator.cancel()
        assertEquals(0, f.speech.cancellations)
    }

    @Test fun synchronousFailureDoesNotRetainTheFailedGenerationOrDuplicateTheRetry() {
        val f = Fixture()
        f.speech.onSubmit = { submission -> f.speech.fail(submission); true }
        assertTrue(f.speak())
        assertNull(f.coordinator.state.responseGeneration)
        assertNull(f.coordinator.state.announcedToken)
        assertEquals(1, f.scheduler.tasks.size)
        f.coordinator.cancel()
        assertEquals(0, f.speech.cancellations)
        assertTrue(f.scheduler.tasks.isEmpty())
    }

    @Test fun synchronousCancellationFailureCannotRetireAReentrantReplacement() {
        val f = Fixture()
        assertTrue(f.speak())
        val old = f.speech.submissions.single()
        f.speech.onCancel = {
            assertNull(f.coordinator.state.pendingToken)
            assertNull(f.coordinator.state.responseGeneration)
            old.failed()
            assertTrue(f.speak(message = "새 질문"))
            old.done()
        }
        f.coordinator.cancel()
        val replacement = f.speech.submissions.last()
        assertEquals("새 질문", replacement.message)
        assertTrue(replacement.current())
        assertEquals(replacement.generation, f.coordinator.state.responseGeneration)
        assertNull(f.coordinator.state.announcedToken)
        assertTrue(f.scheduler.tasks.isEmpty())
        assertEquals(1, f.speech.cancellations)
        f.speech.done(replacement)
        assertEquals(f.token, f.coordinator.state.announcedToken)
    }

    @Test fun replacingAStaleAttemptDoesNotOverwriteAQuestionCreatedDuringCancellation() {
        val f = Fixture()
        var oldScopeCurrent = true
        assertTrue(f.coordinator.speak(f.message, f.token, isScopeCurrent = { oldScopeCurrent }))
        val old = f.speech.submissions.single()
        oldScopeCurrent = false
        f.speech.onCancel = {
            old.failed()
            assertTrue(f.speak(message = "취소 중 도착한 질문"))
        }
        assertFalse(f.speak(message = "이전 호출의 질문"))
        assertEquals(2, f.speech.submissions.size)
        val replacement = f.speech.submissions.last()
        assertEquals("취소 중 도착한 질문", replacement.message)
        assertTrue(replacement.current())
        assertEquals(replacement.generation, f.coordinator.state.responseGeneration)
    }

    @Test fun retiredScopeAndReplacedDecisionCannotAnnounceOrRetryTheirOldQuestion() {
        for (retirement in listOf("scope", "decision")) {
            val f = Fixture()
            assertTrue(f.speak())
            val old = f.speech.submissions.single()
            if (retirement == "scope") f.scopeCurrent = false
            else f.pendingToken = f.token.copy(decisionRevision = f.token.decisionRevision + 1L)
            assertFalse(old.current())
            f.speech.done(old)
            old.failed()
            assertNull(f.coordinator.state.announcedToken)
            assertNull(f.coordinator.state.pendingToken)
            assertTrue(f.scheduler.tasks.isEmpty())
            assertFalse(f.speak())
            assertEquals(1, f.speech.submissions.size)
        }
    }

    @Test fun onlyTheOwnedInterruptedGenerationFailsAndSchedulesRecovery() {
        val f = Fixture()
        assertTrue(f.speak())
        val question = f.speech.submissions.single()
        f.coordinator.failInterruptedSpeech(question.generation - 1L)
        assertTrue(f.speech.failedGenerations.isEmpty())
        assertTrue(f.scheduler.tasks.isEmpty())
        f.coordinator.failInterruptedSpeech(question.generation)
        assertEquals(listOf(question.generation), f.speech.failedGenerations)
        assertEquals(1, f.scheduler.tasks.size)
        assertNull(f.coordinator.state.responseGeneration)
        f.coordinator.failInterruptedSpeech(question.generation)
        assertEquals(1, f.speech.failedGenerations.size)
        assertEquals(1, f.scheduler.tasks.size)
    }

    private class Fixture {
        val token = RouteNavigatorDecisionToken(1L, 2L, "route", RouteNavigatorUserDecision.REROUTE)
        val message = "새 경로 요청, 위치 다시 확인, 길안내 종료 중 선택해 주세요."
        var pendingToken: RouteNavigatorDecisionToken? = token
        var scopeCurrent = true
        val speech = Speech()
        val scheduler = Scheduler()
        val coordinator = RouteDecisionSpeechCoordinator(speech, scheduler) { pendingToken }
        fun speak(message: String = this.message, repeatRequested: Boolean = false) =
            coordinator.speak(message, token, repeatRequested, isScopeCurrent = { scopeCurrent })
    }

    private data class Submission(
        val message: String,
        val generation: Long,
        val current: () -> Boolean,
        val done: () -> Unit,
        val failed: () -> Unit,
    )

    private class Speech : RouteDecisionSpeechCoordinator.SpeechPort {
        override var commandGeneration = 10L
        override var commandPending = false
        var input = false
        var output = false
        var cancellations = 0
        var onCancel: () -> Unit = {}
        var onSubmit: (Submission) -> Boolean = { true }
        val failedGenerations = mutableListOf<Long>()
        val submissions = mutableListOf<Submission>()
        override fun outputBusy() = output
        override fun inputActive() = input
        override fun cancelCommand() {
            cancellations++
            commandPending = false
            commandGeneration++
            onCancel()
        }
        override fun failCommand(generation: Long) {
            failedGenerations += generation
            submissions.lastOrNull { it.generation == generation }?.let(::fail)
        }
        override fun submitStatus(message: String, current: () -> Boolean, completed: () -> Unit,
            failed: (() -> Unit)?): Boolean {
            commandGeneration++
            commandPending = true
            val submission = Submission(message, commandGeneration, current, completed, requireNotNull(failed))
            submissions += submission
            return onSubmit(submission).also { accepted ->
                if (!accepted && commandGeneration == submission.generation) commandPending = false
            }
        }
        fun done(submission: Submission) {
            if (commandGeneration == submission.generation) commandPending = false
            submission.done()
        }
        fun fail(submission: Submission) {
            if (commandGeneration == submission.generation) commandPending = false
            submission.failed()
        }
    }

    private class Scheduler : RouteCallbackScheduler {
        data class Task(val runnable: Runnable, val delayMs: Long)
        val tasks = mutableListOf<Task>()
        val removed = mutableListOf<Runnable>()
        override fun post(task: Runnable) = error("A question retry must retain its delay")
        override fun postDelayed(task: Runnable, delayMs: Long) { tasks += Task(task, delayMs) }
        override fun remove(task: Runnable) {
            removed += task
            tasks.removeAll { it.runnable === task }
        }
        fun runNext() { tasks.removeAt(0).runnable.run() }
    }
}
