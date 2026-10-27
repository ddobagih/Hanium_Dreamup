package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.io.IOException
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionNetworkControllerTest {
    @Test fun constructorStartsNoSdkCallWorkerOrProviderQuery() = Fixture().use { f ->
        assertEquals(0, f.originQueries)
        assertEquals(0, f.purgeQueries)
        assertTrue(f.cleanup.isEmpty() && f.network.isEmpty())
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.events.isEmpty())
    }

    @Test fun confirmedTerminalJournalRejectsTheFactoryBeforeItCanCreateARequest() = Fixture().use { f ->
        assertTrue(f.base.machine.failClosed(f.base.lease, "controlled_terminal"))
        var factoryCalls = 0
        f.controller.startCall({ factoryCalls++; f.successCall() }, AccountDeletionCallPurpose.STATUS)
        assertEquals(0, factoryCalls)
        assertTrue(f.network.isEmpty())
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.events.contains("keep-fence"))
    }

    @Test fun successKeepsAdmissionUntilCleanupAndCommitsTheExactJournalBeforeProgress() = Fixture().use { f ->
        val call = f.successCall()
        f.controller.startCall({ call }, AccountDeletionCallPurpose.INITIAL)
        assertTrue(f.operation.requestInFlight)
        assertEquals(0L, f.base.machine.snapshotOrNull()!!.serverRevision)
        f.runNetwork()
        assertTrue(f.operation.requestInFlight)
        assertEquals(0L, f.base.machine.snapshotOrNull()!!.serverRevision)
        assertTrue(f.progressed.isEmpty())
        f.runCleanup()
        assertFalse(f.operation.requestInFlight)
        val current = requireNotNull(f.base.machine.snapshotOrNull())
        assertEquals(1L, current.serverRevision)
        assertEquals(listOf(current), f.progressed)
        assertEquals(AccountDeletionJournalCodec.encode(current), f.base.preferences.getString("account_deletion_journal_v2", null))
        assertEquals(AccountDeletionPhase.IN_PROGRESS, current.phase)
    }

    @Test fun cancelledCallReleasesBothOwnersWithoutSchedulingRetryOrProgress() = Fixture().use { f ->
        val call = f.successCall()
        f.controller.startCall({ call }, AccountDeletionCallPurpose.STATUS)
        call.cancel()
        f.runNetwork()
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.cleanup.isEmpty())
        assertTrue(f.progressed.isEmpty())
        assertEquals(AccountDeletionPhase.REQUEST_PENDING, f.base.machine.phase())
        val next = f.successCall()
        f.controller.startCall({ next }, AccountDeletionCallPurpose.STATUS)
        assertFalse(next.isCancelled())
        assertTrue(f.operation.requestInFlight)
    }

    @Test fun transportFailureRetainsOwnershipUntilDispositionThenPersistsOneRetry() = Fixture().use { f ->
        f.controller.startCall({ CancellableNetworkCall(executeBlock = { throw IOException("controlled transport failure") }) }, AccountDeletionCallPurpose.STATUS)
        f.runNetwork()
        assertTrue(f.operation.requestInFlight)
        assertEquals(AccountDeletionPhase.REQUEST_PENDING, f.base.machine.phase())
        f.runCleanup()
        assertFalse(f.operation.requestInFlight)
        assertEquals(AccountDeletionPhase.REQUEST_PENDING, f.base.machine.phase())
        f.runCleanup()
        val current = requireNotNull(f.base.machine.snapshotOrNull())
        assertEquals(AccountDeletionPhase.RETRY_WAIT, current.phase)
        assertEquals("request_failed", current.lastErrorCode)
        assertEquals(AccountDeletionJournalCodec.encode(current), f.base.preferences.getString("account_deletion_journal_v2", null))
        assertTrue(f.progressed.isEmpty())
    }

    @Test fun networkExecutorRejectionCancelsTheRealCallAndPersistsItsExistingRetryPolicy() = Fixture().use { f ->
        f.rejectNetwork = true
        val call = f.successCall()
        f.controller.startCall({ call }, AccountDeletionCallPurpose.STATUS)
        assertTrue(call.isCancelled())
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.network.isEmpty())
        f.runCleanup()
        assertEquals("executor_rejected", f.base.machine.snapshotOrNull()!!.lastErrorCode)
        assertEquals(AccountDeletionPhase.RETRY_WAIT, f.base.machine.phase())
    }

    @Test fun cleanupExecutorRejectionReleasesTheExactNetworkOwnersAndKeepsPrivacyClosed() = Fixture().use { f ->
        f.controller.startCall({ f.successCall() }, AccountDeletionCallPurpose.STATUS)
        f.rejectCleanup = true
        f.runNetwork()
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.cleanup.isEmpty())
        assertTrue(f.events.contains("keep-fence"))
        assertEquals(0L, f.base.machine.snapshotOrNull()!!.serverRevision)
        assertTrue(f.progressed.isEmpty())
    }

    @Test fun retiredActivityCannotPersistOrAdvanceTheResponseAfterSdkCompletion() = Fixture().use { f ->
        val before = f.base.preferences.getString("account_deletion_journal_v2", null)
        f.controller.startCall({ f.successCall() }, AccountDeletionCallPurpose.STATUS)
        f.runNetwork()
        f.base.machine.detachActivity(f.base.lease)
        f.runCleanup()
        assertFalse(f.operation.requestInFlight)
        assertEquals(before, f.base.preferences.getString("account_deletion_journal_v2", null))
        assertEquals(0L, f.base.machine.snapshotOrNull()!!.serverRevision)
        assertTrue(f.progressed.isEmpty())
        assertTrue(f.events.contains("keep-fence"))
    }

    @Test fun failedCanonicalJournalCommitNeverDispatchesAcceptedProgress() = Fixture().use { f ->
        val terminalUi = ArrayDeque<() -> Unit>()
        // The shared core fixture rejects reset UI; this case deliberately reaches the real
        // R2 terminal-failure handler, so supply only its UI dispatcher on the same controller.
        val dispatch: (() -> Unit) -> Unit = { terminalUi.addLast(it) }
        f.base.resetController.javaClass.getDeclaredField("runOnUi")
            .apply { isAccessible = true }.set(f.base.resetController, dispatch)
        f.controller.startCall({ f.successCall() }, AccountDeletionCallPurpose.STATUS)
        f.runNetwork()
        f.base.disk.commitsSucceed = false
        f.runCleanup()
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.progressed.isEmpty())
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, f.base.machine.phase())
        assertEquals("account_deletion_journal_storage", f.base.machine.failureReasonOrNull())
        assertTrue(f.base.events.contains("terminal-failure:account_deletion_journal_storage"))
        assertEquals(1, terminalUi.size)
        terminalUi.removeFirst().invoke()
        assertTrue(f.base.events.contains("terminal-ui:account_deletion_journal_storage"))
    }

    @Test fun evidence409UsesTheOriginalConflictHandlerAfterBothOwnersAreReleased() = Fixture().use { f ->
        f.handleConflict = true
        val body = "controlled evidence revision conflict"
        f.controller.startCall({ CancellableNetworkCall(executeBlock = { throw AccountDeletionHttpException(409, body) }) }, AccountDeletionCallPurpose.EVIDENCE)
        f.runNetwork(); f.runCleanup()
        assertEquals(listOf(body), f.conflicts)
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.cleanup.isEmpty())
        assertTrue(f.failures.isEmpty())
        assertEquals(AccountDeletionPhase.REQUEST_PENDING, f.base.machine.phase())
    }

    @Test fun capability404RetainsTheActualTypedRev0ActorWithoutManufacturingStatusOrGeneralSession() = Fixture().use { f ->
        f.controller.startCall({ CancellableNetworkCall(executeBlock = { throw AccountDeletionHttpException(404, "{}") }) }, AccountDeletionCallPurpose.CAPABILITY_REPLAY)
        f.runNetwork(); f.runCleanup()
        assertFalse(f.operation.requestInFlight)
        assertNotNull(f.actor.rev0RecoveryBindingOrNull())
        assertTrue(f.actor.recoveryLoginRequired())
        assertTrue(f.progressed.isEmpty())
        assertTrue(f.cleanup.isEmpty() && f.network.isEmpty())
        assertEquals(AccountDeletionPhase.REQUEST_PENDING, f.base.machine.phase())
    }

    @Test fun recovery401And403KeepTheSameTypedReauthenticationInsteadOfPersistingGenericRetry() {
        for (code in listOf(401, 403)) Fixture().use { f ->
            val attempt = requireNotNull(f.base.stages.begin(f.journal))
            assertTrue(f.actor.activateRev0Reauthentication(attempt))
            val binding = requireNotNull(f.actor.rev0RecoveryBindingOrNull())
            f.controller.startCall({ CancellableNetworkCall(executeBlock = { throw AccountDeletionHttpException(code, "{}") }) },
                AccountDeletionCallPurpose.RECOVERY_ACCEPT_OR_REPLAY)
            f.runNetwork(); f.runCleanup()
            assertEquals(binding, f.actor.rev0RecoveryBindingOrNull())
            assertFalse(f.operation.requestInFlight)
            assertTrue(f.cleanup.isEmpty() && f.network.isEmpty())
            assertTrue(f.progressed.isEmpty() && f.failures.isEmpty())
            assertEquals(AccountDeletionPhase.REQUEST_PENDING, f.base.machine.phase())
            assertNull(f.base.machine.snapshotOrNull()!!.lastErrorCode)
        }
    }

    @Test fun refreshDoesNotReadTheMarkerOrCreateARequestWhileThatExactLocalPurgeIsInFlight() = Fixture().use { f ->
        f.purgeRequestId = f.journal.requestId
        val readsBefore = f.base.markerFactoryCalls
        f.controller.refreshStatus()
        f.runCleanup()
        assertEquals(readsBefore, f.base.markerFactoryCalls)
        assertEquals(0, f.originQueries)
        assertEquals(1, f.purgeQueries)
        assertTrue(f.network.isEmpty())
        assertFalse(f.operation.requestInFlight)
    }

    @Test fun initialExactReauthentication401PublishesPasswordBindingOnlyAfterCanonicalRetryCommit() = Fixture().use { f ->
        f.controller.startCall({ reauthenticationFailure() }, AccountDeletionCallPurpose.INITIAL)
        f.runNetwork()
        f.runCleanup()
        assertNull(f.actor.passwordRecoveryBindingOrNull())
        assertEquals(AccountDeletionPhase.REQUEST_PENDING, f.base.machine.phase())
        f.runCleanup()
        val current = requireNotNull(f.base.machine.snapshotOrNull())
        val binding = requireNotNull(f.actor.passwordRecoveryBindingOrNull())
        assertEquals(AccountDeletionPhase.RETRY_WAIT, current.phase)
        assertEquals(ACCOUNT_DELETION_REAUTHENTICATION_RETRY_CODE, current.lastErrorCode)
        assertEquals(AccountDeletionJournalCodec.encode(current), f.base.preferences.getString("account_deletion_journal_v2", null))
        assertEquals(f.journal.requestId, current.requestId)
        assertEquals(f.record, binding.markerRecord)
        assertEquals(current, binding.journal)
        assertTrue(f.base.preferences.getBoolean("account_deletion_fail_closed_v1", false))
        assertTrue(f.progressed.isEmpty() && f.failures.isEmpty())
        assertTrue(f.cleanup.isEmpty() && f.network.isEmpty())
    }

    @Test fun otherInitial401BodiesAndStatusesDoNotActivatePasswordRecovery() {
        listOf(
            401 to "{}",
            401 to "{\"code\":\"account_deletion_reauthentication_required\",\"reauthentication_required\":false}",
            401 to "{\"code\":\"other\",\"reauthentication_required\":true}",
            401 to "not-json",
            403 to REAUTHENTICATION_BODY,
        ).forEach { (code, body) ->
            Fixture().use { f ->
                f.controller.startCall({ reauthenticationFailure(code, body) }, AccountDeletionCallPurpose.INITIAL)
                f.runNetwork()
                f.runCleanup()
                f.runCleanup()
                assertNull(f.actor.rev0RecoveryBindingOrNull())
                assertNull(f.actor.passwordRecoveryBindingOrNull())
                assertEquals("http_$code", f.base.machine.snapshotOrNull()?.lastErrorCode)
            }
        }
    }

    @Test fun exactReauthentication401ForOtherCallPurposesDoesNotActivatePasswordRecovery() {
        listOf(AccountDeletionCallPurpose.CAPABILITY_REPLAY, AccountDeletionCallPurpose.STATUS,
            AccountDeletionCallPurpose.EVIDENCE, AccountDeletionCallPurpose.RECOVERY_ACCEPT_OR_REPLAY).forEach { purpose ->
            Fixture().use { f ->
                f.controller.startCall({ reauthenticationFailure() }, purpose)
                f.runNetwork()
                f.runCleanup()
                f.runCleanup()
                assertEquals("http_401", f.base.machine.snapshotOrNull()?.lastErrorCode)
                assertNull(f.actor.rev0RecoveryBindingOrNull())
                assertNull(f.actor.passwordRecoveryBindingOrNull())
            }
        }
    }

    @Test fun retiredInitial401ResponseCannotScheduleRetryOrActivatePasswordRecovery() = Fixture().use { f ->
        val before = f.base.preferences.getString("account_deletion_journal_v2", null)
        val call = reauthenticationFailure()
        f.controller.startCall({ call }, AccountDeletionCallPurpose.INITIAL)
        f.runNetwork()
        assertTrue(f.base.machine.detachActivity(f.base.lease))
        assertTrue(call.isCancelled())
        f.runCleanup()
        assertNull(f.actor.passwordRecoveryBindingOrNull())
        assertEquals(before, f.base.preferences.getString("account_deletion_journal_v2", null))
        assertFalse(f.operation.requestInFlight)
        assertTrue(f.cleanup.isEmpty() && f.network.isEmpty())
        // Detach invalidates the network lease; the existing durable fence must remain engaged.
        assertEquals(AccountDeletionPhase.REQUEST_PENDING, f.base.machine.phase())
        assertTrue(f.base.machine.processingBlocked())
        assertTrue(f.base.preferences.getBoolean("account_deletion_fail_closed_v1", false))
        assertEquals(f.record, (f.base.marker.read() as AndroidAccountDeletionFallbackMarker.State.Present).record)
    }

    @Test fun retiredEpochBeforeInitial401RetryCommitCannotActivatePasswordRecovery() = Fixture().use { f ->
        val before = f.base.preferences.getString("account_deletion_journal_v2", null)
        f.controller.startCall({ reauthenticationFailure() }, AccountDeletionCallPurpose.INITIAL)
        f.runNetwork()
        f.runCleanup()
        f.base.machine.detachActivity(f.base.lease)
        f.base.machine.attachActivity()
        f.runCleanup()
        assertNull(f.actor.passwordRecoveryBindingOrNull())
        assertEquals(before, f.base.preferences.getString("account_deletion_journal_v2", null))
        assertTrue(f.events.contains("keep-fence"))
    }

    @Test fun failedInitial401RetryCommitNeverActivatesPasswordRecovery() = Fixture().use { f ->
        f.controller.startCall({ reauthenticationFailure() }, AccountDeletionCallPurpose.INITIAL)
        f.runNetwork()
        f.runCleanup()
        f.base.disk.commitsSucceed = false
        f.runCleanup()
        assertNull(f.actor.rev0RecoveryBindingOrNull())
        assertNull(f.actor.passwordRecoveryBindingOrNull())
        assertEquals(listOf("retry_journal_storage"), f.failures)
        assertTrue(f.progressed.isEmpty())
    }

    private class Fixture : AutoCloseable {
        val base = DeletionRecoveryFixture()
        val operation = AccountDeletionOperationAuthority()
        val cleanup = ArrayDeque<Runnable>()
        val network = ArrayDeque<Runnable>()
        val events = mutableListOf<String>()
        val progressed = mutableListOf<AccountDeletionJournal>()
        val conflicts = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var rejectCleanup = false
        var rejectNetwork = false
        var handleConflict = false
        var purgeRequestId: String? = null
        var originQueries = 0
        var purgeQueries = 0
        val record = base.prepared()
        val journal: AccountDeletionJournal
        init {
            base.store.observeFallbackState(AndroidAccountDeletionFallbackMarker.State.Present(record))
            assertTrue(base.store.prepareIntentAuthority().confirmed)
            journal = (base.store.preparePendingJournal(requireNotNull(base.origin), base.session, record) as AccountDeletionPendingJournalPreparation.Ready).journal
            assertTrue(base.machine.restore(journal, base.lease))
        }
        val actor = AccountDeletionRecoveryActorController(base.store, { base.preferences }, { true }, { base.origin }, { base.actor },
            object : AccountDeletionEnrollmentActorReader {
                override fun onboardingInitialized() = false
                override fun onboardingComplete(): Boolean = error("Unexpected onboarding read")
                override fun onboardingActorId(): String? = error("Unexpected onboarding read")
                override fun priorityPolicyInitialized(): Boolean = error("Unexpected priority read")
                override fun reporterActorId() = base.actor
                override fun priorityActorId(): String? = error("Unexpected priority read")
                override fun priorityAccountUnblocked(): Boolean = error("Unexpected priority read")
            }, { base.machine }, { base.lease }, base.stages, object : AccountDeletionRecoveryActorEffects {
                override fun clearGatewaySession(expectedSession: GatewayFieldSession, expectedActivityLease: AccountDeletionActivityLease) { events += "clear-session" }
                override fun invalidateSpeechStorage() { events += "invalidate-speech" }
                override fun failClosedIntegratedConsent() { events += "fail-consent" }
                override fun postUiRefresh() { events += "post-ui" }
                override fun status(value: String) { events += "status:$value" }
                override fun speak(value: String) { events += "speech:$value" }
            })
        val controller = AccountDeletionNetworkController(operation, base.store, actor, base.stages,
            { base.machine }, { Executor { if (rejectCleanup) throw RejectedExecutionException(); cleanup.addLast(it) } },
            { Executor { if (rejectNetwork) throw RejectedExecutionException(); network.addLast(it) } },
            object : AccountDeletionNetworkQueries {
                override fun trustedOrigin(): String? { originQueries++; return base.origin }
                override fun localPurgeRequestId(): String? { purgeQueries++; return purgeRequestId }
            }, object : AccountDeletionNetworkEffects {
                override fun keepPrivacyFenceClosed() { events += "keep-fence" }
                override fun invalidateSpeechStorage() { events += "invalidate-speech" }
                override fun failClosedIntegratedConsent() { events += "fail-consent" }
                override fun postUiRefresh() { events += "post-ui" }
            }, object : AccountDeletionNetworkProgress {
                override fun scheduleAfterStatus(journal: AccountDeletionJournal) {
                    assertFalse(operation.requestInFlight)
                    assertEquals(AccountDeletionJournalCodec.encode(journal), base.preferences.getString("account_deletion_journal_v2", null))
                    progressed += journal
                }
                override fun handleEvidenceRevisionConflict(responseBody: String, workerAttempt: AccountDeletionWorkerAttempt): Boolean {
                    assertFalse(operation.requestInFlight)
                    assertTrue(base.stages.allowed(workerAttempt))
                    conflicts += responseBody
                    return handleConflict
                }
                override fun postFailure(errorCode: String, workerAttempt: AccountDeletionWorkerAttempt) { failures += errorCode }
            }, AndroidPrivacyDeletionClient())
        fun successCall() = CancellableNetworkCall(executeBlock = { status() })
        private fun status(): AccountDeletionStatus {
            val accepted = Instant.parse(journal.requestedAt)
            return AccountDeletionStatus(ACCOUNT_DELETION_STATUS_SCHEMA_VERSION, journal.installationId, journal.requestId,
                1L, journal.requestedAt, accepted.plusSeconds(1).toString(), DeletionInventoryItem.entries.associateWith { item ->
                    DeletionItemStatus(item, DeletionItemState.IN_PROGRESS, accepted.plusMillis(item.maximumSlaMs).toString(),
                        updatedAt = accepted.plusSeconds(1).toString())
                }, null, tombstoneId = "network_tombstone_0001", requestReceiptSha256 = "a".repeat(64))
        }
        fun runCleanup() = cleanup.removeFirst().run()
        fun runNetwork() = network.removeFirst().run()
        override fun close() = base.close()
    }

    private companion object {
        const val REAUTHENTICATION_BODY =
            "{\"code\":\"account_deletion_reauthentication_required\",\"reauthentication_required\":true}"

        fun reauthenticationFailure(code: Int = 401, body: String = REAUTHENTICATION_BODY) =
            CancellableNetworkCall<AccountDeletionStatus>(executeBlock = { throw AccountDeletionHttpException(code, body) })
    }
}
