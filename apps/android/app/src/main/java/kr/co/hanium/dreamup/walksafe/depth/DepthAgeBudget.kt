package kr.co.hanium.dreamup.walksafe.depth

/** S11-11: what a metric distance is used for; each use has its own allowed depth age. */
enum class DepthUse { PROXIMITY_DISPLAY, RISK_GUIDANCE, TIME_TO_COLLISION }

/**
 * S11-11 (originals/11.txt:120-124): the allowed age of the first independent depth observation,
 * per use. The age is measured from [TrackedObjectDepth.depthObservedAtMs] (the original depth
 * capture), never from a later camera frame, so a new camera frame cannot extend an old depth.
 * Whether the depth was reprojected ([TrackedObjectDepth.depthReprojected]) is kept apart from
 * its age. No new numbers: the display and guidance bounds are the existing ones.
 */
object DepthAgeBudget {
    /** Proximity display: the existing overlay bound (MainActivity MAX_OVERLAY_DETECTION_SOURCE_AGE_MS). */
    const val PROXIMITY_DISPLAY_MAX_AGE_MS = 1_200L

    /** Risk guidance: the existing metric source lease. */
    const val RISK_GUIDANCE_MAX_AGE_MS = CurrentTrackedFrameObservation.MAX_SOURCE_AGE_MS

    fun maxAgeMs(use: DepthUse): Long? = when (use) {
        DepthUse.PROXIMITY_DISPLAY -> PROXIMITY_DISPLAY_MAX_AGE_MS
        DepthUse.RISK_GUIDANCE -> RISK_GUIDANCE_MAX_AGE_MS
        // TTC is not an age budget: only the capture's own, non-reprojected Raw observation (the
        // motion window supplies the history and its own spacing rules).
        DepthUse.TIME_TO_COLLISION -> null
    }

    /** [nowMs] is in the depth capture's millisecond clock (the output timestamp clock). */
    fun admits(use: DepthUse, output: TrackedObjectDepth, nowMs: Long): Boolean {
        if (!output.source.metric || output.riskDistanceM == null) return false
        if (use == DepthUse.TIME_TO_COLLISION) {
            return output.source == DepthSource.ARCORE_RAW_DEPTH && output.depthReprojected == false
        }
        val observedAt = output.depthObservedAtMs ?: return false
        // A depth image stamped after its use time is not older than the budget; the reprojection
        // flag, not the age, marks that it does not belong to this camera frame.
        return nowMs - observedAt <= requireNotNull(maxAgeMs(use))
    }
}
