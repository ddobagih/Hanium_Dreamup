package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionRecoveryActorControllerTest {
    @Test fun constructionDoesNotReadEnrollmentMarkerPreferencesOrSession() = Fixture().use { a ->
        assertEquals(0, a.f.markerFactoryCalls)
        assertEquals(0, a.f.installationQueries)
        assertTrue(a.readerQueries.isEmpty())
        assertTrue(a.f.events.isEmpty())
    }

    @Test fun rev0BindingIsHiddenUntilTheSameTypedWorkerRequiresReauthentication() = Fixture().use { a ->
        val journal = a.restoreRev0()
        assertNotNull(a.actor.rev0RecoveryBindingOrNull(requireReauthentication = false))
        assertNull(a.actor.rev0RecoveryBindingOrNull())
        val attempt = requireNotNull(a.f.stages.begin(journal))
        assertTrue(a.actor.activateRev0Reauthentication(attempt))
        assertEquals(a.f.actor, a.actor.rev0RecoveryBindingOrNull()?.actorId)
        assertTrue(a.actor.recoveryLoginRequired())
        assertEquals(a.f.actor, a.actor.recoveryActorIdOrNull())
        a.f.origin = "https://different.example.test"
        assertNull(a.actor.rev0RecoveryBindingOrNull())
        a.f.origin = journal.gatewayOrigin
        a.f.store.setRemoteResumeBlocked(true)
        assertNull(a.actor.rev0RecoveryBindingOrNull())
    }

    @Test fun aForeignTypedAttemptCannotActivateTheCurrentMarkerRecovery() = Fixture().use { a ->
        a.restoreRev0()
        Fixture().use { other ->
            val otherJournal = other.restoreRev0()
            assertFalse(a.actor.activateRev0Reauthentication(requireNotNull(other.f.stages.begin(otherJournal))))
            assertNull(a.actor.rev0RecoveryBindingOrNull())
        }
    }

    @Test fun unavailableStorageClosesRecoveryBeforeSensitiveReads() = Fixture().use { a ->
        val journal = a.restoreRev0()
        assertTrue(a.actor.activateRev0Reauthentication(requireNotNull(a.f.stages.begin(journal))))
        a.storageReady = false
        assertNull(a.actor.rev0RecoveryBindingOrNull())
        assertNull(a.actor.legacyRev0MarkerBindingOrNull())
    }

    @Test fun durablePreparedActorReturnsBeforeAnyEnrollmentFallbackQuery() = Fixture().use { a ->
        a.f.prepared()
        assertTrue(a.f.machine.enterDurableConfirmationRecovery(a.f.lease))
        assertEquals(a.f.actor, a.actor.recoveryActorIdOrNull())
        assertTrue(a.readerQueries.isEmpty())
    }

    @Test fun legacyPreparedActorFallbackSamplesEachEnrollmentFactAtItsOriginalPoint() = Fixture().use { a ->
        a.createLegacyMarker()
        assertTrue(a.f.machine.enterDurableConfirmationRecovery(a.f.lease))
        a.enrollmentReady = true
        assertEquals(a.f.actor, a.actor.recoveryActorIdOrNull())
        assertEquals(listOf("onboarding-initialized", "onboarding-complete", "priority-initialized",
            "onboarding-actor", "reporter-actor", "priority-actor", "priority-unblocked"), a.readerQueries)
        a.readerQueries.clear()
        a.priorityActor = "actor_changed_0001"
        assertNull(a.actor.recoveryActorIdOrNull())
        assertFalse(a.readerQueries.contains("priority-unblocked"))
    }

    @Test fun onlyTheLiveExactDeletionRecoverySessionIsUsableAndClearedByRecoveryFailure() = Fixture().use { a ->
        val journal = a.restoreRev0()
        assertTrue(a.actor.activateRev0Reauthentication(requireNotNull(a.f.stages.begin(journal))))
        val recovery = a.publishRecoverySession(a.f.actor)
        assertSame(recovery, a.actor.recoveryGatewaySessionOrNull())
        val copy = recovery(a.f.actor, journal.gatewayOrigin)
        assertNull(a.actor.recoveryGatewaySessionOrNull(copy))
        assertTrue(a.actor.retainRev0ReauthenticationAfterSessionFailure(requireNotNull(a.f.stages.begin(journal))))
        a.actor.clearRev0RecoverySessionIfCurrent()
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertNotNull(a.actor.rev0RecoveryBindingOrNull())
    }

    @Test fun recoveryFailureNeverClearsAGeneralOrDifferentActorSession() = Fixture().use { a ->
        val journal = a.restoreRev0()
        assertTrue(a.actor.activateRev0Reauthentication(requireNotNull(a.f.stages.begin(journal))))
        a.publishGeneralSession()
        val general = GatewaySessionProcessCoordinator.snapshot()
        a.actor.clearRev0RecoverySessionIfCurrent()
        assertEquals(general.generation, GatewaySessionProcessCoordinator.snapshot().generation)
        assertSame(general.session, GatewaySessionProcessCoordinator.snapshot().session)
        val other = a.publishRecoverySession("actor_other_0001")
        a.actor.clearRev0RecoverySessionIfCurrent()
        assertSame(other, GatewaySessionProcessCoordinator.snapshot().session)
    }

    @Test fun acceptedTypedJournalRetiresOnlyTheExactRecoveryScopeAndClearsItsRequestFlag() = Fixture().use { a ->
        val journal = a.restoreRev0()
        assertTrue(a.actor.activateRev0Reauthentication(requireNotNull(a.f.stages.begin(journal))))
        a.publishRecoverySession(a.f.actor)
        val accepted = journal.copy(phase = AccountDeletionPhase.IN_PROGRESS, serverRevision = 1L,
            acceptedAt = "2026-10-06T00:00:00Z", accountGeneration = 1L, tombstoneId = "tombstone_0001", requestReceiptSha256 = "a".repeat(64))
        a.actor.completeRev0ReauthenticationAfterAcceptance(accepted)
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertNull(a.actor.rev0RecoveryBindingOrNull())
    }

    @Test fun legacyBindingIsDurablyRewrittenBeforeTheExactGeneralSessionIsRetired() = Fixture().use { a ->
        val journal = a.restoreRev0(legacy = true)
        a.publishGeneralSession()
        assertTrue(a.actor.bindLegacyRev0RecoveryActor(requireNotNull(a.f.stages.begin(journal))))
        val observed = a.f.marker.read() as AndroidAccountDeletionFallbackMarker.State.Present
        assertEquals(a.f.actor, observed.record.recoveryActorId)
        assertEquals(observed, a.f.store.startupFallbackState)
        assertNotNull(a.actor.rev0RecoveryBindingOrNull())
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertTrue(a.f.events.contains("clear-exact-session"))
    }

    @Test fun unavailableLegacyActorPersistsTheOriginalTerminalReasonBeforeUiFenceEffects() = Fixture().use { a ->
        val journal = a.restoreRev0(legacy = true)
        a.currentReporter = null
        assertTrue(a.actor.handleLegacyRev0NotFound(requireNotNull(a.f.stages.begin(journal))))
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, a.f.machine.phase())
        assertEquals("account_deletion_legacy_reauthentication_identity_unavailable", a.f.machine.failureReasonOrNull())
        assertEquals(a.f.machine.failureReasonOrNull(), a.f.preferences.getString("account_deletion_fail_closed_reason_v1", null))
        assertTrue(a.f.events.containsAll(listOf("invalidate-speech", "consent-fail-closed", "post-ui")))
    }

    @Test fun passwordBindingRequiresActivatedRev0AndDoesNotChangeItsAuthorities() = Fixture().use { a ->
        val journal = a.restoreRev0()
        assertNull(a.actor.passwordRecoveryBindingOrNull())
        assertTrue(a.actor.activateRev0Reauthentication(requireNotNull(a.f.stages.begin(journal))))
        val commits = a.f.disk.commitCount
        val process = GatewaySessionProcessCoordinator.snapshot()
        val binding = requireNotNull(a.actor.passwordRecoveryBindingOrNull())
        assertEquals(a.f.actor, binding.actorId)
        assertEquals(journal, binding.journal)
        assertEquals((a.f.store.startupFallbackState as AndroidAccountDeletionFallbackMarker.State.Present).record,
            binding.markerRecord)
        assertEquals(journal.gatewayOrigin, binding.gatewayOrigin)
        assertEquals(binding, a.actor.passwordRecoveryBindingOrNull())
        assertEquals(commits, a.f.disk.commitCount)
        assertEquals(process.generation, GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun passwordBindingSupportsConfirmedPreJournalRecoveryWithoutEnrollmentFallback() = Fixture().use { a ->
        val marker = a.f.prepared()
        assertTrue(a.f.store.prepareIntentAuthority().confirmed)
        assertTrue(a.f.machine.enterDurableConfirmationRecovery(a.f.lease))
        val binding = requireNotNull(a.actor.passwordRecoveryBindingOrNull())
        assertEquals(a.f.actor, binding.actorId)
        assertEquals(marker, binding.markerRecord)
        assertNull(binding.journal)
        assertTrue(a.readerQueries.isEmpty())
    }

    @Test fun passwordBindingKeepsKnownAbsentLegacyPreJournalRecoveryAndItsActorChecks() = Fixture().use { a ->
        val marker = a.createLegacyMarker()
        a.f.store.observeAuthority(a.f.store.dualAuthority.startupState())
        assertTrue(a.f.machine.enterDurableConfirmationRecovery(a.f.lease))
        a.enrollmentReady = true
        val binding = requireNotNull(a.actor.passwordRecoveryBindingOrNull())
        assertEquals(a.f.actor, binding.actorId)
        assertEquals(marker, binding.markerRecord)
        assertNull(binding.journal)
        a.priorityActor = "actor_changed_0001"
        assertNull(a.actor.passwordRecoveryBindingOrNull())
    }

    @Test fun publishedRecoveryKeepsTheSameLegacyPasswordBindingAfterEnrollmentIsRetired() = Fixture().use { a ->
        val captured = a.prepareLegacyPasswordTarget()
        val session = a.publishRecoverySession(a.f.actor)
        a.retireEnrollment()
        assertEquals(captured, a.actor.passwordRecoveryBindingOrNull())
        assertEquals(a.f.actor, a.actor.recoveryActorIdOrNull())
        assertTrue(a.actor.isRecoveryLoginTarget(captured.actorId, captured.gatewayOrigin))
        assertSame(session, a.actor.recoveryGatewaySessionOrNull())
    }

    @Test fun invalidatedRecoveryKeepsOnlyTheLegacyPasswordTargetAndCannotSupplyCredentials() = Fixture().use { a ->
        val captured = a.prepareLegacyPasswordTarget()
        val session = a.publishRecoverySession(a.f.actor, accountGeneration = 1L)
        a.retireEnrollment()
        session.invalidate()
        assertEquals(captured, a.actor.passwordRecoveryBindingOrNull())
        assertTrue(a.actor.isRecoveryLoginTarget(captured.actorId, captured.gatewayOrigin))
        assertFalse(session.isUsableFor(captured.actorId))
        assertNull(a.actor.recoveryGatewaySessionOrNull())
    }

    @Test fun generalForeignActorForeignOriginAndBlockedSessionsCannotSupplyLegacyRecoveryIdentity() {
        listOf("general", "actor", "origin", "blocked").forEach { rejected ->
            Fixture().use { a ->
                a.prepareLegacyPasswordTarget()
                when (rejected) {
                    "general" -> a.publishGeneralSession()
                    "actor" -> a.publishRecoverySession("actor_other_0001")
                    "origin" -> a.publishRecoverySession(a.f.actor, origin = "https://different.example.test")
                    "blocked" -> {
                        a.publishRecoverySession(a.f.actor)
                        assertTrue(GatewaySessionProcessCoordinator.markStorageBlocked(
                            GatewaySessionProcessCoordinator.snapshot().generation))
                    }
                }
                a.retireEnrollment()
                assertNull(a.actor.recoveryActorIdOrNull())
                assertNull(a.actor.passwordRecoveryBindingOrNull())
                assertNull(a.actor.recoveryGatewaySessionOrNull())
            }
        }
    }

    @Test fun legacyRecoveryIdentityRejectsKnownGenerationMismatchAndInvalidKnownSessionGeneration() {
        listOf(2L to 1L, 2L to null, null to 0L, null to -1L, 2L to 2L, null to 1L).forEach { (markerGeneration, sessionGeneration) ->
            Fixture().use { a ->
                val captured = a.prepareLegacyPasswordTarget(markerGeneration)
                a.publishRecoverySession(a.f.actor, accountGeneration = sessionGeneration)
                a.retireEnrollment()
                if (sessionGeneration != null && sessionGeneration > 0L &&
                    (markerGeneration == null || markerGeneration == sessionGeneration)) {
                    assertEquals(captured, a.actor.passwordRecoveryBindingOrNull())
                    assertNotNull(a.actor.recoveryGatewaySessionOrNull())
                } else {
                    assertNull(a.actor.recoveryActorIdOrNull())
                    assertNull(a.actor.passwordRecoveryBindingOrNull())
                    assertNull(a.actor.recoveryGatewaySessionOrNull())
                }
            }
        }
    }

    @Test fun passwordBindingRejectsUnknownAuthorityBlockedOriginAndUnavailableStorage() = Fixture().use { a ->
        a.f.prepared()
        assertTrue(a.f.machine.enterDurableConfirmationRecovery(a.f.lease))
        assertNull(a.actor.passwordRecoveryBindingOrNull())
        val known = a.f.store.dualAuthority.startupState()
        a.f.store.observeAuthority(known)
        assertNotNull(a.actor.passwordRecoveryBindingOrNull())
        a.f.store.setRemoteResumeBlocked(true)
        assertNull(a.actor.passwordRecoveryBindingOrNull())
        a.f.store.setRemoteResumeBlocked(false)
        a.f.origin = "https://different.example.test"
        assertNull(a.actor.passwordRecoveryBindingOrNull())
        a.f.origin = "https://gateway.example.test"
        a.f.store.observeAuthority(known.copy(fileState = AccountDeletionIntentAuthorityState.Corrupt))
        assertNull(a.actor.passwordRecoveryBindingOrNull())
        a.f.store.observeAuthority(known)
        a.storageReady = false
        assertNull(a.actor.passwordRecoveryBindingOrNull())
    }

    @Test fun passwordBindingDetectsSecretAndJournalChangesDuringAuthentication() = Fixture().use { a ->
        val journal = a.restoreRev0()
        val attempt = requireNotNull(a.f.stages.begin(journal))
        assertTrue(a.actor.activateRev0Reauthentication(attempt))
        val captured = requireNotNull(a.actor.passwordRecoveryBindingOrNull())
        val changedRecord = captured.markerRecord.copy(accessSecret = "B".repeat(43))
        a.f.store.observeFallbackState(AndroidAccountDeletionFallbackMarker.State.Present(changedRecord))
        val afterSecret = requireNotNull(a.actor.passwordRecoveryBindingOrNull())
        assertNotEquals(captured, afterSecret)
        assertEquals(changedRecord, afterSecret.markerRecord)
        assertTrue(a.f.machine.markRetry(attempt, "reauthentication_required"))
        val afterRetry = requireNotNull(a.actor.passwordRecoveryBindingOrNull())
        assertNotEquals(afterSecret, afterRetry)
        assertEquals(AccountDeletionPhase.RETRY_WAIT, afterRetry.journal?.phase)
    }

    @Test fun passwordBindingRejectsChangedRequestInstallationRevisionPhaseActorAndAuthority() = Fixture().use { a ->
        val journal = a.restoreRev0()
        assertTrue(a.actor.activateRev0Reauthentication(requireNotNull(a.f.stages.begin(journal))))
        val captured = requireNotNull(a.actor.passwordRecoveryBindingOrNull())
        val marker = captured.markerRecord
        val changed = listOf(
            marker.copy(identity = marker.identity.copy(requestId = "account_delete_other_0001")),
            marker.copy(installationId = "installation_other_0001"),
            marker.copy(clientRevision = marker.clientRevision + 1L),
            marker.copy(phase = AndroidAccountDeletionFallbackMarker.Phase.ACCEPTED),
            marker.copy(recoveryActorId = "actor_other_0001"),
        )
        changed.forEach {
            a.f.store.observeFallbackState(AndroidAccountDeletionFallbackMarker.State.Present(it))
            assertNull(a.actor.passwordRecoveryBindingOrNull())
        }
        a.f.store.observeFallbackState(AndroidAccountDeletionFallbackMarker.State.Present(marker))
        val authority = a.f.store.authorityAtStartup
        a.f.store.observeAuthority(authority.copy(aggregateState = AccountDeletionAggregateAuthorityState.UNAVAILABLE))
        assertNull(a.actor.passwordRecoveryBindingOrNull())
        a.f.store.observeAuthority(authority)
        assertEquals(captured, a.actor.passwordRecoveryBindingOrNull())
    }

    @Test fun passwordBindingRejectsAcceptedAndTerminalJournals() {
        listOf(AccountDeletionPhase.IN_PROGRESS, AccountDeletionPhase.FAIL_CLOSED).forEach { phase ->
            Fixture().use { a ->
                a.restoreRev0(transform = { journal ->
                    if (phase == AccountDeletionPhase.FAIL_CLOSED) journal.copy(phase = phase)
                    else journal.copy(phase = phase, serverRevision = 1L,
                        acceptedAt = "2026-10-06T00:00:00Z", accountGeneration = 1L,
                        tombstoneId = "tombstone_0001", requestReceiptSha256 = "a".repeat(64))
                })
                assertNull(a.actor.passwordRecoveryBindingOrNull())
            }
        }
    }

    @Test fun passwordBindingStringNeverIncludesDeletionOrActorIdentifiers() = Fixture().use { a ->
        val journal = a.restoreRev0()
        assertTrue(a.actor.activateRev0Reauthentication(requireNotNull(a.f.stages.begin(journal))))
        val binding = requireNotNull(a.actor.passwordRecoveryBindingOrNull())
        val text = binding.toString()
        listOf(binding.actorId, binding.gatewayOrigin, binding.markerRecord.identity.requestId,
            binding.markerRecord.identity.actorHash, binding.markerRecord.installationId,
            binding.markerRecord.accessSecret, journal.requestedAt).forEach { assertFalse(text.contains(it)) }
    }

    private class Fixture : AutoCloseable {
        val f = DeletionRecoveryFixture()
        var storageReady = true
        var currentReporter: String? = f.actor
        var enrollmentReady = false
        var priorityActor = f.actor
        val readerQueries = mutableListOf<String>()
        val actor = AccountDeletionRecoveryActorController(f.store, { f.preferences }, { storageReady }, { f.origin },
            { currentReporter }, object : AccountDeletionEnrollmentActorReader {
                override fun onboardingInitialized(): Boolean { readerQueries += "onboarding-initialized"; return enrollmentReady }
                override fun onboardingComplete(): Boolean { readerQueries += "onboarding-complete"; return enrollmentReady }
                override fun onboardingActorId(): String? { readerQueries += "onboarding-actor"; return f.actor }
                override fun priorityPolicyInitialized(): Boolean { readerQueries += "priority-initialized"; return enrollmentReady }
                override fun reporterActorId(): String? { readerQueries += "reporter-actor"; return currentReporter }
                override fun priorityActorId(): String? { readerQueries += "priority-actor"; return priorityActor }
                override fun priorityAccountUnblocked(): Boolean { readerQueries += "priority-unblocked"; return true }
            }, { f.machine }, { f.lease }, f.stages, object : AccountDeletionRecoveryActorEffects {
                override fun clearGatewaySession(expectedSession: GatewayFieldSession, expectedActivityLease: AccountDeletionActivityLease) {
                    assertSame(f.lease, expectedActivityLease)
                    assertSame(expectedSession, GatewaySessionProcessCoordinator.snapshot().session)
                    assertEquals(f.actor, (f.marker.read() as AndroidAccountDeletionFallbackMarker.State.Present).record.recoveryActorId)
                    val current = GatewaySessionProcessCoordinator.snapshot()
                    assertNotNull(GatewaySessionProcessCoordinator.clear(current.generation))
                    f.events += "clear-exact-session"
                }
                override fun invalidateSpeechStorage() { f.events += "invalidate-speech" }
                override fun failClosedIntegratedConsent() { f.events += "consent-fail-closed" }
                override fun postUiRefresh() { f.events += "post-ui" }
                override fun status(value: String) { f.events += "status:$value" }
                override fun speak(value: String) { f.events += "speech:$value" }
            })
        init { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }
        fun createLegacyMarker(): AndroidAccountDeletionFallbackMarker.Record {
            val hash = MessageDigest.getInstance("SHA-256").digest(f.actor.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val record = AndroidAccountDeletionFallbackMarker.Record(AndroidAccountDeletionFallbackMarker.Identity("account_delete_legacy_0001", hash),
                recoveryActorId = null, gatewayOrigin = requireNotNull(f.origin), installationId = "installation_0001", accessSecret = "A".repeat(43),
                clientRevision = 1L, phase = AndroidAccountDeletionFallbackMarker.Phase.PREPARED)
            assertTrue(f.marker.create(record))
            f.store.readFallbackAtStartup()
            return record
        }
        fun restoreRev0(
            legacy: Boolean = false,
            transform: (AccountDeletionJournal) -> AccountDeletionJournal = { it },
        ): AccountDeletionJournal {
            val marker = if (legacy) createLegacyMarker() else f.prepared()
            assertTrue(f.store.prepareIntentAuthority().confirmed)
            val journal = transform((f.store.preparePendingJournal(requireNotNull(f.origin), f.session, marker) as AccountDeletionPendingJournalPreparation.Ready).journal)
            assertTrue(f.machine.restore(journal, f.lease))
            return journal
        }
        fun prepareLegacyPasswordTarget(accountGeneration: Long? = null): AccountDeletionPasswordRecoveryBinding {
            val marker = createLegacyMarker().copy(accountGeneration = accountGeneration)
            f.store.observeFallbackState(AndroidAccountDeletionFallbackMarker.State.Present(marker))
            f.store.observeAuthority(f.store.dualAuthority.startupState())
            assertTrue(f.machine.enterDurableConfirmationRecovery(f.lease))
            enrollmentReady = true
            return requireNotNull(actor.passwordRecoveryBindingOrNull())
        }
        fun retireEnrollment() {
            enrollmentReady = false
            currentReporter = null
            priorityActor = ""
        }
        fun publishRecoverySession(
            actorId: String,
            origin: String = requireNotNull(f.origin),
            accountGeneration: Long? = null,
        ): GatewayFieldSession {
            val session = if (accountGeneration == null) recovery(actorId, origin)
            else GatewayFieldSession.deletionRecoveryVerified(origin, actorId, accountGeneration,
                "walksafe_field_session=v6.recovery", Long.MAX_VALUE)
            assertTrue(GatewaySessionProcessCoordinator.publishDeletionRecoveryVerified(
                requireNotNull(GatewaySessionProcessCoordinator.beginOperation()), session, actorId, origin))
            return session
        }
        fun publishGeneralSession() {
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(requireNotNull(GatewaySessionProcessCoordinator.beginOperation()),
                f.session, FirstRunOnboardingPolicy.initialEmailAccount()))
        }
        override fun close() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation); f.close() }
    }
    private companion object {
        fun recovery(actorId: String, origin: String) = GatewayFieldSession.legacyVerified(origin, actorId, "device_recovery_0001",
            "walksafe_field_session=v2.recovery", Long.MAX_VALUE, GatewaySessionScope.ACCOUNT_DELETION_RECOVERY)
    }
}
