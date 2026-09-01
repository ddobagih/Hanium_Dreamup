package kr.co.hanium.dreamup.walksafe.session

import java.security.MessageDigest
import java.util.Collections

const val FIRST_RUN_ONBOARDING_POLICY_VERSION = "FP-010-1.0.0"
const val EMAIL_ACCOUNT_ONBOARDING_POLICY_VERSION = "FP-010-EMAIL-2.0.0"

enum class FirstRunOnboardingFlow {
    LEGACY_PHONE_V3,
    EMAIL_ACCOUNT_V4,
}

enum class FirstRunOnboardingStage {
    EMAIL_OTP_ENROLLMENT,
    ACCOUNT_CREATED,
    PURPOSE_AND_SAFETY,
    AGE_AND_GUARDIAN_NEED,
    INTEGRATED_CONSENT,
    LOCAL_CREDENTIAL_PHONE_SUBMISSION,
    VERIFIED_SMS,
    GUARDIAN_APPROVAL,
    ACCOUNT_ACTIVATION,
    VERIFIED_LOGIN,
    JIT_PERMISSION_OBSERVATION,
    DEVICE_CHECK,
    FP004_TRAINING,
    COMPLETE,
    BLOCKED_UNDER_14,
}

enum class FirstRunAgeBand {
    UNDER_14,
    AGE_14_TO_17,
    ADULT_18_PLUS,
    /** Backend enrollment/authentication proved that the account is eligible for age 14+. */
    VERIFIED_14_PLUS,
}

@JvmInline
value class FirstRunReceiptHash private constructor(val value: String) {
    companion object {
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        fun fromSha256Hex(value: String): FirstRunReceiptHash {
            require(SHA256_HEX.matches(value)) {
                "A receipt must be one lowercase SHA-256 hexadecimal digest"
            }
            return FirstRunReceiptHash(value)
        }
    }
}

@JvmInline
value class FirstRunOpaqueSubmissionHandle private constructor(val value: String) {
    companion object {
        private val HANDLE = Regex("^onb_([0-9a-f]{32})$")

        /**
         * Accepts only a provider-issued 128-bit-or-stronger random handle. Providers must never
         * derive this value from a phone number, password, birth date, or other user-entered data.
         */
        fun fromProvider(value: String): FirstRunOpaqueSubmissionHandle {
            val body = HANDLE.matchEntire(value)?.groupValues?.get(1)
            require(body != null && body.any { it in 'a'..'f' }) {
                "A submission handle must be an opaque provider handle"
            }
            return FirstRunOpaqueSubmissionHandle(value)
        }
    }
}

@JvmInline
value class FirstRunOpaqueActorBinding private constructor(val value: String) {
    companion object {
        private val BINDING = Regex("^actor_([0-9a-f]{32})$")
        private val BACKEND_ACCOUNT_ID =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

        /**
         * Accepts only a provider-issued 128-bit-or-stronger random binding. Providers must never
         * derive this value from a phone number, password, birth date, or other user-entered data.
         */
        fun fromProvider(value: String): FirstRunOpaqueActorBinding {
            val body = BINDING.matchEntire(value)?.groupValues?.get(1)
            require(
                (body != null && body.any { it in 'a'..'f' }) ||
                    BACKEND_ACCOUNT_ID.matches(value),
            ) {
                "An actor binding must be an opaque verified provider binding"
            }
            return FirstRunOpaqueActorBinding(value)
        }
    }
}

sealed interface FirstRunOnboardingEvidence {
    val stage: FirstRunOnboardingStage
    val receiptHash: FirstRunReceiptHash

    data class EmailOtpEnrollment(
        val ageBand: FirstRunAgeBand,
        override val receiptHash: FirstRunReceiptHash,
    ) : FirstRunOnboardingEvidence {
        override val stage = FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT
    }

    data class AccountCreated(
        override val receiptHash: FirstRunReceiptHash,
    ) : FirstRunOnboardingEvidence {
        override val stage = FirstRunOnboardingStage.ACCOUNT_CREATED
    }

    data class PurposeAndSafety(
        override val receiptHash: FirstRunReceiptHash,
    ) : FirstRunOnboardingEvidence {
        override val stage = FirstRunOnboardingStage.PURPOSE_AND_SAFETY
    }

    data class AgeAndGuardianNeed(
        val ageBand: FirstRunAgeBand,
        override val receiptHash: FirstRunReceiptHash,
    ) : FirstRunOnboardingEvidence {
        override val stage = FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED
    }

    data class IntegratedConsent(
        override val receiptHash: FirstRunReceiptHash,
    ) : FirstRunOnboardingEvidence {
        override val stage = FirstRunOnboardingStage.INTEGRATED_CONSENT
    }

    data class LocalCredentialPhoneSubmission(
        val submissionHandle: FirstRunOpaqueSubmissionHandle,
        override val receiptHash: FirstRunReceiptHash,
    ) : FirstRunOnboardingEvidence {
        override val stage = FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION
    }

    data class VerifiedSms(
        override val receiptHash: FirstRunReceiptHash,
    ) : FirstRunOnboardingEvidence {
        override val stage = FirstRunOnboardingStage.VERIFIED_SMS
    }

