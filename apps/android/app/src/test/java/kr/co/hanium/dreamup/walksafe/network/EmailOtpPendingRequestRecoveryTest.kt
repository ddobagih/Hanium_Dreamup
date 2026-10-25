package kr.co.hanium.dreamup.walksafe.network

import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S01-5: a process killed after the OTP request left the device (before the response) resends
 * the same request ID after restart, so the server replays its result instead of sending a new
 * mail or reporting a cooldown conflict. OTP, password, email and birth date are never stored.
 */
class EmailOtpPendingRequestRecoveryTest {
    @Test
    fun restartedProcessReusesCanonicalPayloadWhenBirthDateFormatChanges() {
        for ((firstDate, secondDate) in listOf(
            "20000229" to "2000-02-29",
            "2000-02-29" to "20000229",
        )) {
            val store = MemoryStore()
            val first = requireNotNull(policy(store, { START }, ID).begin(payload(dateOfBirth = firstDate)))
            val pending = requireNotNull(store.loadPendingOtpRequest())
            assertEquals("2000-02-29", first.payload.dateOfBirth)
            assertEquals(pendingEmailOtpPayloadSha256(payload(), ID), pending.payloadSha256)
            assertFalse(requireNotNull(store.value).contains(firstDate))

            val restarted = policy(store, { START + 1_000L }, "request_second_000001")
            val changedFormat = payload(signupAttempt = "epoch-2", dateOfBirth = secondDate)
            val resumed = requireNotNull(restarted.begin(changedFormat))
            assertEquals(first.requestId, resumed.requestId)
            assertEquals("2000-02-29", resumed.payload.dateOfBirth)
            assertTrue(restarted.recordSuccess(resumed, changedFormat, persistResult = { true }))
            assertNull(store.value)
        }
    }

    @Test
    fun restartedProcessReusesTheSameRequestIdForTheSamePayload() {
        val store = MemoryStore()
        var now = START
        val first = policy(store, { now }, "request_first_0000001")
        val attempt = requireNotNull(first.begin(payload(signupAttempt = "epoch-1")))
        assertEquals("request_first_0000001", attempt.requestId)
        assertNotNull(store.value)
        // Process dies before any response; the new process has a new signup epoch.
        now += 60_000L
        val second = policy(store, { now }, "request_second_000001")
        val resumed = requireNotNull(second.begin(payload(signupAttempt = "epoch-2")))
        assertEquals("request_first_0000001", resumed.requestId)
        assertTrue(second.recordSuccess(resumed, payload(signupAttempt = "epoch-2"), persistResult = { true }))
        assertNull("success clears the record", store.value)
    }

    @Test
    fun changedPayloadOwnerExpiryOrCorruptRecordStartsANewRequest() {
        for ((label, change) in listOf<Pair<String, (MemoryStore) -> EmailOtpRequestRetryPolicy.Payload>>(
            "email" to { _ -> payload(email = "other@example.com") },
            "birth date" to { _ -> payload(dateOfBirth = "2000-03-01") },
            "owner" to { _ -> payload(owner = "b".repeat(64)) },
            "gateway" to { _ -> payload(gateway = "https://other.example") },
            "corrupt" to { store -> store.value = null; store.corrupt = true; payload() },
        )) {
            val store = MemoryStore()
            requireNotNull(policy(store, { START }, "request_first_0000001").begin(payload()))
            val next = change(store)
            val resumed = requireNotNull(policy(store, { START + 1_000L }, "request_new_000000001").begin(next))
            assertEquals(label, "request_new_000000001", resumed.requestId)
        }
        val store = MemoryStore()
        requireNotNull(policy(store, { START }, "request_first_0000001").begin(payload()))
        val late = requireNotNull(
            policy(store, { START + PENDING_EMAIL_OTP_REQUEST_TTL_MS + 1L }, "request_late_00000001")
                .begin(payload()),
        )
        assertEquals("request_late_00000001", late.requestId)
        val clockBack = MemoryStore()
        requireNotNull(policy(clockBack, { START }, "request_first_0000001").begin(payload()))
        assertEquals(
            "request_back_00000001",
            requireNotNull(policy(clockBack, { START - 1L }, "request_back_00000001").begin(payload())).requestId,
        )
    }

