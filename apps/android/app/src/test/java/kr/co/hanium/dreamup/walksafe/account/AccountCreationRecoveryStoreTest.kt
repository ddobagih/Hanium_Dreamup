package kr.co.hanium.dreamup.walksafe.account

import android.content.SharedPreferences
import java.util.Base64
import kr.co.hanium.dreamup.walksafe.network.EmailOtpRequestRetryPolicy
import kr.co.hanium.dreamup.walksafe.security.*
import org.junit.Assert.*
import org.junit.Test

class AccountCreationRecoveryStoreTest {
    @Test fun expiryRestartAndLoginPreparationCannotForgetUnconfirmedCreation() {
        val prefs = FakeSharedPreferences()
        val first = store(prefs)
        assertFalse(first.hasUnconfirmedCreation())
        val partial = EmailEnrollmentPartial(
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }),
            2_000L, 1_000L, "a".repeat(64),
            selections = SignupConsentSelections(true, true, true),
        )
        assertTrue(first.save(partial))
        assertTrue(first.markCreationPending())
        val restarted = store(prefs)
        assertEquals(EmailEnrollmentRestoreResult.ClearedInvalid,
            restarted.restore(2_000L, "a".repeat(64)))
        assertTrue(restarted.hasUnconfirmedCreation())
        assertTrue(restarted.clear()) // login preparation may fail later
        assertTrue(store(prefs).hasUnconfirmedCreation())
        val confirmed = store(prefs)
        assertTrue(confirmed.clear(creationConfirmed = true))
        assertFalse(store(prefs).hasUnconfirmedCreation())
    }

    @Test fun failedPersistencePreventsDispatchAndFailedCleanupPreservesRecovery() {
        val prefs = FakeSharedPreferences()
        val first = store(prefs)
        prefs.failWrites = true
        var sent = false
        if (first.markCreationPending()) sent = true
        assertFalse(sent)
        prefs.failWrites = false
        val retry = store(prefs)
        assertTrue(retry.markCreationPending())
        prefs.failWrites = true
        assertFalse(retry.clear(creationConfirmed = true))
        prefs.failWrites = false
        assertTrue(store(prefs).hasUnconfirmedCreation())
    }

    @Test fun backgroundSuccessIsNotAppliedButLeavesLoginRecoveryAfterRestart() {
        val prefs = FakeSharedPreferences()
        val store = store(prefs)
        val fence = AccountRequestFence().apply { enteredForeground() }
        val attempt = requireNotNull(fence.begin(AccountRemoteAction.CREATE_ACCOUNT, "enrollment"))
        assertTrue(store.markCreationPending())
        fence.enteredBackground()
        fence.enteredForeground()
        // The server may have committed. Its late success must not log the user in.
        if (fence.completeIfCurrent(attempt, "enrollment")) store.clear(creationConfirmed = true)
        assertTrue(store(prefs).hasUnconfirmedCreation())
        val retry = requireNotNull(fence.begin(AccountRemoteAction.CREATE_ACCOUNT, "enrollment"))
        assertTrue(fence.completeIfCurrent(retry, "enrollment"))
        assertTrue(store(prefs).clear(creationConfirmed = true))
        assertFalse(store(prefs).hasUnconfirmedCreation())
    }

    @Test fun rejectedFirstRequestAndConfirmedDeletionRemoveOnlyTheAdvisory() {
        val prefs = FakeSharedPreferences()
        val store = store(prefs)
        assertTrue(store.markCreationPending())
        assertTrue(store.clearUnconfirmedCreation())
        assertFalse(store.hasUnconfirmedCreation())
        assertTrue(store.markCreationPending())
        assertTrue(store.resetAfterConfirmedAccountDeletion())
        assertFalse(store(prefs).hasUnconfirmedCreation())
    }

    @Test fun otpRecoveryHasNoGapBetweenRequestRecordAndReturnedEnrollment() {
        val prefs = FakeSharedPreferences()
        val payload = EmailOtpRequestRetryPolicy.Payload(
            "https://gateway.example", "a".repeat(64), "epoch-1",
            "person@example.com", "2000-01-01",
        )
        val enrollment = EmailEnrollmentPartial(
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }),
            2_000L, 1_000L, payload.ownerBindingSha256,
            selections = SignupConsentSelections(true, true, true),
        )
        var durable = store(prefs)
        fun policy(id: String) = EmailOtpRequestRetryPolicy(
            { 100L }, { id }, { durable }, { 1_500L },
        )
        val first = policy("otp_request_first_001")
        val attempt = requireNotNull(first.begin(payload))
        prefs.failWrites = true
        assertFalse(first.recordSuccess(attempt, payload) { durable.save(enrollment) })
        prefs.failWrites = false
        durable = store(prefs) // process recreation after response persistence failure
        val retry = policy("otp_request_new_00001")
        val retryPayload = payload.copy(signupAttempt = "epoch-2")
        val resumed = requireNotNull(retry.begin(retryPayload))
        assertEquals(attempt.requestId, resumed.requestId)
        // Crash after saving the response but before removing the pending request is safe too.
        assertFalse(retry.recordSuccess(resumed, retryPayload) {
            assertTrue(durable.save(enrollment))
            throw IllegalStateException("simulated process loss after durable result")
        })
        durable = store(prefs)
        assertEquals(EmailEnrollmentRestoreResult.Restored(enrollment),
            durable.restore(1_500L, payload.ownerBindingSha256))
        assertNotNull(durable.loadPendingOtpRequest())
        // Startup restores this enrollment and goes to code entry; no new email is required.
        assertTrue(durable.clearPendingOtpRequest())
        durable = store(prefs)
        assertNull(durable.loadPendingOtpRequest())
        assertEquals(EmailEnrollmentRestoreResult.Restored(enrollment),
            durable.restore(1_500L, payload.ownerBindingSha256))
    }

    private fun store(prefs: SharedPreferences) = AndroidEmailEnrollmentStore(prefs, object : LocalAead {
        // Storage flow test double; actual cryptography is covered by LocalAeadTest.
        override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits) =
            AeadSealResult.Sealed(Base64.getEncoder().encodeToString(plaintext))
        override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits) =
            AeadOpenResult.Opened(Base64.getDecoder().decode(envelope), 1, false)
        override fun destroyVersion(version: Int) = true
        override fun destroyKnownVersions() = true
        override fun createFreshAfterVerifiedPurge() = true
    })

    private class FakeSharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()
        var failWrites = false

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
                if (failWrites) return false
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

}
