package kr.co.hanium.dreamup.walksafe.navigation

/** Only used while the selected destination's start confirmation is open. */
internal object GuidanceStartVoicePolicy {
    enum class Choice { START, CANCEL, REPEAT }

    fun parse(phrases: List<String>, confidence: Float?, platform: Boolean, extraAlternatives: Boolean): Choice? {
        fun choice(text: String): Choice? = when (text.replace(Regex("[\\s.!?。]"), "")) {
            "시작", "시작해줘", "안내시작", "길안내시작", "안내시작해줘", "길안내시작해줘" -> Choice.START
            "취소", "안내취소", "길안내취소", "취소해줘", "시작하지마" -> Choice.CANCEL
            "다시듣기", "다시말해줘" -> Choice.REPEAT
            else -> null
        }
        val top = phrases.firstOrNull()?.let(::choice) ?: return null
        if (extraAlternatives || phrases.drop(1).any { choice(it) != top }) return null
        if (confidence != null && (!confidence.isFinite() || confidence < 0.55f) &&
            !(platform && confidence == 0f)) return null
        return top
    }
}
