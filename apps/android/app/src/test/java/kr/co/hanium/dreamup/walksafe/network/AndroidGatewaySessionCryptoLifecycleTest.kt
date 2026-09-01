package kr.co.hanium.dreamup.walksafe.network

import android.content.SharedPreferences
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAeadKeyProvider
import kr.co.hanium.dreamup.walksafe.security.VersionedLocalAead
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingAttemptRequest
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidence
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidenceVerifier
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingFlow
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.session.FirstRunOpaqueActorBinding
import kr.co.hanium.dreamup.walksafe.session.FirstRunReceiptHash
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGatewaySessionCryptoLifecycleTest {
    @Test
    fun confirmedDeletionCreatesANewInstallGenerationWithoutFailClosedMarkers() {
        val fixture = Fixture("happy")
        val before = fixture.store().getOrCreateInstallDeviceId()
        assertNotNull(before)

        assertTrue(fixture.store().resetInstallationAfterConfirmedAccountDeletion())

        val after = fixture.store().getOrCreateInstallDeviceId()
        assertNotNull(after)
        assertNotEquals(before, after)
        assertFalse(fixture.preferences.contains(FAIL_CLOSED_PREF_KEY))
        assertFalse(fixture.preferences.contains(KEY_RESET_PENDING_PREF_KEY))
    }

    @Test
    fun logoutKeyResetPreservesTheInstallIdentifierAndRemainsReusable() {
        val fixture = Fixture("logout-happy")
        val installationId = fixture.store().getOrCreateInstallDeviceId()
        assertNotNull(installationId)

        assertTrue(fixture.store().purgeDisabled())

        assertTrue(fixture.store().getOrCreateInstallDeviceId() == installationId)
        assertFalse(fixture.preferences.contains(FAIL_CLOSED_PREF_KEY))
        assertFalse(fixture.preferences.contains(KEY_RESET_PENDING_PREF_KEY))
    }

    @Test
    fun restartReconcilesEveryInstallationKeyResetCrashBoundary() {
        repeat(4) { crashBoundary ->
            val fixture = Fixture("crash$crashBoundary")
            assertNotNull(fixture.store().getOrCreateInstallDeviceId())
            fixture.preferences.edit()
                .remove(INSTALL_ID_PREF_KEY)
                .remove(FAIL_CLOSED_PREF_KEY)
                .putString(KEY_RESET_PENDING_PREF_KEY, "INSTALLATION")
                .commit()
            if (crashBoundary >= 1) {
                assertTrue(fixture.sessionAead.destroyKnownVersions())
                assertTrue(fixture.v3SessionAead.destroyKnownVersions())
                assertTrue(fixture.legacyAead.destroyKnownVersions())
                assertTrue(fixture.installAead.destroyKnownVersions())
            }
            if (crashBoundary >= 2) {
                assertTrue(fixture.sessionAead.createFreshAfterVerifiedPurge())
            }
            if (crashBoundary >= 3) {
                assertTrue(fixture.installAead.createFreshAfterVerifiedPurge())
            }

            assertNotNull(fixture.store().getOrCreateInstallDeviceId())
            assertFalse(fixture.preferences.contains(KEY_RESET_PENDING_PREF_KEY))
            assertFalse(fixture.preferences.contains(FAIL_CLOSED_PREF_KEY))
        }
    }

    @Test
    fun restartReconcilesEveryLogoutSessionKeyResetCrashBoundary() {
        repeat(4) { crashBoundary ->
            val fixture = Fixture("logout$crashBoundary")
            val installationId = fixture.store().getOrCreateInstallDeviceId()
            assertNotNull(installationId)
            fixture.preferences.edit()
                .remove(FAIL_CLOSED_PREF_KEY)
                .putString(KEY_RESET_PENDING_PREF_KEY, "SESSION")
                .commit()
            if (crashBoundary >= 1) {
                assertTrue(fixture.sessionAead.destroyKnownVersions())
                assertTrue(fixture.v3SessionAead.destroyKnownVersions())
                assertTrue(fixture.legacyAead.destroyKnownVersions())
            }
            if (crashBoundary >= 2) {
                assertTrue(fixture.sessionAead.createFreshAfterVerifiedPurge())
            }
            if (crashBoundary >= 3) {
                assertTrue(fixture.preferences.edit().remove(KEY_RESET_PENDING_PREF_KEY).commit())
            }

            assertTrue(fixture.store().getOrCreateInstallDeviceId() == installationId)
            assertFalse(fixture.preferences.contains(KEY_RESET_PENDING_PREF_KEY))
            assertFalse(fixture.preferences.contains(FAIL_CLOSED_PREF_KEY))
        }
    }

    @Test
    fun ciphertextAndMissingKeysRemainFailClosedAcrossStoreInstances() {
        val fixture = Fixture("loss")
        assertNotNull(fixture.store().getOrCreateInstallDeviceId())
        fixture.provider.loseAllKeysAndTombstones()

        assertNull(fixture.store().getOrCreateInstallDeviceId())

        assertTrue(fixture.preferences.getBoolean(FAIL_CLOSED_PREF_KEY, false))
        assertFalse(fixture.provider.hasKey(fixture.installAlias))
        assertTrue(fixture.store().resetInstallationAfterConfirmedAccountDeletion())
    }

    @Test
    fun wrongTypeKeyResetMarkerFailsClosedWithoutCreatingKeys() {
        val fixture = Fixture("wrong-type-reset-marker")
        fixture.preferences.edit()
            .putBoolean(KEY_RESET_PENDING_PREF_KEY, true)
            .commit()

        assertNull(fixture.store().getOrCreateInstallDeviceId())

        assertTrue(fixture.preferences.contains(KEY_RESET_PENDING_PREF_KEY))
        assertFalse(fixture.provider.hasKey(fixture.installAlias))
        assertTrue(fixture.store().resetInstallationAfterConfirmedAccountDeletion())
    }

    @Test
    fun authenticatedCompletedV3StateMigratesOnlyAsLegacyPhoneFlow() {
        val fixture = Fixture("v3-complete")
        val deviceId = requireNotNull(fixture.store().getOrCreateInstallDeviceId())
        fixture.storeV3Active(deviceId = deviceId, evidenceCount = 10)

        val restored = fixture.store().restoreActive(GATEWAY_ORIGIN, NOW_EPOCH_MS)

        assertNotNull(restored)
        assertEquals(
            FirstRunOnboardingFlow.LEGACY_PHONE_V3,
            restored?.firstRunSnapshot?.flow,
        )
        assertTrue(restored?.firstRunSnapshot?.isComplete == true)
        assertTrue(fixture.preferences.contains(V4_STATE_PREF_KEY))
        assertFalse(fixture.preferences.contains(V3_STATE_PREF_KEY))
        assertFalse(fixture.preferences.contains(FAIL_CLOSED_PREF_KEY))
    }

    @Test
    fun emailPurposeAndSafetyReceiptIsSerializedAndRestoredAroundLocalChecks() {
        val fixture = Fixture("email-purpose-safety")
        val store = fixture.store()
        val deviceId = requireNotNull(store.getOrCreateInstallDeviceId())
        val completedFirstRun = completedReturningEmailFirstRun()
        val session = GatewayFieldSession.longLivedSession(
            gatewayBaseUrl = GATEWAY_ORIGIN,
            actorId = ACTOR_ID,
            deviceId = deviceId,
            familyId = "f".repeat(32),
            rotation = 0L,
            cookiePair = "${GatewayFieldSession.COOKIE_NAME}=access-token-00000001",
            refreshToken = "r".repeat(64),
            accessExpiresAtEpochMs = NOW_EPOCH_MS + 60_000L,
            idleExpiresAtEpochMs = NOW_EPOCH_MS + 120_000L,
            absoluteExpiresAtEpochMs = NOW_EPOCH_MS + 180_000L,
        )

        assertEquals(
            GatewaySessionStoreResult.COMMITTED,
            store.saveInitialIfAbsent(session, completedFirstRun, GATEWAY_ORIGIN),
        )
        assertEquals(
            listOf("VERIFIED_LOGIN", "PURPOSE_AND_SAFETY", "FP004_TRAINING"),
            fixture.persistedEvidenceStages(),
        )

        val restored = requireNotNull(
            fixture.store().restoreActive(GATEWAY_ORIGIN, NOW_EPOCH_MS),
        )
        assertEquals(FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4, restored.firstRunSnapshot.flow)
        assertTrue(restored.firstRunSnapshot.isComplete)
        assertEquals(
            setOf(
                FirstRunOnboardingStage.VERIFIED_LOGIN,
                FirstRunOnboardingStage.PURPOSE_AND_SAFETY,
                FirstRunOnboardingStage.FP004_TRAINING,
            ),
            restored.firstRunSnapshot.completedReceiptHashes.keys,
        )
        assertEquals(
            receipt(2),
            restored.firstRunSnapshot.completedReceiptHashes[
                FirstRunOnboardingStage.PURPOSE_AND_SAFETY
            ],
        )
    }

    @Test
    fun incompleteV3StateFailsClosedInsteadOfBecomingEmailV4() {
        val fixture = Fixture("v3-incomplete")
        val deviceId = requireNotNull(fixture.store().getOrCreateInstallDeviceId())
        fixture.storeV3Active(deviceId = deviceId, evidenceCount = 9)

        assertNull(fixture.store().restoreActive(GATEWAY_ORIGIN, NOW_EPOCH_MS))
        assertTrue(fixture.preferences.getBoolean(FAIL_CLOSED_PREF_KEY, false))
        assertFalse(fixture.preferences.contains(V3_STATE_PREF_KEY))
        assertFalse(fixture.preferences.contains(V4_STATE_PREF_KEY))
        assertTrue(fixture.store().resetInstallationAfterConfirmedAccountDeletion())
    }

    private class Fixture(suffix: String) {
        val preferences = FakeSharedPreferences()
        val provider = FakeKeyProvider()
        private val sessionPrefix = "walksafe.test.gateway.$suffix.session.v"
        private val sessionPolicy = policy(sessionPrefix, 4)
        private val v3SessionPolicy = policy(sessionPrefix, 3)
        private val installPolicy = policy("walksafe.test.gateway.$suffix.install.v", 1)
        private val legacyPolicy = policy("walksafe.test.gateway.$suffix.legacy.v", 1)
        val sessionAead = VersionedLocalAead(sessionPolicy, provider)
        val v3SessionAead = VersionedLocalAead(v3SessionPolicy, provider)
        val installAead = VersionedLocalAead(installPolicy, provider)
        val legacyAead = VersionedLocalAead(legacyPolicy, provider)
        val installAlias = installPolicy.alias(1)

        fun store() = AndroidGatewaySessionStore(
            preferences = preferences,
            sessionAead = sessionAead,
            installIdAead = installAead,
            v3SessionAead = v3SessionAead,
            legacySessionAead = legacyAead,
        )

        fun persistedEvidenceStages(): List<String> {
            val envelope = requireNotNull(preferences.getString(V4_STATE_PREF_KEY, null))
            val plaintext =
                (sessionAead.open(envelope, STATE_AAD, STATE_LIMITS) as AeadOpenResult.Opened)
                    .plaintext
            return try {
                val evidence = JSONObject(String(plaintext, Charsets.UTF_8))
                    .getJSONObject("first_run")
                    .getJSONArray("ordered_evidence")
                List(evidence.length()) { index ->
                    evidence.getJSONObject(index).getString("stage")
                }
            } finally {
                plaintext.fill(0)
            }
        }

        fun storeV3Active(deviceId: String, evidenceCount: Int) {
            val evidence = legacyEvidence().take(evidenceCount)
            val payload = JSONObject()
                .put("payload_version", 3)
                .put("state", "ACTIVE")
                .put(
                    "first_run",
                    JSONObject()
                        .put("epoch", 9L)
                        .put(
                            "ordered_evidence",
                            JSONArray().also { array -> evidence.forEach(array::put) },
                        ),
                )
                .put(
                    "session",
                    JSONObject()
                        .put("gateway_base_url", GATEWAY_ORIGIN)
                        .put("actor_id", ACTOR_ID)
                        .put(
                            "access_cookie_pair",
                            "${GatewayFieldSession.COOKIE_NAME}=access-token-00000001",
                        )
                        .put("refresh_token", "r".repeat(64))
                        .put("device_id", deviceId)
                        .put("family_id", "f".repeat(32))
                        .put("rotation", 0L)
                        .put("access_expires_at_epoch_ms", NOW_EPOCH_MS + 60_000L)
                        .put("idle_expires_at_epoch_ms", NOW_EPOCH_MS + 120_000L)
                        .put("absolute_expires_at_epoch_ms", NOW_EPOCH_MS + 180_000L),
                )
                .toString()
                .toByteArray(Charsets.UTF_8)
            val envelope = try {
                (v3SessionAead.seal(payload, V3_STATE_AAD, STATE_LIMITS)
                    as AeadSealResult.Sealed).envelope
            } finally {
                payload.fill(0)
            }
            assertTrue(
                preferences.edit().putString(V3_STATE_PREF_KEY, envelope).commit(),
            )
        }

        private fun legacyEvidence(): List<JSONObject> = listOf(
            evidence("PURPOSE_AND_SAFETY"),
            evidence("AGE_AND_GUARDIAN_NEED").put("age_band", "ADULT_18_PLUS"),
            evidence("INTEGRATED_CONSENT"),
            evidence("LOCAL_CREDENTIAL_PHONE_SUBMISSION")
                .put("submission_handle", "onb_0123456789abcdef0123456789abcdef"),
            evidence("VERIFIED_SMS"),
            evidence("ACCOUNT_ACTIVATION"),
            evidence("VERIFIED_LOGIN").put("actor_binding", ACTOR_ID),
            evidence("JIT_PERMISSION_OBSERVATION"),
            evidence("DEVICE_CHECK"),
            evidence("FP004_TRAINING"),
        )

        private fun evidence(stage: String): JSONObject = JSONObject()
            .put("stage", stage)
            .put("receipt_sha256", "a".repeat(64))

        private fun policy(prefix: String, version: Int) = AeadKeyPolicy(
            aliasPrefix = prefix,
            currentVersion = version,
            readableVersions = setOf(version),
        )
    }

    private class FakeKeyProvider : LocalAeadKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        private val tombstones = mutableSetOf<String>()
        private val observed = mutableSetOf<String>()

        override fun getOrCreate(alias: String): SecretKey? {
            observed += alias
            keys[alias]?.let { return it }
            if (alias in tombstones) return null
            tombstones += alias
            return newKey().also { keys[alias] = it }
        }

        override fun getExisting(alias: String): SecretKey? {
            observed += alias
            return keys[alias]
        }

        override fun delete(alias: String): Boolean {
            observed += alias
            tombstones += alias
            keys.remove(alias)
            return true
        }

        override fun createFreshAfterVerifiedPurge(alias: String): SecretKey {
            observed += alias
            tombstones += alias
            return keys.getOrPut(alias, ::newKey)
        }

        fun loseAllKeysAndTombstones() {
            keys.clear()
            tombstones.clear()
        }

        fun hasKey(alias: String): Boolean = alias in keys

        private fun newKey(): SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private fun completedReturningEmailFirstRun(): FirstRunOnboardingSnapshot {
        val loggedIn = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(
            snapshot = FirstRunOnboardingPolicy.initialEmailAccount(10L),
            actorBinding = FirstRunOpaqueActorBinding.fromProvider(ACTOR_ID),
            receiptHash = receipt(1),
        ).current
        val safety = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(
            snapshot = loggedIn,
            request = request(loggedIn),
            receiptHash = receipt(2),
        ).current
        val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
            snapshot = safety,
            expectedEpoch = safety.epoch,
            expectedRevision = safety.revision,
        ).current
        val device = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(
            snapshot = jit,
            expectedEpoch = jit.epoch,
            expectedRevision = jit.revision,
        ).current
        val started = FirstRunOnboardingPolicy.beginAttempt(device, request(device)).current
        return FirstRunOnboardingPolicy.completeAttempt(
            snapshot = started,
            token = requireNotNull(started.pendingAttempt),
            evidence = FirstRunOnboardingEvidence.Fp004Training(receipt(3)),
            verifier = FirstRunOnboardingEvidenceVerifier { _, _ -> true },
        ).current
    }

    private fun request(snapshot: FirstRunOnboardingSnapshot): FirstRunOnboardingAttemptRequest {
        val suffix =
            "${snapshot.epoch.toString(16)}" +
                "${snapshot.revision.toString(16)}" +
                snapshot.stage.ordinal.toString(16)
        return FirstRunOnboardingAttemptRequest.forSnapshot(
            snapshot = snapshot,
            requestId = "req_${suffix.padStart(64, 'a')}",
            attemptId = "att_${suffix.padStart(64, 'b')}",
        )
    }

    private fun receipt(number: Int): FirstRunReceiptHash =
        FirstRunReceiptHash.fromSha256Hex(number.toString(16).padStart(64, '0'))

    private class FakeSharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

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
        const val INSTALL_ID_PREF_KEY = "gateway_install_id_encrypted_v1"
        const val V4_STATE_PREF_KEY = "gateway_login_bundle_encrypted_v4"
        const val V3_STATE_PREF_KEY = "gateway_login_bundle_encrypted_v3"
        const val FAIL_CLOSED_PREF_KEY = "gateway_login_bundle_fail_closed_v4"
        const val KEY_RESET_PENDING_PREF_KEY = "gateway_key_reset_pending_v1"
        const val GATEWAY_ORIGIN = "https://gateway.example.test"
        const val ACTOR_ID = "actor_0123456789abcdef0123456789abcdef"
        const val NOW_EPOCH_MS = 1_000_000L
        val V3_STATE_AAD =
            "kr.co.hanium.dreamup.walksafe|USER|gateway-session|payload=3"
                .toByteArray(Charsets.UTF_8)
        val STATE_AAD =
            "kr.co.hanium.dreamup.walksafe|USER|gateway-session|payload=4"
                .toByteArray(Charsets.UTF_8)
        val STATE_LIMITS = AeadLimits(
            maxPlaintextBytes = 20_480,
            maxCiphertextBytes = 24_576,
            maxEnvelopeChars = 32_768,
        )
    }
}
