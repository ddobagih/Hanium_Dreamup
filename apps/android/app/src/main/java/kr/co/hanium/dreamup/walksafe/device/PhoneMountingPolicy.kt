package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

enum class PhoneMountingMethod {
    CHEST_FORWARD,
    NECKLACE_FORWARD,
    HANDHELD,
    POCKET,
    UNKNOWN,
}

data class PhoneMountingUserConfirmation(
    val epoch: WalkRuntimeEpoch,
    val confirmedAtElapsedRealtimeMs: Long,
    val method: PhoneMountingMethod,
    val postFaultCorrectionConfirmed: Boolean,
)

/** Requests fresh sensor checks; never asserts a mounting method or changes observations. */
data class PhoneMountingCheckRequest(
    val epoch: WalkRuntimeEpoch,
    val requestId: Long,
    val requestedAtElapsedRealtimeMs: Long,
)

data class ApprovedPhoneMountingProfile(
    val profileId: String,
    val cameraFrameQualityProfileId: String,
    /** Maximum age for camera-derived mounting evidence. */
    val maximumEvidenceAgeMs: Long,
    val maximumRuntimeRetryAttempts: Int,
    /** A user confirmation is session-bound and may remain valid longer than a camera frame. */
    val maximumUserConfirmationAgeMs: Long,
) {
    constructor(
        profileId: String,
        cameraFrameQualityProfileId: String,
        maximumEvidenceAgeMs: Long,
        maximumRuntimeRetryAttempts: Int,
    ) : this(
        profileId = profileId,
        cameraFrameQualityProfileId = cameraFrameQualityProfileId,
        maximumEvidenceAgeMs = maximumEvidenceAgeMs,
        maximumRuntimeRetryAttempts = maximumRuntimeRetryAttempts,
        maximumUserConfirmationAgeMs = maximumEvidenceAgeMs,
    )

    init {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(cameraFrameQualityProfileId.isNotBlank()) {
            "cameraFrameQualityProfileId must not be blank"
        }
        require(maximumEvidenceAgeMs >= 0L) {
            "maximumEvidenceAgeMs must not be negative"
        }
        require(maximumRuntimeRetryAttempts > 0) {
            "maximumRuntimeRetryAttempts must be positive"
        }
        require(maximumUserConfirmationAgeMs >= 0L) {
            "maximumUserConfirmationAgeMs must not be negative"
        }
    }
}

data class PhoneMountingRuntimeState(
    val epoch: WalkRuntimeEpoch,
    val approvedProfile: ApprovedPhoneMountingProfile?,
    val correctionRequiredSinceElapsedRealtimeMs: Long? = null,
    val failedRuntimeRetryAttempts: Int = 0,
    val safetyStopRequired: Boolean = false,
) {
    init {
        require(failedRuntimeRetryAttempts >= 0) {
            "failedRuntimeRetryAttempts must not be negative"
        }
    }
}

enum class PhoneMountingStatus {
    SUITABLE,
    CORRECTION_REQUIRED,
    UNUSABLE,
}

enum class PhoneMountingAssessmentPhase {
    PREFLIGHT,
    ACTIVE,
}

/** The camera keeps measuring, so the walker's whole job is to stop moving the phone. */
private const val HOLD_STILL_ACTION_KO = "자세를 유지하면 다시 확인합니다."

/** Evidence from a finished walk is never repaired in place; only a new walk clears it. */
private const val NEW_WALK_ACTION_KO = "새 보행으로 다시 시작하세요."

