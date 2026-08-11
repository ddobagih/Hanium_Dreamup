package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.report.AndroidReportCandidatePolicy

object AndroidRiskSelectionPolicy {
    fun selectBestOutput(outputs: List<TrackedObjectDepth>): TrackedObjectDepth? {
        return outputs.maxWithOrNull(
            compareBy<TrackedObjectDepth> { it.userFacing.messageLevel.priorityScore() }
                .thenBy { it.confidence.finalScore },
        )
    }

    fun selectReportOutput(outputs: List<TrackedObjectDepth>): TrackedObjectDepth? {
        return outputs
            .filter { output ->
                output.className == AndroidReportCandidatePolicy.DAMAGED_TACTILE_BLOCK &&
                    output.source.metric &&
                    output.confidence.finalScore >= REPORT_MIN_DEPTH_CONFIDENCE
            }
            .maxWithOrNull(
                compareBy<TrackedObjectDepth> { it.confidence.finalScore }
                    .thenBy { it.detectionConfidence },
            )
    }

    private fun MessageLevel.priorityScore(): Int = when (this) {
        MessageLevel.STOP -> 4
        MessageLevel.WARNING -> 3
        MessageLevel.CAUTION -> 2
        MessageLevel.AWARE -> 1
        MessageLevel.INFO -> 1
        MessageLevel.NONE -> 0
    }

    private const val REPORT_MIN_DEPTH_CONFIDENCE = 0.55f
}
