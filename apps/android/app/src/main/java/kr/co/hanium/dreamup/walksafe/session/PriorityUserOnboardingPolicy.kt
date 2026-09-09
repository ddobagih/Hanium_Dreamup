package kr.co.hanium.dreamup.walksafe.session

const val PRIORITY_USER_TRAINING_POLICY_VERSION = "FP-004-1.1.0"

enum class PriorityUserSupportPriority {
    EQUAL_BLIND_AND_LOW_VISION,
}

enum class PriorityUserAgeBand(val labelKo: String) {
    UNKNOWN("연령대 선택 필요"),
    UNDER_14("만 14세 미만"),
    AGE_14_TO_17("만 14세 이상 18세 미만"),
    ADULT_18_PLUS("만 18세 이상"),
    VERIFIED_14_PLUS("가입 시 만 14세 이상 확인됨"),
}

enum class PriorityUserPractice(
    val actionLabelKo: String,
    val instructionKo: String,
) {
    // The rehearsal has to be the sentence the walker will actually meet, or it teaches a cue the
    // app never gives. A live warning names whatever it saw, so this names nothing and keeps the
    // opening and the action identical.
    HAZARD_ALERT(
        actionLabelKo = "위험 안내 연습",
        instructionKo = "전방 장애물. 멈추세요. 주변을 확인하세요.",
    ),
    PAUSE(
        actionLabelKo = "일시정지 연습",
        instructionKo = "보행 안내를 일시정지했습니다.",
    ),
    RESUME(
        actionLabelKo = "재개 연습",
        instructionKo = "주변을 확인했습니다. 보행 안내를 다시 시작합니다.",
    ),
    // MainActivity speaks "보행 기능을 안전 중지했습니다. $reason", so the rehearsal carries the
    // same opening and the fallback reason; "안전정지" existed only here.
    SAFE_STOP(
        actionLabelKo = "안전정지 연습",
        instructionKo = "보행 기능을 안전 중지했습니다. " +
            "필수 기능 상태가 바뀌어 새 보행 준비가 필요합니다.",
    ),
}

enum class PriorityUserPracticeDeliverySignal {
    SPEECH_PLAYBACK_COMPLETED,
    VIBRATION_REQUEST_WINDOW_ELAPSED,
}

class PriorityUserPracticeAttemptToken internal constructor(
    val sequence: Long,
)

class PriorityUserUsagePlaybackToken internal constructor(val sequence: Long)

enum class PriorityUserNativeEducationStep {
    SAFETY_EDUCATION,
    APP_USAGE_EDUCATION,
    COMPLETE,
}

enum class PriorityUserBlockReason(val noticeKo: String) {
    AGE_SELECTION_REQUIRED("먼저 연령대를 선택하세요."),
    MINIMUM_AGE_NOT_MET("만 14세 미만은 계정을 활성화하거나 보행을 시작할 수 없습니다."),
    GUARDIAN_VERIFICATION_REQUIRED(
        "만 18세 미만 계정은 보호자 확인이 끝날 때까지 활성화할 수 없습니다.",
    ),
    OFFLINE_KOREAN_VOICE_UNAVAILABLE(
        "오프라인 한국어 음성 안내를 사용할 수 없어 보행을 시작할 수 없습니다.",
    ),
    VIBRATION_UNAVAILABLE("진동 안내를 사용할 수 없어 보행을 시작할 수 없습니다."),
    APP_USAGE_EDUCATION_INCOMPLETE("사용환경 확인과 앱 사용교육을 완료하세요."),
    SAFETY_EDUCATION_NOT_REVIEWED("안전 제한 안내를 먼저 확인하세요."),
    SAFE_PRACTICE_PLACE_NOT_CONFIRMED("실제 도로가 아닌 안전한 연습 장소를 먼저 확인하세요."),
    REQUIRED_PRACTICE_INCOMPLETE("위험 안내·일시정지·재개·안전정지 연습을 모두 완료하세요."),
}

data class PriorityUserSupportEnvironment(
    val screenReaderActive: Boolean,
    val largeTextEnabled: Boolean,
    val highContrastEnabled: Boolean,
    val offlineKoreanVoiceAvailable: Boolean,
    val vibrationAvailable: Boolean,
)