    data class GuardianApproval(
        override val receiptHash: FirstRunReceiptHash,
    ) : FirstRunOnboardingEvidence {
        override val stage = FirstRunOnboardingStage.GUARDIAN_APPROVAL
    }

    data class AccountActivation(
        override val receiptHash: FirstRunReceiptHash,
    ) : FirstRunOnboardingEvidence {
        override val stage = FirstRunOnboardingStage.ACCOUNT_ACTIVATION
    }

    data class VerifiedLogin(
        val actorBinding: FirstRunOpaqueActorBinding,
        override val receiptHash: FirstRunReceiptHash,
    ) : FirstRunOnboardingEvidence {
        override val stage = FirstRunOnboardingStage.VERIFIED_LOGIN
    }

    data class JitPermissionObservation(
        override val receiptHash: FirstRunReceiptHash,
    ) : FirstRunOnboardingEvidence {
        override val stage = FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION
    }

    data class DeviceCheck(
        override val receiptHash: FirstRunReceiptHash,
    ) : FirstRunOnboardingEvidence {
        override val stage = FirstRunOnboardingStage.DEVICE_CHECK
    }

    data class Fp004Training(
        override val receiptHash: FirstRunReceiptHash,
    ) : FirstRunOnboardingEvidence {
        override val stage = FirstRunOnboardingStage.FP004_TRAINING
    }
}

data class FirstRunOnboardingAttemptRequest(
    val epoch: Long,
    val revision: Long,
    val stage: FirstRunOnboardingStage,
    val requestId: String,
    val attemptId: String,
) {
    companion object {
        fun forSnapshot(
            snapshot: FirstRunOnboardingSnapshot,
            requestId: String,
            attemptId: String,
        ) = FirstRunOnboardingAttemptRequest(
            epoch = snapshot.epoch,
            revision = snapshot.revision,
            stage = snapshot.stage,
            requestId = requestId,
            attemptId = attemptId,
        )
    }
}

class FirstRunOnboardingAttemptToken internal constructor(
    val epoch: Long,
    val revision: Long,
    val stage: FirstRunOnboardingStage,
    val requestId: String,
    val attemptId: String,
)

