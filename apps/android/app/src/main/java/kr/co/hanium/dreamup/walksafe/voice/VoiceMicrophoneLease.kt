package kr.co.hanium.dreamup.walksafe.voice

/** One process-wide capture owner, including a recorder that is still being released. */
internal class VoiceMicrophoneLease {
    private var owner: Any? = null

    @Synchronized
    fun acquire(candidate: Any): Boolean {
        if (owner != null) return false
        owner = candidate
        return true
    }

    @Synchronized
    fun release(candidate: Any) {
        if (owner === candidate) owner = null
    }

    companion object {
        val process = VoiceMicrophoneLease()
    }
}
