package kr.co.hanium.dreamup.walksafe.device

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DeviceCheckTraceTest {
    @Test fun oneFlowConnectsThePhaseOutcomeAndCurrentAttemptWithoutIdentityText() {
        val line = formatDeviceCheckTrace(DeviceCheckTraceEvent(17L,
            DeviceCheckTraceStage.AUTHORITY_RECONCILE, DeviceCheckTraceOutcome.BLOCKED,
            DeviceCheckTraceReason.RECONCILE_FAILED, sessionGeneration = 3L, attemptGeneration = 8L,
            state = PostLoginDeviceCheckState.FAIL, failure = PostLoginDeviceCheckFailure.RESULT_SAVE_UNCONFIRMED))
        assertEquals("event=DEVICE_CHECK flow_id=17 stage=AUTHORITY_RECONCILE outcome=BLOCKED " +
            "reason=RECONCILE_FAILED session_generation=3 attempt_generation=8 " +
            "state=FAIL failure=RESULT_SAVE_UNCONFIRMED", line)
        assertFalse(line.contains('\n'))
    }

    @Test fun invalidNumbersAreAbsentAndUnknownGenerationsStayExplicit() {
        for (flow in listOf(0L, -1L, Long.MIN_VALUE)) {
            val line = formatDeviceCheckTrace(DeviceCheckTraceEvent(flow,
                DeviceCheckTraceStage.UI_ACTION, DeviceCheckTraceOutcome.STARTED,
                sessionGeneration = -1L, attemptGeneration = Long.MIN_VALUE))
            assertTrue(line.contains("flow_id=NA"))
            assertTrue(line.contains("session_generation=NA attempt_generation=NA"))
            assertFalse(line.contains("-1"))
            assertFalse(line.contains(Long.MIN_VALUE.toString()))
        }
        val unknown = formatDeviceCheckTrace(DeviceCheckTraceEvent(1L,
            DeviceCheckTraceStage.ATTEMPT, DeviceCheckTraceOutcome.STARTED))
        assertTrue(unknown.contains("session_generation=NA attempt_generation=NA"))
        assertTrue(unknown.endsWith("state=NA failure=NA"))
        val zero = formatDeviceCheckTrace(DeviceCheckTraceEvent(1L,
            DeviceCheckTraceStage.ATTEMPT, DeviceCheckTraceOutcome.STARTED,
            sessionGeneration = 0L, attemptGeneration = 0L))
        assertTrue(zero.contains("session_generation=0 attempt_generation=0"))
    }

    @Test fun everyAllowedMetadataCombinationFitsTheExistingBoundedRecord() {
        for (stage in DeviceCheckTraceStage.values()) for (outcome in DeviceCheckTraceOutcome.values())
            for (reason in DeviceCheckTraceReason.values()) {
                val line = formatDeviceCheckTrace(DeviceCheckTraceEvent(Long.MAX_VALUE, stage, outcome, reason,
                    Long.MAX_VALUE, Long.MAX_VALUE,
                    PostLoginDeviceCheckState.values().maxBy { it.name.length },
                    PostLoginDeviceCheckFailure.values().maxBy { it.name.length }))
                // Leave room for the writer's run_id, elapsed_ms and dropped_total prefix.
                assertTrue(line.toByteArray(Charsets.UTF_8).size < 512)
                assertTrue(line.matches(Regex("[A-Z_a-z0-9= ]+")))
            }
    }

    @Test fun everyActualCheckStateAndFailureKeepsItsExactTypedCauseWithinTheBound() {
        for (state in PostLoginDeviceCheckState.values()) for (failure in PostLoginDeviceCheckFailure.values()) {
            val line = formatDeviceCheckTrace(DeviceCheckTraceEvent(Long.MAX_VALUE,
                DeviceCheckTraceStage.ATTEMPT, DeviceCheckTraceOutcome.FAILED,
                DeviceCheckTraceReason.TIMED_OUT, Long.MAX_VALUE, Long.MAX_VALUE, state, failure))
            assertTrue(line.endsWith("state=" + state.name + " failure=" + failure.name))
            assertTrue(line.contains("reason=TIMED_OUT"))
            assertTrue(line.toByteArray(Charsets.UTF_8).size < 512)
            assertTrue(line.matches(Regex("[A-Z_a-z0-9= ]+")))
        }
    }

    @Test fun eventInputCannotAcceptRawIdentityMessageExceptionOrImage() {
        val allowed = setOf(Long::class.javaPrimitiveType, Long::class.javaObjectType,
            DeviceCheckTraceStage::class.java, DeviceCheckTraceOutcome::class.java,
            DeviceCheckTraceReason::class.java, PostLoginDeviceCheckState::class.java,
            PostLoginDeviceCheckFailure::class.java)
        val inputs = DeviceCheckTraceEvent::class.java.declaredConstructors.filterNot { it.isSynthetic }
        assertEquals(1, inputs.size)
        assertEquals(8, inputs.single().parameterCount)
        assertTrue(inputs.single().parameterTypes.all { it in allowed })
    }

    @Test fun diagnosticFailureCannotEscapeOrRepeatTheOperation() {
        val calls = AtomicInteger()
        val sink = DeviceCheckTraceSink { calls.incrementAndGet(); throw IllegalStateException("diagnostic failure") }
        val event = DeviceCheckTraceEvent(1L, DeviceCheckTraceStage.LEASE_BUDGET, DeviceCheckTraceOutcome.RELEASED)
        sink.emit(event)
        assertEquals(1, calls.get())
        DeviceCheckTraceSink.NONE.emit(event)
    }

    @Test fun parallelCallbacksReceiveDistinctPositiveProcessLocalFlowNumbers() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val ids = executor.invokeAll(List(128) { java.util.concurrent.Callable { DeviceCheckTraceIds.next() } })
                .map { it.get(2, TimeUnit.SECONDS) }
            assertEquals(ids.size, ids.toSet().size)
            assertTrue(ids.all { it > 0L })
        } finally {
            executor.shutdownNow()
        }
    }
}
