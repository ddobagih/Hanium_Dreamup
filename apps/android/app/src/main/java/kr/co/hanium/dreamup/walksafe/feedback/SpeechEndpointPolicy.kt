package kr.co.hanium.dreamup.walksafe.feedback

/** Bounded utterance capture; no-speech and continuous noise both terminate. */
internal class SpeechEndpointPolicy(private val startedAtMs: Long) {
    private var lastSpeechAtMs: Long? = null
    fun shouldStop(nowMs: Long, amplitude: Int): Boolean {
        if (nowMs < startedAtMs) return true
        if (amplitude >= 500) lastSpeechAtMs = nowMs
        val elapsed = nowMs - startedAtMs
        if (elapsed >= 12_000L) return true
        val lastSpeech = lastSpeechAtMs ?: return elapsed >= 7_000L
        return elapsed >= 1_500L && nowMs - lastSpeech >= 1_400L
    }
}
