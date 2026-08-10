package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidReportCooldownPolicyTest {
    @Test
    fun realDistanceMatchClosesRoundedCellBoundaryBypass() {
        val leftOfCellBoundary = scope(longitude = 127.000049)
        val rightOfCellBoundary = scope(longitude = 127.000051)

        assertNotEquals(leftOfCellBoundary.storageKey, rightOfCellBoundary.storageKey)
        assertTrue(AndroidReportCooldownPolicy.isSameCooldownArea(leftOfCellBoundary, rightOfCellBoundary))
        assertTrue(
            !AndroidReportCooldownPolicy.isSameCooldownArea(
                leftOfCellBoundary,
                scope(latitude = 37.0003, longitude = 127.000049),
            ),
        )
        assertTrue(
            !AndroidReportCooldownPolicy.isSameCooldownArea(
                leftOfCellBoundary,
                scope(actorId = "field-user-2", longitude = 127.000049),
            ),
        )
    }

    @Test
    fun automaticCooldownSurvivesTrackerAndCellChangesButVoiceBypassesIt() {
        val store = AndroidReportAttemptStore()
        val first = store.acquire(scope(longitude = 127.000049), nowMs = 1_000L, bypassAutomaticCooldown = false)
            as AndroidReportAttemptResult.Allowed
        store.markSucceeded(first.lease, nowMs = 2_000L)
        store.release(first.lease)

        val automatic = store.acquire(
            scope(longitude = 127.000051),
            nowMs = 3_000L,
            bypassAutomaticCooldown = false,
        ) as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN, automatic.reason)
        assertEquals(AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS - 1_000L, automatic.remainingMs)

        val voice = store.acquire(
            scope(longitude = 127.000051),
            nowMs = 3_000L,
            bypassAutomaticCooldown = true,
        ) as AndroidReportAttemptResult.Allowed
        store.release(voice.lease)

        val afterBoundary = store.acquire(
            scope(longitude = 127.000051),
            nowMs = 2_000L + AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS,
            bypassAutomaticCooldown = false,
        )
        assertTrue(afterBoundary is AndroidReportAttemptResult.Allowed)
    }

    @Test
    fun stateStoreIsCappedAndExpiredEntriesArePruned() {
        assertEquals(512, AndroidReportCooldownPolicy.MAX_TRACKED_STATES)
        val store = AndroidReportAttemptStore(maxTrackedStates = 3)
        repeat(3) { index ->
            val allowed = store.acquire(
                scope(latitude = 37.0 + index * 0.001),
                nowMs = 1_000L,
                bypassAutomaticCooldown = false,
            ) as AndroidReportAttemptResult.Allowed
            store.release(allowed.lease)
        }

        val capacityBlocked = store.acquire(
            scope(latitude = 37.003),
            nowMs = 1_001L,
            bypassAutomaticCooldown = false,
        ) as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportAttemptBlockReason.STATE_CAPACITY, capacityBlocked.reason)
        assertEquals(3, store.trackedStateCount())

        val afterExpiry = store.acquire(
            scope(latitude = 37.003),
            nowMs = 1_000L + AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS,
            bypassAutomaticCooldown = false,
        )
        assertTrue(afterExpiry is AndroidReportAttemptResult.Allowed)
        assertEquals(1, store.trackedStateCount())
    }

    @Test
    fun successfulReportMovesRadiusCenterWithoutAllowingChainedBoundaryBypass() {
        val store = AndroidReportAttemptStore()
        val first = store.acquire(scope(latitude = 37.0), 1_000L, false) as AndroidReportAttemptResult.Allowed
        store.markSucceeded(first.lease, 1_000L)
        store.release(first.lease)

        val secondAtTwentyMeters = store.acquire(
            scope(latitude = 37.00018),
            1_000L + AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS,
            false,
        ) as AndroidReportAttemptResult.Allowed
        store.markSucceeded(secondAtTwentyMeters.lease, 1_000L + AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS)
        store.release(secondAtTwentyMeters.lease)

        val fortyMetersFromOriginal = store.acquire(
            scope(latitude = 37.00036),
            2_000L + AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS,
            false,
        ) as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN, fortyMetersFromOriginal.reason)
    }

    @Test
    fun successfulCooldownCanBeBoundedlyRestoredAfterActivityOrProcessRecreation() {
        val firstStore = AndroidReportAttemptStore(maxTrackedStates = 3)
        val first = firstStore.acquire(scope(), 1_000L, false) as AndroidReportAttemptResult.Allowed
        firstStore.markSucceeded(first.lease, 2_000L)
        firstStore.release(first.lease)
        val persisted = firstStore.successfulCooldowns(nowMs = 3_000L)

        val restoredStore = AndroidReportAttemptStore(maxTrackedStates = 3)
        restoredStore.restoreSuccessfulCooldowns(persisted, nowMs = 3_000L)
        val nearby = restoredStore.acquire(
            scope(longitude = 127.000051),
            nowMs = 4_000L,
            bypassAutomaticCooldown = false,
        ) as AndroidReportAttemptResult.Blocked

        assertEquals(1, persisted.size)
        assertEquals(AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN, nearby.reason)
        assertEquals(AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS - 2_000L, nearby.remainingMs)
    }

    @Test
    fun restoreDropsExpiredCooldownsAndCapsFutureClockSkew() {
        val restoredStore = AndroidReportAttemptStore(maxTrackedStates = 2)
        restoredStore.restoreSuccessfulCooldowns(
            listOf(
                AndroidReportSuccessfulCooldown(scope(latitude = 37.0), lastUploadedAtMs = 1_000L),
                AndroidReportSuccessfulCooldown(scope(latitude = 37.001), lastUploadedAtMs = 99_000L),
                AndroidReportSuccessfulCooldown(scope(latitude = 37.002), lastUploadedAtMs = 99_000L),
            ),
            nowMs = 20_000L,
        )

        assertEquals(2, restoredStore.successfulCooldowns(nowMs = 20_000L).size)
        val futureRecord = restoredStore.acquire(scope(latitude = 37.001), 20_001L, false)
            as AndroidReportAttemptResult.Blocked
        assertEquals(AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS - 1L, futureRecord.remainingMs)
    }

    private fun scope(
        actorId: String = "field-user-1",
        latitude: Double = 37.0,
        longitude: Double = 127.0,
    ): AndroidReportSpatialScope {
        return AndroidReportCooldownPolicy.spatialScopeOrNull(
            actorId = actorId,
            className = AndroidReportCandidatePolicy.DAMAGED_TACTILE_BLOCK,
            location = TrustedLocation(latitude, longitude, 5f, 1_000L),
        )!!
    }
}
