package kr.co.hanium.dreamup.walksafe.startup.capability

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.device.environment.*

/** Owns only accessible prewalk progress, not the native human intent or readiness evidence. */
internal class PrewalkGuidanceProgress(private val port: PrewalkGuidancePort, private val readyGraceMs: Long) {
    private var prewalkGuidanceStage = 0
    fun reset() { prewalkGuidanceStage = 0 }

    fun updatePrewalkGuidance(
        epoch: WalkRuntimeEpoch,
        officialEnvironmentReady: Boolean,
        phoneMountingReady: Boolean,
    ) {
        if (port.nativeStartPending) {
            port.continueNativeStart()
        }
        if (
            !port.preflightActive ||
            port.snapshot().state !in
            setOf(WalkSessionState.READY, WalkSessionState.PAUSED)
        ) {
            prewalkGuidanceStage = 0
            return
        }
        if (
            officialEnvironmentReady &&
            phoneMountingReady &&
            port.preflightActive &&
            port.claimReadyGrace()
        ) {
            port.scheduleReadyTimeout(
                epoch = epoch,
                generation = port.preflightGeneration,
                delayMs = readyGraceMs,
            )
        }
        val nextStage = when {
            officialEnvironmentReady && phoneMountingReady -> 2
            officialEnvironmentReady -> 1
            else -> 0
        }
        if (nextStage == 0 || nextStage <= prewalkGuidanceStage) return
        val target = when (nextStage) {
            1 -> port.mountingTarget()
            2 -> port.startupTarget()
            else -> error("unreachable pre-walk guidance stage")
        }
        if (!target.shown || !target.enabled) return
        prewalkGuidanceStage = nextStage
        target.describe(when (nextStage) {
            1 -> "환경 측정 통과. 휴대전화를 정면으로 고정한 뒤 확인하세요."
            else -> "환경과 장착 점검 통과. 보행 시작 확인"
        })
        target.post {
            if (
                prewalkGuidanceStage != nextStage ||
                !target.shown ||
                !target.enabled ||
                port.snapshot().epoch != epoch
            ) return@post
            target.focus()
        }
    }
}
