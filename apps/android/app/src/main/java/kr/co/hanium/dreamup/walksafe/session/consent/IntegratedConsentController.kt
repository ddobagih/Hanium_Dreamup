package kr.co.hanium.dreamup.walksafe.session.consent

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_REPORT_PRIVACY_CONSENT_KEY
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_AUTOMATIC_REPORT_CONSENT_KEY
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_MOBILE_NETWORK_PREFERENCE_KEY
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_TRAINING_REUSE_CONSENT_KEY
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_REVISION
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_CLIENT_REVISION
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_RECEIPT_SHA256
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_BACKEND_RECEIPT_SHA256
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_GATEWAY_AUDIT_SHA256
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_ACTOR_SHA256
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_CONTROL_SECRET
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_PENDING_INTEGRATED_CONSENT_MUTATION
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_PENDING_AUTOMATIC_REPORT_CLEANUP
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_REJECTED_CONSENT_CAS_JOURNAL
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_RAW_SOURCE_FIELD_LOG_BLOCKED
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.INTEGRATED_CONSENT_RECEIPT_SHA256
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.INTEGRATED_CONSENT_CONTROL_SECRET
import org.json.JSONArray
import org.json.JSONObject

/** Actor-bound consent outbox/CAS ownership; SDK/media/View effects stay with their existing peers. */
internal class IntegratedConsentController(
    private val integratedConsentSession: IntegratedConsentSession,
    private val remote: Remote,
    private val gateway: Gateway,
    private val permissions: () -> PermissionSessionPolicy,
    private val actor: Actor,
    private val activity: Activity,
    private val speech: SpeechStorage,
    private val reports: Reports,
    private val raw: RawStorage,
    private val network: Network,
    private val ui: Ui,
    private val executor: Executor,
    private val postToMain: (() -> Unit) -> Unit,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val randomBytes: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) },
) {
    interface Remote {
        fun fetchCurrentCall(gatewayBaseUrl: String, session: GatewayFieldSession, installationId: String, controlSecret: String): CancellableNetworkCall<IntegratedConsentConfirmation?>
        fun fetchBootstrapCall(gatewayBaseUrl: String, session: GatewayFieldSession, installationId: String): CancellableNetworkCall<IntegratedConsentBootstrap>
        fun saveCall(gatewayBaseUrl: String, session: GatewayFieldSession, installationId: String, controlSecret: String, requestId: String, clientRevision: Long, selections: IntegratedConsentSelections, expectedPreviousBackendReceiptSha256: String?): CancellableNetworkCall<IntegratedConsentConfirmation?>
    }
    interface Gateway {
        fun configuredOrigin(): String?
        fun installationId(): String?
        fun sessionOrNull(reason: String, speak: Boolean): GatewayFieldSession?
        fun refreshSessionOrNull(): GatewayFieldSession?
        fun isCurrentSession(session: GatewayFieldSession): Boolean
    }
    interface Actor {
        fun currentReporterActorId(): String?
        fun isIntegratedConsentOnboardingStage(): Boolean
    }
    interface Activity {
        fun destroyed(): Boolean
        fun processingBlocked(): Boolean
        fun currentLeaseIsCurrent(): Boolean
    }
    interface SpeechStorage {
        fun invalidate()
        fun blockRawWithdrawal()
        fun prepareConfirmationApply(confirmation: IntegratedConsentConfirmation, evaluation: IntegratedConsentApplyResult, previousRawConfirmation: IntegratedConsentConfirmation?, currentRawConfirmation: () -> IntegratedConsentConfirmation?): Boolean
    }
    interface Reports {
        fun withdrawAuthority()
        fun cancelDrain()
        fun automaticRevoked(receipt: String, reporterActorId: String, accountGeneration: Long?): Boolean
        fun cancelAutomaticCalls()
        fun scheduleLegacyPurge()
        fun cancelAllCallsForMobileWithdrawal()
        fun applyConfirmationAuthority(unchanged: Boolean, guard: IntegratedConsentAuthorityGuard)
        fun invalidateExplicitReport()
    }
    interface RawStorage {
        fun cancelOriginalUploads()
        fun cancelActiveUploadIfReady()
        fun withdrawReceiptIfReady(previousReceipt: String?)
        fun disableMetadataIfReady()
        fun blockFieldLogIfReady()
        fun blockFieldLog()
        fun holdReceiptUntilDecidedIfReady(receipt: String)
        fun resetFieldLogAfterConfirmedConsentIfActive()
        fun confirmedReceiptChangedIfReady(previousReceipt: String?, previousConfirmation: IntegratedConsentConfirmation?, confirmation: IntegratedConsentConfirmation, rawEffective: Boolean)
    }
    interface Network {
        fun refreshFeaturePolicy()
        fun isCellularIfReady(): Boolean
        fun cancelGatewayCalls()
    }
    interface Ui {
        fun postRefresh()
        fun refresh()
        fun status(message: String)
        fun speakInteraction(message: String)
        fun completeFirstRunIfReady(confirmation: IntegratedConsentConfirmation)
        fun schedulePostWalkTransfers()
        fun scheduleAutomaticReportCleanupRetry(action: () -> Unit, delayMs: Long) {}
    }

    data class ScreenState(
        val draft: IntegratedConsentSelections,
        val clientRevision: Long,
        val pendingMutation: PendingIntegratedConsentMutation?,
        val requestInFlight: Boolean,
        val bootstrapReady: Boolean,
        val confirmedActorSha256: String?,
    )
    private lateinit var persistence: IntegratedConsentPersistence
    private val sensitivePrefs get() = persistence.sensitive
    private val stepLengthPrefs get() = persistence.plain
    private val permissionSessionPolicy get() = permissions()
    private val integratedConsentLock = Any()
    private var integratedConsentDraft = IntegratedConsentSelections()
    private var integratedConsentClientRevision = 0L
    private var pendingIntegratedConsentMutation: PendingIntegratedConsentMutation? = null
    private var integratedConsentRequestGeneration = 0L
    private var integratedConsentRequestInFlight = false
    private var integratedConsentCall: CancellableNetworkCall<IntegratedConsentConfirmation?>? = null
    private var integratedConsentBootstrapCall: CancellableNetworkCall<IntegratedConsentBootstrap>? = null
    private var integratedConsentBootstrapReady = false
    private var integratedConsentExpectedPreviousBackendReceiptSha256: String? = null
    private var integratedConsentConfirmedActorSha256: String? = null
    private var lastIntegratedConsentAuthoritySnapshot: GatewaySessionProcessSnapshot? = null
    private data class AutomaticCleanup(val actorSha256: String, val receipt: String)
    private var automaticCleanupInFlight = false
    private var automaticCleanupStorageFailed = false
    private var automaticCleanupRetryAttempt = 0

    private fun pendingAutomaticCleanupOrNull(): Set<AutomaticCleanup>? = runCatching {
        val encoded = sensitivePrefs.getString(PREF_PENDING_AUTOMATIC_REPORT_CLEANUP, null) ?: return@runCatching emptySet()
        check(kr.co.hanium.dreamup.walksafe.report.strictJsonObjectDocument(encoded))
        val root = JSONObject(encoded)
        check(root.length() == 1 && root.has("pending"))
        val values = root.getJSONArray("pending")
        check(values.length() <= 64)
        buildSet {
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                check(value.length() == 2)
                val owner = value.get("actor_sha256") as? String ?: error("cleanup owner missing")
                val receipt = value.get("receipt") as? String ?: error("cleanup receipt missing")
                check(INTEGRATED_CONSENT_RECEIPT_SHA256.matches(owner) && INTEGRATED_CONSENT_RECEIPT_SHA256.matches(receipt))
                check(add(AutomaticCleanup(owner, receipt)))
            }
        }
    }.getOrNull()

    private fun saveAutomaticCleanup(values: Set<AutomaticCleanup>): Boolean {
        if (values.size > 64) return false
        val editor = sensitivePrefs.edit()
        if (values.isEmpty()) editor.remove(PREF_PENDING_AUTOMATIC_REPORT_CLEANUP)
        else editor.putString(PREF_PENDING_AUTOMATIC_REPORT_CLEANUP,
            JSONObject().put("pending", JSONArray(values.map {
                JSONObject().put("actor_sha256", it.actorSha256).put("receipt", it.receipt)
            })).toString())
        return editor.commit()
    }

    private fun recordAutomaticCleanup(receipt: String, ownerSha256: String?) {
        permissionSessionPolicy.setAutomaticReportConsent(false)
        val saved = synchronized(integratedConsentLock) {
            val pending = pendingAutomaticCleanupOrNull()
            if (pending == null || ownerSha256 == null || !INTEGRATED_CONSENT_RECEIPT_SHA256.matches(ownerSha256) || !INTEGRATED_CONSENT_RECEIPT_SHA256.matches(receipt)) false
            else saveAutomaticCleanup(pending + AutomaticCleanup(ownerSha256, receipt))
        }
        if (!saved) {
            automaticCleanupStorageFailed = true
            integratedConsentSession.failClosed()
            ui.status("integratedConsent=blocked:automatic_cleanup_pending_storage")
        }
        retryPendingAutomaticReportCleanup()
    }

    fun automaticReportCleanupReadyForCurrentActor(): Boolean = synchronized(integratedConsentLock) {
        if (!this::persistence.isInitialized || automaticCleanupStorageFailed || sensitivePrefs.isBlocked()) return@synchronized false
        val pending = pendingAutomaticCleanupOrNull() ?: return@synchronized false
        val session = GatewaySessionProcessCoordinator.snapshot().session ?: return@synchronized false
        pending.none { it.actorSha256 == integratedConsentActorSha256(session) }
    }

    /** Durable cleanup survives a new grant and is dispatched only for its exact actor generation. */
    fun retryPendingAutomaticReportCleanup() {
        if (!this::persistence.isInitialized || activity.destroyed() || activity.processingBlocked()) return
        val session = GatewaySessionProcessCoordinator.snapshot().session ?: return
        if (session.actorId != actor.currentReporterActorId() || !gateway.isCurrentSession(session)) return
        val owner = integratedConsentActorSha256(session)
        val pending = synchronized(integratedConsentLock) {
            if (automaticCleanupInFlight || automaticCleanupStorageFailed) return
            val records = pendingAutomaticCleanupOrNull() ?: run { automaticCleanupStorageFailed = true; return }
            records.filter { it.actorSha256 == owner }.takeIf { it.isNotEmpty() }?.also { automaticCleanupInFlight = true }
        } ?: return
        try {
            executor.execute {
                var completed = true
                try {
                    pending.forEach { record ->
                        val current = GatewaySessionProcessCoordinator.snapshot().session
                        val success = current != null && current.actorId == session.actorId &&
                            current.backendAccountGeneration == session.backendAccountGeneration &&
                            gateway.isCurrentSession(current) &&
                            runCatching { reports.automaticRevoked(record.receipt, session.actorId, session.backendAccountGeneration) }.getOrDefault(false)
                        if (!success) { completed = false; return@forEach }
                        val cleared = synchronized(integratedConsentLock) {
                            val records = pendingAutomaticCleanupOrNull() ?: return@synchronized false
                            saveAutomaticCleanup(records - record)
                        }
                        if (!cleared) completed = false
                    }
                } finally {
                    synchronized(integratedConsentLock) { automaticCleanupInFlight = false }
                    runOnUiThread {
                        if (activity.destroyed()) return@runOnUiThread
                        reapplyActorBoundIntegratedConsent(retryCleanup = false)
                        if (completed) {
                            automaticCleanupRetryAttempt = 0
                            ui.schedulePostWalkTransfers()
                        } else scheduleAutomaticCleanupRetry()
                        ui.refresh()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(integratedConsentLock) { automaticCleanupInFlight = false }
            scheduleAutomaticCleanupRetry()
        }
    }

    private fun scheduleAutomaticCleanupRetry() {
        automaticCleanupRetryAttempt = (automaticCleanupRetryAttempt + 1).coerceAtMost(6)
        val delayMs = (1_000L shl automaticCleanupRetryAttempt).coerceAtMost(60_000L)
        runOnUiThread { ui.scheduleAutomaticReportCleanupRetry(::retryPendingAutomaticReportCleanup, delayMs) }
    }

    fun initializePersistence(persistence: IntegratedConsentPersistence) {
        check(!this::persistence.isInitialized)
        this.persistence = persistence
    }
    fun screenState(): ScreenState = ScreenState(
        integratedConsentDraft, integratedConsentClientRevision, pendingIntegratedConsentMutation,
        integratedConsentRequestInFlight, integratedConsentBootstrapReady, integratedConsentConfirmedActorSha256,
    )
    private fun postIntegratedConsentUiRefresh() = ui.postRefresh()
    private fun runOnUiThread(action: () -> Unit) = postToMain(action)
    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun migrateKnownIntegratedConsentPolicyAtStartup(): Boolean {
        if (sensitivePrefs.isBlocked()) return true
        val storedPolicy = sensitivePrefs.getString(
            PREF_INTEGRATED_CONSENT_POLICY_VERSION,
            null,
        ) ?: return true
        if (storedPolicy == INTEGRATED_CONSENT_POLICY_VERSION) return true
        // Root decision R3: a 1.1.0 (or 1.0.0) confirmation becomes a denied reconsent. Queued
        // reports and RAW segments are kept, not deleted, and wait for the new consent.
        if (storedPolicy !in KNOWN_PREVIOUS_INTEGRATED_CONSENT_POLICY_VERSIONS) {
            speech.invalidate()
            integratedConsentSession.failClosed()
            return true
        }
        if (hasUnsettledIntegratedConsentControl()) {
            applyImmediateConsentWithdrawals(
                IntegratedConsentItem.entries.toSet(), recordLocalWithdrawal = false,
            )
            speech.invalidate()
            integratedConsentSession.invalidate()
            return false
        }
        if (
            !stepLengthPrefs.edit()
                .putBoolean(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED, true)
                .commit()
        ) {
            speech.invalidate()
            integratedConsentSession.failClosed()
            return false
        }
        if (!resetIntegratedConsentForReconsent()) return false
        val migrated = sensitivePrefs.edit()
            .remove(PREF_INTEGRATED_CONSENT_POLICY_VERSION)
            .remove(PREF_INTEGRATED_CONSENT_REVISION)
            .remove(PREF_INTEGRATED_CONSENT_RECEIPT_SHA256)
            .remove(PREF_INTEGRATED_CONSENT_BACKEND_RECEIPT_SHA256)
            .remove(PREF_INTEGRATED_CONSENT_GATEWAY_AUDIT_SHA256)
            .remove(PREF_INTEGRATED_CONSENT_ACTOR_SHA256)
            .remove(PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION)
            .remove(PREF_REPORT_PRIVACY_CONSENT_KEY)
            .remove(PREF_AUTOMATIC_REPORT_CONSENT_KEY)
            .remove(PREF_MOBILE_NETWORK_PREFERENCE_KEY)
            .remove(PREF_TRAINING_REUSE_CONSENT_KEY)
            .commit()
        if (!migrated) {
            speech.invalidate()
            integratedConsentSession.failClosed()
            return false
        }
        pendingIntegratedConsentMutation = null
        integratedConsentDraft = IntegratedConsentSelections()
        integratedConsentBootstrapReady = false
        integratedConsentExpectedPreviousBackendReceiptSha256 = null
        integratedConsentConfirmedActorSha256 = null
        return true
    }

    private fun restoreLegacyIntegratedConsentRevisionFloor(): Boolean {
        if (sensitivePrefs.isBlocked()) return false
        val revision =
            sensitivePrefs.getLong(PREF_INTEGRATED_CONSENT_REVISION, 0L)
        if (revision <= 0L) return false
        val policyVersion =
            sensitivePrefs.getString(
                PREF_INTEGRATED_CONSENT_POLICY_VERSION,
                null,
            )
        val clientRevision =
            sensitivePrefs.getLong(PREF_INTEGRATED_CONSENT_CLIENT_REVISION, 0L)
        val receipt =
            sensitivePrefs.getString(
                PREF_INTEGRATED_CONSENT_BACKEND_RECEIPT_SHA256,
                null,
            )
        val installationId = gateway.installationId()
        if (
            policyVersion != INTEGRATED_CONSENT_POLICY_VERSION ||
            clientRevision <= 0L ||
            receipt?.let(INTEGRATED_CONSENT_RECEIPT_SHA256::matches) != true ||
            installationId == null ||
            !integratedConsentSession.restoreServerRevisionFloor(
                installationId = installationId,
                policyVersion = policyVersion,
                revision = revision,
            )
        ) {
            speech.invalidate()
            integratedConsentSession.failClosed()
            return false
        }
        integratedConsentClientRevision =
            maxOf(integratedConsentClientRevision, clientRevision)
        integratedConsentDraft = persistence.persistedIntegratedConsentSelections()
        reapplyActorBoundIntegratedConsent()
        if (!integratedConsentDraft.rawSourceCollection) {
            raw.blockFieldLog()
            if (
                !stepLengthPrefs.edit()
                    .putBoolean(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED, true)
                    .commit()
            ) {
                speech.invalidate()
                integratedConsentSession.failClosed()
                return false
            }
        }
        return true
    }

    fun onReportPrivacyConsentButtonClicked() {
        if (integratedConsentDraft.rawSourceCollection) {
            withdrawReportPrivacyConsent(reason = "user_withdrew", announce = true)
            return
        }
        updateIntegratedConsentDraft(
            IntegratedConsentItem.RAW_SOURCE_COLLECTION,
            granted = true,
        )
        persistIntegratedConsentDraft(announce = true)
    }

    private fun withdrawReportPrivacyConsent(reason: String, announce: Boolean = false) {
        updateIntegratedConsentDraft(
            IntegratedConsentItem.RAW_SOURCE_COLLECTION,
            granted = false,
        )
        ui.status("rawDiagnostics=withdrawn reason=$reason")
        if (announce) {
            ui.speakInteraction(
                "선택 원본·진단수집 동의를 즉시 철회했습니다. 직접 신고는 건별 확인 후 계속 사용할 수 있습니다.",
            )
        }
        persistIntegratedConsentDraft(announce = announce)
    }

    fun onAutomaticReportConsentButtonClicked() {
        updateIntegratedConsentDraft(
            IntegratedConsentItem.AUTOMATIC_REPORTING,
            granted = !integratedConsentDraft.automaticReporting,
        )
        persistIntegratedConsentDraft(announce = true)
    }

    fun onMobileNetworkPreferenceButtonClicked() {
        updateIntegratedConsentDraft(
            IntegratedConsentItem.MOBILE_NETWORK_TRANSFER,
            granted = !integratedConsentDraft.mobileNetworkTransfer,
        )
        persistIntegratedConsentDraft(announce = true)
    }

    fun onTrainingReuseConsentButtonClicked() {
        updateIntegratedConsentDraft(
            IntegratedConsentItem.TRAINING_REUSE,
            granted = !integratedConsentDraft.trainingReuse,
        )
        persistIntegratedConsentDraft(announce = true)
    }

    fun updateIntegratedConsentDraft(
        item: IntegratedConsentItem,
        granted: Boolean,
    ) {
        integratedConsentDraft = integratedConsentDraft.withDecision(item, granted)
        if (!granted) {
            if (sensitivePrefs.isBlocked()) {
                applyImmediateConsentWithdrawals(setOf(item))
                speech.invalidate()
                integratedConsentSession.failClosed()
                return
            }
            val existing =
                sensitivePrefs.getString(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED, null)
                    ?.let(persistence::localWithdrawalMarkerItemsOrNull)
                    .orEmpty()
            val marker = JSONArray(
                (existing + item).map(IntegratedConsentItem::wireValue),
            ).toString()
            val rawFencePersisted =
                item != IntegratedConsentItem.RAW_SOURCE_COLLECTION ||
                    stepLengthPrefs.edit()
                        .putBoolean(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED, true)
                        .commit()
            val markerPersisted = rawFencePersisted &&
                sensitivePrefs.edit()
                    .putString(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED, marker)
                    .commit()
            applyImmediateConsentWithdrawals(setOf(item))
            cancelIntegratedConsentControlCall()
            if (!markerPersisted) {
                speech.invalidate()
                integratedConsentSession.failClosed()
            }
        }
        postIntegratedConsentUiRefresh()
    }

    fun cancelIntegratedConsentControlCall() {
        synchronized(integratedConsentLock) {
            integratedConsentRequestGeneration += 1L
            integratedConsentCall?.cancel()
            integratedConsentBootstrapCall?.cancel()
            integratedConsentCall = null
            integratedConsentBootstrapCall = null
            integratedConsentRequestInFlight = false
        }
    }

    /** Pending control belongs to its original actor, including across policy migration. */
    private fun hasUnsettledIntegratedConsentControl(): Boolean =
        sensitivePrefs.isBlocked() ||
            pendingIntegratedConsentMutation != null ||
            integratedConsentSession.pendingMutationOrNull() != null ||
            sensitivePrefs.contains(PREF_PENDING_INTEGRATED_CONSENT_MUTATION) ||
            sensitivePrefs.contains(PREF_REJECTED_CONSENT_CAS_JOURNAL) ||
            sensitivePrefs.contains(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED) ||
            IntegratedConsentItem.entries.any { item ->
                integratedConsentSession.status(item) in setOf(
                    PurposeConsentSyncState.WITHDRAWAL_PENDING,
                    PurposeConsentSyncState.WITHDRAWAL_RETRY,
                )
            }

    private fun resetIntegratedConsentForReconsent(): Boolean {
        speech.invalidate()
        if (hasUnsettledIntegratedConsentControl()) return false
        val changed = integratedConsentSession.resetForPolicyReconsent()
        // False can mean an already-empty session, not only a refused reset.
        return changed || (
            integratedConsentSession.currentConfirmationOrNull() == null &&
                integratedConsentSession.pendingMutationOrNull() == null &&
                IntegratedConsentItem.entries.all {
                    integratedConsentSession.status(it) == PurposeConsentSyncState.UNCONFIRMED
                }
            )
    }

    fun reapplyActorBoundIntegratedConsent(retryCleanup: Boolean = true) {
        val process = GatewaySessionProcessCoordinator.snapshot()
        val session = process.session
        val confirmation = integratedConsentSession.currentConfirmationOrNull()
        val currentOwner = session != null && confirmation != null &&
            !process.storageBlocked && !process.deletionRecoveryOnly &&
            session.verificationState == GatewaySessionVerificationState.VERIFIED &&
            session.isUsableFor(session.actorId) &&
            session.actorId == actor.currentReporterActorId() &&
            permissionSessionPolicy.isAuthenticatedFor(session.actorId) &&
            integratedConsentConfirmedActorSha256 == integratedConsentActorSha256(session)
        val selections = if (currentOwner) {
            IntegratedConsentSelections(
                rawSourceCollection = integratedConsentSession.isAllowed(
                    IntegratedConsentItem.RAW_SOURCE_COLLECTION,
                ),
                automaticReporting = automaticReportCleanupReadyForCurrentActor() && integratedConsentSession.isAllowed(
                    IntegratedConsentItem.AUTOMATIC_REPORTING,
                ),
                mobileNetworkTransfer = integratedConsentSession.isAllowed(
                    IntegratedConsentItem.MOBILE_NETWORK_TRANSFER,
                ),
                trainingReuse = integratedConsentSession.isAllowed(
                    IntegratedConsentItem.TRAINING_REUSE,
                ),
            )
        } else IntegratedConsentSelections()
        if (permissionSessionPolicy.snapshot().rawCollectionConsentGranted != selections.rawSourceCollection) {
            speech.invalidate()
        }
        permissionSessionPolicy.applyIntegratedConsentSelections(selections)
        if (retryCleanup && !automaticReportCleanupReadyForCurrentActor()) retryPendingAutomaticReportCleanup()
    }

    private fun holdIntegratedConsentAuthorityForRefresh() {
        speech.invalidate()
        // Fence an older callback before closing local permits for this new authority read.
        cancelIntegratedConsentControlCall()
        speech.invalidate()
        integratedConsentSession.invalidateBackendAuthority()
        reports.withdrawAuthority()
        reports.cancelDrain()
        raw.cancelOriginalUploads()
        raw.cancelActiveUploadIfReady()
        reapplyActorBoundIntegratedConsent()
    }

    private fun integratedConsentHttpBlocked(): Boolean =
        activity.processingBlocked() ||
            IntegratedConsentItem.entries.any { item ->
                integratedConsentSession.status(item) ==
                    PurposeConsentSyncState.FAIL_CLOSED
            }

    private fun applyImmediateConsentWithdrawals(
        items: Set<IntegratedConsentItem>,
        recordLocalWithdrawal: Boolean = true,
    ) {
        if (IntegratedConsentItem.RAW_SOURCE_COLLECTION in items) speech.blockRawWithdrawal()
        if (items.isNotEmpty()) {
            val previousReceipt = integratedConsentSession
                .cachedConfirmationOrNull()
                ?.backendConsentReceiptSha256
            reports.cancelDrain()
            if (
                previousReceipt != null &&
                IntegratedConsentItem.AUTOMATIC_REPORTING in items
            ) {
                recordAutomaticCleanup(previousReceipt, integratedConsentConfirmedActorSha256)
            }
            // S21-50: an in-flight debug JPEG/metadata transfer stops with the RAW withdrawal.
            if (IntegratedConsentItem.RAW_SOURCE_COLLECTION in items) raw.cancelOriginalUploads()
            if (IntegratedConsentItem.RAW_SOURCE_COLLECTION in items) {
                raw.withdrawReceiptIfReady(previousReceipt)
            }
        }
        items.forEach { item ->
            if (recordLocalWithdrawal) integratedConsentSession.withdrawImmediately(item)
            when (item) {
                IntegratedConsentItem.RAW_SOURCE_COLLECTION -> {
                    speech.invalidate()
                    permissionSessionPolicy.setRawCollectionConsent(false)
                    raw.disableMetadataIfReady()
                    raw.blockFieldLogIfReady()
                }
                IntegratedConsentItem.AUTOMATIC_REPORTING -> {
                    reports.cancelAutomaticCalls()
                    reports.scheduleLegacyPurge()
                    permissionSessionPolicy.setAutomaticReportConsent(false)
                }
                IntegratedConsentItem.MOBILE_NETWORK_TRANSFER -> {
                    permissionSessionPolicy.setMobileNetworkPreference(
                        MobileNetworkPreference.WIFI_ONLY,
                    )
                    network.refreshFeaturePolicy()
                    if (network.isCellularIfReady()) {
                        network.cancelGatewayCalls()
                        reports.cancelAllCallsForMobileWithdrawal()
                    }
                }
                IntegratedConsentItem.TRAINING_REUSE ->
                    permissionSessionPolicy.setTrainingReuseConsent(false)
            }
        }
        postIntegratedConsentUiRefresh()
    }

    fun refreshIntegratedConsentFromServer() {
        holdIntegratedConsentAuthorityForRefresh()
        if (integratedConsentHttpBlocked()) return
        val gatewayOrigin = gateway.configuredOrigin() ?: return
        val gatewaySession = gateway.refreshSessionOrNull() ?: return
        val installationId = gateway.installationId() ?: return
        val controlSecret = getOrCreateIntegratedConsentControlSecret() ?: return
        startIntegratedConsentRequest(
            call = remote.fetchCurrentCall(
                gatewayBaseUrl = gatewayOrigin,
                session = gatewaySession,
                installationId = installationId,
                controlSecret = controlSecret,
            ),
            expectedSelections = null,
            expectedClientRevision = null,
            completeOnboarding = false,
            announce = false,
            expectedSession = gatewaySession,
            bootstrapOnMissing = true,
        )
    }

    private fun prepareIntegratedConsentReconsent(
        clientRevisionFloor: Long,
        expectedPreviousBackendReceiptSha256: String?,
        bootstrapReady: Boolean,
    ): Boolean {
        if (sensitivePrefs.isBlocked()) return false
        applyImmediateConsentWithdrawals(
            IntegratedConsentItem.entries.toSet(), recordLocalWithdrawal = false,
        )
        if (!resetIntegratedConsentForReconsent()) {
            speech.invalidate()
            integratedConsentSession.invalidate()
            return false
        }
        if (
            !stepLengthPrefs.edit()
                .putBoolean(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED, true)
                .commit()
        ) {
            speech.invalidate()
            integratedConsentSession.failClosed()
            return false
        }
        val stored = sensitivePrefs.edit()
            .remove(PREF_INTEGRATED_CONSENT_POLICY_VERSION)
            .remove(PREF_INTEGRATED_CONSENT_REVISION)
            .remove(PREF_INTEGRATED_CONSENT_RECEIPT_SHA256)
            .remove(PREF_INTEGRATED_CONSENT_BACKEND_RECEIPT_SHA256)
            .remove(PREF_INTEGRATED_CONSENT_GATEWAY_AUDIT_SHA256)
            .remove(PREF_INTEGRATED_CONSENT_ACTOR_SHA256)
            .remove(PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION)
            .remove(PREF_REPORT_PRIVACY_CONSENT_KEY)
            .remove(PREF_AUTOMATIC_REPORT_CONSENT_KEY)
            .remove(PREF_MOBILE_NETWORK_PREFERENCE_KEY)
            .remove(PREF_TRAINING_REUSE_CONSENT_KEY)
            .putLong(PREF_INTEGRATED_CONSENT_CLIENT_REVISION, clientRevisionFloor)
            .commit()
        if (!stored) {
            speech.invalidate()
            integratedConsentSession.failClosed()
            return false
        }
        pendingIntegratedConsentMutation = null
        integratedConsentDraft = IntegratedConsentSelections()
        integratedConsentClientRevision = clientRevisionFloor
        integratedConsentExpectedPreviousBackendReceiptSha256 =
            expectedPreviousBackendReceiptSha256
        integratedConsentConfirmedActorSha256 = null
        integratedConsentBootstrapReady = bootstrapReady
        permissionSessionPolicy.applyIntegratedConsentSelections(
            IntegratedConsentSelections(),
        )
        return true
    }

    private fun startIntegratedConsentBootstrap(
        gatewayOrigin: String,
        gatewaySession: GatewayFieldSession,
        installationId: String,
    ) {
        if (!gateway.isCurrentSession(gatewaySession)) return
        val processGeneration = GatewaySessionProcessCoordinator.snapshot().generation
        val localFloor = integratedConsentClientRevision.coerceAtLeast(0L)
        val localReceipt = integratedConsentSession.currentConfirmationOrNull()
            ?.backendConsentReceiptSha256
            ?: integratedConsentExpectedPreviousBackendReceiptSha256
        if (!prepareIntegratedConsentReconsent(localFloor, localReceipt, false)) return
        val call = remote.fetchBootstrapCall(
            gatewayBaseUrl = gatewayOrigin,
            session = gatewaySession,
            installationId = installationId,
        )
        val generation = synchronized(integratedConsentLock) {
            integratedConsentCall?.cancel()
            integratedConsentBootstrapCall?.cancel()
            integratedConsentRequestGeneration += 1L
            integratedConsentCall = null
            integratedConsentBootstrapCall = call
            integratedConsentRequestInFlight = true
            integratedConsentRequestGeneration
        }
        postIntegratedConsentUiRefresh()
        try {
            executor.execute {
                try {
                    val bootstrap = call.execute()
                    if (
                        !claimIntegratedConsentBootstrapCompletion(generation, call) ||
                        !isCurrentIntegratedConsentSession(gatewaySession, processGeneration)
                    ) return@execute
                    runOnUiThread {
                        if (
                            activity.destroyed() ||
                            !isCurrentIntegratedConsentSession(gatewaySession, processGeneration) ||
                            synchronized(integratedConsentLock) {
                                generation != integratedConsentRequestGeneration
                            }
                        ) return@runOnUiThread
                        if (
                            !prepareIntegratedConsentReconsent(
                                clientRevisionFloor = bootstrap.clientRevisionFloor,
                                expectedPreviousBackendReceiptSha256 =
                                    bootstrap.expectedPreviousBackendReceiptSha256,
                                bootstrapReady = true,
                            )
                        ) return@runOnUiThread
                        if (bootstrap.status == IntegratedConsentBootstrapStatus.READY) {
                            integratedConsentDraft = requireNotNull(bootstrap.selections)
                            persistIntegratedConsentDraft(announce = false)
                        } else {
                            ui.refresh()
                            ui.status(
                                "integratedConsent=reconsent_required policy=" +
                                    INTEGRATED_CONSENT_POLICY_VERSION,
                            )
                        }
                    }
                } catch (_: CancellationException) {
                    claimIntegratedConsentBootstrapCompletion(generation, call)
                    postIntegratedConsentUiRefresh()
                } catch (_: Exception) {
                    if (!claimIntegratedConsentBootstrapCompletion(generation, call)) {
                        return@execute
                    }
                    postIntegratedConsentUiRefresh()
                }
            }
        } catch (_: RejectedExecutionException) {
            call.cancel()
            claimIntegratedConsentBootstrapCompletion(generation, call)
            postIntegratedConsentUiRefresh()
        }
    }

    private fun startRejectedIntegratedConsentCasRecovery(
        gatewaySession: GatewayFieldSession,
        rejected: PendingIntegratedConsentMutation,
        recoveryAttempts: Int,
    ) {
        if (!isCurrentIntegratedConsentMutation(rejected, gatewaySession)) return
        val processGeneration = GatewaySessionProcessCoordinator.snapshot().generation
        // The old request is known rejected, but its withdrawal remains in the durable journal.
        holdIntegratedConsentAuthorityForRefresh()
        if (!isCurrentIntegratedConsentMutation(rejected, gatewaySession)) return
        val call = remote.fetchBootstrapCall(
            gatewayBaseUrl = gatewaySession.gatewayBaseUrl,
            session = gatewaySession,
            installationId = rejected.installationId,
        )
        val generation = synchronized(integratedConsentLock) {
            integratedConsentBootstrapCall?.cancel()
            integratedConsentRequestGeneration += 1L
            integratedConsentCall = null
            integratedConsentBootstrapCall = call
            integratedConsentRequestInFlight = true
            integratedConsentRequestGeneration
        }
        try {
            executor.execute {
                try {
                    val bootstrap = call.execute()
                    if (!claimIntegratedConsentBootstrapCompletion(generation, call) ||
                        !isCurrentIntegratedConsentSession(gatewaySession, processGeneration)
                    ) return@execute
                    val receipt = bootstrap.expectedPreviousBackendReceiptSha256
                    val choices = bootstrap.selections
                    val cached = integratedConsentSession.cachedConfirmationOrNull()
                    if (bootstrap.status != IntegratedConsentBootstrapStatus.READY ||
                        bootstrap.source != IntegratedConsentBootstrapSource.CURRENT_CONSENT ||
                        bootstrap.installationId != rejected.installationId ||
                        receipt == null || receipt != bootstrap.sourceReceiptSha256 ||
                        choices == null ||
                        bootstrap.clientRevisionFloor < (cached?.clientRevision ?: 0L) ||
                        bootstrap.clientRevisionFloor == Long.MAX_VALUE
                    ) {
                        integratedConsentSession.markPendingRetry()
                        postIntegratedConsentUiRefresh()
                        return@execute
                    }
                    val existingJournalRaw = sensitivePrefs.getString(
                        PREF_REJECTED_CONSENT_CAS_JOURNAL, null,
                    )
                    val existingJournal = existingJournalRaw?.let {
                        persistence.rejectedConsentCasJournalOrNull(it, rejected)
                    }
                    if (existingJournalRaw != null && existingJournal == null) {
                        speech.invalidate()
                        integratedConsentSession.failClosed()
                        return@execute
                    }
                    val withdrawals = (existingJournal?.cumulativeDenials ?: emptySet()) +
                        rejected.deniedItems() + integratedConsentSession.localWithdrawalItems()
                    val successor = rejected.successorAfterRejectedCas(
                        backendSelections = choices,
                        backendReceiptSha256 = receipt,
                        backendClientRevisionFloor = bootstrap.clientRevisionFloor,
                        clientRevision = bootstrap.clientRevisionFloor + 1L,
                        withdrawalItems = withdrawals,
                        createdAtEpochMs = wallClockMs().coerceAtLeast(1L),
                    )
                    val transition = RejectedConsentCasSupersession(
                        original = rejected,
                        backendSelections = choices,
                        backendReceiptSha256 = receipt,
                        backendClientRevisionFloor = bootstrap.clientRevisionFloor,
                        successor = successor,
                    )
                    val journal = RejectedConsentCasRecoveryJournal.next(
                        existingJournal, transition,
                    )
                    val journalJson = persistence.rejectedConsentCasJournalJson(journal)
                    if (journalJson.toByteArray(Charsets.UTF_8).size >
                        RejectedConsentCasJournalCodec.MAX_JOURNAL_BYTES
                    ) {
                        integratedConsentSession.markPendingRetry()
                        return@execute
                    }
                    val result = GatewaySessionProcessCoordinator.withCurrentGeneralSession(
                        processGeneration, gatewaySession,
                    ) {
                        synchronized(integratedConsentLock) {
                            if (generation != integratedConsentRequestGeneration ||
                                !integratedConsentSession.isPendingMutationCurrent(rejected)
                            ) return@synchronized IntegratedConsentSubmissionResult.REJECTED
                            integratedConsentSession.supersedeRejectedCasMutation(
                                transition,
                                persist = {
                                    sensitivePrefs.edit()
                                        .putLong(
                                            PREF_INTEGRATED_CONSENT_CLIENT_REVISION,
                                            successor.clientRevision,
                                        )
                                        .putString(
                                            PREF_PENDING_INTEGRATED_CONSENT_MUTATION,
                                            persistence.pendingIntegratedConsentMutationJson(successor),
                                        )
                                        .putString(
                                            PREF_REJECTED_CONSENT_CAS_JOURNAL,
                                            journalJson,
                                        )
                                        .remove(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED)
                                        .commit()
                                },
                            ).also { outcome ->
                                if (outcome == IntegratedConsentSubmissionResult.APPLIED) {
                                    pendingIntegratedConsentMutation = successor
                                    integratedConsentClientRevision = successor.clientRevision
                                    integratedConsentDraft = successor.desiredSelections
                                }
                            }
                        }
                    }
                    if (result == IntegratedConsentSubmissionResult.STORAGE_FAILED) {
                        speech.invalidate()
                        integratedConsentSession.failClosed()
                    } else if (result == IntegratedConsentSubmissionResult.APPLIED &&
                        isCurrentIntegratedConsentSession(gatewaySession, processGeneration)
                    ) {
                        retryPendingIntegratedConsentMutation(
                            casRecoveryAttempts = recoveryAttempts + 1,
                        )
                    }
                    postIntegratedConsentUiRefresh()
                } catch (_: CancellationException) {
                    claimIntegratedConsentBootstrapCompletion(generation, call)
                } catch (_: Exception) {
                    claimIntegratedConsentBootstrapCompletion(generation, call)
                    integratedConsentSession.markPendingRetry()
                }
            }
        } catch (_: RejectedExecutionException) {
            call.cancel()
            claimIntegratedConsentBootstrapCompletion(generation, call)
            integratedConsentSession.markPendingRetry()
        }
    }

    private fun claimIntegratedConsentBootstrapCompletion(
        generation: Long,
        call: CancellableNetworkCall<IntegratedConsentBootstrap>,
    ): Boolean = synchronized(integratedConsentLock) {
        if (
            generation != integratedConsentRequestGeneration ||
            integratedConsentBootstrapCall !== call
        ) {
            false
        } else {
            integratedConsentBootstrapCall = null
            integratedConsentRequestInFlight = false
            true
        }
    }

    private fun getOrCreateIntegratedConsentControlSecret(): String? {
        if (sensitivePrefs.isBlocked()) return null
        val existing =
            sensitivePrefs.getString(
                PREF_INTEGRATED_CONSENT_CONTROL_SECRET,
                null,
            )
        if (existing != null && INTEGRATED_CONSENT_CONTROL_SECRET.matches(existing)) {
            return existing
        }
        if (sensitivePrefs.contains(PREF_INTEGRATED_CONSENT_CONTROL_SECRET)) {
            sensitivePrefs.failClosed()
            speech.invalidate()
            integratedConsentSession.failClosed()
            return null
        }
        val random = ByteArray(32)
        randomBytes(random)
        val generated = random.joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xff)
        }
        return if (
            sensitivePrefs.edit()
                .putString(PREF_INTEGRATED_CONSENT_CONTROL_SECRET, generated)
                .commit()
        ) {
            generated
        } else {
            null
        }
    }

    private fun existingIntegratedConsentControlSecretOrNull(): String? =
        sensitivePrefs.getString(
            PREF_INTEGRATED_CONSENT_CONTROL_SECRET,
            null,
        )?.takeIf(INTEGRATED_CONSENT_CONTROL_SECRET::matches)

    private fun integratedConsentActorSha256(session: GatewayFieldSession): String =
        sha256Hex(
            (
                "walksafe.integrated-consent-actor.v1\u0000${session.actorId}\u0000" +
                    (session.lease.backendAccountGeneration?.toString() ?: "legacy")
                )
                .toByteArray(Charsets.UTF_8),
        )

    fun persistIntegratedConsentDraft(announce: Boolean) {
        if (sensitivePrefs.isBlocked()) {
            speech.invalidate()
            integratedConsentSession.failClosed()
            ui.status("integratedConsent=blocked:sensitive_storage")
            return
        }
        if (integratedConsentHttpBlocked()) {
            ui.status("integratedConsent=blocked:account_deletion")
            return
        }
        val gatewayOrigin = gateway.configuredOrigin() ?: run {
            ui.status("integratedConsent=blocked:gateway_origin_unavailable")
            if (announce) {
                ui.speakInteraction("현재 동의 선택을 저장할 수 없어 새 허용 항목을 열지 않았습니다.")
            }
            return
        }
        val gatewaySession = gateway.sessionOrNull(
            reason = "integrated_consent",
            speak = announce,
        ) ?: run {
            ui.status("integratedConsent=blocked:gateway_session_required")
            return
        }
        val installationId = gateway.installationId() ?: run {
            ui.status("integratedConsent=blocked:installation_storage")
            if (announce) {
                ui.speakInteraction("이 기기의 동의 설정을 안전하게 저장하지 못해 동의를 진행하지 않습니다.")
            }
            return
        }
        val controlSecret = getOrCreateIntegratedConsentControlSecret() ?: run {
            ui.status("integratedConsent=blocked:control_secret_storage")
            if (announce) {
                ui.speakInteraction("이 기기의 동의 설정을 안전하게 저장하지 못해 선택을 전송하지 않습니다.")
            }
            return
        }
        val actorSha256 = integratedConsentActorSha256(gatewaySession)
        val currentConfirmation = integratedConsentSession.currentConfirmationOrNull()
        if (
            currentConfirmation != null &&
            integratedConsentConfirmedActorSha256 != actorSha256
        ) {
            refreshIntegratedConsentFromServer()
            return
        }
        if (currentConfirmation == null && !integratedConsentBootstrapReady) {
            ui.status("integratedConsent=blocked:bootstrap_required")
            if (announce) {
                ui.speakInteraction("서버의 현재 동의 상태를 먼저 확인해야 합니다.")
            }
            refreshIntegratedConsentFromServer()
            return
        }
        val expectedPreviousBackendReceiptSha256 =
            currentConfirmation?.backendConsentReceiptSha256
                ?: integratedConsentExpectedPreviousBackendReceiptSha256
        if (
            integratedConsentClientRevision > 0L &&
            expectedPreviousBackendReceiptSha256 == null
        ) {
            ui.status("integratedConsent=blocked:receipt_cas_required")
            return
        }
        if (integratedConsentClientRevision == Long.MAX_VALUE) {
            ui.status("integratedConsent=blocked:client_revision_exhausted")
            return
        }
        val clientRevision = integratedConsentClientRevision + 1L
        val recoverySelections = integratedConsentSession.withdrawalRecoverySelectionsOrNull()
        val selections = recoverySelections ?: integratedConsentDraft
        val requestId = "consent_" + sha256Hex(
            (
                "FP013|$installationId|$clientRevision|" +
                    "$expectedPreviousBackendReceiptSha256|$selections"
            ).toByteArray(),
        )
        val confirmedSelections = persistence.persistedIntegratedConsentSelections()
        val withdrawalItems = IntegratedConsentItem.entries
            .filterTo(mutableSetOf()) { item ->
                (confirmedSelections.isGranted(item) || item in integratedConsentSession.localWithdrawalItems()) &&
                    !selections.isGranted(item)
            }
        val mutation = PendingIntegratedConsentMutation(
            installationId = installationId,
            actorSha256 = actorSha256,
            requestId = requestId,
            policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
            clientRevision = clientRevision,
            previousServerRevision =
                sensitivePrefs.getLong(PREF_INTEGRATED_CONSENT_REVISION, 0L)
                    .coerceAtLeast(0L),
            expectedPreviousBackendReceiptSha256 =
                expectedPreviousBackendReceiptSha256,
            desiredSelections = selections,
            withdrawalItems = withdrawalItems,
            createdAtEpochMs = wallClockMs().coerceAtLeast(1L),
        )
        // submitPendingMutation can publish a deny before its dispatch callback runs.
        if (IntegratedConsentItem.RAW_SOURCE_COLLECTION in withdrawalItems) speech.blockRawWithdrawal()
        val submission = integratedConsentSession.submitPendingMutation(
            mutation = mutation,
            persist = { accepted ->
                sensitivePrefs.edit()
                    .putLong(PREF_INTEGRATED_CONSENT_CLIENT_REVISION, accepted.clientRevision)
                    .putString(
                        PREF_PENDING_INTEGRATED_CONSENT_MUTATION,
                        persistence.pendingIntegratedConsentMutationJson(accepted),
                    )
                    .remove(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED)
                    .commit()
            },
            dispatch = dispatch@{ accepted ->
                if (!isCurrentIntegratedConsentMutation(accepted, gatewaySession)) return@dispatch false
                // Publish the durable request before cancellation/transport reservation can fail.
                integratedConsentClientRevision = accepted.clientRevision
                pendingIntegratedConsentMutation = accepted
                if (recoverySelections != null) integratedConsentDraft = accepted.desiredSelections
                synchronized(integratedConsentLock) {
                    integratedConsentRequestGeneration += 1L
                    integratedConsentCall?.cancel()
                    integratedConsentBootstrapCall?.cancel()
                    integratedConsentCall = null
                    integratedConsentBootstrapCall = null
                    integratedConsentRequestInFlight = false
                }
                applyImmediateConsentWithdrawals(withdrawalItems)
                startIntegratedConsentRequest(
                    call = remote.saveCall(
                        gatewayBaseUrl = gatewayOrigin,
                        session = gatewaySession,
                        installationId = installationId,
                        controlSecret = controlSecret,
                        requestId = requestId,
                        clientRevision = clientRevision,
                        selections = selections,
                        expectedPreviousBackendReceiptSha256 =
                            mutation.expectedPreviousBackendReceiptSha256,
                    ),
                    expectedSelections = selections,
                    expectedClientRevision = clientRevision,
                    completeOnboarding =
                        actor.isIntegratedConsentOnboardingStage(),
                    announce = announce,
                    expectedSession = gatewaySession,
                    bootstrapOnCasConflict = true,
                    expectedMutation = accepted,
                )
            },
        )
        when (submission) {
            IntegratedConsentSubmissionResult.REJECTED -> {
                ui.status("integratedConsent=blocked:pending_withdrawal_or_candidate")
                if (announce) ui.speakInteraction("진행 중인 철회를 완료한 뒤 다시 허용해 주세요.")
            }
            IntegratedConsentSubmissionResult.STORAGE_FAILED -> {
                applyImmediateConsentWithdrawals(withdrawalItems)
                speech.invalidate()
                integratedConsentSession.failClosed()
                ui.status("integratedConsent=blocked:mutation_storage")
                if (announce) ui.speakInteraction("철회 상태를 안전하게 저장하지 못해 개인정보 기능을 잠갔습니다.")
            }
            IntegratedConsentSubmissionResult.DISPATCH_FAILED -> {
                ui.status("integratedConsent=pending:retry")
                postIntegratedConsentUiRefresh()
            }
            else -> Unit
        }
    }

    private fun retryPendingIntegratedConsentMutation(
        announce: Boolean = false,
        casRecoveryAttempts: Int = 0,
    ) {
        if (integratedConsentHttpBlocked()) return
        val mutation = pendingIntegratedConsentMutation ?: return
        val gatewayOrigin = gateway.configuredOrigin() ?: run {
            integratedConsentSession.markPendingRetry()
            return
        }
        val gatewaySession = gateway.sessionOrNull(
            reason = "integrated_consent_retry",
            speak = false,
        ) ?: run {
            integratedConsentSession.markPendingRetry()
            return
        }
        if (
            mutation.actorSha256 !=
            integratedConsentActorSha256(gatewaySession)
        ) {
            startIntegratedConsentBootstrap(
                gatewayOrigin = gatewayOrigin,
                gatewaySession = gatewaySession,
                installationId = mutation.installationId,
            )
            return
        }
        val controlSecret = existingIntegratedConsentControlSecretOrNull() ?: run {
            speech.invalidate()
            integratedConsentSession.failClosed()
            return
        }
        val submission = integratedConsentSession.submitPendingMutation(
            mutation = mutation,
            retry = true,
            persist = { true }, // This exact pending request is already durable.
            dispatch = dispatch@{ accepted ->
                if (!isCurrentIntegratedConsentMutation(accepted, gatewaySession)) return@dispatch false
                integratedConsentDraft = mutation.desiredSelections
                integratedConsentClientRevision =
                    maxOf(integratedConsentClientRevision, mutation.clientRevision)
                applyImmediateConsentWithdrawals(mutation.withdrawalItems)
                startIntegratedConsentRequest(
                    call = remote.saveCall(
                        gatewayBaseUrl = gatewayOrigin,
                        session = gatewaySession,
                        installationId = mutation.installationId,
                        controlSecret = controlSecret,
                        requestId = mutation.requestId,
                        clientRevision = mutation.clientRevision,
                        selections = mutation.desiredSelections,
                        expectedPreviousBackendReceiptSha256 =
                            mutation.expectedPreviousBackendReceiptSha256,
                    ),
                    expectedSelections = mutation.desiredSelections,
                    expectedClientRevision = mutation.clientRevision,
                    completeOnboarding = false,
                    announce = announce,
                    expectedSession = gatewaySession,
                    bootstrapOnCasConflict = true,
                    expectedMutation = accepted,
                    casRecoveryAttempts = casRecoveryAttempts,
                )
            },
        )
        if (submission == IntegratedConsentSubmissionResult.REJECTED) {
            ui.status("integratedConsent=blocked:retry_candidate")
        } else if (submission == IntegratedConsentSubmissionResult.DISPATCH_FAILED) {
            postIntegratedConsentUiRefresh()
        }
    }

    private fun isCurrentIntegratedConsentMutation(
        mutation: PendingIntegratedConsentMutation,
        expectedSession: GatewayFieldSession,
    ): Boolean =
        gateway.isCurrentSession(expectedSession) &&
            mutation.actorSha256 == integratedConsentActorSha256(expectedSession) &&
            !integratedConsentHttpBlocked() &&
            integratedConsentSession.isPendingMutationCurrent(mutation)

    private fun startIntegratedConsentRequest(
        call: CancellableNetworkCall<IntegratedConsentConfirmation?>,
        expectedSelections: IntegratedConsentSelections?,
        expectedClientRevision: Long?,
        completeOnboarding: Boolean,
        announce: Boolean,
        expectedSession: GatewayFieldSession,
        bootstrapOnMissing: Boolean = false,
        bootstrapOnCasConflict: Boolean = false,
        expectedMutation: PendingIntegratedConsentMutation? = null,
        casRecoveryAttempts: Int = 0,
    ): Boolean {
        if (
            integratedConsentHttpBlocked() ||
            !gateway.isCurrentSession(expectedSession) ||
            (expectedMutation != null && !isCurrentIntegratedConsentMutation(expectedMutation, expectedSession))
        ) {
            call.cancel()
            return false
        }
        val processGeneration = GatewaySessionProcessCoordinator.snapshot().generation
        val generation = synchronized(integratedConsentLock) {
            integratedConsentCall?.cancel()
            integratedConsentBootstrapCall?.cancel()
            integratedConsentRequestGeneration += 1L
            integratedConsentCall = call
            integratedConsentBootstrapCall = null
            integratedConsentRequestInFlight = true
            integratedConsentRequestGeneration
        }
        if (expectedMutation != null && !isCurrentIntegratedConsentMutation(expectedMutation, expectedSession)) {
            call.cancel()
            claimIntegratedConsentCallCompletion(generation, call)
            return false
        }
        postIntegratedConsentUiRefresh()
        try {
            executor.execute {
                try {
                    if (
                        !isCurrentIntegratedConsentSession(expectedSession, processGeneration) ||
                        integratedConsentHttpBlocked() ||
                        (expectedMutation != null && !isCurrentIntegratedConsentMutation(expectedMutation, expectedSession)) ||
                        synchronized(integratedConsentLock) {
                            generation != integratedConsentRequestGeneration || integratedConsentCall !== call
                        }
                    ) {
                        call.cancel()
                        claimIntegratedConsentCallCompletion(generation, call)
                        return@execute
                    }
                    val confirmation = call.execute()
                    if (
                        !claimIntegratedConsentCallCompletion(generation, call) ||
                        !isCurrentIntegratedConsentSession(expectedSession, processGeneration)
                    ) {
                        return@execute
                    }
                    if (confirmation == null && bootstrapOnMissing) {
                        startIntegratedConsentBootstrap(
                            gatewayOrigin = expectedSession.gatewayBaseUrl,
                            gatewaySession = expectedSession,
                            installationId =
                                gateway.installationId()
                                    ?: return@execute,
                        )
                        return@execute
                    }
                    if (
                        expectedClientRevision == null &&
                        integratedConsentClientRevision > 0L &&
                        (
                            confirmation == null ||
                                confirmation.clientRevision <
                                integratedConsentClientRevision
                        )
                    ) {
                        postIntegratedConsentUiRefresh()
                        if (pendingIntegratedConsentMutation != null) {
                            retryPendingIntegratedConsentMutation()
                        } else {
                            startIntegratedConsentBootstrap(
                                gatewayOrigin = expectedSession.gatewayBaseUrl,
                                gatewaySession = expectedSession,
                                installationId =
                                    gateway.installationId()
                                        ?: return@execute,
                            )
                        }
                        return@execute
                    }
                    if (
                        !isCurrentIntegratedConsentSession(expectedSession, processGeneration) ||
                        synchronized(integratedConsentLock) {
                            generation != integratedConsentRequestGeneration
                        }
                    ) return@execute
                    val authorityGuard = object : IntegratedConsentAuthorityGuard {
                        override fun <T : Any> withCurrent(action: () -> T): T? =
                            GatewaySessionProcessCoordinator.withCurrentGeneralSession(
                                processGeneration, expectedSession, action,
                            )
                    }
                    val applied = GatewaySessionProcessCoordinator.withCurrentGeneralSession(
                        processGeneration, expectedSession,
                    ) {
                        synchronized(integratedConsentLock) {
                            generation == integratedConsentRequestGeneration &&
                                confirmation != null &&
                                (
                                    expectedClientRevision == null ||
                                        confirmation.clientRevision == expectedClientRevision
                                ) &&
                                (
                                    expectedSelections == null ||
                                        confirmation.selections == expectedSelections
                                ) &&
                                applyIntegratedConsentConfirmation(
                                    confirmation,
                                    integratedConsentActorSha256(expectedSession),
                                    authorityGuard,
                                )
                        }
                    } == true
                    if (applied && isCurrentIntegratedConsentSession(expectedSession, processGeneration)) {
                        postIntegratedConsentUiRefresh()
                        ui.schedulePostWalkTransfers()
                    }
                    val appliedConfirmation = confirmation.takeIf { applied }
                    if (
                        !isCurrentIntegratedConsentSession(expectedSession, processGeneration) ||
                        synchronized(integratedConsentLock) {
                            generation != integratedConsentRequestGeneration
                        }
                    ) {
                        if (appliedConfirmation != null) {
                            prepareIntegratedConsentReconsent(
                                clientRevisionFloor =
                                    appliedConfirmation.clientRevision,
                                expectedPreviousBackendReceiptSha256 = null,
                                bootstrapReady = false,
                            )
                        }
                        return@execute
                    }
                    runOnUiThread {
                        if (
                            activity.destroyed() ||
                            !isCurrentIntegratedConsentSession(expectedSession, processGeneration) ||
                            !activity.currentLeaseIsCurrent() ||
                            synchronized(integratedConsentLock) {
                                generation != integratedConsentRequestGeneration
                            }
                        ) return@runOnUiThread
                        if (appliedConfirmation == null) {
                            ui.refresh()
                            if (announce) {
                                ui.speakInteraction(
                                    "서버에서 현재 동의 선택을 확인하지 못해 기능을 열지 않습니다.",
                                )
                            }
                            return@runOnUiThread
                        }
                        // A fresh receipt supplies the CAS base for an unsaved withdrawal.
                        // Retry that denial before exposing completion or restored permissions.
                        if (integratedConsentSession.withdrawalRecoverySelectionsOrNull() != null) {
                            persistIntegratedConsentDraft(announce = false)
                            return@runOnUiThread
                        }
                        if (completeOnboarding) {
                            ui.completeFirstRunIfReady(
                                appliedConfirmation,
                            )
                        }
                        ui.status(
                            "integratedConsent=confirmed " +
                                "version=${appliedConfirmation.policyVersion} " +
                                "revision=${appliedConfirmation.revision}",
                        )
                        if (announce) {
                            ui.speakInteraction("네 가지 동의 선택을 서버에 저장하고 확인했습니다.")
                        }
                    }
                } catch (_: CancellationException) {
                    if (claimIntegratedConsentCallCompletion(generation, call)) {
                        postIntegratedConsentUiRefresh()
                    }
                } catch (error: Exception) {
                    val current = claimIntegratedConsentCallCompletion(generation, call)
                    if (!current) return@execute
                    if (
                        (bootstrapOnMissing || bootstrapOnCasConflict) &&
                        isCurrentIntegratedConsentSession(expectedSession, processGeneration) &&
                        error is IntegratedConsentHttpException &&
                        error.statusCode == 409 &&
                        (
                            (
                                bootstrapOnMissing &&
                                    error.serverCode in setOf(
                                        "integrated_consent_reconsent_required",
                                        "integrated_consent_actor_reconsent_required",
                                    ) &&
                                    error.requiredPolicyVersion ==
                                    INTEGRATED_CONSENT_POLICY_VERSION
                                ) ||
                                (
                                    bootstrapOnCasConflict &&
                                        error.serverCode ==
                                        "privacy_consent_previous_receipt_conflict"
                                    )
                            )
                    ) {
                        if (bootstrapOnCasConflict &&
                            error.serverCode == "privacy_consent_previous_receipt_conflict"
                        ) {
                            if (expectedMutation != null &&
                                casRecoveryAttempts < MAX_AUTOMATIC_REJECTED_CAS_RECOVERIES
                            ) {
                                startRejectedIntegratedConsentCasRecovery(
                                    expectedSession, expectedMutation, casRecoveryAttempts,
                                )
                            } else {
                                holdIntegratedConsentAuthorityForRefresh()
                                integratedConsentSession.markPendingRetry()
                            }
                        } else {
                            startIntegratedConsentBootstrap(
                                gatewayOrigin = expectedSession.gatewayBaseUrl,
                                gatewaySession = expectedSession,
                                installationId =
                                    gateway.installationId()
                                        ?: return@execute,
                            )
                        }
                        return@execute
                    }
                    if (pendingIntegratedConsentMutation != null) {
                        integratedConsentSession.markPendingRetry()
                    }
                    runOnUiThread {
                        if (
                            activity.destroyed() ||
                            !isCurrentIntegratedConsentSession(expectedSession, processGeneration) ||
                            !activity.currentLeaseIsCurrent() ||
                            synchronized(integratedConsentLock) {
                                generation != integratedConsentRequestGeneration
                            }
                        ) return@runOnUiThread
                        ui.refresh()
                        ui.status(
                            "integratedConsent=blocked:server_confirmation_failed",
                        )
                        if (announce) {
                            ui.speakInteraction("동의 선택 저장에 실패해 새 허용 항목을 열지 않습니다.")
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            call.cancel()
            claimIntegratedConsentCallCompletion(generation, call)
            integratedConsentSession.markPendingRetry()
            postIntegratedConsentUiRefresh()
            return false
        }
        return true
    }

    private fun claimIntegratedConsentCallCompletion(
        generation: Long,
        call: CancellableNetworkCall<IntegratedConsentConfirmation?>,
    ): Boolean = synchronized(integratedConsentLock) {
        if (
            generation != integratedConsentRequestGeneration ||
            integratedConsentCall !== call
        ) {
            false
        } else {
            integratedConsentCall = null
            integratedConsentRequestInFlight = false
            true
        }
    }

    private fun applyIntegratedConsentConfirmation(
        confirmation: IntegratedConsentConfirmation,
        expectedActorSha256: String,
        authorityGuard: IntegratedConsentAuthorityGuard,
    ): Boolean {
        if (sensitivePrefs.isBlocked()) return false
        if (!INTEGRATED_CONSENT_RECEIPT_SHA256.matches(expectedActorSha256)) return false
        if (confirmation.clientRevision < integratedConsentClientRevision) return false
        val previousReceipt = integratedConsentSession
            .cachedConfirmationOrNull()
            ?.backendConsentReceiptSha256
        val previousRawConfirmation = integratedConsentSession.cachedConfirmationOrNull()
        val previousConfirmedActorSha256 = integratedConsentConfirmedActorSha256
        val pending = pendingIntegratedConsentMutation
        if (pending != null && pending.actorSha256 != expectedActorSha256) return false
        val exactPending =
            pending != null &&
                pending.isExactNewerConfirmation(confirmation)
        val evaluation =
            if (exactPending) {
                integratedConsentSession.evaluateExactPendingConfirmation(confirmation)
            } else {
                integratedConsentSession.evaluateConfirmation(confirmation)
            }
        if (!speech.prepareConfirmationApply(
                confirmation, evaluation, previousRawConfirmation,
                {
                    integratedConsentSession.currentConfirmationOrNull(
                        setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
                    )
                },
            )
        ) {
            if (
                evaluation ==
                IntegratedConsentApplyResult.IDENTITY_CONFLICT_FAIL_CLOSED ||
                evaluation ==
                IntegratedConsentApplyResult.REVISION_CONFLICT_FAIL_CLOSED
            ) {
                speech.invalidate()
                integratedConsentSession.failClosed()
            }
            return false
        }
        if (
            !confirmation.selections.rawSourceCollection &&
            !stepLengthPrefs.edit()
                .putBoolean(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED, true)
                .commit()
        ) {
            speech.invalidate()
            integratedConsentSession.failClosed()
            return false
        }
        val editor = sensitivePrefs.edit()
            .putString(
                PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION,
                persistence.integratedConsentConfirmationJson(confirmation),
            )
            .putString(
                PREF_INTEGRATED_CONSENT_POLICY_VERSION,
                confirmation.policyVersion,
            )
            .putLong(PREF_INTEGRATED_CONSENT_REVISION, confirmation.revision)
            .putLong(
                PREF_INTEGRATED_CONSENT_CLIENT_REVISION,
                confirmation.clientRevision,
            )
            .putString(
                PREF_INTEGRATED_CONSENT_BACKEND_RECEIPT_SHA256,
                confirmation.backendConsentReceiptSha256,
            )
            .putString(
                PREF_INTEGRATED_CONSENT_GATEWAY_AUDIT_SHA256,
                confirmation.gatewayAuditRecordSha256,
            )
            .putString(PREF_INTEGRATED_CONSENT_ACTOR_SHA256, expectedActorSha256)
            .remove(PREF_INTEGRATED_CONSENT_RECEIPT_SHA256)
            .putBoolean(
                PREF_REPORT_PRIVACY_CONSENT_KEY,
                confirmation.selections.rawSourceCollection,
            )
            .putBoolean(
                PREF_AUTOMATIC_REPORT_CONSENT_KEY,
                confirmation.selections.automaticReporting,
            )
            .putString(
                PREF_MOBILE_NETWORK_PREFERENCE_KEY,
                if (confirmation.selections.mobileNetworkTransfer) {
                    MobileNetworkPreference.ALLOW_CELLULAR.wireValue
                } else {
                    MobileNetworkPreference.WIFI_ONLY.wireValue
                },
            )
            .putBoolean(
                PREF_TRAINING_REUSE_CONSENT_KEY,
                confirmation.selections.trainingReuse,
            )
        if (exactPending) editor
            .remove(PREF_PENDING_INTEGRATED_CONSENT_MUTATION)
            .remove(PREF_REJECTED_CONSENT_CAS_JOURNAL)
        if (!editor.commit()) {
            speech.invalidate()
            integratedConsentSession.failClosed()
            return false
        }
        if (
            confirmation.selections.rawSourceCollection &&
            exactPending &&
            !stepLengthPrefs.edit().remove(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED).commit()
        ) {
            speech.invalidate()
            integratedConsentSession.failClosed()
            return false
        }
        // S1617-14-F2: keep the earlier RAW receipt's segments until its continue/revoke decision
        // runs on the raw executor, even if a queued capture already sees the new receipt.
        if (previousReceipt != null && previousReceipt != confirmation.backendConsentReceiptSha256) {
            raw.holdReceiptUntilDecidedIfReady(previousReceipt)
        }
        if (exactPending) {
            check(integratedConsentSession.applyExactPendingConfirmation(confirmation, authorityGuard))
            pendingIntegratedConsentMutation = null
        } else {
            val applied = integratedConsentSession.applyConfirmation(confirmation, authorityGuard)
            if (
                applied != IntegratedConsentApplyResult.APPLIED &&
                applied != IntegratedConsentApplyResult.DUPLICATE
            ) return false
        }
        integratedConsentClientRevision = confirmation.clientRevision
        integratedConsentExpectedPreviousBackendReceiptSha256 =
            confirmation.backendConsentReceiptSha256
        integratedConsentBootstrapReady = true
        integratedConsentConfirmedActorSha256 = expectedActorSha256
        if (exactPending && confirmation.selections.rawSourceCollection) {
            raw.resetFieldLogAfterConfirmedConsentIfActive()
        }
        val effectiveSelections = if (pending != null && !exactPending) {
            IntegratedConsentItem.entries.filter {
                !pending.desiredSelections.isGranted(it) || it in pending.withdrawalItems
            }.fold(confirmation.selections) { selections, item ->
                selections.withDecision(item, false)
            }
        } else {
            confirmation.selections
        }
        if (!effectiveSelections.automaticReporting) {
            recordAutomaticCleanup(previousReceipt ?: confirmation.backendConsentReceiptSha256,
                previousConfirmedActorSha256 ?: expectedActorSha256)
            reports.scheduleLegacyPurge()
        }
        if (!effectiveSelections.rawSourceCollection) {
            raw.blockFieldLog()
        }
        // Re-delivery of the exact current confirmation for the same actor and receipt keeps
        // in-flight report work; any other confirmation still invalidates every report permit.
        val unchangedReportAuthority =
            !exactPending &&
                evaluation == IntegratedConsentApplyResult.DUPLICATE &&
                previousReceipt == confirmation.backendConsentReceiptSha256 &&
                previousConfirmedActorSha256 == expectedActorSha256
        reports.applyConfirmationAuthority(unchangedReportAuthority, authorityGuard)
        reapplyActorBoundIntegratedConsent()
        integratedConsentDraft =
            if (pending != null && !exactPending) pending.desiredSelections
            else confirmation.selections
        if (!effectiveSelections.rawSourceCollection) raw.disableMetadataIfReady()
        if (previousReceipt != confirmation.backendConsentReceiptSha256) {
            reports.invalidateExplicitReport()
            reports.cancelDrain()
            raw.confirmedReceiptChangedIfReady(
                previousReceipt, previousRawConfirmation, confirmation,
                effectiveSelections.rawSourceCollection,
            )
        }
        return true
    }

    private fun isCurrentIntegratedConsentSession(
        session: GatewayFieldSession,
        processGeneration: Long,
    ): Boolean =
        GatewaySessionProcessCoordinator.snapshot().let { snapshot ->
            snapshot.generation == processGeneration &&
                snapshot.session === session &&
                gateway.isCurrentSession(session)
        }

    fun restoreConsentState() {
        val legacyFloorRestored =
            restoreLegacyIntegratedConsentRevisionFloor()
        val restoredPendingRaw = sensitivePrefs.getString(
            PREF_PENDING_INTEGRATED_CONSENT_MUTATION, null,
        )
        val restoredPending = restoredPendingRaw?.let(persistence::pendingIntegratedConsentMutationOrNull)
        val restoredCasRaw = sensitivePrefs.getString(PREF_REJECTED_CONSENT_CAS_JOURNAL, null)
        val restoredCasJournal = restoredCasRaw?.let {
            persistence.rejectedConsentCasJournalOrNull(it, restoredPending)
        }
        if ((restoredPendingRaw != null && restoredPending == null) ||
            (restoredCasRaw != null && restoredCasJournal == null)
        ) integratedConsentSession.failClosed()
        sensitivePrefs.getString(PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION, null)
            ?.let { raw ->
                val confirmation = persistence.integratedConsentConfirmationOrNull(raw)
                val installationId =
                    gateway.installationId()
                val controlSecret = existingIntegratedConsentControlSecretOrNull()
                val legacyPolicyVersion =
                    sensitivePrefs.getString(
                        PREF_INTEGRATED_CONSENT_POLICY_VERSION,
                        null,
                    )
                val legacyRevision =
                    sensitivePrefs.getLong(PREF_INTEGRATED_CONSENT_REVISION, 0L)
                val legacyClientRevision =
                    sensitivePrefs.getLong(
                        PREF_INTEGRATED_CONSENT_CLIENT_REVISION,
                        0L,
                    )
                val legacySelections = persistence.persistedIntegratedConsentSelections()
                val legacyReceipt =
                    sensitivePrefs.getString(
                        PREF_INTEGRATED_CONSENT_BACKEND_RECEIPT_SHA256,
                        null,
                    )
                val storedGatewayAudit =
                    sensitivePrefs.getString(
                        PREF_INTEGRATED_CONSENT_GATEWAY_AUDIT_SHA256,
                        null,
                    )
                val storedActorSha256 = sensitivePrefs.getString(
                    PREF_INTEGRATED_CONSENT_ACTOR_SHA256,
                    null,
                )
                if (
                    !legacyFloorRestored ||
                    confirmation == null ||
                    confirmation.installationId != installationId ||
                    confirmation.policyVersion != legacyPolicyVersion ||
                    confirmation.revision != legacyRevision ||
                    (
                        confirmation.clientRevision != legacyClientRevision &&
                            restoredPending?.let { pending ->
                                persistence.restoredPendingPreservesConfirmation(
                                    pending, confirmation, storedActorSha256,
                                    legacyClientRevision, restoredCasJournal,
                                )
                            } != true
                        ) ||
                    confirmation.selections != legacySelections ||
                    confirmation.backendConsentReceiptSha256 != legacyReceipt ||
                    confirmation.gatewayAuditRecordSha256 != storedGatewayAudit ||
                    storedActorSha256?.let(INTEGRATED_CONSENT_RECEIPT_SHA256::matches) !=
                    true ||
                    confirmation.controlSecret != controlSecret ||
                    !integratedConsentSession.restoreCurrentConfirmation(confirmation)
                ) {
                    speech.invalidate()
                    integratedConsentSession.failClosed()
                } else {
                    integratedConsentDraft = confirmation.selections
                    integratedConsentClientRevision =
                        maxOf(
                            integratedConsentClientRevision,
                            confirmation.clientRevision,
                        )
                    integratedConsentExpectedPreviousBackendReceiptSha256 =
                        confirmation.backendConsentReceiptSha256
                    integratedConsentBootstrapReady = false
                    integratedConsentConfirmedActorSha256 = storedActorSha256
                    reapplyActorBoundIntegratedConsent()
                }
            }
        restoredPendingRaw
            ?.let { _ ->
                val mutation = restoredPending
                val restoredConfirmation = integratedConsentSession.cachedConfirmationOrNull()
                if (mutation == null ||
                    (restoredConfirmation != null && !persistence.restoredPendingPreservesConfirmation(
                        mutation, restoredConfirmation, integratedConsentConfirmedActorSha256,
                        sensitivePrefs.getLong(PREF_INTEGRATED_CONSENT_CLIENT_REVISION, 0L),
                        restoredCasJournal,
                    ))
                ) {
                    speech.invalidate()
                    integratedConsentSession.failClosed()
                } else {
                    // Restore the validated durable request before any later local withdrawal.
                    // Its exact receipt settles only this request's withdrawal items.
                    integratedConsentSession.restorePendingMutation(mutation, retry = true)
                    if (integratedConsentSession.pendingMutationOrNull() != mutation) {
                        speech.invalidate()
                        integratedConsentSession.failClosed()
                    } else {
                        pendingIntegratedConsentMutation = mutation
                        integratedConsentClientRevision =
                            maxOf(
                                integratedConsentClientRevision,
                                mutation.clientRevision,
                            )
                        integratedConsentDraft = mutation.desiredSelections
                    }
                }
            }
        sensitivePrefs.getString(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED, null)
            ?.let { raw ->
                val items = persistence.localWithdrawalMarkerItemsOrNull(raw)
                if (items == null) {
                    speech.invalidate()
                    integratedConsentSession.failClosed()
                } else {
                    applyImmediateConsentWithdrawals(items)
                    // A valid marker with a restored actor-bound confirmation can be re-sent
                    // as a denial. Unknown ownership or corrupt storage remains fail closed.
                    if (integratedConsentSession.cachedConfirmationOrNull() == null ||
                        integratedConsentConfirmedActorSha256 == null
                    ) integratedConsentSession.failClosed()
                }
            }
        reapplyActorBoundIntegratedConsent()
    }


    fun restoreDraftAndClientFloorFromStorage() {
        integratedConsentClientRevision =
            sensitivePrefs.getLong(
                PREF_INTEGRATED_CONSENT_CLIENT_REVISION,
                0L,
            ).coerceAtLeast(0L)
        integratedConsentDraft =
            if (
                sensitivePrefs.getString(PREF_INTEGRATED_CONSENT_POLICY_VERSION, null) ==
                INTEGRATED_CONSENT_POLICY_VERSION
            ) {
                IntegratedConsentSelections(
                    rawSourceCollection =
                        sensitivePrefs.getBoolean(PREF_REPORT_PRIVACY_CONSENT_KEY, false),
                    automaticReporting =
                        sensitivePrefs.getBoolean(
                            PREF_AUTOMATIC_REPORT_CONSENT_KEY,
                            false,
                        ),
                    mobileNetworkTransfer =
                        MobileNetworkPreference.fromWireValue(
                            sensitivePrefs.getString(
                                PREF_MOBILE_NETWORK_PREFERENCE_KEY,
                                null,
                            ),
                        ) == MobileNetworkPreference.ALLOW_CELLULAR,
                    trainingReuse =
                        sensitivePrefs.getBoolean(
                            PREF_TRAINING_REUSE_CONSENT_KEY,
                            false,
                        ),
                )
            } else {
                IntegratedConsentSelections()
            }
    }
    fun clearBlockedStartupState() {
        integratedConsentClientRevision = 0L
        integratedConsentDraft = IntegratedConsentSelections()
    }
    fun resetForNewEnrollment() {
        pendingIntegratedConsentMutation = null
        integratedConsentDraft = IntegratedConsentSelections()
        integratedConsentClientRevision = 0L
        speech.invalidate()
        integratedConsentSession.resetForNewEnrollment()
    }
    fun selectDevelopmentDraft(confirmation: IntegratedConsentConfirmation?) {
        integratedConsentDraft = (confirmation?.selections ?: integratedConsentDraft).copy(rawSourceCollection = true, automaticReporting = true)
    }
    private fun clearActorBindingAfterGatewayChange() {
        integratedConsentConfirmedActorSha256 = null
        integratedConsentDraft = IntegratedConsentSelections()
        integratedConsentBootstrapReady = false
        integratedConsentExpectedPreviousBackendReceiptSha256 = null
    }
    fun onActorBindingChanged(currentSession: GatewayFieldSession?) {
        val confirmedActor = integratedConsentConfirmedActorSha256
        if (confirmedActor != null && (currentSession == null || confirmedActor != integratedConsentActorSha256(currentSession))) {
            applyImmediateConsentWithdrawals(IntegratedConsentItem.entries.toSet(), recordLocalWithdrawal = false)
            if (!resetIntegratedConsentForReconsent()) {
                speech.invalidate()
                integratedConsentSession.invalidate()
            }
            clearActorBindingAfterGatewayChange()
        }
    }
    fun fenceGatewayAuthorityChange(snapshot: GatewaySessionProcessSnapshot): Boolean = synchronized(integratedConsentLock) {
        val previous = lastIntegratedConsentAuthoritySnapshot
        lastIntegratedConsentAuthoritySnapshot = snapshot
        val changed = previous == null ||
            previous.generation != snapshot.generation || previous.session !== snapshot.session ||
            previous.storageBlocked != snapshot.storageBlocked ||
            previous.deletionRecoveryOnly != snapshot.deletionRecoveryOnly
        if (changed) {
            cancelIntegratedConsentControlCall()
            speech.invalidate()
            integratedConsentSession.invalidateBackendAuthority()
            integratedConsentBootstrapReady = false
        }
        changed
    }
    fun resumeConsentOperations() {
        if (pendingIntegratedConsentMutation != null) retryPendingIntegratedConsentMutation()
        else if (integratedConsentSession.withdrawalRecoverySelectionsOrNull() != null) persistIntegratedConsentDraft(announce = false)
        else refreshIntegratedConsentFromServer()
    }
    private companion object {
        const val MAX_AUTOMATIC_REJECTED_CAS_RECOVERIES = 1 }
}
