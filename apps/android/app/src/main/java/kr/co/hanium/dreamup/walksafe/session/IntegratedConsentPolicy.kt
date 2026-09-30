package kr.co.hanium.dreamup.walksafe.session

import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Collections

/**
 * Root decision R3 (2026-09-29, sweep/owner-decisions-20260929.md): RAW and automatic reports are
 * sent after the walk ends (owner decision 3(b)), a substantive change of both disclosures, so those
 * items and the document are 1.2.0; mobile-network and training texts are unchanged and keep
 * theirs. A stored earlier confirmation is migrated to a denied reconsent at startup (nothing is
 * deleted); data kept under it is not sent until the user consents to this version.
 */
const val INTEGRATED_CONSENT_POLICY_VERSION = "FP-013-1.2.0"
const val PREVIOUS_INTEGRATED_CONSENT_POLICY_VERSION = "FP-013-1.1.0"
/** Every earlier published document version the startup migration recognizes (others fail closed). */
val KNOWN_PREVIOUS_INTEGRATED_CONSENT_POLICY_VERSIONS: Set<String> =
    setOf("FP-013-1.0.0", PREVIOUS_INTEGRATED_CONSENT_POLICY_VERSION)
const val INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION =
    "walksafe.integrated-consent-confirmation.v2"
const val INTEGRATED_CONSENT_RAW_ITEM_VERSION = "FP-013-RAW-1.2.0"
const val INTEGRATED_CONSENT_AUTOMATIC_ITEM_VERSION = "FP-013-AUTO-1.2.0"
const val INTEGRATED_CONSENT_MOBILE_ITEM_VERSION = "FP-013-MOBILE-1.0.0"
const val INTEGRATED_CONSENT_TRAINING_ITEM_VERSION = "FP-013-TRAINING-1.1.0"

const val INTEGRATED_CONSENT_DISCLOSURE_KO =
    "통합 동의 문서 $INTEGRATED_CONSENT_POLICY_VERSION. 네 항목을 각각 허용하거나 거부할 수 있습니다. " +
        "1. 원본·진단수집 raw v2: 탐지·성능 메타데이터와 chunk 시각·크기·hash만 수집하며, 영상·음성·이미지·" +
        "정확한 위치·이동경로·개별 frame·bbox는 제외합니다. 기기에 최대 30일 암호화 저장하고 보행을 끝낸 " +
        "뒤 Wi-Fi에 연결되어 충전 중일 때만 원래 보행 기록 그대로 전송하며(보행 중·일시정지 중에는 전송하지 " +
        "않음), 서버 검역은 receipt commit부터 최대 14일입니다. " +
        "거부해도 신고 사진과 정확한 위치를 건별 확인하는 직접 신고에는 영향이 없습니다. " +
        "2. 자동신고: JPEG 신고 사진과 정확한 위치·방향·탐지 metadata를 보행 중에는 보내지 않고 기기에 암호화해 " +
        "보관했다가, 보행을 끝낸 뒤 동의와 허용된 연결을 다시 확인해 자동 전송합니다. 후보별 취소는 없으며 " +
        "설정에서 앞으로의 자동신고를 끌 수 있습니다. " +
        "3. 이동통신 전송: Wi-Fi가 아닐 때 같은 서버 자료를 이동통신망으로 전송합니다. 거부하면 Wi-Fi만 사용합니다. " +
        "4. 학습 재사용: 사람의 승인과 비식별 처리를 통과한 정제 이미지·라벨·metadata만 dataset 승인일부터 " +
        "최대 3년간 모델 개선 후보로 사용하며, 정확한 위치·원본 음성·식별 가능한 얼굴은 제외합니다. " +
        "거부하면 신고 처리 외 학습에 쓰지 않습니다. " +
        "각 항목은 언제든 이 화면에서 철회할 수 있고, 철회 즉시 해당 경로를 중단한 뒤 서버에 새 선택을 저장합니다."

enum class IntegratedConsentItem(val wireValue: String) {
    RAW_SOURCE_COLLECTION("raw_source_collection"),
    AUTOMATIC_REPORTING("automatic_reporting"),
    MOBILE_NETWORK_TRANSFER("mobile_network_transfer"),
    TRAINING_REUSE("training_reuse"),
}

