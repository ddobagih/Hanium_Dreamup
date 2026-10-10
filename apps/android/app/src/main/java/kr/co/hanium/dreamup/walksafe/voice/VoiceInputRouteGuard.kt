package kr.co.hanium.dreamup.walksafe.voice

/** One capture belongs to one input device. A new capture gets a fresh guard. */
internal class VoiceInputRouteGuard {
    private var deviceId: Int? = null
    private var changed = false

    /** Initial unknown routing is allowed; losing an established route invalidates the capture. */
    @Synchronized
    fun observe(currentDeviceId: Int?): Boolean {
        if (changed) return true
        val known = deviceId
        if (known == null) deviceId = currentDeviceId
        else if (known != currentDeviceId) changed = true
        return changed
    }
}
