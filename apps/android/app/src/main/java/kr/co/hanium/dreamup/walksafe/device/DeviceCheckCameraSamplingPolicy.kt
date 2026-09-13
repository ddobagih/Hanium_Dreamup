package kr.co.hanium.dreamup.walksafe.device

enum class DeviceCheckCameraSampleVerdict {
    CONTINUE,
    PASSED,
    FAILED,
}

/**
 * Requires successful model execution on at least eight of up to ten camera samples.
 * A pass comes from [DeviceCheckDetectorExecutionPolicy]; it does not measure labelled detection
 * accuracy, hazard recall or field performance.
 */
object DeviceCheckCameraSamplingPolicy {
    const val REQUIRED_FRAMES = 10
    const val MINIMUM_PASSING_PERCENT = 80
    const val REQUIRED_PASSES =
        (REQUIRED_FRAMES * MINIMUM_PASSING_PERCENT + 99) / 100
    const val ALLOWED_FAILURES = REQUIRED_FRAMES - REQUIRED_PASSES

    /** Ends early once eight successes or three failures make the outcome final. */
    fun evaluate(attempted: Int, passed: Int): DeviceCheckCameraSampleVerdict {
        if (attempted !in 0..REQUIRED_FRAMES || passed !in 0..attempted) {
            return DeviceCheckCameraSampleVerdict.FAILED
        }
        return when {
            passed >= REQUIRED_PASSES -> DeviceCheckCameraSampleVerdict.PASSED
            attempted - passed > ALLOWED_FAILURES -> DeviceCheckCameraSampleVerdict.FAILED
            else -> DeviceCheckCameraSampleVerdict.CONTINUE
        }
    }
}
