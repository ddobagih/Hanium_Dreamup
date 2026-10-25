package kr.co.hanium.dreamup.walksafe.session.gateway

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.network.GatewayAccountInputPolicy
import kr.co.hanium.dreamup.walksafe.network.GatewayCredentialPolicy
import kr.co.hanium.dreamup.walksafe.network.GatewayEndpointPolicy
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewayInRunRenewalContext
import kr.co.hanium.dreamup.walksafe.network.GatewayInRunRenewalGate
import kr.co.hanium.dreamup.walksafe.network.GatewayInRunRenewalPolicy
import kr.co.hanium.dreamup.walksafe.network.GatewayInRunRenewalProcessState
import kr.co.hanium.dreamup.walksafe.network.GatewayInRunRenewalTrigger
import kr.co.hanium.dreamup.walksafe.network.GatewayInRunSessionAction
import kr.co.hanium.dreamup.walksafe.network.GatewayPendingRevocation
import kr.co.hanium.dreamup.walksafe.network.GatewayRefreshRetryReservation
import kr.co.hanium.dreamup.walksafe.network.GatewayRenewalFailureAction
import kr.co.hanium.dreamup.walksafe.network.GatewayRenewalFailureNotice
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionHttpException
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionLease
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionOperation
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionRenewal
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionRevalidation
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionRevalidationStatus
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionStoreResult
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionVerificationState
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionVersion
import kr.co.hanium.dreamup.walksafe.network.RestoredGatewayLoginBundle
import kr.co.hanium.dreamup.walksafe.network.runGatewayActivityCallbackIfCurrent
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionActivityLease
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionPolicy
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.session.deletion.runtime.AccountDeletionPasswordRecoveryBinding