@ConsistentCopyVisibility
data class FirstRunOnboardingSnapshot internal constructor(
    val policyVersion: String,
    val flow: FirstRunOnboardingFlow,
    val epoch: Long,
    val revision: Long,
    val stage: FirstRunOnboardingStage,
    val ageBand: FirstRunAgeBand?,
    val completedReceiptHashes: Map<FirstRunOnboardingStage, FirstRunReceiptHash>,
    val localCredentialPhoneSubmissionHandle: FirstRunOpaqueSubmissionHandle?,
    val verifiedActorBinding: FirstRunOpaqueActorBinding?,
    val pendingAttempt: FirstRunOnboardingAttemptToken?,
) {
    init {
        require(
            policyVersion == when (flow) {
                FirstRunOnboardingFlow.LEGACY_PHONE_V3 ->
                    FIRST_RUN_ONBOARDING_POLICY_VERSION
                FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 ->
                    EMAIL_ACCOUNT_ONBOARDING_POLICY_VERSION
            },
        )
        require(epoch > 0L) { "epoch must be positive" }
        require(revision >= 0L) { "revision must not be negative" }
        val expectedReceipts = expectedCompletedStages(
            flow = flow,
            stage = stage,
            ageBand = ageBand,
            actual = completedReceiptHashes.keys,
        )
        require(
            expectedReceipts != null &&
                completedReceiptHashes.keys == expectedReceipts,
        ) {
            "Receipts must be one valid ordered stage prefix"
        }
        require(stage !in TERMINAL_STAGES || pendingAttempt == null) {
            "A terminal snapshot cannot retain an attempt"
        }
        require(
            if (flow == FirstRunOnboardingFlow.LEGACY_PHONE_V3) {
                (localCredentialPhoneSubmissionHandle != null) ==
                    (FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION in
                        completedReceiptHashes)
            } else {
                localCredentialPhoneSubmissionHandle == null
            },
        ) {
            "A submission handle and its completed receipt must stay paired"
        }
        require(
            (verifiedActorBinding != null) ==
                (FirstRunOnboardingStage.VERIFIED_LOGIN in completedReceiptHashes),
        ) {
            "A verified login receipt and its opaque actor binding must stay paired"
        }
        require(
            pendingAttempt == null ||
                (
                    pendingAttempt.epoch == epoch &&
                        pendingAttempt.revision == revision &&
                        pendingAttempt.stage == stage &&
                        stage !in LOCAL_STAGES
                    ),
        ) {
            "A pending attempt must be bound to the current remote stage"
        }
    }

    val isComplete: Boolean
        get() = stage == FirstRunOnboardingStage.COMPLETE

    val isTerminal: Boolean
        get() = stage in TERMINAL_STAGES

    val mayEnterWalk: Boolean
        get() = isComplete

    val guardianApprovalRequired: Boolean
        get() = flow == FirstRunOnboardingFlow.LEGACY_PHONE_V3 &&
            ageBand == FirstRunAgeBand.AGE_14_TO_17

    val hasVerifiedActorBinding: Boolean
        get() = verifiedActorBinding != null

    val reporterActorBinding: FirstRunOpaqueActorBinding?
        get() = verifiedActorBinding.takeIf { isComplete }

    private companion object {
        val TERMINAL_STAGES = setOf(
            FirstRunOnboardingStage.COMPLETE,
            FirstRunOnboardingStage.BLOCKED_UNDER_14,
        )
        val LOCAL_STAGES = setOf(
            FirstRunOnboardingStage.PURPOSE_AND_SAFETY,
            FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED,
        )

        fun expectedCompletedStages(
            flow: FirstRunOnboardingFlow,
            stage: FirstRunOnboardingStage,
            ageBand: FirstRunAgeBand?,
            actual: Set<FirstRunOnboardingStage>,
        ): Set<FirstRunOnboardingStage>? = when (flow) {
            FirstRunOnboardingFlow.LEGACY_PHONE_V3 ->
                expectedLegacyCompletedStages(stage, ageBand)
            FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 ->
                expectedEmailCompletedStages(stage, ageBand, actual)
        }

        private fun expectedEmailCompletedStages(
            stage: FirstRunOnboardingStage,
            ageBand: FirstRunAgeBand?,
            actual: Set<FirstRunOnboardingStage>,
        ): Set<FirstRunOnboardingStage>? {
            if (stage == FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT) {
                return emptySet<FirstRunOnboardingStage>().takeIf { ageBand == null }
            }
            if (ageBand != FirstRunAgeBand.VERIFIED_14_PLUS) return null
            val signupPrefix = listOf(
                FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT,
                FirstRunOnboardingStage.ACCOUNT_CREATED,
                FirstRunOnboardingStage.VERIFIED_LOGIN,
            )
            if (stage == FirstRunOnboardingStage.ACCOUNT_CREATED) {
                return signupPrefix.take(1).toSet()
            }
            if (stage == FirstRunOnboardingStage.VERIFIED_LOGIN) {
                return signupPrefix.take(2).toSet()
            }
            val completedPostLogin = when (stage) {
                FirstRunOnboardingStage.PURPOSE_AND_SAFETY -> emptySet()
                FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION,
                FirstRunOnboardingStage.DEVICE_CHECK,
                FirstRunOnboardingStage.FP004_TRAINING,
                -> setOf(FirstRunOnboardingStage.PURPOSE_AND_SAFETY)
                FirstRunOnboardingStage.COMPLETE ->
                    setOf(
                        FirstRunOnboardingStage.PURPOSE_AND_SAFETY,
                        FirstRunOnboardingStage.FP004_TRAINING,
                    )
                else -> return null
            }
            val signupCompleted = signupPrefix.toSet() + completedPostLogin
            val loginPrefix = setOf(FirstRunOnboardingStage.VERIFIED_LOGIN) + completedPostLogin
            return actual.takeIf { it == signupCompleted || it == loginPrefix }
        }

        private fun expectedLegacyCompletedStages(
            stage: FirstRunOnboardingStage,
            ageBand: FirstRunAgeBand?,
        ): Set<FirstRunOnboardingStage>? {
            if (stage == FirstRunOnboardingStage.PURPOSE_AND_SAFETY) {
                return emptySet<FirstRunOnboardingStage>().takeIf { ageBand == null }
            }
            if (stage == FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED) {
                return setOf(FirstRunOnboardingStage.PURPOSE_AND_SAFETY)
                    .takeIf { ageBand == null }
            }
            if (stage == FirstRunOnboardingStage.BLOCKED_UNDER_14) {
                return setOf(
                    FirstRunOnboardingStage.PURPOSE_AND_SAFETY,
                    FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED,
                ).takeIf { ageBand == FirstRunAgeBand.UNDER_14 }
            }
            if (ageBand !in setOf(
                    FirstRunAgeBand.AGE_14_TO_17,
                    FirstRunAgeBand.ADULT_18_PLUS,
                )
            ) {
                return null
            }
            val path = buildList {
                add(FirstRunOnboardingStage.PURPOSE_AND_SAFETY)
                add(FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED)
                add(FirstRunOnboardingStage.INTEGRATED_CONSENT)
                add(FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION)
                add(FirstRunOnboardingStage.VERIFIED_SMS)
                if (ageBand == FirstRunAgeBand.AGE_14_TO_17) {
                    add(FirstRunOnboardingStage.GUARDIAN_APPROVAL)
                }
                add(FirstRunOnboardingStage.ACCOUNT_ACTIVATION)
                add(FirstRunOnboardingStage.VERIFIED_LOGIN)
                add(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION)
                add(FirstRunOnboardingStage.DEVICE_CHECK)
                add(FirstRunOnboardingStage.FP004_TRAINING)
            }
            if (stage == FirstRunOnboardingStage.COMPLETE) return path.toSet()
            val stageIndex = path.indexOf(stage)
            if (stageIndex < 0) return null
            return path.take(stageIndex).toSet()
        }
    }
}

enum class FirstRunOnboardingRejection {
    TERMINAL_STATE,
    STALE_STATE,
    STAGE_MISMATCH,
    ATTEMPT_ALREADY_PENDING,
    INVALID_IDENTITY,
    NO_PENDING_ATTEMPT,
    ATTEMPT_MISMATCH,
    REMOTE_EVIDENCE_REJECTED,
    TRAINING_RESTART_NOT_ALLOWED,
    COUNTER_EXHAUSTED,
}

