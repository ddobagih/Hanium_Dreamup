package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCheckLocationFixPolicyTest {
    @Test
    fun batchSelectionRejectsMockRegardlessOfOrder() {
        val pass = decision(ageMs = 1_000L, reason = DeviceCheckLocationFixReason.PASS)
        val mock = decision(ageMs = 100L, reason = DeviceCheckLocationFixReason.MOCK_LOCATION)

        assertEquals(
            DeviceCheckLocationFixReason.MOCK_LOCATION,
            DeviceCheckLocationFixPolicy.selectBatch(listOf(pass, mock))?.reason,
        )
        assertEquals(
            DeviceCheckLocationFixReason.MOCK_LOCATION,
            DeviceCheckLocationFixPolicy.selectBatch(listOf(mock, pass))?.reason,
        )
    }

    @Test
    fun batchSelectionUsesTheFreshestPassingSampleWhenNoMockExists() {
        val poor = decision(
            ageMs = 50L,
            reason = DeviceCheckLocationFixReason.ACCURACY_OUTSIDE_FUNCTIONAL_RANGE,
        )
        val olderPass = decision(ageMs = 2_000L, reason = DeviceCheckLocationFixReason.PASS)
        val newerPass = decision(ageMs = 500L, reason = DeviceCheckLocationFixReason.PASS)

        assertEquals(
            newerPass,
            DeviceCheckLocationFixPolicy.selectBatch(listOf(poor, olderPass, newerPass)),
        )
    }

    @Test
    fun freshAccurateRealFixPassesWithoutADeviceModelList() {
        val decision = DeviceCheckLocationFixPolicy.evaluate(
            observation(accuracyMeters = 25f, ageMs = 2_000L),
        )

        assertTrue(decision.passed)
        assertEquals(DeviceCheckLocationFixReason.PASS, decision.reason)
    }

    @Test
    fun mockStaleInaccurateAndInvalidFixesFail() {
        assertFalse(DeviceCheckLocationFixPolicy.passes(observation(mock = true)))
        assertFalse(
            DeviceCheckLocationFixPolicy.passes(
                observation(ageMs = DeviceCheckLocationFixPolicy.MAX_AGE_MS + 1L),
            ),
        )
        assertFalse(
            DeviceCheckLocationFixPolicy.passes(
                observation(
                    accuracyMeters = DeviceCheckLocationFixPolicy.MAX_ACCURACY_METERS + 1f,
                ),
            ),
        )
        assertFalse(
            DeviceCheckLocationFixPolicy.passes(
                observation(latitude = Double.NaN),
            ),
        )
    }

    @Test
    fun failureReasonDistinguishesNoAccuracyPoorAccuracyAndInvalidTime() {
        assertEquals(
            DeviceCheckLocationFixReason.ACCURACY_UNAVAILABLE,
            DeviceCheckLocationFixPolicy.evaluate(observation(accuracyMeters = null)).reason,
        )
        assertEquals(
            DeviceCheckLocationFixReason.ACCURACY_OUTSIDE_FUNCTIONAL_RANGE,
            DeviceCheckLocationFixPolicy.evaluate(
                observation(accuracyMeters = DeviceCheckLocationFixPolicy.MAX_ACCURACY_METERS + 1f),
            ).reason,
        )
        assertEquals(
            DeviceCheckLocationFixReason.FUTURE_TIMESTAMP,
            DeviceCheckLocationFixPolicy.evaluate(observation(ageMs = -1L)).reason,
        )
    }

    private fun observation(
        latitude: Double = 37.5665,
        longitude: Double = 126.978,
        accuracyMeters: Float? = 20f,
        ageMs: Long = 1_000L,
        mock: Boolean = false,
    ) = DeviceCheckLocationFixObservation(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        observedAtElapsedRealtimeMs = NOW_MS - ageMs,
        nowElapsedRealtimeMs = NOW_MS,
        mock = mock,
    )

    private fun decision(
        ageMs: Long,
        reason: DeviceCheckLocationFixReason,
    ) = DeviceCheckLocationFixDecision(
        passed = reason == DeviceCheckLocationFixReason.PASS,
        reason = reason,
        accuracyMeters = if (reason == DeviceCheckLocationFixReason.PASS) 12f else 150f,
        ageMs = ageMs,
    )

    private companion object {
        const val NOW_MS = 100_000L
    }
}