data class IntegratedConsentSelections(
    val rawSourceCollection: Boolean = false,
    val automaticReporting: Boolean = false,
    val mobileNetworkTransfer: Boolean = false,
    val trainingReuse: Boolean = false,
) {
    fun isGranted(item: IntegratedConsentItem): Boolean = when (item) {
        IntegratedConsentItem.RAW_SOURCE_COLLECTION -> rawSourceCollection
        IntegratedConsentItem.AUTOMATIC_REPORTING -> automaticReporting
        IntegratedConsentItem.MOBILE_NETWORK_TRANSFER -> mobileNetworkTransfer
        IntegratedConsentItem.TRAINING_REUSE -> trainingReuse
    }

    /** Per-material training reuse; same truth table as [PermissionSessionSnapshot.mayReuseForTraining]. */
    fun mayReuseForTraining(kind: TrainingSourceKind): Boolean = TrainingReuseConsentRule.allows(
        kind = kind,
        rawCollectionConsentGranted = rawSourceCollection,
        trainingReuseConsentGranted = trainingReuse,
    )

    fun withDecision(item: IntegratedConsentItem, granted: Boolean): IntegratedConsentSelections =
        when (item) {
            IntegratedConsentItem.RAW_SOURCE_COLLECTION ->
                copy(rawSourceCollection = granted)
            IntegratedConsentItem.AUTOMATIC_REPORTING ->
                copy(automaticReporting = granted)
            IntegratedConsentItem.MOBILE_NETWORK_TRANSFER ->
                copy(mobileNetworkTransfer = granted)
            IntegratedConsentItem.TRAINING_REUSE ->
                copy(trainingReuse = granted)
        }
}

data class IntegratedConsentItemVersions(
    val rawSourceCollection: String = INTEGRATED_CONSENT_RAW_ITEM_VERSION,
    val automaticReporting: String = INTEGRATED_CONSENT_AUTOMATIC_ITEM_VERSION,
    val mobileNetworkTransfer: String = INTEGRATED_CONSENT_MOBILE_ITEM_VERSION,
    val trainingReuse: String = INTEGRATED_CONSENT_TRAINING_ITEM_VERSION,
) {
    init {
        require(rawSourceCollection == INTEGRATED_CONSENT_RAW_ITEM_VERSION)
        require(automaticReporting == INTEGRATED_CONSENT_AUTOMATIC_ITEM_VERSION)
        require(mobileNetworkTransfer == INTEGRATED_CONSENT_MOBILE_ITEM_VERSION)
        require(trainingReuse == INTEGRATED_CONSENT_TRAINING_ITEM_VERSION)
    }
}

