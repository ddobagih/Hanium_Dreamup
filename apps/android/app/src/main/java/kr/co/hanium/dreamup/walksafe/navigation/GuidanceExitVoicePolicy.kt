package kr.co.hanium.dreamup.walksafe.navigation

/** Only interprets explicit choices inside the live walk-exit confirmation. */
internal object GuidanceExitVoicePolicy {
    enum class Choice { RETURN, END }
    fun parse(phrases: List<String>, confidence: Float?, extraAlternatives: Boolean): Choice? {
        if (confidence == null || !confidence.isFinite() || confidence !in 0.55f..1f || extraAlternatives) return null
        fun choice(text: String): Choice? = when (text.replace(Regex("[\\s.!?。]"), "")) {
            "돌아가기", "안내로돌아가기", "취소", "종료취소", "보행종료취소", "계속", "계속해줘" -> Choice.RETURN
            "안내종료", "보행종료", "보행종료확인", "종료", "종료해줘", "안내종료해줘" -> Choice.END
            else -> null
        }
        val top = phrases.firstOrNull()?.let(::choice) ?: return null
        return top.takeIf { phrases.drop(1).all { choice(it) == top } }
    }
}
