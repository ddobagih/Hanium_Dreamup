package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

class DialogueOwnershipRegressionTest {
    @Test fun callerMutationCannotReplaceTheCandidateThatWasPresented() {
        val original = result("original")
        val mutable = mutableListOf(original)
        val state = DestinationSearchVoiceState("query", mutable)
        assertTrue(state.completeVoicePrompt(state.beginVoicePrompt()))
        mutable[0] = result("replacement")
        mutable.clear()
        assertEquals(listOf(original), state.results)
        assertSame(original, state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).selectedResult)
        assertThrows(UnsupportedOperationException::class.java) {
            (state.results as MutableList).clear()
        }
    }

    @Test fun malformedNumbersNeverBecomeAnotherCandidateNumber() {
        for (phrase in listOf("1.2번 선택", "1,2번 선택", "1 2번 선택", "21번 선택")) {
            assertNull(phrase, parseAndroidVoiceCommand(phrase, allowBareDestinationIndex = true))
            assertNull(phrase, parseDestinationSearchVoiceCommand(phrase, allowBareDestinationIndex = true))
        }
    }

    @Test fun invalidConfidenceCannotAuthorizeAnAction() {
        assertNull(selectAndroidVoiceAction(listOf("도착 확인"), floatArrayOf(1.1f)))
        assertNull(selectAndroidVoiceAction(listOf("도착 확인"), floatArrayOf(.9f), Float.NaN))
        assertNull(selectAndroidVoiceAction(listOf("도착 확인"), floatArrayOf(.9f), -1f))
    }

    @Test fun candidateLeaseExpiresAtDeadlineAndCannotReviveAfterClockRegression() {
        var now = 1_000L
        val state = DestinationSearchVoiceState("query", listOf(result("one")))
        val lease = HomeDestinationVoiceDialogLease("actor", 7L, 3, state, { now })
        fun current() = lease.currentState("actor", 7L, 3, "query", state, true, true)
        now = 30_999L
        assertSame(state, current())
        now = 31_000L
        assertNull(current())
        now = 1_001L
        assertNull(current())
    }

    @Test fun regressedClockImmediatelyClosesCandidateLease() {
        var now = 1_000L
        val state = DestinationSearchVoiceState("query", listOf(result("one")))
        val lease = HomeDestinationVoiceDialogLease("actor", 7L, 3, state, { now })
        now = 999L
        assertNull(lease.currentState("actor", 7L, 3, "query", state, true, true))
    }

    @Test fun staleRouteRequestCannotConsumeOrClearSameContextReplacement() {
        val pending = PendingExplicitRouteStart<String>()
        val old = pending.replace("same", 1_000L)
        val replacement = pending.replace("same", 2_000L)
        assertNull(pending.consumeIfCurrent(old, "same", 2_001L))
        assertSame(replacement, pending.currentOrNull())
        assertEquals("same", pending.consumeIfCurrent(replacement, "same", 2_002L))
    }

    @Test fun clockRegressionExpiresRouteRequestWithoutThrowingOrStarting() {
        val pending = PendingExplicitRouteStart<String>()
        val request = pending.replace("same", 1_000L)
        assertNull(pending.consumeIfCurrent(request, "same", 999L))
        assertTrue(pending.expireIfCurrent(request, "same", 999L))
    }

    @Test fun permissionRevocationDuringRegistrationFailsClosedAndAllowsRetry() {
        var reject = true
        val callbacks = mutableListOf<RoutePoint?>()
        val controller = DestinationSearchLocationController(
            DestinationSearchLocationSource { callback ->
                if (reject) throw SecurityException("permission revoked")
                callback(fix());
                {}
            },
            { 1_000L },
        )
        val lease = DestinationSearchLocationLease("actor", 1L)
        assertFalse(controller.acquire(lease, { true }, callbacks::add))
        assertFalse(controller.isAcquiring)
        assertEquals(listOf<RoutePoint?>(null), callbacks)
        reject = false
        assertTrue(controller.acquire(lease, { true }, callbacks::add))
        assertNotNull(callbacks.last())
    }

    @Test fun cancellationFailureStillRejectsLateLocationAndPreservesNewOwner() {
        val sourceCallbacks = mutableListOf<(DestinationSearchLocationFix?) -> Unit>()
        val values = mutableListOf<RoutePoint?>()
        val controller = DestinationSearchLocationController(
            DestinationSearchLocationSource { callback ->
                sourceCallbacks += callback
                { throw IllegalStateException("provider already closed") }
            },
            { 1_000L },
        )
        controller.acquire(DestinationSearchLocationLease("old", 1L), { true }, values::add)
        controller.cancel()
        controller.acquire(DestinationSearchLocationLease("new", 2L), { true }, values::add)
        sourceCallbacks[0](fix())
        assertTrue(values.isEmpty())
        assertTrue(controller.isAcquiring)
        sourceCallbacks[1](fix())
        assertEquals(1, values.size)
        assertFalse(controller.isAcquiring)
    }

    @Test fun revokedPermissionRejectsAlreadyQueuedLocation() {
        var allowed = true
        var callback: ((DestinationSearchLocationFix?) -> Unit)? = null
        val values = mutableListOf<RoutePoint?>()
        val controller = DestinationSearchLocationController(
            DestinationSearchLocationSource { callback = it; {} }, { 1_000L },
        )
        controller.acquire(DestinationSearchLocationLease("actor", 1L), { allowed }, values::add)
        allowed = false
        requireNotNull(callback)(fix())
        assertFalse(controller.isAcquiring)
        assertTrue(values.isEmpty())
    }

    private fun fix() = DestinationSearchLocationFix(37.0, 127.0, 3f, 1_000L)
    private fun result(id: String) = DestinationSearchResult(id, id, RoutePoint(37.0, 127.0), null, null, null, null)
}
