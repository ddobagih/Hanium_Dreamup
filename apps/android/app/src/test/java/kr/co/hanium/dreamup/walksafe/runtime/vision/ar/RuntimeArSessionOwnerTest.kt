package kr.co.hanium.dreamup.walksafe.runtime.vision.ar

import kr.co.hanium.dreamup.walksafe.device.RuntimeArSessionCleanupOwnership
import org.junit.Assert.*
import org.junit.Test

class RuntimeArSessionOwnerTest {
    @Test fun constructorAndEmptyLeaseDoNotCallNativeOrDiagnosticDependencies() {
        val owner = RuntimeArSessionOwner<Session, Provider, Purpose>(
            Any(), Purpose.NONE, { error("constructor/native session access") },
            { error("constructor/native provider access") }, RuntimeArSessionCleanupOwnership(),
            { _, _ -> error("constructor diagnostic access") },
        )
        assertNull(owner.acquireLeaseOrNull())
        assertNull(owner.observe().session)
        assertNull(owner.observe().provider)
        assertEquals(0L, owner.observe().generation)
        assertFalse(owner.cleanupPending())
    }

    @Test fun publishAndItsMetricCommitUseTheOriginalSharedTransactionLock() {
        val f = Fixture()
        var committed = false
        val generation = f.owner.publish(f.session, f.provider, Purpose.RUNTIME) { value ->
            assertTrue(Thread.holdsLock(f.lock))
            val observed = f.owner.observe()
            assertSame(f.session, observed.session)
            assertSame(f.provider, observed.provider)
            assertEquals(value, observed.generation)
            committed = true
        }
        assertTrue(committed)
        assertEquals(1L, generation)
        assertEquals(0, f.session.closeCalls)
        assertEquals(0, f.provider.closeCalls)
    }

    @Test fun exactLeaseRequiresTheSameNativeSessionAndProviderReferences() {
        val f = started()
        val lease = requireNotNull(f.owner.acquireLeaseOrNull())
        assertTrue(f.owner.leaseIsCurrent(lease))
        assertEquals(f.session, Session(1))
        assertFalse(f.owner.leaseIsCurrent(lease.copy(session = Session(1))))
        assertEquals(f.provider, Provider(1))
        assertFalse(f.owner.leaseIsCurrent(lease.copy(provider = Provider(1))))
        assertFalse(f.owner.leaseIsCurrent(lease.copy(purpose = Purpose.PREFLIGHT)))
        assertFalse(f.owner.leaseIsCurrent(lease.copy(generation = 2L)))
    }

    @Test fun staleLeaseCannotBecomeCurrentWhenTheSameResourcesArePublishedAgain() {
        val f = started()
        val original = requireNotNull(f.owner.acquireLeaseOrNull())
        f.owner.publish(f.session, f.provider, Purpose.RUNTIME)
        assertFalse(f.owner.leaseIsCurrent(original))
        assertEquals(2L, f.owner.observe().generation)
    }

    @Test fun stopRetainsTheExactTargetBeforeClearingActiveStateWithoutClosingOnTheStateLock() {
        val f = started()
        val oldLease = requireNotNull(f.owner.acquireLeaseOrNull())
        val captured = requireNotNull(f.owner.detachForStop(true) {
            assertTrue(Thread.holdsLock(f.lock))
            assertTrue(f.owner.cleanupPending())
            assertNull(f.owner.observe().session)
            assertEquals(0, f.session.closeCalls)
        })
        assertSame(f.session, captured.session)
        assertSame(f.provider, captured.provider)
        assertFalse(f.owner.leaseIsCurrent(oldLease))
        assertEquals(Purpose.NONE, f.owner.observe().purpose)
        assertEquals(2L, f.owner.observe().generation)
        assertTrue(f.owner.closeRetained { f.events += "prepare" })
        assertEquals(listOf("prepare", "session_close"), f.events)
        assertEquals(1, f.session.closeCalls)
        assertFalse(f.owner.cleanupPending())
    }