    @Test
    fun uncertainFailureKeepsTheRecordAndTerminalFailureOrCancelClearsIt() {
        val store = MemoryStore()
        val policy = policy(store, { START }, "request_first_0000001")
        val attempt = requireNotNull(policy.begin(payload()))
        assertTrue(policy.recordFailure(attempt, payload(), 0, null, null))
        assertNotNull("transport timeout keeps the same ID", store.value)
        val retry = requireNotNull(policy.begin(payload()))
        assertEquals(attempt.requestId, retry.requestId)
        assertTrue(policy.recordFailure(retry, payload(), 409, "account_enrollment_idempotency_conflict", null))
        assertNull(store.value)
        requireNotNull(policy.begin(payload()))
        assertNotNull(store.value)
        policy.cancel()
        assertNull("closing the signup panel forgets the request", store.value)
    }

    @Test
    fun recordHoldsNoSecretOrContactFieldAndCodecIsStrict() {
        val record = PendingEmailOtpRequest(
            requestId = "request_first_0000001",
            ownerBindingSha256 = OWNER,
            payloadSha256 = pendingEmailOtpPayloadSha256(payload(), ID),
            startedAtEpochMs = START,
        )
        val encoded = PendingEmailOtpRequestCodec.encode(record)
        assertEquals(record, PendingEmailOtpRequestCodec.decode(encoded))
        assertFalse(encoded.contains("person@example.com"))
        assertFalse(encoded.contains("2000-02-29"))
        assertEquals("PendingEmailOtpRequest(redacted)", record.toString())
        assertNull(PendingEmailOtpRequestCodec.decode(encoded.replace("\"started_at_epoch_ms\"", "\"started\"")))
        assertNull(PendingEmailOtpRequestCodec.decode(encoded.dropLast(1) + ",\"email\":\"x\"}"))
        assertNull(PendingEmailOtpRequestCodec.decode("{"))
        assertNotEquals(
            pendingEmailOtpPayloadSha256(payload(), ID),
            pendingEmailOtpPayloadSha256(payload(email = "person2@example.com"), ID),
        )
        assertEquals(
            "the process-local signup epoch is not part of the digest",
            pendingEmailOtpPayloadSha256(payload(signupAttempt = "a"), ID),
            pendingEmailOtpPayloadSha256(payload(signupAttempt = "b"), ID),
        )
    }

    @Test
    fun payloadDigestIsSaltedWithTheRandomRequestId() {
        // S01-5-F1: an unsalted digest of email + birth date can be confirmed by guessing offline.
        val first = MemoryStore()
        requireNotNull(policy(first, { START }, "request_first_0000001").begin(payload()))
        val second = MemoryStore()
        requireNotNull(policy(second, { START }, "request_other_0000001").begin(payload()))
        val firstDigest = JSONObject(requireNotNull(first.value)).getString("payload_sha256")
        val secondDigest = JSONObject(requireNotNull(second.value)).getString("payload_sha256")
        assertNotEquals("same email and birth date under two request IDs", firstDigest, secondDigest)
        assertNotEquals(unsaltedV1Digest(payload()), firstDigest)
        // A record written with the unsalted v1 digest is not reused; a new request ID is created.
        val legacy = PendingEmailOtpRequest("request_first_0000001", OWNER, unsaltedV1Digest(payload()), START)
        assertFalse(legacy.reusableFor(payload(), START + 1_000L))
        // The salted record still resumes the same request ID after a restart.
        val resumed = requireNotNull(policy(first, { START + 1_000L }, "request_new_000000001").begin(payload()))
        assertEquals("request_first_0000001", resumed.requestId)
    }

    @Test
    fun preNormalizationRecordStillReplaysTheSameIdForTheExactEnteredEmail() {
        val entered = payload(email = "Person@EXAMPLE.com")
        val store = MemoryStore().apply {
            value = PendingEmailOtpRequestCodec.encode(PendingEmailOtpRequest(
                ID, OWNER, pendingEmailOtpPayloadSha256(entered, ID), START,
            ))
        }
        val resumed = requireNotNull(policy(store, { START + 1_000L }, "request_other_0000001").begin(entered))
        assertEquals(ID, resumed.requestId)
        assertEquals("Person@example.com", resumed.payload.email)
    }

    @Test
    fun missingStoreReadFailureAndSaveFailureBlockDispatch() {
        val missing = EmailOtpRequestRetryPolicy({ 0L }, { ID }, pendingStore = { null })
        assertNull(missing.begin(payload()))
        assertTrue(missing.storageFailure)
        for (readFailure in listOf(true, false)) {
            val store = MemoryStore().apply { this.readFailure = readFailure; saveAllowed = false }
            val retry = policy(store, { START }, ID)
            assertNull(retry.begin(payload()))
            assertTrue(retry.storageFailure)
            assertNull(store.value)
        }
    }

