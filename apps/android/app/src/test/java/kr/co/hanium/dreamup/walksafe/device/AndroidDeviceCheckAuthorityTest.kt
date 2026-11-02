package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AndroidDeviceCheckAuthorityTest {
    @Test fun aDispatchedDeadlineCannotRefenceACompletedRead() = assertCompletedDeadline { f ->
        { it.read(f.binding) }
    }
    @Test fun aDispatchedDeadlineCannotRefenceACompletedBegin() = assertCompletedDeadline { f ->
        { it.beginAttempt(f.binding, id(), 1000, 301000) }
    }
    @Test fun aDispatchedDeadlineCannotRefenceACompletedCancel() = assertCompletedDeadline { f ->
        val attempt = f.begin();
        { authority: AndroidDeviceCheckAuthority -> authority.cancelAttempt(attempt, id(), "test") }
    }
    @Test fun aDispatchedDeadlineCannotRefenceACompletedSuccessPublication() = assertCompletedDeadline { f ->
        val pair = f.pair();
        { authority: AndroidDeviceCheckAuthority -> authority.commitSuccess(pair.attempt, id(), 1100, f.full, f.choice, pair.proof) }
    }
    @Test fun aDispatchedDeadlineCannotRefenceACompletedResultUpdate() = assertCompletedDeadline { f ->
        val pair = f.pair();
        val bundle = (f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof)))
            as DeviceCheckAuthorityOutcome.Committed).value;
        { authority: AndroidDeviceCheckAuthority -> authority.updateResult(bundle, id(), f.full,
            f.full.copy(state = PostLoginDeviceCheckState.LIMITED,
                disabledFeatures = setOf(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE),
                metricDepthState = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED)) }
    }
    @Test fun aDispatchedDeadlineCannotRefenceACompletedReconciliation() = assertCompletedDeadline { f ->
        val operation = id()
        f.core.publicationUnconfirmed(operation);
        { authority: AndroidDeviceCheckAuthority -> authority.reconcileAfterSettled(operation) }
    }
    @Test fun aDispatchedDeadlineCannotRefenceACompletedNativePrepare() = assertCompletedDeadline { f ->
        val owner = f.owner(f.begin())
        val lease = DeviceCheckNativeLease(id(), owner.ownerId, 1);
        { authority: AndroidDeviceCheckAuthority -> authority.prepareNativeOpen(owner, lease, Any(), id(),
            f.primarySpec, f.child, 20000) }
    }
    @Test fun aDispatchedDeadlineCannotRefenceACompletedModelOpenJournal() = assertCompletedDeadline { f ->
        val owner = f.owner(f.begin())
        val lease = DeviceCheckNativeLease(id(), owner.ownerId, 1);
        val identity = Any()
        val permit = (f.core.prepareNativeOpen(owner, lease, identity, id(), f.primarySpec, f.child, 20000)
            as DeviceCheckAuthorityOutcome.Committed).value
        assertTrue(f.core.consumeNativeOpen(permit, identity, 1001));
        { authority: AndroidDeviceCheckAuthority -> authority.recordModelOpened(permit, id(), "a".repeat(64)) }
    }
    @Test fun aDispatchedDeadlineCannotRefenceACompletedModelCloseJournal() = assertCompletedDeadline { f ->
        val pair = f.pair();
        { authority: AndroidDeviceCheckAuthority -> authority.recordModelCloseAck(pair.primary, f.child.pid, false) }
    }
    @Test fun aDispatchedDeadlineCannotRefenceACompletedCohortReleaseJournal() = assertCompletedDeadline { f ->
        val pair = f.pair();
        { authority: AndroidDeviceCheckAuthority -> authority.recordCohortReleased(pair.primary.lease.cohortId,
            f.child.pid, DeviceCheckNativeReleaseProof.CHILD_DEATH) }
    }
    @Test fun aDispatchedDeadlineCannotRefenceACompletedOwnerTransfer() = assertCompletedDeadline { f ->
        val pair = f.pair()
        val bundle = (f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof)))
            as DeviceCheckAuthorityOutcome.Committed).value
        val owner = requireNotNull(f.core.registerOwner("camera-test", bundle, 1))
        val target = pair.primary.lease.copy(ownerId = owner.ownerId, ownerEpoch = 2);
        { authority: AndroidDeviceCheckAuthority -> authority.recordOwnerTransfer(pair.primary.lease, target, owner, Any(), id(),
            mapOf(pair.primary.modelId to "b".repeat(64), pair.aux.modelId to "b".repeat(64))) }
    }
    @Test fun aDispatchedDeadlineCannotRefenceACompletedRuntimeRetirement() = assertCompletedDeadline { f ->
        val pair = f.pair();
        { authority: AndroidDeviceCheckAuthority -> authority.retireRuntime(pair.owner, pair.primary.lease.cohortId,
            pair.identity, "test_fault") }
    }
    @Test fun aDispatchedDeadlineCannotRefenceACompletedChoiceRetirement() = assertCompletedDeadline { f ->
        val pair = f.pair()
        f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof)));
        { authority: AndroidDeviceCheckAuthority -> authority.retireChoice(f.choice) }
    }
    @Test fun dispatchedDeadlinesCannotRefenceCompletedExactNativeCleanup() = assertCompletedDeadline { f ->
        val source = f.begin()
        val owner = f.owner(source)
        val lease = DeviceCheckNativeLease(id(), owner.ownerId, 1)
        val identity = Any()
        val command = id();
        { authority: AndroidDeviceCheckAuthority ->
            authority.prepareNativeOpen(owner, lease, identity, command, f.primarySpec, f.child, 20000).thenCompose {
                assertTrue(it is DeviceCheckAuthorityOutcome.Committed)
                authority.settleNativeOpenRelease(source, owner, lease, identity, command, f.primarySpec, f.child,
                    DeviceCheckNativeReleaseProof.NO_NATIVE_START)
            }
        }
    }
    @Test fun aDispatchedDeadlineCannotRefenceDefinitiveNonPublication() = assertCompletedDeadline(notPublished = true) { f ->
        f.io.publication = DeviceCheckPublication.NOT_PUBLISHED;
        { authority: AndroidDeviceCheckAuthority -> authority.beginAttempt(f.binding, id(), 1000, 301000) }
    }
    @Test fun aDispatchedDeadlineCannotRefenceACompletedRejection() = assertCompletedDeadline(rejected = true) { f ->
        val pair = f.pair()
        val bundle = (f.core.commitSuccess(id(), requireNotNull(f.core.freezeSuccess(pair.attempt, 1100, f.full, f.choice, pair.proof)))
            as DeviceCheckAuthorityOutcome.Committed).value;
        { authority: AndroidDeviceCheckAuthority -> authority.updateResult(bundle, id(),
            f.full.copy(metricDepthState = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED), f.full) }
    }

    @Test fun aQueuedTimeoutNeverEntersIoAndItsExpiredDeadlineCannotReviveAfterReconciliation() {
        val io = BlockingIo(); val core = core(io); val watch = DispatchedDeadlines()
        val lane = Executors.newSingleThreadExecutor(); val hold = CountDownLatch(1); val entered = CountDownLatch(1)
        lane.execute { entered.countDown(); hold.await() }
        val authority = AndroidDeviceCheckAuthority(core, false, 5000, lane, watch)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val command = id()
            val result = authority.beginAttempt(binding, command, 1000, 301000).toCompletableFuture()
            val expired = watch.next()
            expired.run()
            assertEquals(DeviceCheckAuthorityOutcome.Indeterminate(command), result.get(2, TimeUnit.SECONDS))
            hold.countDown()
            val recovered = authority.reconcileAfterSettled(command).toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertTrue(recovered is DeviceCheckAuthorityRead.NeedsCheck)
            expired.run() // The same already-dispatched callback must be inert after settlement.
            assertNull(core.unsettledOperation())
            assertEquals(0, io.writes.get())
        } finally { hold.countDown(); authority.shutdownForTests() }
    }

    @Test fun anUnknownExternalUncertaintySignalStillFencesTheStorageNamespace() {
        val io = BlockingIo(); val core = core(io); val authority = AndroidDeviceCheckAuthority(core, false)
        try {
            val marker = id()
            authority.publicationUnconfirmed(marker)
            assertEquals(marker, core.unsettledOperation())
            assertTrue(authority.cachedSnapshot(binding) is DeviceCheckAuthorityRead.Blocked)
        } finally { authority.shutdownForTests() }
    }

    @Test fun explicitUncertaintyAfterSuccessStillFencesWithoutRevivingItsDeadline() {
        val io = BlockingIo(); val core = core(io); val watch = DispatchedDeadlines()
        val authority = AndroidDeviceCheckAuthority(core, false, watch = watch)
        try {
            val command = id()
            val result = authority.beginAttempt(binding, command, 1000, 301000).toCompletableFuture()
            assertTrue(result.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Committed)
            val expired = watch.next()
            authority.publicationUnconfirmed(command)
            assertEquals(command, core.unsettledOperation())
            assertTrue(authority.reconcileAfterSettled(command).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityRead.NeedsCheck)
            expired.run()
            assertNull(core.unsettledOperation())
            assertTrue(result.get() is DeviceCheckAuthorityOutcome.Committed)
        } finally { authority.shutdownForTests() }
    }

    @Test fun writerRejectionCannotOverwriteAQueuedTimeoutOrReviveItAfterSettlement() {
        val io = BlockingIo(); val core = core(io); val watch = DispatchedDeadlines()
        var expired: Runnable? = null
        val lane = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) {
            var rejectFirst = true
            override fun execute(command: Runnable) {
                if (rejectFirst) {
                    rejectFirst = false
                    expired = watch.next().also { it.run() }
                    throw RejectedExecutionException("test")
                }
                super.execute(command)
            }
        }
        val authority = AndroidDeviceCheckAuthority(core, false, 5000, lane, watch)
        try {
            val command = id()
            assertEquals(DeviceCheckAuthorityOutcome.Indeterminate(command),
                authority.beginAttempt(binding, command, 1000, 301000).toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertTrue(authority.reconcileAfterSettled(command).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityRead.NeedsCheck)
            requireNotNull(expired).run()
            assertNull(core.unsettledOperation())
            assertEquals(0, io.writes.get())
        } finally { authority.shutdownForTests() }
    }

    @Test fun schedulerRejectionNeverIssuesIoAndANewAdapterCanReconcileItsFence() {
        val io = BlockingIo(); val core = core(io)
        val watch = ScheduledThreadPoolExecutor(1).apply { shutdownNow() }
        val authority = AndroidDeviceCheckAuthority(core, false, watch = watch)
        val recovery = AndroidDeviceCheckAuthority(core, false)
        try {
            val command = id()
            assertEquals(DeviceCheckAuthorityOutcome.Indeterminate(command),
                authority.beginAttempt(binding, command, 1000, 301000).toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertFalse(core.workRunning())
            assertEquals(0, io.writes.get())
            assertTrue(recovery.reconcileAfterSettled(command).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityRead.NeedsCheck)
            assertNull(core.unsettledOperation())
        } finally { authority.shutdownForTests(); recovery.shutdownForTests() }
    }

    @Test fun aSlowReentrantSuccessCallbackCannotLetADispatchedDeadlineCreateAFence() {
        val io = BlockingIo(); val core = core(io); val watch = DispatchedDeadlines()
        val lane = Executors.newSingleThreadExecutor()
        val laneEntered = CountDownLatch(1); val laneRelease = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1); val callbackRelease = CountDownLatch(1)
        lane.execute { laneEntered.countDown(); laneRelease.await() }
        val authority = AndroidDeviceCheckAuthority(core, false, 5000, lane, watch)
        try {
            assertTrue(laneEntered.await(2, TimeUnit.SECONDS))
            val result = authority.beginAttempt(binding, id(), 1000, 301000).toCompletableFuture()
            val callback = result.thenCompose { outcome ->
                assertTrue(outcome is DeviceCheckAuthorityOutcome.Committed)
                callbackEntered.countDown()
                assertTrue(callbackRelease.await(2, TimeUnit.SECONDS))
                authority.read(binding) // Completion is outside all ticket and adapter monitors.
            }.toCompletableFuture()
            laneRelease.countDown()
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
            watch.next().run()
            assertNull(core.unsettledOperation())
            callbackRelease.countDown()
            assertTrue(callback.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            watch.runDispatched()
            assertNull(core.unsettledOperation())
        } finally { laneRelease.countDown(); callbackRelease.countDown(); authority.shutdownForTests() }
    }

    @Test fun anOperationRejectedByAnotherCanonicalFenceCannotLaterFenceItself() {
        val io = BlockingIo(); val core = core(io); val watch = DispatchedDeadlines()
        val lane = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        lane.execute { entered.countDown(); release.await() }
        val authority = AndroidDeviceCheckAuthority(core, false, 5000, lane, watch)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val result = authority.beginAttempt(binding, id(), 1000, 301000).toCompletableFuture()
            val foreign = id()
            core.publicationUnconfirmed(foreign)
            release.countDown()
            assertEquals(DeviceCheckAuthorityOutcome.Indeterminate(foreign), result.get(2, TimeUnit.SECONDS))
            assertTrue(authority.reconcileAfterSettled(foreign).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityRead.NeedsCheck)
            watch.runDispatched()
            assertNull(core.unsettledOperation())
            assertEquals(0, io.writes.get())
        } finally { release.countDown(); authority.shutdownForTests() }
    }

    /** Runs a deadline that was already dispatched before cancellation, without wall-clock races. */
    private class DispatchedDeadlines : ScheduledThreadPoolExecutor(1) {
        private val dispatched = LinkedBlockingQueue<Runnable>()
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            dispatched.add(command)
            return super.schedule({}, 1, TimeUnit.DAYS)
        }
        fun next(): Runnable = requireNotNull(dispatched.poll(2, TimeUnit.SECONDS))
        fun runDispatched() { while (true) (dispatched.poll() ?: return).run() }
    }
    private fun assertCompletedDeadline(rejected: Boolean = false, notPublished: Boolean = false,
        prepare: (DeviceCheckAuthorityCoreTest.Fixture) ->
        (AndroidDeviceCheckAuthority) -> CompletionStage<*>) {
        val fixture = DeviceCheckAuthorityCoreTest.Fixture()
        val operation = prepare(fixture)
        val watch = DispatchedDeadlines()
        val authority = AndroidDeviceCheckAuthority(fixture.core, false, 5000, watch = watch)
        try {
            val result = operation(authority).toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertTrue("Exercise the expected terminal outcome: $result", if (notPublished) result is DeviceCheckAuthorityOutcome.NotPublished
                else if (rejected) result is DeviceCheckAuthorityOutcome.Rejected
                else result is DeviceCheckAuthorityOutcome.Committed<*> || result is DeviceCheckAuthorityRead.Ready ||
                    result is DeviceCheckAuthorityRead.NeedsCheck)
            assertNull(fixture.core.unsettledOperation())
            watch.next().run()
            watch.runDispatched()
            assertNull("A completed operation cannot acquire a new uncertainty fence", fixture.core.unsettledOperation())
        } finally { authority.shutdownForTests() }
    }
    @Test fun hungPublicationFencesNewStoreReadsAndWritesUntilTheActualWorkerSettles() {
        val io = BlockingIo(); val core = core(io); val authority = AndroidDeviceCheckAuthority(core, false, 100)
        val secondStore = AndroidDeviceCheckAuthority(core, false, 100)
        try {
            val id = id(); io.block = true
            val first = authority.beginAttempt(binding, id, 1000, 301000).toCompletableFuture()
            assertTrue(io.entered.await(2, TimeUnit.SECONDS))
            assertEquals(DeviceCheckAuthorityOutcome.Indeterminate(id), first.get(2, TimeUnit.SECONDS))
            assertTrue(secondStore.cachedSnapshot(binding) is DeviceCheckAuthorityRead.Blocked)
            assertTrue(secondStore.read(binding).toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.Blocked)
            assertTrue(secondStore.beginAttempt(binding, id(), 1000, 301000).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityOutcome.Indeterminate)
            assertEquals(1, io.writes.get())
            val recovery = authority.reconcileAfterSettled(id).toCompletableFuture()
            assertFalse("Public timeout is not actual I/O completion", recovery.isDone)
            io.release.countDown()
            assertTrue(recovery.get(3, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertEquals(DeviceCheckAuthorityOutcome.Indeterminate(id), first.get())
            assertTrue(authority.beginAttempt(binding, id(), 1000, 301000).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityOutcome.Committed)
        } finally { io.release.countDown(); authority.shutdownForTests(); secondStore.shutdownForTests() }
    }
    @Test fun explicitEarlyUnconfirmedSignalRevokesPermissionBeforeTheWriterReturns() {
        val io = BlockingIo(); val core = core(io); val authority = AndroidDeviceCheckAuthority(core, false, 5000)
        try {
            val id = id(); io.block = true
            val first = authority.beginAttempt(binding, id, 1000, 301000).toCompletableFuture()
            assertTrue(io.entered.await(2, TimeUnit.SECONDS))
            authority.publicationUnconfirmed(id)
            assertEquals(DeviceCheckAuthorityOutcome.Indeterminate(id), first.get(2, TimeUnit.SECONDS))
            assertTrue(authority.cachedSnapshot(binding) is DeviceCheckAuthorityRead.Blocked)
            io.release.countDown()
            assertTrue(authority.reconcileAfterSettled(id).toCompletableFuture().get(3, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
        } finally { io.release.countDown(); authority.shutdownForTests() }
    }
    @Test fun identicalCommandRetryIsOnePublicationAndChangedPayloadCannotHijackItsId() {
        val io = BlockingIo(); val authority = AndroidDeviceCheckAuthority(core(io), false, 5000)
        try {
            val id = id()
            val first = authority.beginAttempt(binding, id, 1000, 301000).toCompletableFuture().get(2, TimeUnit.SECONDS)
            val retry = authority.beginAttempt(binding, id, 1000, 301000).toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertSame((first as DeviceCheckAuthorityOutcome.Committed).value, (retry as DeviceCheckAuthorityOutcome.Committed).value)
            assertEquals(1, io.writes.get())
            assertTrue(authority.checkAttempt(first.value, 1100))
            assertTrue(authority.beginAttempt(binding, id, 1000, 301001).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityOutcome.Rejected)
            assertEquals(1, io.writes.get())
        } finally { authority.shutdownForTests() }
    }
    @Test fun aQueuedOldActorCannotChangeTheBindingAfterTheNewActorsAdmission() {
        val io = BlockingIo(); val authority = AndroidDeviceCheckAuthority(core(io), false, 5000)
        try {
            val original = authority.beginAttempt(binding, id(), 1000, 301000).toCompletableFuture().get(2, TimeUnit.SECONDS)
            val source = (original as DeviceCheckAuthorityOutcome.Committed).value
            val oldOwner = requireNotNull(authority.registerOwner("main-test", source, 1))
            val other = binding.copy(actorBindingHash = "e".repeat(64))
            authority.read(other).toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertNull(authority.ownerRef(oldOwner.ownerId))
            assertFalse(authority.checkAttempt(source, 1100))
            assertTrue(authority.cachedSnapshot(binding) is DeviceCheckAuthorityRead.Blocked)
        } finally { authority.shutdownForTests() }
    }
    @Test fun aReadOrBeginCapturedBeforeAccountChangeCannotReselectTheOldActor() {
        val io = BlockingIo(); val core = core(io); val authority = AndroidDeviceCheckAuthority(core, true, 5000)
        try {
            authority.retireOwnersOutsideSession(1)
            authority.read(binding, 1).toCompletableFuture().get(2, TimeUnit.SECONDS)
            val other = binding.copy(actorBindingHash = "e".repeat(64))
            authority.retireOwnersOutsideSession(2)
            authority.read(other, 2).toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertTrue(authority.read(binding, 1).toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.Blocked)
            assertTrue(authority.beginAttempt(binding, id(), 1000, 301000, 1).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityOutcome.Rejected)
            assertTrue(authority.beginAttempt(other, id(), 1000, 301000).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityOutcome.Rejected)
            assertEquals(other, core.currentBinding())
            assertEquals(0, io.writes.get())
            assertTrue(authority.beginAttempt(other, id(), 1000, 301000, 2).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityOutcome.Committed)
        } finally { authority.shutdownForTests() }
    }
    @Test fun productionSessionAdmissionRequiresObservationBeforeAnyReadOrBegin() {
        val io = BlockingIo()
        val core = DeviceCheckAuthorityCore(io, DeviceCheckProcessObserver { DeviceCheckProcessObservation.UNKNOWN },
            { 1100 }, { 8 }, true, requireSessionObservation = true)
        val authority = AndroidDeviceCheckAuthority(core, false, 5000)
        try {
            assertTrue(authority.read(binding, 1).toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.Blocked)
            assertTrue(authority.beginAttempt(binding, id(), 1000, 301000, 1).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityOutcome.Rejected)
            assertNull(core.currentBinding())
            authority.retireOwnersOutsideSession(1)
            assertTrue(authority.beginAttempt(binding, id(), 1000, 301000, 1).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityOutcome.Committed)
        } finally { authority.shutdownForTests() }
    }
    @Test fun queuedWorkerRechecksSessionEvenWhenTheSameActorLogsInAgain() {
        val io = BlockingIo(); val core = core(io)
        val lane = Executors.newSingleThreadExecutor(); val hold = CountDownLatch(1); val entered = CountDownLatch(1)
        lane.execute { entered.countDown(); hold.await() }
        val authority = AndroidDeviceCheckAuthority(core, true, 5000, lane)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            authority.retireOwnersOutsideSession(1)
            val oldRead = authority.read(binding, 1).toCompletableFuture()
            val oldBegin = authority.beginAttempt(binding, id(), 1000, 301000, 1).toCompletableFuture()
            authority.retireOwnersOutsideSession(2)
            val freshRead = authority.read(binding, 2).toCompletableFuture()
            hold.countDown()
            assertTrue(oldRead.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.Blocked)
            assertTrue(oldBegin.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Rejected)
            assertTrue(freshRead.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertEquals(0, io.writes.get())
            authority.retireOwnersOutsideSession(null)
            assertTrue(authority.read(binding, 2).toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.Blocked)
            assertTrue(authority.beginAttempt(binding, id(), 1000, 301000, 2).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityOutcome.Rejected)
        } finally { hold.countDown(); authority.shutdownForTests() }
    }
    private class BlockingIo : DeviceCheckAuthorityPersistence {
        @Volatile var text: String? = null
        @Volatile var block = false
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val writes = AtomicInteger()
        override fun read() = text
        override fun publish(canonicalState: String): DeviceCheckPublication {
            writes.incrementAndGet()
            if (block) { block = false; entered.countDown(); release.await() }
            text = canonicalState
            return DeviceCheckPublication.COMMITTED
        }
    }
    companion object {
        private val binding = DeviceCheckAuthorityBinding("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64))
        private fun id() = UUID.randomUUID().toString()
        private fun core(io: DeviceCheckAuthorityPersistence) = DeviceCheckAuthorityCore(io,
            DeviceCheckProcessObserver { DeviceCheckProcessObservation.UNKNOWN }, { 1100 }, { 8 }, true).also { it.admit(binding); it.read(binding) }
    }
}
