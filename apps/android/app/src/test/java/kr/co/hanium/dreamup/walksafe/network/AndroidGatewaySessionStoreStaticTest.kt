package kr.co.hanium.dreamup.walksafe.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGatewaySessionStoreStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidGatewaySessionStore.kt")
            .readText()
    private val sessionSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt")
            .readText()
    private val stateSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayPersistedLoginState.kt")
            .readText()

    @Test
    fun v4DelegatesToExactVersionedAndroidKeyStoreAead() {
        assertTrue(source.contains("AndroidKeyStoreAead(SESSION_KEY_POLICY)"))
        assertTrue(source.contains("AndroidKeyStoreAead(INSTALL_ID_KEY_POLICY)"))
        assertTrue(source.contains("sessionAead.open(encoded, STATE_AAD, STATE_LIMITS)"))
        assertTrue(source.contains("sessionAead.seal(payload, STATE_AAD, STATE_LIMITS)"))
        assertTrue(source.contains("installIdAead.open(encoded, INSTALL_ID_AAD, INSTALL_ID_LIMITS)"))
        assertTrue(source.contains("installIdAead.seal(payload, INSTALL_ID_AAD, INSTALL_ID_LIMITS)"))
        assertTrue(source.contains("const val STATE_FORMAT_VERSION = 4"))
        assertTrue(source.contains("aliasPrefix = \"walksafe_gateway_login_bundle_v\""))
        assertTrue(source.contains("aliasPrefix = \"walksafe_gateway_install_id_v\""))
        assertFalse(source.contains("private fun encryptionKey"))
        assertFalse(source.contains("KeyGenerator"))
        assertFalse(source.contains("Cipher.getInstance"))
        assertFalse(source.contains(".putString(STATE_PREF_KEY, payload.toString())"))
    }

    @Test
    fun completedV3StateMigratesOnlyAsExplicitLegacyFlow() {
        assertTrue(source.contains("private fun migrateCompletedV3StateLocked()"))
        assertTrue(source.contains("v3SessionAead.open(encoded, V3_STATE_AAD, STATE_LIMITS)"))
        assertTrue(source.contains("private fun decodeV3State(payload: JSONObject)"))
        assertTrue(source.contains("firstRun.jsonKeys() == V3_FIRST_RUN_FIELDS"))
        assertTrue(source.contains("V3_MIN_COMPLETE_EVIDENCE_COUNT..MAX_COMPLETE_EVIDENCE_COUNT"))
        assertTrue(source.contains("firstRunFlow = FirstRunOnboardingFlow.LEGACY_PHONE_V3"))
        assertFalse(
            source.substringAfter("private fun decodeV3Bundle(")
                .substringBefore("private fun encodeEvidence(")
                .contains("EMAIL_ACCOUNT_V4"),
        )
        assertTrue(source.contains("preferences.contains(V3_FAIL_CLOSED_PREF_KEY)"))
    }

    @Test
    fun activeAndRenewingAtomicallyContainFp010AndGatewaySession() {
        assertTrue(source.contains("const val STATE_ACTIVE = \"ACTIVE\""))
        assertTrue(source.contains("const val STATE_RENEWING = \"RENEWING\""))
        assertTrue(
            source.contains(
                "setOf(\"payload_version\", \"state\", \"first_run\", \"session\")",
            ),
        )
        assertTrue(
            source.contains(
                "setOf(\"payload_version\", \"state\", \"operation_id\", \"first_run\", \"session\")",
            ),
        )
        assertTrue(source.contains("\"ordered_evidence\""))
        listOf(
            "access_cookie_pair",
            "refresh_token",
            "device_id",
            "family_id",
            "rotation",
            "access_expires_at_epoch_ms",
            "idle_expires_at_epoch_ms",
            "absolute_expires_at_epoch_ms",
        ).forEach { field ->
            assertTrue("missing encrypted payload field $field", source.contains("\"$field\""))
        }
        assertTrue(source.contains(".putString(STATE_PREF_KEY, envelope)"))
        assertTrue(source.contains(".commit()"))
        assertFalse(source.contains("putString(\"refresh_token\""))
        assertFalse(source.contains("putString(\"access_cookie_pair\""))
        assertFalse(source.contains("putLong(\"rotation\""))
    }

    @Test
    fun pendingRevocationContainsOnlyTheRefreshProofAndIsRedacted() {
        val pendingFields = setOf(
            "payload_version",
            "state",
            "operation_id",
            "gateway_base_url",
            "actor_id",
            "device_id",
            "family_id",
            "rotation",
            "refresh_token",
        )
        pendingFields.forEach { field ->
            assertTrue(source.contains("\"$field\""))
        }
        assertTrue(source.contains("val PENDING_FIELDS = setOf("))
        assertTrue(source.contains("require(payload.jsonKeys() == PENDING_FIELDS)"))
        assertTrue(stateSource.contains("refreshToken=redacted"))
        assertFalse(
            stateSource.substringAfter("internal data class GatewayPendingRevocation")
                .substringBefore("internal data class RestoredGatewayLoginBundle")
                .contains("accessCookiePair"),
        )
        assertFalse(source.contains(".put(\"first_run\", state.pending"))
        assertFalse(source.contains(".put(\"access_cookie_pair\", state.pending"))
        assertFalse(source.contains("println("))
        assertFalse(source.contains("Log."))
    }

    @Test
    fun restoreRebuildsCompleteFp010BeforeExposingAnUnverifiedSession() {
        val firstRunRestore = source.indexOf("restoreAuthenticatedFirstRun(bundle)")
        val sessionRestore = source.indexOf("GatewayFieldSession.restore(")
        assertTrue(firstRunRestore >= 0)
        assertTrue(sessionRestore > firstRunRestore)
        assertTrue(source.contains("FirstRunOnboardingPolicy.restoreVerifiedReceiptPrefix("))
        assertTrue(source.contains("restored.fullyRestored"))
        assertTrue(source.contains("it.isComplete"))
        assertTrue(source.contains("it.mayEnterWalk"))
        assertTrue(
            source.contains(
                "it.reporterActorBinding?.value == bundle.session.actorId",
            ),
        )
        assertTrue(source.contains("version.deviceId != installDeviceId"))
        assertTrue(source.contains("version.gatewayBaseUrl != expectedGatewayBaseUrl"))
        assertTrue(sessionSource.contains("GatewaySessionVerificationState.RESTORED_UNVERIFIED"))
        assertFalse(source.contains("fun peekActor"))
        assertFalse(source.contains("fun restoreActor"))
    }

    @Test
    fun processWideLockAndDurableCasFenceEveryStateMutation() {
        assertTrue(source.contains("private companion object"))
        assertTrue(source.contains("val PROCESS_LOCK = Any()"))
        listOf(
            "saveInitialIfAbsent",
            "restoreActive",
            "reserveRenewal",
            "commitRenewal",
            "abandonRenewal",
            "recoverRenewingToPendingRevocation",
            "moveActiveToPendingRevocation",
            "restorePendingRevocation",
            "completePendingRevocation",
            "purgeDisabled",
        ).forEach { api ->
            val declaration = source.substringAfter("fun $api")
            assertTrue("$api must use the process lock", declaration.take(300).contains("PROCESS_LOCK"))
        }
        assertTrue(stateSource.contains("fun reserveRenewal("))
        assertTrue(stateSource.contains("fun commitRenewal("))
        assertTrue(stateSource.contains("fun completePendingRevocation("))
        assertTrue(source.contains("if (transition.result != GatewaySessionStoreResult.COMMITTED)"))
    }

    @Test
    fun stableInstallIdHasASeparateEnvelopeAndSurvivesLogout() {
        assertTrue(source.contains("val INSTALL_ID_KEY_POLICY"))
        assertTrue(source.contains("const val INSTALL_ID_PREF_KEY"))
        assertTrue(source.contains("val INSTALL_ID_AAD"))
        assertTrue(source.contains("fun getOrCreateInstallDeviceId()"))
        assertTrue(source.contains("GatewayCredentialPolicy.newDeviceId()"))
        val purgeBody = source.substringAfter("fun purgeDisabled()")
            .substringBefore("fun resetInstallationAfterConfirmedAccountDeletion()")
        assertFalse(purgeBody.contains(".remove(INSTALL_ID_PREF_KEY)"))
        assertFalse(purgeBody.contains("installIdAead.destroyKnownVersions()"))
        val deleteCredentials = source.substringAfter("private fun deleteCredentialKeysLocked()")
            .substringBefore("private fun stageKeyResetLocked")
        assertFalse(deleteCredentials.contains("installIdAead"))
    }

    @Test
    fun unsupportedLegacyAndV3FailClosedMarkersBlockAndUnsafeApisAreGone() {
        assertTrue(source.contains("V3_FAIL_CLOSED_PREF_KEY"))
        assertTrue(source.contains("V2_STATE_PREF_KEY"))
        assertTrue(source.contains("V1_STATE_PREF_KEY"))
        assertTrue(source.contains("preferences.contains(V2_STATE_PREF_KEY)"))
        assertTrue(source.contains("preferences.contains(V1_STATE_PREF_KEY)"))
        assertTrue(source.contains("markFailClosedLocked()"))
        assertTrue(source.contains(".putBoolean(FAIL_CLOSED_PREF_KEY, true)"))
        assertTrue(source.contains("deleteCredentialKeysLocked()"))
        assertTrue(source.contains("legacySessionAead.destroyKnownVersions()"))
        assertTrue(source.contains("aliasPrefix = \"walksafe_gateway_session_v\""))
        assertTrue(source.contains("compromisedVersions = setOf(1)"))
        assertEquals(-1, source.indexOf("fun save("))
        assertEquals(-1, source.indexOf("fun restore("))
        assertEquals(-1, source.indexOf("fun clear("))
    }

    @Test
    fun rawAccessAndRefreshValuesHaveNoPrintableOrPreferenceSurface() {
        assertTrue(sessionSource.contains("credentials=redacted"))
        assertTrue(sessionSource.contains("refreshToken=redacted"))
        assertFalse(sessionSource.contains("println("))
        assertFalse(sessionSource.contains("Log."))
        assertFalse(source.contains("println("))
        assertFalse(source.contains("Log."))
    }
}
