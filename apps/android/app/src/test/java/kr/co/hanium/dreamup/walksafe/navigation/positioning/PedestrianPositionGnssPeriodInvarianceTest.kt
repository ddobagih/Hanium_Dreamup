package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * S09-10 (originals/09.txt:35 and the "실제 궤적 기준 PDR 검사" row, 09.txt:170): the same true walking
 * trajectory must give the same travelled distance when only the GNSS delivery period, phase or
 * arrival order changes, and the error against the true trajectory stays within the estimator's own
 * step-length 1σ for the walked steps (stepLengthSigmaM × √steps, the variance the filter adds).
 *
 * Interval definitions under test: a step event at t_k carries the displacement of (t_{k-1}, t_k];
 * a GNSS fix at t updates the state at t. After a directed step the constant-velocity mean is not
 * extrapolated, so a fix inside a step interval never adds velocity × dt on top of the next step.
 *
 * w25 review pos-F3 / Root R16: a step interval longer than twice the recent step interval (a pause without
 * ZUPT) is not spread over the interval; fixes during the pause stay where they were measured. Runs of the
 * same trajectory must also agree with the true end point and with each other's path length within
 * [ORDER_TOLERANCE_M], not only within the 1σ step budget.
 */
class PedestrianPositionGnssPeriodInvarianceTest {
    @Test
    fun straightWalkDistanceDoesNotDependOnGnssPeriodOrPhase() = WALK_DURATIONS_S.forEach { durationS ->
        val trajectory = Trajectory(durationS, turnAtS = null)
        assertInvariant(
            trajectory,
            listOf(
                "1Hz" to run(trajectory, gnssPeriodMs = 1_000L, phaseMs = 0L),
                "0.5Hz" to run(trajectory, gnssPeriodMs = 2_000L, phaseMs = 0L),
                "1Hz+300ms" to run(trajectory, gnssPeriodMs = 1_000L, phaseMs = 300L),
                "no GNSS" to run(trajectory, gnssPeriodMs = null, phaseMs = 0L),
            ),
        )
    }

    @Test
    fun aPauseWithoutZuptKeepsTheTrueDistanceForEveryGnssPeriodAndArrivalOrder() = PAUSES_MS.forEach { pauseMs ->
        val trajectory = Trajectory(10.0, turnAtS = null, pauseAfterSteps = PAUSE_AFTER_STEPS, pauseMs = pauseMs)
        assertInvariant(
            trajectory,
            listOf(
                "1Hz" to run(trajectory, gnssPeriodMs = 1_000L, phaseMs = 0L),
                "0.5Hz" to run(trajectory, gnssPeriodMs = 2_000L, phaseMs = 0L),
                "1Hz+300ms" to run(trajectory, gnssPeriodMs = 1_000L, phaseMs = 300L),
                "no GNSS" to run(trajectory, gnssPeriodMs = null, phaseMs = 0L),
                "1Hz delivered after the next step" to
                    run(trajectory, gnssPeriodMs = 1_000L, phaseMs = 0L, deliverFixAfterNextStep = true),
            ),
        )
    }

    @Test
    fun rightAngleTurnDoesNotDependOnGnssPeriodPhaseOrArrivalOrder() = WALK_DURATIONS_S.forEach { durationS ->
        val trajectory = Trajectory(durationS, turnAtS = durationS / 2.0)
        assertInvariant(
            trajectory,
            listOf(
                "1Hz" to run(trajectory, gnssPeriodMs = 1_000L, phaseMs = 0L),
                "0.5Hz" to run(trajectory, gnssPeriodMs = 2_000L, phaseMs = 0L),
                "1Hz+300ms" to run(trajectory, gnssPeriodMs = 1_000L, phaseMs = 300L),
                "1Hz delivered after the next step" to
                    run(trajectory, gnssPeriodMs = 1_000L, phaseMs = 0L, deliverFixAfterNextStep = true),
            ),
        )
    }