data class IntegratedConsentConfirmation(
    val schemaVersion: String,
    val policyVersion: String,
    val installationId: String,
    val requestId: String,
    val itemVersions: IntegratedConsentItemVersions,
    val clientRevision: Long,
    val revision: Long,
    val selections: IntegratedConsentSelections,
    val confirmedAt: String,
    val gatewayAuditRecordSha256: String,
    val backendConsentReceiptSha256: String,
    val controlSecret: String,
) {
    val confirmedAtInstant: Instant

    init {
        require(schemaVersion == INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION)
        require(policyVersion == INTEGRATED_CONSENT_POLICY_VERSION)
        require(INSTALLATION_ID.matches(installationId))
        require(REQUEST_ID.matches(requestId))
        require(clientRevision > 0L)
        require(revision > 0L)
        confirmedAtInstant = parseConfirmedAt(confirmedAt)
        require(SHA256.matches(gatewayAuditRecordSha256))
        require(SHA256.matches(backendConsentReceiptSha256))
        require(CONTROL_SECRET.matches(controlSecret))
    }

    private companion object {
        val INSTALLATION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
        val REQUEST_ID = Regex("^[A-Za-z0-9_-]{16,128}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val CONTROL_SECRET = Regex("^[0-9a-f]{64}$")
        val CONFIRMED_AT = Regex(
            "([0-9]{4}|[+-][0-9]{6})-([0-9]{2})-([0-9]{2})T" +
                "([0-9]{2}):([0-9]{2}):([0-9]{2})\\.([0-9]{3})Z",
        )
        const val JS_DATE_TIME_CLIP_MS = 8_640_000_000_000_000L

        fun parseConfirmedAt(value: String): Instant {
            val match = CONFIRMED_AT.matchEntire(value)
            require(match != null) {
                "confirmedAt must use the server's canonical UTC millisecond format"
            }
            val yearText = match.groupValues[1]
            val year = yearText.toInt()
            require(
                yearText.length == 4 ||
                    (yearText[0] == '+' && year >= 10_000) ||
                    (yearText[0] == '-' && year < 0),
            ) {
                "confirmedAt must use the server's canonical year representation"
            }
            val instant = try {
                LocalDateTime.of(
                    year,
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                    match.groupValues[4].toInt(),
                    match.groupValues[5].toInt(),
                    match.groupValues[6].toInt(),
                    match.groupValues[7].toInt() * 1_000_000,
                ).toInstant(ZoneOffset.UTC)
            } catch (exception: DateTimeException) {
                throw IllegalArgumentException("confirmedAt must be a valid calendar instant", exception)
            }
            require(instant.toEpochMilli() in -JS_DATE_TIME_CLIP_MS..JS_DATE_TIME_CLIP_MS) {
                "confirmedAt must be within the server Date TimeClip range"
            }
            return instant
        }
    }
}

enum class PurposeConsentSyncState {
    UNCONFIRMED,
    CONFIRMED_DENIED,
    CONFIRMED_GRANTED,
    GRANT_PENDING,
    WITHDRAWAL_PENDING,
    WITHDRAWAL_RETRY,
    FAIL_CLOSED,
}

enum class IntegratedConsentApplyResult {
    APPLIED,
    DUPLICATE,
    STALE_IGNORED,
    NOT_EXACT,
    IDENTITY_CONFLICT_FAIL_CLOSED,
    REVISION_CONFLICT_FAIL_CLOSED,
}

enum class IntegratedConsentSubmissionResult {
    APPLIED,
    UNCHANGED,
    REJECTED,
    STORAGE_FAILED,
    DISPATCH_FAILED,
}

data class PendingIntegratedConsentMutation(
    val installationId: String,
    val actorSha256: String,
    val requestId: String,
    val policyVersion: String,
    val clientRevision: Long,
    val previousServerRevision: Long,
    val expectedPreviousBackendReceiptSha256: String?,
    val desiredSelections: IntegratedConsentSelections,
    val withdrawalItems: Set<IntegratedConsentItem>,
    val createdAtEpochMs: Long,
) {
    init {
        require(INSTALLATION_ID.matches(installationId))
        require(SHA256.matches(actorSha256))
        require(REQUEST_ID.matches(requestId))
        require(policyVersion == INTEGRATED_CONSENT_POLICY_VERSION)
        require(clientRevision > 0L)
        require(previousServerRevision >= 0L)
        require(
            expectedPreviousBackendReceiptSha256 == null ||
                SHA256.matches(expectedPreviousBackendReceiptSha256),
        )
        require(createdAtEpochMs > 0L)
        require(withdrawalItems.all { !desiredSelections.isGranted(it) })
    }

    fun isExactNewerConfirmation(
        confirmation: IntegratedConsentConfirmation,
    ): Boolean =
        confirmation.installationId == installationId &&
            confirmation.requestId == requestId &&
            confirmation.policyVersion == policyVersion &&
            confirmation.clientRevision == clientRevision &&
            confirmation.revision > previousServerRevision &&
            confirmation.selections == desiredSelections &&
            withdrawalItems.all { !confirmation.selections.isGranted(it) }

    fun preservesRestoredConfirmation(
        confirmation: IntegratedConsentConfirmation,
        expectedActorSha256: String?,
        localClientRevision: Long,
    ): Boolean =
        actorSha256 == expectedActorSha256 && installationId == confirmation.installationId &&
            policyVersion == confirmation.policyVersion && clientRevision == localClientRevision &&
            clientRevision > confirmation.clientRevision && previousServerRevision == confirmation.revision &&
            expectedPreviousBackendReceiptSha256 == confirmation.backendConsentReceiptSha256 &&
            requestId == "consent_" + MessageDigest.getInstance("SHA-256").digest(
                ("FP013|$installationId|$clientRevision|" +
                    "$expectedPreviousBackendReceiptSha256|$desiredSelections").toByteArray(Charsets.UTF_8),
            ).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Rebase only denials onto the latest Backend choices after an exact rejected CAS. */
    fun successorAfterRejectedCas(
        backendSelections: IntegratedConsentSelections,
        backendReceiptSha256: String,
        backendClientRevisionFloor: Long,
        clientRevision: Long,
        withdrawalItems: Set<IntegratedConsentItem>,
        createdAtEpochMs: Long,
    ): PendingIntegratedConsentMutation {
        require(SHA256.matches(backendReceiptSha256))
        require(backendReceiptSha256 != expectedPreviousBackendReceiptSha256)
        require(backendClientRevisionFloor in 0 until Long.MAX_VALUE)
        // The rejected request was never stored. Backend accepts exactly the next stored
        // revision, which can equal this request's rejected revision.
        require(clientRevision == backendClientRevisionFloor + 1L)
        val retainedDenials = withdrawalItems + IntegratedConsentItem.entries.filter {
            !desiredSelections.isGranted(it)
        }
        require(retainedDenials.containsAll(this.withdrawalItems))
        val selections = retainedDenials.fold(backendSelections) { current, item ->
            current.withDecision(item, false)
        }
        val nextRequestId = "consent_" + MessageDigest.getInstance("SHA-256").digest(
            ("FP013|$installationId|$clientRevision|$backendReceiptSha256|$selections")
                .toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return copy(
            requestId = nextRequestId,
            clientRevision = clientRevision,
            expectedPreviousBackendReceiptSha256 = backendReceiptSha256,
            desiredSelections = selections,
            withdrawalItems = Collections.unmodifiableSet(retainedDenials.toSet()),
            createdAtEpochMs = createdAtEpochMs,
        )
    }

    fun deniedItems(): Set<IntegratedConsentItem> =
        withdrawalItems + IntegratedConsentItem.entries.filter { !desiredSelections.isGranted(it) }

    private companion object {
        val INSTALLATION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
        val REQUEST_ID = Regex("^[A-Za-z0-9_-]{16,128}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/** Durable evidence that a rejected immutable request was replaced without reopening grants. */
data class RejectedConsentCasSupersession(
    val original: PendingIntegratedConsentMutation,
    val backendSelections: IntegratedConsentSelections,
    val backendReceiptSha256: String,
    val backendClientRevisionFloor: Long,
    val successor: PendingIntegratedConsentMutation,
) {
    init {
        require(successor == original.successorAfterRejectedCas(
            backendSelections = backendSelections,
            backendReceiptSha256 = backendReceiptSha256,
            backendClientRevisionFloor = backendClientRevisionFloor,
            clientRevision = successor.clientRevision,
            withdrawalItems = successor.withdrawalItems,
            createdAtEpochMs = successor.createdAtEpochMs,
        ))
    }
}

/** Bounded restart proof: first rejected request, latest rejected request, and cumulative denials. */
data class RejectedConsentCasRecoveryJournal(
    val firstRejected: PendingIntegratedConsentMutation,
    val latestTransition: RejectedConsentCasSupersession,
    val cumulativeDenials: Set<IntegratedConsentItem>,
    val rejectedCount: Long,
) {
    val successor: PendingIntegratedConsentMutation get() = latestTransition.successor

    init {
        require(rejectedCount > 0L)
        require(firstRejected.installationId == latestTransition.original.installationId)
        require(firstRejected.actorSha256 == latestTransition.original.actorSha256)
        require(firstRejected.policyVersion == latestTransition.original.policyVersion)
        require(cumulativeDenials.containsAll(firstRejected.deniedItems()))
        require(cumulativeDenials.containsAll(latestTransition.original.deniedItems()))
        require(successor.withdrawalItems.containsAll(cumulativeDenials))
        require(rejectedCount != 1L || firstRejected == latestTransition.original)
    }

    companion object {
        fun next(
            previous: RejectedConsentCasRecoveryJournal?,
            transition: RejectedConsentCasSupersession,
        ): RejectedConsentCasRecoveryJournal {
            require(previous == null || previous.successor == transition.original)
            require(previous?.rejectedCount != Long.MAX_VALUE)
            return RejectedConsentCasRecoveryJournal(
                firstRejected = previous?.firstRejected ?: transition.original,
                latestTransition = transition,
                cumulativeDenials = (previous?.cumulativeDenials ?: emptySet()) +
                    transition.original.deniedItems(),
                rejectedCount = (previous?.rejectedCount ?: 0L) + 1L,
            )
        }
    }
}

/**
 * Process-local fail-closed view of the latest server confirmation.
 *
 * A local withdrawal takes effect before its network update completes. Other independent grants
 * remain usable while that withdrawal is being persisted.
 */
interface IntegratedConsentAuthorityGuard {
    /** Execute the read or permit action while the bound process authority is still current. */
    fun <T : Any> withCurrent(action: () -> T): T?
}

class IntegratedConsentSession {
    private val lock = Any()
    private var current: IntegratedConsentConfirmation? = null
    // A restored or previously checked receipt is a cache until this process checks Backend authority.
    private var backendAuthorityVerified = false
    private var backendAuthorityGuard: IntegratedConsentAuthorityGuard? = null
    private var locallyWithdrawn: Set<IntegratedConsentItem> = emptySet()
    private var pendingMutation: PendingIntegratedConsentMutation? = null
    private var pendingRetry = false
    private var accountDeletionBlocked = false
    private var failClosed = false
    private var revisionFloorInstallationId: String? = null
    private var revisionFloorPolicyVersion: String? = null
    private var revisionFloor = 0L

    fun restoreServerRevisionFloor(
        installationId: String,
        policyVersion: String,
        revision: Long,
    ): Boolean = synchronized(lock) {
        if (
            accountDeletionBlocked ||
            failClosed ||
            pendingMutation != null ||
            current != null ||
            revisionFloor > 0L ||
            !Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$").matches(installationId) ||
            policyVersion != INTEGRATED_CONSENT_POLICY_VERSION ||
            revision <= 0L
        ) return@synchronized false
        revisionFloorInstallationId = installationId
        revisionFloorPolicyVersion = policyVersion
        revisionFloor = revision
        true
    }

    fun restoreCurrentConfirmation(
        confirmation: IntegratedConsentConfirmation,
    ): Boolean = synchronized(lock) {
        if (
            accountDeletionBlocked ||
            failClosed ||
            pendingMutation != null ||
            current != null
        ) return@synchronized false
        if (
            revisionFloor > 0L &&
            (
                confirmation.installationId != revisionFloorInstallationId ||
                    confirmation.policyVersion != revisionFloorPolicyVersion ||
                    confirmation.revision != revisionFloor
            )
        ) {
            failClosed = true
            return@synchronized false
        }
        revisionFloorInstallationId = confirmation.installationId
        revisionFloorPolicyVersion = confirmation.policyVersion
        revisionFloor = maxOf(revisionFloor, confirmation.revision)
        current = confirmation
        backendAuthorityVerified = false
        backendAuthorityGuard = null
        true
    }

    fun evaluateConfirmation(
        confirmation: IntegratedConsentConfirmation,
    ): IntegratedConsentApplyResult = synchronized(lock) {
        evaluateConfirmationLocked(confirmation)
    }

    fun applyConfirmation(
        confirmation: IntegratedConsentConfirmation,
        authorityGuard: IntegratedConsentAuthorityGuard? = null,
    ): IntegratedConsentApplyResult = synchronized(lock) {
        val result = evaluateConfirmationLocked(confirmation)
        when (result) {
            IntegratedConsentApplyResult.APPLIED -> {
                revisionFloorInstallationId = confirmation.installationId
                revisionFloorPolicyVersion = confirmation.policyVersion
                revisionFloor = maxOf(revisionFloor, confirmation.revision)
                current = confirmation
                backendAuthorityVerified = true
                backendAuthorityGuard = authorityGuard
            }
            IntegratedConsentApplyResult.DUPLICATE -> {
                backendAuthorityVerified = true
                backendAuthorityGuard = authorityGuard
            }
            IntegratedConsentApplyResult.IDENTITY_CONFLICT_FAIL_CLOSED,
            IntegratedConsentApplyResult.REVISION_CONFLICT_FAIL_CLOSED,
            -> failClosed = true
            else -> Unit
        }
        result
    }

    fun accept(confirmation: IntegratedConsentConfirmation): Boolean =
        applyConfirmation(confirmation) == IntegratedConsentApplyResult.APPLIED

    fun withdrawImmediately(item: IntegratedConsentItem): Boolean = synchronized(lock) {
        val wasAllowed = current?.selections?.isGranted(item) == true &&
            item !in locallyWithdrawn
        locallyWithdrawn = Collections.unmodifiableSet(locallyWithdrawn + item)
        wasAllowed
    }

    fun restorePendingMutation(
        mutation: PendingIntegratedConsentMutation,
        retry: Boolean = false,
    ): Boolean = synchronized(lock) {
        acceptsPendingMutationLocked(mutation) && retainPendingMutationLocked(mutation, retry)
    }

    /**
     * The rejected candidate must never reach durable storage or the transport scheduler.
     * Persist is synchronous storage only and must not re-enter this session. The lock keeps
     * confirmation/account transitions from interleaving between admission and publication.
     * Dispatch runs without this lock: cancellation can enter coordinators whose context
     * callbacks read this session. The caller rechecks its account and this mutation before
     * reserving transport, returning false if the request has lost ownership.
     */
    fun submitPendingMutation(
        mutation: PendingIntegratedConsentMutation,
        retry: Boolean = false,
        persist: (PendingIntegratedConsentMutation) -> Boolean,
        dispatch: (PendingIntegratedConsentMutation) -> Boolean,
    ): IntegratedConsentSubmissionResult {
        val retained = mutation.copy(
            withdrawalItems = Collections.unmodifiableSet(mutation.withdrawalItems.toSet()),
        )
        val changed = synchronized(lock) {
            if (accountDeletionBlocked || failClosed || !acceptsPendingMutationLocked(retained)) {
                return IntegratedConsentSubmissionResult.REJECTED
            }
            val stored = try { persist(retained) } catch (_: Exception) { false }
            if (!stored) return IntegratedConsentSubmissionResult.STORAGE_FAILED
            retainPendingMutationLocked(retained, retry)
        }
        val dispatched = try {
            isPendingMutationCurrent(retained) && dispatch(retained)
        } catch (_: Exception) {
            false
        }
        if (!dispatched) {
            synchronized(lock) {
                if (pendingMutation == retained) pendingRetry = true
            }
            return IntegratedConsentSubmissionResult.DISPATCH_FAILED
        }
        return if (changed) IntegratedConsentSubmissionResult.APPLIED else IntegratedConsentSubmissionResult.UNCHANGED
    }

    /** Only an exact, known-rejected CAS may replace a pending immutable request. */
    fun supersedeRejectedCasMutation(
        supersession: RejectedConsentCasSupersession,
        persist: (RejectedConsentCasSupersession) -> Boolean,
    ): IntegratedConsentSubmissionResult = synchronized(lock) {
        if (
            accountDeletionBlocked || failClosed ||
            pendingMutation != supersession.original ||
            !supersession.successor.withdrawalItems.containsAll(locallyWithdrawn)
        ) return@synchronized IntegratedConsentSubmissionResult.REJECTED
        val stored = try { persist(supersession) } catch (_: Exception) { false }
        if (!stored) return@synchronized IntegratedConsentSubmissionResult.STORAGE_FAILED
        pendingMutation = supersession.successor
        pendingRetry = true
        locallyWithdrawn = Collections.unmodifiableSet(
            locallyWithdrawn + supersession.successor.withdrawalItems,
        )
        backendAuthorityVerified = false
        backendAuthorityGuard = null
        IntegratedConsentSubmissionResult.APPLIED
    }

    fun isPendingMutationCurrent(mutation: PendingIntegratedConsentMutation): Boolean = synchronized(lock) {
        !accountDeletionBlocked && !failClosed && pendingMutation == mutation
    }

    fun localWithdrawalItems(): Set<IntegratedConsentItem> = synchronized(lock) { locallyWithdrawn }

    /** Re-send a denial, never infer that a missing pending request acknowledged a withdrawal. */
    fun withdrawalRecoverySelectionsOrNull(): IntegratedConsentSelections? = synchronized(lock) {
        if (accountDeletionBlocked || failClosed || pendingMutation != null || locallyWithdrawn.isEmpty()) {
            return@synchronized null
        }
        locallyWithdrawn.fold(current?.selections ?: return@synchronized null) { selections, item ->
            selections.withDecision(item, false)
        }
    }

    private fun acceptsPendingMutationLocked(mutation: PendingIntegratedConsentMutation): Boolean {
        val existing = pendingMutation
        if (existing != null) {
            if (mutation.actorSha256 != existing.actorSha256 ||
                mutation.installationId != existing.installationId ||
                mutation.policyVersion != existing.policyVersion
            ) {
                failClosed = true
                return false
            }
            if (mutation.clientRevision < existing.clientRevision) return false
            if (mutation.clientRevision == existing.clientRevision && mutation != existing) {
                failClosed = true
                return false
            }
        }
        if (current?.let { mutation.clientRevision < it.clientRevision } == true) {
            return false
        }
        if (
            revisionFloor > 0L &&
            (
                mutation.installationId != revisionFloorInstallationId ||
                    mutation.policyVersion != revisionFloorPolicyVersion
            )
        ) {
            failClosed = true
            return false
        }
        if (
            existing != null &&
            existing.withdrawalItems.isNotEmpty() &&
            mutation.clientRevision > existing.clientRevision
        ) return false
        if (
            mutation != existing &&
            locallyWithdrawn.any { mutation.desiredSelections.isGranted(it) }
        ) return false
        return true
    }

    private fun retainPendingMutationLocked(
        mutation: PendingIntegratedConsentMutation,
        retry: Boolean,
    ): Boolean {
        val changed =
            pendingMutation != mutation ||
                !locallyWithdrawn.containsAll(mutation.withdrawalItems) ||
                pendingRetry != retry
        val retainedMutation = mutation.copy(
            withdrawalItems = Collections.unmodifiableSet(mutation.withdrawalItems.toSet()),
        )
        pendingMutation = retainedMutation
        pendingRetry = retry
        locallyWithdrawn =
            Collections.unmodifiableSet(locallyWithdrawn + retainedMutation.withdrawalItems)
        return changed
    }

    fun markPendingRetry(): Boolean = synchronized(lock) {
        if (pendingMutation == null || pendingRetry) return@synchronized false
        pendingRetry = true
        true
    }

    fun applyExactPendingConfirmation(
        confirmation: IntegratedConsentConfirmation,
        authorityGuard: IntegratedConsentAuthorityGuard? = null,
    ): Boolean = synchronized(lock) {
        val result = evaluateExactPendingConfirmationLocked(confirmation)
        if (
            result != IntegratedConsentApplyResult.APPLIED &&
            result != IntegratedConsentApplyResult.DUPLICATE
        ) {
            if (
                result == IntegratedConsentApplyResult.IDENTITY_CONFLICT_FAIL_CLOSED ||
                result == IntegratedConsentApplyResult.REVISION_CONFLICT_FAIL_CLOSED
            ) {
                failClosed = true
            }
            return@synchronized false
        }
        val pending = pendingMutation ?: return@synchronized false
        revisionFloorInstallationId = confirmation.installationId
        revisionFloorPolicyVersion = confirmation.policyVersion
        revisionFloor = maxOf(revisionFloor, confirmation.revision)
        current = confirmation
        backendAuthorityVerified = true
        backendAuthorityGuard = authorityGuard
        locallyWithdrawn =
            Collections.unmodifiableSet(locallyWithdrawn - pending.withdrawalItems)
        pendingMutation = null
        pendingRetry = false
        true
    }

    fun evaluateExactPendingConfirmation(
        confirmation: IntegratedConsentConfirmation,
    ): IntegratedConsentApplyResult = synchronized(lock) {
        evaluateExactPendingConfirmationLocked(confirmation)
    }

    fun pendingMutationOrNull(): PendingIntegratedConsentMutation? =
        synchronized(lock) { pendingMutation }

    fun blockForAccountDeletion(): Boolean = synchronized(lock) {
        val changed = !accountDeletionBlocked
        accountDeletionBlocked = true
        changed
    }

    fun failClosed(): Boolean = synchronized(lock) {
        val changed = !failClosed
        failClosed = true
        changed
    }

    fun resetForNewEnrollment(): Boolean = synchronized(lock) {
        val changed =
            current != null ||
                locallyWithdrawn.isNotEmpty() ||
                pendingMutation != null ||
                pendingRetry ||
                accountDeletionBlocked ||
                failClosed ||
                revisionFloor > 0L
        current = null
        backendAuthorityVerified = false
        backendAuthorityGuard = null
        locallyWithdrawn = emptySet()
        pendingMutation = null
        pendingRetry = false
        accountDeletionBlocked = false
        failClosed = false
        revisionFloorInstallationId = null
        revisionFloorPolicyVersion = null
        revisionFloor = 0L
        changed
    }

    fun resetForPolicyReconsent(): Boolean = synchronized(lock) {
        // Reconsent is not an acknowledgement of an undelivered withdrawal.
        if (accountDeletionBlocked || pendingMutation != null || locallyWithdrawn.isNotEmpty()) {
            return@synchronized false
        }
        val changed =
            current != null ||
                locallyWithdrawn.isNotEmpty() ||
                pendingMutation != null ||
                pendingRetry ||
                failClosed ||
                revisionFloor > 0L
        current = null
        backendAuthorityVerified = false
        backendAuthorityGuard = null
        locallyWithdrawn = emptySet()
        pendingMutation = null
        pendingRetry = false
        failClosed = false
        revisionFloorInstallationId = null
        revisionFloorPolicyVersion = null
        revisionFloor = 0L
        changed
    }

    fun invalidate(): Boolean = synchronized(lock) {
        val changed = current != null
        current = null
        backendAuthorityVerified = false
        backendAuthorityGuard = null
        changed
    }

    /** Retain a validated local receipt for CAS/recovery while closing all runtime grants. */
    fun invalidateBackendAuthority(): Boolean = synchronized(lock) {
        val changed = backendAuthorityVerified
        backendAuthorityVerified = false
        backendAuthorityGuard = null
        changed
    }

    fun cachedConfirmationOrNull(): IntegratedConsentConfirmation? = synchronized(lock) {
        if (accountDeletionBlocked || failClosed) null else current
    }

    fun currentConfirmationOrNull(
        required: Set<IntegratedConsentItem> = emptySet(),
    ): IntegratedConsentConfirmation? {
        val guard = synchronized(lock) {
            if (accountDeletionBlocked || failClosed || !backendAuthorityVerified) return null
            backendAuthorityGuard
        }
        val read = {
            synchronized(lock) {
                if (accountDeletionBlocked || failClosed || !backendAuthorityVerified ||
                    backendAuthorityGuard !== guard
                ) null else current?.takeIf { confirmation ->
                    required.all { item ->
                        confirmation.selections.isGranted(item) &&
                            item !in locallyWithdrawn &&
                            pendingMutation?.desiredSelections?.isGranted(item) != false
                    }
                }
            }
        }
        // The guard holds the process generation lock through the actual consent read.
        return if (guard == null) read() else guard.withCurrent { read() to true }?.first
    }

    fun isAllowed(item: IntegratedConsentItem): Boolean =
        currentConfirmationOrNull(setOf(item)) != null

    fun status(item: IntegratedConsentItem): PurposeConsentSyncState {
        val guard = synchronized(lock) { backendAuthorityGuard }
        return if (guard == null) synchronized(lock) { statusLocked(item, true, null) }
        else guard.withCurrent { synchronized(lock) { statusLocked(item, true, guard) } }
            ?: synchronized(lock) { statusLocked(item, false, guard) }
    }

    private fun statusLocked(
        item: IntegratedConsentItem,
        authorityCurrent: Boolean,
        guard: IntegratedConsentAuthorityGuard?,
    ): PurposeConsentSyncState {
        if (accountDeletionBlocked || failClosed) {
            return PurposeConsentSyncState.FAIL_CLOSED
        }
        val pending = pendingMutation
        if (pending?.desiredSelections?.isGranted(item) == false &&
            current?.selections?.isGranted(item) == true
        ) {
            return if (pendingRetry) {
                PurposeConsentSyncState.WITHDRAWAL_RETRY
            } else {
                PurposeConsentSyncState.WITHDRAWAL_PENDING
            }
        }
        if (item in locallyWithdrawn) {
            return if (pendingRetry) {
                PurposeConsentSyncState.WITHDRAWAL_RETRY
            } else {
                PurposeConsentSyncState.WITHDRAWAL_PENDING
            }
        }
        if (
            pending != null &&
            pending.desiredSelections.isGranted(item) &&
            current?.selections?.isGranted(item) != true
        ) {
            return PurposeConsentSyncState.GRANT_PENDING
        }
        return when (current?.takeIf {
            backendAuthorityVerified && authorityCurrent && backendAuthorityGuard === guard
        }?.selections?.isGranted(item)) {
            true -> PurposeConsentSyncState.CONFIRMED_GRANTED
            false -> PurposeConsentSyncState.CONFIRMED_DENIED
            null -> PurposeConsentSyncState.UNCONFIRMED
        }
    }

    private fun evaluateConfirmationLocked(
        confirmation: IntegratedConsentConfirmation,
    ): IntegratedConsentApplyResult {
        val expectedInstallationId =
            revisionFloorInstallationId ?:
                pendingMutation?.installationId ?:
                current?.installationId ?:
                null
        val expectedPolicyVersion =
            revisionFloorPolicyVersion ?:
                pendingMutation?.policyVersion ?:
                current?.policyVersion ?:
                null
        if (
            expectedInstallationId != null &&
            (
                confirmation.installationId != expectedInstallationId ||
                    confirmation.policyVersion != expectedPolicyVersion
            )
        ) {
            return IntegratedConsentApplyResult.IDENTITY_CONFLICT_FAIL_CLOSED
        }
        val existing = current
        when {
            existing == null -> Unit
            confirmation.revision < existing.revision ->
                return IntegratedConsentApplyResult.STALE_IGNORED
            confirmation.revision == existing.revision && confirmation == existing ->
                return IntegratedConsentApplyResult.DUPLICATE
            confirmation.revision == existing.revision ->
                return IntegratedConsentApplyResult.REVISION_CONFLICT_FAIL_CLOSED
        }
        val requiredRevisionFloor =
            maxOf(
                revisionFloor,
                pendingMutation?.previousServerRevision ?: 0L,
            )
        if (confirmation.revision <= requiredRevisionFloor) {
            return IntegratedConsentApplyResult.REVISION_CONFLICT_FAIL_CLOSED
        }
        return IntegratedConsentApplyResult.APPLIED
    }

    private fun evaluateExactPendingConfirmationLocked(
        confirmation: IntegratedConsentConfirmation,
    ): IntegratedConsentApplyResult {
        val pending =
            pendingMutation ?: return IntegratedConsentApplyResult.NOT_EXACT
        if (
            revisionFloor > 0L &&
            (
                pending.installationId != revisionFloorInstallationId ||
                    pending.policyVersion != revisionFloorPolicyVersion
            )
        ) {
            return IntegratedConsentApplyResult.IDENTITY_CONFLICT_FAIL_CLOSED
        }
        if (
            confirmation.installationId != pending.installationId ||
            confirmation.policyVersion != pending.policyVersion
        ) {
            return IntegratedConsentApplyResult.IDENTITY_CONFLICT_FAIL_CLOSED
        }
        val requiredRevisionFloor =
            maxOf(revisionFloor, pending.previousServerRevision)
        if (confirmation == current &&
            confirmation.revision == revisionFloor &&
            pending.isExactNewerConfirmation(confirmation)
        ) {
            return IntegratedConsentApplyResult.DUPLICATE
        }
        if (confirmation.revision <= requiredRevisionFloor) {
            return IntegratedConsentApplyResult.REVISION_CONFLICT_FAIL_CLOSED
        }
        if (!pending.isExactNewerConfirmation(confirmation)) {
            return IntegratedConsentApplyResult.NOT_EXACT
        }
        return evaluateConfirmationLocked(confirmation)
    }
}