enum class PhoneMountingReason(
    val accessibleReasonKo: String,
    val accessibleActionKo: String,
    /**
     * Whether the cause is worth the seconds it costs to say it out loud. It is not when the
     * walker answers every cause in the group the same way: evidence that the next camera frame
     * replaces, or evidence bound to a finished walk. The reason still reaches the screen, which
     * is read on demand rather than mid-stride.
     */
    val speakReason: Boolean = true,
) {
    PASSED(
        "휴대전화가 앞을 향하도록 고정되었고 카메라 상태가 확인되었습니다.",
        "보행을 시작하거나 다시 시작하기 전 주변 안전을 한 번 더 확인하세요.",
    ),
    SENSOR_CHECK_PASSED(
        "카메라 방향과 흔들림, 영상 품질이 실제 센서 점검 기준을 충족했습니다.",
        "장착 방식을 자동 판별한 결과는 아닙니다. 휴대전화를 앞을 향하게 안정적으로 유지하세요.",
    ),
    CHECK_REQUEST_EPOCH_MISMATCH(
        "이전 보행의 센서 점검 요청은 현재 보행에 사용할 수 없습니다.",
        NEW_WALK_ACTION_KO,
        speakReason = false,
    ),
    CHECK_REQUEST_INVALID(
        "센서 점검 요청 시각이나 요청 정보가 올바르지 않습니다.",
        "안내 시작을 다시 선택해 새 센서 점검을 요청하세요.",
    ),
    POST_CHECK_CAMERA_EVIDENCE_REQUIRED(
        "이번 안내 시작 요청 이후의 새 카메라 측정이 필요합니다.",
        HOLD_STILL_ACTION_KO,
        speakReason = false,
    ),
    POST_FAULT_CHECK_REQUEST_REQUIRED(
        "장착 문제가 발생한 뒤의 새 센서 점검 요청이 필요합니다.",
        "안전한 곳에서 자세를 바로잡고 점검을 다시 요청하세요.",
    ),
    PROFILE_NOT_APPROVED(
        "승인된 휴대전화 장착 기준이 없어 장착 상태를 확인할 수 없습니다.",
        "보행을 시작하지 말고 기존 보조수단을 계속 사용하세요.",
    ),
    STATE_EPOCH_MISMATCH(
        "이전 보행의 장착 상태가 남아 있어 현재 보행에 사용할 수 없습니다.",
        NEW_WALK_ACTION_KO,
        speakReason = false,
    ),
    USER_CONFIRMATION_MISSING(
        "휴대전화 정면 고정 확인이 필요합니다.",
        "휴대전화를 앞을 향하도록 고정한 뒤 화면의 장착 확인을 선택하세요.",
    ),
    USER_CONFIRMATION_EPOCH_MISMATCH(
        "이전 보행에서 확인한 장착 정보는 현재 보행에 사용할 수 없습니다.",
        NEW_WALK_ACTION_KO,
        speakReason = false,
    ),
    USER_CONFIRMATION_STALE(
        "장착 확인 시간이 오래되었거나 올바르지 않습니다.",
        "휴대전화 장착 상태를 지금 다시 확인하세요.",
    ),
    PROHIBITED_MOUNTING_METHOD(
        "손에 들거나 주머니에 넣은 휴대전화는 안전한 장착으로 인정되지 않습니다.",
        "휴대전화를 몸 앞에 세로로 고정하고 후면 카메라가 바깥을 향하게 하세요.",
    ),
    CAMERA_EVIDENCE_MISSING(
        "카메라 방향과 가림, 흔들림, 영상 품질을 확인할 수 없습니다.",
        "렌즈가 앞을 향하고 가리지 않았는지 확인한 뒤 다시 검사하세요.",
    ),
    CAMERA_EPOCH_MISMATCH(
        "이전 보행에서 측정한 카메라 상태는 현재 보행에 사용할 수 없습니다.",
        NEW_WALK_ACTION_KO,
        speakReason = false,
    ),
    CAMERA_PROFILE_MISMATCH(
        "승인된 카메라 검사 기준과 현재 검사 기준이 일치하지 않습니다.",
        "보행을 시작하지 말고 공식 지원 설정에서 다시 검사하세요.",
    ),
    CAMERA_EVIDENCE_STALE(
        "카메라 장착 검사가 오래되었거나 측정 시간이 올바르지 않습니다.",
        HOLD_STILL_ACTION_KO,
        speakReason = false,
    ),
    POST_CONFIRMATION_CAMERA_EVIDENCE_REQUIRED(
        "현재 장착을 확인하기 전에 측정한 카메라 결과는 사용할 수 없습니다.",
        HOLD_STILL_ACTION_KO,
        speakReason = false,
    ),
    CAMERA_QUALITY_UNKNOWN(
        "카메라 장착 품질을 신뢰할 수 있는 근거가 부족합니다.",
        "렌즈 방향과 가림, 흔들림을 확인한 뒤 다시 검사하세요.",
    ),
    CAMERA_QUALITY_FAILED(
        "카메라 방향, 가림, 흔들림 또는 영상 품질이 장착 기준에 맞지 않습니다.",
        "휴대전화 위치와 렌즈 가림을 바로잡은 뒤 다시 검사하세요.",
    ),
    POST_FAULT_CAMERA_EVIDENCE_REQUIRED(
        "장착 문제가 발생하기 전에 측정한 카메라 결과로는 보행을 다시 시작할 수 없습니다.",
        HOLD_STILL_ACTION_KO,
        speakReason = false,
    ),
    POST_FAULT_CONFIRMATION_REQUIRED(
        "장착 문제를 바로잡았다는 새 확인 없이는 보행을 다시 시작할 수 없습니다.",
        "현재 장착 상태를 확인하고 교정 완료를 명시적으로 선택하세요.",
    ),
    RETRY_LIMIT_REACHED(
        "제한된 장착 재검사에서 상태가 회복되지 않았습니다.",
        "위험 안내를 신뢰하지 말고 전체 보행을 안전하게 멈추세요.",
    ),
    SAFETY_STOP_LATCHED(
        "장착 문제로 안전정지가 필요하며 자동으로 보행을 다시 시작할 수 없습니다.",
        NEW_WALK_ACTION_KO,
        speakReason = false,
    ),
    ;

    /** What the walker hears; the cause is dropped where every member answers to one action. */
    val spokenGuidanceKo: String
        get() = if (speakReason) "$accessibleReasonKo $accessibleActionKo" else accessibleActionKo
}

