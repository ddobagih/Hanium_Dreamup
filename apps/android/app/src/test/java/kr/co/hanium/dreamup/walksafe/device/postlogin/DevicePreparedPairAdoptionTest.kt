package kr.co.hanium.dreamup.walksafe.device.postlogin

import org.junit.Assert.*
import org.junit.Test

class DevicePreparedPairAdoptionTest {
    @Test fun failedLeaseCleanupWaitsForHostNativeJournalAndConsumerProofs() {
        val primary = java.util.concurrent.CompletableFuture<Unit>()
        val auxiliary = java.util.concurrent.CompletableFuture<Boolean>()
        val actual = java.util.concurrent.CompletableFuture<Unit>()
        val available = java.util.concurrent.CompletableFuture<Unit>()
        val host = java.util.concurrent.CompletableFuture<Unit>()
        val consumer = java.util.concurrent.CompletableFuture<Unit>()
        val registry = java.util.concurrent.CompletableFuture<Unit>()
        val done = DeviceRuntimeLeaseFailureCleanup.afterRelease(primary, auxiliary, actual, available, host, consumer, registry)
        actual.complete(Unit)
        assertFalse(done.isDone); assertFalse(host.isDone)
        primary.complete(Unit)
        assertFalse(host.isDone)
        auxiliary.complete(true)
        assertEquals(Unit, host.getNow(null)); assertEquals(Unit, consumer.getNow(null))
        registry.complete(Unit)
        assertFalse(done.isDone) // Physical death alone never forgives a pending journal ACK.
        available.complete(Unit)
        assertEquals(Unit, done.getNow(null))
        available.complete(Unit); auxiliary.complete(true)
        assertEquals(Unit, done.getNow(null))
    }

    @Test fun nativeDeathCannotForgiveFalseAuxiliaryOrUnprovenLocalCleanup() {
        for (primaryFailed in listOf(false, true)) {
            val primary = java.util.concurrent.CompletableFuture<Unit>()
            val host = java.util.concurrent.CompletableFuture<Unit>()
            val consumer = java.util.concurrent.CompletableFuture<Unit>()
            val done = DeviceRuntimeLeaseFailureCleanup.afterRelease(primary,
                java.util.concurrent.CompletableFuture.completedFuture(primaryFailed),
                java.util.concurrent.CompletableFuture.completedFuture(Unit),
                java.util.concurrent.CompletableFuture.completedFuture(Unit), host, consumer, null)
            if (primaryFailed) primary.completeExceptionally(IllegalStateException("unproven local release"))
            else primary.complete(Unit)
            assertTrue(done.isCompletedExceptionally)
            assertFalse(host.isDone); assertFalse(consumer.isDone)
        }
    }

    @Test fun journalOrConsumerFailureCannotReturnAFailedClaimsBudget() {
        for (journalFailed in listOf(false, true)) {
            val journal = java.util.concurrent.CompletableFuture<Unit>()
            val consumer = java.util.concurrent.CompletableFuture<Unit>()
            val done = DeviceRuntimeLeaseFailureCleanup.afterRelease(
                java.util.concurrent.CompletableFuture.completedFuture(Unit),
                java.util.concurrent.CompletableFuture.completedFuture(true),
                java.util.concurrent.CompletableFuture.completedFuture(Unit), journal,
                java.util.concurrent.CompletableFuture(), null, consumer)
            if (journalFailed) { journal.completeExceptionally(IllegalStateException("journal")); consumer.complete(Unit) }
            else { journal.complete(Unit); consumer.completeExceptionally(IllegalStateException("consumer")) }
            assertTrue(done.isCompletedExceptionally)
        }
    }

