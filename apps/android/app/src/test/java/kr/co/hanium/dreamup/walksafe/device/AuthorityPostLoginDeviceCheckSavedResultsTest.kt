package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real authority/cache/worker with explicit metadata-only proof fixtures; no native claim. */
class AuthorityPostLoginDeviceCheckSavedResultsTest {
    @Test fun anUnloadedAuthorityNeverAdmitsAnAttemptFromItsEmptyCache() {
        ReadRecoveryFixture().use { f ->
            assertFalse(f.saved.mayBeginAttempt())
            assertEquals(0, f.io.reads.get())
            assertEquals(0, f.io.writes.get())
        }
    }

    @Test fun anInitialReadMustFinishBeforeAnAttemptCanBegin() {
        ReadRecoveryFixture().use { f ->
            f.io.block = true
            val pending = f.saved.refresh(f.resultBinding()).toCompletableFuture()
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            assertFalse(f.saved.mayBeginAttempt())
            f.io.release.countDown()
            assertTrue(pending.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(0, f.io.writes.get())
        }
    }

    @Test fun concurrentRecoveryReadsShareOneRequestAfterTheInitialReadFailed() {
        ReadRecoveryFixture().use { f ->
            f.io.failRead = true
            assertEquals(DeviceCheckAuthorityRead.Blocked("storage_read_failed"),
                f.saved.refresh(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertFalse(f.saved.mayBeginAttempt())
            f.io.failRead = false; f.io.block = true
            val first = f.saved.refresh(f.resultBinding())
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            val duplicate = f.saved.refresh(f.resultBinding())
            assertSame(first, duplicate)
            assertFalse(f.saved.mayBeginAttempt())
            f.io.release.countDown()
            assertTrue(first.toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(2, f.io.reads.get())
            assertEquals(0, f.io.writes.get())
            assertNull(f.saved.historical())
        }
    }

    @Test fun anOldActorsReadCompletionCannotAdmitTheNewActorsAttempt() {
        ReadRecoveryFixture().use { f ->
            f.io.block = true
            val old = f.saved.refresh(f.resultBinding()).toCompletableFuture()
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            f.actor = "other"; f.session = 2
            f.saved.bindSession(f.actor, f.session)
            assertFalse(f.saved.mayBeginAttempt())
            f.io.release.countDown()
            assertEquals(DeviceCheckAuthorityRead.Blocked("stale_session"), old.get(2, TimeUnit.SECONDS))
            assertFalse(f.saved.mayBeginAttempt())
            assertTrue(f.saved.refresh(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
        }
    }

    @Test fun explicitRetryCanPerformTheFirstReadWithoutAutomaticallyRestoringAnything() {
        ReadRecoveryFixture().use { f ->
            assertFalse(f.saved.mayBeginAttempt())
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(1, f.io.reads.get())
            assertEquals(0, f.changes.get())
            assertEquals(0, f.io.writes.get())
        }
    }

    @Test fun explicitRecoveryReadsOnceAndOnlyThenAllowsAUserStartedAttempt() {
        ReadRecoveryFixture().use { f ->
            f.io.failRead = true
            assertEquals(DeviceCheckAuthorityRead.Blocked("storage_read_failed"),
                f.saved.refresh(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertEquals(1, f.changes.get())
            f.io.failRead = false; f.io.block = true
            val retry = f.saved.prepareExplicitRetry(f.resultBinding())
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            assertSame(retry, f.saved.prepareExplicitRetry(f.resultBinding()))
            assertFalse(f.saved.mayBeginAttempt())
            f.io.release.countDown()
            assertTrue(retry.toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(2, f.io.reads.get())
            assertEquals(1, f.changes.get())
            assertEquals(0, f.io.writes.get())
            assertNull(f.saved.historical())
            assertTrue(f.saved.beginAttemptAsync(PostLoginDeviceCheckBinding(f.actor, f.session, 1),
                f.resultBinding(), 1000, 301000).toCompletableFuture().get(2, TimeUnit.SECONDS)
                is DeviceCheckAuthorityOutcome.Committed)
            assertEquals(1, f.io.writes.get())
            assertNull(f.saved.historical())
        }
    }

    @Test fun explicitRetryJoiningTheInitialReadSuppressesItsAutomaticNotification() {
        ReadRecoveryFixture().use { f ->
            f.io.block = true
            val initial = f.saved.refresh(f.resultBinding())
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            assertSame(initial, f.saved.prepareExplicitRetry(f.resultBinding()))
            f.io.release.countDown()
            assertTrue(initial.toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertEquals(0, f.changes.get())
            assertTrue(f.saved.mayBeginAttempt())
        }
    }

    @Test fun repeatedReadFailureStaysBlockedUntilAnotherExplicitRequest() {
        ReadRecoveryFixture().use { f ->
            f.io.failRead = true
            f.saved.refresh(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertEquals(DeviceCheckAuthorityRead.Blocked("storage_read_failed"),
                f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertFalse(f.saved.mayBeginAttempt())
            assertEquals(2, f.io.reads.get())
            assertEquals(1, f.changes.get())
            f.io.failRead = false
            assertTrue(f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertEquals(3, f.io.reads.get())
            assertTrue(f.saved.mayBeginAttempt())
        }
    }

    @Test fun explicitRetryCannotReopenAnInvalidRecordOrAnUnknownNativeProcess() {
        val native = DeviceCheckAuthorityCoreTest.Fixture().also { it.pair() }
        for ((record, reason) in listOf("invalid" to "invalid_authority_record",
            requireNotNull(native.io.text) to "native_recovery_required")) {
            ReadRecoveryFixture().use { f ->
                f.io.text = record
                val blocked = DeviceCheckAuthorityRead.Blocked(reason)
                assertEquals(blocked, f.saved.refresh(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS))
                f.io.text = null
                assertEquals(blocked, f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
                    .get(2, TimeUnit.SECONDS))
                assertFalse(f.saved.mayBeginAttempt())
                assertEquals(1, f.io.reads.get())
                assertEquals(0, f.io.writes.get())
            }
        }
    }

    @Test fun explicitRetryCannotClearAnUncertainWriter() {
        ReadRecoveryFixture().use { f ->
            f.saved.refresh(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS)
            val operation = UUID.randomUUID().toString()
            f.core.publicationUnconfirmed(operation)
            assertEquals(DeviceCheckAuthorityRead.Blocked("publication_unconfirmed", operation),
                f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertEquals(operation, f.core.unsettledOperation())
            assertFalse(f.saved.mayBeginAttempt())
            assertEquals(1, f.io.reads.get())
            assertEquals(0, f.io.writes.get())
        }
    }

    @Test fun aLateExplicitRetryCannotAdmitTheSameActorInANewerSession() {
        ReadRecoveryFixture().use { f ->
            f.io.failRead = true
            f.saved.refresh(f.resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS)
            f.io.failRead = false; f.io.block = true
            val retry = f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            f.session = 2
            f.saved.bindSession(f.actor, f.session)
            f.io.release.countDown()
            assertEquals(DeviceCheckAuthorityRead.Blocked("stale_session"), retry.get(2, TimeUnit.SECONDS))
            assertFalse(f.saved.mayBeginAttempt())
            assertEquals(1, f.changes.get())
            assertEquals(0, f.io.writes.get())
        }
    }

    @Test fun aSupersededBindingCannotCompleteTheCurrentExplicitRetry() {
        ReadRecoveryFixture().use { f ->
            f.io.block = true
            val old = f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
            assertTrue(f.io.entered.await(2, TimeUnit.SECONDS))
            f.selectedBinding = f.binding.copy(resultBindingHash = "f".repeat(64))
            val current = f.saved.prepareExplicitRetry(f.resultBinding()).toCompletableFuture()
            assertNotSame(old, current)
            f.io.release.countDown()
            assertEquals(DeviceCheckAuthorityRead.Blocked("binding_superseded"), old.get(2, TimeUnit.SECONDS))
            assertTrue(current.get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.saved.mayBeginAttempt())
            assertEquals(0, f.changes.get())
            assertEquals(0, f.io.writes.get())
        }
    }

    @Test fun historicalCommitNeedsTheCurrentIssuedPairAndSessionBeforeRestore() {
        Fixture().use { f ->
            val ready = f.runtime
            f.runtime = null
            assertNull(f.saved.restore(f.resultBinding()))
            f.runtime = ready
            f.issued = false
            assertNull(f.saved.restore(f.resultBinding()))
            f.issued = true
            f.runtime = ready!!.copy(binding = ready.binding.copy(sessionGeneration = 2))
            assertNull(f.saved.restore(f.resultBinding()))
            f.runtime = ready
            assertEquals(f.result, f.saved.restore(f.resultBinding()))
        }
    }

    @Test fun lateOtherActorRefreshCannotReselectTheAuthorityOrRetireTheNewAttempt() {
        Fixture().use { f ->
            val old = f.resultBinding()
            f.actor = "other"; f.session = 2; f.generation = 2
            f.saved.bindSession(f.actor, f.session)
            val started = f.saved.beginAttemptAsync(f.checkBinding(), f.resultBinding(), 1000, 301000)
                .toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertTrue(started is DeviceCheckAuthorityOutcome.Committed)
            val source = f.saved.sourceFor(f.checkBinding()) as DeviceCheckAttemptRef
            assertTrue(f.authority.checkAttempt(source, 1100))
            val stale = f.saved.refresh(old).toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertEquals(DeviceCheckAuthorityRead.Blocked("stale_session"), stale)
            assertTrue(f.authority.checkAttempt(source, 1100))
            assertTrue(f.authority.cachedSnapshot(f.otherBinding) is DeviceCheckAuthorityRead.NeedsCheck)
        }
    }

    @Test fun sessionChangingDuringBindingCaptureCannotAdmitTheOldBegin() {
        Fixture().use { f ->
            val oldCheck = f.checkBinding(); val oldResult = f.resultBinding()
            f.capture = {
                f.capture = {}
                f.actor = "other"; f.session = 2; f.generation = 2
                f.saved.bindSession(f.actor, f.session)
                f.authority.read(f.otherBinding, 2).toCompletableFuture().get(2, TimeUnit.SECONDS)
            }
            assertTrue(f.saved.beginAttemptAsync(oldCheck, oldResult, 1000, 301000)
                .toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Rejected)
            assertTrue(f.authority.cachedSnapshot(f.otherBinding) is DeviceCheckAuthorityRead.NeedsCheck)
            assertTrue(f.authority.cachedSnapshot(f.base.binding) is DeviceCheckAuthorityRead.Blocked)
        }
    }

    @Test fun updatesRequireTheDisplayedRevisionAndCannotApplyAnOldResultTwice() {
        Fixture(limited = true).use { f ->
            val before = f.snapshot()
            assertEquals(f.result, f.saved.restore(f.resultBinding()))
            f.saved.didPublish(before)
            val next = before.copy(state = PostLoginDeviceCheckState.FULL, disabledFeatures = emptySet())
            assertTrue(f.saved.updateAsync(before, next, f.resultBinding(), false, PostLoginMetricDepthState.SUPPORTED)
                .toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Committed)
            assertFalse(f.saved.acceptsPublishedSnapshot(before))
            assertTrue(f.saved.acceptsPublishedSnapshot(next))
            assertTrue(f.saved.updateAsync(before, next, f.resultBinding(), false, PostLoginMetricDepthState.SUPPORTED)
                .toCompletableFuture().get(2, TimeUnit.SECONDS) is DeviceCheckAuthorityOutcome.Rejected)
            f.saved.didPublish(next)
            assertTrue(f.saved.runtimeReady(f.checkBinding()))
        }
    }

    @Test fun aHistoricalBundleIsAvailableForFreshPreparationWithoutPretendingItIsReady() {
        Fixture().use { f ->
            f.runtime = null
            assertNotNull(f.saved.sourceFor(f.checkBinding()))
            assertFalse(f.saved.runtimeReady(f.checkBinding()))
            assertNull(f.saved.restore(f.resultBinding()))
            assertEquals(f.result, f.saved.retainedResult(f.resultBinding()))
        }
    }

    private class Fixture(limited: Boolean = false) : AutoCloseable {
        val base = DeviceCheckAuthorityCoreTest.Fixture()
        private val pair = base.pair()
        val result = if (limited) base.full.copy(state = PostLoginDeviceCheckState.LIMITED,
            disabledFeatures = setOf(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE),
            metricDepthState = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED) else base.full
        val authority = AndroidDeviceCheckAuthority(base.core, true, 5000)
        var actor = "actor"
        var session = 1L
        var generation = 1L
        var issued = true
        var capture: () -> Unit = {}
        val otherBinding = base.binding.copy(actorBindingHash = "e".repeat(64))
        var runtime: DeviceCheckReadyRuntime? = DeviceCheckReadyRuntime(checkBinding(), base.choice, pair.proof)
        val saved = AuthorityPostLoginDeviceCheckSavedResults(authority,
            bindingFor = { capture(); if (it.actorId == "actor") base.binding else otherBinding },
            readyRuntime = { runtime }, isCurrent = { it == checkBinding() },
            sessionIsCurrent = { a, s -> a == actor && s == session },
            isIssuedProof = { issued && it === pair.proof }, nowMs = { 1100 })
        init {
            val frozen = requireNotNull(base.core.freezeSuccess(pair.attempt, 1100, result, base.choice, pair.proof))
            assertTrue(base.core.commitSuccess(UUID.randomUUID().toString(), frozen) is DeviceCheckAuthorityOutcome.Committed)
            saved.bindSession(actor, session)
            saved.refresh(resultBinding()).toCompletableFuture().get(2, TimeUnit.SECONDS)
        }
        fun checkBinding() = PostLoginDeviceCheckBinding(actor, session, generation)
        fun snapshot() = PostLoginDeviceCheckSnapshot(result.state, actor, session, generation,
            disabledFeatures = result.disabledFeatures)
        fun resultBinding() = PostLoginDeviceCheckResultBinding(actor, "installation", "maker", "model", "device", 33,
            "fingerprint", "app", 1, "1", "source", "policy", null, null, "registry", "sha", null, null,
            emptySet(), true, true, true, true)
        override fun close() = authority.shutdownForTests()
    }

    /** Starts before any read, owner, attempt or cache population. */
    private class ReadRecoveryFixture : AutoCloseable {
        val io = ReadRecoveryIo()
        val binding = DeviceCheckAuthorityBinding("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64))
        var selectedBinding = binding
        val otherBinding = binding.copy(actorBindingHash = "e".repeat(64))
        val core = DeviceCheckAuthorityCore(io, DeviceCheckProcessObserver { DeviceCheckProcessObservation.UNKNOWN },
            { 1100 }, { 8 }, true, requireSessionObservation = true)
        val authority = AndroidDeviceCheckAuthority(core, true, 5000)
        var actor = "actor"
        var session = 1L
        val changes = AtomicInteger()
        val saved = AuthorityPostLoginDeviceCheckSavedResults(authority,
            bindingFor = { if (it.actorId == "actor") selectedBinding else otherBinding },
            readyRuntime = { null }, isCurrent = { it.actorId == actor && it.sessionGeneration == session },
            sessionIsCurrent = { a, s -> a == actor && s == session },
            isIssuedProof = { false }, nowMs = { 1100 }, onChanged = { changes.incrementAndGet() })
        init { saved.bindSession(actor, session) }
        fun resultBinding() = PostLoginDeviceCheckResultBinding(actor, "installation", "maker", "model", "device", 33,
            "fingerprint", "app", 1, "1", "source", "policy", null, null, "registry", "sha", null, null,
            emptySet(), true, true, true, true)
        override fun close() { io.release.countDown(); authority.shutdownForTests() }
    }

    private class ReadRecoveryIo : DeviceCheckAuthorityPersistence {
        @Volatile var failRead = false
        @Volatile var block = false
        @Volatile var text: String? = null
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        override fun read(): String? {
            reads.incrementAndGet()
            if (block) { block = false; entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) }
            check(!failRead) { "test_read_failure" }
            return text
        }
        override fun publish(canonicalState: String): DeviceCheckPublication {
            writes.incrementAndGet()
            return DeviceCheckPublication.COMMITTED
        }
    }
}
