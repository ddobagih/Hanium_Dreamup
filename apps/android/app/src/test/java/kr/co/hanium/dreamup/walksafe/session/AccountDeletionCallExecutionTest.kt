package kr.co.hanium.dreamup.walksafe.session

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.network.AccountDeletionCallExecution
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.executeAccountDeletionCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionCallExecutionTest {
    @Test
    fun checkedIOExceptionKeepsOwnershipUntilUiDispositionThenAllowsNextRegistration() {
        val machine = AccountDeletionStateMachine()
        val journal = AccountDeletionJournal.pending(
            gatewayOrigin = "https://gateway.example.test",
            installationId = "install-test-0001",
            requestId = "account_delete_" + "1".repeat(64),
            requestedAt = "2026-08-10T00:00:00Z",
        )
        assertTrue(machine.requestConfirmation())
        assertTrue(machine.begin(journal))
        val attempt = requireNotNull(machine.beginWorkerAttempt(journal))
        val firstCall = CancellableNetworkCall.blocking<Unit> {
            throw IOException("checked transport failure")
        }
        val firstLease = requireNotNull(
            machine.registerNetworkCall(attempt, firstCall::cancel),
        )
        assertTrue(machine.beginNetworkCall(firstLease))
        val localInFlight = AtomicBoolean(true)
        val cleanupCalls = AtomicInteger()
        val releaseOwnership = {
            cleanupCalls.incrementAndGet()
            val stateReleased = machine.releaseNetworkCall(firstLease)
            val localReleased = localInFlight.compareAndSet(true, false)
            stateReleased && localReleased
        }

        val result = executeAccountDeletionCall(firstCall, releaseOwnership)

        assertTrue(result is AccountDeletionCallExecution.Failed)
        assertTrue((result as AccountDeletionCallExecution.Failed).error is IOException)
        assertTrue(cleanupCalls.get() == 0)
        assertTrue(localInFlight.get())

        val blockedCall = CancellableNetworkCall.blocking { Unit }
        assertNull(machine.registerNetworkCall(attempt, blockedCall::cancel))

        assertTrue(releaseOwnership())
        assertTrue(cleanupCalls.get() == 1)
        assertFalse(localInFlight.get())
        assertFalse(machine.releaseNetworkCall(firstLease))

        val nextCall = CancellableNetworkCall.blocking { Unit }
        val nextLease = machine.registerNetworkCall(attempt, nextCall::cancel)
        assertNotNull(nextLease)
    }
}