    @Test fun allocationWaitsForCapturedJournalAvailabilityAfterPhysicalReleaseWithoutAnOwnerLookup() {
        val host = java.util.concurrent.CompletableFuture<Unit>()
        val physical = java.util.concurrent.CompletableFuture<Unit>()
        val available = java.util.concurrent.CompletableFuture<Unit>()
        data class Captured(val physical: java.util.concurrent.CompletableFuture<Unit>,
            val available: java.util.concurrent.CompletableFuture<Unit>)
        val captured = Captured(physical, available)
        var exits = 0
        val retired = DeviceRuntimeLeaseRetirement.afterHostDrain<Any, Captured>(
            null, host, snapshot = { error("No latest-owner lookup") },
            actualReleased = { java.util.concurrent.CompletableFuture.allOf(it.physical, it.available) },
            requestExit = { assertSame(captured, it); exits++ }, knownRetirement = captured)
        host.complete(Unit)
        physical.complete(Unit)
        assertFalse(retired.isDone)
        assertEquals(1, exits)
        available.complete(Unit)
        assertEquals(Unit, retired.getNow(null))
    }

    @Test fun aKnownRetirementIsJoinedEvenWithoutAnOwnerInTheRecreatedMain() {
        val host = java.util.concurrent.CompletableFuture<Unit>()
        val physical = java.util.concurrent.CompletableFuture<Unit>()
        val captured = Any()
        val exits = mutableListOf<Any>()
        val retired = DeviceRuntimeLeaseRetirement.afterHostDrain<Any, Any>(null, host,
            snapshot = { error("No opaque owner was reserved") }, actualReleased = { assertSame(captured, it); physical },
            requestExit = { exits += it }, knownRetirement = captured)
        assertFalse(retired.isDone)
        host.complete(Unit)
        assertSame(captured, exits.single())
        assertFalse(retired.isDone)
        physical.complete(Unit)
        assertTrue(retired.isDone)
    }

    @Test fun expiryOrCancellationBeforeDelegatedAttachAcknowledgesNoCreateExactlyOnce() {
        for (reason in listOf("take_expired", "adopt_cancelled")) {
            var noCreate = 0
            val boundary = DeviceRuntimeCreationBoundary { noCreate++ }
            val failure = IllegalStateException(reason)
            try {
                try { throw failure } finally { boundary.finish() }
            } catch (observed: IllegalStateException) { assertSame(failure, observed) }
            boundary.finish()
            assertEquals(1, noCreate)
        }
        var noCreate = 0
        val delegated = DeviceRuntimeCreationBoundary { noCreate++ }
        delegated.enteredAttachment()
        delegated.finish()
        assertEquals(0, noCreate) // Its actual partial-creation receipt is owned by attach's finally.
    }

    @Test fun lateOldAcquireIsCapturedAfterDrainWithoutQueryingANewerOwner() {
        val oldOwner = Any()
        val newOwner = Any()
        var currentOwner = oldOwner
        val host = java.util.concurrent.CompletableFuture<Unit>()
        val native = java.util.concurrent.CompletableFuture<Unit>()
        var oldCohort: Any? = null
        var queries = 0
        val exits = mutableListOf<Any>()
        val retired = DeviceRuntimeLeaseRetirement.afterHostDrain(oldOwner, host,
            snapshot = { captured -> assertSame(oldOwner, captured); queries++; oldCohort },
            actualReleased = { assertSame(oldCohort, it); native }, requestExit = { exits += it })
        assertEquals(0, queries)
        oldCohort = Any() // A queued old create acquires only after the earlier UI snapshot would have been null.
        currentOwner = newOwner
        host.complete(Unit)
        assertSame(newOwner, currentOwner)
        assertEquals(1, queries)
        assertSame(oldCohort, exits.single())
        assertFalse(retired.isDone)
        native.complete(Unit)
        assertTrue(retired.isDone)
    }

    @Test fun exitControlFailureStillRequiresTheActualLatePhysicalReceipt() {
        val host = java.util.concurrent.CompletableFuture.completedFuture(Unit)
        val physical = java.util.concurrent.CompletableFuture<Unit>()
        val retired = DeviceRuntimeLeaseRetirement.afterHostDrain(Any(), host, { Any() }, { physical },
            { throw IllegalStateException("exit control failed") })
        assertFalse(retired.isDone)
        physical.complete(Unit)
        assertTrue(retired.isDone)
        assertFalse(retired.isCompletedExceptionally)
    }