    @Test
    fun cachedWireIdCannotBypassARecoveryReadFailureOnAnExplicitRetry() {
        val store = MemoryStore()
        val retry = policy(store, { START }, ID)
        val first = requireNotNull(retry.begin(payload()))
        assertTrue(retry.recordFailure(first, payload(), 0, null, null))
        store.readFailure = true
        assertNull(retry.begin(payload()))
        assertTrue(retry.storageFailure)
        assertNotNull(store.value)
    }

    @Test
    fun replacingPayloadDoesNotEraseThePreviousDurableRecoveryPointOnSaveFailure() {
        val store = MemoryStore()
        val retry = policy(store, { START }, ID)
        requireNotNull(retry.begin(payload()))
        val saved = store.value
        store.saveAllowed = false
        assertNull(retry.begin(payload(email = "replacement@example.com")))
        assertTrue(retry.storageFailure)
        assertEquals(saved, store.value)
        val restarted = policy(store, { START + 1_000L }, "request_other_0000001")
        assertEquals(ID, requireNotNull(restarted.begin(payload())).requestId)
    }

    @Test
    fun returnedEnrollmentMustBeDurableBeforeTheReplayIdIsCleared() {
        val store = MemoryStore()
        val retry = policy(store, { START }, ID)
        val attempt = requireNotNull(retry.begin(payload()))
        val saved = store.value
        assertFalse(retry.recordSuccess(attempt, payload(), persistResult = { false }))
        assertTrue(retry.storageFailure)
        assertEquals(saved, store.value)
        val restarted = policy(store, { START + 1_000L }, "request_other_0000001")
        val resumed = requireNotNull(restarted.begin(payload()))
        assertEquals(ID, resumed.requestId)
        assertTrue(restarted.recordSuccess(resumed, payload()) {
            assertEquals(saved, store.value)
            true
        })
        assertNull(store.value)
    }

    @Test
    fun mainActivityWiresTheEncryptedEnrollmentStore() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")
            .readText(Charsets.UTF_8)
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/account/EmailAccountController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(source, "createEmailAccountController").contains("EmailAccountController("))
        assertTrue(source.contains("emailAccountController.initializeStorage("))
        val construction = MainActivityRefactorSource.property(owner, "emailOtpRequestRetryPolicy")
        assertTrue(construction.contains("pendingStore = {"))
        assertTrue(construction.contains("::storage.isInitialized && !emailEnrollmentStorageBlocked"))
    }

    private fun unsaltedV1Digest(value: EmailOtpRequestRetryPolicy.Payload): String =
        MessageDigest.getInstance("SHA-256").digest(
            listOf(
                "walksafe.email-otp-pending-request.v1",
                value.gatewayBaseUrl,
                value.ownerBindingSha256,
                value.email,
                value.dateOfBirth,
            ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun policy(store: MemoryStore, clock: () -> Long, id: String) = EmailOtpRequestRetryPolicy(
        elapsedRealtimeMs = { 1_000L },
        createRequestId = { id },
        pendingStore = { store },
        wallClockMs = clock,
    )

    private fun payload(
        gateway: String = "https://gateway.example",
        owner: String = OWNER,
        signupAttempt: String = "epoch-1",
        email: String = "person@example.com",
        dateOfBirth: String = "2000-02-29",
    ) = EmailOtpRequestRetryPolicy.Payload(gateway, owner, signupAttempt, email, dateOfBirth)

    private class MemoryStore : PendingEmailOtpRequestStore {
        var value: String? = null
        var corrupt = false
        var saveAllowed = true
        var readFailure = false
        override fun loadPendingOtpRequest(): PendingEmailOtpRequest? =
            if (readFailure) error("synthetic read failure") else if (corrupt) PendingEmailOtpRequestCodec.decode("{\"schema_version\":1}") else value?.let(PendingEmailOtpRequestCodec::decode)
        override fun savePendingOtpRequest(request: PendingEmailOtpRequest): Boolean {
            if (!saveAllowed) return false
            corrupt = false
            value = PendingEmailOtpRequestCodec.encode(request)
            return true
        }
        override fun clearPendingOtpRequest(): Boolean {
            value = null
            return true
        }
    }

    private companion object {
        val OWNER = "a".repeat(64)
        const val ID = "request_first_0000001"
        const val START = 1_788_663_600_000L
    }
}
