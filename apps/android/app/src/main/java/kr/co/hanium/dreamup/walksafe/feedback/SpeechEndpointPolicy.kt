package kr.co.hanium.dreamup.walksafe.feedback

/** Bounded utterance capture; no-speech and continuous noise both terminate. */
internal class SpeechEndpointPolicy(private val startedAtMs: Long) {
    private var lastSpeechAtMs: Long? = null
    fun captureLimitExceeded(nowMs: Long): Boolean =
        nowMs < startedAtMs || nowMs - startedAtMs >= MAX_RECORDING_DURATION_MS

    fun shouldStop(nowMs: Long, amplitude: Int): Boolean {
        if (captureLimitExceeded(nowMs)) return true
        if (amplitude >= 500) lastSpeechAtMs = nowMs
        val elapsed = nowMs - startedAtMs
        val lastSpeech = lastSpeechAtMs ?: return elapsed >= 7_000L
        return elapsed >= 1_500L && nowMs - lastSpeech >= 1_400L
    }
    companion object { const val MAX_RECORDING_DURATION_MS = 20_000 }
}
