package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S09-28 (originals/09.txt:83): heading smoothing is time-interval based, so the same 90° turn gives
 * the same response and the same smoothing residual in the error budget at 2/10/50 Hz (magnetic)
 * and 1/10/50 Hz (GPS course). An observation stands for the interval since the previous one, so
 * the response is compared at the same time after the last pre-turn observation.
 * At the nominal interval the configured gains are unchanged (0.85 on the turn, then 0.35).
 */
class PedestrianHeadingRateInvarianceTest {
    @Test
    fun magneticNinetyDegreeTurnHasTheSameResponseAtTwoTenAndFiftyHertz() {
        val runs = listOf(500L, 100L, 20L).associateWith { intervalMs ->
            run(intervalMs) { at, heading -> magnetic(at, heading) }
        }
        assertSameResponse(runs, elapsedMs = listOf(500L, 1_000L, 1_500L, 2_000L, 3_000L))
        // Nominal interval keeps the configured per-observation gains.
        assertEquals(90.0 * 0.85, runs.getValue(500L).getValue(0L).first, 1e-9)
        assertEquals(90.0 - 13.5 * 0.65, runs.getValue(500L).getValue(500L).first, 1e-9)
    }

    @Test
    fun gpsCourseNinetyDegreeTurnHasTheSameResponseAtOneTenAndFiftyHertz() {
        val runs = listOf(1_000L, 100L, 20L).associateWith { intervalMs ->
            run(intervalMs) { at, heading -> gps(at, heading) }
        }
        assertSameResponse(runs, elapsedMs = listOf(1_000L, 2_000L, 3_000L))
        assertEquals(90.0 * 0.85, runs.getValue(1_000L).getValue(0L).first, 1e-9)
    }

    /** Returns heading and accuracy keyed by time after the turn's first observation (ms). */
    private fun run(
        intervalMs: Long,
        input: (Long, Double) -> PedestrianHeadingInput,
    ): Map<Long, Pair<Double, Double>> {
        val selector = PedestrianHeadingSelector()
        val result = linkedMapOf<Long, Pair<Double, Double>>()
        var at = 0L
        while (at <= TURN_AT_MS + 3_000L) {
            val heading = if (at < TURN_AT_MS) 0.0 else 90.0
            val selected = requireNotNull(selector.select(input(at, heading)))
            if (at >= TURN_AT_MS) {
                result[at - TURN_AT_MS] = selected.headingDegreesTrueNorth to requireNotNull(selected.accuracyDegrees)
            }
            at += intervalMs
        }
        return result
    }

    private fun assertSameResponse(runs: Map<Long, Map<Long, Pair<Double, Double>>>, elapsedMs: List<Long>) {
        for (elapsed in elapsedMs) {
            val values = runs.map { (intervalMs, samples) ->
                // The first 90° observation covers (TURN_AT - interval, TURN_AT].
                intervalMs to requireNotNull(samples[elapsed - intervalMs]) { "no sample at ${elapsed - intervalMs} for $intervalMs" }
            }
            val (referenceInterval, reference) = values.first()
            values.drop(1).forEach { (intervalMs, value) ->
                assertEquals(
                    "heading ${elapsed}ms after the turn, ${intervalMs}ms vs ${referenceInterval}ms",
                    reference.first, value.first, 1e-6,
                )
                assertEquals(
                    "error budget ${elapsed}ms after the turn, ${intervalMs}ms vs ${referenceInterval}ms",
                    reference.second, value.second, 1e-6,
                )
            }
            // The smoothing residual is in the budget exactly once on top of the 5° sensor accuracy.
            assertEquals(5.0 + (90.0 - reference.first), reference.second, 1e-9)
        }
    }

    private fun magnetic(at: Long, heading: Double) = PedestrianHeadingInput(
        nowElapsedRealtimeMs = at,
        speed = WalkingSpeedObservation(0.2, 0.2, at),
        gpsCourse = null,
        magneticTrueHeading = HeadingObservation(heading, 5.0, at),
        phoneForwardMounted = true,
    )

    private fun gps(at: Long, heading: Double) = PedestrianHeadingInput(
        nowElapsedRealtimeMs = at,
        speed = WalkingSpeedObservation(1.2, 0.2, at),
        gpsCourse = HeadingObservation(heading, 5.0, at),
        magneticTrueHeading = null,
        phoneForwardMounted = false,
    )

    private companion object {
        const val TURN_AT_MS = 2_000L
    }
}
