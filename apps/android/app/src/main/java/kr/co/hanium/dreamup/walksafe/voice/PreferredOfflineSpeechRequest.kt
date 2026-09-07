package kr.co.hanium.dreamup.walksafe.voice

internal enum class OfflineSpeechEngine { PLATFORM, VOSK }

internal enum class OfflineSpeechSelection {
    PLATFORM, VOSK, BUSY, UNAVAILABLE, PERMISSION_DENIED, STALE,
}

/** Request fencing only. Vosk's AudioRecord source already owns the shared capture lease. */
internal class PreferredOfflineSpeechRequest(
    private val microphoneLease: VoiceMicrophoneLease = VoiceMicrophoneLease.process,
) {
    private val fence = VoskSpeechRequestFence()
    private var engine: OfflineSpeechEngine? = null
    private var platformOwner: Any? = null
    private var closed = false

    fun begin(): Long? = if (closed) null else fence.begin()

    fun accepts(id: Long, expectedEngine: OfflineSpeechEngine? = null): Boolean =
        !closed && fence.accepts(id) && (expectedEngine == null || engine == expectedEngine)

    fun select(
        id: Long,
        platformReady: Boolean,
        fallbackReady: Boolean,
        microphoneGranted: Boolean,
    ): OfflineSpeechSelection {
        if (!accepts(id) || engine != null) return OfflineSpeechSelection.STALE
        if (!microphoneGranted) return OfflineSpeechSelection.PERMISSION_DENIED
        if (platformReady) {
            val candidate = Any()
            if (!microphoneLease.acquire(candidate)) return OfflineSpeechSelection.BUSY
            platformOwner = candidate
            engine = OfflineSpeechEngine.PLATFORM
            return OfflineSpeechSelection.PLATFORM
        }
        if (fallbackReady) {
            engine = OfflineSpeechEngine.VOSK
            return OfflineSpeechSelection.VOSK
        }
        return OfflineSpeechSelection.UNAVAILABLE
    }

    fun finish(id: Long, cleanup: () -> Unit): Boolean {
        if (!fence.finish(id)) return false
        releaseAfter(cleanup)
        return true
    }

    fun cancel(cleanup: () -> Unit) {
        fence.cancel()
        releaseAfter(cleanup)
    }

    fun close(cleanup: () -> Unit) {
        closed = true
        cancel(cleanup)
    }

    private fun releaseAfter(cleanup: () -> Unit) {
        val owner = platformOwner
        platformOwner = null
        engine = null
        try {
            cleanup()
        } finally {
            owner?.let(microphoneLease::release)
        }
    }
}
