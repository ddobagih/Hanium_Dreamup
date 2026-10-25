package kr.co.hanium.dreamup.walksafe.report.runtime

import java.util.UUID
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.report.*
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ReportQueueDrainControllerTest {
    @After fun resetPrivacyAuthority() { ReportPrivacyConsentSession.resetForNewEnrollment() }

    @Test fun accountPurgeDeadlineIncludesCancellationBlockedByReceiptDeletionMonitor() {
        val control = Executors.newSingleThreadExecutor()
        val f = Fixture(liveCancellationExecutor = control)
        var observed: QueuedReport? = null
        f.status = { observed = it; receipt(it) }
        f.start()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        f.storage.beforeDelete = { entered.countDown(); check(resume.await(10L, TimeUnit.SECONDS)) }
        val worker = Thread { f.executor.takeNext().run() }
        val purge = Executors.newSingleThreadExecutor()
        try {
            worker.start()
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            f.mainThread = false
            val started = System.nanoTime()
            val acknowledgement = purge.submit<Boolean> { f.controller.onAccountDeleted(100L) }
            assertFalse("The timeout includes dispatch of a cancellation blocked on the receipt monitor",
                acknowledgement.get(750L, TimeUnit.MILLISECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 750L)
            assertTrue(worker.isAlive)
            assertEquals(0, f.storage.purgeCalls)
            assertNotNull(requireNotNull(observed).payload.imageJpeg())
            assertFalse(f.controller.cancelAndAwaitDrainTermination(100L))
            assertNull(ReportQueueDrainCoordinator(f.store).startNextWithResult(
                { error("Deletion barrier must keep replacement coordinators out") },
                object : ReportQueueTransport {
                    override fun statusCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt?> = error("fenced")
                    override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> = error("fenced")
                },
            ))
            assertEquals(0, f.storage.purgeCalls)
            // The configured 100ms deadline already returned while the same monitor stays held for six seconds.
            assertFalse(resume.await(6L, TimeUnit.SECONDS))
            assertTrue(worker.isAlive)
            assertEquals(0, f.storage.purgeCalls)
            resume.countDown(); worker.join(2_000L)
            assertFalse(worker.isAlive)
            assertThrows(IllegalStateException::class.java) { requireNotNull(observed).payload.imageJpeg() }
            f.storage.beforeDelete = null
            assertTrue(f.controller.onAccountDeleted(100L))
            assertEquals(1, f.storage.purgeCalls)
            assertEquals(0, f.uploadCalls)
            assertTrue(f.store.queuedReports().isEmpty())
        } finally {
            resume.countDown(); worker.join(2_000L)
            f.storage.beforeDelete = null; f.mainThread = false; f.controller.onAccountDeleted()
            f.controller.shutdownExecutor(); control.shutdownNow(); purge.shutdownNow()
        }
    }

    @Test fun nestedTransportCancellationRunsBeforeTheCoordinatorMonitorIsReleased() {
        val f = Fixture()
        f.enqueue().close()
        val control = Executors.newSingleThreadExecutor()
        val coordinator = ReportQueueDrainCoordinator(f.store, cancellationExecutor = control)
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val resumeTransport = CountDownLatch(1)
        val monitorEntered = CountDownLatch(1)
        val resumeMonitor = CountDownLatch(1)
        var observed: QueuedReport? = null
        val transport = object : ReportQueueTransport {
            override fun statusCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt?> {
                observed = report
                return CancellableNetworkCall(executeBlock = {
                    entered.countDown(); check(resumeTransport.await(2L, TimeUnit.SECONDS)); null
                }, cancelBlock = { cancelled.countDown() })
            }
            override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> = error("fenced")
        }
        val call = requireNotNull(coordinator.startNextWithResult({
            ReportQueueDrainContext(
                walkState = WalkSessionState.ENDED, appForeground = true, stationary = true,
                networkAllowed = true, consentAllowed = true, automaticReportingAllowed = true,
                authorityAllowed = true, reporterActorId = ACTOR, walkSessionId = WALK,
                consentReceiptSha256 = f.confirmation.backendConsentReceiptSha256, movementGeneration = 0L,
                accountGeneration = f.session.backendAccountGeneration,
            )
        }, transport))
        val worker = Executors.newSingleThreadExecutor()
        val task = worker.submit<ReportQueueDrainResult> { call.execute() }
        val monitor = Thread { synchronized(coordinator) {
            monitorEntered.countDown(); check(resumeMonitor.await(2L, TimeUnit.SECONDS))
        } }
        try {
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            monitor.start(); assertTrue(monitorEntered.await(2L, TimeUnit.SECONDS))
            assertFalse(coordinator.cancelAndAwaitDrainTermination(100L))
            assertTrue("Socket cancellation must precede coordinator-monitor acquisition",
                cancelled.await(500L, TimeUnit.MILLISECONDS))
            assertTrue(call.isCancelled())
            assertTrue(monitor.isAlive)
            assertFalse(task.isDone)
            assertNotNull(requireNotNull(observed).payload.imageJpeg())
            assertEquals(0, f.storage.purgeCalls)
            resumeMonitor.countDown(); monitor.join(2_000L)
            resumeTransport.countDown()
            assertEquals(ReportQueueDrainOutcome.CANCELLED, task.get(2L, TimeUnit.SECONDS).outcome)
            assertThrows(IllegalStateException::class.java) { requireNotNull(observed).payload.imageJpeg() }
            assertTrue(coordinator.onAccountDeleted(100L))
            assertEquals(1, f.storage.purgeCalls)
        } finally {
            resumeMonitor.countDown(); resumeTransport.countDown(); monitor.join(2_000L)
            worker.shutdownNow(); coordinator.onAccountDeleted(); control.shutdownNow()
        }
    }

    @Test fun blockedSerialDrainIsPhysicallyCancelledByAnIndependentControlWorker() {
        val drain = Executors.newSingleThreadExecutor()
        val control = Executors.newSingleThreadExecutor()
        val f = Fixture(drain, control)
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        f.status = { entered.countDown(); check(released.await(2L, TimeUnit.SECONDS)); null }
        f.onStatusCancelled = { cancelled.countDown(); released.countDown() }
        try {
            f.start()
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            f.controller.cancelReportQueueDrain()
            assertTrue("Physical cancellation must run while serial drain is still inside transport", cancelled.await(500L, TimeUnit.MILLISECONDS))
            drain.submit {}.get(2L, TimeUnit.SECONDS)
            assertEquals(0, f.uploadCalls)
            assertEquals(1, f.store.queuedReports().size)
            assertFalse(f.controller.onAccountDeleted()) // UI only fences; no physical-deletion ACK.
            f.mainThread = false
            assertTrue(f.controller.onAccountDeleted())
        } finally { released.countDown(); f.controller.shutdownExecutor(); control.shutdownNow(); drain.shutdownNow() }
    }

    @Test fun uiAccountFenceDoesNotWaitForAStalledPreparingStoreAndNoLatePostCanStart() {
        val f = Fixture()
        f.start()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        f.storage.beforeLockedAccess = { entered.countDown(); check(resume.await(2L, TimeUnit.SECONDS)) }
        val work = f.executor.takeNext()
        val worker = Thread { work.run() }
        val ui = Executors.newSingleThreadExecutor()
        try {
            worker.start()
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            val fence = ui.submit<Boolean> { f.controller.cancelReportQueueDrain(); f.controller.onAccountDeleted() }
            assertFalse(fence.get(500L, TimeUnit.MILLISECONDS))
            assertFalse(f.controller.cancelAndAwaitDrainTermination(1L))
            assertTrue(worker.isAlive)
            resume.countDown(); worker.join(2_000L)
            assertFalse(worker.isAlive)
            assertEquals(0, f.statusCalls); assertEquals(0, f.uploadCalls)
            f.storage.beforeLockedAccess = null
            f.mainThread = false
            assertTrue(f.controller.onAccountDeleted())
            assertTrue(f.store.queuedReports().isEmpty())
        } finally {
            resume.countDown(); worker.join(2_000L); ui.shutdownNow()
            f.storage.beforeLockedAccess = null; f.mainThread = false; f.controller.onAccountDeleted()
        }
    }

    @Test fun uiFenceAndOrdinaryConsentCancelDoNotWaitForReceiptDeletionMonitor() {
        val f = Fixture()
        f.status = { receipt(it) }
        f.start()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        f.storage.beforeDelete = { entered.countDown(); check(resume.await(2L, TimeUnit.SECONDS)) }
        val worker = Thread { f.executor.takeNext().run() }
        val ui = Executors.newSingleThreadExecutor()
        val purge = Executors.newSingleThreadExecutor()
        try {
            worker.start(); assertTrue(entered.await(2L, TimeUnit.SECONDS))
            val fence = ui.submit<Boolean> { f.controller.cancelReportQueueDrain(); f.controller.onAccountDeleted() }
            assertFalse(fence.get(500L, TimeUnit.MILLISECONDS))
            f.mainThread = false
            val acknowledgement = purge.submit<Boolean> { f.controller.onAccountDeleted() }
            assertFalse(acknowledgement.isDone)
            assertTrue(worker.isAlive)
            resume.countDown(); worker.join(2_000L)
            assertFalse(worker.isAlive)
            assertTrue(acknowledgement.get(2L, TimeUnit.SECONDS))
            assertEquals(0, f.uploadCalls)
            assertTrue(f.store.queuedReports().isEmpty())
        } finally {
            resume.countDown(); worker.join(2_000L); ui.shutdownNow(); purge.shutdownNow()
            f.storage.beforeDelete = null; f.mainThread = false; f.controller.onAccountDeleted()
        }
    }

    @Test fun triggerRequiresForegroundReadyProbeAndCurrentPrivacyConsent() {
        val f = Fixture()
        f.foreground = false
        assertNull(f.trigger())
        assertTrue(f.events.isEmpty())
        f.foreground = true
        f.networkReady = false
        assertNull(f.trigger())
        f.networkReady = true
        ReportPrivacyConsentSession.withdraw()
        assertNull(f.trigger())
        assertFalse(f.events.contains("gateway"))
    }

    @Test fun triggerRejectsMissingActorAndBlockedOrReplacedGatewayAuthority() {
        val f = Fixture()
        f.actor = null
        assertNull(f.trigger())
        f.actor = ACTOR
        f.storageBlocked = true
        assertNull(f.trigger())
        f.storageBlocked = false
        f.deletionOnly = true
        assertNull(f.trigger())
        f.deletionOnly = false
        f.session = session("other-actor")
        assertNull(f.trigger())
        f.session = session(ACTOR, GatewaySessionScope.ACCOUNT_DELETION_RECOVERY)
        assertNull(f.trigger())
        f.session = session()
        f.originApproved = false
        assertNull(f.trigger())
        f.originApproved = true
        assertNotNull(f.trigger())
    }

    @Test fun triggerReadsTransportBeforePreferenceAndThenBinding() {
        val f = Fixture()
        assertNotNull(f.trigger())
        assertEquals(listOf("transport", "preference", "binding"), f.networkReads())
        f.events.clear()
        f.transport = ActiveNetworkTransport.CELLULAR
        assertNull(f.trigger())
        assertEquals(listOf("transport", "preference"), f.networkReads())
    }

    @Test fun recoveryReadsBindingBeforePreferenceAndShortCircuitsWhenBindingDisagrees() {
        val f = Fixture()
        assertTrue(f.controller.reportQueueRecoveryBaseGatesAvailable())
        assertEquals(listOf("transport", "binding", "preference"), f.networkReads())
        f.events.clear()
        f.binding = cellularBinding()
        assertFalse(f.controller.reportQueueRecoveryBaseGatesAvailable())
        assertEquals(listOf("transport", "binding"), f.networkReads())
    }

    @Test fun replacementPermissionPolicyIsReadForCellularAdmission() {
        val f = Fixture()
        f.transport = ActiveNetworkTransport.CELLULAR
        f.binding = cellularBinding()
        assertNull(f.trigger())
        f.policy = PermissionSessionPolicy().apply {
            setMobileNetworkPreference(MobileNetworkPreference.ALLOW_CELLULAR)
        }
        assertNotNull(f.trigger())
        assertTrue(f.controller.reportQueueRecoveryBaseGatesAvailable())
        f.policy = PermissionSessionPolicy()
        assertNull(f.trigger())
    }

    @Test fun offlineOtherMissingAndWrongTransportBindingsCannotOpenDrain() {
        val f = Fixture()
        f.policy.setMobileNetworkPreference(MobileNetworkPreference.ALLOW_CELLULAR)
        listOf(ActiveNetworkTransport.OFFLINE, ActiveNetworkTransport.OTHER).forEach {
            f.transport = it
            assertNull(f.trigger())
            assertFalse(f.controller.reportQueueRecoveryBaseGatesAvailable())
        }
        f.transport = ActiveNetworkTransport.WIFI
        f.binding = null
        assertNull(f.trigger())
        f.binding = cellularBinding()
        assertNull(f.trigger())
    }

    @Test fun recoveryCapacityGuardPrecedesPrivacyAndNetworkReadiness() {
        val f = Fixture()
        f.capacityAvailable = false
        assertFalse(f.controller.reportQueueRecoveryBaseGatesAvailable())
        assertTrue(f.events.isEmpty())
        f.capacityAvailable = true
        ReportPrivacyConsentSession.withdraw()
        assertFalse(f.controller.reportQueueRecoveryBaseGatesAvailable())
        assertTrue(f.events.isEmpty())
    }

    @Test fun emptyQueueAndUnrecordedWalkNeverInstallTrigger() {
        val f = Fixture()
        val trigger = requireNotNull(f.trigger())
        assertFalse(f.controller.startBoundedReportQueueDrain(trigger))
        f.enqueue()
        assertFalse(f.controller.startBoundedReportQueueDrain(trigger))
        assertFalse(f.controller.hasActiveDrain())
        assertEquals(0, f.executor.tasks.size)
    }

    @Test fun installingWithFreshGateFailureClearsEpochAndCancelsBeforeSchedulingWorker() {
        val f = Fixture()
        f.enqueue()
        f.recordWalk()
        val trigger = requireNotNull(f.trigger())
        f.foreground = false
        assertFalse(f.controller.startBoundedReportQueueDrain(trigger))
        assertEquals(listOf("clear", "clear", "stop"), f.effectEvents())
        assertFalse(f.controller.hasActiveDrain())
        assertEquals(0, f.executor.tasks.size)
    }

    @Test fun rejectedExecutorCancelsInstalledTriggerAndDoesNotLeakOwnership() {
        val f = Fixture()
        f.enqueue()
        f.recordWalk()
        f.executor.reject = true
        assertFalse(f.controller.startBoundedReportQueueDrain(requireNotNull(f.trigger())))
        assertFalse(f.controller.hasActiveDrain())
        assertEquals(listOf("clear", "clear", "stop"), f.effectEvents())
    }

    @Test fun equalTriggerCopyCannotFinishOrReplaceTheActualOwner() {
        val f = Fixture()
        val trigger = f.start()
        assertNull(f.trigger())
        assertFalse(f.controller.startBoundedReportQueueDrain(trigger.copy()))
        assertFalse(f.controller.finishReportQueueDrain(trigger.copy()))
        assertTrue(f.controller.hasActiveDrain())
        assertTrue(f.controller.finishReportQueueDrain(trigger))
        assertFalse(f.controller.finishReportQueueDrain(trigger))
        assertEquals(1, f.events.count { it == "stop" })
    }

    @Test fun pausedTransitionCancelsWithoutClearingThePlatformStepEpochAgain() {
        val f = Fixture()
        val trigger = f.start()
        f.events.clear()
        f.walk = f.walk.copy(state = WalkSessionState.PAUSED,
            recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED)
        f.recordWalk()
        assertFalse(f.controller.hasActiveDrain())
        assertEquals(listOf("stop"), f.effectEvents())
        assertFalse(f.controller.reportQueueDrainContext(trigger).stationary)
        f.executor.runNext()
        assertEquals(1, f.store.queuedReports().size)
        assertEquals(0, f.statusCalls)
    }

    @Test fun differentWalkSessionCancelsEvenWhenItsStateAllowsPostWalkTransfer() {
        val f = Fixture()
        f.start()
        f.walk = f.walk.copy(epoch = WalkRuntimeEpoch(OTHER_WALK, 0L))
        f.recordWalk()
        assertFalse(f.controller.hasActiveDrain())
        assertEquals(1, f.events.count { it == "stop" })
    }

    @Test fun explicitCancelIsIdempotentAndPreventsQueuedWorkerNetworkCallbacks() {
        val f = Fixture()
        val trigger = f.start()
        f.controller.cancelReportQueueDrain()
        f.controller.cancelReportQueueDrain()
        assertEquals(2, f.events.count { it == "clear" })
        assertEquals(1, f.events.count { it == "stop" })
        assertFalse(f.controller.reportQueueDrainContext(trigger).appForeground)
        f.executor.runNext()
        assertEquals(1, f.transportFactories)
        assertEquals(0, f.statusCalls)
        assertEquals(1, f.store.queuedReports().size)
        assertTrue(f.posts.isEmpty())
    }

    @Test fun networkChangeRunsRawCancellationBeforeQueueCancelAndReschedulesEvenWhenIdle() {
        val f = Fixture()
        val stale = f.start()
        f.events.clear()
        f.controller.onReportQueueDrainNetworkChanged()
        assertEquals(listOf("raw", "clear", "stop", "schedule"), f.effectEvents())
        val next = requireNotNull(f.trigger())
        assertEquals(stale.networkGeneration + 1, next.networkGeneration)
        assertEquals(stale.movementGeneration + 1, next.movementGeneration)
        assertFalse(f.controller.startBoundedReportQueueDrain(stale))
        f.events.clear()
        f.controller.onReportQueueDrainNetworkChanged()
        assertEquals(listOf("raw", "schedule"), f.effectEvents())
        assertEquals(next.networkGeneration + 1, requireNotNull(f.trigger()).networkGeneration)
    }

    @Test fun contextUsesFreshActorGatewayAndEachReadyProbeBeforeCurrentPolicy() {
        val f = Fixture()
        val trigger = f.start()
        f.events.clear()
        val current = f.controller.reportQueueDrainContext(trigger)
        assertTrue(current.authorityAllowed && current.networkAllowed && current.consentAllowed)
        assertEquals(listOf("gateway", "actor", "ready", "transport", "ready", "binding", "preference"),
            f.events.filter { it in listOf("gateway", "actor", "ready", "transport", "binding", "preference") })
        f.actor = "other-actor"
        assertFalse(f.controller.reportQueueDrainContext(trigger).authorityAllowed)
        f.actor = ACTOR
        f.gatewayGeneration++
        assertFalse(f.controller.reportQueueDrainContext(trigger).authorityAllowed)
        f.gatewayGeneration--
        f.session = session()
        assertFalse(f.controller.reportQueueDrainContext(trigger).authorityAllowed)
    }

    @Test fun contextRejectsNetworkIdentityAndNewConsentEvenWhenValuesOtherwiseMatch() {
        val f = Fixture()
        val trigger = f.start()
        f.binding = wifiBinding()
        assertFalse(f.controller.reportQueueDrainContext(trigger).networkAllowed)
        f.binding = trigger.networkBinding
        f.confirmation = confirmation(revision = 2L)
        assertEquals(IntegratedConsentApplyResult.APPLIED, f.integrated.applyConfirmation(f.confirmation))
        assertFalse(f.controller.reportQueueDrainContext(trigger).consentAllowed)
        assertFalse(f.controller.reportQueueDrainContext(trigger).automaticReportingAllowed)
    }

    @Test fun maybeStartPostsToUiAndOnlyReadsRuntimeAfterItRuns() {
        val f = Fixture()
        f.enqueue()
        f.mainThread = false
        f.controller.maybeStartPostWalkReportQueueDrain()
        assertEquals(listOf("ui-post"), f.events)
        assertEquals(0, f.executor.tasks.size)
        f.runUi()
        assertTrue(f.controller.hasActiveDrain())
        assertEquals(1, f.executor.tasks.size)
        assertTrue(f.events.indexOf("steps") < f.events.indexOf("clear"))
    }

    @Test fun statusExactAckDeletesWithoutPostingAndExplicitNoticeSpeaksBeforeFullResult() {
        val f = Fixture()
        f.status = { receipt(it) }
        f.start()
        f.executor.runNext()
        assertTrue(f.store.queuedReports().isEmpty())
        assertEquals(1, f.statusCalls)
        assertEquals(0, f.uploadCalls)
        assertEquals(0, f.persisted.size)
        f.runPosts()
        assertEquals(listOf("speak", "show"), f.effectEvents().takeLast(2))
        assertEquals(listOf("이전에 접수된 신고를 확인했습니다. 중복 접수하지 않았습니다."), f.shown)
    }

    @Test fun typedCreatedAndReplayedUploadOutcomesKeepDistinctMessages() {
        listOf(ReportCreationOutcome.CREATED, ReportCreationOutcome.REPLAYED).forEach { outcome ->
            val f = Fixture()
            f.creation = outcome
            f.start()
            f.executor.runNext()
            assertTrue(f.store.queuedReports().isEmpty())
            assertEquals(1, f.uploadCalls)
            f.runPosts()
            assertEquals(if (outcome == ReportCreationOutcome.CREATED) "신고가 접수되었습니다."
                else "이전에 접수된 신고를 확인했습니다. 중복 접수하지 않았습니다.", f.shown.single())
        }
    }

    @Test fun mismatchedExactAckRetainsPayloadAndNeverCommitsAutomaticCooldown() {
        listOf(false, true).forEach { statusAck ->
            val f = Fixture()
            if (statusAck) f.status = { receipt(it).copy(payloadBytes = it.payload.payloadBytes + 1L) }
            else f.uploadReceipt = { receipt(it).copy(payloadSha256 = "f".repeat(64)) }
            f.start(ReportQueuePriority.AUTOMATIC)
            f.executor.runNext()
            assertEquals(1, f.store.queuedReports().size)
            assertTrue(f.persisted.isEmpty())
            assertTrue(f.posts.isEmpty())
        }
    }

    @Test fun automaticAckPersistsCooldownWithLeaseBeforeDeletingAndRemainsSilent() {
        val f = Fixture()
        f.onPersist = {
            assertEquals(1, f.store.queuedReports().size)
            assertEquals(AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS, it.cooldownRemainingAtMarkMs)
            assertEquals(f.wall, it.lastUploadedAtMs)
            assertEquals(f.elapsed, it.cooldownMonotonicMarkMs)
            assertEquals(7, it.bootCount)
            val scope = requireNotNull(f.store.queuedReports().single().automaticCooldownScopeOrNull())
            val blocked = f.attempt.acquire(scope, f.wall, true) as AndroidReportAttemptResult.Blocked
            assertEquals(AndroidReportAttemptBlockReason.IN_FLIGHT, blocked.reason)
            true
        }
        f.start(ReportQueuePriority.AUTOMATIC)
        f.executor.runNext()
        assertTrue(f.store.queuedReports().isEmpty())
        assertEquals(1, f.attempt.successfulCooldowns(f.wall).size)
        assertEquals(listOf(f.attempt, f.attempt, f.attempt), f.attemptReads)
        f.runPosts()
        assertTrue(f.spoken.isEmpty())
        assertEquals(listOf("신고가 접수되었습니다."), f.shown)
    }

    @Test fun failedOrThrowingCooldownCommitRetainsPayloadAndReleasesLease() {
        listOf(false, true).forEach { throws ->
            val f = Fixture()
            f.onPersist = { if (throws) error("private write failure") else false }
            f.start(ReportQueuePriority.AUTOMATIC)
            f.executor.runNext()
            assertEquals(1, f.store.queuedReports().size)
            assertTrue(f.attempt.successfulCooldowns(f.wall).isEmpty())
            assertEquals(listOf(f.attempt, f.attempt), f.attemptReads)
            val scope = requireNotNull(f.store.queuedReports().single().automaticCooldownScopeOrNull())
            val admission = f.attempt.acquire(scope, f.wall, true)
            assertTrue(admission is AndroidReportAttemptResult.Allowed)
            f.attempt.release((admission as AndroidReportAttemptResult.Allowed).lease)
        }
    }

    @Test fun cancellationDuringStatusAndDuringUploadCannotDeleteExactReceipt() {
        listOf(false, true).forEach { duringUpload ->
            val f = Fixture()
            if (duringUpload) f.uploadReceipt = { f.controller.cancelReportQueueDrain(); receipt(it) }
            else f.status = { f.controller.cancelReportQueueDrain(); receipt(it) }
            f.start()
            f.executor.runNext()
            assertEquals(1, f.store.queuedReports().size)
            assertTrue(f.posts.isEmpty())
            assertFalse(f.controller.hasActiveDrain())
        }
    }

    @Test fun networkCancellationBetweenAckAndCooldownDeleteRetainsPayloadAfterCommit() {
        val f = Fixture()
        f.onPersist = { f.controller.onReportQueueDrainNetworkChanged(); true }
        f.start(ReportQueuePriority.AUTOMATIC)
        f.executor.runNext()
        assertEquals(1, f.persisted.size)
        assertEquals(1, f.store.queuedReports().size)
        assertTrue(f.posts.isEmpty())
    }

    @Test fun crossThreadCancellationWhileStatusIsInFlightPreservesPayloadAndStopsBatch() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.status = { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); receipt(it) }
        f.start()
        val task = f.executor.takeNext()
        val worker = Thread(task)
        worker.start()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        f.controller.cancelReportQueueDrain()
        release.countDown()
        worker.join(3_000L)
        assertFalse(worker.isAlive)
        assertEquals(1, f.store.queuedReports().size)
        assertEquals(0, f.uploadCalls)
        assertTrue(f.posts.isEmpty())
    }

    @Test fun drainUsesInitialCountAndDoesNotConsumeLaterQueueEntries() {
        val f = Fixture()
        val first = f.enqueue()
        f.status = { f.enqueue(); receipt(it) }
        f.recordWalk()
        assertTrue(f.controller.startBoundedReportQueueDrain(requireNotNull(f.trigger())))
        f.executor.runNext()
        assertEquals(1, f.statusCalls)
        val remaining = f.store.queuedReports().single()
        assertNotEquals(first.payload.reportId, remaining.payload.reportId)
    }

    @Test fun delayedNoticeRejectsEpochActorGatewayConsentForegroundAndActivityLeaseChanges() {
        val mutations: List<(Fixture) -> Unit> = listOf(
            { it.walk = it.walk.copy(epoch = WalkRuntimeEpoch(WALK, 1L)) },
            { it.actor = "other-actor" },
            { it.gatewayGeneration++ },
            { it.session = session() },
            { it.storageBlocked = true },
            { it.deletionOnly = true },
            { it.foreground = false },
            { it.walk = it.walk.copy(isForeground = false) },
            { ReportPrivacyConsentSession.withdraw() },
            { it.integrated.applyConfirmation(confirmation(revision = 2L)) },
            { it.activityLease = AccountDeletionActivityLease(2L) },
        )
        mutations.forEach { mutate ->
            val f = Fixture()
            f.start()
            f.executor.runNext()
            assertEquals(1, f.posts.size)
            mutate(f)
            f.runPosts()
            assertTrue(f.shown.isEmpty())
            assertTrue(f.spoken.isEmpty())
        }
    }

    @Test fun delayedNoticeRejectsNewDrainOrNetworkGenerationAndActiveSpeechOnlySuppressesSpeech() {
        listOf(false, true).forEach { newDrain ->
            val f = Fixture()
            f.start()
            f.executor.runNext()
            if (newDrain) f.start() else f.controller.onReportQueueDrainNetworkChanged()
            f.runPosts()
            assertTrue(f.shown.isEmpty())
        }
        val f = Fixture()
        f.handsFreeOutput = true
        f.start()
        f.executor.runNext()
        f.runPosts()
        assertTrue(f.spoken.isEmpty())
        assertEquals(listOf("신고가 접수되었습니다."), f.shown)
    }

    @Test fun walkInProgressRequestsOnlyExistingRetryAndKeepsQueuedPayload() {
        val f = Fixture()
        f.uploadReceipt = {
            throw ReportUploadHttpException(ReportUploadError(409,
                """{"detail":{"code":"report_walk_in_progress","message":"later"}}"""))
        }
        f.start()
        f.executor.runNext()
        assertEquals(1, f.store.queuedReports().size)
        assertEquals(1, f.events.count { it == "retry" })
        assertTrue(f.posts.isEmpty())
    }

    @Test fun revokedAutomaticConsentLeavesExplicitReportEligibleAndPeerCommandsUseSharedStore() {
        val f = Fixture()
        f.enqueue(ReportQueuePriority.AUTOMATIC)
        f.enqueue(ReportQueuePriority.EXPLICIT)
        assertTrue(f.controller.onAutomaticReportingRevoked(f.confirmation.backendConsentReceiptSha256))
        assertEquals(listOf(ReportQueuePriority.EXPLICIT), f.store.queuedReports().map { it.priority })
        f.mainThread = false // Physical purge runs on the I/O worker; UI cancellation returns pending.
        assertTrue(f.controller.onAccountDeleted())
        assertTrue(f.store.queuedReports().isEmpty())
        f.controller.shutdownExecutor()
        assertTrue(f.executor.isShutdown)
    }

    @Test fun automaticCooldownUsesCurrentAttemptStoreForMarkAndReleaseInsteadOfPinningAcquireStore() {
        val f = Fixture()
        val report = f.enqueue(ReportQueuePriority.AUTOMATIC)
        val first = f.attempt
        val replacement = attemptStore()
        f.onPersist = { f.attempt = replacement; true }
        assertTrue(f.controller.persistAutomaticReportCooldownBeforeQueueDelete(report))
        assertEquals(listOf(first, replacement, replacement), f.attemptReads)
        assertTrue(first.successfulCooldowns(f.wall).isEmpty())
        assertTrue(replacement.successfulCooldowns(f.wall).isEmpty())
        // The original Main looks up its replaceable store separately for acquire, mark and release.
        val scope = requireNotNull(report.automaticCooldownScopeOrNull())
        val blocked = first.acquire(scope, f.wall, true) as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportAttemptBlockReason.IN_FLIGHT, blocked.reason)
    }

    private class Fixture(private val liveDrainExecutor: java.util.concurrent.ExecutorService? = null,
        private val liveCancellationExecutor: java.util.concurrent.ExecutorService? = null) {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val attemptReads = mutableListOf<AndroidReportAttemptStore>()
        val executor = ManualExecutor()
        val storage = DrainStorage()
        private var nextId = 10L
        val store = AndroidReportQueueStore(
            capacityProfile = ApprovedReportQueueCapacityProfile(
                maxEntries = 20, maxPayloadBytes = 16_384, maxStoredEntryBytes = 32_768L,
                maxTotalBytes = 655_360L, automaticMaxEntries = 10, automaticMaxTotalBytes = 327_680L),
            storageFactory = { storage }, aead = DrainAead(),
            idFactory = { UUID(0L, nextId++) }, nowMillis = { 10_000L }, testOnly = Unit)
        var attempt = attemptStore()
        val integrated = IntegratedConsentSession()
        var confirmation = confirmation()
        var policy = PermissionSessionPolicy()
        var session = session()
        var actor: String? = ACTOR
        var gatewayGeneration = 1L
        var storageBlocked = false
        var deletionOnly = false
        var originApproved = true
        var capacityAvailable = true
        var foreground = true
        var walkInitialized = true
        var walk = WalkSessionSnapshot(WalkRuntimeEpoch(WALK, 0L), WalkSessionState.ENDED,
            WalkSessionMode.UNAVAILABLE, null, true, null)
        var networkReady = true
        var transport = ActiveNetworkTransport.WIFI
        var binding: IntegratedConsentNetworkBinding? = wifiBinding()
        var mainThread = true
        var activityLease = AccountDeletionActivityLease(1L)
        val ui = mutableListOf<() -> Unit>()
        val posts = mutableListOf<Pair<AccountDeletionActivityLease, () -> Unit>>()
        var wall = 10_000L
        var elapsed = 2_000L
        var handsFreeOutput = false
        val spoken = mutableListOf<String>()
        val shown = mutableListOf<String>()
        val persisted = mutableListOf<AndroidReportSuccessfulCooldown>()
        var onPersist: (AndroidReportSuccessfulCooldown) -> Boolean = { true }
        var status: (QueuedReport) -> ReportQueueReceipt? = { null }
        var onStatusCancelled: () -> Unit = {}
        var uploadReceipt: (QueuedReport) -> ReportQueueReceipt = { receipt(it) }
        var creation = ReportCreationOutcome.CREATED
        var statusCalls = 0
        var uploadCalls = 0
        var transportFactories = 0
        init {
            ReportPrivacyConsentSession.resetForNewEnrollment()
            assertTrue(ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent())
            assertEquals(IntegratedConsentApplyResult.APPLIED, integrated.applyConfirmation(confirmation))
        }
        val controller = ReportQueueDrainController(
            sources = ReportQueueDrainController.Sources(
                queueStore = { store }, attemptStore = {
                    attemptReads += attempt
                    attempt
                }, integratedConsent = integrated, privacyConsent = ReportPrivacyConsentSession,
                permissionPolicy = { events += "preference"; policy },
                gatewaySnapshot = { events += "gateway"; GatewaySessionProcessSnapshot(session, null,
                    gatewayGeneration, storageBlocked, null, deletionOnly) },
                walkSnapshot = { events += "walk"; walk }, reporterActorId = { events += "actor"; actor }),
            runtime = object : ReportQueueDrainController.Runtime {
                override fun isForeground() = foreground
                override fun isWalkInitialized() = walkInitialized
                override fun latestStepCount(): Int { events += "steps"; return 17 }
            },
            network = object : ReportQueueDrainController.Network {
                override fun isReady(): Boolean { events += "ready"; return networkReady }
                override fun currentTransport(): ActiveNetworkTransport { events += "transport"; return transport }
                override fun currentBinding(): IntegratedConsentNetworkBinding? { events += "binding"; return binding }
            },
            scheduler = object : ReportQueueDrainController.Scheduler {
                override fun isMainThread() = mainThread
                override fun runOnUiThread(action: () -> Unit) { events += "ui-post"; ui += action }
                override fun currentActivityLease(): AccountDeletionActivityLease { events += "lease"; return activityLease }
                override fun postActivityCallback(lease: AccountDeletionActivityLease, action: () -> Unit) { posts += lease to action }
            },
            clock = object : ReportQueueDrainController.Clock {
                override fun wallTimeMillis() = wall
                override fun elapsedRealtime() = elapsed
                override fun bootCount(): Int? = 7
            },
            effects = object : ReportQueueDrainController.Effects {
                override fun clearStepTrackingEpoch() { events += "clear" }
                override fun stopStepTrackingNow() { events += "stop" }
                override fun cancelRawUploadAndRevalidateIfReady() { events += "raw" }
                override fun schedulePostWalkTransfers() { events += "schedule" }
                override fun schedulePostWalkRetryAfterWalkInProgress() { events += "retry" }
                override fun isHandsFreeVoiceOutputActive() = handsFreeOutput
                override fun speakStatusExplanation(message: String) { events += "speak"; spoken += message }
                override fun showWalkLastResult(message: String) { events += "show"; shown += message }
            },
            transportFactory = { transportFactories++; events += "factory"; object : ReportQueueTransport {
                override fun statusCall(report: QueuedReport) = CancellableNetworkCall<ReportQueueReceipt?>(executeBlock = {
                    statusCalls++; status(report)
                }, cancelBlock = { onStatusCancelled() })
                override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> =
                    error("typed uploadResultCall must be used")
                override fun uploadResultCall(report: QueuedReport) = CancellableNetworkCall.blocking {
                    uploadCalls++; ReportQueueUploadResult(uploadReceipt(report), creation)
                }
            } },
            persistCooldownForSuccess = { persisted += it; lastCommitSucceeded = onPersist(it); lastCommitSucceeded },
            capacityProfileAvailable = { capacityAvailable }, approvedOrigin = { if (originApproved) it else null },
            reportQueueDrainExecutor = liveDrainExecutor ?: executor,
            reportQueueDrainCancellationExecutor = liveCancellationExecutor ?: ManualExecutor(),
        )
        private var lastCommitSucceeded = false
        fun enqueue(priority: ReportQueuePriority = ReportQueuePriority.EXPLICIT): QueuedReport {
            val automatic = priority == ReportQueuePriority.AUTOMATIC
            val metadata = """{"reporter_user_id":"$ACTOR","trigger":"${if (automatic) "auto" else "voice"}","auto_reported":$automatic,"schema_version":"detect.v2","class_name":"${AndroidReportCandidatePolicy.DAMAGED_TACTILE_BLOCK}","gps":{"latitude":37.5,"longitude":127.0}}"""
            return requireNotNull(store.enqueue(ACTOR, metadata.toByteArray(), jpeg(), priority, WALK,
                confirmation.backendConsentReceiptSha256))
        }
        fun trigger() = controller.buildReportQueueDrainTrigger(WALK, ActivityOriginalStationarySnapshot(17, elapsed))
        fun recordWalk() { controller.updateReportQueueDrainForTransition(WalkSessionTransition(walk, walk,
            WalkSessionTransitionReason.END_REQUESTED, wall)) }
        fun start(priority: ReportQueuePriority = ReportQueuePriority.EXPLICIT): ReportQueueDrainController.ReportQueueDrainTrigger {
            enqueue(priority); recordWalk(); val trigger = requireNotNull(trigger())
            assertTrue(controller.startBoundedReportQueueDrain(trigger)); return trigger
        }
        fun networkReads() = events.filter { it in listOf("transport", "preference", "binding") }
        fun effectEvents() = events.filter { it in listOf("clear", "stop", "raw", "schedule", "retry", "speak", "show") }
        fun runUi() { mainThread = true; val actions = ui.toList(); ui.clear(); actions.forEach { it() } }
        fun runPosts() { val callbacks = posts.toList(); posts.clear(); callbacks.forEach { (lease, action) -> if (lease == activityLease) action() } }
    }

    private companion object {
        const val ACTOR = "walker-1"
        const val WALK = "123e4567-e89b-42d3-a456-426614174001"
        const val OTHER_WALK = "123e4567-e89b-42d3-a456-426614174004"
        fun confirmation(revision: Long = 1L) = IntegratedConsentConfirmation(
            INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION, INTEGRATED_CONSENT_POLICY_VERSION,
            "501e3ad4-e74f-4433-820f-72ac2fdd42ad", "integrated_consent_request_0001", IntegratedConsentItemVersions(),
            revision, revision, IntegratedConsentSelections(automaticReporting = true),
            "2026-07-25T12:00:00.000Z", "9".repeat(64), "a".repeat(64), "b".repeat(64))
        fun session(actor: String = ACTOR, scope: GatewaySessionScope = GatewaySessionScope.GENERAL) =
            GatewayFieldSession.legacyVerified("https://gateway.example.com", actor, "device-1",
                "walksafe_field_session=test-cookie", Long.MAX_VALUE, scope)
        fun wifiBinding() = IntegratedConsentNetworkBinding.forTest(IntegratedConsentNetworkTransport.WIFI) {
            error("test transport must not open a network connection") }
        fun cellularBinding() = IntegratedConsentNetworkBinding.forTest(IntegratedConsentNetworkTransport.CELLULAR) {
            error("test transport must not open a network connection") }
        fun attemptStore() = AndroidReportAttemptStore(monotonicNowMs = { 2_000L }, bootCountProvider = { 7 })
        fun receipt(report: QueuedReport) = ReportQueueReceipt(report.payload.reportId, report.payload.payloadSha256,
            report.payload.payloadBytes, REPORT_RECEIPT_MARKER, "123e4567-e89b-42d3-a456-426614174002")
        fun jpeg() = intArrayOf(0xff, 0xd8, 0xff, 0xc0, 0, 0x0b, 8, 0, 1, 0, 1, 1, 1, 0x11, 0,
            0xff, 0xda, 0, 8, 1, 1, 0, 0, 0x3f, 0, 1, 0xff, 0xd9).map(Int::toByte).toByteArray()
    }
}