    private fun assertInvariant(trajectory: Trajectory, runs: List<Pair<String, Result>>) {
        val tolerance = STEP_SIGMA_M * sqrt(trajectory.stepCount.toDouble())
        val reference = runs.first().second
        val report = runs.joinToString("; ") { (label, r) ->
            "$label end=(${"%.3f".format(r.endEastM)},${"%.3f".format(r.endNorthM)}) path=${"%.3f".format(r.pathLengthM)}"
        } + "; true end=(${"%.3f".format(trajectory.endEastM)},${"%.3f".format(trajectory.endNorthM)}) " +
            "path=${"%.3f".format(trajectory.pathLengthM)} tol=${"%.3f".format(tolerance)}"
        println("S09-10 ${trajectory.label}: $report")
        runs.forEach { (label, result) ->
            assertEquals("$label applied steps", trajectory.stepCount, result.appliedSteps)
            val endError = hypot(result.endEastM - trajectory.endEastM, result.endNorthM - trajectory.endNorthM)
            assertTrue("$label end error ${"%.3f".format(endError)} m: $report", endError <= tolerance)
            assertTrue("$label path vs true: $report", abs(result.pathLengthM - trajectory.pathLengthM) <= tolerance)
            assertTrue("$label path vs 1Hz: $report", abs(result.pathLengthM - reference.pathLengthM) <= tolerance)
            assertTrue(
                "$label end error ${"%.3f".format(endError)} m > $ORDER_TOLERANCE_M: $report",
                endError <= ORDER_TOLERANCE_M,
            )
            assertTrue(
                "$label path vs 1Hz > $ORDER_TOLERANCE_M: $report",
                abs(result.pathLengthM - reference.pathLengthM) <= ORDER_TOLERANCE_M,
            )
        }
    }

    private fun run(
        trajectory: Trajectory,
        gnssPeriodMs: Long?,
        phaseMs: Long,
        deliverFixAfterNextStep: Boolean = false,
    ): Result {
        val estimator = PedestrianPositionEstimator()
        estimator.observeGnss(trajectory.fix(START_MS))
        val stepTimes = trajectory.stepTimes()
        // (deliveredAt, order, action): a fix delivered in the same millisecond as a step follows it.
        val events = mutableListOf<Triple<Long, Int, () -> Unit>>()
        val track = mutableListOf(0.0 to 0.0)
        var applied = 0
        stepTimes.forEach { stepAt ->
            events += Triple(stepAt, 0) {
                val update = estimator.observePdrStep(trajectory.step(stepAt))
                if (update.disposition == PedestrianMotionUpdateDisposition.APPLIED) applied += 1
                val estimate = requireNotNull(update.estimate)
                track += trajectory.east(estimate) to trajectory.north(estimate)
            }
        }
        if (gnssPeriodMs != null) {
            var fixAt = START_MS + phaseMs + gnssPeriodMs
            while (fixAt < trajectory.endMs) {
                val measuredAt = fixAt
                val deliveredAt = if (deliverFixAfterNextStep) {
                    (stepTimes.firstOrNull { it > measuredAt } ?: measuredAt) + 1L
                } else {
                    measuredAt
                }
                events += Triple(deliveredAt, 1) {
                    estimator.observeGnss(trajectory.fix(measuredAt).copy(receivedAtElapsedRealtimeMs = deliveredAt))
                }
                fixAt += gnssPeriodMs
            }
        }
        events.sortedWith(compareBy({ it.first }, { it.second })).forEach { it.third() }

        val end = requireNotNull(estimator.estimateAt(stepTimes.last()))
        var path = 0.0
        for (index in 1 until track.size) {
            path += hypot(track[index].first - track[index - 1].first, track[index].second - track[index - 1].second)
        }
        return Result(trajectory.east(end), trajectory.north(end), path, applied)
    }

    private data class Result(
        val endEastM: Double,
        val endNorthM: Double,
        val pathLengthM: Double,
        val appliedSteps: Int,
    )