    @Test fun actualBothReceiversAndVerificationPrecedeTheOnlyReadinessPublication() {
        val f = Fixture()
        f.coordinator.prepared(f.pair)
        assertEquals(listOf("primary"), f.effects)
        f.primaryReply!!(true)
        assertEquals(listOf("primary", "auxiliary", "verify"), f.effects)
        assertEquals(0, f.ready)
        f.verified = true
        f.coordinator.poll()
        f.coordinator.poll()
        assertEquals(listOf("primary", "auxiliary", "verify", "verify", "publish"), f.effects)
        assertEquals(1, f.ready)
        assertEquals(0, f.failures)
    }

    @Test fun cancellationDuringPrimaryAdoptionCannotAdoptAuxiliaryOrPublishLateSuccess() {
        val f = Fixture()
        f.coordinator.prepared(f.pair)
        f.current = false
        f.coordinator.fail("actor_changed")
        f.primaryReply!!(true)
        assertEquals(listOf("primary"), f.effects)
        assertEquals(1, f.failures)
        assertEquals(0, f.ready)
    }

    @Test fun auxiliaryAdoptionReentryAndDuplicatePrimaryReplyNeverPublishReadiness() {
        val f = Fixture()
        f.auxiliaryEffect = { f.coordinator.fail("cancelled_during_auxiliary"); true }
        f.coordinator.prepared(f.pair)
        f.primaryReply!!(true)
        f.primaryReply!!(true)
        f.coordinator.prepared(f.pair)
        assertEquals(listOf("primary", "auxiliary"), f.effects)
        assertEquals(1, f.failures)
        assertEquals(0, f.ready)
    }

    @Test fun cancelledPublicationRetiresOnceAndCannotMakeTheControllerReady() {
        val f = Fixture()
        f.verified = true
        f.publishEffect = { f.current = false; true }
        f.coordinator.prepared(f.pair)
        f.primaryReply!!(true)
        f.coordinator.poll()
        assertEquals(1, f.failures)
        assertEquals(0, f.ready)
    }

    @Test fun preparedCompletionDoesNotRunTheLegacyIndependentStoragePath() {
        val results = mutableListOf<DeviceCompositeAttemptState.Completion>()
        val state = DeviceCompositeAttemptState<String>(results::add)
        state.beginRunning()
        state.receiveReleasedCandidate("actual-pair", { true }, {}, {}, {})
        assertTrue(state.acceptPrepared { true })
        assertTrue(state.complete(true, "actual_both_models_ready", {}, {}, {}))
        assertEquals(listOf(DeviceCompositeAttemptState.Completion(true, true, "actual_both_models_ready")), results)
    }

    @Test fun aSelectedCandidateAloneCannotCompleteWithSuccess() {
        val results = mutableListOf<DeviceCompositeAttemptState.Completion>()
        val state = DeviceCompositeAttemptState<String>(results::add)
        state.beginRunning()
        state.receiveReleasedCandidate("historical-choice", { true }, {}, {}, {})
        assertTrue(state.complete(true, "unprepared", {}, {}, {}))
        assertFalse(results.single().success)
    }

    private class Fixture {
        val pair = Any()
        var current = true
        var verified = false
        var ready = 0
        var failures = 0
        val effects = mutableListOf<String>()
        var primaryReply: ((Boolean) -> Unit)? = null
        var auxiliaryEffect: () -> Boolean = { true }
        var publishEffect: () -> Boolean = { true }
        val coordinator = DevicePreparedPairAdoption<Any>(
            current = { current },
            adoptPrimary = { value, reply -> assertSame(pair, value); effects += "primary"; primaryReply = reply },
            adoptAuxiliary = { value -> assertSame(pair, value); effects += "auxiliary"; auxiliaryEffect() },
            verification = { value -> assertSame(pair, value); effects += "verify"
                if (verified) DevicePreparedPairAdoption.Verification.VERIFIED else DevicePreparedPairAdoption.Verification.PENDING },
            publish = { value -> assertSame(pair, value); effects += "publish"; publishEffect() },
            onReady = { ready++ }, onFailure = { failures++ },
        )
    }
}
