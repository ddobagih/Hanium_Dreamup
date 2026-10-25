package kr.co.hanium.dreamup.walksafe.network

import android.content.SharedPreferences
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import java.io.File
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import kr.co.hanium.dreamup.walksafe.session.FirstRunAgeBand
import kr.co.hanium.dreamup.walksafe.session.FirstRunOpaqueSubmissionHandle
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidence
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidenceVerifier
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.FirstRunOpaqueActorBinding
import kr.co.hanium.dreamup.walksafe.session.FirstRunReceiptHash
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class GatewayRefreshBusyRetryRegressionTest {
    private val fixtures = mutableListOf<Fixture>()
    private val processOwners = mutableListOf<Any>()
    @Before fun resetFence() {
        resetGatewayRefreshProofFenceForTests()
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }
    @After fun cleanup() {
        fixtures.forEach { it.preferences.failCommits = false; it.store().resetInstallationAfterConfirmedAccountDeletion() }
        processOwners.forEach(GatewaySessionProcessCoordinator::detach)
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
        resetGatewayRefreshProofFenceForTests()
    }

    @Test fun exactBusyReleasesProofOnlyAfterDurableRollbackThenSameProofSucceeds() {
        val fixture = fixture()
        val transport = Transport { if (it == 1) busy() else success(fixture.source) }
        val client = client(transport)
        fixture.reserve()
        val ticket = busyTicket(client, fixture.source)
        assertEquals(1_000L, ticket.retryAfterMs)
        assertThrows(GatewaySessionHttpException::class.java) { client.renew(fixture.source, ACTOR_ID, NOW) }
        assertEquals(1, transport.posts.get())
        assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().rollbackRenewalForRetry(
            fixture.version, OPERATION, ticket))
        val restored = requireNotNull(fixture.store().restoreActive(ORIGIN, NOW + 1_000L))
        assertEquals(fixture.version, restored.version)
        assertFalse(restored.session.isUsableFor(ACTOR_ID, NOW + 1_000L))
        assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().reserveRenewal(restored.version, NEXT_OPERATION))
        val renewed = client.renew(restored.session, ACTOR_ID, NOW + 1_000L)
        assertEquals(1L, renewed.session.rotation)
        assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().commitRenewal(
            restored.version, NEXT_OPERATION, renewed.session, restored.firstRunSnapshot))
        assertEquals(2, transport.posts.get())
        assertEquals(1L, fixture.store().restoreActive(ORIGIN, NOW + 1_000L)?.version?.rotation)
    }

    @Test fun failedRollbackCommitKeepsProofReservedAndNeverGrantsRetry() {
        val fixture = fixture()
        val transport = Transport { busy() }
        val client = client(transport)
        fixture.reserve()
        val ticket = busyTicket(client, fixture.source)
        fixture.preferences.failCommits = true
        assertEquals(GatewaySessionStoreResult.STORAGE_FAILURE, fixture.store().rollbackRenewalForRetry(
            fixture.version, OPERATION, ticket))
        assertTrue(ticket.isCurrent())
        assertEquals("gateway_refresh_proof_already_attempted", assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(fixture.source, ACTOR_ID, NOW + 1_000L)
        }.reason)
        assertEquals(1, transport.posts.get())
        assertNull(fixture.store().restoreActive(ORIGIN, NOW))
    }

    @Test fun wrongOperationOrActorCannotRollbackOrReleaseTheReservation() {
        val fixture = fixture()
        val transport = Transport { busy() }
        fixture.reserve()
        val ticket = busyTicket(client(transport), fixture.source)
        assertEquals(GatewaySessionStoreResult.STALE, fixture.store().rollbackRenewalForRetry(
            fixture.version, NEXT_OPERATION, ticket))
        assertEquals(GatewaySessionStoreResult.STALE, fixture.store().rollbackRenewalForRetry(
            fixture.version.copy(actorId = "another-actor"), OPERATION, ticket))
        assertTrue(ticket.isCurrent())
        assertNull(fixture.store().restoreActive(ORIGIN, NOW))
    }

    @Test fun oldReservationCannotReleaseANewerAttemptForTheSameProof() {
        val fixture = fixture()
        val transport = Transport { busy() }
        val client = client(transport)
        fixture.reserve()
        val old = busyTicket(client, fixture.source)
        assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().rollbackRenewalForRetry(
            fixture.version, OPERATION, old))
        fixture.reserve(NEXT_OPERATION)
        val newer = busyTicket(client, fixture.source)
        assertEquals(GatewaySessionStoreResult.STALE, fixture.store().rollbackRenewalForRetry(
            fixture.version, NEXT_OPERATION, old))
        assertTrue(newer.isCurrent())
        assertNull(fixture.store().restoreActive(ORIGIN, NOW))
        assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().rollbackRenewalForRetry(
            fixture.version, NEXT_OPERATION, newer))
        assertEquals(2, transport.posts.get())
    }

    @Test fun staleBusyCannotReplaceAnAlreadyCommittedNewerSession() {
        val fixture = fixture()
        fixture.reserve()
        val ticket = busyTicket(client(Transport { busy() }), fixture.source)
        val newer = session(fixture.source.deviceId, rotation = 1L)
        assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().commitRenewal(
            fixture.version, OPERATION, newer, fixture.firstRun))
        assertEquals(GatewaySessionStoreResult.STALE, fixture.store().rollbackRenewalForRetry(
            fixture.version, OPERATION, ticket))
        assertEquals(1L, fixture.store().restoreActive(ORIGIN, NOW)?.version?.rotation)
        assertTrue(ticket.isCurrent())
    }

    @Test fun processRestartAfterRollbackRestoresRetryableActiveWithoutVerifiedAccess() {
        val fixture = fixture()
        val transport = Transport { if (it == 1) busy() else success(fixture.source) }
        val client = client(transport)
        fixture.reserve()
        val ticket = busyTicket(client, fixture.source)
        assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().rollbackRenewalForRetry(
            fixture.version, OPERATION, ticket))
        resetGatewayRefreshProofFenceForTests()
        val restored = requireNotNull(fixture.store().restoreActive(ORIGIN, NOW + 1_000L))
        assertEquals(GatewaySessionVerificationState.RESTORED_UNVERIFIED, restored.session.verificationState)
        assertFalse(restored.session.isUsableFor(ACTOR_ID, NOW + 1_000L))
        assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().reserveRenewal(restored.version, NEXT_OPERATION))
        assertEquals(1L, client.renew(restored.session, ACTOR_ID, NOW + 1_000L).session.rotation)
        assertEquals(2, transport.posts.get())
    }

    @Test fun processRestartBeforeRollbackKeepsConservativePendingRevocation() {
        val fixture = fixture()
        fixture.reserve()
        busyTicket(client(Transport { busy() }), fixture.source)
        resetGatewayRefreshProofFenceForTests()
        assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().recoverRenewingToPendingRevocation())
        assertNull(fixture.store().restoreActive(ORIGIN, NOW))
        assertEquals(fixture.version, fixture.store().restorePendingRevocation(ORIGIN)?.version)
    }

    @Test fun unknownResponsesNeverCreateRetryReservations() {
        val responses = listOf(
            GatewayHttpResponse(500, """{"code":"field_session_storage_busy","retryable":true}""", mapOf("Retry-After" to "1")),
            // S19-11: storage_unavailable is a confirmed "not applied" answer only with retryable true.
            GatewayHttpResponse(503, """{"code":"field_session_storage_unavailable"}""", mapOf("Retry-After" to "1")),
            GatewayHttpResponse(503, """{"code":"field_session_storage_unavailable","retryable":true,"reauthentication_required":true}""", mapOf("Retry-After" to "1")),
            GatewayHttpResponse(500, """{"code":"field_session_storage_outcome_unknown","retryable":false,"reauthentication_required":true}""", mapOf("Retry-After" to "1")),
            GatewayHttpResponse(429, """{"code":"field_session_refresh_rate_limited"}""", mapOf("Retry-After" to "30")),
            GatewayHttpResponse(429, """{"code":"gateway_login_rate_limited","retryable":true}""", mapOf("Retry-After" to "30")),
            GatewayHttpResponse(503, """{"code":"field_session_refresh_rate_limited","retryable":true}""", mapOf("Retry-After" to "1")),
            GatewayHttpResponse(503, """{"code":"field_session_storage_busy","retryable":false}""", mapOf("Retry-After" to "1")),
            GatewayHttpResponse(503, """{"code":"field_session_storage_busy","retryable":"true"}""", mapOf("Retry-After" to "1")),
            GatewayHttpResponse(503, """{"detail":{"code":"field_session_storage_busy"},"retryable":true}""", mapOf("Retry-After" to "1")),
            GatewayHttpResponse(503, """{"code":"field_session_storage_busy","retryable":true}""", mapOf("Retry-After" to "not-a-delay")),
            GatewayHttpResponse(503, """{"code":"field_session_storage_busy","retryable":true}""", mapOf("Retry-After" to "0")),
        )
        responses.forEach { response ->
            resetGatewayRefreshProofFenceForTests()
            val source = session("device-installation-00000001")
            val transport = Transport { response }
            val client = client(transport)
            val error = assertThrows(GatewaySessionHttpException::class.java) { client.renew(source, ACTOR_ID, NOW) }
            assertNull(error.refreshRetryReservation)
            assertEquals("gateway_refresh_proof_already_attempted", assertThrows(GatewaySessionHttpException::class.java) {
                client.renew(source, ACTOR_ID, NOW + 1_000L)
            }.reason)
            assertEquals(1, transport.posts.get())
        }
    }

    @Test fun confirmedUnappliedStorageAndAdmissionAnswersKeepTheProofForTheSameRetry() {
        // S19-11 / S19-10: a storage failure before any commit and a refused refresh admission
        // keep the credential; the same proof is retried after Retry-After, like busy.
        val answers = listOf(
            GatewayHttpResponse(503, """{"code":"field_session_storage_unavailable","retryable":true}""", mapOf("Retry-After" to "1")) to 1_000L,
            GatewayHttpResponse(429, """{"code":"field_session_refresh_rate_limited","retryable":true}""", mapOf("Retry-After" to "30")) to 30_000L,
        )
        answers.forEach { (answer, expectedRetryMs) ->
            resetGatewayRefreshProofFenceForTests()
            val fixture = fixture()
            val transport = Transport { if (it == 1) answer else success(fixture.source) }
            val client = client(transport)
            fixture.reserve()
            val error = assertThrows(GatewaySessionHttpException::class.java) {
                client.renew(fixture.source, ACTOR_ID, NOW)
            }
            val ticket = requireNotNull(error.refreshRetryReservation)
            assertEquals(expectedRetryMs, ticket.retryAfterMs)
            assertEquals(GatewaySessionNextAction.RETRY_SAFE, error.nextAction)
            assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().rollbackRenewalForRetry(
                fixture.version, OPERATION, ticket))
            val restored = requireNotNull(fixture.store().restoreActive(ORIGIN, NOW + expectedRetryMs))
            assertEquals(fixture.version, restored.version)
            assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().reserveRenewal(restored.version, NEXT_OPERATION))
            assertEquals(1L, client.renew(restored.session, ACTOR_ID, NOW + expectedRetryMs).session.rotation)
            assertEquals(2, transport.posts.get())
        }
    }
    @Test fun timeoutRetainsUnknownOutcomeAndDurableRevocationPath() {
        val fixture = fixture()
        val transport = Transport { throw SocketTimeoutException("synthetic timeout") }
        fixture.reserve()
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            client(transport).renew(fixture.source, ACTOR_ID, NOW)
        }
        assertEquals("gateway_refresh_outcome_unknown", error.reason)
        assertNull(error.refreshRetryReservation)
        assertFalse(fixture.source.isRenewableFor(ACTOR_ID, NOW))
        assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().abandonRenewal(fixture.version, OPERATION))
        assertNotNull(fixture.store().restorePendingRevocation(ORIGIN))
        assertEquals(1, transport.posts.get())
    }

    @Test fun concurrentSameProofMakesOnlyOneTransportCallWhileAttemptIsInFlight() {
        val fixture = fixture()
        fixture.reserve()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = Transport { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); busy() }
        val client = client(transport)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val first = worker.submit<GatewayRefreshRetryReservation> { busyTicket(client, fixture.source) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val duplicate = assertThrows(GatewaySessionHttpException::class.java) { client.renew(fixture.source, ACTOR_ID, NOW) }
            assertEquals("gateway_refresh_proof_already_attempted", duplicate.reason)
            assertEquals(1, transport.posts.get())
            release.countDown()
            val ticket = first.get(3, TimeUnit.SECONDS)
            assertTrue(ticket.isCurrent())
            assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().rollbackRenewalForRetry(
                fixture.version, OPERATION, ticket))
        } finally { release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)) }
    }

    @Test fun mainHandlesDurableRollbackBeforeSchedulingAndBeforeGenericAbandon() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val renewal = MainActivityRefactorSource.function(source, "fun renewRestoredGatewaySession(")
        val rollback = renewal.indexOf("store.rollbackRenewalForRetry(")
        assertTrue(rollback >= 0)
        assertTrue(renewal.indexOf("GatewaySessionProcessCoordinator.deferRenewalAfterRollback(") > rollback)
        assertTrue(renewal.indexOf("store.abandonRenewal(") > rollback)
        val retry = MainActivityRefactorSource.function(source, "fun retryPendingGatewayRenewal(")
        assertTrue(retry.contains("GatewaySessionProcessCoordinator.snapshot().renewalRetryAtElapsedMs"))
        assertTrue(retry.contains("retryAt - elapsedRealtimeMs()"))
        val dispatch = MainActivityRefactorSource.function(source, "fun dispatchGatewayProcessSessionChanged(")
        val latestSnapshot = dispatch.indexOf("val latest = gateway.snapshot()")
        val applySnapshot = dispatch.indexOf("onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)")
        assertTrue(latestSnapshot >= 0)
        assertTrue(applySnapshot > latestSnapshot)
        assertTrue(dispatch.substring(latestSnapshot, applySnapshot)
            .contains("snapshot.generation != latest.generation"))
        assertTrue(dispatch.substring(latestSnapshot, applySnapshot)
            .contains("snapshot.session !== latest.session"))
        assertTrue(dispatch.substringBefore("val deliver = Runnable {")
            .contains("consent.fenceAuthorityChanged(snapshot)"))
        assertTrue(dispatch.contains(
            "onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)\n            gateway.retryPendingRenewal()",
        ))
        assertTrue(retry.contains("restoreGatewaySessionFromPrefs()"))
    }

    @Test fun activityReplacementDuringBusyResponseKeepsRetryForCurrentAndLaterOwners() {
        val fixture = fixture()
        val oldOwner = Any().also(processOwners::add)
        val replacementOwner = Any().also(processOwners::add)
        val laterOwner = Any().also(processOwners::add)
        GatewaySessionProcessCoordinator.attach(oldOwner) { }
        val started = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        val renewal = requireNotNull(GatewaySessionProcessCoordinator.publishRestoredUnverified(
            started, fixture.source, fixture.firstRun))
        fixture.reserve(renewal.operationId)
        GatewaySessionProcessCoordinator.detach(oldOwner)
        val replacementSnapshots = mutableListOf<GatewaySessionProcessSnapshot>()
        GatewaySessionProcessCoordinator.attach(replacementOwner, replacementSnapshots::add)
        assertNotNull(replacementSnapshots.last().inFlightOperationId)

        val ticket = busyTicket(client(Transport { busy() }), fixture.source)
        assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().rollbackRenewalForRetry(
            fixture.version, renewal.operationId, ticket))
        assertTrue(GatewaySessionProcessCoordinator.deferRenewalAfterRollback(renewal, 5_000L))
        assertEquals(5_000L, replacementSnapshots.last().renewalRetryAtElapsedMs)
        assertNull(replacementSnapshots.last().session)
        GatewaySessionProcessCoordinator.detach(replacementOwner)
        val laterSnapshots = mutableListOf<GatewaySessionProcessSnapshot>()
        GatewaySessionProcessCoordinator.attach(laterOwner, laterSnapshots::add)
        assertEquals(5_000L, laterSnapshots.last().renewalRetryAtElapsedMs)
        assertNotNull(fixture.store().restoreActive(ORIGIN, NOW))
    }

    @Test fun twoActivityOwnersCanStartOnlyOneDueRetry() {
        val fixture = fixture()
        val started = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        val renewal = requireNotNull(GatewaySessionProcessCoordinator.publishRestoredUnverified(
            started, fixture.source, fixture.firstRun))
        fixture.reserve(renewal.operationId)
        val ticket = busyTicket(client(Transport { busy() }), fixture.source)
        assertEquals(GatewaySessionStoreResult.COMMITTED, fixture.store().rollbackRenewalForRetry(
            fixture.version, renewal.operationId, ticket))
        val began = mutableListOf<GatewaySessionOperation>()
        repeat(2) {
            val owner = Any().also(processOwners::add)
            GatewaySessionProcessCoordinator.attach(owner) { snapshot ->
                if (snapshot.renewalRetryAtElapsedMs != null && snapshot.renewalRetryAtElapsedMs <= 5_000L) {
                    GatewaySessionProcessCoordinator.beginOperation()?.let(began::add)
                }
            }
        }
        assertTrue(GatewaySessionProcessCoordinator.deferRenewalAfterRollback(renewal, 5_000L))
        assertEquals(1, began.size)
        assertEquals(began.single().operationId, GatewaySessionProcessCoordinator.snapshot().inFlightOperationId)
        assertNull(GatewaySessionProcessCoordinator.snapshot().renewalRetryAtElapsedMs)
    }

    @Test fun lateBusyCannotAttachRetryToAReplacedSession() {
        val fixture = fixture()
        val old = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        GatewaySessionProcessCoordinator.clear(old)
        val newer = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        val replacement = session(fixture.source.deviceId, rotation = 1L)
        assertTrue(GatewaySessionProcessCoordinator.publishVerified(newer, replacement, fixture.firstRun))
        assertFalse(GatewaySessionProcessCoordinator.deferRenewalAfterRollback(old, 5_000L))
        assertSame(replacement, GatewaySessionProcessCoordinator.snapshot().session)
        assertNull(GatewaySessionProcessCoordinator.snapshot().renewalRetryAtElapsedMs)
    }

    private fun busyTicket(client: GatewayFieldSessionClient, source: GatewayFieldSession): GatewayRefreshRetryReservation =
        requireNotNull(assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(source, ACTOR_ID, NOW)
        }.refreshRetryReservation)

    private fun fixture() = Fixture().also(fixtures::add)
    private inner class Fixture {
        val preferences = FakeSharedPreferences()
        private val aead = object : LocalAead {
            override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits): AeadSealResult =
                AeadSealResult.Sealed(Base64.getEncoder().encodeToString(plaintext))
            override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
                AeadOpenResult.Opened(Base64.getDecoder().decode(envelope), 1, false)
            override fun destroyVersion(version: Int) = true
            override fun destroyKnownVersions() = true
            override fun createFreshAfterVerifiedPurge() = true
        }
        fun store() = AndroidGatewaySessionStore(preferences, aead, aead, aead, aead)
        val firstRun = completedLegacyFirstRun()
        val source: GatewayFieldSession
        val version: GatewaySessionVersion
        init {
            val deviceId = requireNotNull(store().getOrCreateInstallDeviceId())
            assertEquals(GatewaySessionStoreResult.COMMITTED, store().saveInitialIfAbsent(session(deviceId), firstRun, ORIGIN))
            val restored = requireNotNull(store().restoreActive(ORIGIN, NOW))
            source = restored.session
            version = restored.version
        }
        fun reserve(operation: String = OPERATION) {
            assertEquals(GatewaySessionStoreResult.COMMITTED, store().reserveRenewal(version, operation))
        }
    }

    private class Transport(private val response: (Int) -> GatewayHttpResponse) : GatewaySessionTransport {
        val posts = AtomicInteger()
        override fun postJson(url: String, body: String) = response(posts.incrementAndGet())
        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse = error("unused")
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse = error("unused")
        override fun deleteJson(url: String, body: String): GatewayHttpResponse = error("unused")
    }
    private fun client(transport: GatewaySessionTransport) = GatewayFieldSessionClient(
        transport, elapsedRealtimeNanos = { 0L }, approvedAccountOrigin = ORIGIN, allowDebugAccountOrigin = false)
    private fun busy() = GatewayHttpResponse(503, """{"code":"field_session_storage_busy","retryable":true}""", mapOf("Retry-After" to "1"))
    private fun session(deviceId: String, rotation: Long = 0L) = GatewayFieldSession.longLivedSession(
        ORIGIN, ACTOR_ID, deviceId, "f".repeat(32), rotation,
        "walksafe_field_session=access-token-rotation-${rotation.toString().repeat(32)}",
        (if (rotation == 0L) "r" else "s").repeat(64), NOW + 60_000L, NOW + 120_000L, NOW + 240_000L)
    private fun success(source: GatewayFieldSession): GatewayHttpResponse = GatewayHttpResponse(200,
        JSONObject().put("session_scope", "general").put("actor_id", ACTOR_ID).put("device_id", source.deviceId).put("family_id", source.familyId)
            .put("rotation", 1L).put("refresh_token", "s".repeat(64)).put("access_expires_at_epoch_ms", NOW + 61_000L)
            .put("idle_expires_at_epoch_ms", NOW + 121_000L).put("absolute_expires_at_epoch_ms", NOW + 240_000L).toString(),
        mapOf("Set-Cookie" to "walksafe_field_session=access-token-rotation-11111111111111111111111111111111; Path=/; HttpOnly; SameSite=Strict; Max-Age=60; Secure"))
    // This regression exercises the legacy long-lived gateway refresh path. Email restore
    // requires fresh capability checks and is covered by the separate onboarding suite.
    private fun completedLegacyFirstRun(): FirstRunOnboardingSnapshot =
        FirstRunOnboardingPolicy.restoreVerifiedReceiptPrefix(
            epoch = 10L,
            orderedEvidence = listOf(
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(1)),
                FirstRunOnboardingEvidence.AgeAndGuardianNeed(FirstRunAgeBand.ADULT_18_PLUS, receipt(2)),
                FirstRunOnboardingEvidence.IntegratedConsent(receipt(3)),
                FirstRunOnboardingEvidence.LocalCredentialPhoneSubmission(
                    FirstRunOpaqueSubmissionHandle.fromProvider("onb_0123456789abcdef0123456789abcdef"), receipt(4)),
                FirstRunOnboardingEvidence.VerifiedSms(receipt(5)),
                FirstRunOnboardingEvidence.AccountActivation(receipt(6)),
                FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR_ID), receipt(7)),
                FirstRunOnboardingEvidence.JitPermissionObservation(receipt(8)),
                FirstRunOnboardingEvidence.DeviceCheck(receipt(9)),
                FirstRunOnboardingEvidence.Fp004Training(receipt(10)),
            ),
            verifier = FirstRunOnboardingEvidenceVerifier { _, _ -> true },
        ).snapshot

    private fun receipt(number: Int): FirstRunReceiptHash =
        FirstRunReceiptHash.fromSha256Hex(number.toString(16).padStart(64, '0'))

    private class FakeSharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()
        var failCommits = false

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = apply { updates[key] = values?.toSet() }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { updates[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            override fun commit(): Boolean {
                if (failCommits) return false
                if (clear) values.clear()
                removals.forEach(values::remove)
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                return true
            }
            override fun apply() = commit().let { Unit }
        }
    }

    private companion object {
        const val ORIGIN = "https://field.example"
        const val ACTOR_ID = "actor_0123456789abcdef0123456789abcdef"
        const val NOW = 1_000L
        const val OPERATION = "renewal_operation_0001"
        const val NEXT_OPERATION = "renewal_operation_0002"
    }
}
