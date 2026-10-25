package kr.co.hanium.dreamup.walksafe.session

enum class PriorityUserEducationPlayback {
    SAFETY_LIMITS,
    PRACTICE_NECESSITY,
    APP_USAGE,
}

/** Shared screen and speech content; displaying it never records education completion. */
object PriorityUserEducationPresentation {
    const val hazardResponseActionKo = "멈추고 주변을 확인했습니다"

    const val phonePostureNoticeKo =
        "휴대전화를 몸 앞에 세로로 흔들리지 않게 고정하세요. " +
            "상단은 위로, 후면 카메라는 바깥으로 향하게 하고 카메라 앞 시야를 가리지 마세요. " +
            "손에 들거나 주머니에 넣지 마세요. " +
            "밝고 건조하며 짙은 안개가 없는 일반 도심 보도에서만 사용하세요. " +
            "공사 구간이나 심하게 붐비는 곳에서는 사용하지 마세요. " +
            "이 확인은 공식 사용범위를 이해하고 지키겠다는 뜻이며 현재 장소가 안전하다는 증명이 아닙니다. " +
            "실제 보행은 별도의 위치·카메라 측정과 장착 확인을 통과해야 합니다. 흰지팡이와 안내견을 대신하지 않습니다."

    fun speechText(playback: PriorityUserEducationPlayback): String = when (playback) {
        PriorityUserEducationPlayback.SAFETY_LIMITS ->
            "이 앱은 흰지팡이나 안내견을 대신하지 않으며 안전을 보장하지 않습니다. " +
                "모든 장애물을 인식하지 못할 수 있고 위치와 거리 안내에 오차가 있을 수 있습니다. " +
                "위험 안내를 들으면 멈추고 기존 보조수단으로 주변을 확인하세요. " +
                "지원하는 환경과 휴대폰 고정 조건을 지키고 실제 도로가 아닌 안전한 장소에서 먼저 익히세요."

        PriorityUserEducationPlayback.PRACTICE_NECESSITY ->
            "실제 도로에서 사용하기 전에 안전한 장소에서 안내와 조작을 익혀야 합니다. " +
                "위험 안내를 들었을 때 멈추는 방법과 일시정지, 재개, 안전정지의 차이를 먼저 익히세요. " +
                "이 안내를 들었다는 기록은 실제 보행 연습이나 위치 보정을 완료했다는 기록이 아닙니다."

        PriorityUserEducationPlayback.APP_USAGE ->
            phonePostureNoticeKo + " 사전 연습 안내입니다. 홈의 음성 명령 버튼을 누른 뒤 명령을 말하세요. " +
                "호출어 길라잡이는 호출어 듣기가 실행 중일 때만 사용할 수 있습니다. " +
                "서울역으로 안내해줘라고 목적지를 말하거나 홈의 목적지 검색에서 직접 입력하세요. " +
                "후보가 표시된 동안에만 번호로 선택할 수 있습니다. 목적지 취소라고 말하면 취소합니다. " +
                "목적지 선택 후 안내 시작이라고 말하세요. 안내 시작 때 실제 위치와 카메라 품질을 점검하고 " +
                "필요하면 현재 휴대전화 정면 고정 상태를 확인해야 합니다. " +
                "진동은 주의나 입력 상태를 알리는 신호로, 같은 진동도 여러 이유로 울릴 수 있습니다. " +
                "진동만으로 안전이나 방향, 행동을 판단하지 말고, 이유와 해야 할 행동은 함께 나오는 음성이나 화면 설명으로 확인하세요. " +
                "지금 멈춰야 하면 발걸음을 멈추고 주변을 확인한 뒤 보행 일시정지라고 말하세요. " +
                "보행을 아예 끝내려면 보행 종료라고 말하고 종료 확인 안내에 응답하세요. " +
                "보행 일시정지로 보행 전체를 잠시 멈추고, 보행 재개로 이어갑니다. " +
                "보행 종료는 종료 확인 안내를 거쳐 전체 보행을 끝냅니다. 길안내 종료는 경로 안내만 끝내며 보행 일시정지와 다릅니다. " +
                "앱이 자동으로 안전정지한 경우 현재 보행 안내와 출력을 중단하며 자동으로 재개하지 않습니다. " +
                "실제 보행에서 안전정지를 직접 실행하는 버튼이나 음성 명령은 없습니다. " +
                "연습 화면의 안전정지 연습은 실제 보행의 안전정지를 실행하는 명령이 아닙니다. " +
                "실제 안전정지 후에는 보행 재개로 이어가지 않습니다. 안전한 곳에서 기기 상태를 확인한 뒤 새 보행을 준비하세요. " +
                "앱 조작이 어렵거나 혼자 안전하게 이동할 수 없으면 먼저 멈추고 주변 사람에게 직접 도움을 요청하세요. " +
                "신고해는 신고 확인을 시작합니다. 신고는 실제 보행 중 위치 등 필요한 조건이 충족될 때만 가능합니다. " +
                "도움말은 사용 가능한 명령을, 다시 말해줘는 현재 안내만 읽습니다. " +
                "음성 안내 중에는 입력이 잠시 멈춥니다. 듣기가 실패하거나 중지되면 다시 시도하세요. " +
                "이 교육의 완료는 현재 장소가 안전하거나 실제 보행 연습을 마쳤다는 뜻이 아닙니다."
    }

