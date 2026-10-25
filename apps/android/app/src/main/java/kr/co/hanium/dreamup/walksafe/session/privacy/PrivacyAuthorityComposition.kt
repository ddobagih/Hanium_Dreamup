package kr.co.hanium.dreamup.walksafe.session.privacy

import android.content.SharedPreferences
import java.util.UUID
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.fieldlog.FieldSessionLog
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteArchiveController
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.report.ReportPrivacyConsentSession
import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.consent.*
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_RAW_SOURCE_FIELD_LOG_BLOCKED
import kr.co.hanium.dreamup.walksafe.session.deletion.reset.*
import kr.co.hanium.dreamup.walksafe.session.deletion.runtime.*
import kr.co.hanium.dreamup.walksafe.startup.*
import kr.co.hanium.dreamup.walksafe.session.gateway.PrivacyStartupRestoreCoordinator

/**
 * Constructs the one consent/deletion/storage authority graph at the Activity's original anchors.
 * Slots contain the actual existing controllers or their actual Lazy, never copied domain state.
 * Construction stores late boundaries only; methods below are invoked in original eager/lazy order.
 */
internal class PrivacyAuthorityComposition(
    private val plainPreferences: () -> SharedPreferences,
    private val sensitivePreferences: () -> AndroidSensitivePreferenceStore,
    private val sensitivePreferencesOrNull: () -> AndroidSensitivePreferenceStore?,
    private val gatewayStoreOrNull: () -> AndroidGatewaySessionStore?,
    private val installationId: () -> String?,
    private val fallbackMarkerFactory: () -> AndroidAccountDeletionFallbackMarker,
    private val configuredGatewayOriginOrNull: () -> String?,
    private val stateMachine: () -> AccountDeletionStateMachine,
    private val activityLease: () -> AccountDeletionActivityLease,
    private val cleanupExecutor: () -> Executor,
    private val networkExecutor: () -> Executor,
    private val deletionClient: () -> AndroidPrivacyDeletionClient,
    private val consentClient: () -> AndroidIntegratedConsentClient,
    private val callbacks: AccountDeletionProgressCallbacks,
    private val runOnUi: (() -> Unit) -> Unit,
    private val activity: PrivacyAuthorityActivity,
    private val fieldLog: () -> FieldSessionLog,
    private val fieldLogOrNull: () -> FieldSessionLog?,
    private val routeArchive: () -> RouteArchiveController,
    private val validFailClosedReason: (String) -> Boolean,
    private val purgeGuidanceDiagnosticsOnWorker: () -> Boolean,
    private val purgeEvidenceKeyAfterConfirmedDeletion: (AccountDeletionJournal) -> Boolean = { true },
) {
    private lateinit var accountDeletionResetStorage: AccountDeletionResetStorage
    private lateinit var accountDeletionInstallationResetController: AccountDeletionInstallationResetController
    private lateinit var speechOutputStorageCoordinator: SpeechOutputStorageCoordinator
    private lateinit var reportPrivacyConsentSession: ReportPrivacyConsentSession
    private lateinit var integratedConsentSession: IntegratedConsentSession
    private lateinit var accountDeletionWorkerStages: AccountDeletionWorkerStages
    private lateinit var accountDeletionRecoveryStore: AccountDeletionRecoveryStore
    private lateinit var accountDeletionOperationAuthority: AccountDeletionOperationAuthority
    private lateinit var accountDeletionRecoveryActorController: AccountDeletionRecoveryActorController
    private lateinit var accountDeletionPreparationController: AccountDeletionPreparationController
    private lateinit var legacyPendingReportQueuePurgeController: LegacyPendingReportQueuePurgeController
    private lateinit var integratedConsentController: Lazy<IntegratedConsentController>
    private lateinit var accountDeletionNetworkController: Lazy<AccountDeletionNetworkController>
    private lateinit var accountDeletionLocalDataPurger: Lazy<AccountDeletionLocalDataPurger>
    private lateinit var accountDeletionRuntimeFenceController: Lazy<AccountDeletionRuntimeFenceController>
    private lateinit var accountDeletionEvidenceController: Lazy<AccountDeletionEvidenceController>
    private lateinit var accountDeletionProgressController: Lazy<AccountDeletionProgressController>
    private lateinit var privacyStartupRestoreCoordinator: Lazy<PrivacyStartupRestoreCoordinator>

    fun createReportPrivacyConsentSession(): ReportPrivacyConsentSession = ReportPrivacyConsentSession.also { reportPrivacyConsentSession = it }

    fun createIntegratedConsentSession(): IntegratedConsentSession = IntegratedConsentSession().also { integratedConsentSession = it }

    fun createAccountDeletionOperationAuthority(): AccountDeletionOperationAuthority = AccountDeletionOperationAuthority().also { accountDeletionOperationAuthority = it }

    fun createAccountDeletionResetStorage(): AccountDeletionResetStorage = AccountDeletionResetStorage(
        plainPreferences = { plainPreferences() },
        sensitivePreferences = sensitivePreferencesOrNull,
        gatewayStore = gatewayStoreOrNull,
        dualAuthority = { accountDeletionRecoveryStore.dualAuthority },
        routePurgeVerified = {
            val preparation = routeArchive().resetParticipant
            preparation != null && preparation.verified.get() && routeArchive().routeSnapshotResetPreparationIsCurrent(preparation)
        },
        resetFieldStorage = { speechOutputStorageCoordinator.resetFieldStorageOnWorker() },
    ).also { accountDeletionResetStorage = it }

    fun createAccountDeletionInstallationResetController(): AccountDeletionInstallationResetController = AccountDeletionInstallationResetController(
        stateMachine = { stateMachine() },
        activityLease = { activityLease() },
        cleanupExecutor = { cleanupExecutor() },
        postToUi = { callback -> callbacks.post { callback() } },
        runOnUi = { callback -> runOnUi { callback() } },
        storage = accountDeletionResetStorage,
        purgeEvidenceKeyAfterConfirmedDeletion = purgeEvidenceKeyAfterConfirmedDeletion,
        startupHandoff = object : InstallationResetStartupHandoff {
            override var pending: Boolean
                get() = PrivacyStartupInspectionController.accountDeletionStartupResetHandoffPending
                set(value) { PrivacyStartupInspectionController.accountDeletionStartupResetHandoffPending = value }
        },
        actions = object : InstallationResetActions {
            override fun isDestroyed(): Boolean = callbacks.startupInspectionDestroyed
            override fun activityCallbackAllowed(): Boolean = activity.callbackAllowed()
            override fun updateUi() { activity.updateUi() }
            override fun keepPrivacyFenceClosed() { keepAccountDeletionPrivacyFenceClosed() }
            override fun invalidateGatewaySession() { activity.invalidateGatewaySession() }
            override fun captureParticipants(): PreparedPrivacyStartupReset = captureInstallationResetParticipants()
            override fun prepareFreshSpeechCollection(): PreparedResetSpeechCollection? = captureFreshInstallationResetSpeechCollection()
            override fun resetReportPrivacyForNewEnrollment() { reportPrivacyConsentSession.resetForNewEnrollment() }
            override fun resetRuntimeForNewEnrollment() { activity.resetRuntimeForNewEnrollment() }
            override fun finishActivity() { activity.finishActivity() }
            override fun observeStartupAuthority(snapshot: AccountDeletionAuthoritySnapshot) { accountDeletionRecoveryStore.observeAuthority(snapshot) }
            override fun blockPrivacyForResetFailure() {
                integratedConsentSession.blockForAccountDeletion()
                reportPrivacyConsentSession.blockForAccountDeletion()
                fieldLog().blockForAccountDeletion()
            }
            override fun applyTerminalPersistenceFailure(reason: String) {
                speechOutputStorageCoordinator.invalidateBinding()
                integratedConsentSession.failClosed()
                reportPrivacyConsentSession.blockForAccountDeletion()
                accountDeletionRecoveryStore.setRemoteResumeBlocked(true)
            }
            override fun showTerminalPersistenceFailure(reason: String) {
                activity.status("accountDeletion=fail_closed:$reason")
                activity.updateUi()
            }
        },
    ).also { accountDeletionInstallationResetController = it }

    fun createAccountDeletionRecoveryStore(): AccountDeletionRecoveryStore = AccountDeletionRecoveryStore(
        sensitivePreferences = { sensitivePreferences() },
        installationId = installationId,
        fallbackMarkerFactory = fallbackMarkerFactory,
        configuredGatewayOriginOrNull = configuredGatewayOriginOrNull,
        stateMachine = { stateMachine() },
        activityLease = { activityLease() },
        workerStages = accountDeletionWorkerStages,
        resetController = { accountDeletionInstallationResetController },
        effects = object : AccountDeletionRecoveryEffects {
            override fun activityCallbackAllowed() = activity.callbackAllowed()
            override fun blockSpeechOutputStorageForAccountDeletion() { speechOutputStorageCoordinator.blockForAccountDeletion() }
            override fun blockPrivacyForAuthority() { blockPrivacyForAccountDeletionAuthority() }
            override fun applyRuntimeFence() = accountDeletionRuntimeFenceController.value.apply()
        },
    ).also { accountDeletionRecoveryStore = it }

    fun createAccountDeletionWorkerStages(): AccountDeletionWorkerStages = AccountDeletionWorkerStages(
        stateMachine = { stateMachine() },
        activityLease = { activityLease() },
        resetController = { accountDeletionInstallationResetController },
        keepPrivacyFenceClosed = ::keepAccountDeletionPrivacyFenceClosed,
    ).also { accountDeletionWorkerStages = it }

    fun createAccountDeletionNetworkController(): Lazy<AccountDeletionNetworkController> = lazy {
        AccountDeletionNetworkController(
        operation = accountDeletionOperationAuthority,
        store = accountDeletionRecoveryStore,
        actor = accountDeletionRecoveryActorController,
        stages = accountDeletionWorkerStages,
        stateMachine = { stateMachine() },
        cleanupExecutor = { cleanupExecutor() },
        networkExecutor = { networkExecutor() },
        queries = object : AccountDeletionNetworkQueries {
            override fun trustedOrigin() = configuredGatewayOriginOrNull()
            override fun localPurgeRequestId() = accountDeletionProgressController.value.localPurgeRequestId
        },
        effects = object : AccountDeletionNetworkEffects {
            override fun keepPrivacyFenceClosed() { keepAccountDeletionPrivacyFenceClosed() }
            override fun invalidateSpeechStorage() { speechOutputStorageCoordinator.invalidateBinding() }
            override fun failClosedIntegratedConsent() { integratedConsentSession.failClosed() }
            override fun postUiRefresh() { postAccountDeletionUiRefresh() }
        },
        progress = object : AccountDeletionNetworkProgress {
            override fun scheduleAfterStatus(journal: AccountDeletionJournal) { accountDeletionProgressController.value.scheduleAfterStatus(journal) }
            override fun handleEvidenceRevisionConflict(responseBody: String, workerAttempt: AccountDeletionWorkerAttempt) =
                accountDeletionEvidenceController.value.handleRevisionConflict(responseBody, workerAttempt)
            override fun postFailure(errorCode: String, workerAttempt: AccountDeletionWorkerAttempt) {
                accountDeletionProgressController.value.postFailure(errorCode, workerAttempt)
            }
        },
        client = deletionClient(),
    )
    }.also { accountDeletionNetworkController = it }

    fun createAccountDeletionEvidenceController(): Lazy<AccountDeletionEvidenceController> = lazy {
        AccountDeletionEvidenceController(
        store = accountDeletionRecoveryStore,
        stages = accountDeletionWorkerStages,
        stateMachine = { stateMachine() },
        cleanupExecutor = { cleanupExecutor() },
        queries = object : AccountDeletionEvidenceQueries { override fun trustedOrigin() = configuredGatewayOriginOrNull() },
        effects = object : AccountDeletionEvidenceEffects {
            override fun keepPrivacyFenceClosed() { keepAccountDeletionPrivacyFenceClosed() }
            override fun invalidateSpeechStorage() { speechOutputStorageCoordinator.invalidateBinding() }
            override fun failClosedIntegratedConsent() { integratedConsentSession.failClosed() }
            override fun postUiRefresh() { postAccountDeletionUiRefresh() }
        },
        progress = object : AccountDeletionEvidenceProgress {
            override fun scheduleAfterStatus(journal: AccountDeletionJournal) { accountDeletionProgressController.value.scheduleAfterStatus(journal) }
            override fun postFailure(reason: String, workerAttempt: AccountDeletionWorkerAttempt) { accountDeletionProgressController.value.postFailure(reason, workerAttempt) }
        },
        network = object : AccountDeletionNetworkEntry {
            override fun startCall(callFactory: () -> CancellableNetworkCall<AccountDeletionStatus>, purpose: AccountDeletionCallPurpose) {
                accountDeletionNetworkController.value.startCall(callFactory, purpose)
            }
        },
        client = deletionClient(),
    )
    }.also { accountDeletionEvidenceController = it }

    fun createAccountDeletionProgressController(): Lazy<AccountDeletionProgressController> = lazy {
        AccountDeletionProgressController(
        operation = accountDeletionOperationAuthority,
        store = accountDeletionRecoveryStore,
        actor = accountDeletionRecoveryActorController,
        stages = accountDeletionWorkerStages,
        stateMachine = { stateMachine() },
        activityLease = { activityLease() },
        resetController = { accountDeletionInstallationResetController },
        sensitivePreferences = { sensitivePreferences() },
        cleanupExecutor = { cleanupExecutor() },
        callbacks = callbacks,
        effects = object : AccountDeletionProgressEffects {
            override fun applyRuntimeFence() = accountDeletionRuntimeFenceController.value.apply()
            override fun keepPrivacyFenceClosed() { keepAccountDeletionPrivacyFenceClosed() }
            override fun postUiRefresh() { postAccountDeletionUiRefresh() }
            override fun updateUi() { activity.updateUi() }
            override fun status(value: String) { activity.status(value) }
            override fun invalidateSpeechStorage() { speechOutputStorageCoordinator.invalidateBinding() }
            override fun failClosedIntegratedConsent() { integratedConsentSession.failClosed() }
        },
        network = object : AccountDeletionProgressNetwork {
            override fun refreshStatus(workerAttempt: AccountDeletionWorkerAttempt?) { accountDeletionNetworkController.value.refreshStatus(workerAttempt) }
            override fun startCall(callFactory: () -> CancellableNetworkCall<AccountDeletionStatus>, purpose: AccountDeletionCallPurpose) {
                accountDeletionNetworkController.value.startCall(callFactory, purpose)
            }
        },
        evidenceController = accountDeletionEvidenceController.value,
        purge = AccountDeletionLocalDataPurge { accountDeletionLocalDataPurger.value.purgeLocalDataOnWorker() },
    )
    }.also { accountDeletionProgressController = it }

    fun createSpeechOutputStorageCoordinator(
        permissionPolicy: () -> PermissionSessionPolicy,
        lifecycle: () -> WalkSessionLifecycle,
        queries: (() -> Boolean) -> SpeechStorageQueries,
    ): SpeechOutputStorageCoordinator = SpeechOutputStorageCoordinator(
        consentSession = { integratedConsentSession },
        permissionPolicy = permissionPolicy,
        lifecycle = lifecycle,
        deletionStateMachine = { stateMachine() },
        resetCoordinator = { accountDeletionInstallationResetController.resetCoordinator },
        activityLease = { activityLease() },
        cleanupExecutor = { cleanupExecutor() },
        callbacks = SpeechStorageCallbacks { callback -> callbacks.post { callback() } },
        queries = queries(::accountBlockedAtStartup),
    ).also { speechOutputStorageCoordinator = it }

    private fun accountBlockedAtStartup(): Boolean = accountDeletionRecoveryStore.fallbackPresentAtStartup ||
        accountDeletionRecoveryStore.markerPresentAtStartup() || accountDeletionRecoveryStore.authorityAtStartup.blocksPrivacy ||
        stateMachine().processingBlocked()

    fun createAccountDeletionRecoveryActorController(
        storageReady: () -> Boolean, currentReporterActorId: () -> String?, enrollment: AccountDeletionEnrollmentActorReader,
        clearGatewaySessionAction: (GatewayFieldSession, AccountDeletionActivityLease) -> Unit,
    ): AccountDeletionRecoveryActorController = AccountDeletionRecoveryActorController(
        store = accountDeletionRecoveryStore,
        sensitivePreferences = { sensitivePreferences() },
        storageReady = storageReady,
        configuredGatewayOriginOrNull = configuredGatewayOriginOrNull,
        currentReporterActorId = currentReporterActorId,
        enrollment = enrollment,
        stateMachine = { stateMachine() },
        activityLease = { activityLease() },
        workerStages = accountDeletionWorkerStages,
        effects = object : AccountDeletionRecoveryActorEffects {
            override fun clearGatewaySession(expectedSession: GatewayFieldSession, expectedActivityLease: AccountDeletionActivityLease) {
                clearGatewaySessionAction(expectedSession, expectedActivityLease)
            }
            override fun invalidateSpeechStorage() { speechOutputStorageCoordinator.invalidateBinding() }
            override fun failClosedIntegratedConsent() { integratedConsentSession.failClosed() }
            override fun postUiRefresh() { postAccountDeletionUiRefresh() }
            override fun status(value: String) { activity.status(value) }
            override fun speak(value: String) { activity.speak(value) }
        },
    ).also { accountDeletionRecoveryActorController = it }

    fun createAccountDeletionPreparationController(
        queries: AccountDeletionPreparationQueries, reapplyActorBoundConsentAction: () -> Unit,
    ): AccountDeletionPreparationController = AccountDeletionPreparationController(
        operation = accountDeletionOperationAuthority,
        store = accountDeletionRecoveryStore,
        actor = accountDeletionRecoveryActorController,
        stages = accountDeletionWorkerStages,
        stateMachine = { stateMachine() },
        activityLease = { activityLease() },
        resetController = { accountDeletionInstallationResetController },
        cleanupExecutor = { cleanupExecutor() },
        callbacks = callbacks,
        queries = queries,
        effects = object : AccountDeletionPreparationEffects {
            override fun applyRuntimeFence() = accountDeletionRuntimeFenceController.value.apply()
            override fun keepPrivacyFenceClosed() { keepAccountDeletionPrivacyFenceClosed() }
            override fun postUiRefresh() { postAccountDeletionUiRefresh() }
            override fun updateUi() { activity.updateUi() }
            override fun status(value: String) { activity.status(value) }
            override fun speak(value: String) { activity.speak(value) }
            override fun invalidateSpeechStorage() { speechOutputStorageCoordinator.invalidateBinding() }
            override fun resetIntegratedConsentForNewEnrollment() { integratedConsentSession.resetForNewEnrollment() }
            override fun resetReportConsentForNewEnrollment() { reportPrivacyConsentSession.resetForNewEnrollment() }
            override fun reapplyActorBoundConsent() { reapplyActorBoundConsentAction() }
            override fun resumePrivacyControlAfterStartup() { activity.schedulePrivacyOperations() }
            override fun blockIntegratedConsent() { integratedConsentSession.blockForAccountDeletion() }
            override fun blockReportConsent() { reportPrivacyConsentSession.blockForAccountDeletion() }
            override fun failClosedIntegratedConsent() { integratedConsentSession.failClosed() }
        },
        network = object : AccountDeletionNetworkEntry {
            override fun startCall(callFactory: () -> CancellableNetworkCall<AccountDeletionStatus>, purpose: AccountDeletionCallPurpose) {
                accountDeletionNetworkController.value.startCall(callFactory, purpose)
            }
        },
        client = deletionClient(),
    ).also { accountDeletionPreparationController = it }

    fun createLegacyPendingReportQueuePurgeController(
        persistentQueueEnabled: Boolean, executor: () -> Executor, purgeLegacyStore: () -> Boolean,
    ): LegacyPendingReportQueuePurgeController = LegacyPendingReportQueuePurgeController(
        activityToken = UUID.randomUUID().toString(),
        persistentQueueEnabled = persistentQueueEnabled,
        executor = executor,
        callbacks = LegacyQueuePurgeCallbacks { callback -> callbacks.post { callback() } },
        purgeLegacyStore = purgeLegacyStore,
    ).also { legacyPendingReportQueuePurgeController = it }

    fun createAccountDeletionRuntimeFenceController(portsFactory: () -> PrivacyFenceParticipants): Lazy<AccountDeletionRuntimeFenceController> = lazy {
        val ports = portsFactory()
        AccountDeletionRuntimeFenceController(
            blockSpeechStorage = { speechOutputStorageCoordinator.blockForAccountDeletion() },
            purgeAndRotatePosition = ports.purgeAndRotatePosition,
            reports = ports.reports,
            fenceRawCollections = ports.fenceRawCollections,
            blockFieldProcessing = ports.blockFieldProcessing,
            blockConsent = { integratedConsentSession.blockForAccountDeletion(); reportPrivacyConsentSession.blockForAccountDeletion() },
            clearPermissions = ports.clearPermissions,
            disableMetadata = ports.disableMetadata,
            invalidateFrames = ports.invalidateFrames,
            stopActiveWalk = ports.stopActiveWalk,
            showPositionFailure = ports.showPositionFailure,
        )
    }.also { accountDeletionRuntimeFenceController = it }

    fun createAccountDeletionLocalDataPurger(
        participants: (() -> SpeechOutputStorageCoordinator, () -> RouteArchiveController) -> AccountDeletionLocalPurgeParticipants,
    ): Lazy<AccountDeletionLocalDataPurger> = lazy {
        AccountDeletionLocalDataPurger(
            sensitivePreferences = { sensitivePreferences() },
            legacyQueue = legacyPendingReportQueuePurgeController,
            participants = participants({ speechOutputStorageCoordinator }, routeArchive),
        )
    }.also { accountDeletionLocalDataPurger = it }

    fun createIntegratedConsentController(
        portsFactory: (() -> ReportPrivacyConsentSession, () -> LegacyPendingReportQueuePurgeController, () -> SpeechOutputStorageCoordinator) -> PrivacyConsentParticipants,
    ): Lazy<IntegratedConsentController> = lazy {
        val ports = portsFactory({ reportPrivacyConsentSession }, { legacyPendingReportQueuePurgeController }, { speechOutputStorageCoordinator })
        IntegratedConsentController(
            integratedConsentSession = integratedConsentSession,
            remote = object : IntegratedConsentController.Remote {
            override fun fetchCurrentCall(gatewayBaseUrl: String, session: GatewayFieldSession, installationId: String, controlSecret: String) =
                consentClient().fetchCurrentCall(gatewayBaseUrl, session, installationId, controlSecret)
            override fun fetchBootstrapCall(gatewayBaseUrl: String, session: GatewayFieldSession, installationId: String) =
                consentClient().fetchBootstrapCall(gatewayBaseUrl, session, installationId)
            override fun saveCall(gatewayBaseUrl: String, session: GatewayFieldSession, installationId: String, controlSecret: String, requestId: String, clientRevision: Long, selections: IntegratedConsentSelections, expectedPreviousBackendReceiptSha256: String?) =
                consentClient().saveCall(gatewayBaseUrl, session, installationId, controlSecret, requestId, clientRevision, selections, expectedPreviousBackendReceiptSha256)
        },
            gateway = ports.gateway,
            permissions = ports.permissions,
            actor = ports.actor,
            activity = object : IntegratedConsentController.Activity {
            override fun destroyed() = callbacks.startupInspectionDestroyed
            override fun processingBlocked() = stateMachine().processingBlocked()
            override fun currentLeaseIsCurrent() = stateMachine().activityLeaseIsCurrent(activityLease())
        },
            speech = object : IntegratedConsentController.SpeechStorage {
            override fun invalidate() = speechOutputStorageCoordinator.invalidateBinding()
            override fun blockRawWithdrawal() = speechOutputStorageCoordinator.blockForRawWithdrawal()
            override fun prepareConfirmationApply(confirmation: IntegratedConsentConfirmation, evaluation: IntegratedConsentApplyResult, previousRawConfirmation: IntegratedConsentConfirmation?, currentRawConfirmation: () -> IntegratedConsentConfirmation?) =
                prepareSpeechOutputStorageConsentApply(confirmation, evaluation, previousRawConfirmation, currentRawConfirmation)
        },
            reports = ports.reports,
            raw = ports.raw,
            network = ports.network,
            ui = ports.ui,
            executor = ports.executor,
            postToMain = { action -> runOnUi { action() } },
        )
    }.also { integratedConsentController = it }

    fun initializeIntegratedConsentPersistence() {
            integratedConsentController.value.initializePersistence(IntegratedConsentPersistence(
                sensitive = object : IntegratedConsentPersistence.SensitiveStore {
                    override fun isBlocked() = sensitivePreferences().isBlocked()
                    override fun failClosed() = sensitivePreferences().failClosed()
                    override fun contains(key: String) = sensitivePreferences().contains(key)
                    override fun getString(key: String, defaultValue: String?) = sensitivePreferences().getString(key, defaultValue)
                    override fun getLong(key: String, defaultValue: Long) = sensitivePreferences().getLong(key, defaultValue)
                    override fun getBoolean(key: String, defaultValue: Boolean) = sensitivePreferences().getBoolean(key, defaultValue)
                    override fun edit(): IntegratedConsentPersistence.Editor {
                        val editor = sensitivePreferences().edit()
                        return object : IntegratedConsentPersistence.Editor {
                            override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
                            override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }
                            override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
                            override fun remove(key: String) = apply { editor.remove(key) }
                            override fun commit() = editor.commit()
                        }
                    }
                },
                plain = object : IntegratedConsentPersistence.PlainStore {
                    override fun edit(): IntegratedConsentPersistence.Editor {
                        val editor = plainPreferences().edit()
                        return object : IntegratedConsentPersistence.Editor {
                            override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
                            override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }
                            override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
                            override fun remove(key: String) = apply { editor.remove(key) }
                            override fun commit() = editor.commit()
                        }
                    }
                },
            ))
        }

    fun blockPrivacyForAccountDeletionAuthority() {
            speechOutputStorageCoordinator.blockForAccountDeletion()
            integratedConsentSession.blockForAccountDeletion()
            reportPrivacyConsentSession.blockForAccountDeletion()
            fieldLogOrNull()?.blockForAccountDeletion()
        }

    fun resumePrivacyControlOperations() {
            if (stateMachine().processingBlocked()) {
                accountDeletionProgressController.value.applyFenceAndPurgeLocal()
                activity.updateUi()
                return
            }
            integratedConsentController.value.resumeConsentOperations()
        }

    fun postAccountDeletionUiRefresh() {
            callbacks.post {
                if (
                    !callbacks.startupInspectionDestroyed &&
                    stateMachine().activityLeaseIsCurrent(
                        activityLease(),
                    )
                ) activity.updateUi()
            }
        }

    fun keepAccountDeletionPrivacyFenceClosed() {
            runOnUi {
                if (
                    callbacks.startupInspectionDestroyed ||
                    !stateMachine().activityLeaseIsCurrent(
                        activityLease(),
                    ) ||
                    !stateMachine().processingBlocked()
                ) return@runOnUi
                accountDeletionRuntimeFenceController.value.apply()
                activity.updateUi()
            }
        }

    fun captureInstallationResetParticipants(): PreparedPrivacyStartupReset {
        val original = InstallationResetParticipants.capture(
            prepareSpeech = { speechOutputStorageCoordinator.prepareAccountPurgeOnWorker() },
            prepareRoute = { routeArchive().prepareRouteSnapshotAccountPurge() },
            awaitSpeech = { preparation -> speechOutputStorageCoordinator.awaitPreparedAccountPurge(preparation) },
            awaitRoute = { preparation -> routeArchive().awaitRouteSnapshotAccountPurgeOnWorker(preparation) },
            speechIsCurrent = { preparation -> speechOutputStorageCoordinator.resetPreparationIsCurrent(preparation) },
            routeIsCurrent = { preparation -> routeArchive().routeSnapshotResetPreparationIsCurrent(preparation) },
        )
        return object : PreparedPrivacyStartupReset {
            override fun awaitOriginalAcknowledgements(): Boolean {
                val originalPurged = original.awaitOriginalAcknowledgements()
                val diagnosticsPurged = purgeGuidanceDiagnosticsOnWorker()
                return originalPurged && diagnosticsPurged
            }
            override fun isCurrent(): Boolean = original.isCurrent()
        }
    }

    fun captureFreshInstallationResetSpeechCollection(): PreparedResetSpeechCollection? {
            val replacement = speechOutputStorageCoordinator.prepareAfterVerifiedAccountReset() ?: return null
            return object : PreparedResetSpeechCollection {
                override fun publish(): Boolean = speechOutputStorageCoordinator.publishAfterVerifiedAccountReset(replacement)
                override fun closeAndDrain() { replacement.store.closeAndDrain() }
            }
        }

    /** Use the actual evaluator result before withdrawal effects; accepted changes fence before mutation. */
        internal fun prepareSpeechOutputStorageConsentApply(
            confirmation: IntegratedConsentConfirmation,
            evaluation: IntegratedConsentApplyResult,
            previousRawConfirmation: IntegratedConsentConfirmation?,
            currentRawConfirmation: () -> IntegratedConsentConfirmation?,
            withdrawRaw: () -> Unit = { speechOutputStorageCoordinator.blockForRawWithdrawal() },
            invalidate: () -> Unit = { speechOutputStorageCoordinator.invalidateBinding() },
        ): Boolean {
            if (evaluation !in setOf(IntegratedConsentApplyResult.APPLIED, IntegratedConsentApplyResult.DUPLICATE)) {
                return false
            }
            if (!confirmation.selections.rawSourceCollection) {
                withdrawRaw()
            } else if (evaluation != IntegratedConsentApplyResult.DUPLICATE ||
                currentRawConfirmation() !== previousRawConfirmation
            ) {
                invalidate()
            }
            return true
        }

    fun createPrivacyStartupRestoreCoordinator(): Lazy<PrivacyStartupRestoreCoordinator> = lazy(LazyThreadSafetyMode.NONE) {
        PrivacyStartupRestoreCoordinator(
            deletion = object : PrivacyStartupRestoreCoordinator.Deletion {
                override fun callbackAllowed() = activity.callbackAllowed()
                override fun startupAuthority() = accountDeletionRecoveryStore.authorityAtStartup
                override fun preparedRecoveryCandidatePresent() = accountDeletionRecoveryStore.preparedRecoveryCandidateOrNull() != null
                override fun fallbackState() = accountDeletionRecoveryStore.startupFallbackState
                override fun enterDurableConfirmationRecovery() = stateMachine().enterDurableConfirmationRecovery(activityLease())
                override fun blockRemoteResume() { accountDeletionRecoveryStore.setRemoteResumeBlocked(true) }
                override fun enforceFailClosedAuthority(reason: String) = stateMachine().enforceFailClosedAuthority(activityLease(), reason)
                override fun failClosed(reason: String) = stateMachine().failClosed(activityLease(), reason)
                override fun publishDualFailClosed(reason: String) { accountDeletionRecoveryStore.dualAuthority.failClosed(reason) }
                override fun decodeJournal(raw: String) = AccountDeletionJournalCodec.decodeOrNull(raw)
                override fun restoreJournal(journal: AccountDeletionJournal) = stateMachine().restoreAtStartup(journal, activityLease())
                override fun forceAuthorityFailClosed(reason: String) = accountDeletionRecoveryStore.forceAuthorityFailClosed(reason)
                override fun journalPresent() = stateMachine().snapshotOrNull() != null
                override fun legacyResetIntentPresent() = accountDeletionInstallationResetController.legacyIntentState() != LegacyResetIntentState.ABSENT
                override fun reconcileAuthorityAfterJournalRestore() = accountDeletionRecoveryStore.reconcileAuthorityAfterJournalRestore()
            },
            consent = object : PrivacyStartupRestoreCoordinator.Consent {
                override fun restoreConsentState() { integratedConsentController.value.restoreConsentState() }
                override fun blockForAccountDeletion() { integratedConsentSession.blockForAccountDeletion() }
                override fun failClosed() { integratedConsentSession.failClosed() }
            },
            storage = object : PrivacyStartupRestoreCoordinator.Storage {
                override fun blocked() = sensitivePreferences().isBlocked()
                override fun journal() = sensitivePreferences().getString(PREF_ACCOUNT_DELETION_JOURNAL, null)
                override fun failClosedMarker() = sensitivePreferences().getBoolean(PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER, false)
                override fun validFailClosedReason() = sensitivePreferences().getString(PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON, null)?.takeIf(validFailClosedReason)
            },
            effects = object : PrivacyStartupRestoreCoordinator.Effects {
                override fun withdrawReportPrivacy() { reportPrivacyConsentSession.withdraw() }
                override fun blockReportPrivacy() { reportPrivacyConsentSession.blockForAccountDeletion() }
                override fun invalidateSpeechStorage() { speechOutputStorageCoordinator.invalidateBinding() }
                override fun persistRawSourceFieldLogBlocked() { plainPreferences().edit().putBoolean(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED, true).commit() }
                override fun blockFieldSessionLogIfInitialized() { fieldLogOrNull()?.blockForAccountDeletion() }
            },
        )
    }.also { privacyStartupRestoreCoordinator = it }
    private companion object {
        const val PREF_ACCOUNT_DELETION_JOURNAL = "account_deletion_journal_v2"
        const val PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER = "account_deletion_fail_closed_v1"
        const val PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON = "account_deletion_fail_closed_reason_v1"
    }
}

