package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device check accepted 100 m while the walk gate wants 15 m
 * (`WalkSafeEnvironmentProfiles` `maximumGpsHorizontalAccuracyMeters`), so a fix that could not
 * tell one crossing from the next passed the last gate before walking. 11.5 m was measured
 * indoors on SM-A716S (2026-09-08), so 15 m does not exclude a working phone.
 *
 * Tightening makes failure more common, and every existing reason describes a fix that arrived.
 * Reaching that 11.5 m took three runs returning no fix at all because the phone's network
 * location provider was off — a state the user can fix, but only if told which one it is.
 */
class DeviceCheckLocationFixTighteningTest {
    @Test
    fun theDeviceCheckDemandsTheSameAccuracyTheWalkGateDoes() {
        assertEquals(
            WalkSafeEnvironmentProfiles.testCandidate
                .officialEnvironment
                .maximumGpsHorizontalAccuracyMeters
                .toFloat(),
            DeviceCheckLocationFixPolicy.MAX_ACCURACY_METERS,
            0.001f,
        )
    }

    @Test
    fun aFixCoarserThanFifteenMetresNoLongerPasses() {
        // Comfortably inside the old 100 m gate, and useless for pedestrian guidance.
        assertFalse(DeviceCheckLocationFixPolicy.passes(observation(accuracyMeters = 25f)))
        assertEquals(
            DeviceCheckLocationFixReason.ACCURACY_OUTSIDE_FUNCTIONAL_RANGE,
            DeviceCheckLocationFixPolicy.evaluate(observation(accuracyMeters = 25f)).reason,
        )
    }

    @Test
    fun theAccuracyMeasuredIndoorsStillPasses() {
        assertTrue(DeviceCheckLocationFixPolicy.passes(observation(accuracyMeters = 11.5f)))
        assertTrue(DeviceCheckLocationFixPolicy.passes(observation(accuracyMeters = 15f)))
    }

    @Test
    fun aDisabledProviderIsItsOwnFailureNotAMissingAccuracy() {
        val decision = DeviceCheckLocationFixPolicy.unavailable(
            DeviceCheckLocationFixUnavailability.PROVIDER_DISABLED,
        )

        assertFalse(decision.passed)
        assertEquals(DeviceCheckLocationFixReason.PROVIDER_DISABLED, decision.reason)
        assertEquals(null, decision.accuracyMeters)
    }

    @Test
    fun aFixThatNeverArrivesIsDistinctFromOneThatArrivedTooCoarse() {
        assertEquals(
            DeviceCheckLocationFixReason.NO_FIX_RECEIVED,
            DeviceCheckLocationFixPolicy.unavailable(
                DeviceCheckLocationFixUnavailability.NO_FIX_RECEIVED,
            ).reason,
        )
        assertEquals(
            DeviceCheckLocationFixReason.LOCATION_SERVICE_OFF,
            DeviceCheckLocationFixPolicy.unavailable(
                DeviceCheckLocationFixUnavailability.LOCATION_SERVICE_OFF,
            ).reason,
        )
    }

    @Test
    fun everyFailureCauseCarriesAnActionTheUserCanTake() {
        // A cause the user cannot act on leaves them repeating a check that cannot change.
        DeviceCheckLocationFixUnavailability.entries.forEach { cause ->
            assertTrue(cause.name, cause.userActionKo.isNotBlank())
        }
    }

    @Test
    fun theProviderCauseNamesTheSettingRatherThanTheSymptom() {
        // "위치를 사용할 수 없습니다" is what sent three measurement runs looking at dumpsys.
        val action = DeviceCheckLocationFixUnavailability.PROVIDER_DISABLED.userActionKo
        assertTrue(action, action.contains("위치 정확도"))
    }

    private fun observation(
        latitude: Double = 37.5665,
        longitude: Double = 126.978,
        accuracyMeters: Float? = 12f,
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

    private companion object {
        const val NOW_MS = 100_000L
    }
}