data class PhoneMountingAssessment(
    val phase: PhoneMountingAssessmentPhase,
    val status: PhoneMountingStatus,
    val reason: PhoneMountingReason,
    val profileId: String?,
    val nextState: PhoneMountingRuntimeState,
    val retryAllowed: Boolean,
) {
    val canStartOrResumeDetection: Boolean
        get() = status == PhoneMountingStatus.SUITABLE

    val mustSuppressDetectionOutput: Boolean
        get() = status != PhoneMountingStatus.SUITABLE

    val requiresSafetyStop: Boolean
        get() =
            phase == PhoneMountingAssessmentPhase.ACTIVE &&
                status == PhoneMountingStatus.UNUSABLE

    val accessibleReasonKo: String
        get() = reason.accessibleReasonKo

    val accessibleActionKo: String
        get() = reason.accessibleActionKo

    val spokenGuidanceKo: String
        get() = reason.spokenGuidanceKo
}

object PhoneMountingPolicy {
    val productionProfile: ApprovedPhoneMountingProfile? = null

    private val allowedMethods = setOf(
        PhoneMountingMethod.CHEST_FORWARD,
        PhoneMountingMethod.NECKLACE_FORWARD,
    )

    fun initialState(
        epoch: WalkRuntimeEpoch,
        approvedProfile: ApprovedPhoneMountingProfile?,
    ) = PhoneMountingRuntimeState(
        epoch = epoch,
        approvedProfile = approvedProfile,
    )