data class FirstRunOnboardingTransition(
    val previous: FirstRunOnboardingSnapshot,
    val current: FirstRunOnboardingSnapshot,
    val accepted: Boolean,
    val rejection: FirstRunOnboardingRejection?,
)

data class FirstRunOnboardingRestoreResult(
    val snapshot: FirstRunOnboardingSnapshot,
    val restoredEvidenceCount: Int,
    val rejection: FirstRunOnboardingRejection?,
) {
    val fullyRestored: Boolean
        get() = rejection == null
}

fun interface FirstRunOnboardingEvidenceVerifier {
    fun verify(
        token: FirstRunOnboardingAttemptToken,
        evidence: FirstRunOnboardingEvidence,
    ): Boolean
}

/**
 * Pure FP-010 first-run reducer. It accepts no password, SMS code, phone number, exact birth
 * date, or disability field. Persisted evidence is limited to opaque provider handles and receipt
 * hashes. Remote evidence remains fail-closed until a production verifier is configured.
 */
object FirstRunOnboardingPolicy {
    const val productionEvidenceAvailable: Boolean = false

    val productionEvidenceVerifier = FirstRunOnboardingEvidenceVerifier { _, _ -> false }

    private val requestIdPattern = Regex("^req_[0-9a-f]{64}$")
    private val attemptIdPattern = Regex("^att_[0-9a-f]{64}$")

    fun initial(epoch: Long = 1L): FirstRunOnboardingSnapshot {
        require(epoch > 0L) { "epoch must be positive" }
        return FirstRunOnboardingSnapshot(
            policyVersion = FIRST_RUN_ONBOARDING_POLICY_VERSION,
            flow = FirstRunOnboardingFlow.LEGACY_PHONE_V3,
            epoch = epoch,
            revision = 0L,
            stage = FirstRunOnboardingStage.PURPOSE_AND_SAFETY,
            ageBand = null,
            completedReceiptHashes = immutableReceiptHashes(emptyMap()),
            localCredentialPhoneSubmissionHandle = null,
            verifiedActorBinding = null,
            pendingAttempt = null,
        )
    }

    fun initialEmailAccount(epoch: Long = 1L): FirstRunOnboardingSnapshot {
        require(epoch > 0L) { "epoch must be positive" }
        return FirstRunOnboardingSnapshot(
            policyVersion = EMAIL_ACCOUNT_ONBOARDING_POLICY_VERSION,
            flow = FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4,
            epoch = epoch,
            revision = 0L,
            stage = FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT,
            ageBand = null,
            completedReceiptHashes = immutableReceiptHashes(emptyMap()),
            localCredentialPhoneSubmissionHandle = null,
            verifiedActorBinding = null,
            pendingAttempt = null,
        )
    }

    fun recordEmailOtpEnrollment(
        snapshot: FirstRunOnboardingSnapshot,
        receiptHash: FirstRunReceiptHash,
    ): FirstRunOnboardingTransition {
        if (
            snapshot.flow != FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 ||
            snapshot.stage != FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT ||
            snapshot.pendingAttempt != null
        ) return rejected(snapshot, FirstRunOnboardingRejection.STAGE_MISMATCH)
        if (snapshot.revision == Long.MAX_VALUE) {
            return rejected(snapshot, FirstRunOnboardingRejection.COUNTER_EXHAUSTED)
        }
        return accepted(
            snapshot,
            advance(
                snapshot,
                FirstRunOnboardingEvidence.EmailOtpEnrollment(
                    ageBand = FirstRunAgeBand.VERIFIED_14_PLUS,
                    receiptHash = receiptHash,
                ),
            ),
        )
    }

    fun recordEmailAccountCreated(
        snapshot: FirstRunOnboardingSnapshot,
        receiptHash: FirstRunReceiptHash,
    ): FirstRunOnboardingTransition {
        if (
            snapshot.flow != FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 ||
            snapshot.stage != FirstRunOnboardingStage.ACCOUNT_CREATED ||
            snapshot.pendingAttempt != null
        ) return rejected(snapshot, FirstRunOnboardingRejection.STAGE_MISMATCH)
        if (snapshot.revision == Long.MAX_VALUE) {
            return rejected(snapshot, FirstRunOnboardingRejection.COUNTER_EXHAUSTED)
        }
        return accepted(
            snapshot,
            advance(snapshot, FirstRunOnboardingEvidence.AccountCreated(receiptHash)),
        )
    }

    /**
     * A verified password session is valid either after account creation or as returning-user
     * login. The returning-user path intentionally records no synthetic email-OTP/account-create
     * evidence. Both paths require an explicit purpose-and-safety acknowledgement before JIT
     * permission observation.
     */
    fun recordVerifiedEmailLogin(
        snapshot: FirstRunOnboardingSnapshot,
        actorBinding: FirstRunOpaqueActorBinding,
        receiptHash: FirstRunReceiptHash,
    ): FirstRunOnboardingTransition {
        if (
            snapshot.flow != FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 ||
            snapshot.stage !in setOf(
                FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT,
                FirstRunOnboardingStage.VERIFIED_LOGIN,
            ) ||
            snapshot.pendingAttempt != null
        ) return rejected(snapshot, FirstRunOnboardingRejection.STAGE_MISMATCH)
        if (snapshot.revision == Long.MAX_VALUE) {
            return rejected(snapshot, FirstRunOnboardingRejection.COUNTER_EXHAUSTED)
        }
        val receipts = immutableReceiptHashes(
            snapshot.completedReceiptHashes +
                (FirstRunOnboardingStage.VERIFIED_LOGIN to receiptHash),
        )
        return accepted(
            snapshot,
            snapshot.copy(
                revision = snapshot.revision + 1L,
                stage = FirstRunOnboardingStage.PURPOSE_AND_SAFETY,
                ageBand = FirstRunAgeBand.VERIFIED_14_PLUS,
                completedReceiptHashes = receipts,
                verifiedActorBinding = actorBinding,
                pendingAttempt = null,
            ),
        )
    }

