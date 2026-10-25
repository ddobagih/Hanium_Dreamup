package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.inference.ConflictingStateGrouping
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

    /**
     * Two labels for the same observed object must not issue opposite state claims. Preserve the
     * strongest member's measured risk, track identity and source lease; change only its message.
     * Unrelated objects, including overlapping classes outside a state family, remain separate.
     */
    fun resolveConflictingStateFeedback(
        outputs: List<TrackedObjectDepth>,
        nowElapsedRealtimeMs: Long? = null,
    ): List<TrackedObjectDepth> {
        if (outputs.size < 2) return outputs
        val conflicting = BooleanArray(outputs.size)
        for (first in outputs.indices) {
            for (second in first + 1 until outputs.size) {
                if (sameCapturedStateConflict(outputs[first], outputs[second])) {
                    conflicting[first] = true
                    conflicting[second] = true
                }
            }
        }
        val remaining = BooleanArray(outputs.size) { true }
        val resolved = ArrayList<TrackedObjectDepth>(outputs.size)
        for (index in outputs.indices) {
            if (!remaining[index]) continue
            val partner = (index + 1 until outputs.size).firstOrNull { candidate ->
                remaining[candidate] && sameCapturedStateConflict(outputs[index], outputs[candidate])
            }
            remaining[index] = false
            if (partner == null) {
                // An ambiguous third detection may have lost its partner to a direct pair. Keep
                // its own risk/track instead of silently folding a chain into one object.
                resolved += if (conflicting[index]) neutralStateOutput(outputs[index]) else outputs[index]
                continue
            }
            remaining[partner] = false
            val pair = listOf(outputs[index], outputs[partner])
            val live = if (nowElapsedRealtimeMs == null) pair else pair.filter { output ->
                output.sourceValidUntilElapsedRealtimeMs?.let { nowElapsedRealtimeMs <= it } == true
            }
            val strongest = (live.ifEmpty { pair }).maxWithOrNull(feedbackPriorityComparator)!!
            resolved += neutralStateOutput(strongest)
        }
        return resolved
    }

    private fun neutralStateOutput(output: TrackedObjectDepth): TrackedObjectDepth {
        val level = output.userFacing.messageLevel
        val message = when (level) {
            MessageLevel.STOP, MessageLevel.WARNING, MessageLevel.CAUTION -> {
                val family = ConflictingStateGrouping.conflictingStateFamily(output.className)
                val subject = if (family == "e_scooter_state") "킥보드의 이동 상태" else "점자블록의 손상 상태"
                val action = when (level) {
                    MessageLevel.STOP -> "멈추세요. 주변을 확인하세요."
                    MessageLevel.WARNING -> "멈출 준비를 하고 주변을 확인하세요."
                    else -> "속도를 늦추고 주변을 확인하세요."
                }
                "${subject}가 불확실합니다. $action"
            }
            else -> null
        }
        return output.copy(userFacing = output.userFacing.copy(message = message))
    }

    private fun sameCapturedStateConflict(first: TrackedObjectDepth, second: TrackedObjectDepth): Boolean =
        first.frameId == second.frameId &&
            ConflictingStateGrouping.isConflictingStatePair(
                first.className, first.bboxNorm, second.className, second.bboxNorm,
            )

    fun selectReportOutput(outputs: List<TrackedObjectDepth>): TrackedObjectDepth? {
        return reportableDamageOutputs(outputs)
            .filterNot { hasConflictingStatePartner(it, outputs) }
            .maxWithOrNull(
                compareBy<TrackedObjectDepth> { it.confidence.finalScore }
                    .thenBy { it.detectionConfidence },
            )
    }

    fun selectAutomaticReportOutput(outputs: List<TrackedObjectDepth>): TrackedObjectDepth? {
        return reportableDamageOutputs(outputs)
            .filter { AndroidReportCandidatePolicy.isStableAutomaticDetection(it) }
            // S10-17: a same-object normal/damaged pair is uncertain in both report paths.
            .filterNot { hasConflictingStatePartner(it, outputs) }
            .maxWithOrNull(
                compareBy<TrackedObjectDepth> { it.confidence.finalScore }
                    .thenBy { it.detectionConfidence },
            )
    }

    /**
     * S12-17: a reportable damage class is observed, but no output has report-grade evidence
     * (metric source, full spatial coverage, confidence >= 0.55). Refusal wording only; the
     * report selectors above are unchanged.
     */
    fun hasReportableDamageWithoutReportDepth(outputs: List<TrackedObjectDepth>): Boolean =
        outputs.any { AndroidReportCandidatePolicy.isReportableDamageClass(it.className) } &&
            reportableDamageOutputs(outputs).isEmpty()

    fun hasConflictingReportDamage(outputs: List<TrackedObjectDepth>): Boolean =
        outputs.any { AndroidReportCandidatePolicy.isReportableDamageClass(it.className) &&
            hasConflictingStatePartner(it, outputs) }

    fun hasConflictingTactileState(outputs: List<TrackedObjectDepth>): Boolean =
        outputs.any { output ->
            ConflictingStateGrouping.conflictingStateFamily(output.className) in TACTILE_FAMILIES &&
                hasConflictingStatePartner(output, outputs)
        }

    private fun hasConflictingStatePartner(output: TrackedObjectDepth, outputs: List<TrackedObjectDepth>): Boolean =
        outputs.any { other ->
            other !== output && other.frameId == output.frameId &&
                ConflictingStateGrouping.isConflictingStatePair(
                    output.className, output.bboxNorm, other.className, other.bboxNorm,
                )
        }

    private fun reportableDamageOutputs(outputs: List<TrackedObjectDepth>): List<TrackedObjectDepth> {
        return outputs.filter { output ->
            AndroidReportCandidatePolicy.isReportableDamageClass(output.className) &&
                output.source.metric &&
                // S11-6-F1: escalation-only proximity evidence is never a report source.
                !output.spatialCoverageLimited &&
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
    }.thenByDescending {
        // Approach urgency orders the original output, retaining its UNKNOWN collision evidence.
        it.approachTimeMs.validNonnegativeOrInfinity()
    }.thenByDescending {
        if (it.source.metric) it.riskDistanceM.validNonnegativeOrInfinity() else Double.POSITIVE_INFINITY
    }.thenBy { it.confidence.finalScore }

    private fun Number?.validNonnegativeOrInfinity(): Double =
        this?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 } ?: Double.POSITIVE_INFINITY

    private const val REPORT_MIN_DEPTH_CONFIDENCE = 0.55f
    private val TACTILE_FAMILIES = setOf("tactile_block", "linear_tactile_paving", "dot_tactile_paving")
}