    fun assess(
        phase: PhoneMountingAssessmentPhase,
        currentEpoch: WalkRuntimeEpoch,
        nowElapsedRealtimeMs: Long,
        userConfirmation: PhoneMountingUserConfirmation?,
        cameraFrameQuality: CameraFrameQualityAssessment?,
        previousState: PhoneMountingRuntimeState,
        runtimeRetryRequested: Boolean = false,
        checkRequest: PhoneMountingCheckRequest? = null,
    ): PhoneMountingAssessment {
        require(
            phase == PhoneMountingAssessmentPhase.ACTIVE || !runtimeRetryRequested,
        ) {
            "runtime retry is only valid during an active walk"
        }
        val state = previousState
        if (state.epoch != currentEpoch) {
            return unusable(
                phase = phase,
                previousState = state,
                reason = PhoneMountingReason.STATE_EPOCH_MISMATCH,
            )
        }
        if (state.safetyStopRequired) {
            return unusable(
                phase = phase,
                previousState = state,
                reason = PhoneMountingReason.SAFETY_STOP_LATCHED,
            )
        }

        val profile = state.approvedProfile ?: return unusable(
            phase = phase,
            previousState = state,
            reason = PhoneMountingReason.PROFILE_NOT_APPROVED,
        )

        val failureReason = validateCurrentEvidence(
            phase = phase,
            currentEpoch = currentEpoch,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            userConfirmation = userConfirmation,
            cameraFrameQuality = cameraFrameQuality,
            profile = profile,
            checkRequest = checkRequest,
        )
        if (failureReason != null) {
            if (phase == PhoneMountingAssessmentPhase.PREFLIGHT) {
                return preflightCorrection(
                    profile = profile,
                    previousState = state,
                    reason = failureReason,
                )
            }
            return correctionOrStop(
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                profile = profile,
                previousState = state,
                reason = failureReason,
                runtimeRetryRequested = runtimeRetryRequested,
            )
        }

        val faultSince = state.correctionRequiredSinceElapsedRealtimeMs
        if (phase == PhoneMountingAssessmentPhase.ACTIVE && faultSince != null) {
            val cameraObservedAt = cameraFrameQuality?.observedAtElapsedRealtimeMs
            if (cameraObservedAt == null || cameraObservedAt <= faultSince) {
                return correctionAfterFaultOrStop(
                    profile = profile,
                    previousState = state,
                    reason = PhoneMountingReason.POST_FAULT_CAMERA_EVIDENCE_REQUIRED,
                    runtimeRetryRequested = runtimeRetryRequested,
                )
            }
            val correctionRequestedAt = if (checkRequest != null) {
                checkRequest.requestedAtElapsedRealtimeMs.takeIf { it > faultSince }
            } else {
                userConfirmation?.takeIf {
                    it.postFaultCorrectionConfirmed && it.confirmedAtElapsedRealtimeMs > faultSince
                }?.confirmedAtElapsedRealtimeMs
            }
            if (correctionRequestedAt == null) {
                return correctionAfterFaultOrStop(
                    profile = profile,
                    previousState = state,
                    reason = if (checkRequest != null) {
                        PhoneMountingReason.POST_FAULT_CHECK_REQUEST_REQUIRED
                    } else {
                        PhoneMountingReason.POST_FAULT_CONFIRMATION_REQUIRED
                    },
                    runtimeRetryRequested = runtimeRetryRequested,
                )
            }
            if (cameraObservedAt <= correctionRequestedAt) {
                return correctionAfterFaultOrStop(
                    profile = profile,
                    previousState = state,
                    reason = PhoneMountingReason.POST_FAULT_CAMERA_EVIDENCE_REQUIRED,
                    runtimeRetryRequested = runtimeRetryRequested,
                )
            }
        }

        return PhoneMountingAssessment(
            phase = phase,
            status = PhoneMountingStatus.SUITABLE,
            reason = if (checkRequest != null) PhoneMountingReason.SENSOR_CHECK_PASSED else PhoneMountingReason.PASSED,
            profileId = profile.profileId,
            nextState = state.copy(
                correctionRequiredSinceElapsedRealtimeMs = null,
                failedRuntimeRetryAttempts = 0,
            ),
            retryAllowed = false,
        )
    }

    private fun preflightCorrection(
        profile: ApprovedPhoneMountingProfile,
        previousState: PhoneMountingRuntimeState,
        reason: PhoneMountingReason,
    ) = PhoneMountingAssessment(
        phase = PhoneMountingAssessmentPhase.PREFLIGHT,
        status = PhoneMountingStatus.CORRECTION_REQUIRED,
        reason = reason,
        profileId = profile.profileId,
        nextState = previousState.copy(
            correctionRequiredSinceElapsedRealtimeMs = null,
            failedRuntimeRetryAttempts = 0,
            safetyStopRequired = false,
        ),
        retryAllowed = true,
    )

