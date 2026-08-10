package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionIntentFenceTest {
    @Test
    fun stateAcceptsOnlyVerifiedAbsenceAndExactPresence() {
        var stored: Boolean? = false
        val fence = AccountDeletionIntentFence(
            read = { stored },
            store = {
                stored = true
                true
            },
        )

        assertEquals(AccountDeletionIntentFenceState.ABSENT, fence.state())
        stored = true
        assertEquals(AccountDeletionIntentFenceState.PRESENT, fence.state())
        stored = null
        assertEquals(AccountDeletionIntentFenceState.UNAVAILABLE, fence.state())
    }

    @Test
    fun readFailureIsUnavailable() {
        val fence = AccountDeletionIntentFence(
            read = { error("storage unavailable") },
            store = { true },
        )

        assertEquals(AccountDeletionIntentFenceState.UNAVAILABLE, fence.state())
    }

    @Test
    fun successfulEngageIsVisibleToANewProcessInstance() {
        var sharedStored: Boolean? = false
        val first = AccountDeletionIntentFence(
            read = { sharedStored },
            store = {
                sharedStored = true
                true
            },
        )

        assertTrue(first.engage())

        val restarted = AccountDeletionIntentFence(
            read = { sharedStored },
            store = { false },
        )
        assertEquals(AccountDeletionIntentFenceState.PRESENT, restarted.state())
    }

    @Test
    fun engageFailsWhenSynchronousStoreFails() {
        var stored: Boolean? = false
        val rejected = AccountDeletionIntentFence(
            read = { stored },
            store = { false },
        )
        val throwing = AccountDeletionIntentFence(
            read = { stored },
            store = { error("commit failed") },
        )

        assertFalse(rejected.engage())
        assertFalse(throwing.engage())
        assertEquals(AccountDeletionIntentFenceState.ABSENT, rejected.state())
    }

    @Test
    fun failedStoreNeverReportsEngagedButAVisibleWriteStillFencesRestart() {
        var sharedStored: Boolean? = false
        val first = AccountDeletionIntentFence(
            read = { sharedStored },
            store = {
                sharedStored = true
                false
            },
        )

        assertFalse(first.engage())

        val restarted = AccountDeletionIntentFence(
            read = { sharedStored },
            store = { false },
        )
        assertEquals(AccountDeletionIntentFenceState.PRESENT, restarted.state())
    }

    @Test
    fun engageRequiresReadBackVerificationAfterReportedStoreSuccess() {
        var stored: Boolean? = false
        val missing = AccountDeletionIntentFence(
            read = { stored },
            store = { true },
        )
        val unavailable = AccountDeletionIntentFence(
            read = { stored },
            store = {
                stored = null
                true
            },
        )

        assertFalse(missing.engage())
        assertEquals(AccountDeletionIntentFenceState.ABSENT, missing.state())
        assertFalse(unavailable.engage())
        assertEquals(AccountDeletionIntentFenceState.UNAVAILABLE, unavailable.state())
    }

    @Test
    fun clearRequiresSynchronousRemovalAndAbsentReadback() {
        var stored: Boolean? = true
        val fence = AccountDeletionIntentFence(
            read = { stored },
            store = { true },
            clear = {
                stored = false
                true
            },
        )

        assertTrue(fence.clear())
        assertEquals(AccountDeletionIntentFenceState.ABSENT, fence.state())
    }

    @Test
    fun failedThrowingOrUnverifiedClearNeverReportsSuccess() {
        var rejectedStored: Boolean? = true
        val rejected = AccountDeletionIntentFence(
            read = { rejectedStored },
            store = { true },
            clear = {
                rejectedStored = false
                false
            },
        )
        val throwing = AccountDeletionIntentFence(
            read = { true },
            store = { true },
            clear = { error("clear failed") },
        )
        val stillPresent = AccountDeletionIntentFence(
            read = { true },
            store = { true },
            clear = { true },
        )

        assertFalse(rejected.clear())
        assertEquals(AccountDeletionIntentFenceState.ABSENT, rejected.state())
        assertFalse(throwing.clear())
        assertFalse(stillPresent.clear())
    }
}
