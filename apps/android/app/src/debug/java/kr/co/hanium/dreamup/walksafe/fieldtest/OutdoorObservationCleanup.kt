package kr.co.hanium.dreamup.walksafe.fieldtest

/** A stopped outdoor observation's exact native owners; file finalization is independent. */
internal class OutdoorObservationCleanup(
    removeLocation: () -> Boolean,
    stopSteps: () -> Boolean,
    closeMotion: () -> Boolean,
    closeQuality: () -> Boolean,
    stopOrientation: () -> Boolean,
) {
    private val pending = linkedMapOf(
        "location" to removeLocation,
        "steps" to stopSteps,
        "motion" to closeMotion,
        "quality" to closeQuality,
        "orientation" to stopOrientation,
    )
    val isPending: Boolean get() = pending.isNotEmpty()
    val pendingSources: Set<String> get() = pending.keys.toSet()

    /** A failed source cannot skip another source or release its retry handle. */
    fun retry(): Boolean {
        for ((name, close) in pending.toMap()) {
            val released = try { close() } catch (_: RuntimeException) { false }
            if (released) pending.remove(name)
        }
        return !isPending
    }
}
