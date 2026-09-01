package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityAccountDeletionStaticTest {
    private val source =
        ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)

    @Test
    fun acceptancePrecedesLocalPurgeAndEvidenceSubmission() {
        val confirm =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun onAccountDeletionConfirmClicked",
        )
        assertTrue(confirm.contains("AccountDeletionPhase.CONFIRM_REQUIRED"))
        assertTrue(
            appearsInOrder(
                confirm,
                "AccountDeletionPhase.CONFIRM_REQUIRED",
                "accountDeletionRecoveryGatewaySessionOrNull(",
                "configuredGatewayOriginOrNull()",
                "sensitivePrefs.isBlocked()",
                "applyAccountDeletionRuntimeFence()",
                "beginPreparationWorkerAttempt(",
                "startAccountDeletionConfirmationPreparation(",
            ),
        )

        val markerPreparation =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun prepareAccountDeletionRecoveryMarker",
            )
        val preparation =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun startAccountDeletionPreparation",
            )
        val pendingReady =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun postAccountDeletionPendingJournalReady",
            )
        val pendingJournalPreparation =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun prepareAccountDeletionPendingJournal",
            )
        val acceptedPurge =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun scheduleAcceptedAccountDeletionLocalPurge",
            )
        val submitEvidence =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun submitPendingDeviceDeletionEvidenceAllowed",
            )
        assertTrue(
            "The encrypted capability marker must precede the pending journal and POST.",
            appearsInOrder(
                markerPreparation + "\n" + pendingJournalPreparation + "\n" + pendingReady,
                "ByteArray(32)",
                "Base64.getUrlEncoder().withoutPadding()",
                "phase = AndroidAccountDeletionFallbackMarker.Phase.PREPARED",
                "accountDeletionFallbackMarker.create(record)",
                "accountDeletionFallbackMarker.read()",
                "AccountDeletionJournal.pending(",
                "PREF_ACCOUNT_DELETION_JOURNAL",
                ".commit()",
                "exactReadback",
                "accountDeletionClient.requestDeletionCall(",
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
        assertTrue(preparation.contains("prepareAccountDeletionPendingJournal("))
        assertTrue(preparation.contains("postAccountDeletionPendingJournalReady("))
        assertTrue(
            "Local purge starts only from a canonically accepted marker, then durable " +
                "evidence is recorded before submission.",
            appearsInOrder(
                acceptedPurge,
                "accountDeletionLocalPurgeInFlightRequestId",
                "accountDeletionCleanupExecutor.execute",
                "purgeAccountDeletionLocalDataOnCleanupThread()",
                "DeviceDeletionEvidence(",
                "deviceDeletionEvidenceSha256(evidenceWithoutHash)",
                "Phase.EVIDENCE_PENDING",
                "accountDeletionFallbackMarker.update(next)",
                "accountDeletionStateMachine.recordDeviceEvidence(",
                "runAccountDeletionWorkerDurableStage(workerAttempt)",
                "persistAccountDeletionJournal(current)",
                "submitPendingDeviceDeletionEvidenceAllowed(",
            ),
        )
        assertTrue(
            appearsInOrder(
                pendingReady,
                "reportCleanupDestroyed",
                "activityToken != reportCleanupActivityToken",
                "!isCurrentAccountDeletionPreparation(generation)",
                "accountDeletionStateMachine.begin(pendingJournal, workerAttempt)",
                "accountDeletionClient.requestDeletionCall(",
            ),
        )
        assertTrue(submitEvidence.contains("submitDeviceDeletionEvidenceCall("))
        assertTrue(submitEvidence.contains("accessSecret = record.accessSecret"))
        assertFalse(preparation.contains("purgeAccountDeletionLocalDataOnCleanupThread()"))
        assertFalse(pendingReady.contains("purgeAccountDeletionLocalDataOnCleanupThread()"))
    }

    @Test
    fun runtimeFenceBlocksFieldLogAdmissionBeforeSafetyStopEvents() {
        val runtimeFence = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun applyAccountDeletionRuntimeFence",
        )
        assertTrue(
            appearsInOrder(
                runtimeFence,
                "fieldSessionLog.blockNewProcessingForAccountDeletion()",
                "integratedConsentSession.blockForAccountDeletion()",
                "reportPrivacyConsentSession.blockForAccountDeletion()",
                "enterWalkSessionSafetyStopAndCancelOutputs(",
            ),
        )
        assertTrue(runtimeFence.contains("persistInterruptionMarker = false"))
        val safetyStop = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun enterWalkSessionSafetyStopAndCancelOutputs",
        )
        assertTrue(
            safetyStop.contains(
                "if (persistInterruptionMarker) persistWalkSessionInterruptionMarker()",
            ),
        )

        val durableCleanup = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun purgeAccountDeletionLocalDataOnCleanupThread",
        )
        assertTrue(
            appearsInOrder(
                durableCleanup,
                "fieldSessionLog.blockForAccountDeletion()",
                "fieldSessionLog.purgeAll()",
            ),
        )
    }

    @Test
    fun identityFreeIntentFencePrecedesIdentityAndSurvivesRestartUntilPlainReset() {
        val creation = ReportStaticSourceInspector.functionBlock(
            source,
            "override fun onCreate",
        )
        val fenceWiring = creation
            .substringAfter("accountDeletionIntentFence = AccountDeletionIntentFence(")
            .substringBefore(
                "accountDeletionResetCoordinator = createAccountDeletionResetCoordinator()",
            )
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
                "accountDeletionPreparedConfirmationRecovery =",
                "accountDeletionDualAuthority = AccountDeletionDualAuthority(",
                "accountDeletionAuthorityAtStartup =",
                "accountDeletionDualAuthority.startupState()",
                "initiallyBlockedForAccountDeletion =",
                "accountDeletionMarkerPresentAtStartup()",
                "accountDeletionAuthorityAtStartup.blocksPrivacy",
                "restorePrivacyControlStateFromPrefs()",
            ),
        )
        val startupMarker = source
            .substringAfter("private fun accountDeletionMarkerPresentAtStartup")
            .substringBefore("private fun restoreLegacyIntegratedConsentRevisionFloor")
        assertTrue(startupMarker.contains("accountDeletionAuthorityAtStartup.blocksPrivacy"))

        val restore = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun restorePrivacyControlStateFromPrefs",
        )
        assertTrue(restore.contains("reconcileAccountDeletionAuthorityAfterJournalRestore()"))
        val authorityRestore = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun reconcileAccountDeletionAuthorityAfterJournalRestore",
        )
        assertTrue(
            appearsInOrder(
                authorityRestore,
                "beginLegacyFailClosedUpgradeAttempt(",
                "runLegacyFailClosedUpgradeStageIfCurrent(",
                "accountDeletionDualAuthority.failClosed(reason).durableSuccess",
                "accountDeletionDualAuthority.confirm().decision",
                "accountDeletionStartupAuthorityDecision(",
                "enforceFailClosedAuthority(",
                "forceAccountDeletionAuthorityFailClosed(reason)",
                "blockPrivacyForAccountDeletionAuthority()",
            ),
        )

        val plainReset = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun clearAccountDeletionPlainResetArtifacts",
        )
        val resetCoordinator = source
            .substringAfter("private fun createAccountDeletionResetCoordinator")
            .substringBefore("private fun clearAccountDeletionPlainResetArtifacts")
        assertTrue(
            resetCoordinator.contains(
                "resetPlainPreferences = ::clearAccountDeletionPlainResetArtifacts",
            ),
        )
        assertTrue(
            appearsInOrder(
                resetCoordinator,
                "resetFieldStorage = {",
                "fieldSessionLog.resetForNewEnrollment()",
                "clearDeletionIntentFence = ::clearAccountDeletionIntentFence",
            ),
        )
        assertFalse(plainReset.contains("PREF_ACCOUNT_DELETION_INTENT_FENCE"))
        val clearFence = source
            .substringAfter("private fun clearAccountDeletionIntentFence")
            .substringBefore("private fun reconcileAccountDeletionResetAtStartup")
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
                "accountDeletionIntentFence = AccountDeletionIntentFence(",
                "restorePrivacyControlStateFromPrefs()",
            ),
        )

        val attemptCalls = Regex(
            "accountDeletionStateMachine\\." +
                "(?:beginPreparationWorkerAttempt|beginWorkerAttempt)\\([\\s\\S]*?\\)",
        ).findAll(source).map(MatchResult::value).toList()
        assertTrue(attemptCalls.isNotEmpty())
        attemptCalls.forEach { call ->
            assertTrue(
                "Every deletion worker attempt must be bound to the active Activity lease: $call",
                call.contains("accountDeletionActivityLease"),
            )
        }

        val destroy = ReportStaticSourceInspector.functionBlock(source, "override fun onDestroy")
        assertTrue(
            appearsInOrder(
                destroy,
                "accountDeletionProcessCoordinator.detach(accountDeletionActivityLease)",
                "reportCleanupDestroyed = true",
                "reportCleanupExecutor.shutdownNow()",
                "accountDeletionRequestGeneration += 1L",
                "accountDeletionCall?.cancel()",
            ),
        )

        val request = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startAccountDeletionCallAllowed",
        )
        assertTrue(
            appearsInOrder(
                request,
                "accountDeletionStateMachine.registerNetworkCall(",
                "gatewaySessionExecutor.execute {",
                "accountDeletionStateMachine.beginNetworkCall(networkLease)",
                "val execution = executeAccountDeletionCall(call)",
                "is AccountDeletionCallExecution.Succeeded",
                "accountDeletionStateMachine.completeNetworkCall(",
            ),
        )
        val executorBeforeIo = request
            .substringAfter("gatewaySessionExecutor.execute {")
            .substringBefore("val execution = executeAccountDeletionCall(call)")
        assertFalse(
            "Network I/O must not run inside a StateMachine durable-stage lambda.",
            executorBeforeIo.contains("runAccountDeletionWorkerDurableStage"),
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
                "scheduleAccountDeletionNetworkCompletion(",
                "releaseNetworkCall(networkLease)",
                "completeCurrentAccountDeletionCall(generation, call)",
                "accountDeletionWorkerStageAllowed(workerAttempt)",
            ),
        )
        assertTrue(request.contains("releaseNetworkCall(networkLease)"))
        assertTrue(request.contains("completeCurrentAccountDeletionCall(generation, call)"))
        assertTrue(rejected.contains("releaseNetworkCall(networkLease)"))
    }

    @Test
    fun durableConfirmationRecoveryIsDeletionOnlyRetryableAndIdentityBound() {
        val authorityRestore = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun reconcileAccountDeletionAuthorityAfterJournalRestore",
        )
        assertTrue(
            appearsInOrder(
                authorityRestore,
                "accountDeletionStartupAuthorityDecision(",
                "startupDecision.durableConfirmationRecoveryRequired",
                "enterDurableConfirmationRecovery(",
                "startupDecision.failClosedReason",
            ),
        )

        val confirm = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun onAccountDeletionConfirmClicked",
        )
        val markerCallback = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun postAccountDeletionRecoveryMarkerPreparation",
        )
        assertTrue(
            appearsInOrder(
                markerCallback,
                "AccountDeletionRecoveryMarkerPreparation.Ready",
                ".retainDurableConfirmationRecovery(workerAttempt)",
                "applyAccountDeletionRuntimeFence()",
                "beginPreparationWorkerAttempt(",
                "startPreparedAccountDeletionAuthorityConfirmation(",
            ),
        )
        assertFalse(confirm.contains("retainDurableConfirmationRecovery("))
        assertTrue(
            appearsInOrder(
                confirm,
                "accountDeletionRecoveryGatewaySessionOrNull(",
                "applyAccountDeletionRuntimeFence()",
                "startAccountDeletionConfirmationPreparation(",
            ),
        )
        assertTrue(confirm.contains("recovery_required:verified_login_required"))
        assertFalse(confirm.contains("failAccountDeletionPreparation("))

        val preparedAuthority = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun prepareAccountDeletionIntentAuthority",
        )
        assertTrue(
            appearsInOrder(
                preparedAuthority,
                "recoverPreparedConfirmation()",
                "PreparedConfirmationRecoveryResult.RECOVERED_CONFIRMED",
                "accountDeletionIntentFence.engage()",
                "PreparedConfirmationRecoveryResult.RECONFIRM_REQUIRED",
                "accountDeletionDualAuthority.confirm()",
                "PreparedConfirmationRecoveryResult.RETRY_REQUIRED",
                "PreparedConfirmationRecoveryResult.REJECTED",
            ),
        )

        val recoveryActor = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun accountDeletionRecoveryActorIdOrNull",
        )
        listOf(
            "durableConfirmationRecoveryRequired()",
            "firstRunOnboardingSnapshot.isComplete",
            "firstRunOnboardingSnapshot.reporterActorBinding?.value",
            "reporterUserId == liveActorId",
            "priorityUserOnboardingActorId == liveActorId",
            "priorityUserOnboardingPolicy.accountBlockReason() == null",
            "preparedRecord?.recoveryActorId",
            "preparedRecord.identity.actorHash",
        ).forEach { token -> assertTrue(recoveryActor.contains(token)) }

        val recoverySession = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun accountDeletionRecoveryGatewaySessionOrNull",
        )
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
            source,
            "private fun onGatewaySessionButtonClicked",
        )
        assertTrue(gatewayLogin.contains("val actorId = gatewayLoginActorIdOrNull()"))
        assertTrue(gatewayLogin.contains("deletionRecoveryTarget"))
        assertTrue(gatewayLogin.contains("!deletionRecoveryTarget"))
        assertTrue(
            gatewayLogin.contains(
                "accountDeletionRecoveryGatewaySessionOrNull(session) !== session",
            ),
        )
        val gatewayCommit = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun commitLoggedInGatewaySession",
        )
        assertTrue(
            appearsInOrder(
                gatewayCommit,
                "isAccountDeletionRecoveryLoginTarget(",
                "if (deletionRecoveryLogin)",
                "publishDeletionRecoveryVerified(",
                "WALKSAFE_LONG_LIVED_LOGIN_ENABLED",
                "publishVerified(",
            ),
        )
        val generalSessionGetter = source
            .substringAfter("private val gatewayFieldSession: GatewayFieldSession?")
            .substringBefore("private val gatewaySessionGeneration")
        assertTrue(generalSessionGetter.contains("takeUnless { snapshot.deletionRecoveryOnly }"))

        val logout = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun onAccountLogoutClicked",
        )
        assertTrue(
            appearsInOrder(
                logout,
                "durableConfirmationRecoveryRequired()",
                "return",
                "reporterUserId = null",
            ),
        )

        val cancel = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun onAccountDeletionCancelClicked",
        )
        assertTrue(cancel.contains("durableConfirmationRecoveryRequired()"))
        assertTrue(cancel.contains("이미 저장된 삭제 확인은 취소할 수 없습니다"))

        val ui = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun updateAccountDeletionUi",
        )
        assertTrue(ui.contains("durableRecoverySessionReady"))
        assertTrue(ui.contains("현장 게이트웨이 로그인이 필요합니다"))
        assertTrue(ui.contains("현재 로그인 계정 삭제 요청 다시 확인"))
        assertTrue(ui.contains("confirming && !durableConfirmationRecovery"))
    }

    @Test
    fun preparedMarkerAndAuthorityRecoveryAreDurableWorkerStages() {
        val click = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun onAccountDeletionConfirmClicked",
        )
        assertTrue(
            appearsInOrder(
                click,
                "sensitivePrefs.isBlocked()",
                "applyAccountDeletionRuntimeFence()",
                "beginPreparationWorkerAttempt(",
                "startAccountDeletionConfirmationPreparation(",
            ),
        )
        assertFalse(click.contains("accountDeletionFallbackMarker.create("))
        assertFalse(click.contains("accountDeletionDualAuthority.confirm()"))

        val markerWorker = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startAccountDeletionConfirmationPreparation",
        )
        assertTrue(
            appearsInOrder(
                markerWorker,
                "accountDeletionCleanupExecutor.execute",
                "runAccountDeletionWorkerDurableStage(workerAttempt)",
                "prepareAccountDeletionRecoveryMarker(",
                "postAccountDeletionRecoveryMarkerPreparation(",
            ),
        )
        val marker = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun prepareAccountDeletionRecoveryMarker",
        )
        assertTrue(
            appearsInOrder(
                marker,
                "accountDeletionFallbackMarker.read()",
                "accountDeletionFallbackMarker.create(record)",
                "val readback = accountDeletionFallbackMarker.read()",
                "readback.record == expected",
                "AccountDeletionRecoveryMarkerPreparation.Ready(expected)",
            ),
        )

        val markerCallback = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun postAccountDeletionRecoveryMarkerPreparation",
        )
        assertTrue(
            appearsInOrder(
                markerCallback,
                "AccountDeletionRecoveryMarkerPreparation.Ready",
                "retainDurableConfirmationRecovery(workerAttempt)",
                "startPreparedAccountDeletionAuthorityConfirmation(",
            ),
        )
        val authorityWorker = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startPreparedAccountDeletionAuthorityConfirmation",
        )
        assertTrue(authorityWorker.contains("accountDeletionCleanupExecutor.execute"))
        assertTrue(authorityWorker.contains("prepareAccountDeletionIntentAuthority()"))
    }

    @Test
    fun pendingJournalIsCreateOnceOrExactCanonicalReuse() {
        val pending = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun prepareAccountDeletionPendingJournal",
        )
        assertTrue(
            appearsInOrder(
                pending,
                "val rawJournal = sensitivePrefs.getString(",
                "val failClosedMarker = sensitivePrefs.getBoolean(",
                "val actorHash = sensitivePrefs.getString(",
                "if (rawJournal != null)",
                "accountDeletionJournalOrNull(rawJournal)",
                "current == expected",
                "rawJournal == accountDeletionJournalJson(current)",
                "actorHash == markerRecord.identity.actorHash",
                "terminalReason != null",
                "val pending = AccountDeletionJournal.pending(",
                ".commit()",
                "val exactReadback =",
                "if (committed && exactReadback)",
            ),
        )
        val preparation = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startAccountDeletionPreparation",
        )
        assertTrue(preparation.contains("AccountDeletionPendingJournalPreparation.Conflict"))
        assertTrue(preparation.contains("pending_journal_conflict"))
    }

    @Test
    fun preparedStartupRecoversWithoutJournalForAbsentOrInterruptedAuthority() {
        val fallback = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun reconcileAccountDeletionFallbackMarkerAtStartup",
        )
        assertTrue(
            appearsInOrder(
                fallback,
                "val rawJournal = sensitivePrefs.getString(",
                "durablePreJournalRecovery",
                "state.record == preparedAccountDeletionRecoveryCandidateOrNull()",
                "fileState !is",
                "AccountDeletionIntentAuthorityState.FailClosed",
                "rawJournal == null",
                "!exactTuple && !durablePreJournalRecovery",
            ),
        )
        val authority = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun reconcileAccountDeletionAuthorityAfterJournalRestore",
        )
        assertTrue(
            appearsInOrder(
                authority,
                "val preparedMarkerRecovery =",
                "preparedAccountDeletionRecoveryCandidateOrNull() != null",
                "authority.fileState !is AccountDeletionIntentAuthorityState.FailClosed",
                "startupDecision.durableConfirmationRecoveryRequired ||",
                "preparedMarkerRecovery",
                "enterDurableConfirmationRecovery(",
            ),
        )
        val candidate = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun preparedAccountDeletionRecoveryCandidateOrNull",
        )
        assertTrue(candidate.contains("Phase.PREPARED"))
        assertTrue(candidate.contains("configuredGatewayOriginOrNull()"))
        assertFalse(candidate.contains("recoveryActorId ?: return null"))
        val identityBound = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun preparedAccountDeletionRecoveryRecordOrNull",
        )
        assertTrue(
            appearsInOrder(
                identityBound,
                "preparedAccountDeletionRecoveryCandidateOrNull()",
                "record.recoveryActorId ?: return null",
                "sha256Hex(actorId.toByteArray(Charsets.UTF_8))",
                "record.identity.actorHash",
            ),
        )
    }

    @Test
    fun recoveryOnlyLoginIsShortLivedHiddenAndReplaceableAfterExpiry() {
        val login = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun onGatewaySessionButtonClicked",
        )
        assertTrue(
            appearsInOrder(
                login,
                "val deletionRecoverySurface =",
                "if (!BuildConfig.DEBUG && !deletionRecoverySurface) return",
                "processSnapshot.deletionRecoveryOnly",
                "accountDeletionRecoveryGatewaySessionOrNull(",
                "GatewaySessionProcessCoordinator.clear(",
                "processSnapshot.generation",
                "val deletionRecoveryTarget =",
                "!deletionRecoveryTarget",
                "enableLongLivedSession =",
            ),
        )
        val commit = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun commitLoggedInGatewaySession",
        )
        assertTrue(commit.contains("publishDeletionRecoveryVerified("))
        val authUi = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun updateBackendAuthButtonText",
        )
        assertTrue(authUi.contains("deletionRecoverySessionReady"))
        assertTrue(authUi.contains("삭제 복구 다시 로그인"))
        assertTrue(authUi.contains("val surfaceVisible = BuildConfig.DEBUG || deletionRecoverySurface"))
        assertTrue(authUi.contains("backendFieldTokenInput.visibility ="))
        assertTrue(authUi.contains("backendAuthApplyButton.visibility ="))
        assertTrue(authUi.contains("!requestInFlight &&"))
        assertTrue(authUi.contains("!deletionRecoverySessionReady &&"))
        assertTrue(authUi.contains("!deletionRecoverySessionReserved"))
        assertFalse(source.contains("gateway=login_pending actor="))
        assertFalse(source.contains("gateway=session_ready actor="))
    }

    @Test
    fun privacyControlsRemainAccessibleOutsideWalkingRuntime() {
        val privacy =
            ReportStaticSourceInspector.blockAfter(
                source,
                "privacyControls = LinearLayout(this).apply",
            )
        val runtime =
            ReportStaticSourceInspector.blockAfter(
                source,
                "runtimeControls = LinearLayout(this).apply",
            )
        assertTrue(privacy.contains("addView(privacyConsentStatusText)"))
        assertTrue(privacy.contains("addView(accountDeletionRequestButton)"))
        assertTrue(privacy.contains("addView(backendFieldTokenInput)"))
        assertTrue(privacy.contains("addView(backendAuthApplyButton)"))
        assertTrue(privacy.contains("privacySettingsControls = LinearLayout(this@MainActivity).apply"))
        assertTrue(privacy.contains("accountDeletionControls = LinearLayout(this@MainActivity).apply"))
        assertTrue(privacy.contains("gatewaySessionControls = LinearLayout(this@MainActivity).apply"))
        assertFalse(runtime.contains("addView(accountDeletionRequestButton)"))
        assertTrue(
            appearsInOrder(
                source,
                "addView(runtimeControls)",
                "addView(privacyControls)",
            ),
        )

        val visibility = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun updatePrivacySectionVisibility",
        )
        assertTrue(visibility.contains("GatewaySessionProcessCoordinator.snapshot()"))
        assertTrue(visibility.contains("deletionRecoveryOnly"))
        assertTrue(visibility.contains("firstRunOnboardingSnapshot.isComplete"))
        assertTrue(visibility.contains("accountDeletionStateMachine.processingBlocked()"))
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
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun refreshAccountDeletionStatusAllowed",
            )
        assertTrue(refresh.contains("accountDeletionFallbackMarker.read()"))
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
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun refreshAccountDeletionStatusAllowed",
            )

        assertTrue(refresh.contains("configuredGatewayOriginOrNull()"))
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
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun submitPendingDeviceDeletionEvidenceAllowed",
            )
        assertTrue(
            appearsInOrder(
                submit,
                "journal.deviceEvidenceId == record.evidenceId",
                "journal.deviceEvidenceId == null",
                "accountDeletionStateMachine.recordDeviceEvidence(",
                "accountDeletionStateMachine.snapshotOrNull()",
                "runAccountDeletionWorkerDurableStage(workerAttempt)",
                "persistAccountDeletionJournal(current)",
                "DeviceDeletionEvidence(",
                "submitDeviceDeletionEvidenceCall(",
            ),
        )
        assertTrue(submit.contains("accessSecret = record.accessSecret"))
    }

    @Test
    fun fallbackStartupFencesPresentAndCorruptMarkersBeforeConditionalClear() {
        val read =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun readAccountDeletionFallbackMarkerAtStartup",
            )
        val reconcile =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun reconcileAccountDeletionFallbackMarkerAtStartup",
            )
        assertTrue(
            appearsInOrder(
                read,
                "accountDeletionFallbackMarker.read()",
                "accountDeletionStartupFallbackState = state",
                "state !is AndroidAccountDeletionFallbackMarker.State.Absent",
            ),
        )
        assertTrue(
            appearsInOrder(
                reconcile,
                "accountDeletionStartupFallbackState",
                "applyAccountDeletionRuntimeFence()",
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
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun startAccountDeletionFallbackRecoveryAfterRestore",
            )
        assertTrue(
            appearsInOrder(
                recovery,
                "accountDeletionStateMachine.snapshotOrNull()",
                "restoredJournal?.phase == AccountDeletionPhase.FAIL_CLOSED",
                "applyAccountDeletionRuntimeFence()",
                "return",
                "is AndroidAccountDeletionFallbackMarker.State.Present",
                "durableConfirmationRecoveryRequired()",
                "AndroidAccountDeletionFallbackMarker.Phase.PREPARED",
                "!accountDeletionRemoteResumeBlocked",
                "updateAccountDeletionUi()\n                    return",
                "PREF_ACCOUNT_DELETION_ACTOR_HASH",
                "restoredJournal?.requestId == state.identity.requestId",
                "persistedActorHash == state.identity.actorHash &&\n" +
                    "                        !accountDeletionRemoteResumeBlocked",
                "continueAccountDeletionFromMarker(state.record)",
            ),
        )

        assertTrue(
            appearsInOrder(
                recovery,
                "is AndroidAccountDeletionFallbackMarker.State.Corrupt",
                "is AndroidAccountDeletionFallbackMarker.State.Unavailable",
                "accountDeletionRemoteResumeBlocked = true",
                "applyAccountDeletionRuntimeFence()",
                "accountDeletion=recovery_blocked:fallback_marker_storage",
            ),
        )
        assertFalse(recovery.contains("purgeAccountDeletionLocalDataOnCleanupThread"))
        assertFalse(recovery.contains("refreshAccountDeletionStatus"))
        assertFalse(recovery.contains("accountDeletionClient"))
        assertTrue(source.contains("dispatchAndroidAccountDeletionResume("))
    }

    @Test
    fun confirmedResetUsesDurableCrossStoreReplayAndChecksFieldReset() {
        val reset = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun resetAfterConfirmedAccountDeletion",
        )
        val startup = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun reconcileAccountDeletionResetAtStartup",
        )

        assertTrue(reset.contains("isConfirmedAccountDeletionJournal(journal)"))
        assertTrue(reset.contains("accountDeletionResetCoordinator.beginAndReconcile()"))
        assertTrue(
            appearsInOrder(
                reset,
                "reserveWorkerResetIfCurrent(workerAttempt)",
                "accountDeletionResetCoordinator.beginAndReconcile()",
                "commitWorkerReset(resetReservation)",
                "persistAccountDeletionTerminalStateOrHandle(",
                "reportPrivacyConsentSession.resetForNewEnrollment()",
                "activityLeaseIsCurrent(",
            ),
        )
        assertTrue(reset.contains("rollbackWorkerReset(resetReservation)"))
        assertTrue(reset.contains("account_deletion_reset_commit_conflict"))
        assertFalse(reset.contains("fieldSessionLog.resetForNewEnrollment()"))
        assertTrue(source.contains("FileAccountDeletionResetJournal("))
        assertTrue(source.contains("storeLegacyIntent ="))
        assertTrue(source.contains("resetGateway ="))
        assertTrue(source.contains("resetSensitivePreferences ="))
        assertTrue(source.contains("resetPlainPreferences ="))
        assertTrue(source.contains("resetFieldStorage ="))
        assertTrue(source.contains("fieldSessionLog.purgeAll() && fieldSessionLog.resetForNewEnrollment()"))
        assertTrue(startup.contains("accountDeletionResetCoordinator.reconcilePending()"))
        assertTrue(startup.contains("LegacyResetIntentState.EXACT"))
        assertTrue(startup.contains("accountDeletionResetCoordinator.beginAndReconcile()"))
        assertTrue(source.contains("AccountDeletionResetResult.COMPLETED_ELSEWHERE ->"))
        assertTrue(source.contains("accountDeletionResetJournalStateAtStartup"))
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
        val resume = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun resumeAccountDeletionFromMarker",
        )
        val absent = ReportStaticSourceInspector.blockAfter(
            resume,
            "is AndroidAccountDeletionFallbackMarker.State.Absent",
        )

        assertTrue(
            appearsInOrder(
                absent,
                "isConfirmedAccountDeletionJournal(restoredJournal)",
                "scheduleAccountDeletionActorBindingCleanup(restoredJournal)",
                "else {",
                "fallback_marker_missing",
                "workerAttempt",
            ),
        )
    }

    @Test
    fun resetWaitsForDurableTerminalMarkerAndActorBindingCleanup() {
        val cleanup = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun scheduleAccountDeletionActorBindingCleanup",
        )
        val reset = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun resetAfterConfirmedAccountDeletion",
        )
        val ui = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun updateAccountDeletionUi",
        )

        assertTrue(
            appearsInOrder(
                cleanup,
                "accountDeletionTerminalCleanupCoordinator.begin(journal)",
                "AccountDeletionTerminalMarkerCleanupResult.ALREADY_ABSENT",
                "accountDeletionFallbackMarker.clear(identity)",
                "PREF_ACCOUNT_DELETION_ACTOR_HASH",
                ".commit()",
                "accountDeletionTerminalCleanupCoordinator.complete(",
            ),
        )
        assertTrue(
            appearsInOrder(
                reset,
                "!accountDeletionTerminalCleanupComplete",
                "isConfirmedAccountDeletionJournal(journal)",
                "accountDeletionResetCoordinator.beginAndReconcile()",
            ),
        )
        assertTrue(ui.contains("accountDeletionTerminalCleanupComplete"))
        assertTrue(ui.contains("로컬 삭제 마무리 확인 중"))
        assertTrue(source.contains("accountDeletionTerminalCleanupCoordinator::isResetEnabled"))
    }

    @Test
    fun everyDeletionNetworkEntryAndSessionRestoreUsesTheSharedFailClosedGuard() {
        val refresh = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun refreshAccountDeletionStatus",
        )
        val start = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startAccountDeletionCall",
        )
        val submit = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun submitPendingDeviceDeletionEvidence",
        )
        val sessionRestore = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun onGatewayProcessSessionChanged",
        )

        assertTrue(
            appearsInOrder(
                refresh,
                "accountDeletionStateMachine.snapshotOrNull()",
                "workerAttempt ?: accountDeletionWorkerAttemptOrNull(journal)",
                "accountDeletionWorkerStageAllowed(currentAttempt)",
                "dispatchAccountDeletionNetworkEntry(",
                "keepPrivacyFenceClosed = ::keepAccountDeletionPrivacyFenceClosed",
                "refreshAccountDeletionStatusAllowed(journal, currentAttempt)",
            ),
        )
        assertFalse(refresh.contains("accountDeletionFallbackMarker.read()"))
        assertFalse(refresh.contains("accountDeletionClient."))
        assertTrue(
            appearsInOrder(
                start,
                "accountDeletionStateMachine.snapshotOrNull()",
                "dispatchAccountDeletionNetworkEntry(",
                "keepPrivacyFenceClosed = ::keepAccountDeletionPrivacyFenceClosed",
                "startAccountDeletionCallAllowed(",
                "callFactory()",
            ),
        )
        assertFalse(start.contains("gatewaySessionExecutor.execute"))
        assertFalse(start.contains("accountDeletionClient."))
        assertTrue(
            appearsInOrder(
                submit,
                "dispatchAccountDeletionNetworkEntry(",
                "accountDeletionStateMachine.snapshotOrNull()",
                "keepPrivacyFenceClosed = ::keepAccountDeletionPrivacyFenceClosed",
                "submitPendingDeviceDeletionEvidenceAllowed(record, journal)",
            ),
        )
        assertFalse(submit.contains("recordDeviceEvidence("))
        assertFalse(submit.contains("accountDeletionClient."))
        assertTrue(
            appearsInOrder(
                sessionRestore,
                "accountDeletionStateMachine.snapshotOrNull()",
                "dispatchAccountDeletionNetworkEntry(",
                "keepPrivacyFenceClosed = ::keepAccountDeletionPrivacyFenceClosed",
                "dispatch = ::refreshAccountDeletionStatus",
            ),
        )
    }

    @Test
    fun everyQueuedDeletionWorkerUsesGenerationGuardAtEntryWriteAndCallback() {
        val deletionWorkers = source
            .substringAfter("private fun startAccountDeletionPreparation")
            .substringBefore("private fun scheduleLegacyPendingReportQueuePurge")
        val deletionExecutorEntries =
            Regex("accountDeletionCleanupExecutor\\.execute \\{")
                .findAll(deletionWorkers)
                .count()
        assertTrue(deletionExecutorEntries >= 5)
        assertFalse(deletionWorkers.contains("reportCleanupExecutor.execute"))

        val networkExecutorEntries =
            Regex("gatewaySessionExecutor\\.execute \\{")
                .findAll(deletionWorkers)
                .count()
        val begunNetworkEntries = Regex(
            "gatewaySessionExecutor\\.execute \\{\\s*" +
                "if \\(!accountDeletionStateMachine\\.beginNetworkCall\\(networkLease\\)\\)",
        ).findAll(deletionWorkers).count()
        assertEquals(1, networkExecutorEntries)
        assertEquals(networkExecutorEntries, begunNetworkEntries)

        listOf(
            "private fun startAccountDeletionPreparation",
            "private fun startAccountDeletionCallAllowed",
            "private fun scheduleAccountDeletionEvidenceRebase",
            "private fun scheduleAccountDeletionActorBindingCleanup",
            "private fun scheduleAccountDeletionProgressAfterStatus",
            "private fun scheduleAcceptedAccountDeletionLocalPurge",
        ).forEach { signature ->
            val worker = ReportStaticSourceInspector.functionBlock(source, signature)
            assertTrue(
                "$signature must linearize durable work with the shared state lock",
                Regex("runAccountDeletionWorkerDurableStage\\(\\s*workerAttempt")
                    .containsMatchIn(worker),
            )
        }

        val preparation = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startAccountDeletionPreparation",
        )
        val confirmationPreparation = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startAccountDeletionConfirmationPreparation",
        )
        val markerPreparation = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun prepareAccountDeletionRecoveryMarker",
        )
        assertTrue(
            appearsInOrder(
                confirmationPreparation,
                "accountDeletionCleanupExecutor.execute",
                "runAccountDeletionWorkerDurableStage(workerAttempt)",
                "prepareAccountDeletionRecoveryMarker(",
                "postAccountDeletionRecoveryMarkerPreparation(",
            ),
        )
        assertTrue(markerPreparation.contains("gatewaySessionStore.getOrCreateInstallDeviceId()"))
        assertTrue(preparation.contains("prepareAccountDeletionPendingJournal("))
        val progress = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun scheduleAccountDeletionProgressAfterStatus",
        )
        val rebase = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun scheduleAccountDeletionEvidenceRebase",
        )
        val actorCleanup = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun scheduleAccountDeletionActorBindingCleanup",
        )
        listOf(progress, rebase, actorCleanup).forEach { worker ->
            assertTrue(
                Regex(
                    """runAccountDeletionWorkerDurableStage\(workerAttempt\) \{\s*""" +
                        "(?:markerState|state) = " +
                        """accountDeletionFallbackMarker\.read\(\)""",
                ).containsMatchIn(worker),
            )
        }
        listOf(progress, rebase).forEach { worker ->
            assertTrue(
                worker.contains(
                    "val workerAttempt = accountDeletionWorkerAttemptOrNull(journal) ?: return",
                ),
            )
            assertTrue(
                Regex("accountDeletionWorkerStageAllowed\\(workerAttempt\\)")
                    .findAll(worker)
                    .count() >= 2,
            )
        }
        assertTrue(
            appearsInOrder(
                progress,
                "activityToken != reportCleanupActivityToken",
                "!accountDeletionWorkerStageAllowed(workerAttempt)",
            ),
        )
        assertTrue(
            appearsInOrder(
                progress,
                "val next = current.copy(",
                "runAccountDeletionWorkerDurableStage(workerAttempt)",
                "accountDeletionFallbackMarker.update(next)",
            ),
        )
        assertTrue(progress.contains("continueAccountDeletionFromMarker(next, workerAttempt)"))
        val continuation = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun continueAccountDeletionFromMarker",
        )
        assertTrue(
            Regex("postAccountDeletionProgressFailure\\([\\s\\S]*currentAttempt")
                .containsMatchIn(continuation),
        )
        assertTrue(
            Regex("refreshAccountDeletionStatus\\(currentAttempt\\)")
                .findAll(continuation)
                .count() == 2,
        )
        val guardedRefresh = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun refreshAccountDeletionStatusAllowed",
        )
        assertTrue(guardedRefresh.contains("runAccountDeletionWorkerDurableStage(workerAttempt)"))
        assertTrue(guardedRefresh.contains("runAccountDeletionWorkerTerminalStage("))
        val preparationFailure = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun postAccountDeletionPreparationFailure",
        )
        assertTrue(
            preparationFailure.contains(
                "retainAccountDeletionPreparationRecovery(",
            ),
        )
        assertTrue(
            appearsInOrder(
                rebase,
                "val next = record.copy(",
                "runAccountDeletionWorkerDurableStage(workerAttempt)",
                "accountDeletionFallbackMarker.update(next)",
            ),
        )

        val sharedGuard = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun accountDeletionWorkerStageAllowed",
        )
        assertTrue(sharedGuard.contains("dispatchAccountDeletionWorkerStage("))
        val durableGuard = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun runAccountDeletionWorkerDurableStage",
        )
        assertTrue(durableGuard.contains("runWorkerMonotonicDurableIoIfCurrent("))
        val terminalGuard = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun runAccountDeletionWorkerTerminalStage",
        )
        assertTrue(
            terminalGuard.contains("runWorkerMonotonicTerminalDurableIoIfCurrent("),
        )
        assertTrue(terminalGuard.contains("persistAccountDeletionTerminalState("))
        assertTrue(terminalGuard.contains("handleAccountDeletionTerminalPersistenceFailure("))
        val workerFailure = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun postAccountDeletionProgressFailure",
        )
        assertTrue(
            appearsInOrder(
                workerFailure,
                "accountDeletionWorkerStageAllowed(workerAttempt)",
                "accountDeletionCleanupExecutor.execute",
                "!accountDeletionWorkerStageAllowed(workerAttempt)",
                "runAccountDeletionWorkerTerminalStage(",
                "errorCode = reason",
                "reportCleanupCallbackHandler.post",
                "activityLeaseIsCurrent(",
            ),
        )
    }

    @Test
    fun permanentGatewayConflictFailsClosedAfterStaleRevisionRecoveryCheck() {
        val request = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startAccountDeletionCallAllowed",
        )

        assertTrue(
            appearsInOrder(
                request,
                "handleAccountDeletionEvidenceRevisionConflict(",
                "accountDeletionHttpFailureDisposition(",
                "AccountDeletionHttpFailureDisposition.TerminalConflict",
                "postAccountDeletionProgressFailure(",
                "AccountDeletionHttpFailureDisposition.Retry",
                "markAccountDeletionRetry(",
                "disposition.code",
                "workerAttempt",
            ),
        )
    }

    @Test
    fun startupRestoreRetainsOnlyCurrentMonotonicProcessSuccessor() {
        val restore = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun restorePrivacyControlStateFromPrefs",
        )
        assertTrue(
            appearsInOrder(
                restore,
                "accountDeletionStateMachine.restoreAtStartup(",
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
        val inspection = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startPrivacyStartupInspection",
        )
        assertTrue(
            appearsInOrder(
                inspection,
                "pendingPrivacyStartupReadyAction = onReady",
                "completePrivacyStartupReadyIfForeground()",
            ),
        )
        val ready = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun completePrivacyStartupReadyIfForeground",
        )
        assertTrue(ready.contains("!isActivityForeground"))
        assertTrue(
            appearsInOrder(
                ready,
                "pendingPrivacyStartupReadyAction ?: return",
                "pendingPrivacyStartupReadyAction = null",
                "privacyStartupInspectionComplete = true",
                "onReady()",
            ),
        )
        val close = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun schedulePrivacyStartupResourcesClose",
        )
        assertTrue(close.contains("privacyStartupResourcesCloseScheduled"))
        assertTrue(close.contains("privacyStartupResourcesCloseScheduled = true"))
        assertTrue(close.contains("::closePrivacyStartupResourcesOnWorker"))
    }

    @Test
    fun absentPreparedMarkerAbortsOnlyNonDurablePreparationRecovery() {
        val callback = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun postAccountDeletionRecoveryMarkerPreparation",
        )
        assertTrue(
            appearsInOrder(
                callback,
                "AccountDeletionRecoveryMarkerPreparation.Absent",
                "if (recoveringDurableConfirmation)",
                "retainAccountDeletionPreparationRecovery(",
                "abortPreparationRecovery(",
                "integratedConsentSession.resetForNewEnrollment()",
                "reportPrivacyConsentSession.resetForNewEnrollment()",
            ),
        )
    }

    @Test
    fun nineAccessibleRowsAndLocalPurgeArePresent() {
        assertTrue(source.contains("DeletionInventoryItem.entries.forEach"))
        assertTrue(source.contains("view.contentDescription = details"))
        assertTrue(source.contains("fieldSessionLog.purgeAll()"))
        assertTrue(source.contains("invalidateFrameStateForPause()"))
        assertTrue(source.contains("reportAttemptStore = AndroidReportAttemptStore()"))
    }

    @Test
    fun logoutDoesNotBecomeDeletionOrEnrollmentReset() {
        val logout =
            ReportStaticSourceInspector.functionBlock(
                source,
                "private fun onAccountLogoutClicked",
            )
        assertFalse(logout.contains("onAccountDeletionConfirmClicked"))
        assertFalse(logout.contains("resetInstallationAfterConfirmedAccountDeletion"))
        assertTrue(logout.contains("independent_preferences_retained"))
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
