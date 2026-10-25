package kr.co.hanium.dreamup.walksafe.navigation

/** Presentation only: these values never grant runtime or sensor output permission. */
enum class DestinationGuidanceDisplayStage {
    PREPARING, PARTIALLY_AVAILABLE, ACTIVE, BLOCKED, ARRIVED, ARRIVAL_CONFIRMATION_PENDING,
}

data class GuidanceFeatureDisplayInput(
    val outputRunning: Boolean,
    val preparationRunning: Boolean,
    val unavailableReasonKo: String?,
    val availableMessageKo: String? = null,
)

/** Touch presentation only; confirmation always rechecks the current session token. */
enum class DestinationGuidanceResumeAction { NONE, PREPARING, NOTICE, CONFIRM, PENDING }

data class DestinationGuidancePresentation(
    val stage: DestinationGuidanceDisplayStage,
    val titleKo: String,
    val routeMessageKo: String,
    val cameraMessageKo: String,
    val retryAvailable: Boolean,
    val resumeAction: DestinationGuidanceResumeAction = DestinationGuidanceResumeAction.NONE,
)

object DestinationGuidancePresentationPolicy {
    fun present(
        route: GuidanceFeatureDisplayInput,
        camera: GuidanceFeatureDisplayInput,
        retryAvailable: Boolean,
        arrivalConfirmed: Boolean = false,
        arrivalConfirmationPending: Boolean = false,
    ): DestinationGuidancePresentation {
        val routeAvailable = route.outputRunning && route.unavailableReasonKo.isNullOrBlank()
        val cameraAvailable = camera.outputRunning && camera.unavailableReasonKo.isNullOrBlank()
        val preparing = route.preparationRunning || camera.preparationRunning
        val blocked = !route.unavailableReasonKo.isNullOrBlank() ||
            !camera.unavailableReasonKo.isNullOrBlank()
        val stage = when {
            arrivalConfirmed -> DestinationGuidanceDisplayStage.ARRIVED
            arrivalConfirmationPending -> DestinationGuidanceDisplayStage.ARRIVAL_CONFIRMATION_PENDING
            routeAvailable && cameraAvailable -> DestinationGuidanceDisplayStage.ACTIVE
            routeAvailable || cameraAvailable -> DestinationGuidanceDisplayStage.PARTIALLY_AVAILABLE
            blocked && !preparing -> DestinationGuidanceDisplayStage.BLOCKED
            else -> DestinationGuidanceDisplayStage.PREPARING
        }
        return DestinationGuidancePresentation(
            stage = stage,
            titleKo = when (stage) {
                DestinationGuidanceDisplayStage.ARRIVED -> "도착했습니다"
                DestinationGuidanceDisplayStage.ARRIVAL_CONFIRMATION_PENDING -> "도착 안내 종료 중"
                DestinationGuidanceDisplayStage.PREPARING -> "안내 준비 중"
                DestinationGuidanceDisplayStage.PARTIALLY_AVAILABLE ->
                    if (routeAvailable) "경로 안내 중" else "카메라 안내 중"
                DestinationGuidanceDisplayStage.ACTIVE -> "경로 안내 중"
                DestinationGuidanceDisplayStage.BLOCKED -> "현재 안내 기능이 제한되어 있습니다"
            },
            routeMessageKo = "위치·경로: " + when {
                arrivalConfirmed -> "도착했습니다. 안내를 종료합니다"
                !route.unavailableReasonKo.isNullOrBlank() -> route.unavailableReasonKo
                routeAvailable -> route.availableMessageKo?.takeIf { it.isNotBlank() }
                    ?: "현재 위치와 선택한 목적지의 경로가 준비되었습니다."
                route.preparationRunning -> "현재 위치와 TMAP 경로를 준비하고 있습니다."
                else -> "경로 안내 실행을 준비하고 있습니다."
            },
            cameraMessageKo = "카메라·객체인식: " + when {
                !camera.unavailableReasonKo.isNullOrBlank() -> camera.unavailableReasonKo
                cameraAvailable -> "실제 객체인식 안내를 사용할 수 있습니다."
                else -> "카메라 측정과 객체인식 실행을 준비하고 있습니다."
            },
            retryAvailable = retryAvailable && !arrivalConfirmed && !arrivalConfirmationPending,
        )
    }
}
