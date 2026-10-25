package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionPreparationControllerTest {
    @Test fun constructorDoesNotQueryActorsStartWorkersOrClaimConfirmation() = Fixture().use { f ->
        assertTrue(f.queryEvents.isEmpty())
        assertTrue(f.worker.isEmpty())
        assertTrue(f.ui.isEmpty())
        assertFalse(f.operation.requestInFlight)
        assertEquals(AccountDeletionPhase.IDLE, f.base.machine.phase())
        assertEquals(0, f.base.markerFactoryCalls)
    }

    @Test fun confirmationAndCancellationKeepTheirOriginalNondurableStateTransitions() = Fixture().use { f ->
        f.controller.onPrimaryClicked()
        assertEquals(AccountDeletionPhase.CONFIRM_REQUIRED, f.base.machine.phase())
        f.controller.onCancelClicked()
        assertEquals(AccountDeletionPhase.IDLE, f.base.machine.phase())
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.worker.isEmpty())
        assertEquals(0, f.base.markerFactoryCalls)
    }

    @Test fun namedActorMismatchIsRejectedBeforeTheRuntimeFenceAndDurableWorker() = Fixture().use { f ->
        f.controller.onPrimaryClicked()
        f.reporter = "other_actor_0001"
        f.controller.onConfirmClicked()
        assertEquals(listOf("session", "origin", "actor"), f.queryEvents)
        assertFalse(f.events.contains("runtime-fence"))
        assertTrue(f.worker.isEmpty())
        assertFalse(f.operation.requestInFlight)
        assertEquals(0, f.base.markerFactoryCalls)
    }

    @Test fun aFailedPositionScopeFencePreventsMarkerAndNetworkPreparation() = Fixture().use { f ->
        f.controller.onPrimaryClicked()
        f.runtimeFence = false
        f.controller.onConfirmClicked()
        assertTrue(f.events.contains("status:accountDeletion=blocked:position_field_purge"))
        assertTrue(f.worker.isEmpty())
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun rejectedInitialWorkerReleasesOnlyItsTicketWithoutManufacturingDurableAcceptance() = Fixture().use { f ->
        f.controller.onPrimaryClicked()
        f.reject = true
        f.controller.onConfirmClicked()
        assertFalse(f.operation.requestInFlight)
        assertEquals(AccountDeletionPhase.CONFIRM_REQUIRED, f.base.machine.phase())
        assertFalse(f.base.machine.durableConfirmationRecoveryRequired())
        assertFalse(f.base.preferences.contains("account_deletion_journal_v2"))
        assertTrue(f.events.contains("status:accountDeletion=not_accepted:cleanup_executor_rejected"))
    }

    @Test fun durableMarkerAuthorityAndCanonicalJournalPrecedeTheOnlyInitialNetworkEntry() = Fixture().use { f ->
        f.controller.onPrimaryClicked()
        f.controller.onConfirmClicked()
        assertTrue(f.operation.requestInFlight)
        f.runWorker()
        assertTrue(f.base.marker.read() is AndroidAccountDeletionFallbackMarker.State.Present)
        assertTrue(f.calls.isEmpty())
        f.runUi()
        assertTrue(f.base.machine.durableConfirmationRecoveryRequired())
        f.runWorker(); f.runUi()
        assertEquals(AccountDeletionIntentAuthorityState.Confirmed, f.base.store.authorityAtStartup.fileState)
        assertTrue(f.calls.isEmpty())
        f.runWorker()
        val raw = requireNotNull(f.base.preferences.getString("account_deletion_journal_v2", null))
        assertNotNull(AccountDeletionJournalCodec.decodeOrNull(raw))
        assertTrue(f.calls.isEmpty())
        f.runUi()
        assertEquals(1, f.calls.size)
        assertEquals(AccountDeletionCallPurpose.INITIAL, f.calls.single().second)
        assertEquals(AccountDeletionPhase.REQUEST_PENDING, f.base.machine.phase())
        assertEquals(raw, AccountDeletionJournalCodec.encode(requireNotNull(f.base.machine.snapshotOrNull())))
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.worker.isEmpty() && f.ui.isEmpty())
    }

    @Test fun currentReporterChangesNeverReplaceTheAlreadyCapturedSessionInTheInitialFactory() = Fixture().use { f ->
        val captured = f.base.session
        f.controller.onPrimaryClicked(); f.controller.onConfirmClicked()
        f.session = GatewayFieldSession.verified(requireNotNull(f.base.origin), "other_actor_0001", "walksafe_field_session=v2.other", Long.MAX_VALUE)
        repeat(3) { f.runWorker(); f.runUi() }
        val factory = f.calls.single().first
        val sessionFields = factory.javaClass.declaredFields.filter { it.type == GatewayFieldSession::class.java }
        assertEquals(1, sessionFields.size)
        assertSame(captured, sessionFields.single().apply { isAccessible = true }.get(factory))
    }

    @Test fun retiredActivityDropsThePostedStageAndKeepsItsActualPreparedMarkerForRecovery() = Fixture().use { f ->
        f.controller.onPrimaryClicked(); f.controller.onConfirmClicked(); f.runWorker()
        f.destroyed = true
        f.operation.destroy()
        f.runUi()
        assertTrue(f.calls.isEmpty())
        assertTrue(f.worker.isEmpty())
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.base.marker.read() is AndroidAccountDeletionFallbackMarker.State.Present)
    }

    @Test fun stalePreparationGenerationCannotAdvanceAuthorityOrClearTheNewerSlot() = Fixture().use { f ->
        f.controller.onPrimaryClicked(); f.controller.onConfirmClicked(); f.runWorker()
        val newer = f.operation.beginPreparation()
        f.runUi()
        assertTrue(f.operation.isCurrentPreparation(newer))
        assertTrue(f.worker.isEmpty())
        assertTrue(f.calls.isEmpty())
        assertEquals(AccountDeletionIntentAuthorityState.Absent, f.base.store.dualAuthority.startupState().fileState)
    }

    @Test fun failedMarkerDurabilityWithExactPresentReadbackRetainsConfirmationAndNeverStartsNetwork() =
        Fixture(AndroidAccountDeletionFallbackMarker.Operations(syncDirectory = { throw IOException("controlled sync failure") })).use { f ->
            f.prepareKey = true
            f.controller.onPrimaryClicked(); f.controller.onConfirmClicked(); f.runWorker(); f.runUi()
            assertTrue(f.base.machine.durableConfirmationRecoveryRequired())
            assertTrue(f.base.store.fallbackPresentAtStartup)
            assertTrue(f.base.store.startupFallbackState is AndroidAccountDeletionFallbackMarker.State.Present)
            assertFalse(f.operation.requestInFlight)
            assertTrue(f.calls.isEmpty())
            assertFalse(f.base.preferences.contains("account_deletion_journal_v2"))
            assertTrue(f.events.contains("status:accountDeletion=recovery_required:fallback_marker_durability_retry"))
            assertFalse(f.events.contains("key-executed"))
        }

    @Test fun installationKeyPreparationIsCapturedBeforeFenceAndRunsOnlyOnWorkerWithoutRemovingDeletionRights() = Fixture().use { f ->
        f.prepareKey = true; f.keyFailure = true
        f.controller.onPrimaryClicked(); f.controller.onConfirmClicked()
        assertTrue(f.events.indexOf("key-captured") < f.events.indexOf("runtime-fence"))
        assertFalse(f.events.contains("key-executed"))
        repeat(3) { f.runWorker(); f.runUi() }
        assertEquals(AccountDeletionInstallationKeyPreparationState.UNCONFIRMED, f.controller.installationKeyPreparationState)
        assertTrue(f.events.contains("key-executed"))
        assertEquals(1, f.calls.size)
        assertEquals(AccountDeletionPhase.REQUEST_PENDING, f.base.machine.phase())
    }

    @Test fun everyNewPreparationResetsPreviouslyRegisteredAdjunctToUnconfirmed() = Fixture().use { f ->
        f.prepareKey = true
        f.controller.onPrimaryClicked(); f.controller.onConfirmClicked(); f.runWorker()
        assertEquals(AccountDeletionInstallationKeyPreparationState.REGISTERED, f.controller.installationKeyPreparationState)
        val attempt = requireNotNull(f.base.machine.beginPreparationWorkerAttempt(f.base.lease))
        f.controller.startConfirmationPreparation(requireNotNull(f.base.origin), f.base.session, f.base.actor, false,
            attempt, { CancellableNetworkCall.blocking { Unit } })
        assertEquals(AccountDeletionInstallationKeyPreparationState.UNCONFIRMED, f.controller.installationKeyPreparationState)
    }

    @Test fun retiredKeySuccessCannotOverwriteNewUnavailablePreparationState() = staleKeyResultDoesNotPublish(false)
    @Test fun retiredKeyFailureCannotOverwriteNewUnavailablePreparationState() = staleKeyResultDoesNotPublish(true)

    private fun staleKeyResultDoesNotPublish(failure: Boolean) = Fixture().use { f ->
        f.prepareKey = true; f.keyFailure = failure
        f.keyRun = {
            val attempt = requireNotNull(f.base.machine.beginPreparationWorkerAttempt(f.base.lease))
            f.controller.startConfirmationPreparation(requireNotNull(f.base.origin), f.base.session, f.base.actor, false, attempt)
        }
        f.controller.onPrimaryClicked(); f.controller.onConfirmClicked(); f.runWorker()
        assertEquals(AccountDeletionInstallationKeyPreparationState.UNAVAILABLE, f.controller.installationKeyPreparationState)
        assertEquals(0, f.calls.size)
    }

    @Test fun legacyWithoutInstallationConsentKeepsCanonicalDeletionPreparation() = Fixture().use { f ->
        f.controller.onPrimaryClicked(); f.controller.onConfirmClicked()
        repeat(3) { f.runWorker(); f.runUi() }
        assertEquals(AccountDeletionInstallationKeyPreparationState.UNAVAILABLE, f.controller.installationKeyPreparationState)
        assertEquals(1, f.calls.size)
        assertFalse(f.events.contains("key-executed"))
    }

    private class Fixture(operations: AndroidAccountDeletionFallbackMarker.Operations = AndroidAccountDeletionFallbackMarker.Operations()) : AutoCloseable {
        val base = DeletionRecoveryFixture(operations)
        val operation = AccountDeletionOperationAuthority()
        val worker = ArrayDeque<Runnable>()
        val ui = ArrayDeque<() -> Unit>()
        val queryEvents = mutableListOf<String>()
        val events = mutableListOf<String>()
        val calls = mutableListOf<Pair<() -> CancellableNetworkCall<AccountDeletionStatus>, AccountDeletionCallPurpose>>()
        var reject = false
        var destroyed = false
        var runtimeFence = true
        var prepareKey = false
        var keyFailure = false
        var keyRun: (() -> Unit)? = null
        var session: GatewayFieldSession? = base.session
        var reporter: String? = base.actor
        private val actor = AccountDeletionRecoveryActorController(base.store, { base.preferences }, { true }, { base.origin }, { reporter },
            object : AccountDeletionEnrollmentActorReader {
                override fun onboardingInitialized() = false
                override fun onboardingComplete(): Boolean = error("Unexpected enrollment query")
                override fun onboardingActorId(): String? = error("Unexpected enrollment query")
                override fun priorityPolicyInitialized(): Boolean = error("Unexpected enrollment query")
                override fun reporterActorId(): String? = reporter
                override fun priorityActorId(): String? = error("Unexpected enrollment query")
                override fun priorityAccountUnblocked(): Boolean = error("Unexpected enrollment query")
            }, { base.machine }, { base.lease }, base.stages, object : AccountDeletionRecoveryActorEffects {
                override fun clearGatewaySession(expectedSession: GatewayFieldSession, expectedActivityLease: AccountDeletionActivityLease) = error("Unexpected session retirement")
                override fun invalidateSpeechStorage() = error("Unexpected recovery actor effect")
                override fun failClosedIntegratedConsent() = error("Unexpected recovery actor effect")
                override fun postUiRefresh() = error("Unexpected recovery actor effect")
                override fun status(value: String) { events += "status:$value" }
                override fun speak(value: String) { events += "speech:$value" }
            })
        val controller = AccountDeletionPreparationController(operation, base.store, actor, base.stages,
            { base.machine }, { base.lease }, { base.resetController }, { Executor { if (reject) throw RejectedExecutionException(); worker.addLast(it) } },
            object : AccountDeletionCleanupCallbacks {
                override val activityToken = "preparation_activity_0001"
                override val destroyed get() = this@Fixture.destroyed
                override fun post(callback: () -> Unit): Boolean { ui.addLast(callback); return true }
            }, object : AccountDeletionPreparationQueries {
                override fun generalSession(): GatewayFieldSession? { queryEvents += "session"; return session }
                override fun trustedOrigin(): String? { queryEvents += "origin"; return base.origin }
                override fun currentReporterActor(): String? { queryEvents += "actor"; return reporter }
                override fun sensitiveStorageBlocked(): Boolean { queryEvents += "sensitive"; return base.preferences.isBlocked() }
                override fun captureInstallationKeyPreparation(session: GatewayFieldSession): (() -> CancellableNetworkCall<Unit>)? {
                    if (!prepareKey) return null
                    events += "key-captured"
                    return { CancellableNetworkCall.blocking {
                        events += "key-executed"
                        assertTrue(base.marker.read() is AndroidAccountDeletionFallbackMarker.State.Present)
                        keyRun?.invoke()
                        if (keyFailure) throw IOException("controlled registration unknown")
                        Unit
                    } }
                }
            }, object : AccountDeletionPreparationEffects {
                override fun applyRuntimeFence(): Boolean { events += "runtime-fence"; return runtimeFence }
                override fun keepPrivacyFenceClosed() { events += "keep-fence" }
                override fun postUiRefresh() { events += "post-ui" }
                override fun updateUi() { events += "ui" }
                override fun status(value: String) { events += "status:$value" }
                override fun speak(value: String) { events += "speech:$value" }
                override fun invalidateSpeechStorage() { events += "invalidate-speech" }
                override fun resetIntegratedConsentForNewEnrollment() { events += "reset-integrated" }
                override fun resetReportConsentForNewEnrollment() { events += "reset-report" }
                override fun reapplyActorBoundConsent() { events += "reapply-consent" }
                override fun resumePrivacyControlAfterStartup() { events += "resume-control" }
                override fun blockIntegratedConsent() { events += "block-integrated" }
                override fun blockReportConsent() { events += "block-report" }
                override fun failClosedIntegratedConsent() { events += "fail-integrated" }
            }, object : AccountDeletionNetworkEntry {
                override fun startCall(callFactory: () -> CancellableNetworkCall<AccountDeletionStatus>, purpose: AccountDeletionCallPurpose) {
                    val journal = requireNotNull(base.machine.snapshotOrNull())
                    assertEquals(AccountDeletionJournalCodec.encode(journal), base.preferences.getString("account_deletion_journal_v2", null))
                    assertTrue(base.preferences.getBoolean("account_deletion_fail_closed_v1", false))
                    assertEquals(AccountDeletionIntentAuthorityState.Confirmed, base.store.authorityAtStartup.fileState)
                    calls += callFactory to purpose
                }
            }, AndroidPrivacyDeletionClient())
        fun runWorker() { worker.removeFirst().run() }
        fun runUi() { ui.removeFirst().invoke() }
        override fun close() = base.close()
    }
}
