package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.StaleAuthenticationResponseTiming
import kr.co.hanium.dreamup.walksafe.network.exerciseStaleAuthenticationHttpCall
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendWalkingRouteAuthenticationStaleTest {
    @Test
    fun routeCurrentStaleResponseClearsOnce() = exercise(false, StaleAuthenticationResponseTiming.CURRENT)

    @Test
    fun routeRetainsCreationGenerationAcrossDelayedExecution() =
        exercise(false, StaleAuthenticationResponseTiming.AFTER_CREATION)

    @Test
    fun routeLateResponseCannotClearReplacementSession() =
        exercise(false, StaleAuthenticationResponseTiming.IN_FLIGHT_REPLACEMENT)

    @Test
    fun searchCurrentStaleResponseClearsOnce() = exercise(true, StaleAuthenticationResponseTiming.CURRENT)

    @Test
    fun searchRetainsCreationGenerationAcrossDelayedExecution() =
        exercise(true, StaleAuthenticationResponseTiming.AFTER_CREATION)

    @Test
    fun searchLateResponseCannotClearReplacementSession() =
        exercise(true, StaleAuthenticationResponseTiming.IN_FLIGHT_REPLACEMENT)

    @Test
    fun cancelledRouteResponseCannotClearSession() =
        exercise(false, StaleAuthenticationResponseTiming.CANCELLED)

    @Test
    fun cancelledSearchResponseCannotClearSession() =
        exercise(true, StaleAuthenticationResponseTiming.CANCELLED)

    @Test
    fun routeRootCodeKeepsLegacyClassificationButDoesNotClear() {
        val error = exerciseStaleAuthenticationHttpCall(
            timing = StaleAuthenticationResponseTiming.CURRENT,
            errorType = GatewayProxyHttpException::class.java,
            body = """{"code":"account_authentication_stale"}""",
            clearExpected = false,
        ) { routeCall(it) } as GatewayProxyHttpException
        assertEquals("account_authentication_stale", error.backendCode)
        assertEquals(401, error.statusCode)
    }

    @Test
    fun searchTrimmedCodeKeepsLegacyClassificationButDoesNotClear() {
        val error = exerciseStaleAuthenticationHttpCall(
            timing = StaleAuthenticationResponseTiming.CURRENT,
            errorType = GatewayProxyHttpException::class.java,
            body = """{"detail":{"code":" account_authentication_stale "}}""",
            clearExpected = false,
        ) { searchCall(it) } as GatewayProxyHttpException
        assertEquals("account_authentication_stale", error.backendCode)
    }

    @Test
    fun unrelatedGetOnSameTransportIsExcluded() {
        exerciseStaleAuthenticationHttpCall(
            timing = StaleAuthenticationResponseTiming.CURRENT,
            errorType = GatewayProxyHttpException::class.java,
            clearExpected = false,
        ) { session ->
            HttpUrlConnectionWalkingRouteTransport().getCall(
                session.gatewayBaseUrl + "/api/reports/v2/example/status",
                session,
            )
        }
    }

    @Test
    fun otherAuthenticationAndBusyResponsesPreserveOriginalErrors() {
        listOf(401, 403, 503).forEach { status ->
            val error = exerciseStaleAuthenticationHttpCall(
                timing = StaleAuthenticationResponseTiming.CURRENT,
                errorType = GatewayProxyHttpException::class.java,
                statusCode = status,
                body = """{"detail":{"code":"field_session_storage_busy"}}""",
                clearExpected = false,
            ) { routeCall(it) } as GatewayProxyHttpException
            assertEquals(status, error.statusCode)
            assertEquals("field_session_storage_busy", error.backendCode)
        }
    }

    private fun exercise(search: Boolean, timing: StaleAuthenticationResponseTiming) {
        exerciseStaleAuthenticationHttpCall(timing, GatewayProxyHttpException::class.java) {
            if (search) searchCall(it) else routeCall(it)
        }
    }

    private fun routeCall(session: GatewayFieldSession): CancellableNetworkCall<*> =
        BackendWalkingRouteClient().fetchRouteCall(
            session,
            WalkingRouteRequest(
                origin = RoutePoint(37.0, 127.0),
                destination = RoutePoint(37.001, 127.0),
            ),
        )

    private fun searchCall(session: GatewayFieldSession): CancellableNetworkCall<*> =
        BackendWalkingRouteClient().searchDestinationsCall(
            session = session,
            query = "station",
            limit = 5,
            origin = RoutePoint(37.0, 127.0),
        )
}