data class PriorityUserOnboardingSnapshot(
    val policyVersion: String = PRIORITY_USER_TRAINING_POLICY_VERSION,
    val ageBand: PriorityUserAgeBand = PriorityUserAgeBand.UNKNOWN,
    val guardianVerified: Boolean = false,
    val educationReviewed: Boolean = false,
    val safePracticePlaceConfirmed: Boolean = false,
    val completedPractices: Set<PriorityUserPractice> = emptySet(),
    val phonePostureAcknowledged: Boolean = false,
    val practiceNecessityReviewed: Boolean = false,
    val educationAccepted: Boolean = false,
    val usageConditionsAcknowledged: Boolean = false,
    val appUsageReviewed: Boolean = false,
    val appUsageAccepted: Boolean = false,
) {
    init {
        require(policyVersion == PRIORITY_USER_TRAINING_POLICY_VERSION) {
            "Unsupported priority-user training policy version"
        }
        require(!guardianVerified || ageBand == PriorityUserAgeBand.AGE_14_TO_17) {
            "Guardian verification is meaningful only for a minor account"
        }
        require(!practiceNecessityReviewed || educationReviewed) {
            "Practice-necessity playback requires completed safety education"
        }
        require(!educationAccepted ||
            (educationReviewed && practiceNecessityReviewed)) {
            "Safety agreement requires both completed playbacks"
        }
        require(!usageConditionsAcknowledged || (educationAccepted && phonePostureAcknowledged)) {
            "Usage-scope acknowledgment requires safety agreement and mounting instructions"
        }
        require(!appUsageReviewed || educationAccepted) {
            "App-usage playback follows safety agreement"
        }
        require(!appUsageAccepted || (usageConditionsAcknowledged && appUsageReviewed)) {
            "App-usage completion requires scope acknowledgment and terminal playback"
        }
        require(!safePracticePlaceConfirmed || educationReviewed) {
            "A safe practice place can be confirmed only after education review"
        }
        require(completedPractices.isEmpty() || safePracticePlaceConfirmed) {
            "Practice success requires a confirmed safe practice place"
        }
        require(
            completedPractices ==
                PriorityUserPractice.entries.take(completedPractices.size).toSet(),
        ) {
            "Completed practices must be one ordered prefix"
        }
    }

    val educationConsentComplete: Boolean
        get() = phonePostureAcknowledged && educationReviewed &&
            practiceNecessityReviewed && educationAccepted

    val safetyEducationConsentComplete: Boolean
        get() = educationReviewed && practiceNecessityReviewed && educationAccepted

    val nativeEducationComplete: Boolean
        get() = educationConsentComplete && usageConditionsAcknowledged &&
            appUsageReviewed && appUsageAccepted

    val nativeEducationStep: PriorityUserNativeEducationStep
        get() = when {
            nativeEducationComplete -> PriorityUserNativeEducationStep.COMPLETE
            safetyEducationConsentComplete -> PriorityUserNativeEducationStep.APP_USAGE_EDUCATION
            else -> PriorityUserNativeEducationStep.SAFETY_EDUCATION
        }

    val nextRequiredPractice: PriorityUserPractice?
        get() = PriorityUserPractice.entries.getOrNull(completedPractices.size)

    val trainingComplete: Boolean
        get() = educationReviewed &&
            safePracticePlaceConfirmed &&
            nextRequiredPractice == null
}

data class PriorityUserOnboardingDecision(
    val supportPriority: PriorityUserSupportPriority,
    val accountBlockReason: PriorityUserBlockReason?,
    val walkBlockReason: PriorityUserBlockReason?,
    val remainingPractices: List<PriorityUserPractice>,
    val requiresSafetyStop: Boolean,
) {
    val mayActivateAccount: Boolean
        get() = accountBlockReason == null

    val mayStartWalk: Boolean
        get() = walkBlockReason == null

    val noticeKo: String
        get() = walkBlockReason?.noticeKo ?: "최초 보행 전 안전교육 확인을 완료했습니다."
}

/**
 * A sensor-free practice sandbox backed by the production walk lifecycle reducer. It proves that
 * the hazard acknowledgement advances the practice state and that pause, resume and safe-stop
 * actions reach their expected walk states without activating app sensors, restoring a real route,
 * or representing a real walk.
 */