    private fun validateCurrentEvidence(
        phase: PhoneMountingAssessmentPhase,
        currentEpoch: WalkRuntimeEpoch,
        nowElapsedRealtimeMs: Long,
        userConfirmation: PhoneMountingUserConfirmation?,
        cameraFrameQuality: CameraFrameQualityAssessment?,
        profile: ApprovedPhoneMountingProfile,
        checkRequest: PhoneMountingCheckRequest?,
    ): PhoneMountingReason? {
        val confirmation = userConfirmation
        if (checkRequest != null) {
            if (checkRequest.epoch != currentEpoch) return PhoneMountingReason.CHECK_REQUEST_EPOCH_MISMATCH
            if (checkRequest.requestId <= 0L || checkRequest.requestedAtElapsedRealtimeMs < 0L ||
                checkRequest.requestedAtElapsedRealtimeMs > nowElapsedRealtimeMs
            ) return PhoneMountingReason.CHECK_REQUEST_INVALID
            if (confirmation?.epoch == currentEpoch &&
                confirmation.method in setOf(PhoneMountingMethod.HANDHELD, PhoneMountingMethod.POCKET)
            ) return PhoneMountingReason.PROHIBITED_MOUNTING_METHOD
        } else {
            if (confirmation == null) return PhoneMountingReason.USER_CONFIRMATION_MISSING
            if (confirmation.epoch != currentEpoch) {
                return PhoneMountingReason.USER_CONFIRMATION_EPOCH_MISMATCH
            }
            if (!isFresh(
                    observedAtElapsedRealtimeMs = confirmation.confirmedAtElapsedRealtimeMs,
                    nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                    maximumAgeMs = profile.maximumUserConfirmationAgeMs,
                )
            ) {
                return PhoneMountingReason.USER_CONFIRMATION_STALE
            }
            if (confirmation.method !in allowedMethods) {
                return PhoneMountingReason.PROHIBITED_MOUNTING_METHOD
            }
        }

        val camera = cameraFrameQuality ?: return PhoneMountingReason.CAMERA_EVIDENCE_MISSING
        if (camera.epoch != currentEpoch) {
            return PhoneMountingReason.CAMERA_EPOCH_MISMATCH
        }
        if (camera.profileId != profile.cameraFrameQualityProfileId) {
            return PhoneMountingReason.CAMERA_PROFILE_MISMATCH
        }
        val cameraMaximumAgeMs = camera.maximumEvidenceAgeMs
            ?: return PhoneMountingReason.CAMERA_EVIDENCE_STALE
        val cameraObservedAt = camera.observedAtElapsedRealtimeMs
            ?: return PhoneMountingReason.CAMERA_EVIDENCE_STALE
        if (!isFresh(
                observedAtElapsedRealtimeMs = cameraObservedAt,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                maximumAgeMs = minOf(profile.maximumEvidenceAgeMs, cameraMaximumAgeMs),
            )
        ) {
            return PhoneMountingReason.CAMERA_EVIDENCE_STALE
        }
        if (checkRequest != null && cameraObservedAt <= checkRequest.requestedAtElapsedRealtimeMs) {
            return PhoneMountingReason.POST_CHECK_CAMERA_EVIDENCE_REQUIRED
        }
        if (checkRequest == null && phase == PhoneMountingAssessmentPhase.PREFLIGHT &&
            cameraObservedAt <= requireNotNull(confirmation).confirmedAtElapsedRealtimeMs
        ) {
            return PhoneMountingReason.POST_CONFIRMATION_CAMERA_EVIDENCE_REQUIRED
        }
        if (
            camera.status == EnvironmentEvidenceStatus.PASS &&
            camera.reason != CameraFrameQualityReason.PASSED
        ) {
            return PhoneMountingReason.CAMERA_QUALITY_UNKNOWN
        }
        return when (camera.status) {
            EnvironmentEvidenceStatus.PASS -> null
            EnvironmentEvidenceStatus.FAIL -> PhoneMountingReason.CAMERA_QUALITY_FAILED
            EnvironmentEvidenceStatus.UNKNOWN -> PhoneMountingReason.CAMERA_QUALITY_UNKNOWN
        }
    }