    /** Keep each native utterance below the education watchdog without omitting any content. */
    fun speechSegments(message: String): List<String> = buildList {
        var start = 0
        while (start < message.length) {
            val limit = (start + 250).coerceAtMost(message.length)
            val sentenceEnd = if (limit < message.length) message.lastIndexOf(". ", limit - 2) else -1
            val end = if (sentenceEnd >= start) sentenceEnd + 2 else limit
            add(message.substring(start, end))
            start = end
        }
    }

    fun screenText(snapshot: PriorityUserOnboardingSnapshot): String =
        if (snapshot.safetyEducationConsentComplete) {
            speechText(PriorityUserEducationPlayback.APP_USAGE)
        } else {
            "1. 안전 제한 안내\n" + speechText(PriorityUserEducationPlayback.SAFETY_LIMITS) +
                "\n\n2. 연습 필요성\n" + speechText(PriorityUserEducationPlayback.PRACTICE_NECESSITY)
        }

    fun progressNotice(
        snapshot: PriorityUserOnboardingSnapshot,
        playback: PriorityUserEducationPlayback? = null,
        practice: PriorityUserPractice? = null,
        hazardResponseReady: Boolean = false,
        screenReaderReview: Boolean = false,
    ): String {
        if (practice != null) {
            return if (practice == PriorityUserPractice.HAZARD_ALERT && hazardResponseReady) {
                "위험 예시 안내가 끝났습니다. 멈추고 주변을 확인한 뒤 $hazardResponseActionKo 버튼을 누르세요. " +
                    "이 응답은 사용자의 확인이며 센서로 안전을 확인한 기록은 아닙니다."
            } else {
                "조작 연습 ${snapshot.completedPractices.size}/4 완료. ${practice.actionLabelKo} 안내를 재생하고 있습니다. " +
                    "안내 완료 전에는 다음 연습으로 넘어가지 않습니다."
            }
        }
        val nextAction = when {
            snapshot.nativeEducationComplete -> "안전교육과 앱 사용교육, 네 가지 조작 연습을 완료했습니다."
            snapshot.safetyEducationConsentComplete -> when {
                !snapshot.usageConditionsAcknowledged ->
                    "착용 방법과 사용 환경을 확인하고 이해했습니다 버튼을 누르세요."
                !snapshot.appUsageReviewed -> if (screenReaderReview) {
                    "화면의 사전 연습 설명을 끝까지 읽은 뒤 확인 버튼을 누르세요."
                } else "사전 연습 듣기를 눌러 안내를 끝까지 들어 주세요."
                !snapshot.safePracticePlaceConfirmed || snapshot.requiresPracticeRestart ->
                    "안내 청취를 완료했습니다. 실제 도로가 아닌 안전한 장소에서 안전한 연습 장소 확인 버튼을 누르세요."
                !snapshot.interactivePracticeComplete ->
                    "조작 연습 ${snapshot.completedPractices.size}/4 완료. ${snapshot.nextRequiredPractice?.actionLabelKo} 버튼을 누르세요."
                else -> "안내 청취와 사용 환경 확인, 조작 연습 4/4를 완료했습니다. 완료하고 홈으로 버튼을 누르세요."
            }
            !snapshot.educationReviewed -> if (screenReaderReview) {
                "화면의 안전 제한 안내를 끝까지 읽은 뒤 1번 확인 버튼을 누르세요."
            } else "1. 안전 제한 안내 듣기를 눌러 안내를 끝까지 들어 주세요."
            !snapshot.practiceNecessityReviewed ->
                if (screenReaderReview) "안전 제한 안내 확인을 완료했습니다. 화면의 연습 필요성을 읽은 뒤 2번 확인 버튼을 누르세요."
                else "안전 제한 안내 청취를 완료했습니다. 2. 연습 필요성 듣기를 눌러 주세요."
            else -> "두 안내의 청취를 완료했습니다. 안전 교육 동의하고 사전 연습으로 버튼을 누르세요."
        }
        if (playback == null) return nextAction
        val replay = when (playback) {
            PriorityUserEducationPlayback.SAFETY_LIMITS -> snapshot.educationReviewed
            PriorityUserEducationPlayback.PRACTICE_NECESSITY -> snapshot.practiceNecessityReviewed
            PriorityUserEducationPlayback.APP_USAGE -> snapshot.appUsageReviewed
        }
        val label = when (playback) {
            PriorityUserEducationPlayback.SAFETY_LIMITS -> "안전 제한 안내"
            PriorityUserEducationPlayback.PRACTICE_NECESSITY -> "연습 필요성"
            PriorityUserEducationPlayback.APP_USAGE -> "사전 연습 안내"
        }
        return if (replay) {
            "$label 다시 듣는 중입니다. 중지해도 이전 청취 완료 기록은 유지됩니다. $nextAction"
        } else {
            "$label 듣는 중입니다. 끝까지 재생되어야 청취 완료로 기록됩니다."
        }
    }
}