/** Activity/account effects only; privacy authority and durable reset decisions remain in the graph. */
internal interface PrivacyAuthorityActivity {
    fun callbackAllowed(): Boolean
    fun updateUi()
    fun status(value: String)
    fun speak(value: String)
    fun invalidateGatewaySession()
    fun resetRuntimeForNewEnrollment()
    fun finishActivity()
    fun schedulePrivacyOperations()
}

internal class PrivacyFenceParticipants(
    val purgeAndRotatePosition: () -> Boolean,
    val reports: AccountDeletionReportFenceActions,
    val fenceRawCollections: () -> Unit,
    val blockFieldProcessing: () -> Unit,
    val clearPermissions: () -> Unit,
    val disableMetadata: () -> Unit,
    val invalidateFrames: () -> Unit,
    val stopActiveWalk: () -> Unit,
    val showPositionFailure: () -> Unit,
)

internal class PrivacyConsentParticipants(
    val gateway: IntegratedConsentController.Gateway,
    val permissions: () -> PermissionSessionPolicy,
    val actor: IntegratedConsentController.Actor,
    val reports: IntegratedConsentController.Reports,
    val raw: IntegratedConsentController.RawStorage,
    val network: IntegratedConsentController.Network,
    val ui: IntegratedConsentController.Ui,
    val executor: Executor,
)
