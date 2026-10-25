package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteFacingContinuityGateTest {
    @Test fun coldNominalRequiresTwoDistinctObservationsAcrossRepeatedConsumers() {
        val gate = RouteFacingContinuityGate()
        val first = observation(1_000L)
        val nominal = facing(0.0, nominal = true)
        assertEquals(RouteFacingDirection.UNKNOWN, gate.gateFacingSource(nominal, first).direction)
        repeat(3) { assertEquals(RouteFacingDirection.UNKNOWN, gate.gateFacingSource(nominal, first).direction) }
        assertFalse(gate.hasConfirmedFacingDirection(nominal))
        assertEquals(12, gate.gateFacingSource(nominal, observation(1_100L)).clockHour)
        assertTrue(gate.hasConfirmedFacingDirection(nominal))
    }

    @Test fun preciseFirstSectorIsImmediateButAChangedSectorNeedsDistinctFreshSamples() {
        val gate = RouteFacingContinuityGate()
        assertEquals(12, gate.stabilizeClockSector(facing(0.0), observation(1_000L)).clockHour)
        assertNull(gate.stabilizeClockSector(facing(30.0), observation(1_100L)).clockHour)
        assertNull(gate.stabilizeClockSector(facing(30.0), observation(1_100L)).clockHour)
        assertNull(gate.stabilizeClockSector(facing(30.0), observation(1_050L)).clockHour)
        assertEquals(1, gate.stabilizeClockSector(facing(30.0), observation(1_200L)).clockHour)
    }

    @Test fun olderNominalSampleCannotProvideTheSecondConfirmation() {
        val gate = RouteFacingContinuityGate()
        val nominal = facing(0.0, nominal = true)
        gate.gateFacingSource(nominal, observation(1_000L))
        assertEquals(RouteFacingDirection.UNKNOWN, gate.gateFacingSource(nominal, observation(900L)).direction)
        assertEquals(12, gate.gateFacingSource(nominal, observation(1_100L)).clockHour)
    }

    @Test fun aSourceSwitchRequiresConfirmationAndReadOnlyChecksDoNotAdvanceIt() {
        val gate = RouteFacingContinuityGate()
        gate.gateFacingSource(facing(0.0), observation(1_000L))
        val fallback = observation(1_100L).copy(source = RouteFacingSource.GRAVITY_MAGNETIC)
        assertTrue(gate.facingSourceChanged(fallback))
        assertEquals(RouteFacingDirection.UNKNOWN, gate.gateFacingSource(facing(0.0), fallback).direction)
        repeat(3) {
            assertFalse(gate.facingSourceChanged(fallback))
            assertFalse(gate.hasConfirmedFacingDirection(facing(0.0)))
            assertTrue(gate.facingSourceSwitchPending)
        }
        assertEquals(12, gate.gateFacingSource(facing(0.0), fallback.copy(observedAtElapsedRealtimeMs = 1_200L)).clockHour)
        assertFalse(gate.facingSourceSwitchPending)
    }

    @Test fun recoveryEpisodeIdentityRequiresFreshConfirmationEvenWithoutSourceChange() {
        val gate = RouteFacingContinuityGate()
        val fallback = observation(1_000L).copy(source = RouteFacingSource.GRAVITY_MAGNETIC,
            fallbackRecoveryEpisode = RouteCompassRecoveryEpisode(1L, 900L))
        gate.gateFacingSource(facing(0.0), fallback)
        assertEquals(12, gate.gateFacingSource(facing(0.0), fallback.copy(observedAtElapsedRealtimeMs = 1_100L)).clockHour)
        val recoveredAgain = fallback.copy(observedAtElapsedRealtimeMs = 1_200L,
            fallbackRecoveryEpisode = RouteCompassRecoveryEpisode(2L, 1_150L))
        assertTrue(gate.facingSourceChanged(recoveredAgain))
        assertEquals(RouteFacingDirection.UNKNOWN, gate.gateFacingSource(facing(0.0), recoveredAgain).direction)
        assertEquals(12, gate.gateFacingSource(facing(0.0), recoveredAgain.copy(observedAtElapsedRealtimeMs = 1_300L)).clockHour)
    }

    @Test fun adjacentNominalBoundaryWithinFiveDegreesSurvivesBothGates() {
        val gate = RouteFacingContinuityGate()
        val first = gate.gateFacingSource(facing(14.0, nominal = true), observation(1_000L))
        assertNull(gate.stabilizeClockSector(first, observation(1_000L)).clockHour)
        val second = gate.gateFacingSource(facing(16.0, nominal = true), observation(1_100L))
        assertEquals(1, gate.stabilizeClockSector(second, observation(1_100L)).clockHour)
        val next = gate.gateFacingSource(facing(14.0, nominal = true), observation(1_200L))
        assertEquals(12, gate.stabilizeClockSector(next, observation(1_200L)).clockHour)
    }

    @Test fun widerNominalBoundaryChangeRestartsConfirmation() {
        val gate = RouteFacingContinuityGate()
        gate.gateFacingSource(facing(10.0, nominal = true), observation(1_000L))
        assertEquals(RouteFacingDirection.UNKNOWN,
            gate.gateFacingSource(facing(16.0, nominal = true), observation(1_100L)).direction)
        assertEquals(1, gate.gateFacingSource(facing(16.0, nominal = true), observation(1_200L)).clockHour)
    }

    @Test fun sourceAndSectorResetsKeepTheirIndependentLifetimes() {
        val gate = RouteFacingContinuityGate()
        val nominal = facing(0.0, nominal = true)
        gate.gateFacingSource(nominal, observation(1_000L))
        gate.gateFacingSource(nominal, observation(1_100L))
        gate.stabilizeClockSector(facing(0.0), observation(1_100L))
        gate.resetClockSector()
        assertTrue(gate.hasConfirmedFacingDirection(nominal))
        assertEquals(1, gate.stabilizeClockSector(facing(30.0), observation(1_200L)).clockHour)
        gate.resetFacingSourceGate()
        assertFalse(gate.hasConfirmedFacingDirection(nominal))
        assertFalse(gate.facingSourceSwitchPending)
        assertEquals(RouteFacingDirection.UNKNOWN, gate.gateFacingSource(nominal, observation(1_300L)).direction)
        assertNull(gate.stabilizeClockSector(facing(0.0), observation(1_300L)).clockHour)
    }

    private fun observation(at: Long) = RouteFacingObservation(0.0, 2.0, at)
    private fun facing(degrees: Double, nominal: Boolean = false) = RouteFacingGuidanceResult(
        direction = RouteFacingDirection.FRONT,
        signedDifferenceDegrees = degrees,
        clockPrecisionAvailable = !nominal,
        nominalClockReferenceAllowanceDegrees = 20.0.takeIf { nominal },
    )
}