    /**
     * Constant-speed walk east, optionally turning to north at [turnAtS]. With [pauseAfterSteps] the walker
     * stands still for [pauseMs] after that step (no ZUPT is reported) and the next step's displacement
     * happens in its last step period.
     */
    private class Trajectory(
        val durationS: Double,
        val turnAtS: Double?,
        val pauseAfterSteps: Int? = null,
        val pauseMs: Long = 0L,
    ) {
        val label = (if (turnAtS == null) "straight" else "90° turn") + " ${durationS}s" +
            (pauseAfterSteps?.let { " pause ${pauseMs}ms after step $it" } ?: "")
        val endMs = START_MS + (durationS * 1_000).toLong() + pauseMs
        val stepCount = (durationS * STEP_HZ).toInt()
        val stepLengthM = WALK_SPEED_MPS / STEP_HZ
        val pathLengthM = stepCount * stepLengthM
        val endEastM get() = truthAt(stepTimes().last()).first
        val endNorthM get() = truthAt(stepTimes().last()).second

        fun stepTimes(): List<Long> = (1..stepCount).map {
            START_MS + (it * 1_000.0 / STEP_HZ).toLong() + if (pauseAfterSteps != null && it > pauseAfterSteps) pauseMs else 0L
        }

        /** Walking time up to [elapsedMs]: the pause (if any) contributes no motion. */
        private fun walkingMs(elapsedMs: Long): Double {
            val sinceStart = (elapsedMs - START_MS).toDouble()
            val pauseStart = pauseAfterSteps?.let { it * 1_000.0 / STEP_HZ } ?: return sinceStart
            return when {
                sinceStart <= pauseStart -> sinceStart
                sinceStart <= pauseStart + pauseMs -> pauseStart
                else -> sinceStart - pauseMs
            }
        }

        /** True position; the step displacement is spread uniformly over its interval. */
        fun truthAt(elapsedMs: Long): Pair<Double, Double> {
            val distance = walkingMs(elapsedMs) / 1_000.0 * WALK_SPEED_MPS
            val corner = turnAtS?.let { it * WALK_SPEED_MPS }
            return if (corner == null || distance <= corner) distance to 0.0 else corner to distance - corner
        }

        fun headingAt(elapsedMs: Long): Double {
            val corner = turnAtS?.let { it * WALK_SPEED_MPS } ?: return 90.0
            return if (walkingMs(elapsedMs) / 1_000.0 * WALK_SPEED_MPS <= corner + 1e-9) 90.0 else 0.0
        }

        fun fix(at: Long): GnssPositionObservation {
            val (east, north) = truthAt(at)
            return GnssPositionObservation(
                latitude = ORIGIN_LATITUDE + Math.toDegrees(north / EARTH_RADIUS_M),
                longitude = ORIGIN_LONGITUDE + Math.toDegrees(east / (EARTH_RADIUS_M * cos(Math.toRadians(ORIGIN_LATITUDE)))),
                horizontalAccuracyM = GNSS_ACCURACY_M,
                elapsedRealtimeMs = at,
            )
        }

        fun step(at: Long) = PdrStepObservation(
            stepCount = 1,
            stepLengthM = stepLengthM,
            stepLengthSigmaM = STEP_SIGMA_M,
            headingDegreesTrueNorth = headingAt(at),
            headingAccuracyDegrees = 5.0,
            quality = PdrStepQuality.HIGH,
            elapsedRealtimeMs = at,
        )

        fun east(location: EstimatedLocation) =
            Math.toRadians(location.longitude - ORIGIN_LONGITUDE) * EARTH_RADIUS_M * cos(Math.toRadians(ORIGIN_LATITUDE))

        fun north(location: EstimatedLocation) = Math.toRadians(location.latitude - ORIGIN_LATITUDE) * EARTH_RADIUS_M
    }

    private companion object {
        const val START_MS = 1_000L
        val WALK_DURATIONS_S = listOf(10.0, 30.0)
        val PAUSES_MS = listOf(1_000L, 2_000L, 4_000L)
        const val PAUSE_AFTER_STEPS = 8

        /** Agreement with the true end point and between GNSS periods/orders (achieved: about 1 mm). */
        const val ORDER_TOLERANCE_M = 0.02
        const val STEP_HZ = 1.8
        const val WALK_SPEED_MPS = 1.2
        const val STEP_SIGMA_M = 0.08
        const val GNSS_ACCURACY_M = 3.0
        const val ORIGIN_LATITUDE = 37.0
        const val ORIGIN_LONGITUDE = 127.0
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}
