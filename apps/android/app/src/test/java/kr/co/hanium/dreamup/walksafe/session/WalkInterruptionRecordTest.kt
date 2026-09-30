package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S03-16: the interruption record distinguishes pause, safety stop and active interruption. */
class WalkInterruptionRecordTest {
    @Test
    fun versionTwoRecordRoundTripsWithoutPersonalData() {
        val record = WalkInterruptionRecord.forCurrentWalk(
            state = WalkSessionState.SAFE_STOP,
            walkSessionId = "walk-1",
            recoveryStage = null,
            safetyStopCauses = listOf("startup_unavailable:CAMERA", "gateway"),
            actorId = "actor-a",
            recordedAtEpochMs = 1_700_000_000_000L,
        )!!
        val encoded = record.encode()

        assertEquals(record, WalkInterruptionRecord.restore(encoded, legacyInterrupted = true))
        assertEquals(WalkInterruptionLastState.SAFE_STOP, record.lastState)
        assertEquals(
            "process_ended_after_safety_stop:startup_unavailable:camera",
            record.reasonCode,
        )
        assertFalse(encoded.contains("actor-a"))
        assertTrue(encoded.contains("\"version\":2"))
    }

    @Test
    fun onlyInterruptingStatesProduceARecord() {
        fun recordFor(state: WalkSessionState, stage: WalkSessionRecoveryStage? = null) =
            WalkInterruptionRecord.forCurrentWalk(state, "walk-1", stage, emptyList(), "a", 1L)

        assertEquals(WalkInterruptionLastState.ACTIVE, recordFor(WalkSessionState.ACTIVE)?.lastState)
        assertEquals(
            "process_ended_while_paused:recheck_required",
            recordFor(
                WalkSessionState.PAUSED,
                WalkSessionRecoveryStage.RECHECK_REQUIRED,
            )?.reasonCode,
        )
        assertNull(recordFor(WalkSessionState.READY))
        assertNull(recordFor(WalkSessionState.ENDED))
    }

    @Test
    fun legacyBooleanMigratesToUnknownAndDamagedJsonIsNotTrusted() {
        val legacy = WalkInterruptionRecord.restore(rawRecord = null, legacyInterrupted = true)!!
        assertEquals(WalkInterruptionLastState.UNKNOWN, legacy.lastState)
        assertEquals(WalkInterruptionRecord.LEGACY_REASON, legacy.reasonCode)

        listOf("{", "{\"version\":1,\"lastState\":\"PAUSED\",\"reasonCode\":\"x\"}",
            "{\"version\":2,\"lastState\":\"RUNNING\",\"reasonCode\":\"x\"}").forEach { damaged ->
            assertEquals(
                WalkInterruptionLastState.UNKNOWN,
                WalkInterruptionRecord.restore(damaged, legacyInterrupted = true)?.lastState,
            )
        }
    }

    @Test
    fun clearedBooleanMarkerIgnoresALeftoverRecord() {
        val record = WalkInterruptionRecord.forCurrentWalk(
            WalkSessionState.ACTIVE, "walk-1", null, emptyList(), "a", 1L,
        )!!
        assertNull(WalkInterruptionRecord.restore(record.encode(), legacyInterrupted = false))
    }

    @Test
    fun pauseSafetyStopAndActiveInterruptionHaveDifferentNotices() {
        val messages = listOf(
            WalkSessionState.ACTIVE,
            WalkSessionState.PAUSED,
            WalkSessionState.SAFE_STOP,
        ).map { state ->
            WalkInterruptionRecord.forCurrentWalk(
                state, "walk-1", WalkSessionRecoveryStage.RECHECK_REQUIRED.takeIf {
                    state == WalkSessionState.PAUSED
                }, emptyList(), "actor-a", 1L,
            )!!.noticeMessageKo(currentActorId = "actor-a")
        }
        assertEquals(3, messages.toSet().size)
        assertTrue(messages[1].contains("일시중지"))
        assertTrue(messages[2].contains("안전 중지"))
        messages.forEach { assertTrue(it.contains("자동으로 재개하지 않습니다")) }
    }

    @Test
    fun anotherAccountSeesOnlyTheAccountNeutralNotice() {
        val record = WalkInterruptionRecord.forCurrentWalk(
            WalkSessionState.SAFE_STOP, "walk-1", null, listOf("x"), "actor-a", 1L,
        )!!
        val neutral = WalkInterruptionRecord.restore(null, true)!!.noticeMessageKo(null)

        assertEquals(neutral, record.noticeMessageKo(currentActorId = "actor-b"))
        assertNotEquals(neutral, record.noticeMessageKo(currentActorId = "actor-a"))
        assertNotEquals(neutral, record.noticeMessageKo(currentActorId = null))
    }
}
