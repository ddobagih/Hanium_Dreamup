package kr.co.hanium.dreamup.walksafe.fieldtest

/** Native callback retirement is separate from a successfully flushed and published RAW file. */
internal class OutdoorRawGnssRegistration {
    private var unregister: (() -> Unit)? = null
    var registered = false
        private set
    var failure: RuntimeException? = null
        private set
    val cleanupPending: Boolean get() = unregister != null && !registered

    fun prepare(unregister: () -> Unit) {
        check(this.unregister == null) { "Previous RAW callback is still owned" }
        this.unregister = unregister
    }

    fun confirmRegistration() { check(unregister != null); registered = true }

    fun release(): Boolean {
        registered = false
        val owned = unregister ?: return true
        return try {
            owned()
            unregister = null
            failure = null
            true
        } catch (error: RuntimeException) {
            failure = error
            false
        }
    }
}