    fun mayReauthenticateVerifiedEmailActor(
        snapshot: FirstRunOnboardingSnapshot,
        actorBinding: FirstRunOpaqueActorBinding,
    ): Boolean =
        snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 &&
            snapshot.stage in setOf(
                FirstRunOnboardingStage.PURPOSE_AND_SAFETY,
                FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION,
                FirstRunOnboardingStage.DEVICE_CHECK,
                FirstRunOnboardingStage.FP004_TRAINING,
                FirstRunOnboardingStage.COMPLETE,
            ) &&
            snapshot.pendingAttempt == null &&
            snapshot.verifiedActorBinding == actorBinding

    fun recordEmailJitPermissionObservation(
        snapshot: FirstRunOnboardingSnapshot,
        expectedEpoch: Long,
        expectedRevision: Long,
    ): FirstRunOnboardingTransition = advanceEmailLocalPostLoginStage(
        snapshot = snapshot,
        expectedEpoch = expectedEpoch,
        expectedRevision = expectedRevision,
        expectedStage = FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION,
        nextStage = FirstRunOnboardingStage.DEVICE_CHECK,
    )

    fun recordEmailDeviceCheckPassed(
        snapshot: FirstRunOnboardingSnapshot,
        expectedEpoch: Long,
        expectedRevision: Long,
    ): FirstRunOnboardingTransition = advanceEmailLocalPostLoginStage(
        snapshot = snapshot,
        expectedEpoch = expectedEpoch,
        expectedRevision = expectedRevision,
        expectedStage = FirstRunOnboardingStage.DEVICE_CHECK,
        nextStage = FirstRunOnboardingStage.FP004_TRAINING,
    )

    fun acknowledgePurposeAndSafety(
        snapshot: FirstRunOnboardingSnapshot,
        request: FirstRunOnboardingAttemptRequest,
        receiptHash: FirstRunReceiptHash,
    ): FirstRunOnboardingTransition = applyLocalEvidence(
        snapshot,
        request,
        FirstRunOnboardingEvidence.PurposeAndSafety(receiptHash),
    )

    fun recordAgeAndGuardianNeed(
        snapshot: FirstRunOnboardingSnapshot,
        request: FirstRunOnboardingAttemptRequest,
        ageBand: FirstRunAgeBand,
        receiptHash: FirstRunReceiptHash,
    ): FirstRunOnboardingTransition = applyLocalEvidence(
        snapshot,
        request,
        FirstRunOnboardingEvidence.AgeAndGuardianNeed(ageBand, receiptHash),
    )

    /**
     * Rebuilds only the verified ordered receipt prefix. It never accepts a pending token or a
     * caller-provided completion flag, and it returns the last verified snapshot on any failure.
     */
    fun restoreVerifiedReceiptPrefix(
        epoch: Long,
        orderedEvidence: List<FirstRunOnboardingEvidence>,
        verifier: FirstRunOnboardingEvidenceVerifier = productionEvidenceVerifier,
    ): FirstRunOnboardingRestoreResult {
        var snapshot = initial(epoch)
        orderedEvidence.forEachIndexed { index, evidence ->
            if (snapshot.isTerminal) {
                return restoreStopped(
                    snapshot,
                    index,
                    FirstRunOnboardingRejection.TERMINAL_STATE,
                )
            }
            if (evidence.stage != snapshot.stage) {
                return restoreStopped(
                    snapshot,
                    index,
                    FirstRunOnboardingRejection.STAGE_MISMATCH,
                )
            }
            if (snapshot.revision == Long.MAX_VALUE) {
                return restoreStopped(
                    snapshot,
                    index,
                    FirstRunOnboardingRejection.COUNTER_EXHAUSTED,
                )
            }
            if (snapshot.stage !in LOCAL_STAGES) {
                val token = FirstRunOnboardingAttemptToken(
                    epoch = snapshot.epoch,
                    revision = snapshot.revision,
                    stage = snapshot.stage,
                    requestId = "req_${restoreIdentity("request", snapshot, index)}",
                    attemptId = "att_${restoreIdentity("attempt", snapshot, index)}",
                )
                if (
                    !runCatching { verifier.verify(token, evidence) }
                        .getOrDefault(false)
                ) {
                    return restoreStopped(
                        snapshot,
                        index,
                        FirstRunOnboardingRejection.REMOTE_EVIDENCE_REJECTED,
                    )
                }
            }
            snapshot = advance(snapshot, evidence)
        }
        return FirstRunOnboardingRestoreResult(
            snapshot = snapshot,
            restoredEvidenceCount = orderedEvidence.size,
            rejection = null,
        )
    }

