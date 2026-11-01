package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AndroidDeviceCheckAuthorityTest {
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
