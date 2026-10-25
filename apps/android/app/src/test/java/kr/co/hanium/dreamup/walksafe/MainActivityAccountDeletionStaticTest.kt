package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityAccountDeletionStaticTest {
    private val source =
        ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)
    private val settingsPanel = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/ui/SettingsPrivacyPanel.kt",
    )
    private val destinationPanel = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt",
    )

    private val settingsPresenter = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/ui/SettingsPrivacyPresenter.kt",
    )
    private val screenPresenter = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeScreenPresenter.kt",
    )
    private val installationResetController = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/reset/AccountDeletionInstallationResetController.kt",
    )
    private val installationResetStorage = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/reset/AccountDeletionResetStorage.kt",
    )
    private val speechStorage = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/SpeechOutputStorageCoordinator.kt",
    )
    private val recoveryStore = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionRecoveryStore.kt",
    )
    private val workerStages = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionWorkerStages.kt",
    )
    private val operationAuthority = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionOperationAuthority.kt",
    )
    private val gatewayController = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/GatewaySessionController.kt",
    )
    private val gatewayControlsRenderer = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/ui/GatewayControlsRenderer.kt",
    )
    private val nativeWorkflow = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/ui/workflow/NativeWorkflowController.kt",
    )

    @Test
    fun acceptancePrecedesLocalPurgeAndEvidenceSubmission() {
        val confirm =
            movedBody("onAccountDeletionConfirmClicked")
        assertTrue(confirm.contains("AccountDeletionPhase.CONFIRM_REQUIRED"))
        assertTrue(
            appearsInOrder(
                confirm,
                "AccountDeletionPhase.CONFIRM_REQUIRED",
                "actor.recoveryGatewaySessionOrNull(",
                "queries.trustedOrigin()",
                "queries.sensitiveStorageBlocked()",
                "effects.applyRuntimeFence()",
                "beginPreparationWorkerAttempt(",
                "startConfirmationPreparation(",
            ),
        )

        val markerPreparation =
            movedBody("prepareAccountDeletionRecoveryMarker")
        val preparation =
            movedBody("startAccountDeletionPreparation")
        val pendingReady =
            movedBody("postAccountDeletionPendingJournalReady")
        val pendingJournalPreparation =
            movedBody("prepareAccountDeletionPendingJournal")
        val acceptedPurge =
            movedBody("scheduleAcceptedAccountDeletionLocalPurge")
        val submitEvidence =
            movedBody("submitPendingDeviceDeletionEvidenceAllowed")
        assertTrue(
            "The encrypted capability marker must precede the pending journal and POST.",
            appearsInOrder(
                markerPreparation + "\n" + pendingJournalPreparation + "\n" + pendingReady,
                "ByteArray(32)",
                "Base64.getUrlEncoder().withoutPadding()",
                "phase = AndroidAccountDeletionFallbackMarker.Phase.PREPARED",
                "fallbackMarker.create(record)",
                "fallbackMarker.read()",
                "AccountDeletionJournal.pending(",
                "PREF_ACCOUNT_DELETION_JOURNAL",
                ".commit()",
                "exactReadback",
                "client.requestDeletionCall(",
            ),
        )
        assertTrue(
            "The pending journal, actor binding, and global fence must commit as one tuple.",
            appearsInOrder(
                pendingJournalPreparation,
                "PREF_ACCOUNT_DELETION_JOURNAL",
                "PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER",
                "PREF_ACCOUNT_DELETION_ACTOR_HASH",
                "markerRecord.identity.actorHash",
                ".commit()",
                "exactReadback",
            ),
        )
        assertTrue(preparation.contains("store.preparePendingJournal("))
        assertTrue(preparation.contains("postPendingJournalReady("))
        assertTrue(
            "Local purge starts only from a canonically accepted marker, then durable " +
                "evidence is recorded before submission.",
            appearsInOrder(
                acceptedPurge,
                "localPurgeInFlightRequestId",
                "cleanupExecutor().execute",
                "purge.purgeLocalDataOnWorker()",
                "DeviceDeletionEvidence(",
                "deviceDeletionEvidenceSha256(evidenceWithoutHash)",
                "Phase.EVIDENCE_PENDING",
                "store.fallbackMarker.update(next)",
                "stateMachine().recordDeviceEvidence(",
                "stages.durable(workerAttempt)",
                "store.persistJournal(current)",
                "evidenceController.submitPendingAllowed(",
            ),
        )
        assertTrue(
            appearsInOrder(
                pendingReady,
                "callbacks.destroyed",
                "activityToken != callbacks.activityToken",
                "!operation.isCurrentPreparation(generation)",
                "stateMachine().begin(pendingJournal, workerAttempt)",
                "client.requestDeletionCall(",
            ),
        )
        assertTrue(submitEvidence.contains("submitDeviceDeletionEvidenceCall("))
        assertTrue(submitEvidence.contains("accessSecret = record.accessSecret"))
        assertFalse(preparation.contains("purgeAccountDeletionLocalDataOnCleanupThread()"))
        assertFalse(pendingReady.contains("purgeAccountDeletionLocalDataOnCleanupThread()"))
    }

    @Test
    fun runtimeFenceBlocksFieldLogAdmissionBeforeSafetyStopEvents() {
        val runtimeFence = movedBody("applyAccountDeletionRuntimeFence")
        val fencePorts = MainActivityRefactorSource.factory(source, "createPrivacyRuntimeFence")
        val fenceAssembly = MainActivityRefactorSource.composition(source, "accountDeletionRuntimeFenceController")
        assertTrue(
            appearsInOrder(
                runtimeFence,
                "blockFieldProcessing()",
                "blockConsent()",
                "stopActiveWalk()",
            ),
        )
        assertTrue(fencePorts.contains("blockFieldProcessing = { if (::fieldSessionLog.isInitialized) fieldSessionLog.blockNewProcessingForAccountDeletion() }"))
        assertTrue(fenceAssembly.contains("blockFieldProcessing = ports.blockFieldProcessing"))
        assertTrue(appearsInOrder(fenceAssembly, "integratedConsentSession.blockForAccountDeletion()",
            "reportPrivacyConsentSession.blockForAccountDeletion()"))
        assertTrue(fenceAssembly.contains("stopActiveWalk = ports.stopActiveWalk"))
        assertTrue(fencePorts.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(fencePorts.contains("persistInterruptionMarker = false"))
        val safetyStop = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun enterWalkSessionSafetyStopAndCancelOutputs",
        )
        assertTrue(
            safetyStop.contains(
                "performWalkSessionSafetyStopCleanup(reason, persistInterruptionMarker, null, null)",
            ),
        )
        val cleanup = movedBody("performWalkSessionSafetyStopCleanup")
        val markerGuard = ReportStaticSourceInspector.blockAfter(
            cleanup,
            "if (persistInterruptionMarker && walk.available())",
        )
        assertTrue(markerGuard.contains("admission.persistInterruptionMarker()"))
        assertEquals(1, Regex(Regex.escape("admission.persistInterruptionMarker()")).findAll(cleanup).count())
        assertTrue(source.contains("override fun persistInterruptionMarker() = persistWalkSessionInterruptionMarker()"))

        val durableCleanup = movedBody("purgeAccountDeletionLocalDataOnCleanupThread")
        MainActivityRefactorSource.composition(source, "accountDeletionLocalDataPurger")
        val purgePorts = MainActivityRefactorSource.factory(source, "createPrivacyLocalPurge")
        assertTrue(
            appearsInOrder(
                durableCleanup,
                "participants.blockPersistentFieldLogger()",
                "participants.purgePersistentFieldLogger()",
            ),
        )
        assertTrue(purgePorts.contains("override fun blockPersistentFieldLogger() { fieldSessionLog.blockForAccountDeletion() }"))
        assertTrue(purgePorts.contains("override fun purgePersistentFieldLogger() = fieldSessionLog.purgeAll()"))
        // DELETED evidence for device-unsent data must include the report queue and RAW
        // store purge results; a failure in either one keeps the evidence at FAILED.
        assertTrue(
            appearsInOrder(
                durableCleanup,
                "legacyQueue.purgeOnWorker()",
                "val reportQueuePurged = retry {",
                "participants.purgePersistentReportQueue()",
                "runCatching { participants.rawCollectionsInitialized() }.getOrDefault(false) &&",
                "retry {\n",
                "participants.purgeRawCollections()",
                "return routeSnapshotPurged && legacyQueuePurged && reportQueuePurged && rawCollectionsPurged &&",
                "preferencesPurged && fieldSessionsPurged",
            ),
        )
        assertTrue(purgePorts.contains("override fun purgePersistentReportQueue() = reportQueueDrainController.onAccountDeleted()"))
        assertTrue(purgePorts.contains("override fun rawCollectionsInitialized() = ::rawCollectionRuntimeCoordinator.isInitialized"))
        assertTrue(purgePorts.contains("override fun purgeRawCollections() = rawCollectionRuntimeCoordinator.onAccountDeleted()"))
        // A transient failure is retried a bounded number of times, never forever.
        val retry = movedBody("retryAccountDeletionPurgeOnCleanupThread")
        assertTrue(
            appearsInOrder(
                retry,
                "for (attempt in 0 until MAX_ATTEMPTS)",
                "sleep(",
                "Thread.currentThread().interrupt()",
                "return false\n                }",
                "if (runCatching(purge).getOrDefault(false)) return true",
                "return false\n    }",
            ),
        )
        val purgerSource = ReportStaticSourceInspector.read("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionLocalDataPurger.kt")
        assertTrue(purgerSource.contains("const val MAX_ATTEMPTS = 3"))
        assertTrue(purgerSource.contains("const val INITIAL_BACKOFF_MS = 250L"))
        assertTrue(purgerSource.contains("private val sleep: (Long) -> Unit = Thread::sleep"))
    }

    @Test
    fun identityFreeIntentFencePrecedesIdentityAndSurvivesRestartUntilPlainReset() {
        val creation = ReportStaticSourceInspector.functionBlock(
            source,
            "override fun onCreate",
        )
        val fenceWiring = creation
            .substringAfter("accountDeletionRecoveryStore.bindIntentFence(AccountDeletionIntentFence(")
            .substringBefore("fieldSessionLog = NoopFieldSessionLog()")
        assertTrue(fenceWiring.contains("PREF_ACCOUNT_DELETION_INTENT_FENCE"))
        assertTrue(fenceWiring.contains(".putBoolean(PREF_ACCOUNT_DELETION_INTENT_FENCE, true)"))
        listOf(
            "PREF_ACCOUNT_DELETION_JOURNAL",
            "PREF_ACCOUNT_DELETION_ACTOR_HASH",
            "actorId",
            "actorHash",
            "requestId",
            "gatewayOrigin",
            "installationId",
        ).forEach { identityToken ->
            assertFalse(
                "The durable confirmation fence must remain identity-free: $identityToken",
                fenceWiring.contains(identityToken),
            )
        }

        assertTrue(
            appearsInOrder(
                creation,
                "val noBackupRoot = noBackupFilesDir",
                "File(noBackupRoot, \"account_deletion_intent_authority\")",
                "accountDeletionRecoveryStore.bindFileAuthority(fileAccountDeletionIntentAuthority)",
                "accountDeletionRecoveryStore.observeAuthority(",
                "accountDeletionDualAuthority.startupState()",
                "initiallyBlockedForAccountDeletion =",
                "accountDeletionMarkerPresentAtStartup()",
                "accountDeletionAuthorityAtStartup.blocksPrivacy",
                "restorePrivacyControlStateFromPrefs()",
            ),
        )
        val authorityBinding = namedFunctionText(recoveryStore, "bindFileAuthority")
        assertTrue(appearsInOrder(authorityBinding, "preparedConfirmationRecovery =", "dualAuthority = AccountDeletionDualAuthority("))
        assertTrue(source.contains("get() = accountDeletionRecoveryStore.dualAuthority"))
        assertTrue(source.contains("accountDeletionRecoveryStore.markerPresentAtStartup()"))
        val startupMarker = namedFunctionText(recoveryStore, "markerPresentAtStartup")
        assertTrue(startupMarker.contains("authorityAtStartup.blocksPrivacy"))

        val restore = movedBody("restorePrivacyControlStateFromPrefs")
        assertTrue(restore.contains("deletion.reconcileAuthorityAfterJournalRestore()"))
        assertTrue(startupRestoreBinding().contains("override fun reconcileAuthorityAfterJournalRestore() = accountDeletionRecoveryStore.reconcileAuthorityAfterJournalRestore()"))
        val authorityRestore = movedBody("reconcileAccountDeletionAuthorityAfterJournalRestore")
        assertTrue(
            appearsInOrder(
                authorityRestore,
                "beginLegacyFailClosedUpgradeAttempt(",
                "runLegacyFailClosedUpgradeStageIfCurrent(",
                "dualAuthority.failClosed(reason).durableSuccess",
                "dualAuthority.confirm().decision",
                "accountDeletionStartupAuthorityDecision(",
                "enforceFailClosedAuthority(",
                "forceAuthorityFailClosed(reason)",
                "effects.blockPrivacyForAuthority()",
            ),
        )

        val plainReset = ReportStaticSourceInspector.functionBlock(
            installationResetStorage,
            "fun clearPlainResetArtifacts",
        )
        val resetCoordinator = installationResetStorage
            .substringAfter("fun createCoordinator")
            .substringBefore("fun authoritySnapshot")
        assertTrue(source.contains("private val accountDeletionResetStorage = privacyAuthority.createAccountDeletionResetStorage()"))
        val resetStorageAssembly = MainActivityRefactorSource.composition(source, "accountDeletionResetStorage")
        assertTrue(resetStorageAssembly.contains("AccountDeletionResetStorage = AccountDeletionResetStorage("))
        val resetFactory = MainActivityRefactorSource.composition(source, "accountDeletionInstallationResetController")
        assertTrue(resetFactory.contains("AccountDeletionInstallationResetController = AccountDeletionInstallationResetController("))
        assertTrue(resetFactory.contains("storage = accountDeletionResetStorage"))
        assertFalse(resetFactory.contains("coordinatorFactory ="))
        assertTrue(installationResetController.contains("private val coordinatorFactory: (File) -> AccountDeletionResetCoordinator = storage::createCoordinator"))
        assertTrue(namedFunctionText(installationResetController, "initialize")
            .contains("coordinator = coordinatorFactory(noBackupRoot)"))
        assertTrue(namedFunctionText(installationResetController, "clearPlainResetArtifacts")
            .contains("storage.clearPlainResetArtifacts()"))
        assertTrue(resetStorageAssembly.contains("resetFieldStorage = { speechOutputStorageCoordinator.resetFieldStorageOnWorker() }"))
        assertTrue(
            resetCoordinator.contains(
                "resetPlainPreferences = ::clearPlainResetArtifacts",
            ),
        )
        assertTrue(
            appearsInOrder(
                resetCoordinator,
                "resetFieldStorage = resetFieldStorage",
                "clearDeletionIntentFence = { accountDeletionDualAuthority.clear().cleared }",
            ),
        )
        assertTrue(source.contains("private fun resetSpeechOutputFieldStorageOnWorker(): Boolean =\n        speechOutputStorageCoordinator.resetFieldStorageOnWorker()"))
        assertTrue(source.contains("speechOutputStorageCoordinator.resetPreparationIsCurrent(preparation)"))
        val fieldReset = ReportStaticSourceInspector.functionBlock(speechStorage, "fun resetFieldStorageOnWorker")
        assertTrue(fieldReset.contains("speechOutputResetParticipant.get() ?: return false"))
        assertTrue(appearsInOrder(
            fieldReset,
            "!speechOutputAccountPurgeVerified.get()",
            "!resetPreparationIsCurrent(preparation)",
            "return false",
            "val fieldParticipant = preparation.fieldParticipant",
            "fieldParticipant.purgeAll() && fieldParticipant.resetForNewEnrollment()",
        ))
        assertTrue(fieldReset.contains(
            "preparation.collection.provider.purgeAll() && preparation.collection.provider.resetForNewEnrollment()",
        ))
        assertFalse(plainReset.contains("PREF_ACCOUNT_DELETION_INTENT_FENCE"))
        val clearFence = installationResetStorage
            .substringAfter("fun clearIntentFence()")
            .substringBefore("fun failClosedAuthority")
        assertTrue(namedFunctionText(installationResetController, "clearIntentFence")
            .contains("storage.clearIntentFence()"))
        assertTrue(
            clearFence.contains("accountDeletionDualAuthority.clear().cleared"),
        )
        val sensitiveSpec = source
            .substringAfter("val SENSITIVE_PREF_SPEC = SensitivePreferenceSpec(")
            .substringBefore("const val DEFAULT_PROGRESS_BEEP_VOLUME_PERCENT")
        assertFalse(sensitiveSpec.contains("PREF_ACCOUNT_DELETION_INTENT_FENCE"))
        assertEquals(
            1,
            Regex("\\.remove\\(PREF_ACCOUNT_DELETION_INTENT_FENCE\\)")
                .findAll(source)
                .count(),
        )
    }

    @Test
    fun deletionWorkersUseTheProcessCoordinatorAndActivityLease() {
        assertFalse(
            source.contains(
                "private val accountDeletionStateMachine = AccountDeletionStateMachine()",
            ),
        )
        assertTrue(
            appearsInOrder(
                source,
                "private val accountDeletionProcessCoordinator =",
                "AccountDeletionProcessCoordinator.shared",
                "get() = accountDeletionProcessCoordinator.stateMachine",
            ),
        )

        val creation = ReportStaticSourceInspector.functionBlock(source, "override fun onCreate")
        assertTrue(
            appearsInOrder(
                creation,
                "accountDeletionActivityLease = accountDeletionProcessCoordinator.attach()",
                "accountDeletionRecoveryStore.bindIntentFence(AccountDeletionIntentFence(",
                "restorePrivacyControlStateFromPrefs()",
            ),
        )

        val attemptBodies = listOf(movedBody("onAccountDeletionConfirmClicked"),
            movedBody("postAccountDeletionRecoveryMarkerPreparation"),
            movedBody("reconcileAccountDeletionAuthorityAfterJournalRestore"), namedFunctionText(workerStages, "begin"))
        val networkFactory = MainActivityRefactorSource.composition(source, "accountDeletionNetworkController")
        assertTrue(networkFactory.contains("AccountDeletionNetworkController("))
        assertTrue(networkFactory.contains("stages = accountDeletionWorkerStages"))
        assertTrue(readOwner("session/deletion/runtime/AccountDeletionNetworkController.kt")
            .contains("private val stages: AccountDeletionWorkerStages"))
        assertTrue(movedBody("refreshAccountDeletionStatus")
            .contains("workerAttempt ?: stages.begin(journal) ?: return"))
        val attemptCalls = attemptBodies.flatMap { body -> Regex(
            "stateMachine\\(\\)\\." +
                "(?:beginPreparationWorkerAttempt|beginWorkerAttempt)\\((?:[^()]|\\([^()]*\\))*\\)",
        ).findAll(body).map(MatchResult::value).toList() }
        assertTrue(attemptCalls.isNotEmpty())
        attemptCalls.forEach { call ->
            assertTrue(
                "Every deletion worker attempt must be bound to the active Activity lease: $call",
                call.contains("activityLease()"),
            )
        }

        val destroy = ReportStaticSourceInspector.functionBlock(source, "override fun onDestroy")
        assertTrue(
            appearsInOrder(
                destroy,
                "accountDeletionProcessCoordinator.detach(accountDeletionActivityLease)",
                "legacyPendingReportQueuePurgeController.destroy()",
                "reportCleanupExecutor.shutdownNow()",
                "accountDeletionOperationAuthority.destroy()",
            ),
        )
        val destroyOperation = namedFunctionText(operationAuthority, "destroy")
        assertTrue(appearsInOrder(destroyOperation, "generation += 1L", "call?.cancel()"))

        val request = movedBody("startAccountDeletionCallAllowed")
        assertTrue(
            appearsInOrder(
                request,
                "stateMachine().registerNetworkCall(",
                "networkExecutor().execute {",
                "stateMachine().beginNetworkCall(networkLease)",
                "val execution = executeAccountDeletionCall(call)",
                "is AccountDeletionCallExecution.Succeeded",
                "stateMachine().completeNetworkCall(",
            ),
        )
        val executorBeforeIo = request
            .substringAfter("networkExecutor().execute {")
            .substringBefore("val execution = executeAccountDeletionCall(call)")
        assertFalse(
            "Network I/O must not run inside a StateMachine durable-stage lambda.",
            executorBeforeIo.contains("stages.durable"),
        )

        val rejected = ReportStaticSourceInspector.blockAfter(
            request,
            "catch (_: RejectedExecutionException)",
        )
        val executionHelper = ReportStaticSourceInspector.read(
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/" +
                "AccountDeletionCallExecution.kt",
        )
        assertTrue(executionHelper.contains("catch (_: CancellationException)"))
        assertTrue(executionHelper.contains("catch (error: Exception)"))
        val checkedFailure = ReportStaticSourceInspector.blockAfter(
            executionHelper,
            "catch (error: Exception)",
        )
        assertFalse(checkedFailure.contains("releaseCancellationOwnership()"))
        assertTrue(
            appearsInOrder(
                request,
                "is AccountDeletionCallExecution.Failed",
                "scheduleCompletion(",
                "releaseNetworkCall(networkLease)",
                "operation.completeCall(generation, call)",
                "stages.allowed(workerAttempt)",
            ),
        )
        assertTrue(request.contains("releaseNetworkCall(networkLease)"))
        assertTrue(request.contains("operation.completeCall(generation, call)"))
        assertTrue(rejected.contains("releaseNetworkCall(networkLease)"))
    }

    @Test
    fun durableConfirmationRecoveryIsDeletionOnlyRetryableAndIdentityBound() {
        val authorityRestore = movedBody("reconcileAccountDeletionAuthorityAfterJournalRestore")
        assertTrue(
            appearsInOrder(
                authorityRestore,
                "accountDeletionStartupAuthorityDecision(",
                "startupDecision.durableConfirmationRecoveryRequired",
                "enterDurableConfirmationRecovery(",
                "startupDecision.failClosedReason",
            ),
        )

        val confirm = movedBody("onAccountDeletionConfirmClicked")
        val markerCallback = movedBody("postAccountDeletionRecoveryMarkerPreparation")
        assertTrue(
            appearsInOrder(
                markerCallback,
                "AccountDeletionRecoveryMarkerPreparation.Ready",
                ".retainDurableConfirmationRecovery(workerAttempt)",
                "effects.applyRuntimeFence()",
                "beginPreparationWorkerAttempt(",
                "startPreparedAuthorityConfirmation(",
            ),
        )
        assertFalse(confirm.contains("retainDurableConfirmationRecovery("))
        assertTrue(
            appearsInOrder(
                confirm,
                "actor.recoveryGatewaySessionOrNull(",
                "effects.applyRuntimeFence()",
                "startConfirmationPreparation(",
            ),
        )
        assertTrue(confirm.contains("recovery_required:verified_login_required"))
        assertFalse(confirm.contains("failAccountDeletionPreparation("))

        val preparedAuthority = movedBody("prepareAccountDeletionIntentAuthority")
        assertTrue(
            appearsInOrder(
                preparedAuthority,
                "recoverPreparedConfirmation()",
                "PreparedConfirmationRecoveryResult.RECOVERED_CONFIRMED",
                "intentFence.engage()",
                "PreparedConfirmationRecoveryResult.RECONFIRM_REQUIRED",
                "dualAuthority.confirm()",
                "PreparedConfirmationRecoveryResult.RETRY_REQUIRED",
                "PreparedConfirmationRecoveryResult.REJECTED",
            ),
        )

        val recoveryActor = movedBody("accountDeletionRecoveryActorIdOrNull")
        listOf(
            "durableConfirmationRecoveryRequired()",
            "enrollment.onboardingComplete()",
            "enrollment.onboardingActorId()",
            "enrollment.reporterActorId() == liveActorId",
            "enrollment.priorityActorId() == liveActorId",
            "enrollment.priorityAccountUnblocked()",
            "preparedRecord?.recoveryActorId",
            "preparedRecord.identity.actorHash",
        ).forEach { token -> assertTrue(recoveryActor.contains(token)) }

        val recoverySession = movedBody("accountDeletionRecoveryGatewaySessionOrNull")
        listOf(
            "!processSnapshot.storageBlocked",
            "processSnapshot.session === session",
            "processSnapshot.deletionRecoveryOnly",
            "GatewaySessionVerificationState.VERIFIED",
            "session.isUsableFor(actorId)",
            "session.gatewayBaseUrl == trustedOrigin",
        ).forEach { token -> assertTrue(recoverySession.contains(token)) }
        assertFalse(recoverySession.contains("permissionSessionPolicy.isAuthenticatedFor"))

        val gatewayLogin = ReportStaticSourceInspector.functionBlock(
            gatewayController,
            "fun onGatewaySessionButtonClicked",
        )
        assertTrue(source.contains("return gatewaySessionController.onGatewaySessionButtonClicked()"))
        assertTrue(source.contains("override fun loginActorId() = gatewayLoginActorIdOrNull()"))
        assertTrue(source.contains("config = GatewaySessionController.Configuration("))
        assertTrue(source.contains("BuildConfig.DEBUG, BuildConfig.WALKSAFE_LONG_LIVED_LOGIN_ENABLED, BuildConfig.WALKSAFE_GATEWAY_ORIGIN"))
        assertTrue(source.contains("override fun recoverySessionOrNull(session: GatewayFieldSession?) = accountDeletionRecoveryGatewaySessionOrNull(session)"))
        val gatewayBinding = MainActivityRefactorSource.factory(source, "createGatewaySessionController")
        assertTrue(gatewayBinding.contains("override fun isRecoveryLoginTarget(actorId: String, gatewayBaseUrl: String) ="))
        assertTrue(gatewayBinding.contains("this@MainActivity.accountDeletionRecoveryActorController.isRecoveryLoginTarget(actorId, gatewayBaseUrl)"))
        val actorBinding = MainActivityRefactorSource.composition(source, "accountDeletionRecoveryActorController")
        assertTrue(actorBinding.contains("AccountDeletionRecoveryActorController("))
        assertTrue(actorBinding.contains("stateMachine = { stateMachine() }"))
        assertTrue(MainActivityRefactorSource.factory(source, "createPrivacyAuthorityComposition").contains("stateMachine = { accountDeletionStateMachine }"))
        assertTrue(actorBinding.contains("store = accountDeletionRecoveryStore"))
        val actorOwner = readOwner("session/deletion/runtime/AccountDeletionRecoveryActorController.kt")
        val target = MainActivityRefactorSource.member(actorOwner, "isRecoveryLoginTarget")
        assertTrue(target.contains("stateMachine().processingBlocked()"))
        assertTrue(target.contains("recoveryLoginRequired()"))
        assertTrue(target.contains("recoveryActorIdOrNull() == actorId"))
        assertTrue(target.contains("configuredGatewayOriginOrNull() == gatewayBaseUrl"))
        assertTrue(gatewayLogin.contains("val actorId = profile.loginActorId()"))
        assertTrue(gatewayLogin.contains("deletionRecoveryTarget"))
        assertTrue(gatewayLogin.contains("!deletionRecoveryTarget"))
        assertTrue(
            gatewayLogin.contains(
                "deletion.recoverySessionOrNull(session) !== session",
            ),
        )
        val gatewayCommit = ReportStaticSourceInspector.functionBlock(
            gatewayController,
            "private fun commitLoggedInGatewaySession",
        )
        assertTrue(
            appearsInOrder(
                gatewayCommit,
                "deletion.isRecoveryLoginTarget(",
                "if (deletionRecoveryLogin)",
                "publishDeletionRecoveryVerified(",
                "config.longLivedLoginEnabled",
                "publishVerified(",
            ),
        )
        val generalSessionGetter = source
            .substringAfter("private val gatewayFieldSession: GatewayFieldSession?")
            .substringBefore("private val gatewaySessionGeneration")
        assertTrue(generalSessionGetter.contains("takeUnless { snapshot.deletionRecoveryOnly }"))

        val logout = movedBody("onAccountLogoutClicked")
        assertTrue(source.contains("override fun deletionRecoveryRequired() = accountDeletionStateMachine.durableConfirmationRecoveryRequired()"))
        assertTrue(source.contains("override fun clearReporterActor() { reporterUserId = null }"))
        assertTrue(
            appearsInOrder(
                logout,
                "admission.deletionRecoveryRequired()",
                "return",
                "identity.clearReporterActor()",
            ),
        )

        val cancel = movedBody("onAccountDeletionCancelClicked")
        assertTrue(cancel.contains("durableConfirmationRecoveryRequired()"))
        assertTrue(cancel.contains("이미 저장된 삭제 확인은 취소할 수 없습니다"))

        val ui = ReportStaticSourceInspector.functionBlock(
            settingsPresenter,
            "fun deletionPresentation",
        )
        val wrapper = ReportStaticSourceInspector.functionBlock(source, "private fun updateAccountDeletionUi")
        val captured = ReportStaticSourceInspector.functionBlock(source, "private fun captureAccountDeletionUiFacts")
        assertTrue(wrapper.contains("settingsPrivacyPresenter.renderAccountDeletion(captureAccountDeletionUiFacts())"))
        assertTrue(captured.contains("accountDeletionStateMachine.durableConfirmationRecoveryRequired()"))
        assertTrue(captured.contains("accountDeletionRecoveryGatewaySessionOrNull()"))
        assertTrue(ui.contains("durableRecoverySessionReady"))
        assertTrue(ui.contains("현장 게이트웨이 로그인이 필요합니다"))
        assertTrue(ui.contains("현재 로그인 계정 삭제 요청 다시 확인"))
        assertTrue(ui.contains("confirming && !durableConfirmationRecovery"))
    }

    @Test
    fun preparedMarkerAndAuthorityRecoveryAreDurableWorkerStages() {
        val click = movedBody("onAccountDeletionConfirmClicked")
        assertTrue(
            appearsInOrder(
                click,
                "queries.sensitiveStorageBlocked()",
                "effects.applyRuntimeFence()",
                "beginPreparationWorkerAttempt(",
                "startConfirmationPreparation(",
            ),
        )
        assertFalse(click.contains("store.fallbackMarker.create("))
        assertFalse(click.contains("store.dualAuthority.confirm()"))

        val markerWorker = movedBody("startAccountDeletionConfirmationPreparation")
        assertTrue(
            appearsInOrder(
                markerWorker,
                "cleanupExecutor().execute",
                "stages.durable(workerAttempt)",
                "store.prepareRecoveryMarker(",
                "postRecoveryMarkerPreparation(",
            ),
        )
        val marker = movedBody("prepareAccountDeletionRecoveryMarker")
        assertTrue(
            appearsInOrder(
                marker,
                "fallbackMarker.read()",
                "fallbackMarker.create(record)",
                "val readback = fallbackMarker.read()",
                "readback.record == expected",
                "AccountDeletionRecoveryMarkerPreparation.Ready(expected)",
            ),
        )

        val markerCallback = movedBody("postAccountDeletionRecoveryMarkerPreparation")
        assertTrue(
            appearsInOrder(
                markerCallback,
                "AccountDeletionRecoveryMarkerPreparation.Ready",
                "retainDurableConfirmationRecovery(workerAttempt)",
                "startPreparedAuthorityConfirmation(",
            ),
        )
        val authorityWorker = movedBody("startPreparedAccountDeletionAuthorityConfirmation")
        assertTrue(authorityWorker.contains("cleanupExecutor().execute"))
        assertTrue(authorityWorker.contains("store.prepareIntentAuthority()"))
    }

    @Test
    fun pendingJournalIsCreateOnceOrExactCanonicalReuse() {
        val pending = movedBody("prepareAccountDeletionPendingJournal")
        assertTrue(
            appearsInOrder(
                pending,
                "val rawJournal = sensitivePrefs.getString(",
                "val failClosedMarker = sensitivePrefs.getBoolean(",
                "val actorHash = sensitivePrefs.getString(",
                "if (rawJournal != null)",
                "AccountDeletionJournalCodec.decodeOrNull(rawJournal)",
                "current == expected",
                "rawJournal == AccountDeletionJournalCodec.encode(current)",
                "actorHash == markerRecord.identity.actorHash",
                "terminalReason != null",
                "val pending = AccountDeletionJournal.pending(",
                ".commit()",
                "val exactReadback =",
                "if (committed && exactReadback)",
            ),
        )
        val preparation = movedBody("startAccountDeletionPreparation")
        assertTrue(preparation.contains("AccountDeletionPendingJournalPreparation.Conflict"))
        assertTrue(preparation.contains("pending_journal_conflict"))
    }

    @Test
    fun preparedStartupRecoversWithoutJournalForAbsentOrInterruptedAuthority() {
        val fallback = movedBody("reconcileAccountDeletionFallbackMarkerAtStartup")
        assertTrue(
            appearsInOrder(
                fallback,
                "val rawJournal = sensitivePrefs.getString(",
                "durablePreJournalRecovery",
                "state.record == preparedRecoveryCandidateOrNull()",
                "fileState !is",
                "AccountDeletionIntentAuthorityState.FailClosed",
                "rawJournal == null",
                "!exactTuple && !durablePreJournalRecovery",
            ),
        )
        val authority = movedBody("reconcileAccountDeletionAuthorityAfterJournalRestore")
        assertTrue(
            appearsInOrder(
                authority,
                "val preparedMarkerRecovery =",
                "preparedRecoveryCandidateOrNull() != null",
                "authority.fileState !is AccountDeletionIntentAuthorityState.FailClosed",
                "startupDecision.durableConfirmationRecoveryRequired ||",
                "preparedMarkerRecovery",
                "enterDurableConfirmationRecovery(",
            ),
        )
        val candidate = movedBody("preparedAccountDeletionRecoveryCandidateOrNull")
        assertTrue(candidate.contains("Phase.PREPARED"))
        assertTrue(candidate.contains("configuredGatewayOriginOrNull()"))
        assertFalse(candidate.contains("recoveryActorId ?: return null"))
        val identityBound = movedBody("preparedAccountDeletionRecoveryRecordOrNull")
        assertTrue(
            appearsInOrder(
                identityBound,
                "preparedRecoveryCandidateOrNull()",
                "record.recoveryActorId ?: return null",
                "sha256Hex(actorId.toByteArray(Charsets.UTF_8))",
                "record.identity.actorHash",
            ),
        )
    }

    @Test
    fun recoveryOnlyLoginIsShortLivedHiddenAndReplaceableAfterExpiry() {
        val login = ReportStaticSourceInspector.functionBlock(
            gatewayController,
            "fun onGatewaySessionButtonClicked",
        )
        assertTrue(
            appearsInOrder(
                login,
                "val deletionRecoverySurface =",
                "if (!config.debug && !deletionRecoverySurface) return",
                "processSnapshot.deletionRecoveryOnly",
                "deletion.recoverySessionOrNull(",
                "GatewaySessionProcessCoordinator.clear(",
                "processSnapshot.generation",
                "val deletionRecoveryTarget =",
                "!deletionRecoveryTarget",
                "enableLongLivedSession =",
            ),
        )
        val commit = ReportStaticSourceInspector.functionBlock(
            gatewayController,
            "private fun commitLoggedInGatewaySession",
        )
        assertTrue(commit.contains("publishDeletionRecoveryVerified("))
        val authUi = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun updateBackendAuthButtonText",
        )
        assertTrue(authUi.contains("deletionRecoverySessionReady"))
        val renderGateway = ReportStaticSourceInspector.functionBlock(settingsPresenter, "fun renderGatewayControls")
        val rendering = ReportStaticSourceInspector.functionBlock(gatewayControlsRenderer, "fun render")
        assertTrue(authUi.contains("settingsPrivacyPresenter.renderGatewayControls("))
        assertTrue(renderGateway.contains("GatewayControlsRenderer.render("))
        assertTrue(rendering.contains("삭제 복구 다시 로그인"))
        assertTrue(authUi.contains("val passwordRecoverySurface = accountDeletionPasswordRecoveryBindingOrNull() != null"))
        assertTrue(authUi.contains("val surfaceVisible = (BuildConfig.DEBUG || deletionRecoverySurface) && !passwordRecoverySurface"))
        assertTrue(appearsInOrder(authUi, "settingsPrivacyPresenter.renderGatewayControls(",
            "surfaceVisible, requestInFlight, deletionRecoverySessionReady,"))
        assertTrue(renderGateway.contains("token.visibility ="))
        assertTrue(renderGateway.contains("action.visibility ="))
        assertTrue(settingsPresenter.contains("gatewayInputTarget(): android.widget.EditText = publishedGatewayInput.backendFieldTokenInput"))
        assertTrue(settingsPresenter.contains("gatewayActionTarget(): android.widget.Button = publishedGatewayAction.backendAuthApplyButton"))
        assertTrue(rendering.contains("!requestInFlight &&"))
        assertTrue(rendering.contains("!recoverySessionReady &&"))
        assertTrue(rendering.contains("!recoverySessionReserved"))
        assertFalse(source.contains("gateway=login_pending actor="))
        assertFalse(source.contains("gateway=session_ready actor="))
    }

    @Test
    fun privacyControlsRemainAccessibleOutsideWalkingRuntime() {
        val privacy =
            ReportStaticSourceInspector.blockAfter(
                settingsPanel,
                "val privacyControls: LinearLayout = LinearLayout(context).apply",
            )
        val privacySettings =
            ReportStaticSourceInspector.blockAfter(
                settingsPanel,
                "val privacySettingsControls: LinearLayout = LinearLayout(context).apply",
            )
        val deletion =
            ReportStaticSourceInspector.blockAfter(
                settingsPanel,
                "val accountDeletionControls: LinearLayout = LinearLayout(context).apply",
            )
        val gateway =
            ReportStaticSourceInspector.blockAfter(
                settingsPanel,
                "val gatewaySessionControls: LinearLayout = LinearLayout(context).apply",
            )
        val runtime =
            ReportStaticSourceInspector.blockAfter(
                destinationPanel,
                "val runtimeControls = LinearLayout(context).apply",
            )
        assertTrue(privacySettings.contains("addView(privacyConsentStatusText)"))
        assertTrue(deletion.contains("addView(accountDeletionRequestButton)"))
        assertTrue(gateway.contains("addView(backendFieldTokenInput)"))
        assertTrue(gateway.contains("addView(backendAuthApplyButton)"))
        assertTrue(privacy.contains("addView(privacySettingsControls)"))
        assertTrue(privacy.contains("addView(accountDeletionControls)"))
        assertTrue(privacy.contains("addView(gatewaySessionControls)"))
        assertTrue(
            appearsInOrder(
                privacy,
                "addView(nativeSettingsTitle)",
                "addView(privacySettingsControls)",
                "addView(accountDeletionControls)",
                "addView(gatewaySessionControls)",
                "addView(nativeSettingsLogoutButton)",
                "addView(nativeSettingsHomeButton)",
            ),
        )
        listOf(
            "privacyControls", "privacySettingsControls", "accountDeletionControls",
            "gatewaySessionControls", "accountDeletionStatusText", "accountDeletionRequestButton",
            "accountDeletionConfirmButton", "accountDeletionCancelButton", "accountDeletionRefreshButton",
            "backendFieldTokenInput", "backendAuthApplyButton",
        ).forEach { field ->
            assertTrue("The same Settings panel owns $field", settingsPanel.contains("val $field"))
            assertTrue(source.contains("settingsPrivacyPresenter = SettingsPrivacyPresenter(settingsPanel, onboardingPanel)"))
        }
        assertTrue(source.contains("onDeletionRequest = ::onAccountDeletionPrimaryClicked"))
        assertTrue(source.contains("onDeletionConfirm = ::onAccountDeletionConfirmClicked"))
        assertTrue(source.contains("onDeletionCancel = ::onAccountDeletionCancelClicked"))
        assertTrue(source.contains("onDeletionRefresh = ::refreshAccountDeletionStatus"))
        val debugUrl = ReportStaticSourceInspector.blockAfter(
            settingsPanel,
            "val backendUrlInput: EditText? = if (BuildConfig.DEBUG)",
        )
        assertFalse(debugUrl.contains("setText("))
        assertTrue(
            appearsInOrder(
                source,
                "backendUrlInput = requireNotNull(settingsPanel.backendUrlInput)",
                "backendUrlInput.setText(gatewayFieldSession?.gatewayBaseUrl ?: configuredGatewayOriginOrNull().orEmpty())",
                "settingsPrivacyPresenter.publishGatewayInputTarget()",
                "nativeUiPublication.publish(NativeUiPublication.Target.BACKEND_FIELD_TOKEN_INPUT)",
                "settingsPrivacyPresenter.publishGatewayActionTarget()",
                "nativeUiPublication.publish(NativeUiPublication.Target.BACKEND_AUTH_APPLY_BUTTON)",
                "updateBackendAuthButtonText()",
            ),
        )
        assertFalse(runtime.contains("addView(accountDeletionRequestButton)"))
        assertTrue(
            appearsInOrder(
                source,
                "addView(nativeScreenPresenter.searchBinding().runtimeControls)",
                "addView(settingsPanel.privacyControls)",
            ),
        )

        val visibility = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun updatePrivacySectionVisibility",
        )
        assertTrue(visibility.contains("renderMainUi()"))
        val render = ReportStaticSourceInspector.functionBlock(
            nativeWorkflow,
            "fun renderMainUi",
        )
        assertTrue(source.contains("private fun renderMainUi() = nativeWorkflowController.renderMainUi()"))
        val workflowBinding = ReportStaticSourceInspector.blockAfter(source,
            "private val nativeWorkflowController: NativeWorkflowController by lazy")
        assertTrue(workflowBinding.contains("accountDeletionRecoveryLoginRequired() || GatewaySessionProcessCoordinator.snapshot().deletionRecoveryOnly"))
        assertTrue(workflowBinding.contains("override fun deletionProcessingBlocked(): Boolean = this@MainActivity.accountDeletionStateMachine.processingBlocked()"))
        assertTrue(render.contains("val deletionRecovery = queries.deletionRecoveryRequired()"))
        assertTrue(render.contains("forceSettings = deletionRecovery || queries.deletionProcessingBlocked()"))
        assertTrue(render.contains("settingsVisible, featureVisible, forceSettings, deletionRecovery"))
        assertTrue(render.contains("pages.renderFrame(NativeScreenPresenter.FrameFacts("))
        assertTrue(workflowBinding.contains("this@MainActivity.nativePagePresentationController.renderFrame(frame)"))
        val frameOwner = ReportStaticSourceInspector.read(
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativePagePresentationController.kt",
        )
        assertTrue(namedFunctionText(frameOwner, "renderFrame").contains("screen().finishFrame(frame)"))
        val frameBinding = ReportStaticSourceInspector.blockAfter(source,
            "private val nativePagePresentationController by lazy")
        assertTrue(frameBinding.contains("screen = { nativeScreenPresenter }"))
        val bindingsStart = "nativeScreenPresenter.bindPanels(NativeScreenPresenter.Panels("
        val bindingsEnd = "nativeScreenPresenter.bindRuntimeViews("
        assertTrue(source.contains(bindingsStart))
        assertTrue(source.contains(bindingsEnd))
        val bindings = source.substringAfter(bindingsStart).substringBefore(bindingsEnd)
        assertTrue(bindings.contains("settings = settingsPanel"))
        val presentation = ReportStaticSourceInspector.functionBlock(screenPresenter, "fun finishFrame")
        assertTrue(presentation.contains("show(settings.gatewaySessionControls, facts.deletionRecovery)"))
        assertTrue(presentation.contains("settings.privacyConsentStatusText.visibility = settings.privacySettingsControls.visibility"))
        val settingsGate = render.substringAfter("val settingsVisible =")
            .substringBefore("val homeVisible =")
        assertTrue(settingsGate.contains("forceSettings ||"))
        assertTrue(settingsGate.contains("homeAvailable && nativeUiPage == NativeUiPage.SETTINGS"))
        assertFalse(settingsGate.contains("isWalkSessionRuntimeActive()"))
        assertTrue(
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun updateAccountDeletionUi",
            ).contains("updatePrivacySectionVisibility()"),
        )
        assertTrue(
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun updateBackendAuthButtonText",
            ).contains("updatePrivacySectionVisibility()"),
        )
    }

    @Test
    fun unknownInitialPostUsesOnlyExactEncryptedCapabilityReplay() {
        val refresh =
            movedBody("refreshAccountDeletionStatusAllowed")
        assertTrue(refresh.contains("store.fallbackMarker.read()"))
        assertTrue(
            appearsInOrder(
                refresh,
                "journal.gatewayOrigin != trustedGatewayOrigin",
                "marker.record.identity.requestId != journal.requestId",
                "journal.serverRevision == 0L",
                "replayDeletionRequestCall(",
                "accessSecret = marker.record.accessSecret",
            ),
        )
        assertFalse(refresh.contains("gatewaySessionOrNull("))
        assertFalse(source.contains("retryAccountDeletionPostAfterNotFound"))
    }

    @Test
    fun automaticResumeRejectsRestoredOriginBeforeStatusGet() {
        val refresh =
            movedBody("refreshAccountDeletionStatusAllowed")

        assertTrue(refresh.contains("queries.trustedOrigin()"))
        assertTrue(refresh.contains("journal.gatewayOrigin != trustedGatewayOrigin"))
        assertTrue(
            appearsInOrder(
                refresh,
                "journal.gatewayOrigin != trustedGatewayOrigin",
                "fetchDeletionStatusCall(",
            ),
        )
        assertTrue(refresh.contains("trustedGatewayOrigin = trustedGatewayOrigin"))
    }

    @Test
    fun pendingEvidenceMarkerRehydratesJournalBeforeNetworkReplay() {
        val submit =
            movedBody("submitPendingDeviceDeletionEvidenceAllowed")
        assertTrue(
            appearsInOrder(
                submit,
                "journal.deviceEvidenceId == record.evidenceId",
                "journal.deviceEvidenceId == null",
                "stateMachine().recordDeviceEvidence(",
                "stateMachine().snapshotOrNull()",
                "stages.durable(workerAttempt)",
                "store.persistJournal(current)",
                "DeviceDeletionEvidence(",
                "submitDeviceDeletionEvidenceCall(",
            ),
        )
        assertTrue(submit.contains("accessSecret = record.accessSecret"))
    }

    @Test
    fun fallbackStartupFencesPresentAndCorruptMarkersBeforeConditionalClear() {
        val read =
            movedBody("readAccountDeletionFallbackMarkerAtStartup")
        val reconcile =
            movedBody("reconcileAccountDeletionFallbackMarkerAtStartup")
        assertTrue(
            appearsInOrder(
                read,
                "fallbackMarker.read()",
                "startupFallbackState = state",
                "state !is AndroidAccountDeletionFallbackMarker.State.Absent",
            ),
        )
        assertTrue(
            appearsInOrder(
                reconcile,
                "startupFallbackState",
                "effects.applyRuntimeFence()",
                "when (state)",
            ),
        )

        val reconcilePresent =
            ReportStaticSourceInspector.blockAfter(
                reconcile,
                "is AndroidAccountDeletionFallbackMarker.State.Present",
            )
        assertTrue(
            appearsInOrder(
                reconcilePresent,
                "PREF_ACCOUNT_DELETION_JOURNAL",
                "request_id",
                "PREF_ACCOUNT_DELETION_ACTOR_HASH",
                "journalRequestId == state.identity.requestId",
                "persistedActorHash == state.identity.actorHash",
                "durablePreJournalRecovery",
                "!exactTuple && !durablePreJournalRecovery",
            ),
        )

        val recovery =
            movedBody("startAccountDeletionFallbackRecoveryAfterRestore")
        assertTrue(
            appearsInOrder(
                recovery,
                "stateMachine().snapshotOrNull()",
                "restoredJournal?.phase == AccountDeletionPhase.FAIL_CLOSED",
                "effects.applyRuntimeFence()",
                "return",
                "is AndroidAccountDeletionFallbackMarker.State.Present",
                "durableConfirmationRecoveryRequired()",
                "AndroidAccountDeletionFallbackMarker.Phase.PREPARED",
                "!store.remoteResumeBlocked",
                "effects.updateUi()\n                    return",
                "PREF_ACCOUNT_DELETION_ACTOR_HASH",
                "restoredJournal?.requestId == state.identity.requestId",
                "persistedActorHash == state.identity.actorHash &&\n" +
                    "                        !store.remoteResumeBlocked",
                "continueFromMarker(state.record)",
            ),
        )

        assertTrue(
            appearsInOrder(
                recovery,
                "is AndroidAccountDeletionFallbackMarker.State.Corrupt",
                "is AndroidAccountDeletionFallbackMarker.State.Unavailable",
                "store.setRemoteResumeBlocked(true)",
                "effects.applyRuntimeFence()",
                "accountDeletion=recovery_blocked:fallback_marker_storage",
            ),
        )
        assertFalse(recovery.contains("purge.purgeLocalDataOnWorker"))
        assertFalse(recovery.contains("network.refreshStatus"))
        assertFalse(recovery.contains("client"))
        assertTrue(movedBody("continueAccountDeletionFromMarker").contains("dispatchAndroidAccountDeletionResume("))
    }

    @Test
    fun confirmedResetUsesDurableCrossStoreReplayAndChecksFieldReset() {
        val reset = ReportStaticSourceInspector.functionBlock(
            installationResetController,
            "fun resetAfterConfirmedDeletion",
        )
        val startup = ReportStaticSourceInspector.functionBlock(
            installationResetController,
            "fun reconcileResetAtStartup",
        )

        assertPreparationAssembly()
        assertTrue(namedFunctionText(source, "onAccountDeletionPrimaryClicked")
            .contains("accountDeletionPreparationController.onPrimaryClicked()"))
        val primary = namedFunctionText(readOwner("session/deletion/runtime/AccountDeletionPreparationController.kt"), "onPrimaryClicked")
        assertTrue(appearsInOrder(primary, "AccountDeletionPhase.COMPLETED",
            "resetController().resetAfterConfirmedDeletion()", "return"))
        assertStartupResetInspectionConnection()
        assertTrue(MainActivityRefactorSource.composition(source, "accountDeletionInstallationResetController")
            .contains("override fun resetReportPrivacyForNewEnrollment() { reportPrivacyConsentSession.resetForNewEnrollment() }"))
        assertTrue(reset.contains("isConfirmedJournal(journal)"))
        assertTrue(installationResetController.contains("isConfirmedTerminalAccountDeletion(journal)"))
        assertTrue(reset.contains("coordinator.beginAndReconcile()"))
        assertTrue(
            appearsInOrder(
                reset,
                "reserveWorkerResetIfCurrent(attempt)",
                "coordinator.beginAndReconcile()",
                "commitWorkerReset(reservation)",
                "persistTerminalStateOrHandle(",
                "actions.resetReportPrivacyForNewEnrollment()",
                "activityLeaseIsCurrent(",
            ),
        )
        assertTrue(reset.contains("rollbackWorkerReset(reservation)"))
        assertTrue(reset.contains("account_deletion_reset_commit_conflict"))
        assertFalse(reset.contains("fieldSessionLog.resetForNewEnrollment()"))
        assertTrue(installationResetStorage.contains("FileAccountDeletionResetJournal("))
        assertTrue(installationResetStorage.contains("storeLegacyIntent ="))
        assertTrue(installationResetStorage.contains("resetGateway ="))
        assertTrue(installationResetStorage.contains("resetSensitivePreferences ="))
        assertTrue(installationResetStorage.contains("resetPlainPreferences ="))
        assertTrue(installationResetStorage.contains("resetFieldStorage ="))
        assertTrue(appearsInOrder(reset, "actions.captureParticipants()",
            "prepared.awaitOriginalAcknowledgements()", "prepared.isCurrent()",
            "stateMachine().snapshotOrNull() === reservation.journal", "AccountDeletionPhase.RESET_PENDING",
            "coordinator.beginAndReconcile()"))
        assertTrue(MainActivityRefactorSource.composition(source, "accountDeletionInstallationResetController")
            .contains("override fun captureParticipants(): PreparedPrivacyStartupReset = captureInstallationResetParticipants()"))
        assertTrue(source.contains("private fun resetSpeechOutputFieldStorageOnWorker(): Boolean =\n        speechOutputStorageCoordinator.resetFieldStorageOnWorker()"))
        assertTrue(source.contains("speechOutputStorageCoordinator.resetPreparationIsCurrent(preparation)"))
        val fieldReset = ReportStaticSourceInspector.functionBlock(speechStorage, "fun resetFieldStorageOnWorker")
        assertTrue(fieldReset.contains("val preparation = speechOutputResetParticipant.get() ?: return false"))
        assertTrue(fieldReset.contains("!speechOutputAccountPurgeVerified.get()"))
        assertTrue(fieldReset.contains("!resetPreparationIsCurrent(preparation)"))
        assertTrue(appearsInOrder(fieldReset, "val fieldParticipant = preparation.fieldParticipant",
            "fieldParticipant is PersistentFieldSessionLog", "fieldParticipant.purgeAll() && fieldParticipant.resetForNewEnrollment()"))
        assertTrue(fieldReset.contains("preparation.collection.provider.purgeAll() && preparation.collection.provider.resetForNewEnrollment()"))
        assertTrue(startup.contains("coordinator.reconcilePending()"))
        assertTrue(startup.contains("LegacyResetIntentState.EXACT"))
        assertTrue(startup.contains("coordinator.beginAndReconcile()"))
        assertTrue(installationResetController.contains("AccountDeletionResetResult.COMPLETED_ELSEWHERE ->"))
        assertTrue(source.contains("accountDeletionResetJournalStateAtStartup"))
        assertTrue(source.contains("get() = accountDeletionInstallationResetController.journalStateAtStartup"))
        assertTrue(installationResetController.contains("startupJournalState = coordinator.journalState()"))
        val sensitiveInitialization = source
            .substringAfter("sensitivePrefs = AndroidSensitivePreferenceStore(")
            .substringBefore("gatewaySessionStore = AndroidGatewaySessionStore")
        assertTrue(
            sensitiveInitialization.contains(
                "accountDeletionResetJournalStateAtStartup !is",
            ),
        )
        assertTrue(
            sensitiveInitialization.contains("AccountDeletionResetJournalState.Absent"),
        )
        assertTrue(source.contains("::gatewaySessionStore.isInitialized"))
        assertTrue(source.contains("::sensitivePrefs.isInitialized"))
        assertTrue(source.contains("::fieldSessionLog.isInitialized"))
        assertFalse(
            startup.contains(
                "accountDeletionResetJournalStateAtStartup !is\n" +
                    "                AccountDeletionResetJournalState.Absent",
            ),
        )
    }

    @Test
    fun terminalJournalWithAlreadyAbsentMarkerResumesCleanupWithoutFailClosed() {
        val resume = movedBody("resumeAccountDeletionFromMarker")
        val absent = ReportStaticSourceInspector.blockAfter(
            resume,
            "is AndroidAccountDeletionFallbackMarker.State.Absent",
        )

        assertTrue(
            appearsInOrder(
                absent,
                "resetController().isConfirmedJournal(restoredJournal)",
                "scheduleActorBindingCleanup(restoredJournal)",
                "else {",
                "fallback_marker_missing",
                "workerAttempt",
            ),
        )
    }

    @Test
    fun resetWaitsForDurableTerminalMarkerAndActorBindingCleanup() {
        val cleanup = movedBody("scheduleAccountDeletionActorBindingCleanup")
        val reset = ReportStaticSourceInspector.functionBlock(
            installationResetController,
            "fun resetAfterConfirmedDeletion",
        )
        val ui = ReportStaticSourceInspector.functionBlock(
            settingsPresenter,
            "fun deletionPresentation",
        )

        assertTrue(
            appearsInOrder(
                cleanup,
                "resetController().terminalCleanup.begin(journal)",
                "AccountDeletionTerminalMarkerCleanupResult.ALREADY_ABSENT",
                "store.fallbackMarker.clear(identity)",
                "PREF_ACCOUNT_DELETION_ACTOR_HASH",
                ".commit()",
                "resetController().terminalCleanup.complete(",
            ),
        )
        assertTrue(
            appearsInOrder(
                reset,
                "!terminalCleanupComplete",
                "isConfirmedJournal(journal)",
                "coordinator.beginAndReconcile()",
            ),
        )
        val captured = ReportStaticSourceInspector.functionBlock(source, "private fun captureAccountDeletionUiFacts")
        assertTrue(captured.contains("terminalCleanupComplete = accountDeletionTerminalCleanupComplete"))
        assertTrue(ui.contains("facts.terminalCleanupComplete"))
        assertTrue(ui.contains("로컬 삭제 마무리 확인 중"))
        assertTrue(installationResetController.contains("stateMachine().snapshotOrNull()?.let(terminalCleanup::isResetEnabled) == true"))
        assertTrue(source.contains("get() = accountDeletionInstallationResetController.terminalCleanup"))
        assertTrue(source.contains("get() = accountDeletionInstallationResetController.terminalCleanupComplete"))
    }

    @Test
    fun everyDeletionNetworkEntryAndSessionRestoreUsesTheSharedFailClosedGuard() {
        val refresh = movedBody("refreshAccountDeletionStatus")
        val start = movedBody("startAccountDeletionCall")
        val submit = movedBody("submitPendingDeviceDeletionEvidence")
        val sessionRestore = movedBody("onGatewayProcessSessionChanged")

        assertTrue(
            appearsInOrder(
                refresh,
                "stateMachine().snapshotOrNull()",
                "workerAttempt ?: stages.begin(journal)",
                "stages.allowed(currentAttempt)",
                "dispatchAccountDeletionNetworkEntry(",
                "keepPrivacyFenceClosed = effects::keepPrivacyFenceClosed",
                "refreshStatusAllowed(journal, currentAttempt)",
            ),
        )
        assertFalse(refresh.contains("store.fallbackMarker.read()"))
        assertFalse(refresh.contains("client."))
        assertTrue(
            appearsInOrder(
                start,
                "stateMachine().snapshotOrNull()",
                "dispatchAccountDeletionNetworkEntry(",
                "keepPrivacyFenceClosed = effects::keepPrivacyFenceClosed",
                "startCallAllowed(",
                "callFactory()",
            ),
        )
        assertFalse(start.contains("networkExecutor().execute"))
        assertFalse(start.contains("client."))
        assertTrue(
            appearsInOrder(
                submit,
                "dispatchAccountDeletionNetworkEntry(",
                "stateMachine().snapshotOrNull()",
                "keepPrivacyFenceClosed = effects::keepPrivacyFenceClosed",
                "submitPendingAllowed(record, journal)",
            ),
        )
        assertFalse(submit.contains("recordDeviceEvidence("))
        assertFalse(submit.contains("client."))
        assertTrue(sessionRestore.contains("privacy.resumeDeletionIfNeeded(snapshot.session != null)"))
        val deletionResumePort = ReportStaticSourceInspector.functionBlock(source, "override fun resumeDeletionIfNeeded")
        assertTrue(
            appearsInOrder(
                deletionResumePort,
                "accountDeletionStateMachine.snapshotOrNull()",
                "dispatchAccountDeletionNetworkEntry(",
                "keepPrivacyFenceClosed = ::keepAccountDeletionPrivacyFenceClosed",
                "dispatch = ::refreshAccountDeletionStatus",
            ),
        )
    }

    @Test
    fun everyQueuedDeletionWorkerUsesGenerationGuardAtEntryWriteAndCallback() {
        fun currentWorkerBody(name: String): String {
            if (name !in setOf("scheduleAccountDeletionProgressAfterStatus", "postAccountDeletionProgressFailure")) return movedBody(name)
            val binding = MainActivityRefactorSource.composition(source, "accountDeletionNetworkController")
            assertTrue(binding.contains("progress = object : AccountDeletionNetworkProgress"))
            assertTrue(binding.contains("override fun scheduleAfterStatus(journal: AccountDeletionJournal) { accountDeletionProgressController.value.scheduleAfterStatus(journal) }"))
            MainActivityRefactorSource.composition(source, "accountDeletionProgressController")
            assertTrue(binding.contains("override fun postFailure(errorCode: String, workerAttempt: AccountDeletionWorkerAttempt)"))
            assertTrue(binding.contains("accountDeletionProgressController.value.postFailure(errorCode, workerAttempt)"))
            return MainActivityRefactorSource.member(readOwner("session/deletion/runtime/AccountDeletionProgressController.kt"),
                if (name == "postAccountDeletionProgressFailure") "postFailure" else "scheduleAfterStatus")
        }
        val workerMethods = listOf("startAccountDeletionPreparation", "startAccountDeletionCallAllowed",
            "scheduleAccountDeletionEvidenceRebase", "scheduleAccountDeletionActorBindingCleanup",
            "scheduleAccountDeletionProgressAfterStatus", "scheduleAcceptedAccountDeletionLocalPurge")
        val deletionWorkers = workerMethods.map(::currentWorkerBody)
        val deletionExecutorEntries =
            deletionWorkers.sumOf { Regex("cleanupExecutor\\(\\)\\.execute \\{").findAll(it).count() }
        assertTrue(deletionExecutorEntries >= 5)
        assertFalse(deletionWorkers.any { it.contains("reportCleanupExecutor.execute") })

        val networkExecutorEntries =
            deletionWorkers.sumOf { Regex("networkExecutor\\(\\)\\.execute \\{").findAll(it).count() }
        val begunNetworkEntries = Regex(
            "networkExecutor\\(\\)\\.execute \\{\\s*" +
                "if \\(!stateMachine\\(\\)\\.beginNetworkCall\\(networkLease\\)\\)",
        ).let { pattern -> deletionWorkers.sumOf { pattern.findAll(it).count() } }
        assertEquals(1, networkExecutorEntries)
        assertEquals(networkExecutorEntries, begunNetworkEntries)

        workerMethods.forEach { signature ->
            val worker = currentWorkerBody(signature)
            assertTrue(
                "$signature must linearize durable work with the shared state lock",
                Regex("stages\\.durable\\(\\s*workerAttempt")
                    .containsMatchIn(worker),
            )
        }

        val preparation = movedBody("startAccountDeletionPreparation")
        val confirmationPreparation = movedBody("startAccountDeletionConfirmationPreparation")
        val markerPreparation = movedBody("prepareAccountDeletionRecoveryMarker")
        assertTrue(
            appearsInOrder(
                confirmationPreparation,
                "cleanupExecutor().execute",
                "stages.durable(workerAttempt)",
                "store.prepareRecoveryMarker(",
                "postRecoveryMarkerPreparation(",
            ),
        )
        assertTrue(markerPreparation.contains("installationId()"))
        assertTrue(preparation.contains("store.preparePendingJournal("))
        val progress = currentWorkerBody("scheduleAccountDeletionProgressAfterStatus")
        val rebase = movedBody("scheduleAccountDeletionEvidenceRebase")
        val actorCleanup = movedBody("scheduleAccountDeletionActorBindingCleanup")
        listOf(progress, rebase, actorCleanup).forEach { worker ->
            assertTrue(
                Regex(
                    """stages\.durable\(workerAttempt\) \{\s*""" +
                        "(?:markerState|state) = " +
                        """store\.fallbackMarker\.read\(\)""",
                ).containsMatchIn(worker),
            )
        }
        listOf(progress, rebase).forEach { worker ->
            assertTrue(
                worker.contains(
                    "val workerAttempt = stages.begin(journal) ?: return",
                ),
            )
            assertTrue(
                Regex("stages\\.allowed\\(workerAttempt\\)")
                    .findAll(worker)
                    .count() >= 2,
            )
        }
        assertTrue(
            appearsInOrder(
                progress,
                "activityToken != callbacks.activityToken",
                "!stages.allowed(workerAttempt)",
            ),
        )
        assertTrue(
            appearsInOrder(
                progress,
                "val next = current.copy(",
                "stages.durable(workerAttempt)",
                "store.fallbackMarker.update(next)",
            ),
        )
        assertTrue(progress.contains("continueFromMarker(next, workerAttempt)"))
        val continuation = movedBody("continueAccountDeletionFromMarker")
        assertTrue(
            Regex("postFailure\\([\\s\\S]*currentAttempt")
                .containsMatchIn(continuation),
        )
        assertTrue(
            Regex("network.refreshStatus\\(currentAttempt\\)")
                .findAll(continuation)
                .count() == 2,
        )
        val guardedRefresh = movedBody("refreshAccountDeletionStatusAllowed")
        assertTrue(guardedRefresh.contains("stages.durable(workerAttempt)"))
        assertTrue(guardedRefresh.contains("stages.terminal("))
        val preparationFailure = movedBody("postAccountDeletionPreparationFailure")
        assertTrue(
            preparationFailure.contains(
                "retainPreparationRecovery(",
            ),
        )
        assertTrue(
            appearsInOrder(
                rebase,
                "val next = record.copy(",
                "stages.durable(workerAttempt)",
                "store.fallbackMarker.update(next)",
            ),
        )

        val sharedGuard = movedBody("accountDeletionWorkerStageAllowed")
        assertTrue(sharedGuard.contains("dispatchAccountDeletionWorkerStage("))
        val durableGuard = movedBody("runAccountDeletionWorkerDurableStage")
        assertTrue(durableGuard.contains("runWorkerMonotonicDurableIoIfCurrent("))
        val terminalGuard = movedBody("runAccountDeletionWorkerTerminalStage")
        assertTrue(
            terminalGuard.contains("runWorkerMonotonicTerminalDurableIoIfCurrent("),
        )
        assertTrue(terminalGuard.contains("resetController().persistTerminalState("))
        assertTrue(terminalGuard.contains("resetController().handleTerminalPersistenceFailure("))
        val workerFailure = currentWorkerBody("postAccountDeletionProgressFailure")
        assertTrue(
            appearsInOrder(
                workerFailure,
                "stages.allowed(workerAttempt)",
                "cleanupExecutor().execute",
                "!stages.allowed(workerAttempt)",
                "stages.terminal(",
                "errorCode = reason",
                "callbacks.post",
                "activityLeaseIsCurrent(",
            ),
        )
    }

    @Test
    fun permanentGatewayConflictFailsClosedAfterStaleRevisionRecoveryCheck() {
        val request = movedBody("startAccountDeletionCallAllowed")

        assertTrue(
            appearsInOrder(
                request,
                "progress.handleEvidenceRevisionConflict(",
                "accountDeletionHttpFailureDisposition(",
                "AccountDeletionHttpFailureDisposition.TerminalConflict",
                "progress.postFailure(",
                "AccountDeletionHttpFailureDisposition.Retry",
                "markRetry(",
                "disposition.code",
                "workerAttempt",
            ),
        )
    }

    @Test
    fun startupRestoreRetainsOnlyCurrentMonotonicProcessSuccessor() {
        val restore = movedBody("restorePrivacyControlStateFromPrefs")
        assertTrue(
            appearsInOrder(
                restore,
                "deletion.restoreJournal(",
                "AccountDeletionStartupRestoreResult.RESTORED",
                ".RETAINED_MONOTONIC_PROCESS_SUCCESSOR",
                "AccountDeletionStartupRestoreResult.STALE_ACTIVITY",
                "AccountDeletionStartupRestoreResult.CONFLICT",
                "return false",
            ),
        )
    }

    @Test
    fun startupReadyIsForegroundBoundAndResourceCloseIsCoalesced() {
        val controller = ReportStaticSourceInspector.read(
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/startup/PrivacyStartupInspectionController.kt",
        )
        assertTrue(source.contains("privacyStartupInspectionController.startInspection(prepare, inspect, onReady)"))
        assertTrue(source.contains("privacyStartupInspectionController.completeReadyIfForeground()"))
        assertTrue(source.contains("privacyStartupInspectionController.scheduleResourcesClose()"))
        val inspection = ReportStaticSourceInspector.functionBlock(controller, "fun startInspection")
        assertTrue(appearsInOrder(inspection, "pendingPrivacyStartupReadyAction = PendingReady(generation, onReady)", "completeReadyIfForeground()"))
        val ready = ReportStaticSourceInspector.functionBlock(controller, "fun completeReadyIfForeground")
        assertTrue(ready.contains("!isForeground()"))
        assertTrue(appearsInOrder(ready, "pendingPrivacyStartupReadyAction ?: return",
            "pendingPrivacyStartupReadyAction = null", "if (!isCurrent(pending.generation))", "privacyStartupInspectionComplete = true", "pending.action()"))
        val close = ReportStaticSourceInspector.functionBlock(controller, "fun scheduleResourcesClose")
        assertTrue(close.contains("privacyStartupResourcesCloseScheduled"))
        assertTrue(close.contains("privacyStartupResourcesCloseScheduled = true"))
        assertTrue(close.contains("::closeResourcesOnWorker"))
    }

    @Test
    fun absentPreparedMarkerAbortsOnlyNonDurablePreparationRecovery() {
        val callback = movedBody("postAccountDeletionRecoveryMarkerPreparation")
        assertTrue(
            appearsInOrder(
                callback,
                "AccountDeletionRecoveryMarkerPreparation.Absent",
                "if (recoveringDurableConfirmation)",
                "retainPreparationRecovery(",
                "abortPreparationRecovery(",
                "effects.resetIntegratedConsentForNewEnrollment()",
                "effects.resetReportConsentForNewEnrollment()",
            ),
        )
    }

    @Test
    fun nineAccessibleRowsAndLocalPurgeArePresent() {
        val rows = ReportStaticSourceInspector.blockAfter(
            settingsPanel,
            "DeletionInventoryItem.entries.associateWith",
        )
        assertTrue(rows.contains("id = View.generateViewId()"))
        assertTrue(rows.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(source.contains("accountDeletionItemTexts.putAll(settingsPanel.accountDeletionItemTexts)"))
        assertEquals(9, kr.co.hanium.dreamup.walksafe.session.DeletionInventoryItem.entries.size)
        val display = ReportStaticSourceInspector.functionBlock(
            settingsPresenter,
            "fun renderAccountDeletion",
        )
        assertTrue(display.contains("DeletionInventoryItem.entries.forEach"))
        assertTrue(display.contains("settings.accountDeletionItemTexts.getValue(item)"))
        assertTrue(display.contains("view.contentDescription = details"))
        val update = ReportStaticSourceInspector.functionBlock(source, "private fun updateAccountDeletionUi")
        assertTrue(update.contains("settingsPrivacyPresenter.renderAccountDeletion(captureAccountDeletionUiFacts())"))
        assertTrue(source.contains("settingsPrivacyPresenter = SettingsPrivacyPresenter(settingsPanel, onboardingPanel)"))
        assertTrue(source.contains("fieldSessionLog.purgeAll()"))
        assertTrue(source.contains("invalidateFrameStateForPause()"))
        assertTrue(source.contains("reportAttemptStore = AndroidReportAttemptStore(bootCountProvider = ::reportCooldownBootCount)"))
    }

    @Test
    fun logoutDoesNotBecomeDeletionOrEnrollmentReset() {
        val logout = movedBody("onAccountLogoutClicked")
        assertFalse(logout.contains("onConfirmClicked("))
        assertFalse(logout.contains("resetAfterConfirmedDeletion("))
        assertTrue(logout.contains("independent_preferences_retained"))
    }


    private data class MovedSource(val ownerPath: String, val method: String, val mainDeclaration: String? = null)
    private val movedSources = mapOf(
        "onAccountLogoutClicked" to MovedSource("session/account/AccountAccessInteractionOwner.kt", "onAccountLogoutClicked", "private fun onAccountLogoutClicked() {\n        accountAccessInteractionOwner.onAccountLogoutClicked()\n    }"),
        "accountDeletionRecoveryActorIdOrNull" to MovedSource("session/deletion/runtime/AccountDeletionRecoveryActorController.kt", "recoveryActorIdOrNull", "private fun accountDeletionRecoveryActorIdOrNull(): String? =\n        accountDeletionRecoveryActorController.recoveryActorIdOrNull()"),
        "accountDeletionRecoveryGatewaySessionOrNull" to MovedSource("session/deletion/runtime/AccountDeletionRecoveryActorController.kt", "recoveryGatewaySessionOrNull", "private fun accountDeletionRecoveryGatewaySessionOrNull(\n        session: GatewayFieldSession? = GatewaySessionProcessCoordinator.snapshot().session,\n        speak: Boolean = false,\n    ): GatewayFieldSession? =\n        accountDeletionRecoveryActorController.recoveryGatewaySessionOrNull(session, speak)"),
        "accountDeletionWorkerStageAllowed" to MovedSource("session/deletion/runtime/AccountDeletionWorkerStages.kt", "allowed"),
        "applyAccountDeletionRuntimeFence" to MovedSource("session/deletion/runtime/AccountDeletionRuntimeFenceController.kt", "apply", "private fun applyAccountDeletionRuntimeFence(): Boolean =\n        accountDeletionRuntimeFenceController.apply()"),
        "continueAccountDeletionFromMarker" to MovedSource("session/deletion/runtime/AccountDeletionProgressController.kt", "continueFromMarker", "private fun continueAccountDeletionFromMarker(\n        record: AndroidAccountDeletionFallbackMarker.Record,\n        workerAttempt: AccountDeletionWorkerAttempt? = null,\n    ) =\n        accountDeletionProgressController.continueFromMarker(record, workerAttempt)"),
        "onAccountDeletionCancelClicked" to MovedSource("session/deletion/runtime/AccountDeletionPreparationController.kt", "onCancelClicked", "private fun onAccountDeletionCancelClicked() =\n        accountDeletionPreparationController.onCancelClicked()"),
        "onAccountDeletionConfirmClicked" to MovedSource("session/deletion/runtime/AccountDeletionPreparationController.kt", "onConfirmClicked", "private fun onAccountDeletionConfirmClicked() =\n        accountDeletionPreparationController.onConfirmClicked()"),
        "onGatewayProcessSessionChanged" to MovedSource("session/gateway/ActorSessionLifecycleCoordinator.kt", "onGatewayProcessSessionChanged", "private fun onGatewayProcessSessionChanged(\n        snapshot: GatewaySessionProcessSnapshot,\n        consentAuthorityChanged: Boolean,\n    ) {\n        actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)\n        refreshGuidanceDiagnosticAuthority()\n    }"),
        "performWalkSessionSafetyStopCleanup" to MovedSource("session/runtime/WalkSessionCleanupController.kt", "performWalkSessionSafetyStopCleanup", "private fun performWalkSessionSafetyStopCleanup(\n        reason: String,\n        persistInterruptionMarker: Boolean,\n        checkpoints: WalkRuntimeStopCleanupCheckpoints?,\n        onCameraResult: ((Boolean) -> Unit)?,\n        onLocationResult: ((Boolean) -> Unit)? = null,\n    ): Boolean = walkSessionCleanupController.performWalkSessionSafetyStopCleanup(reason, persistInterruptionMarker, checkpoints, onCameraResult, onLocationResult)"),
        "postAccountDeletionPendingJournalReady" to MovedSource("session/deletion/runtime/AccountDeletionPreparationController.kt", "postPendingJournalReady", "private fun postAccountDeletionPendingJournalReady(\n        workerAttempt: AccountDeletionWorkerAttempt,\n        generation: Long,\n        trustedGatewayOrigin: String,\n        session: GatewayFieldSession,\n        pendingJournal: AccountDeletionJournal,\n        markerRecord: AndroidAccountDeletionFallbackMarker.Record,\n    ) =\n        accountDeletionPreparationController.postPendingJournalReady(workerAttempt, generation, trustedGatewayOrigin, session, pendingJournal, markerRecord)"),
        "postAccountDeletionPreparationFailure" to MovedSource("session/deletion/runtime/AccountDeletionPreparationController.kt", "postPreparationFailure", "private fun postAccountDeletionPreparationFailure(\n        workerAttempt: AccountDeletionWorkerAttempt,\n        generation: Long,\n        @Suppress(\"UNUSED_PARAMETER\") requestId: String,\n        reason: String,\n    ) =\n        accountDeletionPreparationController.postPreparationFailure(workerAttempt, generation, requestId, reason)"),
        "postAccountDeletionProgressFailure" to MovedSource("session/deletion/runtime/AccountDeletionProgressController.kt", "postFailure", "private fun postAccountDeletionProgressFailure(\n        reason: String,\n        workerAttempt: AccountDeletionWorkerAttempt,\n    ) =\n        accountDeletionProgressController.postFailure(reason, workerAttempt)"),
        "postAccountDeletionRecoveryMarkerPreparation" to MovedSource("session/deletion/runtime/AccountDeletionPreparationController.kt", "postRecoveryMarkerPreparation", "private fun postAccountDeletionRecoveryMarkerPreparation(\n        generation: Long,\n        trustedGatewayOrigin: String,\n        session: GatewayFieldSession,\n        recoveringDurableConfirmation: Boolean,\n        workerAttempt: AccountDeletionWorkerAttempt,\n        preparation: AccountDeletionRecoveryMarkerPreparation,\n    ) =\n        accountDeletionPreparationController.postRecoveryMarkerPreparation(generation, trustedGatewayOrigin, session, recoveringDurableConfirmation, workerAttempt, preparation)"),
        "prepareAccountDeletionIntentAuthority" to MovedSource("session/deletion/runtime/AccountDeletionRecoveryStore.kt", "prepareIntentAuthority", "private fun prepareAccountDeletionIntentAuthority():\n        AccountDeletionAuthorityPreparation =\n        accountDeletionRecoveryStore.prepareIntentAuthority()"),
        "prepareAccountDeletionPendingJournal" to MovedSource("session/deletion/runtime/AccountDeletionRecoveryStore.kt", "preparePendingJournal"),
        "prepareAccountDeletionRecoveryMarker" to MovedSource("session/deletion/runtime/AccountDeletionRecoveryStore.kt", "prepareRecoveryMarker"),
        "preparedAccountDeletionRecoveryCandidateOrNull" to MovedSource("session/deletion/runtime/AccountDeletionRecoveryStore.kt", "preparedRecoveryCandidateOrNull"),
        "preparedAccountDeletionRecoveryRecordOrNull" to MovedSource("session/deletion/runtime/AccountDeletionRecoveryStore.kt", "preparedRecoveryRecordOrNull"),
        "purgeAccountDeletionLocalDataOnCleanupThread" to MovedSource("session/deletion/runtime/AccountDeletionLocalDataPurger.kt", "purgeLocalDataOnWorker", "private fun purgeAccountDeletionLocalDataOnCleanupThread(): Boolean =\n        accountDeletionLocalDataPurger.purgeLocalDataOnWorker()"),
        "readAccountDeletionFallbackMarkerAtStartup" to MovedSource("session/deletion/runtime/AccountDeletionRecoveryStore.kt", "readFallbackAtStartup", "private fun readAccountDeletionFallbackMarkerAtStartup() =\n        accountDeletionRecoveryStore.readFallbackAtStartup()"),
        "reconcileAccountDeletionAuthorityAfterJournalRestore" to MovedSource("session/deletion/runtime/AccountDeletionRecoveryStore.kt", "reconcileAuthorityAfterJournalRestore"),
        "reconcileAccountDeletionFallbackMarkerAtStartup" to MovedSource("session/deletion/runtime/AccountDeletionRecoveryStore.kt", "reconcileFallbackAtStartup", "private fun reconcileAccountDeletionFallbackMarkerAtStartup() =\n        accountDeletionRecoveryStore.reconcileFallbackAtStartup()"),
        "refreshAccountDeletionStatus" to MovedSource("session/deletion/runtime/AccountDeletionNetworkController.kt", "refreshStatus", "private fun refreshAccountDeletionStatus() =\n        accountDeletionNetworkController.refreshStatus()"),
        "refreshAccountDeletionStatusAllowed" to MovedSource("session/deletion/runtime/AccountDeletionNetworkController.kt", "refreshStatusAllowed", "private fun refreshAccountDeletionStatusAllowed(\n        journal: AccountDeletionJournal,\n        workerAttempt: AccountDeletionWorkerAttempt,\n    ) =\n        accountDeletionNetworkController.refreshStatusAllowed(journal, workerAttempt)"),
        "restorePrivacyControlStateFromPrefs" to MovedSource("session/gateway/PrivacyStartupRestoreCoordinator.kt", "restore", "private fun restorePrivacyControlStateFromPrefs(): Boolean = privacyStartupRestoreCoordinator.restore()"),
        "resumeAccountDeletionFromMarker" to MovedSource("session/deletion/runtime/AccountDeletionProgressController.kt", "resumeFromMarker", "private fun resumeAccountDeletionFromMarker() =\n        accountDeletionProgressController.resumeFromMarker()"),
        "retryAccountDeletionPurgeOnCleanupThread" to MovedSource("session/deletion/runtime/AccountDeletionLocalDataPurger.kt", "retry"),
        "runAccountDeletionWorkerDurableStage" to MovedSource("session/deletion/runtime/AccountDeletionWorkerStages.kt", "durable", "private fun runAccountDeletionWorkerDurableStage(\n        attempt: AccountDeletionWorkerAttempt,\n        action: (AccountDeletionJournal?) -> Boolean,\n    ): Boolean? =\n        accountDeletionWorkerStages.durable(attempt, action)"),
        "runAccountDeletionWorkerTerminalStage" to MovedSource("session/deletion/runtime/AccountDeletionWorkerStages.kt", "terminal", "private fun runAccountDeletionWorkerTerminalStage(\n        attempt: AccountDeletionWorkerAttempt,\n        errorCode: String? = null,\n    ): Boolean? =\n        accountDeletionWorkerStages.terminal(attempt, errorCode)"),
        "scheduleAcceptedAccountDeletionLocalPurge" to MovedSource("session/deletion/runtime/AccountDeletionProgressController.kt", "scheduleAcceptedLocalPurge", "private fun scheduleAcceptedAccountDeletionLocalPurge(\n        record: AndroidAccountDeletionFallbackMarker.Record,\n        journal: AccountDeletionJournal,\n    ) =\n        accountDeletionProgressController.scheduleAcceptedLocalPurge(record, journal)"),
        "scheduleAccountDeletionActorBindingCleanup" to MovedSource("session/deletion/runtime/AccountDeletionProgressController.kt", "scheduleActorBindingCleanup", "private fun scheduleAccountDeletionActorBindingCleanup(\n        journal: AccountDeletionJournal,\n    ) =\n        accountDeletionProgressController.scheduleActorBindingCleanup(journal)"),
        "scheduleAccountDeletionEvidenceRebase" to MovedSource("session/deletion/runtime/AccountDeletionEvidenceController.kt", "scheduleRebase", "private fun scheduleAccountDeletionEvidenceRebase(\n        journal: AccountDeletionJournal,\n    ) =\n        accountDeletionEvidenceController.scheduleRebase(journal)"),
        "scheduleAccountDeletionProgressAfterStatus" to MovedSource("session/deletion/runtime/AccountDeletionProgressController.kt", "scheduleAfterStatus", "private fun scheduleAccountDeletionProgressAfterStatus(\n        journal: AccountDeletionJournal,\n    ) =\n        accountDeletionProgressController.scheduleAfterStatus(journal)"),
        "startAccountDeletionCall" to MovedSource("session/deletion/runtime/AccountDeletionNetworkController.kt", "startCall", "private fun startAccountDeletionCall(\n        callFactory: () -> CancellableNetworkCall<AccountDeletionStatus>,\n        purpose: AccountDeletionCallPurpose,\n    ) =\n        accountDeletionNetworkController.startCall(callFactory, purpose)"),
        "startAccountDeletionCallAllowed" to MovedSource("session/deletion/runtime/AccountDeletionNetworkController.kt", "startCallAllowed", "private fun startAccountDeletionCallAllowed(\n        call: CancellableNetworkCall<AccountDeletionStatus>,\n        purpose: AccountDeletionCallPurpose,\n    ) =\n        accountDeletionNetworkController.startCallAllowed(call, purpose)"),
        "startAccountDeletionConfirmationPreparation" to MovedSource("session/deletion/runtime/AccountDeletionPreparationController.kt", "startConfirmationPreparation", "private fun startAccountDeletionConfirmationPreparation(\n        trustedGatewayOrigin: String,\n        session: GatewayFieldSession,\n        actorId: String,\n        recoveringDurableConfirmation: Boolean,\n        workerAttempt: AccountDeletionWorkerAttempt,\n    ) =\n        accountDeletionPreparationController.startConfirmationPreparation(trustedGatewayOrigin, session, actorId, recoveringDurableConfirmation, workerAttempt)"),
        "startAccountDeletionFallbackRecoveryAfterRestore" to MovedSource("session/deletion/runtime/AccountDeletionProgressController.kt", "startFallbackRecoveryAfterRestore", "private fun startAccountDeletionFallbackRecoveryAfterRestore() =\n        accountDeletionProgressController.startFallbackRecoveryAfterRestore()"),
        "startAccountDeletionPreparation" to MovedSource("session/deletion/runtime/AccountDeletionPreparationController.kt", "startPreparation", "private fun startAccountDeletionPreparation(\n        trustedGatewayOrigin: String,\n        session: GatewayFieldSession,\n        markerRecord: AndroidAccountDeletionFallbackMarker.Record,\n        workerAttempt: AccountDeletionWorkerAttempt,\n    ) =\n        accountDeletionPreparationController.startPreparation(trustedGatewayOrigin, session, markerRecord, workerAttempt)"),
        "startPreparedAccountDeletionAuthorityConfirmation" to MovedSource("session/deletion/runtime/AccountDeletionPreparationController.kt", "startPreparedAuthorityConfirmation", "private fun startPreparedAccountDeletionAuthorityConfirmation(\n        generation: Long,\n        trustedGatewayOrigin: String,\n        session: GatewayFieldSession,\n        markerRecord: AndroidAccountDeletionFallbackMarker.Record,\n        workerAttempt: AccountDeletionWorkerAttempt,\n    ) =\n        accountDeletionPreparationController.startPreparedAuthorityConfirmation(generation, trustedGatewayOrigin, session, markerRecord, workerAttempt)"),
        "submitPendingDeviceDeletionEvidence" to MovedSource("session/deletion/runtime/AccountDeletionEvidenceController.kt", "submitPending", "private fun submitPendingDeviceDeletionEvidence(\n        record: AndroidAccountDeletionFallbackMarker.Record,\n        journal: AccountDeletionJournal,\n    ) =\n        accountDeletionEvidenceController.submitPending(record, journal)"),
        "submitPendingDeviceDeletionEvidenceAllowed" to MovedSource("session/deletion/runtime/AccountDeletionEvidenceController.kt", "submitPendingAllowed", "private fun submitPendingDeviceDeletionEvidenceAllowed(\n        record: AndroidAccountDeletionFallbackMarker.Record,\n        journal: AccountDeletionJournal,\n    ) =\n        accountDeletionEvidenceController.submitPendingAllowed(record, journal)")
    )
    private fun movedBody(mainMethod: String): String {
        val spec = movedSources.getValue(mainMethod)
        if (spec.mainDeclaration != null) {
            assertTrue("The actual Main must delegate $mainMethod to this exact owner", source.contains(spec.mainDeclaration))
        } else {
            assertActualOwnerConnection(mainMethod)
        }
        val owner = ReportStaticSourceInspector.read("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/" + spec.ownerPath)
        return namedFunctionText(owner, spec.method)
    }
    private fun readOwner(path: String): String = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/" + path,
    )

    private fun assertRecoveryStoreAssembly() {
        val factory = MainActivityRefactorSource.composition(source, "accountDeletionRecoveryStore")
        assertTrue(source.contains("private val accountDeletionRecoveryStore = privacyAuthority.createAccountDeletionRecoveryStore()"))
        assertTrue(factory.contains("AccountDeletionRecoveryStore = AccountDeletionRecoveryStore("))
        val boundary = MainActivityRefactorSource.factory(source, "createPrivacyAuthorityComposition")
        assertTrue(boundary.contains("sensitivePreferences = { sensitivePrefs }"))
        assertTrue(boundary.contains("stateMachine = { accountDeletionStateMachine }"))
        assertTrue(boundary.contains("activityLease = { accountDeletionActivityLease }"))
        assertTrue(factory.contains("sensitivePreferences = { sensitivePreferences() }"))
        assertTrue(factory.contains("stateMachine = { stateMachine() }"))
        assertTrue(factory.contains("activityLease = { activityLease() }"))
        assertTrue(factory.contains("workerStages = accountDeletionWorkerStages"))
        assertTrue(factory.contains("resetController = { accountDeletionInstallationResetController }"))
        assertTrue(recoveryStore.contains("private val workerStages: AccountDeletionWorkerStages"))
    }

    private fun assertPreparationAssembly() {
        assertRecoveryStoreAssembly()
        val factory = MainActivityRefactorSource.composition(source, "accountDeletionPreparationController")
        assertTrue(source.contains("private val accountDeletionPreparationController = createPrivacyPreparation()"))
        assertTrue(factory.contains("AccountDeletionPreparationController = AccountDeletionPreparationController("))
        assertTrue(factory.contains("store = accountDeletionRecoveryStore"))
        assertTrue(factory.contains("stages = accountDeletionWorkerStages"))
        assertTrue(factory.contains("stateMachine = { stateMachine() }"))
        assertTrue(factory.contains("activityLease = { activityLease() }"))
        assertTrue(factory.contains("resetController = { accountDeletionInstallationResetController }"))
        val owner = readOwner("session/deletion/runtime/AccountDeletionPreparationController.kt")
        assertTrue(owner.contains("private val store: AccountDeletionRecoveryStore"))
        assertTrue(owner.contains("private val stages: AccountDeletionWorkerStages"))
    }

    private fun startupRestoreBinding(): String {
        assertRecoveryStoreAssembly()
        assertTrue(source.contains("private fun restorePrivacyControlStateFromPrefs(): Boolean = privacyStartupRestoreCoordinator.restore()"))
        val binding = MainActivityRefactorSource.composition(source, "privacyStartupRestoreCoordinator")
        assertTrue(binding.contains("lazy(LazyThreadSafetyMode.NONE)"))
        assertTrue(binding.contains("PrivacyStartupRestoreCoordinator("))
        assertTrue(binding.contains("deletion = object : PrivacyStartupRestoreCoordinator.Deletion"))
        return binding
    }

    private fun assertActualOwnerConnection(mainMethod: String) {
        when (mainMethod) {
            "prepareAccountDeletionRecoveryMarker" -> {
                assertPreparationAssembly()
                assertTrue(movedBody("startAccountDeletionConfirmationPreparation")
                    .contains("preparation = store.prepareRecoveryMarker("))
            }
            "prepareAccountDeletionPendingJournal" -> {
                assertPreparationAssembly()
                assertTrue(movedBody("startAccountDeletionPreparation")
                    .contains("preparation = store.preparePendingJournal("))
            }
            "accountDeletionWorkerStageAllowed" -> {
                assertPreparationAssembly()
                val stages = MainActivityRefactorSource.composition(source, "accountDeletionWorkerStages")
                assertTrue(stages.contains("stateMachine = { stateMachine() }"))
                assertTrue(stages.contains("activityLease = { activityLease() }"))
                assertTrue(stages.contains("resetController = { accountDeletionInstallationResetController }"))
                assertTrue(stages.contains("keepPrivacyFenceClosed = ::keepAccountDeletionPrivacyFenceClosed"))
                assertTrue(movedBody("startAccountDeletionPreparation").contains("!stages.allowed(workerAttempt)"))
            }
            "preparedAccountDeletionRecoveryCandidateOrNull" -> {
                assertTrue(startupRestoreBinding().contains("override fun preparedRecoveryCandidatePresent() = accountDeletionRecoveryStore.preparedRecoveryCandidateOrNull() != null"))
                assertTrue(namedFunctionText(readOwner("session/gateway/PrivacyStartupRestoreCoordinator.kt"), "restore")
                    .contains("deletion.preparedRecoveryCandidatePresent()"))
            }
            "preparedAccountDeletionRecoveryRecordOrNull" -> {
                // This owner method had no product caller before the unused Main facade was removed.
                // Keep its identity-bound contract on the actual, shared recovery store without activating it.
                assertRecoveryStoreAssembly()
                assertTrue(startupRestoreBinding().contains("accountDeletionRecoveryStore.preparedRecoveryCandidateOrNull()"))
                assertTrue(namedFunctionText(recoveryStore, "preparedRecoveryRecordOrNull")
                    .contains("val record = preparedRecoveryCandidateOrNull() ?: return null"))
            }
            "reconcileAccountDeletionAuthorityAfterJournalRestore" -> {
                assertTrue(startupRestoreBinding().contains("override fun reconcileAuthorityAfterJournalRestore() = accountDeletionRecoveryStore.reconcileAuthorityAfterJournalRestore()"))
                assertTrue(namedFunctionText(readOwner("session/gateway/PrivacyStartupRestoreCoordinator.kt"), "restore")
                    .contains("return deletion.reconcileAuthorityAfterJournalRestore()"))
            }
            "retryAccountDeletionPurgeOnCleanupThread" -> {
                val factory = MainActivityRefactorSource.composition(source, "accountDeletionLocalDataPurger")
                assertTrue(factory.contains("AccountDeletionLocalDataPurger("))
                assertTrue(appearsInOrder(factory, "sensitivePreferences = { sensitivePreferences() }",
                    "legacyQueue = legacyPendingReportQueuePurgeController"))
                assertTrue(factory.contains("participants = participants({ speechOutputStorageCoordinator }, routeArchive)"))
                assertTrue(MainActivityRefactorSource.factory(source, "createPrivacyLocalPurge")
                    .contains("override fun purgeCollectionFieldProvider() = speech().collectionOrNull()?.provider?.purgeAll()"))
                assertTrue(movedBody("purgeAccountDeletionLocalDataOnCleanupThread").contains("val routeSnapshotPurged = retry {"))
            }
            else -> error("Missing actual owner connection for $mainMethod")
        }
    }

    private fun assertStartupResetInspectionConnection() {
        MainActivityRefactorSource.composition(source, "accountDeletionInstallationResetController")
        assertTrue(source.contains("private val accountDeletionInstallationResetController = privacyAuthority.createAccountDeletionInstallationResetController()"))
        val inspection = source.substringAfter("inspect = inspection@{ speechOutputPurgeCompleted ->")
            .substringBefore("restoreEmailEnrollmentPartialAtStartup()")
        assertTrue(appearsInOrder(inspection,
            "accountDeletionInstallationResetController.inspectStartupReset(speechOutputPurgeCompleted)",
            "InstallationResetStartupResult.FINISH", "return@inspection PrivacyStartupInspectionResult.FINISH"))
        assertTrue(namedFunctionText(installationResetController, "inspectStartupReset")
            .contains("when (reconcileResetAtStartup(acknowledged))"))
    }

    private fun namedFunctionText(owner: String, method: String): String {
        val signature = Regex("\\bfun\\s+" + Regex.escape(method) + "\\s*\\(")
        val match = requireNotNull(signature.find(owner)) { "Missing actual named method: $method" }
        val next = Regex("(?m)^    (?:(?:private|internal|override|public|protected) )*(?:fun|val|var|companion object)\\b|^}")
            .find(owner, match.range.last + 1)
        return owner.substring(match.range.first, next?.range?.first ?: owner.length)
    }

    private fun appearsInOrder(source: String, vararg tokens: String): Boolean {
        if (
            source.isEmpty() ||
            tokens.isEmpty() ||
            tokens.any(String::isBlank) ||
            tokens.toSet().size != tokens.size
        ) return false
        var offset = 0
        tokens.forEach { token ->
            val match = source.indexOf(token, offset)
            if (match < 0) return false
            offset = match + token.length
        }
        return true
    }

    private companion object {
        const val MAIN_ACTIVITY_PATH =
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"
    }
}