    fun restoreVerifiedEmailReceiptPrefix(
        epoch: Long,
        orderedEvidence: List<FirstRunOnboardingEvidence>,
        verifier: FirstRunOnboardingEvidenceVerifier = productionEvidenceVerifier,
    ): FirstRunOnboardingRestoreResult {
        var snapshot = initialEmailAccount(epoch)
        orderedEvidence.forEachIndexed { index, evidence ->
            if (snapshot.isTerminal) {
                return restoreStopped(
                    snapshot,
                    index,
                    FirstRunOnboardingRejection.TERMINAL_STATE,
                )
            }
            if (
                snapshot.stage == FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION &&
                evidence is FirstRunOnboardingEvidence.Fp004Training
            ) {
                snapshot = recordEmailJitPermissionObservation(
                    snapshot,
                    snapshot.epoch,
                    snapshot.revision,
                ).current
                snapshot = recordEmailDeviceCheckPassed(
                    snapshot,
                    snapshot.epoch,
                    snapshot.revision,
                ).current
            }
            val returningLogin =
                snapshot.stage == FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT &&
                    evidence is FirstRunOnboardingEvidence.VerifiedLogin
            if (!returningLogin && evidence.stage != snapshot.stage) {
                return restoreStopped(
                    snapshot,
                    index,
                    FirstRunOnboardingRejection.STAGE_MISMATCH,
                )
            }
            if (snapshot.revision == Long.MAX_VALUE) {
                return restoreStopped(
                    snapshot,
                    index,
                    FirstRunOnboardingRejection.COUNTER_EXHAUSTED,
                )
            }
            val token = FirstRunOnboardingAttemptToken(
                epoch = snapshot.epoch,
                revision = snapshot.revision,
                stage = evidence.stage,
                requestId = "req_${restoreIdentity("email-request", snapshot, index)}",
                attemptId = "att_${restoreIdentity("email-attempt", snapshot, index)}",
            )
            if (!runCatching { verifier.verify(token, evidence) }.getOrDefault(false)) {
                return restoreStopped(
                    snapshot,
                    index,
                    FirstRunOnboardingRejection.REMOTE_EVIDENCE_REJECTED,
                )
            }
            snapshot = when {
                returningLogin -> {
                    val login = evidence as FirstRunOnboardingEvidence.VerifiedLogin
                    recordVerifiedEmailLogin(
                        snapshot,
                        login.actorBinding,
                        login.receiptHash,
                    ).current
                }
                evidence is FirstRunOnboardingEvidence.JitPermissionObservation ->
                    recordEmailJitPermissionObservation(
                        snapshot,
                        snapshot.epoch,
                        snapshot.revision,
                    ).current
                evidence is FirstRunOnboardingEvidence.DeviceCheck ->
                    recordEmailDeviceCheckPassed(
                        snapshot,
                        snapshot.epoch,
                        snapshot.revision,
                    ).current
                else -> advance(snapshot, evidence)
            }
        }
        return FirstRunOnboardingRestoreResult(
            snapshot = snapshot,
            restoredEvidenceCount = orderedEvidence.size,
            rejection = null,
        )
    }

    fun restartFp004Training(
        snapshot: FirstRunOnboardingSnapshot,
    ): FirstRunOnboardingTransition {
        if (!snapshot.isComplete) {
            return rejected(
                snapshot,
                FirstRunOnboardingRejection.TRAINING_RESTART_NOT_ALLOWED,
            )
        }
        if (snapshot.epoch == Long.MAX_VALUE || snapshot.revision == Long.MAX_VALUE) {
            return rejected(snapshot, FirstRunOnboardingRejection.COUNTER_EXHAUSTED)
        }
        return accepted(
            snapshot,
            snapshot.copy(
                epoch = snapshot.epoch + 1L,
                revision = snapshot.revision + 1L,
                stage = FirstRunOnboardingStage.FP004_TRAINING,
                completedReceiptHashes =
                    immutableReceiptHashes(
                        snapshot.completedReceiptHashes -
                            FirstRunOnboardingStage.FP004_TRAINING,
                    ),
                pendingAttempt = null,
            ),
        )
    }

    fun beginAttempt(
        snapshot: FirstRunOnboardingSnapshot,
        request: FirstRunOnboardingAttemptRequest,
    ): FirstRunOnboardingTransition {
        validateRequest(snapshot, request)?.let { return rejected(snapshot, it) }
        if (snapshot.stage in LOCAL_STAGES || snapshot.isEmailLocalPostLoginStage()) {
            return rejected(snapshot, FirstRunOnboardingRejection.STAGE_MISMATCH)
        }
        if (snapshot.revision == Long.MAX_VALUE) {
            return rejected(snapshot, FirstRunOnboardingRejection.COUNTER_EXHAUSTED)
        }
        val token = FirstRunOnboardingAttemptToken(
            epoch = snapshot.epoch,
            revision = snapshot.revision + 1L,
            stage = snapshot.stage,
            requestId = request.requestId,
            attemptId = request.attemptId,
        )
        return accepted(snapshot, snapshot.copy(revision = token.revision, pendingAttempt = token))
    }

