package kr.co.hanium.dreamup.walksafe.device.environment

import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.session.ApprovedOfficialEnvironmentProfile
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentAssessment
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentRuntimeAction
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentRuntimeDecision
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentRuntimeGuard
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

/** Runtime policy and its watchdog. The scheduler is the existing Activity UI queue. */
internal class OfficialEnvironmentRuntimeController(
    private val evidence: OfficialEnvironmentEvidenceStore,
    private val facts: OfficialEnvironmentRuntimeFactsReader,
    private val effects: OfficialEnvironmentRuntimeEffects,
    private val nowMs: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val bypassEnabled: () -> Boolean,
) {
    var guard: OfficialEnvironmentRuntimeGuard? = null
        private set
    @Volatile var outputsAllowed = false
        private set
    private var lastAction: OfficialEnvironmentRuntimeAction? = null
    private var lastUnavailableFactors = emptySet<OfficialEnvironmentFactor>()
    private var lastRequestedWarningEpisodes = emptyMap<OfficialEnvironmentFactor, Long>()
    private val deliveredWarningFactors = mutableSetOf<OfficialEnvironmentFactor>()
    private val lastWarningAtMs = mutableMapOf<OfficialEnvironmentFactor, Long>()
    private var lastRestorationNoticeAtMs: Long? = null
    private var silentCameraBoundaryLimited = false
    private var watchdogGeneration = 0L

    fun invalidateWatchdog() { watchdogGeneration += 1L }

    fun clearGuard() { guard = null }

    fun clearDecisionAndOutput() {
        lastAction = null
        lastUnavailableFactors = emptySet()
        clearNotices()
        outputsAllowed = false
    }

    fun reset() {
        invalidateWatchdog()
        guard = null
        lastAction = null
        lastUnavailableFactors = emptySet()
        clearNotices()
        outputsAllowed = false
    }

    fun runtimeReadiness(epoch: WalkRuntimeEpoch): Pair<WalkSessionReadinessStatus, String>? {
        if (bypassEnabled()) return WalkSessionReadinessStatus.READY to ""
        val lifecycle = facts.lifecycle()
        val owner = guard
        if (lifecycle.state != WalkSessionState.ACTIVE || owner == null || owner.epoch != epoch || !facts.isRuntimeEpochCurrent(epoch)) return null
        return when (owner.decision().action) {
            OfficialEnvironmentRuntimeAction.CONTINUE,
            OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY -> WalkSessionReadinessStatus.READY to ""
            OfficialEnvironmentRuntimeAction.SAFE_STOP -> WalkSessionReadinessStatus.UNAVAILABLE to "official_environment:runtime_retry_exhausted"
        }
    }

    fun activate(): Boolean {
        if (bypassEnabled()) { outputsAllowed = true; return true }
        val epoch = facts.currentRuntimeEpoch() ?: return false
        val owner = OfficialEnvironmentRuntimeGuard(epoch, facts.profile())
        guard = owner
        val now = nowMs()
        evidence.prepareGpsForRuntime({ facts.lifecycle().epoch }, now, facts::continuousSourcePresent, facts::continuousGpsEvidence)
        evidence.seedRuntimeCameraHistory(now, facts::latestMountingCameraAssessment)
        lastAction = null
        lastUnavailableFactors = emptySet()
        clearNotices()
        val decision = owner.onAssessment(facts.currentAssessment(epoch, now), now)
        applyDecision(decision)
        return decision.action != OfficialEnvironmentRuntimeAction.SAFE_STOP
    }

    fun applyCurrentAssessment() {
        if (bypassEnabled()) return
        val owner = guard ?: return
        if (!facts.isRuntimeEpochCurrent(owner.epoch)) return
        val now = nowMs()
        val assessment = facts.currentAssessment(owner.epoch, now)
        if (facts.cameraEnabled() && facts.cameraHandoffEpoch() != owner.epoch &&
            assessment.factorStatuses[OfficialEnvironmentFactor.CAMERA_QUALITY] != EnvironmentEvidenceStatus.PASS
        ) effects.reassessMountingWithoutExplicitRetry()
        applyDecision(owner.onAssessment(assessment, now))
    }

    fun reactToAdmittedGpsObservation(
        epoch: WalkRuntimeEpoch,
        previous: MeasuredEnvironmentEvidence?,
        current: MeasuredEnvironmentEvidence,
        reassess: () -> Unit = ::applyCurrentAssessment,
        scheduleWatchdog: (WalkRuntimeEpoch) -> Unit = ::scheduleWatchdog,
    ) {
        val shouldReassess = current.status == EnvironmentEvidenceStatus.PASS && previous?.status != EnvironmentEvidenceStatus.PASS ||
            outputsAllowed && current.status != EnvironmentEvidenceStatus.PASS
        if (shouldReassess) reassess()
        else if (evidence.gpsQualityGate.pendingFailureDeadlineElapsedRealtimeMs() != null ||
            current.status == EnvironmentEvidenceStatus.PASS && current != previous && evidence.gpsQualityGate.latestRawObservation() == current
        ) scheduleWatchdog(epoch)
    }

    fun applyDecision(decision: OfficialEnvironmentRuntimeDecision) {
        if (bypassEnabled() || !facts.isRuntimeEpochCurrent(decision.epoch)) return
        val now = nowMs()
        reconcileRecheckEpisodes(decision, now)
        val camera = OfficialEnvironmentFactor.CAMERA_QUALITY
        val boundaryRechecking = evidence.cameraQualityGate.current(now)?.let {
            it.epoch == decision.epoch && it.status == EnvironmentEvidenceStatus.FAIL &&
                it.detail == kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityReason.MOUNT_ANGLE_RECHECK_PENDING.name &&
                evidence.cameraQualityGate.pendingFailureDeadlineElapsedRealtimeMs()?.let { deadline -> now < deadline } == true
        } == true
        val silentFactors = if (boundaryRechecking) setOf(camera) else emptySet()
        val boundaryConfirmed = silentCameraBoundaryLimited && !boundaryRechecking && camera in decision.unavailableFactors
        when (decision.action) {
            OfficialEnvironmentRuntimeAction.CONTINUE -> {
                outputsAllowed = true
                val newlyUnavailable = decision.unavailableFactors - lastUnavailableFactors
                if (newlyUnavailable.isNotEmpty()) effects.invalidateReportOutput("measured_environment_feature_limited")
                if (OfficialEnvironmentFactor.GPS_QUALITY in newlyUnavailable) {
                    effects.restrictGpsNavigationAndCancelFeedback()
                } else if (decision.navigationOutputsAllowed && OfficialEnvironmentFactor.GPS_QUALITY in lastUnavailableFactors) {
                    effects.resumeGpsNavigation()
                }
                // Partial recovery retires the queued all-output warning. Rebuild its still
                // unavailable causes even though none of them became newly unavailable.
                val warningFactors = ((if (lastAction == OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY)
                    decision.unavailableFactors else newlyUnavailable) +
                    if (boundaryConfirmed) setOf(camera) else emptySet()) - silentFactors
                if (warningFactors.isNotEmpty()) {
                    effects.showStatus("안내 품질 재확인 중", "품질이 확인되지 않은 기능을 잠시 제한합니다. 새 위치와 영상이 확인되면 자동으로 다시 안내합니다.")
                    val notWarned = (warningFactors - deliveredWarningFactors)
                        .filter { noticeDue(lastWarningAtMs[it], now) }.toSet()
                    if (notWarned.isNotEmpty()) {
                        val message = effects.failureSpeech(facts.currentAssessment(facts.lifecycle().epoch, now), notWarned)
                            .ifBlank { effects.locationRecoveryActionMessage() }
                        val owner = guard
                        val stillLimited = {
                            facts.isRuntimeEpochCurrent(decision.epoch) && guard === owner &&
                                guard?.decision()?.unavailableFactors?.containsAll(notWarned) != false
                        }
                        effects.speakRecheckExplanation(message, stillLimited, {
                            if (stillLimited()) {
                                deliveredWarningFactors += notWarned
                                val completedAt = nowMs()
                                notWarned.forEach { lastWarningAtMs[it] = completedAt }
                            }
                        })
                    }
                }
                val restored = (lastUnavailableFactors - decision.unavailableFactors) -
                    if (silentCameraBoundaryLimited) setOf(camera) else emptySet()
                // The shared speech slot must keep an unresolved cause, not replace it with
                // an all-clear while another measured feature is still restricted/rechecking.
                if (restored.isNotEmpty() &&
                    decision.unavailableFactors.intersect(decision.enabledMeasuredFactors).isEmpty() &&
                    lastRequestedWarningEpisodes.isEmpty()
                ) {
                    val gpsRestored = OfficialEnvironmentFactor.GPS_QUALITY in restored
                    val cameraRestored = OfficialEnvironmentFactor.CAMERA_QUALITY in restored
                    val message = when {
                        gpsRestored && cameraRestored -> "위치와 카메라 상태가 다시 확인되었습니다."
                        gpsRestored -> "위치가 다시 확인되었습니다."
                        cameraRestored -> "카메라 상태가 다시 확인되었습니다."
                        else -> "사용 환경 확인 내용이 갱신되었습니다."
                    }
                    effects.showStatus("상태 재확인", message)
                    if (noticeDue(lastRestorationNoticeAtMs, now)) {
                        effects.speakRecheckExplanation(message, {
                            facts.isRuntimeEpochCurrent(decision.epoch) &&
                                guard?.decision()?.unavailableFactors?.intersect(restored)?.isEmpty() != false
                        }, {})
                        lastRestorationNoticeAtMs = now
                    }
                }
                scheduleWatchdog(decision.epoch)
            }
            OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY -> {
                val newlySuppressed = lastAction != decision.action
                outputsAllowed = false
                if (newlySuppressed || boundaryConfirmed) {
                    if (newlySuppressed) {
                        effects.invalidateReportOutput("official_environment_retry")
                        effects.cancelPendingFeedbackDeliveries()
                        effects.cancelVoiceAndFeedbackActuator()
                        effects.showStatus("환경 품질 재확인 중", "위치와 카메라 품질을 확인할 수 없어 안내를 잠시 멈췄습니다. 새 측정이 확인되면 자동으로 다시 안내합니다.")
                    }
                    val notWarned = (decision.unavailableFactors - deliveredWarningFactors - silentFactors)
                        .filter { noticeDue(lastWarningAtMs[it], now) }.toSet()
                    if (notWarned.isNotEmpty() || decision.unavailableFactors.isEmpty()) {
                        val owner = guard
                        val stillSuppressed = {
                            facts.isRuntimeEpochCurrent(decision.epoch) && guard === owner &&
                                owner?.decision()?.let {
                                    it.action == OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY &&
                                        it.unavailableFactors.containsAll(notWarned)
                                } == true
                        }
                        val message = effects.failureSpeech(facts.currentAssessment(decision.epoch, now),
                            notWarned.takeIf { it.isNotEmpty() })
                            .ifBlank { "환경 품질을 다시 확인하고 있습니다. 안전한 곳에서 잠시 기다려 주세요." }
                        effects.speakRecheckExplanation(message, stillSuppressed, {
                            if (stillSuppressed()) {
                                deliveredWarningFactors += notWarned
                                val completedAt = nowMs()
                                notWarned.forEach { lastWarningAtMs[it] = completedAt }
                            }
                        })
                    }
                }
                scheduleWatchdog(decision.epoch)
            }
            OfficialEnvironmentRuntimeAction.SAFE_STOP -> {
                val speech = effects.failureSpeech(facts.currentAssessment(facts.lifecycle().epoch, nowMs()))
                outputsAllowed = false
                effects.enterSafetyStop("official_environment_quality_persisted")
                effects.showStatus("공식 사용환경 이탈 · 안전 중지", "위치 또는 카메라 품질 저하가 제한된 재확인 뒤에도 계속되어 모든 보행 기능을 안전 중지했습니다. 전체 상태를 다시 확인하고 새 보행을 시작하세요.")
                effects.speakStatusExplanation(speech + " 보행 안내를 중지했습니다. 문제를 바로잡은 뒤 새 보행을 시작하세요.")
            }
        }
        lastAction = decision.action
        lastUnavailableFactors = decision.unavailableFactors
        silentCameraBoundaryLimited = boundaryRechecking && camera in decision.unavailableFactors
    }

    private fun clearNotices() {
        lastRequestedWarningEpisodes = emptyMap()
        deliveredWarningFactors.clear()
        lastWarningAtMs.clear()
        lastRestorationNoticeAtMs = null
        silentCameraBoundaryLimited = false
    }

    private fun noticeDue(previous: Long?, now: Long): Boolean =
        previous == null || now >= previous && now - previous >= 30_000L

    private fun reconcileRecheckEpisodes(decision: OfficialEnvironmentRuntimeDecision, now: Long) {
        if (decision.action != OfficialEnvironmentRuntimeAction.CONTINUE) return
        val rechecking = listOf(evidence.gpsQualityGate, evidence.cameraQualityGate).mapNotNull { gate ->
            val raw = gate.latestRawObservation() ?: return@mapNotNull null
            val observedAt = raw.observedAtElapsedRealtimeMs ?: return@mapNotNull null
            val maximumAge = raw.maximumEvidenceAgeMs ?: return@mapNotNull null
            if (raw.epoch != decision.epoch || raw.factor !in decision.enabledMeasuredFactors ||
                observedAt < 0L || now < observedAt || now - observedAt > maximumAge
            ) return@mapNotNull null
            if (raw.status == EnvironmentEvidenceStatus.PASS) {
                deliveredWarningFactors -= raw.factor
                return@mapNotNull null
            }
            val deadline = gate.pendingFailureDeadlineElapsedRealtimeMs() ?: return@mapNotNull null
            raw.takeIf { now < deadline && raw.factor !in decision.unavailableFactors }
        }
        val episodes = rechecking.associate { raw -> raw.factor to checkNotNull(
            (if (raw.factor == OfficialEnvironmentFactor.GPS_QUALITY) evidence.gpsQualityGate else evidence.cameraQualityGate)
                .pendingFailureDeadlineElapsedRealtimeMs()) }
        if (episodes == lastRequestedWarningEpisodes) return
        episodes.forEach { (factor, deadline) ->
            if (lastRequestedWarningEpisodes[factor]?.let { it != deadline } == true) deliveredWarningFactors -= factor
        }
        lastRequestedWarningEpisodes = episodes
        // A passing history still owns the outputs during this short recheck. Sensor quality
        // naturally fluctuates; announcing every pending episode interrupts useful guidance.
        // Keep the episode identities for partial recovery, but explain a problem only when
        // the assessed feature actually becomes unavailable in applyDecision above.
    }

    fun scheduleWatchdog(epoch: WalkRuntimeEpoch) {
        val profile = facts.profile() ?: return
        if (!facts.isRuntimeEpochCurrent(epoch)) return
        val generation = ++watchdogGeneration
        val unavailable = guard?.decision()?.unavailableFactors ?: emptySet()
        val locationRequired = facts.locationEnabled()
        val cameraRequired = facts.cameraEnabled()
        if (!locationRequired && !cameraRequired) return
        val now = nowMs()
        val evidenceDelayMs = if (outputsAllowed) {
            listOfNotNull(evidence.gpsEvidence.takeIf { locationRequired }, evidence.cameraEvidence.takeIf { cameraRequired })
                .filter { it.epoch == epoch && it.status == EnvironmentEvidenceStatus.PASS && it.factor !in unavailable &&
                    it.observedAtElapsedRealtimeMs != null && it.maximumEvidenceAgeMs != null }
                .minOfOrNull {
                    val observedAt = checkNotNull(it.observedAtElapsedRealtimeMs)
                    val maximumAge = minOf(profile.maximumMeasuredEvidenceAgeMs, checkNotNull(it.maximumEvidenceAgeMs))
                    if (observedAt < 0L || now < observedAt) 1L else {
                        val remaining = maximumAge - (now - observedAt)
                        when { remaining < 1L -> 1L; remaining == Long.MAX_VALUE -> Long.MAX_VALUE; else -> remaining + 1L }
                    }
                } ?: profile.runtimeRetryIntervalMs
        } else profile.runtimeRetryIntervalMs
        val qualityFailureDelayMs = listOfNotNull(evidence.gpsQualityGate.pendingFailureDeadlineElapsedRealtimeMs(),
            evidence.cameraQualityGate.pendingFailureDeadlineElapsedRealtimeMs())
            .minOfOrNull { (it - now).coerceAtLeast(1L) }
        val delay = minOf(evidenceDelayMs, profile.runtimeRetryIntervalMs, qualityFailureDelayMs ?: evidenceDelayMs)
        schedule(delay) {
            if (generation == watchdogGeneration && facts.isRuntimeEpochCurrent(epoch)) applyCurrentAssessment()
        }
    }
}

