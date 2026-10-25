package kr.co.hanium.dreamup.walksafe.startup

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class PrivacyStartupInspectionControllerTest {
    @Test fun capturedParticipantsKeepTheirOriginalIdentityAndBothAcksWhenSpeechFails() {
        val originalSpeech = Any()
        val originalRoute = Any()
        var currentSpeech = originalSpeech
        var currentRoute = originalRoute
        val events = mutableListOf<String>()
        val captured = PreparedPrivacyStartupReset.capture(
            prepareSpeech = { events += "capture-speech"; currentSpeech },
            prepareRoute = { events += "capture-route"; currentRoute },
            awaitSpeech = { assertSame(originalSpeech, it); events += "await-speech"; false },
            awaitRoute = { assertSame(originalRoute, it); events += "await-route"; true },
            speechIsCurrent = { assertSame(originalSpeech, it); events += "check-speech"; true },
            routeIsCurrent = { assertSame(originalRoute, it); events += "check-route"; true },
        )
        currentSpeech = Any()
        currentRoute = Any()
        assertFalse(captured.awaitOriginalAcknowledgements())
        assertTrue(captured.isCurrent())
        assertEquals(listOf("capture-speech", "capture-route", "await-speech", "await-route", "check-speech", "check-route"), events)
    }

    @Test fun failedRoutePreparationStillWaitsForTheAlreadyCapturedSpeechAckAndCannotBeCurrent() {
        val speech = Any()
        val events = mutableListOf<String>()
        val captured = PreparedPrivacyStartupReset.capture<Any, Any>(
            prepareSpeech = { speech },
            prepareRoute = { throw IllegalStateException("second participant preparation failed") },
            awaitSpeech = { assertSame(speech, it); events += "speech-ack"; true },
            awaitRoute = { assertNull(it); events += "missing-route-ack"; false },
            speechIsCurrent = { assertSame(speech, it); true },
            routeIsCurrent = { fail("No route preparation exists"); true },
        )
        assertFalse(captured.awaitOriginalAcknowledgements())
        assertFalse(captured.isCurrent())
        assertEquals(listOf("speech-ack", "missing-route-ack"), events)
    }

    @Test fun failedFirstPreparationDoesNotCreateOrWaitForAnySecondParticipant() {
        val failure = IllegalStateException("first participant preparation failed")
        val observed = assertThrows(IllegalStateException::class.java) {
            PreparedPrivacyStartupReset.capture<Any, Any>(
                prepareSpeech = { throw failure },
                prepareRoute = { fail("No first preparation exists"); null },
                awaitSpeech = { fail("No first ACK exists"); false },
                awaitRoute = { fail("No second ACK exists"); false },
                speechIsCurrent = { false }, routeIsCurrent = { false },
            )
        }
        assertSame(failure, observed)
    }

    @Test fun failedSpeechDrainStillClosesAllAcquiredWorkerResources() {
        val f = PrivacyStartupFixture()
        var closed = false
        f.resources.acquireOnWorker({ Any() }, { closed = true })
        f.drainFailure = IllegalStateException("drain")
        f.controller.closeResourcesOnWorker()
        assertTrue(closed)
        assertEquals(listOf("speech-drain", "legacy-close"), f.events)
    }

    @Test fun everyFailedWorkerAcquisitionClosesOnlyTheAcquiredPrefixAndNeverRunsReady() {
        repeat(5) { failAt ->
            val f = PrivacyStartupFixture()
            val released = mutableListOf<Int>()
            f.controller.startInspection({
                repeat(5) { index ->
                    f.resources.acquireOnWorker({ if (index == failAt) error("acquire") else Any() }, { released += index })
                }
                null
            }, { PrivacyStartupInspectionResult.READY }, { fail("Failed preparation cannot publish ready") })
            f.worker.runNext()
            f.runUi()
            assertEquals((0 until failAt).toList(), released)
            assertFalse(f.controller.isComplete)
            f.controller.closeResourcesOnWorker()
            assertEquals(failAt, released.size)
        }
    }

    @Test fun partialReadyFailureClosesSensorsAndSchedulesTheAcquiredWorkerResources() {
        val f = PrivacyStartupFixture()
        val released = mutableListOf<String>()
        f.controller.startInspection({
            f.resources.acquireOnWorker({ Any() }, { released += "worker" })
            null
        }, { PrivacyStartupInspectionResult.READY }, {
            f.resources.acquireOnUiThread({ Any() }, { released += "sensor" })
            error("next ready resource")
        })
        f.worker.runNext()
        f.runUi()
        assertFalse(f.controller.isComplete)
        assertEquals(listOf("sensor"), released)
        assertFalse(f.events.contains("voice"))
        assertFalse(f.events.contains("runtime"))
        f.cleanup.runNext()
        assertEquals(listOf("sensor", "worker"), released)
    }

    @Test fun backgroundReadyCannotApplyAfterAnotherActivityTakesItsLease() {
        val f = PrivacyStartupFixture()
        f.foreground = false
        f.start()
        f.worker.runNext()
        f.runUi()
        f.machine.attachActivity()
        f.foreground = true
        f.controller.completeReadyIfForeground()
        assertFalse(f.controller.isComplete)
        assertFalse(f.events.contains("ready"))
        assertFalse(f.events.contains("voice"))
        f.cleanup.runNext()
        assertTrue(f.events.contains("legacy-close"))
    }

    @Test fun constructionDoesNotResolveAndroidServicesOrExecuteAnyParticipant() {
        val f = PrivacyStartupFixture()
        assertTrue(f.events.isEmpty())
        assertFalse(f.controller.isComplete)
        assertFalse(f.controller.isDestroyed)
        assertEquals(0, f.worker.size)
        assertEquals(0, f.cleanup.size)
    }

    @Test fun foregroundReadyCompletesOnceBeforeVoicePreparationAndRuntimeResume() {
        val f = PrivacyStartupFixture()
        f.start()
        f.worker.runNext()
        assertFalse(f.controller.isComplete)
        f.runUi()
        assertEquals(listOf("prepare", "inspect:null", "ready", "voice", "runtime"), f.events)
        assertTrue(f.controller.isComplete)
        f.controller.completeReadyIfForeground()
        assertEquals(1, f.events.count { it == "ready" })
    }

    @Test fun backgroundKeepsTheOriginalReadyActionUntilForegroundReturns() {
        val f = PrivacyStartupFixture()
        f.foreground = false
        f.start()
        f.worker.runNext()
        f.runUi()
        assertFalse(f.controller.isComplete)
        assertFalse(f.events.contains("ready"))
        f.foreground = true
        f.controller.completeReadyIfForeground()
        assertEquals(listOf("prepare", "inspect:null", "ready", "voice", "runtime"), f.events)
    }

    @Test fun readyActionEnteringBackgroundStillPreparesVoiceButDoesNotResumeRuntime() {
        val f = PrivacyStartupFixture()
        f.controller.startInspection({ null }, { PrivacyStartupInspectionResult.READY }, {
            f.events += "ready"
            f.foreground = false
        })
        f.worker.runNext()
        f.runUi()
        assertEquals(listOf("ready", "voice"), f.events)
        assertTrue(f.controller.isComplete)
    }

    @Test fun destroyBeforeWorkerEntryRetiresGenerationWithoutDetachingTheForeignLease() {
        val f = PrivacyStartupFixture()
        f.start()
        f.controller.destroy()
        assertTrue(f.controller.isDestroyed)
        assertFalse(f.controller.isCurrent(1L))
        assertTrue(f.machine.activityLeaseIsCurrent(f.lease))
        f.worker.runNext()
        f.runUi()
        assertEquals(listOf("speech-drain", "legacy-close"), f.events)
        assertFalse(f.controller.isComplete)
    }

    @Test fun destroyAfterReadyDispatchPreventsLateForegroundCompletion() {
        val f = PrivacyStartupFixture()
        f.foreground = false
        f.start()
        f.worker.runNext()
        f.runUi()
        f.controller.destroy()
        f.controller.clearPendingReady()
        f.foreground = true
        f.controller.completeReadyIfForeground()
        assertFalse(f.events.contains("ready"))
        assertFalse(f.controller.isComplete)
    }

    @Test fun replacementActivityLeaseRejectsTheOldWorkerAndUiCallback() {
        val f = PrivacyStartupFixture()
        f.start()
        f.worker.runNext()
        f.machine.attachActivity()
        assertFalse(f.controller.isCurrent(1L))
        f.runUi()
        assertEquals(1, f.cleanup.size)
        f.cleanup.runNext()
        assertFalse(f.events.contains("ready"))
        assertTrue(f.events.contains("legacy-close"))
    }

    @Test fun resetAcknowledgementsAreAwaitedOutsideBothLocksThenTheSameOwnerIsRechecked() {
        val f = PrivacyStartupFixture()
        f.resetRequired = true
        f.beforeAwait = {
            assertFalse(Thread.holdsLock(f.processLock()))
            assertFalse(Thread.holdsLock(f.resourceLock()))
            assertFalse(Thread.holdsLock(f.inspectionLock()))
        }
        f.beforePrepareReset = {
            assertTrue(Thread.holdsLock(f.processLock()))
            assertTrue(Thread.holdsLock(f.resourceLock()))
        }
        f.beforeOwnerCheck = {
            assertTrue(Thread.holdsLock(f.processLock()))
            assertTrue(Thread.holdsLock(f.resourceLock()))
        }
        f.start()
        f.worker.runNext()
        f.runUi()
        assertEquals(listOf("prepare", "capture-reset", "await-speech", "await-route", "check-reset-owner",
            "inspect:true", "ready", "voice", "runtime"), f.events)
    }

    @Test fun failedSpeechAcknowledgementStillWaitsForRouteAndPassesFalseToInspection() {
        val f = PrivacyStartupFixture()
        f.resetRequired = true
        f.speechAcknowledged = false
        f.inspectionResult = { result ->
            assertEquals(false, result)
            PrivacyStartupInspectionResult.FINISH
        }
        f.start()
        f.worker.runNext()
        assertEquals(listOf("prepare", "capture-reset", "await-speech", "await-route", "check-reset-owner",
            "inspect:false", "speech-drain", "legacy-close"), f.events)
        f.runUi()
        assertEquals("finish", f.events.last())
        assertFalse(f.controller.isComplete)
    }

    @Test fun retiredPreparedOwnerCannotPublishAReadyResultEvenAfterSuccessfulAcknowledgements() {
        val f = PrivacyStartupFixture()
        f.resetRequired = true
        f.beforeAwait = { f.resetOwnerCurrent = false }
        f.start()
        f.worker.runNext()
        f.runUi()
        assertFalse(f.events.any { it.startsWith("inspect:") })
        assertFalse(f.events.contains("ready"))
        assertTrue(f.events.contains("legacy-close"))
        assertTrue(f.events.last().startsWith("status:"))
    }

    @Test fun closeSchedulingIsCoalescedAndTheDrainCannotSuppressTheIndependentTail() {
        val f = PrivacyStartupFixture()
        repeat(4) { f.controller.scheduleResourcesClose() }
        assertEquals(1, f.cleanup.size)
        f.cleanup.runNext()
        f.controller.scheduleResourcesClose()
        assertEquals(0, f.cleanup.size)
        f.controller.closeResourcesOnWorker()
        assertEquals(2, f.events.count { it == "speech-drain" })
        assertEquals(1, f.events.count { it == "legacy-close" })
    }

    @Test fun rejectedCloseExecutorRetiresOnlyTheScheduledFlagSoCloseCanBeRetried() {
        val f = PrivacyStartupFixture()
        f.cleanup.reject = true
        f.controller.scheduleResourcesClose()
        assertEquals(0, f.cleanup.size)
        f.cleanup.reject = false
        f.controller.scheduleResourcesClose()
        assertEquals(1, f.cleanup.size)
        f.cleanup.runNext()
        assertEquals(listOf("speech-drain", "legacy-close"), f.events)
    }

    @Test fun rejectedInspectionOrUiDispatchNeverPublishesReadyAndSchedulesCleanup() {
        val rejectedWorker = PrivacyStartupFixture()
        rejectedWorker.worker.reject = true
        rejectedWorker.start()
        assertEquals(1, rejectedWorker.cleanup.size)
        assertTrue(rejectedWorker.events.single().startsWith("status:"))
        val rejectedUi = PrivacyStartupFixture()
        rejectedUi.acceptUi = false
        rejectedUi.start()
        rejectedUi.worker.runNext()
        assertEquals(1, rejectedUi.cleanup.size)
        assertFalse(rejectedUi.events.contains("ready"))
    }

    @Test fun restoreAndPrivacyWorkersRespectReadinessDestructionAndRejection() {
        val f = PrivacyStartupFixture()
        f.controller.scheduleGatewayRestore()
        f.controller.schedulePrivacyOperations()
        f.worker.runAll()
        assertTrue(f.events.isEmpty())
        f.start(); f.worker.runNext(); f.runUi()
        f.controller.scheduleGatewayRestore()
        f.controller.schedulePrivacyOperations()
        f.worker.runAll()
        assertTrue(f.events.contains("gateway-restore"))
        assertTrue(f.events.contains("privacy-resume"))
        f.controller.destroy()
        val before = f.events.toList()
        f.controller.scheduleGatewayRestore()
        f.controller.schedulePrivacyOperations()
        f.worker.runAll()
        assertEquals(before, f.events)
        f.worker.reject = true
        f.controller.scheduleGatewayRestore()
        f.controller.schedulePrivacyOperations()
        assertEquals(listOf("gateway-rejected", "privacy-rejected"), f.events.takeLast(2))
    }

    @Test fun blockedPrivacyResumeIsPostedToUiAndDestructionRetiresThePostedWork() {
        val f = PrivacyStartupFixture()
        f.start(); f.worker.runNext(); f.runUi()
        assertTrue(f.machine.failClosed(f.lease, "controlled_startup_block"))
        f.controller.schedulePrivacyOperations()
        f.worker.runNext()
        assertFalse(f.events.contains("privacy-resume"))
        f.controller.destroy()
        f.runUi()
        assertFalse(f.events.contains("privacy-resume"))
    }

    @Test fun resetHandoffIsCoalescedAndStillPendingNeverRecreatesTheActivity() {
        val f = PrivacyStartupFixture()
        f.reserveReset()
        f.start(); f.worker.runNext(); f.runUi()
        f.controller.scheduleResetHandoff(1L)
        assertEquals(1, f.cleanup.size)
        f.cleanup.runNext(); f.runUi()
        assertFalse(f.events.contains("prepare"))
        assertFalse(f.events.contains("recreate"))
        assertTrue(f.events.last().contains("초기화를 완료하지 못해"))
    }

    @Test fun completedResetRecreatesOnlyTheCurrentGenerationAndActivityLease() {
        val f = PrivacyStartupFixture()
        val reservation = f.reserveReset()
        f.start(); f.worker.runNext(); f.runUi()
        assertTrue(f.machine.commitWorkerReset(reservation))
        f.cleanup.runNext(); f.runUi()
        assertEquals("recreate", f.events.last())
        val stale = PrivacyStartupFixture()
        val staleReservation = stale.reserveReset()
        stale.start(); stale.worker.runNext(); stale.runUi()
        assertTrue(stale.machine.commitWorkerReset(staleReservation))
        stale.cleanup.runNext()
        stale.controller.destroy()
        stale.runUi()
        assertFalse(stale.events.contains("recreate"))
    }

    @Test fun bothControllersUseOneProcessLockForPreparationButReleaseItDuringAcknowledgementWait() {
        val f = PrivacyStartupFixture()
        val other = PrivacyStartupFixture()
        assertSame(f.processLock(), other.processLock())
        val waitEntered = CountDownLatch(1)
        val releaseWait = CountDownLatch(1)
        val otherPrepared = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        f.resetRequired = true
        f.beforeAwait = { waitEntered.countDown(); check(releaseWait.await(2, TimeUnit.SECONDS)) }
        f.start()
        val blocked = Thread { runCatching { f.worker.runNext() }.onFailure(error::set) }
        blocked.start()
        try {
            assertTrue(waitEntered.await(1, TimeUnit.SECONDS))
            other.controller.startInspection({ otherPrepared.countDown(); PrivacyStartupInspectionResult.READY },
                { fail("Prepared READY must not inspect"); PrivacyStartupInspectionResult.FINISH }, {})
            other.worker.runNext()
            assertTrue(otherPrepared.await(1, TimeUnit.SECONDS))
        } finally {
            releaseWait.countDown()
            blocked.join(2_000L)
        }
        assertFalse(blocked.isAlive)
        assertNull(error.get())
    }

    @Test fun twoActivityPreparationsCannotEnterTheSharedProcessSectionConcurrently() {
        val first = PrivacyStartupFixture()
        val second = PrivacyStartupFixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        first.controller.startInspection({
            assertTrue(Thread.holdsLock(first.processLock()))
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            PrivacyStartupInspectionResult.READY
        }, { PrivacyStartupInspectionResult.FINISH }, {})
        second.controller.startInspection({ secondEntered.countDown(); PrivacyStartupInspectionResult.READY },
            { PrivacyStartupInspectionResult.FINISH }, {})
        val firstWorker = Thread { runCatching { first.worker.runNext() }.onFailure(error::set) }
        val secondWorker = Thread { runCatching { second.worker.runNext() }.onFailure(error::set) }
        firstWorker.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            secondWorker.start()
            assertFalse(secondEntered.await(50, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            firstWorker.join(2_000L)
            secondWorker.join(2_000L)
        }
        assertFalse(firstWorker.isAlive)
        assertFalse(secondWorker.isAlive)
        assertEquals(0L, secondEntered.count)
        assertNull(error.get())
    }
}

internal class PrivacyStartupFixture {
    val resources = PrivacyStartupResources()
    val events = mutableListOf<String>()
    val worker = QueuedExecutor()
    val cleanup = QueuedExecutor()
    val machine = AccountDeletionStateMachine()
    val lease = machine.attachActivity()
    private val ui = ArrayDeque<() -> Unit>()
    var foreground = true
    var drainFailure: Exception? = null
    var acceptUi = true
    var resetRequired = false
    var resetOwnerCurrent = true
    var speechAcknowledged = true
    var routeAcknowledged = true
    var beforeAwait: () -> Unit = {}
    var beforePrepareReset: () -> Unit = {}
    var beforeOwnerCheck: () -> Unit = {}
    var inspectionResult: (Boolean?) -> PrivacyStartupInspectionResult = { PrivacyStartupInspectionResult.READY }
    val controller = PrivacyStartupInspectionController(worker, { cleanup }, { callback ->
        if (acceptUi) ui.addLast(callback)
        acceptUi
    }, { machine }, { lease }, { foreground }, object : PrivacyStartupResetPreparation {
        override fun isRequired() = resetRequired
        override fun prepare(): PreparedPrivacyStartupReset {
            beforePrepareReset()
            events += "capture-reset"
            return object : PreparedPrivacyStartupReset {
                override fun awaitOriginalAcknowledgements(): Boolean {
                    beforeAwait()
                    events += "await-speech"
                    val speech = speechAcknowledged
                    events += "await-route"
                    val route = routeAcknowledged
                    return speech && route
                }
                override fun isCurrent(): Boolean {
                    beforeOwnerCheck()
                    events += "check-reset-owner"
                    return resetOwnerCurrent
                }
            }
        }
    }, object : PrivacyStartupActions {
        override fun showStatus(message: String) { events += "status:$message" }
        override fun finishActivity() { events += "finish" }
        override fun recreateActivity() { events += "recreate" }
        override fun drainSpeechOutputOnWorker() { events += "speech-drain"; drainFailure?.let { throw it } }
        override fun closeLegacyResourcesOnWorker() { events += "legacy-close"; resources.closeOnWorker() }
        override fun closeReadyResourcesOnUiThread() { resources.closeOnUiThread() }
        override fun prepareHandsFreeVoiceModel() { events += "voice" }
        override fun resumeWalkRuntime() { events += "runtime" }
        override fun restoreGatewaySessionOnWorker() { events += "gateway-restore" }
        override fun rejectGatewayRestore() { events += "gateway-rejected" }
        override fun resumePrivacyControlOperations() { events += "privacy-resume" }
        override fun rejectPrivacyControlOperations() { events += "privacy-rejected" }
    })

    fun start() = controller.startInspection({ events += "prepare"; null }, {
        events += "inspect:$it"
        inspectionResult(it)
    }, { events += "ready" })
    fun runUi() { ui.removeFirst().invoke() }
    fun inspectionLock(): Any = lock("privacyStartupInspectionLock")
    fun resourceLock(): Any = lock("privacyStartupResourceLock")
    fun processLock(): Any = PrivacyStartupInspectionController::class.java
        .getDeclaredField("PRIVACY_STARTUP_PROCESS_LOCK").apply { isAccessible = true }.get(null)
    private fun lock(name: String): Any = PrivacyStartupInspectionController::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(controller)

    fun reserveReset(): AccountDeletionResetReservation {
        val pending = AccountDeletionJournal.pending("https://gateway.example.test", "installation_0001",
            "account_delete_request_0001", "2026-10-06T00:00:00Z")
        val complete = pending.copy(phase = AccountDeletionPhase.COMPLETED, serverRevision = 1L,
            acceptedAt = "2026-10-06T00:01:00Z", accountGeneration = 1L, tombstoneId = "tombstone_0001",
            requestReceiptSha256 = "a".repeat(64), receiptSha256 = "b".repeat(64),
            deviceEvidenceId = "device_evidence_0001", deviceEvidenceSha256 = "c".repeat(64),
            deviceEvidenceExpectedStatusRevision = 1L, deviceEvidenceResult = "DELETED",
            deviceEvidenceCompletedAt = "2026-10-06T00:02:00Z", deviceEvidenceAcknowledged = true,
            items = pending.items.mapValues { (_, value) -> value.copy(state = DeletionItemState.COMPLETED,
                evidenceSha256 = "d".repeat(64), terminalAt = "2026-10-06T00:02:00Z") })
        check(machine.restore(complete, lease))
        val attempt = requireNotNull(machine.beginWorkerAttempt(complete, lease))
        return requireNotNull(machine.reserveWorkerResetIfCurrent(attempt))
    }

    class QueuedExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        var reject = false
        val size: Int get() = queue.size
        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException("controlled startup executor rejection")
            queue.addLast(command)
        }
        fun runNext() { queue.removeFirst().run() }
        fun runAll() { while (queue.isNotEmpty()) runNext() }
    }
}
