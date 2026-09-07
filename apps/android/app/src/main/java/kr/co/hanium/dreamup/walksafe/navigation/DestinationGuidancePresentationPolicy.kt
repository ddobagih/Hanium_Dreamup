package kr.co.hanium.dreamup.walksafe.navigation

/** Presentation only: these values never grant runtime or sensor output permission. */
enum class DestinationGuidanceDisplayStage {
    PREPARING, PARTIALLY_AVAILABLE, ACTIVE, BLOCKED,
}

data class GuidanceFeatureDisplayInput(
    val outputRunning: Boolean,
    val preparationRunning: Boolean,
    val unavailableReasonKo: String?,
)

data class DestinationGuidancePresentation(
    val stage: DestinationGuidanceDisplayStage,
    val titleKo: String,
    val routeMessageKo: String,
    val cameraMessageKo: String,
    val retryAvailable: Boolean,
)

object DestinationGuidancePresentationPolicy {
    fun present(
        route: GuidanceFeatureDisplayInput,
        camera: GuidanceFeatureDisplayInput,
        retryAvailable: Boolean,
    ): DestinationGuidancePresentation {
        val routeAvailable = route.outputRunning && route.unavailableReasonKo.isNullOrBlank()
        val cameraAvailable = camera.outputRunning && camera.unavailableReasonKo.isNullOrBlank()
        val preparing = route.preparationRunning || camera.preparationRunning
        val blocked = !route.unavailableReasonKo.isNullOrBlank() ||
            !camera.unavailableReasonKo.isNullOrBlank()
        val stage = when {
            routeAvailable && cameraAvailable -> DestinationGuidanceDisplayStage.ACTIVE
            routeAvailable || cameraAvailable -> DestinationGuidanceDisplayStage.PARTIALLY_AVAILABLE
            blocked && !preparing -> DestinationGuidanceDisplayStage.BLOCKED
            else -> DestinationGuidanceDisplayStage.PREPARING
        }
        return DestinationGuidancePresentation(
            stage = stage,
            titleKo = when (stage) {
                DestinationGuidanceDisplayStage.PREPARING -> "안내 준비 중"
                DestinationGuidanceDisplayStage.PARTIALLY_AVAILABLE -> "일부 안내 기능 사용 가능"
                DestinationGuidanceDisplayStage.ACTIVE -> "안내 기능 사용 가능"
                DestinationGuidanceDisplayStage.BLOCKED -> "현재 안내 기능이 제한되어 있습니다"
            },
            routeMessageKo = "위치·경로: " + when {
                !route.unavailableReasonKo.isNullOrBlank() -> route.unavailableReasonKo
                routeAvailable -> "현재 위치와 선택한 목적지의 경로가 준비되었습니다."
                route.preparationRunning -> "현재 위치와 TMAP 경로를 준비하고 있습니다."
                else -> "경로 안내 실행을 준비하고 있습니다."
            },
            cameraMessageKo = "카메라·객체인식: " + when {
                !camera.unavailableReasonKo.isNullOrBlank() -> camera.unavailableReasonKo
                cameraAvailable -> "실제 객체인식 안내를 사용할 수 있습니다."
                else -> "카메라 측정과 객체인식 실행을 준비하고 있습니다."
            },
            retryAvailable = retryAvailable,
        )
    }
}