    @Test fun ordinaryDetachDoesNotRetainOrInvokeNativeClose() {
        val f = started()
        val captured = requireNotNull(f.owner.detachForStop(false))
        assertSame(f.session, captured.session)
        assertNull(f.owner.observe().session)
        assertEquals(2L, f.owner.observe().generation)
        assertFalse(f.owner.cleanupPending())
        assertEquals(0, f.session.closeCalls)
        assertEquals(0, f.provider.closeCalls)
    }

    @Test fun failedRetentionDoesNotDetachTheSessionOrAdvanceGenerationOrRunCommit() {
        val f = started()
        val failure = IllegalStateException("uncertain close")
        assertFalse(f.owner.closeCandidate { throw failure })
        val original = f.owner.observe()
        assertNull(f.owner.detachForStop(true) { error("failed retention must not run commit") })
        assertSame(original.session, f.owner.observe().session)
        assertSame(original.provider, f.owner.observe().provider)
        assertEquals(original.generation, f.owner.observe().generation)
        assertEquals(original.purpose, f.owner.observe().purpose)
        assertSame(failure, f.owner.cleanupFailureOrNull())
    }

    @Test fun nativeFailureKeepsTheExactFailureAndDoesNotReplayOrReplaceTheUncertainTarget() {
        val f = Fixture()
        val failure = IllegalStateException("session close failed")
        var calls = 0
        val original: () -> Unit = { calls += 1; throw failure }
        assertFalse(f.owner.closeCandidate(original))
        assertTrue(f.owner.cleanupPending())
        assertSame(failure, f.owner.cleanupFailureOrNull())
        assertFalse(f.owner.closeRetained())
        assertFalse(f.owner.closeCandidate { error("replacement must never execute") })
        assertEquals(1, calls)
        assertEquals(listOf("ar_session_cleanup_failed"), f.diagnostics.map { it.first })
    }