    fun completeAttempt(
        snapshot: FirstRunOnboardingSnapshot,
        token: FirstRunOnboardingAttemptToken,
        evidence: FirstRunOnboardingEvidence,
        verifier: FirstRunOnboardingEvidenceVerifier = productionEvidenceVerifier,
    ): FirstRunOnboardingTransition {
        if (snapshot.isTerminal) {
            return rejected(snapshot, FirstRunOnboardingRejection.TERMINAL_STATE)
        }
        if (snapshot.isEmailLocalPostLoginStage()) {
            return rejected(snapshot, FirstRunOnboardingRejection.STAGE_MISMATCH)
        }
        val pending = snapshot.pendingAttempt
            ?: return rejected(snapshot, FirstRunOnboardingRejection.NO_PENDING_ATTEMPT)
        if (
            pending != token ||
            token.epoch != snapshot.epoch ||
            token.revision != snapshot.revision
        ) {
            return rejected(snapshot, FirstRunOnboardingRejection.ATTEMPT_MISMATCH)
        }
        if (token.stage != snapshot.stage || evidence.stage != snapshot.stage) {
            return rejected(snapshot, FirstRunOnboardingRejection.STAGE_MISMATCH)
        }
        if (
            !runCatching { verifier.verify(token, evidence) }
                .getOrDefault(false)
        ) {
            return rejected(snapshot, FirstRunOnboardingRejection.REMOTE_EVIDENCE_REJECTED)
        }
        if (snapshot.revision == Long.MAX_VALUE) {
            return rejected(snapshot, FirstRunOnboardingRejection.COUNTER_EXHAUSTED)
        }
        return accepted(snapshot, advance(snapshot, evidence))
    }

    fun cancelAttempt(
        snapshot: FirstRunOnboardingSnapshot,
        token: FirstRunOnboardingAttemptToken,
    ): FirstRunOnboardingTransition {
        val pending = snapshot.pendingAttempt
            ?: return rejected(snapshot, FirstRunOnboardingRejection.NO_PENDING_ATTEMPT)
        if (
            pending != token ||
            token.epoch != snapshot.epoch ||
            token.revision != snapshot.revision
        ) {
            return rejected(snapshot, FirstRunOnboardingRejection.ATTEMPT_MISMATCH)
        }
        if (snapshot.epoch == Long.MAX_VALUE || snapshot.revision == Long.MAX_VALUE) {
            return rejected(snapshot, FirstRunOnboardingRejection.COUNTER_EXHAUSTED)
        }
        return accepted(
            snapshot,
            snapshot.copy(
                epoch = snapshot.epoch + 1L,
                revision = snapshot.revision + 1L,
                pendingAttempt = null,
            ),
        )
    }

    private fun applyLocalEvidence(
        snapshot: FirstRunOnboardingSnapshot,
        request: FirstRunOnboardingAttemptRequest,
        evidence: FirstRunOnboardingEvidence,
    ): FirstRunOnboardingTransition {
        validateRequest(snapshot, request)?.let { return rejected(snapshot, it) }
        if (evidence.stage != snapshot.stage || snapshot.stage !in LOCAL_STAGES) {
            return rejected(snapshot, FirstRunOnboardingRejection.STAGE_MISMATCH)
        }
        if (snapshot.revision == Long.MAX_VALUE) {
            return rejected(snapshot, FirstRunOnboardingRejection.COUNTER_EXHAUSTED)
        }
        return accepted(snapshot, advance(snapshot, evidence))
    }

    private fun advanceEmailLocalPostLoginStage(
        snapshot: FirstRunOnboardingSnapshot,
        expectedEpoch: Long,
        expectedRevision: Long,
        expectedStage: FirstRunOnboardingStage,
        nextStage: FirstRunOnboardingStage,
    ): FirstRunOnboardingTransition {
        if (
            snapshot.flow != FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 ||
            snapshot.stage != expectedStage ||
            snapshot.pendingAttempt != null
        ) {
            return rejected(snapshot, FirstRunOnboardingRejection.STAGE_MISMATCH)
        }
        if (snapshot.epoch != expectedEpoch || snapshot.revision != expectedRevision) {
            return rejected(snapshot, FirstRunOnboardingRejection.STALE_STATE)
        }
        if (snapshot.revision == Long.MAX_VALUE) {
            return rejected(snapshot, FirstRunOnboardingRejection.COUNTER_EXHAUSTED)
        }
        return accepted(
            snapshot,
            snapshot.copy(
                revision = snapshot.revision + 1L,
                stage = nextStage,
                pendingAttempt = null,
            ),
        )
    }

    private fun validateRequest(
        snapshot: FirstRunOnboardingSnapshot,
        request: FirstRunOnboardingAttemptRequest,
    ): FirstRunOnboardingRejection? = when {
        snapshot.isTerminal -> FirstRunOnboardingRejection.TERMINAL_STATE
        snapshot.pendingAttempt != null -> FirstRunOnboardingRejection.ATTEMPT_ALREADY_PENDING
        request.epoch != snapshot.epoch || request.revision != snapshot.revision ->
            FirstRunOnboardingRejection.STALE_STATE
        request.stage != snapshot.stage -> FirstRunOnboardingRejection.STAGE_MISMATCH
        !requestIdPattern.matches(request.requestId) ||
            !attemptIdPattern.matches(request.attemptId) ->
            FirstRunOnboardingRejection.INVALID_IDENTITY
        else -> null
    }

