package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult

/** An operation outcome, not a reusable currentness or readiness assertion. */
internal sealed interface FrameSourceTracking {
    data object Retired : FrameSourceTracking
    data class Evaluated(val result: VisualTrackingResult?) : FrameSourceTracking
}
