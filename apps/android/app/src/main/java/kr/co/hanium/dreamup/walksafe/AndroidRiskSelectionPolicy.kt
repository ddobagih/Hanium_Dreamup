package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.report.AndroidReportCandidatePolicy

object AndroidRiskSelectionPolicy {
    fun selectBestOutput(outputs: List<TrackedObjectDepth>): TrackedObjectDepth? {
        return outputs.maxWithOrNull(feedbackPriorityComparator)
    }

    /**
     * Keeps danger first while retaining lower-priority candidates. The feedback gate can then
     * fall through when the highest-priority track is in its per-track cooldown.
     */
    fun prioritizedFeedbackOutputs(outputs: List<TrackedObjectDepth>): List<TrackedObjectDepth> {
        return outputs
            .filter { !it.userFacing.message.isNullOrBlank() }
            .sortedWith(feedbackPriorityComparator.reversed())
    }

    fun selectReportOutput(outputs: List<TrackedObjectDepth>): TrackedObjectDepth? {
        return reportableDamageOutputs(outputs)
            .maxWithOrNull(
                compareBy<TrackedObjectDepth> { it.confidence.finalScore }
                    .thenBy { it.detectionConfidence },
            )
    }

    fun selectAutomaticReportOutput(outputs: List<TrackedObjectDepth>): TrackedObjectDepth? {
        return reportableDamageOutputs(outputs)
            .filter { AndroidReportCandidatePolicy.isStableAutomaticDetection(it) }
            .maxWithOrNull(
                compareBy<TrackedObjectDepth> { it.confidence.finalScore }
                    .thenBy { it.detectionConfidence },
            )
    }

    private fun reportableDamageOutputs(outputs: List<TrackedObjectDepth>): List<TrackedObjectDepth> {
        return outputs.filter { output ->
            AndroidReportCandidatePolicy.isReportableDamageClass(output.className) &&
                output.source.metric &&
                output.confidence.finalScore >= REPORT_MIN_DEPTH_CONFIDENCE
        }
    }

    private fun MessageLevel.priorityScore(): Int = when (this) {
        MessageLevel.STOP -> 4
        MessageLevel.WARNING -> 3
        MessageLevel.CAUTION -> 2
        MessageLevel.AWARE -> 1
        MessageLevel.INFO -> 1
        MessageLevel.NONE -> 0
    }

    private val feedbackPriorityComparator = compareBy<TrackedObjectDepth> {
        it.userFacing.messageLevel.priorityScore()
    }.thenBy { it.confidence.finalScore }

    private const val REPORT_MIN_DEPTH_CONFIDENCE = 0.55f
}