    private fun advance(
        snapshot: FirstRunOnboardingSnapshot,
        evidence: FirstRunOnboardingEvidence,
    ): FirstRunOnboardingSnapshot {
        val ageBand = when (evidence) {
            is FirstRunOnboardingEvidence.AgeAndGuardianNeed -> evidence.ageBand
            is FirstRunOnboardingEvidence.EmailOtpEnrollment -> evidence.ageBand
            else -> snapshot.ageBand
        }
        val nextStage = when (snapshot.stage) {
            FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT ->
                FirstRunOnboardingStage.ACCOUNT_CREATED
            FirstRunOnboardingStage.ACCOUNT_CREATED ->
                FirstRunOnboardingStage.VERIFIED_LOGIN
            FirstRunOnboardingStage.PURPOSE_AND_SAFETY ->
                if (snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4) {
                    FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION
                } else {
                    FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED
                }
            FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED ->
                if (ageBand == FirstRunAgeBand.UNDER_14) {
                    FirstRunOnboardingStage.BLOCKED_UNDER_14
                } else {
                    FirstRunOnboardingStage.INTEGRATED_CONSENT
                }
            FirstRunOnboardingStage.INTEGRATED_CONSENT ->
                FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION
            FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION ->
                FirstRunOnboardingStage.VERIFIED_SMS
            FirstRunOnboardingStage.VERIFIED_SMS ->
                if (ageBand == FirstRunAgeBand.AGE_14_TO_17) {
                    FirstRunOnboardingStage.GUARDIAN_APPROVAL
                } else {
                    FirstRunOnboardingStage.ACCOUNT_ACTIVATION
                }
            FirstRunOnboardingStage.GUARDIAN_APPROVAL ->
                FirstRunOnboardingStage.ACCOUNT_ACTIVATION
            FirstRunOnboardingStage.ACCOUNT_ACTIVATION ->
                FirstRunOnboardingStage.VERIFIED_LOGIN
            FirstRunOnboardingStage.VERIFIED_LOGIN ->
                FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION
            FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION ->
                FirstRunOnboardingStage.DEVICE_CHECK
            FirstRunOnboardingStage.DEVICE_CHECK ->
                FirstRunOnboardingStage.FP004_TRAINING
            FirstRunOnboardingStage.FP004_TRAINING ->
                FirstRunOnboardingStage.COMPLETE
            FirstRunOnboardingStage.COMPLETE,
            FirstRunOnboardingStage.BLOCKED_UNDER_14,
            -> error("A terminal snapshot cannot advance")
        }
        val submissionHandle = when (evidence) {
            is FirstRunOnboardingEvidence.LocalCredentialPhoneSubmission ->
                evidence.submissionHandle
            else -> snapshot.localCredentialPhoneSubmissionHandle
        }
        val actorBinding = when (evidence) {
            is FirstRunOnboardingEvidence.VerifiedLogin -> evidence.actorBinding
            else -> snapshot.verifiedActorBinding
        }
        return snapshot.copy(
            revision = snapshot.revision + 1L,
            stage = nextStage,
            ageBand = ageBand,
            completedReceiptHashes =
                immutableReceiptHashes(
                    snapshot.completedReceiptHashes + (snapshot.stage to evidence.receiptHash),
                ),
            localCredentialPhoneSubmissionHandle = submissionHandle,
            verifiedActorBinding = actorBinding,
            pendingAttempt = null,
        )
    }

    private fun accepted(
        previous: FirstRunOnboardingSnapshot,
        current: FirstRunOnboardingSnapshot,
    ) = FirstRunOnboardingTransition(
        previous = previous,
        current = current,
        accepted = true,
        rejection = null,
    )

    private fun rejected(
        snapshot: FirstRunOnboardingSnapshot,
        reason: FirstRunOnboardingRejection,
    ) = FirstRunOnboardingTransition(
        previous = snapshot,
        current = snapshot,
        accepted = false,
        rejection = reason,
    )

    private fun restoreStopped(
        snapshot: FirstRunOnboardingSnapshot,
        restoredEvidenceCount: Int,
        reason: FirstRunOnboardingRejection,
    ) = FirstRunOnboardingRestoreResult(
        snapshot = snapshot,
        restoredEvidenceCount = restoredEvidenceCount,
        rejection = reason,
    )

    private val LOCAL_STAGES = setOf(
        FirstRunOnboardingStage.PURPOSE_AND_SAFETY,
        FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED,
    )

    private fun FirstRunOnboardingSnapshot.isEmailLocalPostLoginStage(): Boolean =
        flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 &&
            stage in setOf(
                FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION,
                FirstRunOnboardingStage.DEVICE_CHECK,
            )

    private fun restoreIdentity(
        kind: String,
        snapshot: FirstRunOnboardingSnapshot,
        index: Int,
    ): String = sha256Hex(
        "FP010|restore|$kind|${snapshot.epoch}|${snapshot.revision}|" +
            "${snapshot.stage.name}|$index",
    )
}

private fun immutableReceiptHashes(
    source: Map<FirstRunOnboardingStage, FirstRunReceiptHash>,
): Map<FirstRunOnboardingStage, FirstRunReceiptHash> =
    Collections.unmodifiableMap(LinkedHashMap(source))

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    val alphabet = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(alphabet[unsigned ushr 4])
            append(alphabet[unsigned and 0x0f])
        }
    }
}
