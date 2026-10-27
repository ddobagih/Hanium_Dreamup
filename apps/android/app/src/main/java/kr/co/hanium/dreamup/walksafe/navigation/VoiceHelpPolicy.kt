package kr.co.hanium.dreamup.walksafe.navigation

internal object VoiceHelpPolicy {
    const val MENU = "1번 음성 명령 사용법, 2번 길안내 사용법, 3번 문제 해결, 4번 도움말 목록 다시 듣기. 번호를 말씀해 주세요. 도움말 닫기라고 말하면 종료합니다."
    const val NEXT = "다른 설명은 번호를 말씀해 주세요. 다시 듣기는 현재 설명을 반복합니다. 도움말 닫기라고 말하면 종료합니다."
    val titles = listOf("음성 명령 사용법", "길안내 사용법", "문제 해결", "목록 다시 듣기")
    val descriptions = listOf(
        "호출어 기능을 켠 상태에서 길라잡이라고 부르거나 음성 명령 버튼을 누르세요. 안내와 듣기 시작 신호가 끝난 뒤 말씀해 주세요. 목적지는 서울역으로 안내해줘처럼 말해 주세요. 후보 안내 뒤에는 1번 또는 첫 번째라고 선택할 수 있습니다. 2번 자세히라고 말하면 해당 후보의 주소와 거리를 들을 수 있습니다. 더 듣기, 이전 후보, 목적지 취소, 안내 시작, 설정, 도움말을 사용할 수 있습니다. 이 도움말을 닫고 명령을 말씀해 주세요.",
        "보행 중에는 다음 안내 알려줘, 다시 말해줘, 경로 다시 찾아줘, 새 경로 요청, 위치 다시 확인, 도착했어, 아직 도착 아니야, 길안내 종료, 신고해, 보행 일시정지, 보행 종료를 말할 수 있습니다. 일시정지 중에는 보행 재개 또는 보행 종료라고 말해 주세요. 종료 확인에는 보행 종료 확인 또는 보행 종료 취소라고 말해 주세요. 재개는 상태 점검과 확인을 거칩니다. 이 도움말을 닫고 길안내 명령을 말씀해 주세요.",
        "마이크를 사용할 수 없으면 앱 설정에서 마이크 권한을 허용하세요. 음성이 나오지 않으면 휴대전화의 미디어 음량과 연결된 이어폰을 확인하고 한국어 음성 데이터를 설치한 뒤 기기 점검을 다시 하세요. 장착 문제는 휴대전화를 몸 앞에 세로로 고정하고 후면 카메라의 가림을 없앤 뒤 장착 교정을 확인하세요. 위치를 찾지 못하면 위치 권한과 위치 서비스를 확인하고 안전한 실외에서 다시 점검하세요."
    )
    // Help is reversible. Require a recognized top candidate; only conflicting help commands veto it.
    fun parse(phrases: List<String>): Int? {
        fun one(raw: String): Int? = when (compactVoicePhrase(raw)) {
            "1", "1번", "일번", "첫번째", "음성명령", "음성명령사용법" -> 1
            "2", "2번", "이번", "두번째", "길안내", "길안내사용법" -> 2
            "3", "3번", "삼번", "세번째", "문제해결" -> 3
            "4", "4번", "사번", "네번째", "목록다시듣기", "도움말", "뒤로" -> 4
            "다시듣기", "다시말해줘" -> 5
            "도움말닫기", "도움말닫아줘", "도움말닫아주세요", "도움말종료", "도움말종료해줘",
            "도움말종료해주세요", "닫기", "닫아줘", "닫아주세요" -> 0
            else -> if (parseAndroidVoiceCommand(raw) in setOf(AndroidVoiceCommand.Help, AndroidVoiceCommand.OpenHelp)) 4 else null
        }
        val first = phrases.firstOrNull()?.let(::one) ?: return null
        return first.takeIf { phrases.mapNotNull(::one).all { it == first } }
    }
}
