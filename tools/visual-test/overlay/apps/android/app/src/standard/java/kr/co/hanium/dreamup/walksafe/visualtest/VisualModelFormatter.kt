package kr.co.hanium.dreamup.walksafe.visualtest

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth

@Suppress("UNUSED_PARAMETER")
object VisualModelFormatter {
    fun describe(detections: List<DetectionCandidate>, outputs: List<TrackedObjectDepth>, frameId: Long?, ageMs: Long?, partial: Boolean): String = ""
    fun label(detection: DetectionCandidate, outputs: List<TrackedObjectDepth>, frameId: Long?): String = ""
}
