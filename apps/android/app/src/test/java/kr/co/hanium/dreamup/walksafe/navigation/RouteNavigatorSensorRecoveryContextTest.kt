package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigatorSensorRecoveryContextTest {
    // This suite isolates recovery wording and accepted versus unaccepted distance evidence.
    // Production 1.5-second delay and immediate safety actions are covered by RouteDirectionRecheckGraceTest.

    @Test
    fun repeatedLocationKeepsTheCurrentCompassRecoveryReason() {
        val navigator = navigator()
        val location = fix(50.0, 1_000L)
        navigator.update(location, 1_000L, false, facingObservation = null)
        val originalMatch = requireNotNull(navigator.currentAcceptedRouteMatchFor(1_000L))

        val repeated = navigator.update(location, 1_100L, false, facingObservation = null,
            facingUnavailableReason = RouteCompassHeadingReason.REAR_AXIS_VERTICAL)

        assertTrue(repeated.instruction.orEmpty(), repeated.instruction.orEmpty()
            .contains("휴대전화 후면을 걷는 방향으로 들어 주세요."))
        assertSame(originalMatch, navigator.currentAcceptedRouteMatchFor(1_000L))
        assertFalse(repeated.arrivalCandidate)
        assertNoMovementCommand(repeated.instruction.orEmpty())
    }

    @Test
    fun finalSegmentKeepsKnownRemainingDistanceWhenFacingIsUnavailable() {
        val navigator = navigator()
        val update = navigator.update(fix(50.0, 1_000L), 1_000L, false, facingObservation = null,
            facingUnavailableReason = RouteCompassHeadingReason.REAR_AXIS_VERTICAL)

        assertNotNull(navigator.currentAcceptedRouteMatchFor(1_000L))
        assertTrue(update.instruction.orEmpty(), update.instruction.orEmpty().contains("목적지까지 약 250m 남았습니다."))
        assertFalse(update.arrivalCandidate)
        assertNoMovementCommand(update.instruction.orEmpty())
    }

    @Test
    fun finalSegmentDistinguishesTheRouteEndFromAnUnroutedDestination() {
        val navigator = navigator(destination = point(350.0))
        val update = navigator.update(fix(50.0, 1_000L), 1_000L, false, facingObservation = null)

        assertTrue(update.instruction.orEmpty(), update.instruction.orEmpty().contains("종점까지 약 250m 남았습니다."))
        assertFalse(update.instruction.orEmpty().contains("목적지까지 약"))
        assertNoMovementCommand(update.instruction.orEmpty())
    }

    @Test
    fun aDirectionReferenceCannotPromoteUnacceptedProgressIntoRemainingDistance() {
        val navigator = navigator(waitForJoin = true)
        val update = navigator.update(fix(50.0, 1_000L).copy(latitude = 20.0 / 111_194.92664455874), 1_000L, false,
            facingObservation = null)

        assertNull(navigator.currentAcceptedRouteMatchFor(1_000L))
        assertTrue(update.directionOnly)
        assertFalse(update.instruction.orEmpty().contains("목적지까지 약"))
        assertFalse(update.instruction.orEmpty().contains("종점까지 약"))
        assertFalse(update.arrivalCandidate)
    }

    private fun navigator(destination: RoutePoint = point(300.0), waitForJoin: Boolean = false) = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply {
        setRoute(WalkingRoute("STAIR_AVOID", WalkingRouteSummary(300, 300),
            listOf(point(0.0), point(300.0)), emptyList()), destination = destination,
            waitForInitialRouteJoin = waitForJoin)
    }

    private fun fix(eastM: Double, at: Long) = point(eastM).let {
        TrustedLocation(it.latitude, it.longitude, 2f, at)
    }

    private fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)

    private fun assertNoMovementCommand(text: String) {
        listOf("이동하세요", "꺾으세요", "직진하세요", "돌리세요", "시 방향").forEach {
            assertFalse(text, text.contains(it))
        }
    }
}
