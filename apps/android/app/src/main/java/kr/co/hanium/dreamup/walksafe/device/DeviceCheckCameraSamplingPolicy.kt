package kr.co.hanium.dreamup.walksafe.device

enum class DeviceCheckCameraSampleVerdict {
    CONTINUE,
    PASSED,
    FAILED,
}

/**
 * How many camera frames the device check watches before deciding.
 *
 * One passing frame out of five used to end it, which proves the camera opened once and nothing
 * about sustained recognition — a phone recognising a fifth of what it sees would be reported
 * ready and then miss four hazards in five during a walk.
 *
 * The shape is borrowed rather than invented: [RuntimeMetricPreflightPolicy] already samples ten
 * frames at eighty percent for the distance check. SM-A716S returned 30 of 30 (2026-09-08), so
 * the bar admits a working phone.
 */
object DeviceCheckCameraSamplingPolicy {
    const val REQUIRED_FRAMES = RuntimeMetricPreflightPolicy.MIN_DISTINCT_FRAMES
    const val MINIMUM_PASSING_PERCENT = RuntimeMetricPreflightPolicy.MIN_PASSING_PERCENT

    /** Frames that must pass; the ceiling division keeps a fractional frame on the strict side. */
    const val REQUIRED_PASSES =
        (REQUIRED_FRAMES * MINIMUM_PASSING_PERCENT + 99) / 100

    const val ALLOWED_FAILURES = REQUIRED_FRAMES - REQUIRED_PASSES

    /**
     * Decides after each frame. Both terminal answers land as soon as the remaining frames cannot
     * change them, so a clearly good or clearly bad camera does not hold the preview for ten.
     */
    fun evaluate(attempted: Int, passed: Int): DeviceCheckCameraSampleVerdict = when {
        passed >= REQUIRED_PASSES -> DeviceCheckCameraSampleVerdict.PASSED
        attempted - passed > ALLOWED_FAILURES -> DeviceCheckCameraSampleVerdict.FAILED
        else -> DeviceCheckCameraSampleVerdict.CONTINUE
    }
}
