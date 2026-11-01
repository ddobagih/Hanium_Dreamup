package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real authority/cache/worker with explicit metadata-only proof fixtures; no native claim. */
class AuthorityPostLoginDeviceCheckSavedResultsTest {
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
}