private class ManualExecutor : AbstractExecutorService() {
    val tasks = mutableListOf<Runnable>()
    var reject = false
    private var stopped = false
    override fun execute(command: Runnable) { if (reject || stopped) throw RejectedExecutionException(); tasks += command }
    fun takeNext(): Runnable = tasks.removeAt(0)
    fun runNext() = takeNext().run()
    override fun shutdown() { stopped = true }
    override fun shutdownNow(): MutableList<Runnable> { stopped = true; val cancelled = tasks.toMutableList(); tasks.clear(); return cancelled }
    override fun isShutdown() = stopped
    override fun isTerminated() = stopped && tasks.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
}

private class DrainStorage : ReportQueueStorage {
    @Volatile var beforeLockedAccess: (() -> Unit)? = null
    @Volatile var beforeDelete: (() -> Unit)? = null
    @Volatile var purgeCalls = 0
    private val values = mutableMapOf<String, String>()
    private val fences = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()
    private val locked = object : LockedReportQueueStorage {
        override fun listReportIds() = this@DrainStorage.listReportIds()
        override fun read(reportId: String) = this@DrainStorage.read(reportId)
        override fun delete(reportId: String) = this@DrainStorage.delete(reportId)
        override fun hasFence(name: String) = this@DrainStorage.hasFence(name)
        override fun measureUsage() = this@DrainStorage.measureUsage()
        override fun replace(reportId: String, envelope: String, storedEntryByteLimit: Long, storedByteLimit: Long): Boolean {
            val previous = values[reportId] ?: return false
            val size = envelope.toByteArray().size.toLong()
            if (size > storedEntryByteLimit || measureUsage().totalBytes - previous.toByteArray().size + size > storedByteLimit) return false
            values[reportId] = envelope
            return true
        }
    }
    override fun listReportIds(): Set<String> = values.keys
    override fun read(reportId: String): String? = values[reportId]
    override fun delete(reportId: String): Boolean { beforeDelete?.invoke(); values.remove(reportId); return true }
    override fun hasFence(name: String) = name in fences
    override fun measureUsage() = ReportQueueStorageUsage(values.size,
        values.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() })
    @Synchronized override fun <T> withLockedAccess(action: (LockedReportQueueStorage) -> T): T { beforeLockedAccess?.invoke(); return action(locked) }
    override fun writeAtomicallyIfAllowed(reportId: String, entryLimit: Int, storedByteLimit: Long,
        blockingFences: Set<String>, storedEntryByteLimit: Long, envelopeFactory: () -> String?): Boolean {
        val usage = measureUsage()
        if (blockingFences.any(fences::contains) || usage.reportFileCount >= entryLimit ||
            usage.totalBytes >= storedByteLimit || reportId in values) return false
        val envelope = envelopeFactory() ?: return false
        val bytes = envelope.toByteArray(Charsets.UTF_8).size.toLong()
        if (blockingFences.any(fences::contains) || usage.reportFileCount >= entryLimit ||
            bytes > storedEntryByteLimit || bytes > storedByteLimit - usage.totalBytes || reportId in values) return false
        values[reportId] = envelope
        return true
    }
    override fun purgeAllWithFence(name: String, keyLifecycle: (ReportQueuePurgeContext) -> Boolean): Boolean {
        purgeCalls++
        pending += name; fences += "lifecycle-pending"; fences += pending; values.clear()
        val done = keyLifecycle(ReportQueuePurgeContext(true, "account-deleted" in fences))
        if (done) { pending.clear(); fences -= "lifecycle-pending" }
        return done
    }
    @Synchronized override fun deleteMatchingWithFences(pendingFence: String, initialPermanentFences: Set<String>,
        permanentFenceForMatch: (String, String) -> String?): Boolean {
        fences += pendingFence; fences += initialPermanentFences
        values.entries.filter { (id, envelope) -> permanentFenceForMatch(id, envelope)?.also(fences::add) != null }
            .map { it.key }.forEach(values::remove)
        fences -= pendingFence
        return true
    }
}

private class DrainAead : LocalAead {
    private val values = mutableMapOf<String, ByteArray>()
    private var next = 0
    override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits): AeadSealResult {
        if (plaintext.size > limits.maxPlaintextBytes) return AeadSealResult.Blocked(AeadBlockReason.PLAINTEXT_TOO_LARGE)
        val token = "drain-${next++}"; values[token] = plaintext.copyOf(); return AeadSealResult.Sealed(token)
    }
    override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
        values[envelope]?.copyOf()?.let { AeadOpenResult.Opened(it, 1, false) }
            ?: AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
    override fun destroyVersion(version: Int) = true
    override fun destroyKnownVersions() = values.clear().let { true }
    override fun createFreshAfterVerifiedPurge() = true
}