    private fun correctionOrStop(
        nowElapsedRealtimeMs: Long,
        profile: ApprovedPhoneMountingProfile,
        previousState: PhoneMountingRuntimeState,
        reason: PhoneMountingReason,
        runtimeRetryRequested: Boolean,
    ): PhoneMountingAssessment {
        val retryAttempts = previousState.failedRuntimeRetryAttempts +
            if (
                runtimeRetryRequested &&
                previousState.correctionRequiredSinceElapsedRealtimeMs != null
            ) {
                1
            } else {
                0
            }
        if (
            profile.maximumRuntimeRetryAttempts == 0 ||
            retryAttempts >= profile.maximumRuntimeRetryAttempts
        ) {
            return unusable(
                phase = PhoneMountingAssessmentPhase.ACTIVE,
                previousState = previousState.copy(
                    failedRuntimeRetryAttempts = retryAttempts,
                ),
                reason = PhoneMountingReason.RETRY_LIMIT_REACHED,
            )
        }
        val nextState = previousState.copy(
            correctionRequiredSinceElapsedRealtimeMs = maxOf(
                previousState.correctionRequiredSinceElapsedRealtimeMs
                    ?: nowElapsedRealtimeMs,
                nowElapsedRealtimeMs,
            ),
            failedRuntimeRetryAttempts = retryAttempts,
        )
        return PhoneMountingAssessment(
            phase = PhoneMountingAssessmentPhase.ACTIVE,
            status = PhoneMountingStatus.CORRECTION_REQUIRED,
            reason = reason,
            profileId = profile.profileId,
            nextState = nextState,
            retryAllowed = true,
        )
    }

    private fun correctionAfterFaultOrStop(
        profile: ApprovedPhoneMountingProfile,
        previousState: PhoneMountingRuntimeState,
        reason: PhoneMountingReason,
        runtimeRetryRequested: Boolean,
    ): PhoneMountingAssessment {
        val retryAttempts = previousState.failedRuntimeRetryAttempts +
            if (runtimeRetryRequested) 1 else 0
        if (retryAttempts >= profile.maximumRuntimeRetryAttempts) {
            return unusable(
                phase = PhoneMountingAssessmentPhase.ACTIVE,
                previousState = previousState.copy(
                    failedRuntimeRetryAttempts = retryAttempts,
                ),
                reason = PhoneMountingReason.RETRY_LIMIT_REACHED,
            )
        }
        return PhoneMountingAssessment(
            phase = PhoneMountingAssessmentPhase.ACTIVE,
            status = PhoneMountingStatus.CORRECTION_REQUIRED,
            reason = reason,
            profileId = profile.profileId,
            nextState = previousState.copy(
                failedRuntimeRetryAttempts = retryAttempts,
            ),
            retryAllowed = true,
        )
    }

    private fun unusable(
        phase: PhoneMountingAssessmentPhase,
        previousState: PhoneMountingRuntimeState,
        reason: PhoneMountingReason,
    ) = PhoneMountingAssessment(
        phase = phase,
        status = PhoneMountingStatus.UNUSABLE,
        reason = reason,
        profileId = previousState.approvedProfile?.profileId,
        nextState = previousState.copy(
            safetyStopRequired =
                previousState.safetyStopRequired ||
                    phase == PhoneMountingAssessmentPhase.ACTIVE,
        ),
        retryAllowed = false,
    )

    private fun isFresh(
        observedAtElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long,
        maximumAgeMs: Long,
    ): Boolean {
        if (observedAtElapsedRealtimeMs < 0L || nowElapsedRealtimeMs < 0L) return false
        val ageMs = nowElapsedRealtimeMs - observedAtElapsedRealtimeMs
        return ageMs in 0L..maximumAgeMs
    }
}
