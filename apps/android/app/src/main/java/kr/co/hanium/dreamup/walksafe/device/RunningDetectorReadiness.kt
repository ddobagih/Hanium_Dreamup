package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.inference.RunningDetectorVerificationState

/**
 * S14-33-F1 (FP-039 failure_behavior): what the running-detector verification means for the
 * pre-walk device check and for walk entry. Loading alone is not readiness; the fixed sample
 * inference on the exact running instance must have issued a token. Waiting is bounded by the
 * verification deadline, and a failed verification is never READY.
 */
object RunningDetectorReadiness {
    /** Detector load reason after a failed verification disabled the running instance. */
    const val SAMPLE_INFERENCE_UNVERIFIED_REASON = "sample_inference_unverified"

    const val WALK_BLOCK_NOTICE_KO =
        "장애물 탐지 모델의 예제 추론 확인에 실패해 위험 탐지와 보행 안내를 시작하지 않습니다. " +
            "'카메라·거리 기능 다시 점검'을 누르거나 앱을 완전히 종료한 뒤 다시 열어 주세요."

    const val CHECKING_NOTICE_KO =
        "객체인식 모델의 예제 추론을 확인하고 있습니다. 확인이 끝나기 전에는 위험 안내를 내보내지 않습니다."

    /** Device-check detector row for a model that loaded (the caller checks the load flags first). */
    fun deviceCheckSignal(state: RunningDetectorVerificationState): PostLoginDeviceCheckSignal = when (state) {
        RunningDetectorVerificationState.VERIFIED -> PostLoginDeviceCheckSignal.READY
        RunningDetectorVerificationState.FAILED -> PostLoginDeviceCheckSignal.UNAVAILABLE
        // Bounded: every adoption begins a verification whose deadline ends in VERIFIED or FAILED.
        RunningDetectorVerificationState.PENDING,
        RunningDetectorVerificationState.NONE,
        -> PostLoginDeviceCheckSignal.PENDING
    }

    /**
     * True while the model failed its sample inference: before the failed instance is detached
     * (FAILED) and afterwards (unavailable with [SAMPLE_INFERENCE_UNVERIFIED_REASON]) until a reload.
     */
    fun blocksWalkEntry(
        state: RunningDetectorVerificationState,
        detectorAvailable: Boolean,
        detectorLoadReason: String?,
    ): Boolean = state == RunningDetectorVerificationState.FAILED ||
        (!detectorAvailable && detectorLoadReason == SAMPLE_INFERENCE_UNVERIFIED_REASON)
}