    @Test fun anotherOwnerCannotConsumeTheProcessRetainedTargetOrReadItsFailureAsItsOwn() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val first = Fixture(ownership)
        val next = Fixture(ownership)
        val failure = IllegalStateException("first owner failed")
        assertFalse(first.owner.closeCandidate { throw failure })
        assertTrue(next.owner.cleanupPending())
        assertFalse(next.owner.closeRetained { error("foreign prepare must never run") })
        assertFalse(next.owner.closeCandidate { error("foreign replacement must never run") })
        assertNull(next.owner.cleanupFailureOrNull())
        assertSame(failure, first.owner.cleanupFailureOrNull())
    }

    @Test fun beforeCloseFailurePreventsTheNativeCallAndKeepsTheTargetWithoutReplay() {
        val f = started()
        val failure = IllegalStateException("renderer pre-close failed")
        assertNotNull(f.owner.detachForStop(true))
        assertFalse(f.owner.closeRetained { throw failure })
        assertEquals(0, f.session.closeCalls)
        assertTrue(f.owner.cleanupPending())
        assertSame(failure, f.owner.cleanupFailureOrNull())
        assertFalse(f.owner.closeRetained())
        assertEquals(0, f.session.closeCalls)
    }

    @Test fun successfulCloseAcknowledgementAllowsAnIndependentTarget() {
        val f = Fixture()
        var calls = 0
        assertTrue(f.owner.closeCandidate { calls += 1 })
        assertFalse(f.owner.cleanupPending())
        assertNull(f.owner.cleanupFailureOrNull())
        assertTrue(f.owner.closeCandidate { calls += 1 })
        assertEquals(2, calls)
    }

    @Test fun invalidationRetainsItsExactCapturedTargetBeforeTheCallerDetachesIt() {
        val f = started()
        val captured = f.owner.captureInvalidation { it == Purpose.RUNTIME || it == Purpose.PREFLIGHT }
        assertTrue(captured.ownsPurpose)
        assertTrue(captured.closeOwnerRetained)
        assertSame(f.session, captured.sessionToClose)
        assertSame(f.provider, captured.providerToClose)
        assertNotNull(captured.closeAction)
        assertSame(f.session, f.owner.observe().session)
        assertTrue(f.owner.cleanupPending())
        assertTrue(f.owner.detachInvalidated(captured))
        assertNull(f.owner.observe().session)
        assertEquals(2L, f.owner.observe().generation)
        assertTrue(f.owner.closeRetained())
        assertEquals(1, f.session.closeCalls)
    }

    @Test fun failedInvalidationRetentionKeepsActiveReferencesAndCannotAcknowledgeDetach() {
        val f = started()
        assertFalse(f.owner.closeCandidate { throw IllegalStateException("previous target") })
        val captured = f.owner.captureInvalidation { it == Purpose.RUNTIME }
        assertFalse(captured.closeOwnerRetained)
        assertNull(captured.sessionToClose)
        assertNull(captured.providerToClose)
        assertNull(captured.closeAction)
        assertFalse(f.owner.detachInvalidated(captured))
        assertSame(f.session, f.owner.observe().session)
        assertEquals(1L, f.owner.observe().generation)
    }

    @Test fun anInvalidationFromBeforeReplacementCannotClearTheNewActiveSlot() {
        val f = started()
        val old = f.owner.captureInvalidation { it == Purpose.RUNTIME }
        val replacement = Session(2)
        val provider = Provider(2)
        f.owner.publish(replacement, provider, Purpose.RUNTIME)
        assertFalse(f.owner.detachInvalidated(old))
        assertSame(replacement, f.owner.observe().session)
        assertSame(provider, f.owner.observe().provider)
        assertTrue(f.owner.closeRetained())
        assertEquals(1, f.session.closeCalls)
        assertEquals(0, replacement.closeCalls)
    }

    @Test fun purposeOutsideTheOriginalInvalidationScopeDoesNotCloseOrDetach() {
        val f = Fixture()
        f.owner.publish(f.session, f.provider, Purpose.NONE)
        val captured = f.owner.captureInvalidation { it == Purpose.RUNTIME || it == Purpose.PREFLIGHT }
        assertFalse(captured.ownsPurpose)
        assertNull(captured.closeAction)
        assertFalse(f.owner.cleanupPending())
        assertFalse(f.owner.detachInvalidated(captured))
        assertSame(f.session, f.owner.observe().session)
    }

    @Test fun completedPreflightClosesProviderAndReturnsTheOriginalSessionWithoutClosingIt() {
        val f = Fixture()
        f.owner.publish(f.session, f.provider, Purpose.PREFLIGHT)
        assertSame(f.session, f.owner.detachCompletedPreflight())
        assertEquals(listOf("provider_close"), f.events)
        assertEquals(0, f.session.closeCalls)
        assertNull(f.owner.observe().session)
        assertNull(f.owner.observe().provider)
        assertEquals(Purpose.NONE, f.owner.observe().purpose)
        assertEquals(2L, f.owner.observe().generation)
    }

    @Test fun providerCloseFailurePreservesTheOriginalPartialPreflightDetachAndFailure() {
        val f = Fixture()
        f.owner.publish(f.session, f.provider, Purpose.PREFLIGHT)
        val failure = IllegalStateException("provider close failed")
        f.provider.failure = failure
        assertSame(failure, runCatching { f.owner.detachCompletedPreflight() }.exceptionOrNull())
        assertNull(f.owner.observe().session)
        assertSame(f.provider, f.owner.observe().provider)
        assertEquals(Purpose.PREFLIGHT, f.owner.observe().purpose)
        assertEquals(1L, f.owner.observe().generation)
        assertEquals(0, f.session.closeCalls)
    }

    @Test fun cleanupRunsTheIndependentTailAfterAnUncertainNativeCloseAndPreservesCauses() {
        val f = Fixture()
        val closeFailure = IllegalStateException("native")
        val tailFailure = IllegalStateException("tail")
        val action: () -> Unit = { f.events += "close"; throw closeFailure }
        assertTrue(f.owner.retainClose(action))
        assertFalse(f.owner.completeInvalidationCleanup(action,
            beforeClose = { f.events += "before" },
            afterClose = { f.events += "after"; throw tailFailure }))
        assertEquals(listOf("before", "close", "after"), f.events)
        assertSame(closeFailure, f.owner.cleanupFailureOrNull())
        assertEquals(listOf(tailFailure), closeFailure.suppressed.toList())
        assertTrue(f.owner.cleanupPending())
    }

    @Test fun cleanupWithoutRetainedNativeTargetRunsBothStagesAndPreservesSuppressedFailureOrder() {
        val f = Fixture()
        val before = IllegalStateException("before")
        val after = IllegalStateException("after")
        assertFalse(f.owner.completeInvalidationCleanup(null,
            beforeClose = { f.events += "before"; throw before },
            afterClose = { f.events += "after"; throw after }))
        assertEquals(listOf("before", "after"), f.events)
        assertSame(before, f.owner.cleanupFailureOrNull())
        assertEquals(listOf(after), before.suppressed.toList())
    }

    @Test fun successfulNativeCloseDoesNotHideAnIndependentTailFailure() {
        val f = Fixture()
        val tail = IllegalStateException("tail")
        val action: () -> Unit = { f.events += "close" }
        assertTrue(f.owner.retainClose(action))
        assertFalse(f.owner.completeInvalidationCleanup(action, {}, { throw tail }))
        assertFalse(f.owner.cleanupPending())
        assertSame(tail, f.owner.cleanupFailureOrNull())
        assertTrue(f.owner.closeCandidate {})
        assertNull(f.owner.cleanupFailureOrNull())
    }

    @Test fun diagnosticFailureCannotReplaceTheNativeFailureOrMakeCloseSuccessful() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val owner = RuntimeArSessionOwner<Session, Provider, Purpose>(Any(), Purpose.NONE,
            { it.closeCalls += 1 }, { it.closeCalls += 1 }, ownership,
            { _, _ -> error("optional diagnostics") })
        val native = IllegalStateException("native")
        assertFalse(owner.closeCandidate { throw native })
        assertSame(native, owner.cleanupFailureOrNull())
        assertTrue(owner.cleanupPending())
    }

    @Test fun publicationReturnsItsIssuedGenerationEvenIfItsCommitRetiresThatSlot() {
        val f = Fixture()
        val issued = f.owner.publish(f.session, f.provider, Purpose.RUNTIME) {
            assertNotNull(f.owner.detachForStop(false))
        }
        assertEquals(1L, issued)
        assertEquals(2L, f.owner.observe().generation)
    }

    private fun started() = Fixture().also { it.owner.publish(it.session, it.provider, Purpose.RUNTIME) }
    private enum class Purpose { NONE, PREFLIGHT, RUNTIME }
    private data class Session(val id: Int) { var closeCalls = 0 }
    private data class Provider(val id: Int) { var closeCalls = 0; var failure: RuntimeException? = null }
    private class Fixture(ownership: RuntimeArSessionCleanupOwnership = RuntimeArSessionCleanupOwnership()) {
        val lock = Any()
        val session = Session(1)
        val provider = Provider(1)
        val events = mutableListOf<String>()
        val diagnostics = mutableListOf<Pair<String, RuntimeException>>()
        val owner = RuntimeArSessionOwner<Session, Provider, Purpose>(lock, Purpose.NONE,
            { target -> assertFalse(Thread.holdsLock(lock)); target.closeCalls += 1; events += "session_close" },
            { target -> target.closeCalls += 1; events += "provider_close"; target.failure?.let { throw it } },
            ownership, { event, cause -> diagnostics += event to cause })
    }
}