/** Activity-owned restoration/renewal/revocation; the original process authority remains shared. */
internal class GatewaySessionController(
    private val config: Configuration,
    private val remote: Remote,
    private val profile: Profile,
    private val permissions: () -> PermissionSessionPolicy,
    private val activity: ActivityAuthority,
    private val deletion: DeletionAuthority,
    private val inputs: Inputs,
    private val runtime: RuntimeReader,
    private val effects: Effects,
    private val ui: Ui,
    private val networkObserver: NetworkObserver,
    private val networkEffects: NetworkEffects,
    private val restorationTimer: Timer,
    private val renewalTimer: Timer,
    private val executor: Executor,
    private val postToMain: (() -> Unit) -> Unit,
    private val isMainThread: () -> Boolean,
    private val elapsedRealtimeMs: () -> Long,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val warn: (String, String) -> Unit,
) {
    data class Configuration(val debug: Boolean, val longLivedLoginEnabled: Boolean, val approvedOrigin: String)

    interface Store {
        fun getOrCreateInstallDeviceId(): String?
        fun restoreBackendDevice(expectedGatewayBaseUrl: String): RestoredGatewayLoginBundle?
        fun isStorageBlocked(): Boolean
        fun recoverRenewingToPendingRevocation(): GatewaySessionStoreResult
        fun restorePendingRevocation(expectedGatewayBaseUrl: String): GatewayPendingRevocation?
        fun restoreActive(expectedGatewayBaseUrl: String): RestoredGatewayLoginBundle?
        fun purgeDisabled(): Boolean
        fun moveActiveToPendingRevocation(expectedVersion: GatewaySessionVersion, operationId: String): GatewaySessionStoreResult
        fun removeBackendDeviceIfCurrent(expectedVersion: GatewaySessionVersion): GatewaySessionStoreResult
        fun completePendingRevocation(expectedVersion: GatewaySessionVersion, operationId: String): GatewaySessionStoreResult
        fun reserveRenewal(expectedVersion: GatewaySessionVersion, operationId: String): GatewaySessionStoreResult
        fun commitRenewal(expectedVersion: GatewaySessionVersion, operationId: String, renewedSession: GatewayFieldSession, firstRunSnapshot: FirstRunOnboardingSnapshot): GatewaySessionStoreResult
        fun rollbackRenewalForRetry(expectedVersion: GatewaySessionVersion, operationId: String, reservation: GatewayRefreshRetryReservation): GatewaySessionStoreResult
        fun abandonRenewal(expectedVersion: GatewaySessionVersion, operationId: String): GatewaySessionStoreResult
        fun saveInitialIfAbsent(session: GatewayFieldSession, firstRunSnapshot: FirstRunOnboardingSnapshot, expectedGatewayBaseUrl: String): GatewaySessionStoreResult
        fun persistGatewayOrigin(origin: String)
    }

    interface Remote {
        fun login(gatewayBaseUrl: String, actorId: String?, token: String?, enableLongLivedSession: Boolean, deviceId: String?, sessionScope: GatewaySessionScope): GatewayFieldSession
        fun loginDeletionRecoveryWithPassword(gatewayBaseUrl: String, email: String, password: String,
            expectedActorId: String, expectedAccountGeneration: Long?): GatewayFieldSession =
            throw GatewaySessionHttpException(0, "deletion_password_login_unavailable")
        fun revalidateBackendAccountDeviceSession(session: GatewayFieldSession): GatewayFieldSession
        fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long?): GatewaySessionRevalidation
        fun renew(session: GatewayFieldSession, actorId: String?): GatewaySessionRenewal
        fun logout(session: GatewayFieldSession)
        fun logout(pending: GatewayPendingRevocation)
    }

    interface Profile {
        val firstRunSnapshot: FirstRunOnboardingSnapshot
        fun restoreFirstRun(snapshot: FirstRunOnboardingSnapshot, expected: FirstRunOnboardingSnapshot? = null): Boolean
        var reporterActorId: String?
        fun restorePriorityProfile()
        fun priorityActorId(): String?
        fun accountBlocked(): Boolean
        fun loginActorId(): String?
        fun currentReporterActorId(): String?
    }

    interface ActivityAuthority {
        fun currentLease(): AccountDeletionActivityLease
        fun leaseIsCurrent(lease: AccountDeletionActivityLease): Boolean
        fun destroyed(): Boolean
    }
    interface DeletionAuthority {
        fun processingBlocked(): Boolean
        fun durableConfirmationRecoveryRequired(): Boolean
        fun recoveryLoginRequired(): Boolean
        fun recoverySessionOrNull(session: GatewayFieldSession?): GatewayFieldSession?
        fun isRecoveryLoginTarget(actorId: String, gatewayBaseUrl: String): Boolean
        fun passwordRecoveryBindingOrNull(): AccountDeletionPasswordRecoveryBinding? = null
    }
    interface Inputs {
        fun configuredGatewayOrigin(): String?
        fun debugGatewayUrl(): String?
        fun hasTokenInput(): Boolean
        fun token(): String?
        fun clearTokenInput()
        fun deletionRecoveryEmail(): String? = null
        fun deletionRecoveryPassword(): String? = null
        fun clearDeletionRecoveryPasswordInput() {}
    }
    interface RuntimeReader {
        fun foreground(): Boolean
        fun startupInspectionComplete(): Boolean
        fun networkProbeReady(): Boolean
        fun networkTransport(): ActiveNetworkTransport
        fun feedbackGeneration(): Int
        fun feedbackGenerationIsCurrent(generation: Int): Boolean
        fun developmentQuickStartEnabled(): Boolean
        fun gatewayNetworkAllowed(reason: String): Boolean
        fun screenReaderActive(): Boolean
        fun walkState(): WalkSessionState?
    }
    interface Effects {
        fun invalidateSpeechStorage()
        fun downgradeSession(reason: String, expectedActivityLease: AccountDeletionActivityLease)
        fun cancelDestinationSearchLocation()
        fun cancelReportTransfers()
        fun cancelGatewayNetworkCalls()
        fun clearGatewayNavigationState()
        fun resetReportAttemptFailures()
        fun recordCapacitySignal(trigger: String, version: Long?, adminWarning: Boolean, participantRestricted: Boolean)
    }
    interface Ui {
        fun showAccountMessage(message: String)
        fun showDeletionPasswordMessage(message: String) { showAccountMessage(message) }
        fun refreshFirstRun()
        fun firstRunChanged(message: String)
        fun refreshIntegratedConsent()
        fun refreshReportPrivacy()
        fun refreshAccountDeletion()
        fun refreshBackendAuthButton()
        fun status(message: String)
        fun speakInteraction(message: String)
        fun setAccountAccessNotice(message: String?)
        fun accountStatusInitialized(): Boolean
        fun accountStatusText(): String?
        fun accountStatusShown(): Boolean
    }
    interface Timer {
        fun isReady(): Boolean
        fun remove(runnable: Runnable)
        fun postDelayed(runnable: Runnable, delayMs: Long)
    }
    interface NetworkObserver {
        fun register(events: NetworkEvents): Registration?
    }
    fun interface Registration { fun unregister() }
    interface NetworkEvents {
        fun available()
        fun lost()
        fun capabilitiesChanged(network: Any, transport: ActiveNetworkTransport, validated: Boolean)
    }
    interface NetworkEffects {
        fun reportDrainNetworkChanged()
        fun refreshFeatureNetworkPolicy()
        fun resetNetworkChangeFilter()
        fun networkCapabilitiesChanged(network: Any, transport: ActiveNetworkTransport, validated: Boolean): Boolean
    }

    private lateinit var store: Store
    private val permissionSessionPolicy: PermissionSessionPolicy get() = permissions()
    private val owner = Any()
    @Volatile private var backendDeviceRestorePending = false
    private val gatewayRenewalRetryRunnable = Runnable { retryPendingGatewayRenewal() }
    private val inRunGatewayRenewalRunnable = Runnable { onInRunGatewaySessionRenewalDue() }
    private var capacityRegistration: Registration? = null
    private val gatewayFieldSession: GatewayFieldSession?
        get() = GatewaySessionProcessCoordinator.snapshot().let { it.session.takeUnless { _ -> it.deletionRecoveryOnly } }

    fun initializeStorage(store: Store) {
        check(!this::store.isInitialized)
        this.store = store
    }
    fun backendRestorePending(): Boolean = backendDeviceRestorePending
    fun backendRestoreVerified() { backendDeviceRestorePending = false }
    fun processSessionChanged(snapshot: GatewaySessionProcessSnapshot) {
        if (snapshot.session?.isBackendAccountDeviceBound == true) {
            backendDeviceRestorePending = snapshot.session.verificationState == GatewaySessionVerificationState.RESTORED_UNVERIFIED
            if (backendDeviceRestorePending) snapshot.restoredFirstRunSnapshot?.let { profile.restoreFirstRun(it) }
        } else if (snapshot.deletionRecoveryOnly) {
            backendDeviceRestorePending = false
        }
    }
    fun attach(callback: (GatewaySessionProcessSnapshot) -> Unit) = GatewaySessionProcessCoordinator.attach(owner, callback)
    fun detach() = GatewaySessionProcessCoordinator.detach(owner)
    fun cancelRestorationRetryTimer() = restorationTimer.remove(gatewayRenewalRetryRunnable)

    fun registerGatewayCapacityNetworkObserver() {
        if (capacityRegistration != null) return
        capacityRegistration = networkObserver.register(object : NetworkEvents {
            override fun available() {
                networkEffects.reportDrainNetworkChanged()
                networkEffects.refreshFeatureNetworkPolicy()
                requestGatewayCapacityRefresh("network_reconnected")
                postToMain { retryPendingBackendDeviceRestore() }
            }
            override fun lost() {
                networkEffects.resetNetworkChangeFilter()
                networkEffects.reportDrainNetworkChanged()
                networkEffects.refreshFeatureNetworkPolicy()
            }
            override fun capabilitiesChanged(network: Any, transport: ActiveNetworkTransport, validated: Boolean) {
                if (networkEffects.networkCapabilitiesChanged(network, transport, validated)) networkEffects.reportDrainNetworkChanged()
                networkEffects.refreshFeatureNetworkPolicy()
            }
        })
    }
    fun unregisterGatewayCapacityNetworkObserver() {
        val registration = capacityRegistration ?: return
        runCatching { registration.unregister() }
        capacityRegistration = null
    }

    fun requestGatewayCapacityRefresh(trigger: String) {
        if (activity.destroyed()) return
        val snapshot = GatewaySessionProcessCoordinator.snapshot()
        val session = snapshot.session?.takeUnless { snapshot.deletionRecoveryOnly } ?: return
        val expectedSessionGeneration = snapshot.generation
        if (!isGatewayCapacityRefreshSessionCurrent(session, expectedSessionGeneration)) return
        GatewayCapacityProcessState.fenceSessionGeneration(expectedSessionGeneration)
        if (!GatewayCapacityProcessState.tryBeginRefresh()) return
        val expectedActivityLease = activity.currentLease()
        try {
            executor.execute {
                try {
                    if (
                        !isGatewayCapacityRefreshSessionCurrent(
                            session,
                            expectedSessionGeneration,
                        )
                    ) return@execute
                    val revalidation = remote.revalidate(
                        session = session,
                        actorId = session.actorId,
                        capacitySessionGeneration = expectedSessionGeneration,
                    )
                    val capacityUpdate = revalidation.capacityUpdate
                    val adminWarning =
                        capacityUpdate?.signals?.adminWarningOneShot == true
                    val participantRestricted =
                        capacityUpdate?.signals
                            ?.participantAdmissionRestricted == true
                    if (adminWarning || participantRestricted) {
                        postGatewayActivityCallback(expectedActivityLease) {
                            if (
                                !isGatewayCapacityRefreshSessionCurrent(
                                    session,
                                    expectedSessionGeneration,
                                )
                            ) {
                                return@postGatewayActivityCallback
                            }
                            effects.recordCapacitySignal(
                                trigger, capacityUpdate?.admission?.snapshot?.version,
                                adminWarning, participantRestricted,
                            )
                        }
                    }
                    if (
                        revalidation.status ==
                        GatewaySessionRevalidationStatus.NOT_READY
                    ) {
                        postGatewayActivityCallback(expectedActivityLease) {
                            // login-F1/F4: an expired or rejected long-lived login is refreshed
                            // durably once (renewed, or the revocation reason) before any clear.
                            if (probeGatewaySessionAfterUnauthorized(session)) {
                                return@postGatewayActivityCallback
                            }
                            clearGatewaySession(
                                logoutRemote = false,
                                expectedSession = session,
                            )
                        }
                    }
                } finally {
                    if (GatewayCapacityProcessState.finishRefresh()) {
                        requestGatewayCapacityRefresh("coalesced_latest_session")
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (GatewayCapacityProcessState.finishRefresh()) {
                requestGatewayCapacityRefresh("coalesced_latest_session")
            }
        }
    }

    private fun isGatewayCapacityRefreshSessionCurrent(
        session: GatewayFieldSession,
        expectedGeneration: Long? = null,
    ): Boolean {
        val snapshot = GatewaySessionProcessCoordinator.snapshot()
        return !snapshot.deletionRecoveryOnly &&
            !snapshot.storageBlocked &&
            snapshot.session === session &&
            (expectedGeneration == null || snapshot.generation == expectedGeneration) &&
            session.verificationState == GatewaySessionVerificationState.VERIFIED &&
            session.isUsableFor(session.actorId)
    }

    fun gatewayActivityCallbackAllowed(
        expectedLease: AccountDeletionActivityLease,
    ): Boolean =
        !activity.destroyed() &&
            activity.leaseIsCurrent(expectedLease)

    fun postGatewayActivityCallback(
        expectedLease: AccountDeletionActivityLease,
        callback: () -> Unit,
    ) {
        postToMain {
            runGatewayActivityCallbackIfCurrent(
                activityDestroyed = activity.destroyed(),
                expectedLeaseIsCurrent =
                    activity.leaseIsCurrent(expectedLease),
                callback = callback,
            )
        }
    }

    fun restoreGatewaySessionFromPrefs() {
        val expectedFirstRun = profile.firstRunSnapshot
        val processSnapshot = GatewaySessionProcessCoordinator.snapshot()
        if (processSnapshot.renewalRetryAtElapsedMs?.let { elapsedRealtimeMs() < it } == true) {
            retryPendingGatewayRenewal()
            return
        }
        if (
            processSnapshot.session != null ||
            processSnapshot.inFlightOperationId != null
        ) {
            return
        }
        val operation = GatewaySessionProcessCoordinator.beginOperation() ?: return
        effects.invalidateSpeechStorage()
        permissionSessionPolicy.authenticationExpired()
        val expectedOrigin = inputs.configuredGatewayOrigin() ?: run {
            warn("WalkSafeSession", "restore=origin_unavailable")
            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            return
        }
        val backendDevice = store.restoreBackendDevice(expectedOrigin)
        if (store.isStorageBlocked()) {
            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            return
        }
        if (backendDevice != null) {
            restoreBackendDeviceSession(backendDevice, operation, expectedFirstRun)
            return
        }
        backendDeviceRestorePending = false
        val recovered = store.recoverRenewingToPendingRevocation()
        if (
            recovered == GatewaySessionStoreResult.BLOCKED ||
            recovered == GatewaySessionStoreResult.STORAGE_FAILURE
        ) {
            warn("WalkSafeSession", "restore_recovery=$recovered")
            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            return
        }
        store.restorePendingRevocation(expectedOrigin)?.let { pending ->
            val pendingOperation =
                GatewaySessionProcessCoordinator.publishPendingRevocation(operation)
                    ?: return
            drainPendingGatewayRevocation(pending, pendingOperation)
            return
        }
        val restored = store.restoreActive(
            expectedGatewayBaseUrl = expectedOrigin,
        )
        if (restored == null) {
            store.restorePendingRevocation(expectedOrigin)?.let { pending ->
                val pendingOperation =
                    GatewaySessionProcessCoordinator.publishPendingRevocation(operation)
                        ?: return
                drainPendingGatewayRevocation(pending, pendingOperation)
                return
            }
        }
        if (!config.longLivedLoginEnabled) {
            if (restored == null) {
                if (store.purgeDisabled()) {
                    GatewaySessionProcessCoordinator.clear(operation)
                } else {
                    GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                }
                return
            }
            stageRestoredGatewayRevocation(restored, operation)
            return
        }
        if (restored == null) {
            GatewaySessionProcessCoordinator.clear(operation)
            return
        }
        if (
            restored.session.verificationState !=
            GatewaySessionVerificationState.RESTORED_UNVERIFIED
        ) {
            restored.session.invalidate()
            warn("WalkSafeSession", "restore=verification_state_invalid")
            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            return
        }
        val actorId = restored.firstRunSnapshot.verifiedActorBinding?.value
        if (actorId == null || actorId != restored.session.actorId) {
            restored.session.invalidate()
            warn("WalkSafeSession", "restore=actor_binding_mismatch")
            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            return
        }
        if (!profile.restoreFirstRun(restored.firstRunSnapshot, expectedFirstRun)) {
            restored.session.invalidate()
            GatewaySessionProcessCoordinator.clear(operation)
            return
        }
        profile.reporterActorId = actorId
        effects.invalidateSpeechStorage()
        permissionSessionPolicy.rememberActor(actorId)
        profile.restorePriorityProfile()
        if (
            profile.priorityActorId() != actorId ||
            profile.accountBlocked()
        ) {
            stageRestoredGatewayRevocation(restored, operation)
            return
        }
        val effectiveRestored = restored.copy(firstRunSnapshot = profile.firstRunSnapshot)
        val renewalOperation =
            GatewaySessionProcessCoordinator.publishRestoredUnverified(
                operation = operation,
                session = restored.session,
                firstRunSnapshot = effectiveRestored.firstRunSnapshot,
            ) ?: return
        renewRestoredGatewaySession(effectiveRestored, renewalOperation)
    }

    fun retryPendingGatewayRenewal() {
        restorationTimer.remove(gatewayRenewalRetryRunnable)
        // login-F1: every session publish and foreground return re-arms the in-run renewal.
        scheduleInRunGatewaySessionRenewal()
        val retryAt = GatewaySessionProcessCoordinator.snapshot().renewalRetryAtElapsedMs ?: return
        if (!runtime.foreground() || !runtime.startupInspectionComplete() || activity.destroyed() ||
            deletion.processingBlocked()
        ) return
        val remainingMs = retryAt - elapsedRealtimeMs()
        if (remainingMs > 0L) {
            restorationTimer.postDelayed(gatewayRenewalRetryRunnable, remainingMs)
            return
        }
        restoreGatewaySessionFromPrefs()
    }

    fun retryPendingBackendDeviceRestore() {
        if (
            backendDeviceRestorePending && runtime.foreground() &&
            runtime.startupInspectionComplete() && !activity.destroyed() &&
            !deletion.durableConfirmationRecoveryRequired()
        ) {
            restoreGatewaySessionFromPrefs()
        }
    }

    private fun restoreBackendDeviceSession(
        restored: RestoredGatewayLoginBundle,
        operation: GatewaySessionOperation,
        expectedFirstRun: FirstRunOnboardingSnapshot,
    ) {
        if (!profile.restoreFirstRun(restored.firstRunSnapshot, expectedFirstRun)) {
            restored.session.invalidate()
            GatewaySessionProcessCoordinator.clear(operation)
            return
        }
        val effectiveFirstRun = profile.firstRunSnapshot
        backendDeviceRestorePending = true
        val expectedActivityLease = activity.currentLease()
        val verificationOperation = GatewaySessionProcessCoordinator.publishRestoredUnverified(
            operation, restored.session, effectiveFirstRun,
        ) ?: return
        fun unavailable() {
            val cleared = GatewaySessionProcessCoordinator.clear(verificationOperation) ?: return
            postGatewayActivityCallback(expectedActivityLease) {
                if (GatewaySessionProcessCoordinator.snapshot().generation != cleared.generation) {
                    return@postGatewayActivityCallback
                }
                ui.showAccountMessage("저장된 로그인을 확인하려면 인터넷 연결이 필요합니다. 연결 후 다시 확인하세요.")
                ui.refreshFirstRun()
            }
        }
        try {
            executor.execute {
                try {
                    val verified = remote.revalidateBackendAccountDeviceSession(restored.session)
                    if (!GatewaySessionProcessCoordinator.publishVerified(
                            verificationOperation, verified, effectiveFirstRun,
                        )
                    ) {
                        verified.invalidate()
                        return@execute
                    }
                    backendDeviceRestorePending = false
                    postGatewayActivityCallback(expectedActivityLease) {
                        if (gatewayFieldSession !== verified) return@postGatewayActivityCallback
                        ui.setAccountAccessNotice(null)
                        ui.firstRunChanged("저장된 로그인으로 이어갑니다.")
                        ui.refreshIntegratedConsent()
                    }
                } catch (error: GatewaySessionHttpException) {
                    if (error.reason == "gateway_backend_device_restore_unavailable") {
                        unavailable()
                        return@execute
                    }
                    val process = GatewaySessionProcessCoordinator.snapshot()
                    if (process.inFlightOperationId != verificationOperation.operationId ||
                        process.generation != verificationOperation.generation
                    ) return@execute
                    val removed = store.removeBackendDeviceIfCurrent(restored.version)
                    if (removed == GatewaySessionStoreResult.BLOCKED ||
                        removed == GatewaySessionStoreResult.STORAGE_FAILURE
                    ) {
                        GatewaySessionProcessCoordinator.markStorageBlocked(verificationOperation)
                    } else {
                        backendDeviceRestorePending = false
                        GatewaySessionProcessCoordinator.clear(verificationOperation)
                        postGatewayActivityCallback(expectedActivityLease) {
                            ui.showAccountMessage("저장된 로그인이 만료되었거나 해제되었습니다. 다시 로그인하세요.")
                            ui.refreshFirstRun()
                        }
                    }
                } catch (_: Exception) {
                    unavailable()
                }
            }
        } catch (_: RejectedExecutionException) {
            unavailable()
        }
    }

    private fun stageRestoredGatewayRevocation(
        restored: RestoredGatewayLoginBundle,
        operation: GatewaySessionOperation,
    ) {
        val staged = store.moveActiveToPendingRevocation(
            expectedVersion = restored.version,
            operationId = operation.operationId,
        )
        restored.session.invalidate()
        effects.invalidateSpeechStorage()
        permissionSessionPolicy.authenticationExpired()
        if (
            staged != GatewaySessionStoreResult.COMMITTED &&
            staged != GatewaySessionStoreResult.ALREADY_COMMITTED
        ) {
            if (
                staged == GatewaySessionStoreResult.BLOCKED ||
                staged == GatewaySessionStoreResult.STORAGE_FAILURE
            ) {
                GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            } else {
                GatewaySessionProcessCoordinator.clear(operation)
            }
            return
        }
        val pending = store.restorePendingRevocation(
            restored.version.gatewayBaseUrl,
        )
        val pendingOperation =
            GatewaySessionProcessCoordinator.publishPendingRevocation(operation)
                ?: return
        if (pending == null) {
            GatewaySessionProcessCoordinator.markStorageBlocked(pendingOperation)
            return
        }
        drainPendingGatewayRevocation(pending, pendingOperation)
    }

    private fun drainPendingGatewayRevocation(
        pending: GatewayPendingRevocation,
        operation: GatewaySessionOperation,
        result: ((Boolean) -> Unit)? = null,
        expectedActivityLease: AccountDeletionActivityLease =
            activity.currentLease(),
    ) {
        try {
            executor.execute {
                val remoteRevoked = runCatching {
                    remote.logout(pending)
                    true
                }.getOrDefault(false)
                val completed = if (remoteRevoked) {
                    store.completePendingRevocation(
                        expectedVersion = pending.version,
                        operationId = pending.operationId,
                    )
                } else {
                    null
                }
                val fullyCompleted =
                    remoteRevoked &&
                        (
                            completed == GatewaySessionStoreResult.COMMITTED ||
                                completed == GatewaySessionStoreResult.ALREADY_COMMITTED ||
                                completed == GatewaySessionStoreResult.NOT_FOUND
                            )
                when {
                    fullyCompleted || !remoteRevoked ||
                        completed == GatewaySessionStoreResult.STALE ->
                        GatewaySessionProcessCoordinator.clear(operation)
                    else -> {
                        warn(
                            "WalkSafeGatewaySession",
                            "phase=revocation_complete result=${completed?.name ?: "UNKNOWN"}",
                        )
                        GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                    }
                }
                if (result != null) {
                    postGatewayActivityCallback(expectedActivityLease) {
                        result(fullyCompleted)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            GatewaySessionProcessCoordinator.clear(operation)
            if (result != null) {
                postGatewayActivityCallback(expectedActivityLease) { result(false) }
            }
        }
    }

    fun onDeletionPasswordLoginClicked() {
        val email = inputs.deletionRecoveryEmail().orEmpty()
        val password = inputs.deletionRecoveryPassword().orEmpty()
        inputs.clearDeletionRecoveryPasswordInput()
        val expectedLease = activity.currentLease()
        fun showMessage(message: String) {
            ui.showDeletionPasswordMessage(message)
            ui.speakInteraction(message)
            ui.refreshAccountDeletion()
            ui.refreshBackendAuthButton()
        }
        val binding = deletion.passwordRecoveryBindingOrNull()
        if (binding == null || !gatewayActivityCallbackAllowed(expectedLease)) {
            showMessage("삭제 요청 상태를 확인한 뒤 다시 로그인하세요.")
            return
        }
        if (!GatewayAccountInputPolicy.validEmail(email) || !GatewayAccountInputPolicy.validPassword(password)) {
            showMessage("이메일과 비밀번호를 확인하세요.")
            return
        }
        val origin = inputs.configuredGatewayOrigin()
        val approvedOrigin = if (config.debug) GatewayEndpointPolicy.debugOriginOrNull(origin)
            else GatewayEndpointPolicy.approvedReleaseOriginOrNull(origin, config.approvedOrigin)
        val before = GatewaySessionProcessCoordinator.snapshot()
        val previous = before.session
        val firstRun = profile.firstRunSnapshot
        if (
            origin != binding.gatewayOrigin || approvedOrigin != binding.gatewayOrigin ||
            profile.loginActorId() != binding.actorId ||
            !deletion.isRecoveryLoginTarget(binding.actorId, binding.gatewayOrigin) ||
            before.storageBlocked || before.inFlightOperationId != null ||
            (previous != null && (previous.actorId != binding.actorId || previous.gatewayBaseUrl != origin)) ||
            (previous?.sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY && !before.deletionRecoveryOnly)
        ) {
            showMessage("현재 삭제 요청의 로그인 상태를 확인할 수 없습니다.")
            return
        }
        val generations = listOfNotNull(binding.journal?.accountGeneration,
            binding.markerRecord.accountGeneration, previous?.accountGenerationOrNull).distinct()
        if (generations.size > 1) {
            showMessage("현재 삭제 요청의 계정 정보를 확인할 수 없습니다.")
            return
        }
        if (!runtime.networkProbeReady() || runtime.networkTransport() == ActiveNetworkTransport.OFFLINE) {
            showMessage("인터넷 연결을 확인한 뒤 다시 시도하세요.")
            return
        }
        val expectedGeneration = generations.singleOrNull()
        val operation = if (previous != null && previous.sessionScope == GatewaySessionScope.GENERAL) {
            GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(before.generation, previous)
        } else GatewaySessionProcessCoordinator.beginOperation()
        if (operation == null) {
            showMessage("다른 로그인 확인이 끝난 뒤 다시 시도하세요.")
            return
        }
        fun attemptCurrent(): Boolean {
            val current = GatewaySessionProcessCoordinator.snapshot()
            return gatewayActivityCallbackAllowed(expectedLease) &&
                current.generation == operation.generation && current.inFlightOperationId == operation.operationId &&
                current.session === previous && !current.storageBlocked &&
                current.deletionRecoveryOnly == before.deletionRecoveryOnly &&
                profile.firstRunSnapshot == firstRun && profile.loginActorId() == binding.actorId &&
                inputs.configuredGatewayOrigin() == binding.gatewayOrigin &&
                deletion.passwordRecoveryBindingOrNull() == binding &&
                deletion.isRecoveryLoginTarget(binding.actorId, binding.gatewayOrigin)
        }
        fun fail(message: String) {
            if (!GatewaySessionProcessCoordinator.cancelOperationPreservingSession(operation)) return
            postGatewayActivityCallback(expectedLease) {
                val current = GatewaySessionProcessCoordinator.snapshot()
                if (current.inFlightOperationId == null && current.session === previous &&
                    deletion.passwordRecoveryBindingOrNull() == binding && profile.firstRunSnapshot == firstRun
                ) showMessage(message)
            }
        }
        if (!attemptCurrent()) {
            fail("삭제 요청 상태가 바뀌어 로그인하지 않았습니다.")
            return
        }
        showMessage("비밀번호를 확인하고 있습니다.")
        try {
            executor.execute {
                if (!attemptCurrent()) {
                    fail("삭제 요청 상태가 바뀌어 로그인하지 않았습니다.")
                    return@execute
                }
                var candidate: GatewayFieldSession? = null
                try {
                    val session = remote.loginDeletionRecoveryWithPassword(
                        binding.gatewayOrigin, email, password, binding.actorId, expectedGeneration,
                    )
                    candidate = session
                    if (!attemptCurrent() || session === previous ||
                        session.actorId != binding.actorId || session.gatewayBaseUrl != binding.gatewayOrigin ||
                        session.sessionScope != GatewaySessionScope.ACCOUNT_DELETION_RECOVERY ||
                        session.verificationState != GatewaySessionVerificationState.VERIFIED ||
                        !session.isUsableFor(binding.actorId, wallClockMs()) ||
                        expectedGeneration?.let { session.accountGenerationOrNull != it } == true ||
                        !commitLoggedInGatewaySession(session, operation, binding.gatewayOrigin,
                            binding.actorId, expectedLease, expectedPasswordRecoveryBinding = binding)
                    ) throw GatewaySessionHttpException(0, "deletion_password_login_binding_changed")
                    postGatewayActivityCallback(expectedLease) {
                        val current = GatewaySessionProcessCoordinator.snapshot()
                        if (current.session === session && current.deletionRecoveryOnly) {
                            showMessage("비밀번호 확인을 마쳤습니다. 삭제 요청을 이어서 확인합니다.")
                        }
                    }
                } catch (error: Exception) {
                    candidate?.takeUnless { it === previous }?.let {
                        runCatching { remote.logout(it) }
                        it.invalidate()
                    }
                    fail(if (error is GatewaySessionHttpException && error.statusCode == 401)
                        "이메일과 비밀번호를 확인한 뒤 다시 시도하세요."
                        else "삭제 요청 로그인을 확인하지 못했습니다. 잠시 후 다시 시도하세요.")
                } finally {
                    postGatewayActivityCallback(expectedLease) {
                        ui.refreshAccountDeletion()
                        ui.refreshBackendAuthButton()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            fail("지금은 비밀번호 확인을 시작할 수 없습니다. 잠시 후 다시 시도하세요.")
        }
    }

    fun onGatewaySessionButtonClicked() {
        val expectedActivityLease = activity.currentLease()
        val processSnapshot = GatewaySessionProcessCoordinator.snapshot()
        val deletionRecoverySurface =
            deletion.recoveryLoginRequired() ||
                processSnapshot.deletionRecoveryOnly
        if (!config.debug && !deletionRecoverySurface) return
        if (processSnapshot.deletionRecoveryOnly) {
            if (
                deletion.processingBlocked() &&
                !deletion.recoveryLoginRequired()
            ) {
                ui.status("gateway=account_deletion_session_reserved")
                ui.speakInteraction("계정 삭제 처리 전용 로그인이 유지되고 있습니다.")
                return
            }
            if (
                deletion.recoverySessionOrNull(
                    processSnapshot.session,
                ) != null
            ) {
                ui.status("gateway=deletion_recovery_session_ready")
                ui.speakInteraction("삭제 요청 복구 로그인이 유지되고 있습니다.")
                return
            }
            if (
                GatewaySessionProcessCoordinator.clear(
                    processSnapshot.generation,
                ) == null
            ) return
        }
        val currentSession = gatewayFieldSession
        if (currentSession != null) {
            ui.status("gateway=remote_revoke_pending")
            ui.speakInteraction("로컬 로그아웃을 완료했습니다. 서버 해제를 확인 중입니다.")
            clearGatewaySession(
                logoutRemote = true,
                expectedSession = currentSession,
                remoteLogoutResult = { confirmed ->
                    if (confirmed) {
                        ui.status("gateway=remote_revoke_confirmed")
                        ui.speakInteraction("서버의 기기 로그인 해제를 확인했습니다.")
                    } else {
                        ui.status("gateway=remote_revoke_unconfirmed")
                        ui.speakInteraction("로컬 로그아웃은 완료했지만 서버 해제는 확인하지 못했습니다.")
                    }
                },
                expectedActivityLease = expectedActivityLease,
            )
            return
        }
        if (!runtime.gatewayNetworkAllowed(reason = "gateway_login")) return
        val baseUrl = if (config.debug) {
            GatewayEndpointPolicy.debugOriginOrNull(inputs.debugGatewayUrl())
        } else {
            GatewayEndpointPolicy.approvedReleaseOriginOrNull(
                raw = config.approvedOrigin,
                approvedOrigin = config.approvedOrigin,
            )
        }
        val actorId = profile.loginActorId()
        val token = if (inputs.hasTokenInput()) {
            inputs.token()
        } else {
            null
        }
        if (inputs.hasTokenInput()) {
            inputs.clearTokenInput()
        }
        if (baseUrl == null || actorId == null || GatewayCredentialPolicy.normalizedTokenOrNull(token) == null) {
            ui.refreshBackendAuthButton()
            ui.status("gateway=login_input_invalid named_actor_and_account_token_required")
            ui.speakInteraction("게이트웨이 주소, 로그인 아이디와 계정 토큰을 확인하세요.")
            return
        }
        val deletionRecoveryTarget =
            deletion.isRecoveryLoginTarget(actorId, baseUrl)
        val operation = GatewaySessionProcessCoordinator.beginOperation() ?: return
        val deviceId = if (
            config.longLivedLoginEnabled &&
            !deletionRecoveryTarget
        ) {
            store.getOrCreateInstallDeviceId()
        } else {
            null
        }
        if (
            config.longLivedLoginEnabled &&
            !deletionRecoveryTarget &&
            deviceId == null
        ) {
            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            ui.refreshBackendAuthButton()
            ui.status("gateway=login_failed storage_blocked")
            ui.speakInteraction("기기 로그인 저장소를 사용할 수 없어 로그인하지 않았습니다.")
            return
        }
        val feedbackGeneration = runtime.feedbackGeneration()
        ui.refreshBackendAuthButton()
        ui.status(
            if (deletionRecoveryTarget) {
                "gateway=login_pending purpose=account_deletion_recovery"
            } else {
                "gateway=login_pending purpose=general"
            },
        )
        try {
            executor.execute {
                if (
                    !gatewayActivityCallbackAllowed(expectedActivityLease) ||
                    GatewaySessionProcessCoordinator.snapshot()
                        .inFlightOperationId != operation.operationId
                ) {
                    GatewaySessionProcessCoordinator.clear(operation)
                    return@execute
                }
                try {
                    val session = remote.login(
                        gatewayBaseUrl = baseUrl,
                        actorId = actorId,
                        token = token,
                        enableLongLivedSession =
                            config.longLivedLoginEnabled &&
                                !deletionRecoveryTarget,
                        deviceId = deviceId,
                        sessionScope =
                            if (deletionRecoveryTarget) {
                                GatewaySessionScope.ACCOUNT_DELETION_RECOVERY
                            } else {
                                GatewaySessionScope.GENERAL
                            },
                    )
                    if (!gatewayActivityCallbackAllowed(expectedActivityLease)) {
                        runCatching { remote.logout(session) }
                        GatewaySessionProcessCoordinator.clear(operation)
                        return@execute
                    }
                    val deletionRecoveryLogin =
                        deletion.isRecoveryLoginTarget(
                            actorId = session.actorId,
                            gatewayBaseUrl = session.gatewayBaseUrl,
                        )
                    val boundToCurrentProfile =
                        gatewayActivityCallbackAllowed(expectedActivityLease) &&
                            GatewaySessionProcessCoordinator.snapshot()
                            .inFlightOperationId == operation.operationId &&
                            profile.loginActorId() == session.actorId &&
                            (
                                deletionRecoveryLogin ||
                                    profile.priorityActorId() == session.actorId
                                )
                    if (!boundToCurrentProfile) {
                        runCatching { remote.logout(session) }
                        GatewaySessionProcessCoordinator.clear(operation)
                        return@execute
                    }
                    val accepted = commitLoggedInGatewaySession(
                        session = session,
                        operation = operation,
                        expectedGatewayBaseUrl = baseUrl,
                        expectedActorId = actorId,
                        expectedActivityLease = expectedActivityLease,
                    )
                    if (!accepted) {
                        runCatching { remote.logout(session) }
                        GatewaySessionProcessCoordinator.clear(operation)
                        return@execute
                    }
                    if (!deletionRecoveryLogin) {
                        store.persistGatewayOrigin(session.gatewayBaseUrl)
                        effects.resetReportAttemptFailures()
                    }
                    postGatewayActivityCallback(expectedActivityLease) {
                        if (
                            !isCurrentGatewaySession(session) &&
                            deletion.recoverySessionOrNull(session) !== session
                        ) return@postGatewayActivityCallback
                        ui.refreshReportPrivacy()
                        ui.refreshAccountDeletion()
                        ui.status(
                            if (deletionRecoveryLogin) {
                                "gateway=session_ready purpose=account_deletion_recovery"
                            } else {
                                "gateway=session_ready purpose=general"
                            },
                        )
                        if (runtime.feedbackGenerationIsCurrent(feedbackGeneration)) {
                            ui.speakInteraction("현장 게이트웨이 로그인이 완료되었습니다.")
                        }
                    }
                } catch (error: GatewaySessionHttpException) {
                    val attemptCurrent =
                        gatewayActivityCallbackAllowed(expectedActivityLease) &&
                            GatewaySessionProcessCoordinator.snapshot()
                            .inFlightOperationId == operation.operationId &&
                            profile.loginActorId() == actorId
                    GatewaySessionProcessCoordinator.clear(operation)
                    postGatewayActivityCallback(expectedActivityLease) {
                        if (!attemptCurrent) return@postGatewayActivityCallback
                        ui.status("gateway=login_failed status=${error.statusCode} reason=${error.reason}")
                        if (runtime.feedbackGenerationIsCurrent(feedbackGeneration)) {
                            ui.speakInteraction("현장 게이트웨이 로그인에 실패했습니다.")
                        }
                    }
                } catch (_: Exception) {
                    val attemptCurrent =
                        gatewayActivityCallbackAllowed(expectedActivityLease) &&
                            GatewaySessionProcessCoordinator.snapshot()
                            .inFlightOperationId == operation.operationId &&
                            profile.loginActorId() == actorId
                    GatewaySessionProcessCoordinator.clear(operation)
                    postGatewayActivityCallback(expectedActivityLease) {
                        if (!attemptCurrent) return@postGatewayActivityCallback
                        ui.status("gateway=login_failed network")
                        if (runtime.feedbackGenerationIsCurrent(feedbackGeneration)) {
                            ui.speakInteraction("현장 게이트웨이 로그인에 실패했습니다.")
                        }
                    }
                } finally {
                    postGatewayActivityCallback(expectedActivityLease) {
                        ui.refreshBackendAuthButton()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            GatewaySessionProcessCoordinator.clear(operation)
            if (gatewayActivityCallbackAllowed(expectedActivityLease)) {
                ui.refreshBackendAuthButton()
                ui.status("gateway=login_failed executor_rejected")
            }
        }
    }

    /**
     * Durable reserve -> renew -> commit of a long-lived login. [inRun] is set when the running
     * app renews its published login (login-F1); otherwise this is the restored login at start.
     */
    private fun renewRestoredGatewaySession(
        restored: RestoredGatewayLoginBundle,
        operation: GatewaySessionOperation,
        inRun: GatewayInRunRenewalContext? = null,
    ) {
        val expectedActivityLease = activity.currentLease()
        val reserved = store.reserveRenewal(
            expectedVersion = restored.version,
            operationId = operation.operationId,
        )
        if (
            reserved != GatewaySessionStoreResult.COMMITTED &&
            reserved != GatewaySessionStoreResult.ALREADY_COMMITTED
        ) {
            if (
                reserved == GatewaySessionStoreResult.BLOCKED ||
                reserved == GatewaySessionStoreResult.STORAGE_FAILURE
            ) {
                warn(
                    "WalkSafeGatewaySession",
                    "phase=renew_reserve result=${reserved.name}",
                )
                GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            } else {
                GatewaySessionProcessCoordinator.clear(operation)
            }
            inRun?.let { completeInRunGatewaySessionRenewal(it, expectedActivityLease) }
            return
        }
        try {
            executor.execute {
                try {
                    val renewal = remote.renew(
                        session = restored.session,
                        actorId = restored.session.actorId,
                    )
                    if (renewal.sourceLease != restored.session.lease) {
                        renewal.session.invalidate()
                        throw IllegalStateException("gateway_session_renewal_lease_mismatch")
                    }
                    val stored = store.commitRenewal(
                        expectedVersion = restored.version,
                        operationId = operation.operationId,
                        renewedSession = renewal.session,
                        firstRunSnapshot = restored.firstRunSnapshot,
                    )
                    if (
                        stored != GatewaySessionStoreResult.COMMITTED &&
                        stored != GatewaySessionStoreResult.ALREADY_COMMITTED
                    ) {
                        if (stored == GatewaySessionStoreResult.STALE) {
                            renewal.session.invalidate()
                        } else {
                            runCatching { remote.logout(renewal.session) }
                        }
                        if (
                            stored == GatewaySessionStoreResult.BLOCKED ||
                            stored == GatewaySessionStoreResult.STORAGE_FAILURE
                        ) {
                            warn(
                                "WalkSafeGatewaySession",
                                "phase=renew_commit result=${stored.name}",
                            )
                            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                        } else {
                            GatewaySessionProcessCoordinator.clear(operation)
                        }
                        return@execute
                    }
                    val published = GatewaySessionProcessCoordinator.publishVerified(
                        operation = operation,
                        session = renewal.session,
                        firstRunSnapshot = inRun?.publishFirstRunSnapshot ?: restored.firstRunSnapshot,
                    )
                    if (!published) {
                        runCatching { remote.logout(renewal.session) }
                        return@execute
                    }
                    postGatewayActivityCallback(expectedActivityLease) {
                        if (!isCurrentGatewaySession(renewal.session)) {
                            return@postGatewayActivityCallback
                        }
                        ui.refreshBackendAuthButton()
                        ui.refreshReportPrivacy()
                        ui.status(
                            if (inRun != null) {
                                "gateway=session_renewed_in_run purpose=general"
                            } else {
                                "gateway=session_restored_verified purpose=general"
                            },
                        )
                    }
                } catch (error: RuntimeException) {
                    // Same failure mapping for a restored and an in-run renewal (login-F1).
                    val failure = GatewayInRunRenewalPolicy.failureAction(error, restored.session.lease)
                    if (failure is GatewayRenewalFailureAction.DuplicateAttempt) {
                        return@execute
                    }
                    if (failure is GatewayRenewalFailureAction.RetryAfterRollback) {
                        val retryReservation = failure.reservation
                        val rolledBack = store.rollbackRenewalForRetry(
                            restored.version, operation.operationId, retryReservation,
                        )
                        when (rolledBack) {
                            GatewaySessionStoreResult.COMMITTED -> {
                                val retryAt = elapsedRealtimeMs() + retryReservation.retryAfterMs
                                // login-F1: the durable login is unchanged, so the running app keeps
                                // its published credential and retries after the busy answer.
                                val kept = inRun != null &&
                                    GatewaySessionProcessCoordinator.publishVerified(
                                        operation, inRun.sourceSession, inRun.publishFirstRunSnapshot,
                                    )
                                if (kept) {
                                    inRun?.retryNotBeforeElapsedMs = retryAt
                                } else {
                                    GatewaySessionProcessCoordinator.deferRenewalAfterRollback(
                                        operation, retryAt,
                                    )
                                }
                            }
                            GatewaySessionStoreResult.NOT_FOUND,
                            GatewaySessionStoreResult.STALE -> GatewaySessionProcessCoordinator.clear(operation)
                            else -> GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                        }
                        return@execute
                    }
                    val abandoned = store.abandonRenewal(
                        expectedVersion = restored.version,
                        operationId = operation.operationId,
                    )
                    when (abandoned) {
                        GatewaySessionStoreResult.COMMITTED,
                        GatewaySessionStoreResult.ALREADY_COMMITTED -> {
                            val pending = store.restorePendingRevocation(
                                restored.version.gatewayBaseUrl,
                            )
                            if (pending == null) {
                                warn(
                                    "WalkSafeGatewaySession",
                                    "phase=renew_pending_restore result=${abandoned.name}",
                                )
                                GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                            } else {
                                GatewaySessionProcessCoordinator
                                    .publishPendingRevocation(operation)
                                    ?.let { pendingOperation ->
                                        drainPendingGatewayRevocation(pending, pendingOperation)
                                    }
                            }
                        }
                        GatewaySessionStoreResult.NOT_FOUND,
                        GatewaySessionStoreResult.STALE ->
                            GatewaySessionProcessCoordinator.clear(operation)
                        GatewaySessionStoreResult.BLOCKED,
                        GatewaySessionStoreResult.STORAGE_FAILURE -> {
                            warn(
                                "WalkSafeGatewaySession",
                                "phase=renew_abandon result=${abandoned.name}",
                            )
                            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                        }
                    }
                    // failure is Abandon here; its notice is GatewayRenewalFailureNotice.of(error).
                    GatewayRenewalFailureNotice.of(error)?.let { notice ->
                        postGatewayActivityCallback(expectedActivityLease) {
                            ui.status(notice.statusToken)
                            val statusBefore = if (ui.accountStatusInitialized()) {
                                ui.accountStatusText()
                            } else {
                                null
                            }
                            // Owner decision 1: the login screen keeps the reason until the next login.
                            ui.setAccountAccessNotice(notice.speech)
                            ui.refreshFirstRun()
                            // login-F9 (S13-30-F1): the POLITE live region already reads a shown,
                            // changed status; speaking it too would read the sentence twice.
                            val readByLiveRegion = ui.accountStatusInitialized() &&
                                GatewayInRunRenewalPolicy.liveRegionReadsNotice(
                                    screenReaderActive = runtime.screenReaderActive(),
                                    statusShown = ui.accountStatusShown(),
                                    statusBefore = statusBefore,
                                    statusAfter = ui.accountStatusText(),
                                    message = notice.speech,
                                )
                            if (!readByLiveRegion) ui.speakInteraction(notice.speech)
                        }
                    }
                } finally {
                    postGatewayActivityCallback(expectedActivityLease) {
                        ui.refreshBackendAuthButton()
                    }
                    inRun?.let { completeInRunGatewaySessionRenewal(it, expectedActivityLease) }
                }
            }
        } catch (_: RejectedExecutionException) {
            val abandoned = store.abandonRenewal(
                expectedVersion = restored.version,
                operationId = operation.operationId,
            )
            when (abandoned) {
                GatewaySessionStoreResult.COMMITTED,
                GatewaySessionStoreResult.ALREADY_COMMITTED -> {
                    val pending = store.restorePendingRevocation(
                        restored.version.gatewayBaseUrl,
                    )
                    if (pending == null) {
                        warn(
                            "WalkSafeGatewaySession",
                            "phase=renew_rejected_pending_restore result=${abandoned.name}",
                        )
                        GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                    } else {
                        GatewaySessionProcessCoordinator.publishPendingRevocation(operation)
                            ?.let(GatewaySessionProcessCoordinator::clear)
                    }
                }
                GatewaySessionStoreResult.NOT_FOUND,
                GatewaySessionStoreResult.STALE ->
                    GatewaySessionProcessCoordinator.clear(operation)
                GatewaySessionStoreResult.BLOCKED,
                GatewaySessionStoreResult.STORAGE_FAILURE -> {
                    warn(
                        "WalkSafeGatewaySession",
                        "phase=renew_rejected_abandon result=${abandoned.name}",
                    )
                    GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                }
            }
            inRun?.let { completeInRunGatewaySessionRenewal(it, expectedActivityLease) }
        }
    }

    private fun commitLoggedInGatewaySession(
        session: GatewayFieldSession,
        operation: GatewaySessionOperation,
        expectedGatewayBaseUrl: String,
        expectedActorId: String,
        expectedActivityLease: AccountDeletionActivityLease,
        expectedPasswordRecoveryBinding: AccountDeletionPasswordRecoveryBinding? = null,
    ): Boolean {
        effects.invalidateSpeechStorage()
        if (!gatewayActivityCallbackAllowed(expectedActivityLease)) return false
        val actorId = profile.loginActorId()
        val deletionRecoveryLogin =
            deletion.isRecoveryLoginTarget(
                actorId = expectedActorId,
                gatewayBaseUrl = expectedGatewayBaseUrl,
            )
        if (expectedPasswordRecoveryBinding != null &&
            (!deletionRecoveryLogin || deletion.passwordRecoveryBindingOrNull() != expectedPasswordRecoveryBinding ||
                inputs.configuredGatewayOrigin() != expectedPasswordRecoveryBinding.gatewayOrigin)
        ) return false
        if (
            GatewaySessionProcessCoordinator.snapshot().inFlightOperationId !=
            operation.operationId ||
            session.verificationState != GatewaySessionVerificationState.VERIFIED ||
            !session.isUsableFor(actorId) ||
            actorId != session.actorId ||
            actorId != expectedActorId ||
            session.gatewayBaseUrl != expectedGatewayBaseUrl ||
            (!deletionRecoveryLogin && profile.priorityActorId() != session.actorId)
        ) {
            return false
        }
        if (deletionRecoveryLogin) {
            if (!gatewayActivityCallbackAllowed(expectedActivityLease)) return false
            if (expectedPasswordRecoveryBinding != null &&
                deletion.passwordRecoveryBindingOrNull() != expectedPasswordRecoveryBinding
            ) return false
            return GatewaySessionProcessCoordinator.publishDeletionRecoveryVerified(
                operation = operation,
                session = session,
                expectedActorId = expectedActorId,
                expectedGatewayBaseUrl = expectedGatewayBaseUrl,
            )
        }
        if (config.longLivedLoginEnabled) {
            val stored = store.saveInitialIfAbsent(
                session = session,
                firstRunSnapshot = profile.firstRunSnapshot,
                expectedGatewayBaseUrl = expectedGatewayBaseUrl,
            )
            if (
                stored != GatewaySessionStoreResult.COMMITTED &&
                stored != GatewaySessionStoreResult.ALREADY_COMMITTED
            ) {
                if (stored == GatewaySessionStoreResult.STORAGE_FAILURE) {
                    GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                } else {
                    GatewaySessionProcessCoordinator.clear(operation)
                }
                session.invalidate()
                effects.downgradeSession(
                    "gateway_session_storage_blocked",
                    expectedActivityLease,
                )
                return false
            }
        }
        if (!gatewayActivityCallbackAllowed(expectedActivityLease)) return false
        val published = GatewaySessionProcessCoordinator.publishVerified(
            operation = operation,
            session = session,
            firstRunSnapshot = profile.firstRunSnapshot,
        )
        if (!published) {
            effects.downgradeSession(
                "gateway_session_storage_blocked",
                expectedActivityLease,
            )
        }
        return published
    }

    fun clearGatewaySession(
        logoutRemote: Boolean,
        expectedSession: GatewayFieldSession? = null,
        remoteLogoutResult: ((Boolean) -> Unit)? = null,
        expectedActivityLease: AccountDeletionActivityLease =
            activity.currentLease(),
        expectedProcessGeneration: Long? = null,
    ): Long? {
        effects.invalidateSpeechStorage()
        val before = GatewaySessionProcessCoordinator.snapshot()
        if (
            before.deletionRecoveryOnly &&
            deletion.durableConfirmationRecoveryRequired()
        ) return null
        if (expectedSession != null && before.session !== expectedSession) return null
        val previous = before.session ?: if (backendDeviceRestorePending) {
            inputs.configuredGatewayOrigin()?.let { origin ->
                store.restoreBackendDevice(origin)?.session
            }
        } else null
        effects.cancelDestinationSearchLocation()
        val shouldDowngradeWalk =
            previous != null || permissionSessionPolicy.snapshot().mayUseProtectedServerFeature
        val operation = if (expectedProcessGeneration != null && expectedSession != null) {
            GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(
                expectedGeneration = expectedProcessGeneration,
                expectedSession = expectedSession,
            )
        } else {
            GatewaySessionProcessCoordinator.beginOperation()
        } ?: return null
        val version = previous?.versionOrNull
        val backendDevice = previous?.isBackendAccountDeviceBound == true
        var mayLogoutPrevious = true
        var revocationStorageBlocked =
            ::store.isInitialized && store.isStorageBlocked()
        val pending = if (version != null && ::store.isInitialized) {
            val staged = if (backendDevice) {
                store.removeBackendDeviceIfCurrent(version)
            } else {
                store.moveActiveToPendingRevocation(
                    expectedVersion = version,
                    operationId = operation.operationId,
                )
            }
            when (staged) {
                GatewaySessionStoreResult.COMMITTED,
                GatewaySessionStoreResult.ALREADY_COMMITTED -> {
                    val restoredPending = if (backendDevice) null else
                        store.restorePendingRevocation(version.gatewayBaseUrl)
                    if (restoredPending == null && !backendDevice) {
                        revocationStorageBlocked = true
                        warn(
                            "WalkSafeGatewaySession",
                            "phase=clear_pending_restore result=${staged.name}",
                        )
                    }
                    restoredPending
                }
                GatewaySessionStoreResult.NOT_FOUND -> null
                GatewaySessionStoreResult.STALE -> {
                    mayLogoutPrevious = false
                    null
                }
                GatewaySessionStoreResult.BLOCKED,
                GatewaySessionStoreResult.STORAGE_FAILURE -> {
                    revocationStorageBlocked = true
                    warn(
                        "WalkSafeGatewaySession",
                        "phase=clear_stage result=${staged.name}",
                    )
                    null
                }
            }
        } else {
            null
        }
        if (!revocationStorageBlocked) backendDeviceRestorePending = false
        val pendingOperation = if (revocationStorageBlocked) {
            if (!GatewaySessionProcessCoordinator.markStorageBlocked(operation)) return null
            null
        } else {
            GatewaySessionProcessCoordinator.publishPendingRevocation(operation)
                ?: return null
        }
        effects.invalidateSpeechStorage()
        permissionSessionPolicy.authenticationExpired()
        if (shouldDowngradeWalk) {
            effects.downgradeSession(
                "gateway_session_cleared",
                expectedActivityLease,
            )
        }
        effects.cancelReportTransfers()
        effects.cancelGatewayNetworkCalls()
        val clearCredentialUi = {
            if (inputs.hasTokenInput()) inputs.clearTokenInput()
            effects.clearGatewayNavigationState()
            ui.refreshBackendAuthButton()
            ui.refreshReportPrivacy()
        }
        if (isMainThread()) {
            if (gatewayActivityCallbackAllowed(expectedActivityLease)) {
                clearCredentialUi()
            }
        } else {
            postGatewayActivityCallback(expectedActivityLease, clearCredentialUi)
        }
        if (pendingOperation == null) {
            if (logoutRemote && previous != null && mayLogoutPrevious) {
                try {
                    executor.execute {
                        val confirmed = runCatching {
                            remote.logout(previous)
                            true
                        }.getOrDefault(false)
                        if (remoteLogoutResult != null) {
                            postGatewayActivityCallback(expectedActivityLease) {
                                remoteLogoutResult(confirmed)
                            }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    if (remoteLogoutResult != null) {
                        postGatewayActivityCallback(expectedActivityLease) {
                            remoteLogoutResult(false)
                        }
                    }
                }
            }
            return GatewaySessionProcessCoordinator.snapshot().generation
        }
        if (!logoutRemote) {
            GatewaySessionProcessCoordinator.clear(pendingOperation)
            return pendingOperation.generation
        }
        if (pending != null) {
            drainPendingGatewayRevocation(
                pending = pending,
                operation = pendingOperation,
                result = remoteLogoutResult,
                expectedActivityLease = expectedActivityLease,
            )
            return pendingOperation.generation
        }
        if (previous == null || !mayLogoutPrevious) {
            GatewaySessionProcessCoordinator.clear(pendingOperation)
            if (logoutRemote && remoteLogoutResult != null) {
                postGatewayActivityCallback(expectedActivityLease) {
                    remoteLogoutResult(false)
                }
            }
            return pendingOperation.generation
        }
        try {
            executor.execute {
                val confirmed = runCatching {
                    remote.logout(previous)
                    true
                }.getOrDefault(false)
                GatewaySessionProcessCoordinator.clear(pendingOperation)
                if (remoteLogoutResult != null) {
                    postGatewayActivityCallback(expectedActivityLease) {
                        remoteLogoutResult(confirmed)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            GatewaySessionProcessCoordinator.clear(pendingOperation)
            if (remoteLogoutResult != null) {
                postGatewayActivityCallback(expectedActivityLease) {
                    remoteLogoutResult(false)
                }
            }
        }
        return pendingOperation.generation
    }

    fun isGatewaySessionReadyForCurrentActor(
        session: GatewayFieldSession? = gatewayFieldSession,
    ): Boolean {
        val actorId = profile.currentReporterActorId()
        val processSnapshot = GatewaySessionProcessCoordinator.snapshot()
        return !processSnapshot.storageBlocked &&
            session != null &&
            processSnapshot.session === session &&
            session.verificationState == GatewaySessionVerificationState.VERIFIED &&
            session.isUsableFor(actorId) &&
            (runtime.developmentQuickStartEnabled() || permissionSessionPolicy.isAuthenticatedFor(actorId))
    }

    fun clearExpiredGatewaySessionIfCurrent(session: GatewayFieldSession) {
        if (
            session.verificationState != GatewaySessionVerificationState.VERIFIED ||
            session.isUsableFor(session.actorId)
        ) return
        // login-F1 (owner decision 2, Root R1): an expired access credential of a renewable
        // long-lived login is renewed durably instead of being moved to pending revocation.
        if (requestInRunGatewaySessionRenewal(session, GatewayInRunRenewalTrigger.ON_DEMAND)) return
        val process = GatewaySessionProcessCoordinator.snapshot()
        clearGatewaySession(
            logoutRemote = false,
            expectedSession = session,
            expectedProcessGeneration = process.generation,
        )
    }

    /**
     * login-F1 (owner decision 2, Root decision R1): renews a renewable long-lived login of the
     * running app through the durable reserve -> renew -> commit path of a restored login
     * ([renewRestoredGatewaySession]); single flight per process, concurrent callers join.
     * Returns true when the caller must not clear the session (renewal started, joined,
     * deferred after a busy answer, or waiting for a network): a connection failure would make
     * the one-use refresh outcome unknown, so no attempt is made while offline.
     */
    private fun requestInRunGatewaySessionRenewal(
        session: GatewayFieldSession,
        trigger: GatewayInRunRenewalTrigger,
        waiter: (() -> Unit)? = null,
    ): Boolean {
        if (
            !config.longLivedLoginEnabled ||
            !::store.isInitialized ||
            activity.destroyed() ||
            deletion.processingBlocked()
        ) return false
        val process = GatewaySessionProcessCoordinator.snapshot()
        if (
            process.session !== session ||
            process.storageBlocked ||
            process.deletionRecoveryOnly ||
            GatewayInRunRenewalPolicy.actionFor(session, trigger, wallClockMs()) !=
            GatewayInRunSessionAction.RENEW
        ) return false
        val gate = GatewayInRunRenewalProcessState.gate
        when (gate.tryBegin(session.lease, elapsedRealtimeMs(), waiter)) {
            GatewayInRunRenewalGate.Admission.JOINED -> return true
            GatewayInRunRenewalGate.Admission.DEFERRED -> {
                // Keep the login for the scheduled retry; a waiting caller continues now.
                postToMain(::scheduleInRunGatewaySessionRenewal)
                waiter?.let { runCatching { it() } }
                return true
            }
            GatewayInRunRenewalGate.Admission.STARTED -> Unit
        }
        if (
            runtime.networkProbeReady() &&
            runtime.networkTransport() == ActiveNetworkTransport.OFFLINE
        ) {
            finishInRunGatewaySessionRenewal(
                session.lease,
                elapsedRealtimeMs() + GatewayInRunRenewalPolicy.RENEWAL_RETRY_DELAY_MS,
            )
            postToMain(::scheduleInRunGatewaySessionRenewal)
            return true
        }
        val restored = store.restoreActive(
            expectedGatewayBaseUrl = session.gatewayBaseUrl,
        )
        if (
            restored == null ||
            restored.version != session.versionOrNull ||
            restored.firstRunSnapshot.verifiedActorBinding?.value != session.actorId
        ) {
            restored?.session?.invalidate()
            finishInRunGatewaySessionRenewal(session.lease)
            return false
        }
        val operation = GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(
            expectedGeneration = process.generation,
            expectedSession = session,
        ) ?: run {
            restored.session.invalidate()
            finishInRunGatewaySessionRenewal(session.lease)
            return false
        }
        val processFirstRun = process.restoredFirstRunSnapshot
            ?.takeIf { it.verifiedActorBinding?.value == session.actorId }
        renewRestoredGatewaySession(
            restored = restored,
            operation = operation,
            inRun = GatewayInRunRenewalContext(
                sourceSession = session,
                publishFirstRunSnapshot = processFirstRun ?: restored.firstRunSnapshot,
            ),
        )
        return true
    }

    private fun finishInRunGatewaySessionRenewal(
        sourceLease: GatewaySessionLease,
        retryNotBeforeElapsedMs: Long? = null,
    ) {
        GatewayInRunRenewalProcessState.gate.finish(sourceLease, retryNotBeforeElapsedMs)
            .forEach { waiter -> runCatching { waiter() } }
    }

    /** Called once per in-run renewal after its durable outcome and process publish. */
    private fun completeInRunGatewaySessionRenewal(
        inRun: GatewayInRunRenewalContext,
        expectedActivityLease: AccountDeletionActivityLease,
    ) {
        finishInRunGatewaySessionRenewal(
            inRun.sourceSession.lease,
            inRun.retryNotBeforeElapsedMs,
        )
        postGatewayActivityCallback(expectedActivityLease) {
            if (GatewaySessionProcessCoordinator.snapshot().session == null) {
                // The renewal ended the login (pending revocation or blocked storage): apply the
                // same runtime downgrade as clearGatewaySession.
                effects.downgradeSession("gateway_session_renewal_failed", expectedActivityLease)
                effects.cancelReportTransfers()
                effects.cancelGatewayNetworkCalls()
                effects.clearGatewayNavigationState()
                ui.refreshReportPrivacy()
            }
            scheduleInRunGatewaySessionRenewal()
        }
    }

    /**
     * login-F4: after the Gateway rejected a long-lived access credential, one durable refresh
     * (bounded per family, [GatewayUnauthorizedRenewalProbe]) either keeps a login the server
     * still has or returns the revocation reason (e.g. `field_session_revoked_reuse_detected`)
     * for [GatewayRenewalFailureNotice]. Returns true when the probe replaced the clear.
     */
    fun probeGatewaySessionAfterUnauthorized(
        session: GatewayFieldSession,
        waiter: (() -> Unit)? = null,
    ): Boolean {
        if (
            GatewaySessionProcessCoordinator.snapshot().session !== session ||
            GatewayInRunRenewalPolicy.actionFor(
                session,
                GatewayInRunRenewalTrigger.UNAUTHORIZED,
                wallClockMs(),
            ) != GatewayInRunSessionAction.RENEW
        ) return false
        if (GatewayInRunRenewalProcessState.gate.joinIfInFlight(waiter ?: {})) return true
        // An access credential that already expired locally is login-F1's case (no probe bound).
        if (!session.isUsableFor(session.actorId)) {
            return requestInRunGatewaySessionRenewal(
                session,
                GatewayInRunRenewalTrigger.ON_DEMAND,
                waiter,
            )
        }
        if (
            !GatewayInRunRenewalProcessState.unauthorizedProbe.tryAcquire(
                session.familyId,
                elapsedRealtimeMs(),
            )
        ) return false
        return requestInRunGatewaySessionRenewal(
            session,
            GatewayInRunRenewalTrigger.UNAUTHORIZED,
            waiter,
        )
    }

    /** login-F1: [previous] was replaced only by an in-run renewal of the same login. */
    fun isRenewedGatewaySessionContinuation(previous: GatewayFieldSession): Boolean {
        val current = gatewayFieldSession ?: return false
        return GatewayInRunRenewalPolicy.isRenewedContinuation(previous, current) &&
            isCurrentGatewaySession(current)
    }

    /** login-F1: queues [waiter] behind a running in-run renewal, or runs it if already renewed. */
    fun awaitInRunGatewaySessionRenewal(waiter: () -> Unit): Boolean {
        if (GatewayInRunRenewalProcessState.gate.joinIfInFlight(waiter)) return true
        if (!isGatewaySessionReadyForCurrentActor(gatewayFieldSession)) return false
        waiter()
        return true
    }

    /** login-F1: arms the proactive renewal about one minute before access expiry. */
    private fun scheduleInRunGatewaySessionRenewal() {
        if (!renewalTimer.isReady()) return
        renewalTimer.remove(inRunGatewayRenewalRunnable)
        if (!config.longLivedLoginEnabled || activity.destroyed()) return
        val process = GatewaySessionProcessCoordinator.snapshot()
        if (process.storageBlocked || process.deletionRecoveryOnly) return
        val delayMs = GatewayInRunRenewalPolicy.proactiveDelayMsOrNull(
            process.session,
            wallClockMs(),
        ) ?: return
        val deferralMs = GatewayInRunRenewalProcessState.gate.deferralRemainingMs(
            elapsedRealtimeMs(),
        )
        renewalTimer.postDelayed(inRunGatewayRenewalRunnable, maxOf(delayMs, deferralMs))
    }

    private fun onInRunGatewaySessionRenewalDue() {
        val session = GatewaySessionProcessCoordinator.snapshot().session ?: return
        val walkState = runtime.walkState()
        // "While the app is running": foreground, or a walk that keeps its lease in background.
        // A backgrounded app without a walk renews on its next foreground return.
        if (
            !runtime.startupInspectionComplete() ||
            (
                !runtime.foreground() &&
                    walkState != WalkSessionState.ACTIVE &&
                    walkState != WalkSessionState.PAUSED
                )
        ) return
        requestInRunGatewaySessionRenewal(session, GatewayInRunRenewalTrigger.PROACTIVE)
    }

    fun isCurrentGatewaySession(session: GatewayFieldSession): Boolean =
        isGatewaySessionReadyForCurrentActor(session)
}