internal class PriorityUserPracticeLifecycle(
    completedPractices: Set<PriorityUserPractice>,
) {
    private val lifecycle = WalkSessionLifecycle(
        sessionIdFactory = { "priority-user-practice" },
        clock = { 0L },
    )
    private var nextPracticeIndex = 0

    init {
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(
            WalkSessionEvent.InitialCheckCompleted(
                ready(WalkSessionAction.START_WALK),
            ),
        )
        lifecycle.handle(
            WalkSessionEvent.StartRequested(
                lifecycle.snapshot().confirmationToken
                    ?: error("Practice sandbox did not produce a start token"),
            ),
        )
        PriorityUserPractice.entries
            .take(completedPractices.size)
            .forEach { practice ->
                check(perform(practice)) {
                    "Stored practice prefix cannot be replayed"
                }
            }
    }

    fun perform(practice: PriorityUserPractice): Boolean {
        if (PriorityUserPractice.entries.getOrNull(nextPracticeIndex) != practice) {
            return false
        }
        val performed = when (practice) {
            PriorityUserPractice.HAZARD_ALERT ->
                lifecycle.snapshot().state == WalkSessionState.ACTIVE

            PriorityUserPractice.PAUSE -> {
                val before = lifecycle.snapshot()
                val transition = lifecycle.handle(WalkSessionEvent.RecheckRequested)
                before.state == WalkSessionState.ACTIVE &&
                    transition.changed &&
                    transition.current.state == WalkSessionState.PAUSED &&
                    transition.current.recoveryStage == WalkSessionRecoveryStage.RECHECK_REQUIRED
            }

            PriorityUserPractice.RESUME -> {
                val before = lifecycle.snapshot()
                if (
                    before.state != WalkSessionState.PAUSED ||
                    before.recoveryStage != WalkSessionRecoveryStage.RECHECK_REQUIRED
                ) {
                    false
                } else {
                    val recheck = lifecycle.handle(
                        WalkSessionEvent.RecoveryRecheckCompleted(
                            ready(WalkSessionAction.RESUME_WALK),
                        ),
                    )
                    val token = lifecycle.snapshot().confirmationToken
                    if (
                        !recheck.changed ||
                        recheck.current.recoveryStage !=
                        WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION ||
                        token == null
                    ) {
                        false
                    } else {
                        val resumed = lifecycle.handle(
                            WalkSessionEvent.ResumeConfirmationReceived(
                                confirmation = WalkSessionResumeConfirmation.START,
                                token = token,
                            ),
                        )
                        resumed.changed &&
                            resumed.current.state == WalkSessionState.ACTIVE &&
                            resumed.current.recoveryStage == null
                    }
                }
            }

            PriorityUserPractice.SAFE_STOP -> {
                val before = lifecycle.snapshot()
                val transition = lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
                before.state == WalkSessionState.ACTIVE &&
                    transition.changed &&
                    transition.current.state == WalkSessionState.SAFE_STOP &&
                    transition.current.mode == WalkSessionMode.UNAVAILABLE
            }
        }
        if (performed) {
            nextPracticeIndex += 1
        }
        return performed
    }

    fun snapshot(): WalkSessionSnapshot = lifecycle.snapshot()

    fun nextRequiredPractice(): PriorityUserPractice? =
        PriorityUserPractice.entries.getOrNull(nextPracticeIndex)

    private fun ready(action: WalkSessionAction): WalkSessionReadinessSnapshot {
        val epoch = lifecycle.snapshot().epoch
        val collector = WalkSessionReadinessCollector(
            epoch = epoch,
            plan = WalkSessionReadinessPlan(
                action = action,
                mode = WalkSessionMode.FULL,
            ),
        )
        collector.plan.requiredRequirements.forEach { requirement ->
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = requirement,
                    status = WalkSessionReadinessStatus.READY,
                ),
            )
        }
        return collector.build(assessedAtEpochMs = 0L)
    }
}

/**
 * FP-004 onboarding state. Education and practice are completed only by terminal delivery
 * callbacks. This state can survive a process restart, but it never restores an actual walk or
 * route.
 */
