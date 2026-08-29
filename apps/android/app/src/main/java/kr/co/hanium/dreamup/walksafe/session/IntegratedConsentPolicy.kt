package kr.co.hanium.dreamup.walksafe.session

import java.util.Collections

const val INTEGRATED_CONSENT_POLICY_VERSION = "FP-013-1.1.0"
const val PREVIOUS_INTEGRATED_CONSENT_POLICY_VERSION = "FP-013-1.0.0"
const val INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION =
    "walksafe.integrated-consent-confirmation.v2"
const val INTEGRATED_CONSENT_RAW_ITEM_VERSION = "FP-013-RAW-1.1.0"
const val INTEGRATED_CONSENT_AUTOMATIC_ITEM_VERSION = "FP-013-AUTO-1.1.0"
const val INTEGRATED_CONSENT_MOBILE_ITEM_VERSION = "FP-013-MOBILE-1.0.0"
const val INTEGRATED_CONSENT_TRAINING_ITEM_VERSION = "FP-013-TRAINING-1.1.0"

const val INTEGRATED_CONSENT_DISCLOSURE_KO =
    "통합 동의 문서 $INTEGRATED_CONSENT_POLICY_VERSION. 네 항목을 각각 허용하거나 거부할 수 있습니다. " +
        "1. 신고·진단용 raw v2: 탐지·성능 메타데이터와 chunk 시각·크기·hash만 수집하며, 영상·음성·이미지·" +
        "정확한 위치·이동경로·개별 frame·bbox는 제외합니다. 기기에 최대 30일 암호화 저장하고 사용자가 " +
        "재확인한 PAUSED 상태에서만 전송하며, 서버 검역은 receipt commit부터 최대 14일입니다. END에서는 " +
        "전송하지 않습니다. " +
        "2. 자동신고: JPEG 신고 사진과 정확한 위치·방향·탐지 metadata를 기기에 암호화해 대기하고, 사용자가 " +
        "재확인한 PAUSED 상태에서만 동의와 네트워크 조건을 다시 확인해 전송합니다. END는 자동 전송 시점이 아닙니다. " +
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
    init {
        require(schemaVersion == INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION)
        require(policyVersion == INTEGRATED_CONSENT_POLICY_VERSION)
        require(INSTALLATION_ID.matches(installationId))
        require(REQUEST_ID.matches(requestId))
        require(clientRevision > 0L)
        require(revision > 0L)
        require(confirmedAt.isNotBlank())
        require(SHA256.matches(gatewayAuditRecordSha256))
        require(SHA256.matches(backendConsentReceiptSha256))
        require(CONTROL_SECRET.matches(controlSecret))
    }

    private companion object {
        val INSTALLATION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
        val REQUEST_ID = Regex("^[A-Za-z0-9_-]{16,128}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val CONTROL_SECRET = Regex("^[0-9a-f]{64}$")
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

    private companion object {
        val INSTALLATION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
        val REQUEST_ID = Regex("^[A-Za-z0-9_-]{16,128}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Process-local fail-closed view of the latest server confirmation.
 *
 * A local withdrawal takes effect before its network update completes. Other independent grants
 * remain usable while that withdrawal is being persisted.
 */
class IntegratedConsentSession {
    private val lock = Any()
    private var current: IntegratedConsentConfirmation? = null
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
        true
    }

    fun evaluateConfirmation(
        confirmation: IntegratedConsentConfirmation,
    ): IntegratedConsentApplyResult = synchronized(lock) {
        evaluateConfirmationLocked(confirmation)
    }

    fun applyConfirmation(
        confirmation: IntegratedConsentConfirmation,
    ): IntegratedConsentApplyResult = synchronized(lock) {
        val result = evaluateConfirmationLocked(confirmation)
        when (result) {
            IntegratedConsentApplyResult.APPLIED -> {
                revisionFloorInstallationId = confirmation.installationId
                revisionFloorPolicyVersion = confirmation.policyVersion
                revisionFloor = maxOf(revisionFloor, confirmation.revision)
                current = confirmation
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
        if (
            revisionFloor > 0L &&
            (
                mutation.installationId != revisionFloorInstallationId ||
                    mutation.policyVersion != revisionFloorPolicyVersion
            )
        ) {
            failClosed = true
            return@synchronized false
        }
        val changed =
            pendingMutation != mutation ||
                !locallyWithdrawn.containsAll(mutation.withdrawalItems) ||
                pendingRetry != retry
        pendingMutation = mutation
        pendingRetry = retry
        locallyWithdrawn =
            Collections.unmodifiableSet(locallyWithdrawn + mutation.withdrawalItems)
        changed
    }

    fun markPendingRetry(): Boolean = synchronized(lock) {
        if (pendingMutation == null || pendingRetry) return@synchronized false
        pendingRetry = true
        true
    }

    fun applyExactPendingConfirmation(
        confirmation: IntegratedConsentConfirmation,
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
        if (accountDeletionBlocked) return@synchronized false
        val changed =
            current != null ||
                locallyWithdrawn.isNotEmpty() ||
                pendingMutation != null ||
                pendingRetry ||
                failClosed ||
                revisionFloor > 0L
        current = null
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
        changed
    }

    fun currentConfirmationOrNull(
        required: Set<IntegratedConsentItem> = emptySet(),
    ): IntegratedConsentConfirmation? = synchronized(lock) {
        if (accountDeletionBlocked || failClosed) return@synchronized null
        current?.takeIf { confirmation ->
            required.all { item ->
                confirmation.selections.isGranted(item) && item !in locallyWithdrawn
            }
        }
    }

    fun isAllowed(item: IntegratedConsentItem): Boolean =
        currentConfirmationOrNull(setOf(item)) != null

    fun status(item: IntegratedConsentItem): PurposeConsentSyncState = synchronized(lock) {
        if (accountDeletionBlocked || failClosed) {
            return@synchronized PurposeConsentSyncState.FAIL_CLOSED
        }
        val pending = pendingMutation
        if (item in locallyWithdrawn) {
            return@synchronized if (pendingRetry) {
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
            return@synchronized PurposeConsentSyncState.GRANT_PENDING
        }
        when (current?.selections?.isGranted(item)) {
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
        if (confirmation.revision <= requiredRevisionFloor) {
            return IntegratedConsentApplyResult.REVISION_CONFLICT_FAIL_CLOSED
        }
        if (!pending.isExactNewerConfirmation(confirmation)) {
            return IntegratedConsentApplyResult.NOT_EXACT
        }
        return evaluateConfirmationLocked(confirmation)
    }
}
