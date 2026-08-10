package kr.co.hanium.dreamup.walksafe.network

import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournal
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionRev0RecoveryPolicyTest {
    @Test
    fun exactRev0TupleIsHiddenUntilCapabilityReplayRequiresReauthentication() {
        val fixture = fixture()

        assertNotNull(
            binding(
                fixture = fixture,
                reauthenticationRequestId = null,
                requireReauthentication = false,
            ),
        )
        assertNull(
            binding(
                fixture = fixture,
                reauthenticationRequestId = null,
                requireReauthentication = true,
            ),
        )
        val recovered = binding(
            fixture = fixture,
            reauthenticationRequestId = REQUEST_ID,
            requireReauthentication = true,
        )
        assertEquals(ACTOR_ID, recovered?.actorId)
        assertEquals(fixture.journal, recovered?.journal)
        assertEquals(fixture.marker, recovered?.markerRecord)

        assertNotNull(
            binding(
                fixture.copy(
                    journal = fixture.journal.copy(
                        phase = AccountDeletionPhase.RETRY_WAIT,
                        lastErrorCode = "request_failed",
                    ),
                ),
                reauthenticationRequestId = REQUEST_ID,
                requireReauthentication = true,
            ),
        )
    }

    @Test
    fun anyAuthorityIdentityOriginOrFenceMismatchKeepsRecoveryClosed() {
        val fixture = fixture()
        fun exact(
            candidate: Fixture = fixture,
            configuredOrigin: String? = ORIGIN,
            persistedActorHash: String? = fixture.actorHash,
            authorityConfirmed: Boolean = true,
            sensitiveFenceConfirmed: Boolean = true,
            remoteResumeBlocked: Boolean = false,
            requestId: String? = REQUEST_ID,
        ) = exactAccountDeletionRev0RecoveryBindingOrNull(
            journal = candidate.journal,
            markerState = AndroidAccountDeletionFallbackMarker.State.Present(
                candidate.marker,
            ),
            configuredGatewayOrigin = configuredOrigin,
            persistedActorHash = persistedActorHash,
            authorityConfirmed = authorityConfirmed,
            sensitiveFenceConfirmed = sensitiveFenceConfirmed,
            remoteResumeBlocked = remoteResumeBlocked,
            reauthenticationRequestId = requestId,
            requireReauthentication = true,
        )

        assertNotNull(exact())
        assertNull(exact(authorityConfirmed = false))
        assertNull(exact(sensitiveFenceConfirmed = false))
        assertNull(exact(remoteResumeBlocked = true))
        assertNull(exact(configuredOrigin = "https://other.example.test"))
        assertNull(exact(persistedActorHash = "0".repeat(64)))
        assertNull(exact(requestId = "different_request_0001"))
        assertNull(
            exact(
                fixture.copy(
                    marker = fixture.marker.copy(
                        recoveryActorId = "different-actor",
                    ),
                ),
            ),
        )
        assertNull(
            exact(
                fixture.copy(
                    marker = fixture.marker.copy(
                        installationId = "different-installation",
                    ),
                ),
            ),
        )
        assertNull(
            exact(
                fixture.copy(
                    marker = fixture.marker.copy(clientRevision = 2L),
                ),
            ),
        )
        assertNull(
            exact(
                fixture.copy(
                    marker = fixture.marker.copy(
                        phase = AndroidAccountDeletionFallbackMarker.Phase.ACCEPTED,
                    ),
                ),
            ),
        )
        assertNull(
            exact(
                fixture.copy(
                    journal = fixture.journal.copy(
                        phase = AccountDeletionPhase.IN_PROGRESS,
                    ),
                ),
            ),
        )
    }

    @Test
    fun rev0RecoveryAcceptsOnlyLiveProcessOwnedDeletionRecoveryScope() {
        val binding = requireNotNull(
            binding(
                fixture = fixture(),
                reauthenticationRequestId = REQUEST_ID,
                requireReauthentication = true,
            ),
        )
        val now = 1_000_000L
        val recovery = session(
            scope = GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
            actorId = ACTOR_ID,
            expiresAt = now + 10_000L,
        )
        assertTrue(
            isExactAccountDeletionRev0RecoverySessionReady(
                binding = binding,
                session = recovery,
                processSession = recovery,
                deletionRecoveryOnly = true,
                storageBlocked = false,
                nowEpochMs = now,
            ),
        )
        listOf(
            session(GatewaySessionScope.GENERAL, ACTOR_ID, now + 10_000L),
            session(
                GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
                "different-actor",
                now + 10_000L,
            ),
            session(
                GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
                ACTOR_ID,
                now,
            ),
        ).forEach { rejected ->
            assertTrue(
                !isExactAccountDeletionRev0RecoverySessionReady(
                    binding = binding,
                    session = rejected,
                    processSession = rejected,
                    deletionRecoveryOnly = true,
                    storageBlocked = false,
                    nowEpochMs = now,
                ),
            )
        }
        assertTrue(
            !isExactAccountDeletionRev0RecoverySessionReady(
                binding = binding,
                session = recovery,
                processSession = recovery,
                deletionRecoveryOnly = false,
                storageBlocked = false,
                nowEpochMs = now,
            ),
        )
        assertTrue(
            !isExactAccountDeletionRev0RecoverySessionReady(
                binding = binding,
                session = recovery,
                processSession = session(
                    GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
                    ACTOR_ID,
                    now + 10_000L,
                ),
                deletionRecoveryOnly = true,
                storageBlocked = false,
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun legacyRev0BindsOnlyExactCurrentGeneralSessionActor() {
        val fixture = fixture().let {
            it.copy(marker = it.marker.copy(recoveryActorId = null))
        }
        val now = 1_000_000L
        val general = session(
            scope = GatewaySessionScope.GENERAL,
            actorId = ACTOR_ID,
            expiresAt = now + 10_000L,
        )

        assertNotNull(
            exactLegacyAccountDeletionRev0MarkerBindingOrNull(
                journal = fixture.journal,
                markerState = AndroidAccountDeletionFallbackMarker.State.Present(
                    fixture.marker,
                ),
                configuredGatewayOrigin = ORIGIN,
                persistedActorHash = fixture.actorHash,
                authorityConfirmed = true,
                sensitiveFenceConfirmed = true,
                remoteResumeBlocked = false,
            ),
        )
        assertNotNull(
            exactLegacyAccountDeletionRev0ActorCandidateOrNull(
                journal = fixture.journal,
                markerState = AndroidAccountDeletionFallbackMarker.State.Present(
                    fixture.marker,
                ),
                configuredGatewayOrigin = ORIGIN,
                persistedActorHash = fixture.actorHash,
                authorityConfirmed = true,
                sensitiveFenceConfirmed = true,
                remoteResumeBlocked = false,
                liveActorId = ACTOR_ID,
            ),
        )

        val binding = legacyBinding(
            fixture = fixture,
            liveActorId = ACTOR_ID,
            session = general,
            processSession = general,
            now = now,
        )

        assertEquals(ACTOR_ID, binding?.actorId)
        assertEquals(fixture.journal, binding?.journal)
        assertEquals(fixture.marker, binding?.markerRecord)
        assertNull(
            legacyBinding(
                fixture = fixture,
                liveActorId = "different-actor",
                session = general,
                processSession = general,
                now = now,
            ),
        )
        val expiredGeneral = session(
            GatewaySessionScope.GENERAL,
            ACTOR_ID,
            now,
        )
        assertNull(
            legacyBinding(
                fixture = fixture,
                liveActorId = ACTOR_ID,
                session = expiredGeneral,
                processSession = expiredGeneral,
                now = now,
            ),
        )
        assertNull(
            legacyBinding(
                fixture = fixture,
                liveActorId = ACTOR_ID,
                session = session(
                    GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
                    ACTOR_ID,
                    now + 10_000L,
                ),
                processSession = general,
                now = now,
            ),
        )
        assertNull(
            legacyBinding(
                fixture = fixture,
                liveActorId = ACTOR_ID,
                session = general,
                processSession = session(
                    GatewaySessionScope.GENERAL,
                    ACTOR_ID,
                    now + 10_000L,
                ),
                now = now,
            ),
        )
        assertNull(
            legacyBinding(
                fixture = fixture.copy(
                    marker = fixture.marker.copy(
                        recoveryActorId = ACTOR_ID,
                    ),
                ),
                liveActorId = ACTOR_ID,
                session = general,
                processSession = general,
                now = now,
            ),
        )
    }

    @Test
    fun acceptedJournalRetiresExactRecoverySessionWithoutActivityFlag() {
        val fixture = fixture()
        val accepted = fixture.journal.copy(
            phase = AccountDeletionPhase.IN_PROGRESS,
            serverRevision = 1L,
            acceptedAt = "2026-08-10T00:00:00Z",
            accountGeneration = 1L,
            tombstoneId = "tombstone-0001",
            requestReceiptSha256 = "1".repeat(64),
        )
        val recovery = session(
            GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
            ACTOR_ID,
            2_000_000L,
        )

        assertTrue(
            isExactAcceptedRev0RecoverySessionRetirement(
                journal = accepted,
                markerState = AndroidAccountDeletionFallbackMarker.State.Present(
                    fixture.marker,
                ),
                persistedActorHash = fixture.actorHash,
                session = recovery,
                processSession = recovery,
                deletionRecoveryOnly = true,
            ),
        )
        assertTrue(
            !isExactAcceptedRev0RecoverySessionRetirement(
                journal = accepted,
                markerState = AndroidAccountDeletionFallbackMarker.State.Present(
                    fixture.marker,
                ),
                persistedActorHash = fixture.actorHash,
                session = recovery,
                processSession = recovery,
                deletionRecoveryOnly = false,
            ),
        )
        assertTrue(
            !isExactAcceptedRev0RecoverySessionRetirement(
                journal = accepted,
                markerState = AndroidAccountDeletionFallbackMarker.State.Present(
                    fixture.marker.copy(installationId = "other-installation"),
                ),
                persistedActorHash = fixture.actorHash,
                session = recovery,
                processSession = recovery,
                deletionRecoveryOnly = true,
            ),
        )
    }

    private fun binding(
        fixture: Fixture,
        reauthenticationRequestId: String?,
        requireReauthentication: Boolean,
    ): AccountDeletionRev0RecoveryBinding? =
        exactAccountDeletionRev0RecoveryBindingOrNull(
            journal = fixture.journal,
            markerState = AndroidAccountDeletionFallbackMarker.State.Present(
                fixture.marker,
            ),
            configuredGatewayOrigin = ORIGIN,
            persistedActorHash = fixture.actorHash,
            authorityConfirmed = true,
            sensitiveFenceConfirmed = true,
            remoteResumeBlocked = false,
            reauthenticationRequestId = reauthenticationRequestId,
            requireReauthentication = requireReauthentication,
        )

    private fun legacyBinding(
        fixture: Fixture,
        liveActorId: String?,
        session: GatewayFieldSession?,
        processSession: GatewayFieldSession?,
        now: Long,
    ): AccountDeletionRev0LegacyActorBinding? =
        exactLegacyAccountDeletionRev0ActorBindingOrNull(
            journal = fixture.journal,
            markerState = AndroidAccountDeletionFallbackMarker.State.Present(
                fixture.marker,
            ),
            configuredGatewayOrigin = ORIGIN,
            persistedActorHash = fixture.actorHash,
            authorityConfirmed = true,
            sensitiveFenceConfirmed = true,
            remoteResumeBlocked = false,
            liveActorId = liveActorId,
            session = session,
            processSession = processSession,
            deletionRecoveryOnly = false,
            storageBlocked = false,
            nowEpochMs = now,
        )

    private fun fixture(): Fixture {
        val actorHash = sha256(ACTOR_ID)
        val journal = AccountDeletionJournal.pending(
            gatewayOrigin = ORIGIN,
            installationId = INSTALLATION_ID,
            requestId = REQUEST_ID,
            requestedAt = "2026-08-10T00:00:00Z",
        )
        val marker = AndroidAccountDeletionFallbackMarker.Record(
            identity = AndroidAccountDeletionFallbackMarker.Identity(
                requestId = REQUEST_ID,
                actorHash = actorHash,
            ),
            recoveryActorId = ACTOR_ID,
            gatewayOrigin = ORIGIN,
            installationId = INSTALLATION_ID,
            accessSecret = "a".repeat(43),
            clientRevision = 1L,
            phase = AndroidAccountDeletionFallbackMarker.Phase.PREPARED,
        )
        return Fixture(journal, marker, actorHash)
    }

    private fun session(
        scope: GatewaySessionScope,
        actorId: String,
        expiresAt: Long,
    ): GatewayFieldSession = GatewayFieldSession.legacyVerified(
        gatewayBaseUrl = ORIGIN,
        actorId = actorId,
        deviceId = "device-00000001",
        cookiePair = "walksafe_field_session=test-cookie",
        expiresAtEpochMs = expiresAt,
        sessionScope = scope,
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Fixture(
        val journal: AccountDeletionJournal,
        val marker: AndroidAccountDeletionFallbackMarker.Record,
        val actorHash: String,
    )

    private companion object {
        const val ORIGIN = "https://gateway.example.test"
        const val INSTALLATION_ID = "installation-0001"
        const val REQUEST_ID = "delete_request_0001"
        const val ACTOR_ID = "walker-0001"
    }
}