class PriorityUserOnboardingPolicy(
    initial: PriorityUserOnboardingSnapshot = PriorityUserOnboardingSnapshot(),
) {
    private data class PendingPracticeAttempt(
        val token: PriorityUserPracticeAttemptToken,
        val practice: PriorityUserPractice,
        val speechPlaybackCompleted: Boolean = false,
        val vibrationRequestWindowElapsed: Boolean = false,
    )

    private val lock = Any()
    private var current = initial
    private var nextAttemptSequence = 1L
    private var nextUsageSequence = 1L
    private var pendingUsagePlayback: PriorityUserUsagePlaybackToken? = null
    private var pendingPractice: PendingPracticeAttempt? = null
    private var practiceLifecycle = PriorityUserPracticeLifecycle(initial.completedPractices)

    fun snapshot(): PriorityUserOnboardingSnapshot = synchronized(lock) { current }

    fun accountBlockReason(): PriorityUserBlockReason? = synchronized(lock) {
        accountBlockReason(current)
    }

    fun selectAgeBand(ageBand: PriorityUserAgeBand): PriorityUserOnboardingSnapshot =
        synchronized(lock) {
            if (current.ageBand != ageBand) {
                current = PriorityUserOnboardingSnapshot(ageBand = ageBand)
                cancelPendingPracticeLocked()
            }
            current
        }

    fun recordGuardianVerification(verified: Boolean): PriorityUserOnboardingSnapshot =
        synchronized(lock) {
            val next = if (current.ageBand == PriorityUserAgeBand.AGE_14_TO_17) {
                current.copy(guardianVerified = verified)
            } else {
                current.copy(guardianVerified = false)
            }
            if (next != current) {
                current = next
                cancelPendingPracticeLocked()
            }
            current
        }

    fun reviewSafetyEducation(
        voicePlaybackCompleted: Boolean,
    ): PriorityUserOnboardingSnapshot = synchronized(lock) {
        if (voicePlaybackCompleted && accountBlockReason(current) == null) {
            current = current.copy(educationReviewed = true)
        }
        current
    }

    fun acknowledgePhonePosture(): PriorityUserOnboardingSnapshot = synchronized(lock) {
        if (accountBlockReason(current) == null) {
            current = current.copy(phonePostureAcknowledged = true)
        }
        current
    }

    fun reviewPracticeNecessity(
        voicePlaybackCompleted: Boolean,
    ): PriorityUserOnboardingSnapshot = synchronized(lock) {
        if (voicePlaybackCompleted && current.educationReviewed && accountBlockReason(current) == null) {
            current = current.copy(practiceNecessityReviewed = true)
        }
        current
    }

    fun acceptEducationConsent(): PriorityUserOnboardingSnapshot = synchronized(lock) {
        if (current.educationReviewed && current.practiceNecessityReviewed && accountBlockReason(current) == null) {
            current = current.copy(educationAccepted = true)
        }
        current
    }

    fun acknowledgeUsageConditions(): PriorityUserOnboardingSnapshot = synchronized(lock) {
        if (current.safetyEducationConsentComplete && accountBlockReason(current) == null) {
            // Understanding operating limits is not evidence of the current physical environment.
            current = current.copy(phonePostureAcknowledged = true, usageConditionsAcknowledged = true)
        }
        current
    }

    fun beginAppUsageEducationPlayback(): PriorityUserUsagePlaybackToken? = synchronized(lock) {
        if (!current.safetyEducationConsentComplete || accountBlockReason(current) != null ||
            pendingUsagePlayback != null) return@synchronized null
        PriorityUserUsagePlaybackToken(nextUsageSequence++).also { pendingUsagePlayback = it }
    }

    fun finishAppUsageEducationPlayback(
        token: PriorityUserUsagePlaybackToken,
        completed: Boolean,
    ): PriorityUserOnboardingSnapshot = synchronized(lock) {
        if (pendingUsagePlayback !== token) return@synchronized current
        pendingUsagePlayback = null
        if (completed && current.safetyEducationConsentComplete && accountBlockReason(current) == null) {
            current = current.copy(appUsageReviewed = true)
        }
        current
    }

    fun cancelAppUsageEducationPlayback() = synchronized(lock) {
        pendingUsagePlayback = null
    }

    fun acceptAppUsageEducation(): PriorityUserOnboardingSnapshot = synchronized(lock) {
        if (current.safetyEducationConsentComplete && current.usageConditionsAcknowledged &&
            current.appUsageReviewed && accountBlockReason(current) == null) {
            current = current.copy(appUsageAccepted = true)
        }
        current
    }

    fun confirmSafePracticePlace(): PriorityUserOnboardingSnapshot = synchronized(lock) {
        if (current.educationReviewed && accountBlockReason(current) == null) {
            current = current.copy(safePracticePlaceConfirmed = true)
        }
        current
    }

    fun beginPractice(
        practice: PriorityUserPractice,
    ): PriorityUserPracticeAttemptToken? = synchronized(lock) {
        if (
            pendingPractice != null ||
            !current.safePracticePlaceConfirmed ||
            accountBlockReason(current) != null ||
            current.nextRequiredPractice != practice ||
            !practiceLifecycle.perform(practice)
        ) {
            return@synchronized null
        }
        PriorityUserPracticeAttemptToken(nextAttemptSequence++).also { token ->
            pendingPractice = PendingPracticeAttempt(
                token = token,
                practice = practice,
            )
        }
    }

    fun recordPracticeDelivery(
        token: PriorityUserPracticeAttemptToken,
        signal: PriorityUserPracticeDeliverySignal,
    ): PriorityUserOnboardingSnapshot = synchronized(lock) {
        val pending = pendingPractice
        if (pending == null || pending.token != token) return@synchronized current
        val signalled = when (signal) {
            PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED ->
                pending.copy(speechPlaybackCompleted = true)

            PriorityUserPracticeDeliverySignal.VIBRATION_REQUEST_WINDOW_ELAPSED ->
                pending.copy(vibrationRequestWindowElapsed = true)
        }
        if (
            signalled.speechPlaybackCompleted &&
            signalled.vibrationRequestWindowElapsed &&
            current.nextRequiredPractice == signalled.practice &&
            current.safePracticePlaceConfirmed &&
            accountBlockReason(current) == null
        ) {
            current = current.copy(
                completedPractices = current.completedPractices + signalled.practice,
            )
            pendingPractice = null
        } else {
            pendingPractice = signalled
        }
        current
    }

    fun cancelPractice(
        token: PriorityUserPracticeAttemptToken? = null,
    ): PriorityUserOnboardingSnapshot = synchronized(lock) {
        if (token == null || pendingPractice?.token == token) {
            cancelPendingPracticeLocked()
        }
        current
    }

    fun resetTraining(): PriorityUserOnboardingSnapshot = synchronized(lock) {
        current = current.copy(
            educationReviewed = false,
            safePracticePlaceConfirmed = false,
            completedPractices = emptySet(),
            phonePostureAcknowledged = false,
            practiceNecessityReviewed = false,
            educationAccepted = false,
            usageConditionsAcknowledged = false,
            appUsageReviewed = false,
            appUsageAccepted = false,
        )
        cancelPendingPracticeLocked()
        current
    }

    fun evaluate(
        environment: PriorityUserSupportEnvironment,
        walkIsActive: Boolean = false,
    ): PriorityUserOnboardingDecision = synchronized(lock) {
        val accountBlock = accountBlockReason(current)
        val remaining = PriorityUserPractice.entries.filterNot(
            current.completedPractices::contains,
        )
        val walkBlock = accountBlock ?: when {
            current.nativeEducationComplete -> null
            current.safetyEducationConsentComplete -> PriorityUserBlockReason.APP_USAGE_EDUCATION_INCOMPLETE
            !current.educationReviewed ->
                PriorityUserBlockReason.SAFETY_EDUCATION_NOT_REVIEWED
            !current.safePracticePlaceConfirmed ->
                PriorityUserBlockReason.SAFE_PRACTICE_PLACE_NOT_CONFIRMED
            remaining.isNotEmpty() ->
                PriorityUserBlockReason.REQUIRED_PRACTICE_INCOMPLETE
            else -> null
        }
        PriorityUserOnboardingDecision(
            supportPriority = PriorityUserSupportPriority.EQUAL_BLIND_AND_LOW_VISION,
            accountBlockReason = accountBlock,
            walkBlockReason = walkBlock,
            remainingPractices = remaining,
            requiresSafetyStop = walkIsActive && walkBlock != null,
        )
    }

    internal fun practiceLifecycleSnapshot(): WalkSessionSnapshot =
        synchronized(lock) { practiceLifecycle.snapshot() }

    internal fun practiceLifecycleNextRequiredPractice(): PriorityUserPractice? =
        synchronized(lock) { practiceLifecycle.nextRequiredPractice() }

    private fun accountBlockReason(
        snapshot: PriorityUserOnboardingSnapshot,
    ): PriorityUserBlockReason? = when (snapshot.ageBand) {
        PriorityUserAgeBand.UNKNOWN -> PriorityUserBlockReason.AGE_SELECTION_REQUIRED
        PriorityUserAgeBand.UNDER_14 -> PriorityUserBlockReason.MINIMUM_AGE_NOT_MET
        PriorityUserAgeBand.AGE_14_TO_17 -> if (snapshot.guardianVerified) {
            null
        } else {
            PriorityUserBlockReason.GUARDIAN_VERIFICATION_REQUIRED
        }
        PriorityUserAgeBand.ADULT_18_PLUS -> null
        PriorityUserAgeBand.VERIFIED_14_PLUS -> null
    }

    private fun cancelPendingPracticeLocked() {
        pendingUsagePlayback = null
        pendingPractice = null
        practiceLifecycle = PriorityUserPracticeLifecycle(current.completedPractices)
    }
}
