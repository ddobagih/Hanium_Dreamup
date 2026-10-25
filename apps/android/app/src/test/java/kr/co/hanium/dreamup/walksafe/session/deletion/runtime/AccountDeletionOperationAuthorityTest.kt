package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStatus
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionOperationAuthorityTest {
    @Test fun constructionDoesNotAdmitOrCancelWork() {
        val owner = AccountDeletionOperationAuthority()
        assertFalse(owner.requestInFlight)
        assertEquals(0L, owner.state("generation"))
        assertNull(owner.state("call"))
    }

    @Test fun oneCurrentPreparationRetiresOnlyItsOwnGeneration() {
        val owner = AccountDeletionOperationAuthority()
        val first = owner.beginPreparation()
        val second = owner.beginPreparation()
        assertFalse(owner.isCurrentPreparation(first))
        assertFalse(owner.completePreparation(first))
        assertTrue(owner.isCurrentPreparation(second))
        assertTrue(owner.completePreparation(second))
        assertFalse(owner.isCurrentPreparation(second))
        assertFalse(owner.requestInFlight)
        // The original completion is idempotent when the same generation has no call.
        assertTrue(owner.completePreparation(second))
    }

    @Test fun callAdmissionAndCompletionShareThePreparationSlotAndExactCallIdentity() {
        val owner = AccountDeletionOperationAuthority()
        val preparation = owner.beginPreparation()
        val call = call()
        val generation = owner.admitCall(call)
        assertFalse(owner.isCurrentPreparation(preparation))
        assertFalse(owner.completePreparation(preparation))
        assertFalse(owner.completePreparation(generation))
        assertFalse(owner.completeCall(generation, call()))
        assertFalse(owner.completeCall(preparation, call))
        assertTrue(owner.requestInFlight)
        assertTrue(owner.completeCall(generation, call))
        assertFalse(owner.requestInFlight)
        assertFalse(owner.completeCall(generation, call))
    }

    @Test fun replacedCallIsCancelledUnderOriginalLockBeforeNewGenerationAndPointerPublication() {
        val owner = AccountDeletionOperationAuthority()
        var observed: Triple<Boolean, Long, Any?>? = null
        val old = call {
            observed = Triple(Thread.holdsLock(owner.state("lock")), owner.state("generation") as Long, owner.state("call"))
        }
        val first = owner.admitCall(old)
        val replacement = call()
        val second = owner.admitCall(replacement)
        assertEquals(Triple(true, first, old), observed)
        assertEquals(first + 1L, second)
        assertTrue(old.isCancelled())
        assertSame(replacement, owner.state("call"))
        assertFalse(owner.completeCall(first, old))
        assertTrue(owner.completeCall(second, replacement))
    }

    @Test fun cancellationFailureDoesNotPublishTheReplacementOrAdvanceItsGeneration() {
        val owner = AccountDeletionOperationAuthority()
        val failure = IllegalStateException("controlled cancel failure")
        val old = call { throw failure }
        val generation = owner.admitCall(old)
        val replacement = call()
        try { owner.admitCall(replacement); fail("Cancellation failure must propagate") }
        catch (error: IllegalStateException) { assertSame(failure, error) }
        assertEquals(generation, owner.state("generation"))
        assertSame(old, owner.state("call"))
        assertTrue(owner.requestInFlight)
        assertFalse(replacement.isCancelled())
    }

    @Test fun destroyAdvancesGenerationBeforeCancellingAndInvalidatesAllOldCompletions() {
        val owner = AccountDeletionOperationAuthority()
        var observed: Triple<Boolean, Long, Any?>? = null
        val old = call {
            observed = Triple(Thread.holdsLock(owner.state("lock")), owner.state("generation") as Long, owner.state("call"))
        }
        val generation = owner.admitCall(old)
        owner.destroy()
        assertEquals(Triple(true, generation + 1L, old), observed)
        assertNull(owner.state("call"))
        assertFalse(owner.requestInFlight)
        assertFalse(owner.completeCall(generation, old))
        assertFalse(owner.completePreparation(generation))
    }

    @Test fun destroyPreservesOriginalCancellationExceptionAndAdvancedGeneration() {
        val owner = AccountDeletionOperationAuthority()
        val failure = IllegalStateException("controlled destroy cancel failure")
        val old = call { throw failure }
        val generation = owner.admitCall(old)
        try { owner.destroy(); fail("Cancellation failure must propagate") }
        catch (error: IllegalStateException) { assertSame(failure, error) }
        assertEquals(generation + 1L, owner.state("generation"))
        assertSame(old, owner.state("call"))
        assertTrue(owner.requestInFlight)
        assertFalse(owner.completeCall(generation, old))
        owner.destroy()
        assertNull(owner.state("call"))
        assertFalse(owner.requestInFlight)
    }

    @Test fun concurrentPreparationCannotEnterWhileReplacementCancellationHoldsAdmissionLock() {
        val owner = AccountDeletionOperationAuthority()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val result = AtomicReference<Long?>()
        val errors = AtomicReference<Throwable?>()
        val old = call { entered.countDown(); assertTrue(release.await(2L, TimeUnit.SECONDS)) }
        owner.admitCall(old)
        val replacement = call()
        val admission = Thread { try { owner.admitCall(replacement) } catch (error: Throwable) { errors.set(error) } }
        val preparation = Thread {
            try { result.set(owner.beginPreparation()) } catch (error: Throwable) { errors.set(error) }
            finally { done.countDown() }
        }
        try {
            admission.start()
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            preparation.start()
            assertFalse(done.await(30L, TimeUnit.MILLISECONDS))
            release.countDown()
            admission.join(2_000L); preparation.join(2_000L)
            assertFalse(admission.isAlive); assertFalse(preparation.isAlive)
            assertNull(errors.get())
            assertEquals(3L, result.get())
            assertSame(replacement, owner.state("call"))
            // Beginning preparation alone never cancels an existing call or clears its pointer.
            assertFalse(replacement.isCancelled())
            assertFalse(owner.isCurrentPreparation(requireNotNull(result.get())))
        } finally { release.countDown(); admission.join(2_000L); preparation.join(2_000L) }
    }

    private fun call(onCancel: () -> Unit = {}) = CancellableNetworkCall<AccountDeletionStatus>(
        executeBlock = { error("This admission test must not execute a network request") },
        cancelBlock = onCancel,
    )
    private fun AccountDeletionOperationAuthority.state(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
}
