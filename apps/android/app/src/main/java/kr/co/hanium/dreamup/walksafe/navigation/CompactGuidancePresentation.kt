package kr.co.hanium.dreamup.walksafe.navigation

/** Visual summary only. The original message remains available to speech and TalkBack. */
data class CompactGuidancePresentation(val symbol: String, val headline: String, val secondary: String = "") {
    val text: String get() = listOf(headline, secondary).filter(String::isNotBlank).joinToString("\n\n")
}

object CompactGuidancePresentationPolicy {
    fun present(message: String, risk: Boolean = false, paused: Boolean = false): CompactGuidancePresentation {
        if (risk) return CompactGuidancePresentation("!", "장애물 주의")
        if (paused) return CompactGuidancePresentation("Ⅱ", "안내 일시정지")
        val lines = message.lines().map(String::trim).filter(String::isNotBlank)
        val first = lines.firstOrNull().orEmpty()
        val remaining = lines.firstOrNull { it.startsWith("남은 거리 ") }.orEmpty()
        if (first == "전방 장애물") return CompactGuidancePresentation("!", "장애물 주의")
        if (first == "안내 일시정지") return CompactGuidancePresentation("Ⅱ", first)
        if (first == "목적지 도착") return CompactGuidancePresentation("✓", first)
        if (first == "경로 이탈") return CompactGuidancePresentation("!", first)
        if (first == "경로 재탐색 중") return CompactGuidancePresentation("↻", first)
        val checking = first.startsWith("위치·장착 확인 중") || first.startsWith("점검이 종료됐습니다")
        if (checking) {
            val issues = buildList {
                if (message.contains("위치 요청 실패")) add("위치 설정 확인")
                else if (message.contains("위치 오차 범위")) add("위치 정확도 확인")
                else if (message.contains("정확한 위치를 아직")) add("위치 확인 중")
                if (message.contains("렌즈가 가려져")) add("렌즈 가림 확인")
                else if (message.contains("영상이 어둡")) add("주변 밝기 확인")
                else if (message.contains("흔들립니다")) add("휴대전화 고정")
                else if (message.contains("앞을 향하도록")) add("카메라 방향 확인")
                else if (message.contains("장착과 카메라 상태")) add("장착 확인 중")
            }
            val failed = first.startsWith("점검이 종료됐습니다")
            return CompactGuidancePresentation(if (failed) "!" else "◎",
                if (failed) "점검 필요" else "위치·장착 확인 중", issues.joinToString("\n"))
        }
        if (first == "기기 기능 확인 중") return CompactGuidancePresentation("◎", first)
        // Only shorten known route phrases; preserve unfamiliar instructions verbatim.
        val instruction = if (first == "안내 중") lines.getOrNull(1).orEmpty() else first
        val route = Regex("^(.*?)\\s*앞에서 (오른쪽|왼쪽)으로 이동하세요\\.$").matchEntire(instruction)
        if (route != null) {
            val right = route.groupValues[2] == "오른쪽"
            return CompactGuidancePresentation(if (right) "↱" else "↰",
                "${route.groupValues[1]} 앞 ${if (right) "우회전" else "좌회전"}", remaining)
        }
        if (message.contains("위치 권한이 없습니다")) return CompactGuidancePresentation("!", "위치 권한 필요")
        if (message.contains("Android 위치 서비스를 켜야")) return CompactGuidancePresentation("!", "위치 설정 확인")
        if (message.contains("현재 위치를 기다리고") || first == "위치 확인 중")
            return CompactGuidancePresentation("◎", "위치 확인 중")
        if (first == "현재 안내 기능이 제한되어 있습니다")
            return CompactGuidancePresentation("!", "안내 기능 제한")
        val symbol = when {
            instruction.contains("오른쪽") || instruction.contains("우회전") -> "↱"
            instruction.contains("왼쪽") || instruction.contains("좌회전") -> "↰"
            instruction.contains("직진") -> "↑"
            else -> "•"
        }
        return CompactGuidancePresentation(symbol, instruction.ifBlank { "안내 준비 중" }, remaining)
    }
}
