package kr.co.hanium.dreamup.walksafe.navigation

/** R17: how a guidance stop phrase names what it stops. */
internal enum class GuidanceStopPhrase {
    /** "길안내 종료/취소/중지/멈춰…", "내비 중지": always the guidance - ends it at once. */
    EXPLICIT,

    /** "안내 취소/중지/종료/멈춰…" without "길": the app also calls reading the candidates "안내". */
    WITHOUT_GUIDANCE_WORD,
}

internal enum class GuidanceStopDecision { STOP, CONFIRM }

/** R17: the one confirmation question; its answers are named in the same words the parser accepts. */
internal const val GUIDANCE_STOP_CONFIRMATION_PROMPT_KO =
    "길안내를 끝낼까요? 끝내려면 길안내 종료, 후보 선택만 닫으려면 취소라고 말해 주세요."

/** R17: the confirmation question stays open this long (the same span as the explicit report confirmation). */
internal const val GUIDANCE_STOP_CONFIRMATION_WINDOW_MS = 30_000L

/** R17: which kind of guidance stop [text] is; null when it is not a guidance stop command at all. */
internal fun guidanceStopPhraseOrNull(text: String): GuidanceStopPhrase? {
    if (parseAndroidVoiceCommand(text) != AndroidVoiceCommand.StopNavigation) return null
    return if (compactVoicePhrase(text).startsWith("안내")) {
        GuidanceStopPhrase.WITHOUT_GUIDANCE_WORD
    } else {
        GuidanceStopPhrase.EXPLICIT
    }
}

/**
 * R17 (voice-F3, S05-12/S06-21; 05.txt:157 "후보 검색만 취소했는데 기존 안내까지 종료되는 상황이 없는지"):
 * while a candidate dialog, a destination search or a walking selection is open during guidance, a stop
 * phrase without "길" is asked once ([GUIDANCE_STOP_CONFIRMATION_PROMPT_KO]). Inside that question the same
 * stop phrase said again, or "길안내 종료/취소", ends guidance. "길안내 종료/취소" never needs the
 * question, and with no dialog or search open every stop is immediate (중지 우선, 05.txt:125).
 * The question ends with its time window or with the next recognized utterance, whatever it is
 * ([consumeOpenConfirmation] is called for every final, like the other voice dialog windows).
 */
internal class GuidanceStopConfirmationPolicy(
    private val windowMs: Long = GUIDANCE_STOP_CONFIRMATION_WINDOW_MS,
) {
    private var askedAtMs: Long? = null

    init {
        require(windowMs > 0L)
    }

    /** Ends any open question; true when it was still inside its window at [nowMs]. */
    @Synchronized
    fun consumeOpenConfirmation(nowMs: Long): Boolean {
        val askedAt = askedAtMs ?: return false
        askedAtMs = null
        return nowMs >= askedAt && nowMs - askedAt <= windowMs
    }

    /** The answer window starts after the spoken question, not while TTS is still reading it. */
    @Synchronized
    fun promptDelivered(nowMs: Long) {
        if (askedAtMs != null) askedAtMs = nowMs
    }

    /**
     * [confirmationWasOpen] is what [consumeOpenConfirmation] returned for this same utterance. CONFIRM
     * opens the question (the caller asks it); STOP means guidance ends now.
     */
    @Synchronized
    fun decide(
        phrase: GuidanceStopPhrase,
        confirmationWasOpen: Boolean,
        dialogOpen: Boolean,
        guidanceRunning: Boolean,
        nowMs: Long,
    ): GuidanceStopDecision {
        if (
            phrase == GuidanceStopPhrase.EXPLICIT || confirmationWasOpen || !dialogOpen || !guidanceRunning
        ) {
            askedAtMs = null
            return GuidanceStopDecision.STOP
        }
        askedAtMs = nowMs
        return GuidanceStopDecision.CONFIRM
    }

    @Synchronized
    fun invalidate() {
        askedAtMs = null
    }
}
