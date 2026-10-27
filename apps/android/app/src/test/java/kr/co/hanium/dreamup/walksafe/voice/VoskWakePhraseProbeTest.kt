package kr.co.hanium.dreamup.walksafe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskWakePhraseProbeTest {
    @Test
    fun becomesAvailableOnlyAfterReadyAndTrustedFinalWakePhrase() {
        val harness = ProbeHarness()

        assertTrue(harness.probe.start())
        harness.listener.onTranscript(7L, transcript("길라잡이", confidence = 0.95f))
        harness.listener.onReady(7L)
        harness.listener.onTranscript(
            7L,
            transcript("길라잡이", confidence = 0.95f, isFinal = false),
        )
        harness.listener.onTranscript(7L, transcript("길라잡이", confidence = 0.59f))
        harness.listener.onTranscript(7L, transcript("오늘 날씨 알려줘", confidence = 0.99f))

        assertTrue(harness.results.isEmpty())
        assertEquals(0, harness.stream.stopCount)

        harness.listener.onTranscript(7L, transcript("길라잡이", confidence = 0.95f))

        assertTrue(harness.results.isEmpty())
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        harness.dispatcher.runPosted()
        assertEquals(
            listOf(VoskWakePhraseProbeResult.available()),
            harness.results,
        )
        assertTrue(harness.resultCallbacksWereOnMain.single())
    }

    @Test
    fun wakePhraseFollowedByACommandAlsoProvesAvailability() {
        val harness = ProbeHarness()

        harness.probe.start()
        harness.listener.onReady(11L)
        harness.listener.onTranscript(
            11L,
            transcript("길라 잡이 서울역으로 안내해줘", confidence = 0.91f),
        )
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeAvailability.AVAILABLE, harness.results.single().availability)
        assertNull(harness.results.single().failure)
        assertNull("Capability checks must not dispatch spoken commands", harness.results.single().commandTranscript)
    }

    @Test
    fun duplicateReadyCannotReplaceTheFirstActiveRun() {
        val harness = ProbeHarness()

        harness.probe.start()
        harness.listener.onReady(13L)
        harness.listener.onReady(17L)
        harness.listener.onTranscript(17L, transcript("길라잡이", confidence = 0.99f))

        assertTrue(harness.results.isEmpty())
        // Observe readiness while the first run is still alive, before its terminal wake result.
        harness.dispatcher.runPosted()
        assertEquals(1, harness.readyCallbacks)

        harness.listener.onTranscript(13L, transcript("길라잡이", confidence = 0.99f))
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeAvailability.AVAILABLE, harness.results.single().availability)
        assertEquals(1, harness.readyCallbacks)
    }

    @Test
    fun readyCueIsDeliveredOnceOnTheDispatcher() {
        val harness = ProbeHarness()

        harness.probe.start()
        harness.listener.onReady(29L)
        harness.listener.onReady(31L)

        assertEquals(0, harness.readyCallbacks)
        harness.dispatcher.runPosted()
        assertEquals(1, harness.readyCallbacks)
        assertTrue(harness.readyCallbackWasOnMain)
    }

    @Test
    fun successfulWakeBeforeQueuedReadyNeverReopensTheFinishedProbe() {
        val harness = ProbeHarness()
        harness.probe.start()
        harness.listener.onReady(13L)
        harness.listener.onReady(17L)
        harness.listener.onTranscript(17L, transcript("길라잡이", confidence = 0.99f))
        assertTrue(harness.results.isEmpty())
        harness.listener.onTranscript(13L, transcript("길라잡이", confidence = 0.99f))
        harness.dispatcher.runPosted()
        assertEquals(VoskWakePhraseProbeAvailability.AVAILABLE, harness.results.single().availability)
        assertEquals(0, harness.readyCallbacks)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
    }

    @Test
    fun timeoutBeforeReadyFailsClosedAndRejectsEveryLateCallback() {
        val harness = ProbeHarness()

        harness.probe.start()
        harness.dispatcher.fireTimeout()
        harness.listener.onReady(19L)
        harness.listener.onTranscript(19L, transcript("길라잡이", confidence = 0.99f))
        harness.listener.onError(19L, VoskStreamingError.INFERENCE_FAILED)
        harness.probe.cancel()
        harness.probe.close()
        harness.dispatcher.runPosted()

        assertEquals(1, harness.results.size)
        assertEquals(VoskWakePhraseProbeAvailability.UNAVAILABLE, harness.results.single().availability)
        assertEquals(VoskWakePhraseProbeFailure.TIMEOUT, harness.results.single().failure)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertFalse(harness.probe.start())
    }

    @Test
    fun boundedWakePreparationRetainsTwentySecondDeadlineAndRejectsLateCallbacks() {
        val harness = ProbeHarness(listenUntilWake = false, timeoutMs = 20_000L)
        assertTrue(harness.probe.start())
        harness.dispatcher.advanceBy(19_999L)
        assertTrue(harness.results.isEmpty())
        assertEquals(0, harness.stream.stopCount)
        harness.dispatcher.advanceBy(1L)
        harness.listener.onReady(211L)
        harness.listener.onTranscript(211L, transcript("길라잡이", confidence = 0.99f))
        harness.listener.onError(211L, VoskStreamingError.INFERENCE_FAILED)
        harness.probe.cancel()
        harness.probe.close()
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.TIMEOUT, harness.results.single().failure)
        assertEquals(VoskWakePhraseProbeAvailability.UNAVAILABLE, harness.results.single().availability)
        assertEquals(0, harness.readyCallbacks)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertTrue(harness.resourcesClosedAtResult.single())
        assertFalse(harness.probe.start())
    }

    @Test
    fun boundedReadyKeepsTwentySecondListeningDeadlineAndCannotAcceptALateWake() {
        val harness = ProbeHarness(listenUntilWake = false, timeoutMs = 20_000L)
        assertTrue(harness.probe.start())
        harness.dispatcher.advanceBy(19_000L)
        harness.listener.onReady(223L)
        harness.dispatcher.runPosted()
        assertEquals(1, harness.readyCallbacks)
        assertTrue(harness.dispatcher.scheduledTasks.first().isCancelled)
        assertEquals(1, harness.dispatcher.pendingTimeoutCount)
        harness.dispatcher.advanceBy(19_999L)
        assertTrue(harness.results.isEmpty())
        assertEquals(0, harness.stream.stopCount)
        harness.dispatcher.advanceBy(1L)
        harness.listener.onReady(223L)
        harness.listener.onTranscript(223L, transcript("길라잡이", confidence = 0.99f))
        harness.listener.onError(223L, VoskStreamingError.INFERENCE_FAILED)
        harness.probe.cancel()
        harness.probe.close()
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.TIMEOUT, harness.results.single().failure)
        assertEquals(VoskWakePhraseProbeAvailability.UNAVAILABLE, harness.results.single().availability)
        assertEquals(1, harness.readyCallbacks)
        assertEquals(0, harness.dispatcher.pendingTimeoutCount)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertTrue(harness.resourcesClosedAtResult.single())
        assertFalse(harness.probe.start())
    }

    @Test
    fun streamErrorAfterReadyIsReportedOnceWithItsBoundedCategory() {
        for (error in listOf(VoskStreamingError.AUDIO_READ_FAILED, VoskStreamingError.AUDIO_SILENCED)) {
            val harness = ProbeHarness()

            harness.probe.start()
            harness.listener.onReady(23L)
            harness.listener.onError(23L, error)
            harness.listener.onError(23L, VoskStreamingError.INFERENCE_FAILED)
            harness.listener.onTranscript(23L, transcript("길라잡이", confidence = 0.99f))
            harness.dispatcher.fireTimeout()
            harness.probe.cancel()
            harness.dispatcher.runPosted()

            val result = harness.results.single()
            assertEquals(VoskWakePhraseProbeAvailability.UNAVAILABLE, result.availability)
            assertEquals(VoskWakePhraseProbeFailure.STREAM_ERROR, result.failure)
            assertEquals(error, result.streamingError)
            assertEquals(1, harness.stream.stopCount)
            assertEquals(1, harness.stream.closeCount)
            assertFalse(harness.probe.start())
        }
    }

    @Test
    fun cancelAndCloseAreFailClosedAndIdempotent() {
        val cancelled = ProbeHarness()
        cancelled.probe.start()
        cancelled.probe.cancel()
        cancelled.probe.cancel()
        cancelled.probe.close()
        cancelled.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.CANCELLED, cancelled.results.single().failure)
        assertEquals(1, cancelled.stream.stopCount)
        assertEquals(1, cancelled.stream.closeCount)

        val closed = ProbeHarness()
        closed.probe.close()
        closed.probe.close()
        closed.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.CLOSED, closed.results.single().failure)
        assertEquals(0, closed.factory.createCount)
        assertFalse(closed.probe.start())
    }

    @Test
    fun synchronousStartFailureClosesResourcesAndPostsOneUnavailableResult() {
        val harness = ProbeHarness(startFailure = IllegalStateException("start failed"))

        assertFalse(harness.probe.start())
        assertTrue(harness.results.isEmpty())
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.START_FAILED, harness.results.single().failure)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertTrue(harness.resultCallbacksWereOnMain.single())
    }

    @Test
    fun lateReadyReceivesTheFullListeningWindow() {
        val harness = ProbeHarness()
        harness.probe.start()
        harness.dispatcher.advanceBy(3_900L)
        harness.listener.onReady(41L)
        harness.dispatcher.runPosted()

        harness.dispatcher.advanceBy(100L)
        harness.dispatcher.runPosted()
        assertTrue("Preparation time must not consume the listening window", harness.results.isEmpty())

        harness.dispatcher.advanceBy(3_899L)
        harness.dispatcher.runPosted()
        assertTrue(harness.results.isEmpty())

        harness.dispatcher.advanceBy(1L)
        harness.dispatcher.runPosted()
        assertEquals(VoskWakePhraseProbeFailure.TIMEOUT, harness.results.single().failure)
        assertEquals(1, harness.readyCallbacks)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertEquals(0, harness.dispatcher.pendingTimeoutCount)
    }

    @Test
    fun trustedWakeAfterThePreparationDeadlineCanStillCompleteListening() {
        val harness = ProbeHarness()
        harness.probe.start()
        harness.dispatcher.advanceBy(3_500L)
        harness.listener.onReady(43L)
        harness.dispatcher.advanceBy(1_000L)
        harness.listener.onTranscript(43L, transcript("길라잡이", confidence = 0.95f))
        harness.dispatcher.advanceBy(10_000L)
        harness.dispatcher.runPosted()

        assertEquals(listOf(VoskWakePhraseProbeResult.available()), harness.results)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertEquals(0, harness.dispatcher.pendingTimeoutCount)
    }

    @Test
    fun preparationStillTimesOutWithoutReadyAtItsOriginalDeadline() {
        val harness = ProbeHarness()
        harness.probe.start()
        harness.dispatcher.advanceBy(3_999L)
        harness.dispatcher.runPosted()
        assertTrue(harness.results.isEmpty())

        harness.dispatcher.advanceBy(1L)
        harness.listener.onReady(47L)
        harness.listener.onTranscript(47L, transcript("길라잡이", confidence = 0.95f))
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.TIMEOUT, harness.results.single().failure)
        assertEquals(0, harness.readyCallbacks)
        assertEquals(1, harness.dispatcher.scheduledTasks.size)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
    }

    @Test
    fun queuedPreparationTimeoutCannotTerminateAnAlreadyListeningRun() {
        val harness = ProbeHarness()
        harness.probe.start()
        val preparationTimeout = harness.dispatcher.scheduledTasks.single()
        harness.listener.onReady(53L)
        preparationTimeout.runQueuedCallback()
        harness.dispatcher.runPosted()
        assertTrue("A cancelled but already queued preparation callback is stale", harness.results.isEmpty())

        harness.listener.onTranscript(53L, transcript("길라잡이", confidence = 0.95f))
        preparationTimeout.runQueuedCallback()
        harness.dispatcher.runPosted()

        assertEquals(listOf(VoskWakePhraseProbeResult.available()), harness.results)
        assertTrue(preparationTimeout.isCancelled)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
    }

    @Test
    fun duplicateReadyDoesNotExtendTheListeningDeadline() {
        val harness = ProbeHarness()
        harness.probe.start()
        harness.dispatcher.advanceBy(2_000L)
        harness.listener.onReady(59L)
        harness.dispatcher.advanceBy(3_500L)
        harness.listener.onReady(59L)
        harness.listener.onReady(61L)
        harness.dispatcher.advanceBy(499L)
        harness.dispatcher.runPosted()
        assertTrue(harness.results.isEmpty())

        harness.dispatcher.advanceBy(1L)
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.TIMEOUT, harness.results.single().failure)
        assertEquals(1, harness.readyCallbacks)
        assertEquals(2, harness.dispatcher.scheduledTasks.size)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
    }

    @Test
    fun cancelAndCloseDuringListeningRejectBothQueuedTimersAndLateCallbacks() {
        for (close in listOf(false, true)) {
            val harness = ProbeHarness()
            harness.probe.start()
            harness.listener.onReady(67L)
            harness.dispatcher.runPosted()
            if (close) harness.probe.close() else harness.probe.cancel()
            harness.probe.cancel()
            harness.probe.close()
            harness.dispatcher.scheduledTasks.forEach { it.runQueuedCallback() }
            harness.listener.onReady(71L)
            harness.listener.onTranscript(67L, transcript("길라잡이", confidence = 0.99f))
            harness.listener.onError(67L, VoskStreamingError.AUDIO_READ_FAILED)
            harness.dispatcher.runPosted()

            assertEquals(
                if (close) VoskWakePhraseProbeFailure.CLOSED else VoskWakePhraseProbeFailure.CANCELLED,
                harness.results.single().failure,
            )
            assertEquals(1, harness.readyCallbacks)
            assertEquals(1, harness.stream.stopCount)
            assertEquals(1, harness.stream.closeCount)
            assertEquals(0, harness.dispatcher.pendingTimeoutCount)
        }
    }

    @Test
    fun listeningTimerRegistrationFailureClosesResourcesOnce() {
        val harness = ProbeHarness()
        harness.dispatcher.rejectScheduleNumber = 2
        harness.probe.start()
        harness.listener.onReady(73L)
        harness.dispatcher.advanceBy(10_000L)
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.START_FAILED, harness.results.single().failure)
        assertEquals(0, harness.readyCallbacks)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertEquals(0, harness.dispatcher.pendingTimeoutCount)
    }

    @Test
    fun cancellationWhileTheListeningTimerIsRegisteredCancelsItsUnadoptedHandle() {
        val harness = ProbeHarness()
        harness.probe.start()
        harness.dispatcher.onScheduled = { number ->
            if (number == 2) harness.probe.cancel()
        }
        harness.listener.onReady(79L)
        harness.dispatcher.scheduledTasks.forEach { it.runQueuedCallback() }
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.CANCELLED, harness.results.single().failure)
        assertEquals(0, harness.readyCallbacks)
        assertEquals(2, harness.dispatcher.scheduledTasks.size)
        assertTrue(harness.dispatcher.scheduledTasks.all { it.isCancelled })
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertEquals(0, harness.dispatcher.pendingTimeoutCount)
    }

    @Test
    fun streamErrorWhileTheListeningTimerIsRegisteredStillTerminatesOnce() {
        val harness = ProbeHarness()
        harness.probe.start()
        harness.dispatcher.onScheduled = { number ->
            if (number == 2) harness.listener.onError(83L, VoskStreamingError.AUDIO_READ_FAILED)
        }
        harness.listener.onReady(83L)
        harness.dispatcher.scheduledTasks.forEach { it.runQueuedCallback() }
        harness.probe.close()
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.STREAM_ERROR, harness.results.single().failure)
        assertEquals(VoskStreamingError.AUDIO_READ_FAILED, harness.results.single().streamingError)
        assertEquals(0, harness.readyCallbacks)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertEquals(0, harness.dispatcher.pendingTimeoutCount)
    }


    @Test
    fun homeWaitKeepsListeningWithoutWeakeningTheWakeGate() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.dispatcher.advanceBy(3_900L)
        harness.listener.onReady(89L)
        harness.dispatcher.runPosted()
        harness.dispatcher.advanceBy(120_000L)
        harness.dispatcher.scheduledTasks.single().runQueuedCallback()
        harness.listener.onTranscript(89L, transcript("길라잡이", 0.99f, isFinal = false))
        harness.listener.onTranscript(89L, transcript("길라잡이", 0.59f))
        harness.listener.onTranscript(89L, transcript("도움말", 0.99f))
        harness.dispatcher.runPosted()

        assertTrue(harness.results.isEmpty())
        assertEquals(1, harness.readyCallbacks)
        assertEquals(0, harness.dispatcher.pendingTimeoutCount)
        assertEquals(1, harness.dispatcher.scheduledTasks.size)
        assertEquals(1, harness.stream.startCount)
        assertEquals(0, harness.stream.stopCount)

        harness.listener.onTranscript(89L, transcript("길라잡이", 0.95f))
        harness.dispatcher.runPosted()
        assertEquals(listOf(VoskWakePhraseProbeResult.available()), harness.results)
        assertTrue(harness.resourcesClosedAtResult.single())
    }

    @Test
    fun homePreparationStillHasTheOriginalFiniteDeadline() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.dispatcher.advanceBy(4_000L)
        harness.listener.onReady(97L)
        harness.listener.onTranscript(97L, transcript("길라잡이", 0.95f))
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.TIMEOUT, harness.results.single().failure)
        assertEquals(0, harness.readyCallbacks)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertFalse(harness.probe.start())
    }

    @Test
    fun homeDuplicateReadyCannotSwitchRunsOrCreateListeningTimers() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.listener.onReady(101L)
        harness.listener.onReady(101L)
        harness.listener.onReady(103L)
        harness.listener.onTranscript(103L, transcript("길라잡이", 0.99f))
        harness.listener.onError(103L, VoskStreamingError.AUDIO_READ_FAILED)
        harness.dispatcher.runPosted()

        assertTrue(harness.results.isEmpty())
        assertEquals(1, harness.readyCallbacks)
        assertEquals(1, harness.dispatcher.scheduledTasks.size)
        harness.listener.onTranscript(101L, transcript("길라잡이", 0.95f))
        harness.dispatcher.runPosted()
        assertEquals(listOf(VoskWakePhraseProbeResult.available()), harness.results)
    }

    @Test
    fun homeWakeReturnsFromCaptureBeforeDispatcherCleanupAndHandoff() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.listener.onReady(107L)
        harness.dispatcher.runPosted()
        var captureCallbackReturned = false
        harness.stream.beforeStop = {
            assertTrue("Capture callback must not join itself", captureCallbackReturned)
            assertTrue("HOME cleanup belongs to the dispatcher", harness.dispatcher.runningPostedTask)
        }

        harness.listener.onTranscript(107L, transcript("길라잡이", 0.95f))
        captureCallbackReturned = true
        assertEquals(0, harness.stream.stopCount)
        assertTrue(harness.results.isEmpty())
        harness.dispatcher.runPosted()

        assertEquals(listOf(VoskWakePhraseProbeResult.available()), harness.results)
        assertTrue(harness.resourcesClosedAtResult.single())
        assertTrue(harness.resultCallbacksWereOnMain.single())
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
    }

    @Test
    fun homeHandsOffTrustedFinalCommandOnlyAfterReleasingTheMicrophone() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.listener.onReady(137L)
        harness.dispatcher.runPosted()
        harness.listener.onTranscript(137L, transcript("길라잡이 서울역으로 안내해줘", 0.93f))
        harness.listener.onTranscript(137L, transcript("길라잡이 목적지 취소", 0.99f))
        assertTrue(harness.results.isEmpty())
        assertEquals(0, harness.stream.stopCount)

        harness.dispatcher.runPosted()

        val result = harness.results.single()
        assertEquals(transcript("서울역으로 안내해줘", 0.93f), result.commandTranscript)
        assertTrue(harness.resourcesClosedAtResult.single())
        assertTrue(harness.resultCallbacksWereOnMain.single())
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
    }

    @Test
    fun homeIgnoresALowWakePhraseEvenWhenTheCommandWordsAreClear() {
        // S04-19: per-word scores [0.10, 1.0, 1.0] average 0.70, but the wake phrase itself is doubtful.
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.listener.onReady(141L)
        harness.dispatcher.runPosted()
        val lowWake = checkNotNull(
            parseVoskTranscript(
                """{"result":[{"conf":0.10,"word":"길라잡이"},{"conf":1.0,"word":"서울역으로"},{"conf":1.0,"word":"안내해줘"}],"text":"길라잡이 서울역으로 안내해줘"}""",
                isFinal = true,
            ),
        )
        assertTrue(lowWake.isTrustedForCommand())
        harness.listener.onTranscript(141L, lowWake)
        harness.dispatcher.runPosted()
        assertTrue(harness.results.isEmpty())
        assertEquals(0, harness.stream.stopCount)
        val clearWake = checkNotNull(
            parseVoskTranscript(
                """{"result":[{"conf":0.9,"word":"길라잡이"},{"conf":1.0,"word":"서울역으로"},{"conf":1.0,"word":"안내해줘"}],"text":"길라잡이 서울역으로 안내해줘"}""",
                isFinal = true,
            ),
        )
        harness.listener.onTranscript(141L, clearWake)
        harness.dispatcher.runPosted()
        assertEquals("서울역으로 안내해줘", harness.results.single().commandTranscript?.text)
    }

    @Test
    fun homeNeverHandsOffPartialLowConfidenceUnaddressedOrCancelledCommands() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.listener.onReady(139L)
        harness.dispatcher.runPosted()
        harness.listener.onTranscript(139L, transcript("길라잡이 서울역으로 안내해줘", 0.99f, isFinal = false))
        harness.listener.onTranscript(139L, transcript("길라잡이 서울역으로 안내해줘", 0.59f))
        harness.listener.onTranscript(139L, transcript("서울역으로 안내해줘", 0.99f))
        harness.listener.onTranscript(149L, transcript("길라잡이 서울역으로 안내해줘", 0.99f))
        harness.dispatcher.runPosted()
        assertTrue(harness.results.isEmpty())

        harness.listener.onTranscript(139L, transcript("길라잡이 서울역으로 안내해줘", 0.99f))
        harness.probe.cancel()
        harness.dispatcher.runPosted()
        assertEquals(VoskWakePhraseProbeFailure.CANCELLED, harness.results.single().failure)
        assertNull(harness.results.single().commandTranscript)
    }

    @Test
    fun homeCancelBeatsAnAlreadyQueuedWakeWithoutStartingAgain() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.listener.onReady(109L)
        harness.dispatcher.runPosted()
        harness.listener.onTranscript(109L, transcript("길라잡이", 0.95f))

        harness.probe.cancel()
        assertEquals("Foreground owner cancellation releases immediately", 1, harness.stream.closeCount)
        harness.dispatcher.runPosted()
        harness.dispatcher.advanceBy(120_000L)
        harness.probe.cancel()
        harness.probe.close()

        assertEquals(VoskWakePhraseProbeFailure.CANCELLED, harness.results.single().failure)
        assertTrue(harness.resourcesClosedAtResult.single())
        assertEquals(1, harness.factory.createCount)
        assertEquals(1, harness.stream.startCount)
        assertFalse(harness.probe.start())
    }

    @Test
    fun homeStreamErrorCleansUpOnDispatcherOnceAndNeverRetriesItself() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.listener.onReady(113L)
        harness.dispatcher.runPosted()
        harness.stream.beforeStop = { assertTrue(harness.dispatcher.runningPostedTask) }
        harness.listener.onError(113L, VoskStreamingError.AUDIO_READ_FAILED)
        harness.listener.onError(113L, VoskStreamingError.INFERENCE_FAILED)
        harness.listener.onTranscript(113L, transcript("길라잡이", 0.95f))
        assertEquals(0, harness.stream.stopCount)

        harness.dispatcher.runPosted()
        harness.dispatcher.advanceBy(120_000L)
        assertEquals(VoskWakePhraseProbeFailure.STREAM_ERROR, harness.results.single().failure)
        assertEquals(VoskStreamingError.AUDIO_READ_FAILED, harness.results.single().streamingError)
        assertTrue(harness.resourcesClosedAtResult.single())
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertEquals(1, harness.factory.createCount)
        assertFalse(harness.probe.start())
    }

    @Test
    fun homeCloseRejectsQueuedErrorAndLateWake() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.listener.onReady(127L)
        harness.dispatcher.runPosted()
        harness.listener.onError(127L, VoskStreamingError.AUDIO_READ_FAILED)
        harness.probe.close()
        harness.listener.onReady(131L)
        harness.listener.onTranscript(127L, transcript("길라잡이", 0.95f))
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.CLOSED, harness.results.single().failure)
        assertEquals(1, harness.readyCallbacks)
        assertTrue(harness.resourcesClosedAtResult.single())
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertFalse(harness.probe.start())
    }

    @Test
    fun homeRejectsQueuedFinalAfterSuppressionStartsAndEndsBeforeDelivery() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.listener.onReady(157L)
        harness.dispatcher.runPosted()
        harness.listener.onTranscript(157L, transcript("길라잡이 신고해줘", 0.95f), harness.inputGeneration)

        harness.suppressed = true
        harness.inputGeneration += 1L
        harness.suppressed = false
        harness.dispatcher.runPosted()

        assertTrue("A completed suppression interval must retire its queued result", harness.results.isEmpty())
        assertEquals(0, harness.stream.stopCount)
        harness.listener.onTranscript(157L, transcript("길라잡이 도움말", 0.95f), harness.inputGeneration)
        harness.dispatcher.runPosted()
        assertEquals("도움말", harness.results.single().commandTranscript?.text)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
    }

    @Test
    fun homeRejectsCapturedOldGenerationEvenWhenCallbackArrivesAfterOutputEnds() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.listener.onReady(163L)
        harness.dispatcher.runPosted()
        val capturedGeneration = harness.inputGeneration
        harness.suppressed = true
        harness.inputGeneration += 1L
        harness.suppressed = false
        harness.listener.onTranscript(163L, transcript("길라잡이 신고해줘", 0.95f), capturedGeneration)
        harness.dispatcher.runPosted()

        assertTrue("Use the decoder's generation instead of recapturing at callback time", harness.results.isEmpty())
        assertEquals(0, harness.stream.stopCount)
    }

    @Test
    fun homeStillRejectsQueuedFinalWhileSuppressedEvenWithTheSameGeneration() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.listener.onReady(167L)
        harness.dispatcher.runPosted()
        harness.listener.onTranscript(167L, transcript("길라잡이 신고해줘", 0.95f), harness.inputGeneration)
        harness.suppressed = true
        harness.dispatcher.runPosted()

        assertTrue(harness.results.isEmpty())
        assertEquals(0, harness.stream.stopCount)
        harness.suppressed = false
        harness.listener.onTranscript(167L, transcript("길라잡이 도움말", 0.95f), harness.inputGeneration)
        harness.dispatcher.runPosted()
        assertEquals("도움말", harness.results.single().commandTranscript?.text)
    }

    @Test
    fun homeLegacyCallbackCapturesGenerationBeforePosting() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.listener.onReady(173L)
        harness.dispatcher.runPosted()
        harness.listener.onTranscript(173L, transcript("길라잡이 신고해줘", 0.95f))
        harness.inputGeneration += 1L
        harness.dispatcher.runPosted()

        assertTrue(harness.results.isEmpty())
        harness.listener.onTranscript(173L, transcript("길라잡이 도움말", 0.95f))
        harness.dispatcher.runPosted()
        assertEquals("도움말", harness.results.single().commandTranscript?.text)
    }

    @Test
    fun homeFailsClosedForMissingOrUnreadableGenerationAndRecoversForFreshInput() {
        val harness = ProbeHarness(listenUntilWake = true)
        harness.probe.start()
        harness.listener.onReady(179L)
        harness.dispatcher.runPosted()
        harness.listener.onTranscript(179L, transcript("길라잡이 신고해줘", 0.95f), null)
        harness.generationReadFails = true
        harness.listener.onTranscript(179L, transcript("길라잡이 신고해줘", 0.95f), harness.inputGeneration)
        harness.listener.onTranscript(179L, transcript("길라잡이 신고해줘", 0.95f))
        harness.generationReadFails = false
        harness.dispatcher.runPosted()

        assertTrue(harness.results.isEmpty())
        assertEquals(0, harness.stream.stopCount)
        harness.listener.onTranscript(179L, transcript("길라잡이 도움말", 0.95f), harness.inputGeneration)
        harness.dispatcher.runPosted()
        assertEquals("도움말", harness.results.single().commandTranscript?.text)
    }

    @Test
    fun homeRechecksGenerationAndSuppressionAtTheFinalResultDelivery() {
        for (generationChanges in listOf(false, true)) {
            val harness = ProbeHarness(listenUntilWake = true)
            harness.probe.start()
            harness.listener.onReady(181L)
            harness.dispatcher.runPosted()
            harness.listener.onTranscript(181L, transcript("길라잡이 신고해줘", 0.95f), harness.inputGeneration)
            harness.dispatcher.runNextPosted()
            assertEquals(1, harness.stream.stopCount)
            assertEquals(1, harness.stream.closeCount)
            assertTrue(harness.results.isEmpty())

            harness.suppressed = true
            if (generationChanges) {
                harness.inputGeneration += 1L
                harness.suppressed = false
            }
            harness.dispatcher.runPosted()

            assertEquals(VoskWakePhraseProbeFailure.CANCELLED, harness.results.single().failure)
            assertNull(harness.results.single().commandTranscript)
            assertEquals(1, harness.stream.stopCount)
            assertEquals(1, harness.stream.closeCount)
        }
    }

    private class ProbeHarness(
        startFailure: RuntimeException? = null,
        listenUntilWake: Boolean = false,
        timeoutMs: Long = 4_000L,
    ) {
        val dispatcher = FakeDispatcher()
        val stream = FakeStream(startFailure)
        val factory = FakeStreamFactory(stream)
        val results = mutableListOf<VoskWakePhraseProbeResult>()
        val resultCallbacksWereOnMain = mutableListOf<Boolean>()
        val resourcesClosedAtResult = mutableListOf<Boolean>()
        var suppressed = false
        var inputGeneration = 1L
        var generationReadFails = false
        var readyCallbacks = 0
        var readyCallbackWasOnMain = false
        val probe = VoskWakePhraseProbe(
            streamFactory = factory,
            dispatcher = dispatcher,
            timeoutMs = timeoutMs,
            onResult = { result ->
                results += result
                resultCallbacksWereOnMain += dispatcher.runningPostedTask
                resourcesClosedAtResult += stream.stopCount == 1 && stream.closeCount == 1
            },
            onReady = {
                readyCallbacks += 1
                readyCallbackWasOnMain = dispatcher.runningPostedTask
            },
            listenUntilWake = listenUntilWake,
            isInputSuppressed = { suppressed },
            inputGeneration = { if (generationReadFails) error("generation unavailable") else inputGeneration },
        )

        val listener: VoskStreamingListener
            get() = factory.listener
    }

    private class FakeStreamFactory(
        private val stream: FakeStream,
    ) : VoskWakePhraseStreamFactory {
        var createCount = 0
        lateinit var listener: VoskStreamingListener

        override fun create(listener: VoskStreamingListener): VoskWakePhraseStream {
            createCount += 1
            this.listener = listener
            return stream
        }
    }

    private class FakeStream(
        private val startFailure: RuntimeException?,
    ) : VoskWakePhraseStream {
        var startCount = 0
        var stopCount = 0
        var closeCount = 0
        var beforeStop: (() -> Unit)? = null

        override fun start() {
            startCount += 1
            startFailure?.let { throw it }
        }

        override fun stop() {
            beforeStop?.invoke()
            stopCount += 1
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeDispatcher : VoskWakePhraseProbeDispatcher {
        private val posted = ArrayDeque<() -> Unit>()
        val scheduledTasks = mutableListOf<ScheduledTask>()
        private var nowMs = 0L
        var rejectScheduleNumber: Int? = null
        var onScheduled: ((Int) -> Unit)? = null
        var runningPostedTask = false
            private set
        val pendingTimeoutCount: Int
            get() = scheduledTasks.count { it.isPending }

        override fun post(task: () -> Unit) {
            posted += task
        }

        override fun postDelayed(
            delayMs: Long,
            task: () -> Unit,
        ): VoskWakePhraseProbeCancellation {
            check(delayMs > 0L)
            val number = scheduledTasks.size + 1
            check(number != rejectScheduleNumber) { "test timer registration failure" }
            val scheduled = ScheduledTask(nowMs + delayMs, task)
            scheduledTasks += scheduled
            onScheduled?.invoke(number)
            return scheduled
        }

        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = nowMs + deltaMs
            while (true) {
                val next = scheduledTasks
                    .filter { it.isPending && it.deadlineMs <= target }
                    .minByOrNull { it.deadlineMs }
                    ?: break
                nowMs = next.deadlineMs
                next.runUnlessCancelled()
            }
            nowMs = target
        }

        fun fireTimeout() {
            scheduledTasks.lastOrNull()?.runUnlessCancelled()
        }

        fun runPosted() {
            while (posted.isNotEmpty()) runNextPosted()
        }

        fun runNextPosted() {
            runningPostedTask = true
            try {
                posted.removeFirst().invoke()
            } finally {
                runningPostedTask = false
            }
        }
    }

    private class ScheduledTask(
        val deadlineMs: Long,
        private val task: () -> Unit,
    ) : VoskWakePhraseProbeCancellation {
        var isCancelled = false
            private set
        private var fired = false
        val isPending: Boolean
            get() = !isCancelled && !fired

        override fun cancel() {
            isCancelled = true
        }

        fun runUnlessCancelled() {
            if (isPending) {
                fired = true
                task()
            }
        }

        fun runQueuedCallback() {
            fired = true
            task()
        }
    }

    private fun transcript(
        text: String,
        confidence: Float,
        isFinal: Boolean = true,
    ) = VoskTranscript(
        text = text,
        confidence = confidence,
        isFinal = isFinal,
    )
}
