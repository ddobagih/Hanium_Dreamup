package kr.co.hanium.dreamup.walksafe.ui

import org.junit.Assert.*
import org.junit.Test
import kr.co.hanium.dreamup.walksafe.navigation.*

class NativeRouteGuidancePresentationTest {
    @Test fun providerTokensAreInterpretedOnlyForTheOriginalFailedStatuses() {
        val reason = NavigationBackendErrorKind.AUTHENTICATION
        assertEquals(reason.userMessage, NativeRouteGuidancePresentation.backendFailureReason(listOf("navigation=route_failed", "kind=${reason.statusToken}")))
        assertNull(NativeRouteGuidancePresentation.backendFailureReason(listOf("navigation=active", "kind=${reason.statusToken}")))
    }

    @Test fun permissionFailureDoesNotQueryLocationServiceOrLaterState() {
        val reason = render(permission = { false }, service = { error("early service") })
        assertEquals("경로 안내에 필요한 정확한 위치 권한이 없습니다.", reason)
    }

    @Test fun newRequestSupersedesProviderFailureAfterCurrentLocationLimitsWereChecked() {
        val trace = mutableListOf<String>()
        val reason = render(requestPending = true, backend = "이전 실패", permission = { trace += "permission"; true },
            service = { trace += "service"; true }, paused = { trace += "paused"; false }, active = { error("early route state") })
        assertNull(reason)
        assertEquals(listOf("permission", "service", "paused"), trace)
        assertEquals("경로 안내에 필요한 정확한 현재 위치를 기다리고 있습니다.", render(requestPending = true, trusted = false))
    }

    @Test fun inactiveRouteDoesNotReadTheDirectionPauseSupplier() {
        val reason = render(decision = RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION, active = { false }, direction = { error("direction") })
        assertEquals("도착했습니다. 안내를 종료합니다.", reason)
    }

    @Test fun startupBlockIsLateAndOnlyReadWhenRuntimeIsInactiveWithoutEarlierReasons() {
        assertNull(render(runtime = true, startup = { error("active runtime") }))
        assertEquals("시작 확인 필요", render(runtime = false, startup = { "시작 확인 필요" }))
        assertEquals("서버 실패", render(runtime = false, backend = "서버 실패", startup = { error("earlier failure") }))
    }

    @Test fun manualRecheckSurvivesPausedAndMissingGpsAndDoesNotPromiseAutomaticResume() {
        val reason = render(trusted = false, decision = RouteNavigatorUserDecision.LOCATION_RECHECK,
            paused = { error("manual action must not be hidden by GPS wait") })!!
        assertTrue(reason, reason.contains("위치 다시 확인이라고"))
        assertFalse(reason.contains("이어갑니다"))
    }

    @Test fun rerouteActionsAndAutomaticArrivalRemainExplainedWhenGpsIsPaused() {
        val reroute = render(trusted = false, decision = RouteNavigatorUserDecision.REROUTE,
            paused = { true })!!
        assertTrue(reroute, reroute.contains("현재 위치를 확인하지 못해"))
        assertTrue(reroute, reroute.contains("새 경로, 위치 다시 확인, 길안내 종료"))
        val arrival = render(trusted = false, decision = RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION,
            paused = { error("automatic arrival must not be hidden") })!!
        assertEquals("도착했습니다. 안내를 종료합니다.", arrival)
    }

    @Test fun currentRouteRequestSupersedesThePreviousManualQuestion() {
        assertNull(render(requestPending = true, decision = RouteNavigatorUserDecision.REROUTE,
            backend = "이전 요청 실패"))
    }

    @Test fun failedProviderAndItsManualRecoveryActionsAreBothExplained() {
        val reason = render(decision = RouteNavigatorUserDecision.REROUTE,
            active = { true }, direction = { "tmap_unavailable" })!!
        assertTrue(reason, reason.contains("새 경로를 찾지 못해 방향 안내가 멈춰 있습니다"))
        assertTrue(reason, reason.contains("새 경로, 위치 다시 확인, 길안내 종료"))
    }

    private fun render(runtime: Boolean = true, trusted: Boolean = true, requestPending: Boolean = false,
        decision: RouteNavigatorUserDecision? = null, backend: String? = null,
        permission: () -> Boolean = { true }, service: () -> Boolean = { true }, paused: () -> Boolean = { false },
        active: () -> Boolean = { false }, direction: () -> String? = { null }, startup: () -> String? = { null }): String? =
        NativeRouteGuidancePresentation.locationReason(runtime, trusted, requestPending, decision, backend, false,
            permission, service, paused, active, direction, startup)
}
