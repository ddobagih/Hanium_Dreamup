package kr.co.hanium.dreamup.walksafe.ui

import kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidancePresentation
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchOrigin
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.approximateDestinationDistanceKo
import kr.co.hanium.dreamup.walksafe.navigation.destinationDistanceKindKo
import kr.co.hanium.dreamup.walksafe.navigation.destinationOriginAgeNoticeKo
import kr.co.hanium.dreamup.walksafe.navigation.destinationOriginUncertaintyNoticeKo
import kr.co.hanium.dreamup.walksafe.navigation.displayAddressOrNull

/** Formats current display inputs; targeted suppliers retain their original conditional read points. */
internal object NativeGuidanceTextPresentation {
    fun destinationConfirmation(
        destination: DestinationSearchResult?,
        origin: () -> DestinationSearchOrigin?,
        elapsedRealtime: () -> Long,
    ): String {
        val selected = destination ?: return "목적지를 선택해 주세요."
        val address = selected.displayAddressOrNull()
        val distance = selected.distanceM?.let {
            "검색 시점 기준 ${approximateDestinationDistanceKo(it, origin()?.accuracyM)}"
        }
        val distanceKind = selected.distanceM?.let { destinationDistanceKindKo(selected.distanceSource) }
        val originNotice = destinationOriginUncertaintyNoticeKo(origin())
        val originAgeNotice = destinationOriginAgeNoticeKo(origin(), elapsedRealtime())
        return listOfNotNull(selected.name, address, distance, distanceKind, originNotice, originAgeNotice)
            .joinToString(", ")
    }

    fun guidanceStatus(
        presentation: DestinationGuidancePresentation,
        includeMeasurements: Boolean,
        currentSafetyStopSummary: () -> String?,
        developmentBypassEnabled: () -> Boolean,
        buildMarker: String,
        runtimeActive: () -> Boolean,
        gpsFailureMessage: () -> String,
        destinationPreparationMessage: () -> String,
    ): String {
        currentSafetyStopSummary()?.let { return "보행 안전 중지\n$it" }
        val statusNotice = when {
            developmentBypassEnabled() ->
                "시험용 우회 켜짐 [$buildMarker]: 환경·장착·성능 기준으로 안내 시작을 차단하지 않습니다."
            !includeMeasurements -> null
            runtimeActive() -> gpsFailureMessage()
            else -> destinationPreparationMessage()
        }
        return listOfNotNull(
            presentation.titleKo,
            presentation.routeMessageKo,
            presentation.cameraMessageKo,
            statusNotice,
        ).filter { it.isNotBlank() }.joinToString("\n\n")
    }
}