internal data class OfficialEnvironmentLifecycleFacts(val epoch: WalkRuntimeEpoch, val state: WalkSessionState)

internal interface OfficialEnvironmentRuntimeFactsReader {
    fun lifecycle(): OfficialEnvironmentLifecycleFacts
    fun currentRuntimeEpoch(): WalkRuntimeEpoch?
    fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch): Boolean
    fun profile(): ApprovedOfficialEnvironmentProfile?
    fun continuousSourcePresent(): Boolean
    fun continuousGpsEvidence(epoch: WalkRuntimeEpoch, nowMs: Long): MeasuredEnvironmentEvidence?
    fun latestMountingCameraAssessment(): CameraFrameQualityAssessment?
    fun currentAssessment(epoch: WalkRuntimeEpoch, nowMs: Long): OfficialEnvironmentAssessment
    fun locationEnabled(): Boolean
    fun cameraEnabled(): Boolean
    fun cameraHandoffEpoch(): WalkRuntimeEpoch?
}

internal interface OfficialEnvironmentRuntimeEffects {
    fun reassessMountingWithoutExplicitRetry()
    fun invalidateReportOutput(reason: String)
    fun restrictGpsNavigationAndCancelFeedback()
    fun resumeGpsNavigation()
    fun cancelPendingFeedbackDeliveries()
    fun cancelVoiceAndFeedbackActuator()
    fun showStatus(title: String, detail: String)
    fun speakStatusExplanation(message: String)
    fun speakRecheckExplanation(message: String, current: () -> Boolean, delivered: () -> Unit) {
        if (current()) speakStatusExplanation(message)
    }
    fun failureSpeech(assessment: OfficialEnvironmentAssessment, factors: Set<OfficialEnvironmentFactor>? = null): String
    fun locationRecoveryActionMessage(): String
    fun enterSafetyStop(reason: String)
}
