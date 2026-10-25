package kr.co.hanium.dreamup.walksafe.ui

import kr.co.hanium.dreamup.walksafe.navigation.NavigationBackendErrorKind
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision

/** Display reasons only. Navigation authority and the live collection/location checks stay outside. */
internal object NativeRouteGuidancePresentation {
    fun backendFailureReason(routeStatus: List<String>): String? {
        val failed = routeStatus.firstOrNull() in setOf(
            "navigation=route_failed", "navigation=reroute_failed", "navigation=safety_stopped",
        )
        return if (failed) NavigationBackendErrorKind.values().firstOrNull { kind ->
            routeStatus.any { it == kind.statusToken || it == "kind=${kind.statusToken}" }
        }?.userMessage ?: "경로 요청을 완료하지 못했습니다. 표시된 상태를 확인한 뒤 다시 시도하세요." else null
    }

    fun locationReason(
        runtimeActive: Boolean,
        trustedLocationReady: Boolean,
        routeRequestPending: Boolean,
        pendingDecision: RouteNavigatorUserDecision?,
        backendReason: String?,
        initialJoinPending: Boolean,
        locationPermissionReady: () -> Boolean,
        locationServiceEnabled: () -> Boolean,
        positionGuidancePaused: () -> Boolean,
        routeActive: () -> Boolean,
        directionGuidancePauseReason: () -> String?,
        startupReadinessBlock: () -> String?,
    ): String? = when {
        !locationPermissionReady() -> "경로 안내에 필요한 정확한 위치 권한이 없습니다."
        !locationServiceEnabled() -> "Android 위치 서비스를 켜야 현재 위치를 확인할 수 있습니다."
        !routeRequestPending && pendingDecision == RouteNavigatorUserDecision.REROUTE -> when {
            positionGuidancePaused() -> "현재 위치를 확인하지 못해 방향 안내가 잠시 멈춰 있습니다."
            !trustedLocationReady -> "경로 안내에 필요한 정확한 현재 위치를 기다리고 있습니다."
            routeActive() && directionGuidancePauseReason() == "tmap_unavailable" -> "새 경로를 찾지 못해 방향 안내가 멈춰 있습니다."
            else -> "경로를 벗어나 방향 안내가 멈춰 있습니다."
        } + " 새 경로, 위치 다시 확인, 길안내 종료 중 선택해 주세요."
        !routeRequestPending && pendingDecision == RouteNavigatorUserDecision.LOCATION_RECHECK -> "현재 위치를 다시 확인해야 합니다. 위치 다시 확인이라고 말씀해 주세요."
        !routeRequestPending && pendingDecision == RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION -> "도착했습니다. 안내를 종료합니다."
        positionGuidancePaused() -> "현재 위치를 확인하지 못해 방향 안내가 잠시 멈춰 있습니다."
        !trustedLocationReady -> "경로 안내에 필요한 정확한 현재 위치를 기다리고 있습니다."
        routeRequestPending -> null
        routeActive() && directionGuidancePauseReason() == "tmap_unavailable" -> "새 경로를 찾지 못해 방향 안내가 멈춰 있습니다."
        backendReason != null -> backendReason
        initialJoinPending -> "현재 위치와 경로 연결을 확인하고 있습니다."
        !runtimeActive -> startupReadinessBlock()
        else -> null
    }
}
