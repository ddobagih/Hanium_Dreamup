package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

/** Sweep wave22 admin app regressions: S18-1, S18-2, S18-3, S18-36. */
public class AdminDeviceKeySweepW22Test {
    private static final String ADMIN_ID = "admin-01";
    private static final String DEVICE_ID = AdminDeviceProofTest.DEVICE_ID;
    private static final String SESSION_ID = "11111111-1111-4111-8111-111111111111";
    private static final String ACCESS_TOKEN = "opaque-access-token-for-tests-123456";
    private static final String REPORT_ID = "44444444-4444-4444-8444-444444444444";

    // --- S18-3: rotation state machine -------------------------------------------------

    @Test
    public void s18_3_rotationKeepsActiveKeyUntilServerConfirmationThenSwitches() throws Exception {
        FakeKeys keys = new FakeKeys();
        MemoryMetadata metadata = new MemoryMetadata();
        AdminDeviceKeyRotation rotation = new AdminDeviceKeyRotation(keys, metadata);

        AdminDeviceKeyStore.Descriptor active = rotation.ensureActive();
        assertEquals(1, active.version());
        assertEquals(AdminDeviceKeySecurityLevel.TRUSTED_ENVIRONMENT, active.securityLevel());

        AdminDeviceKeyStore.Descriptor pending = rotation.preparePending();
        assertEquals(2, pending.version());
        assertNotEquals(active.marker(), pending.marker());
        // Before confirmation every proof is still signed by the active key.
        rotation.signActive("proof".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(1), keys.signedVersions);
        assertEquals(pending.marker(), rotation.preparePending().marker());
        assertEquals(1, rotation.ensureActive().version());

        rotation.signPending("rotation".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(1, 2), keys.signedVersions);

        rotation.promotePending();
        assertEquals(2, rotation.ensureActive().version());
        assertFalse(keys.contains(1));
        assertNull(rotation.pendingOrNull());
        rotation.signActive("proof".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(1, 2, 2), keys.signedVersions);
    }

    @Test
    public void s18_3_everyFailureBeforeConfirmationKeepsTheActiveKeyUsable() throws Exception {
        FakeKeys keys = new FakeKeys();
        MemoryMetadata metadata = new MemoryMetadata();
        AdminDeviceKeyRotation rotation = new AdminDeviceKeyRotation(keys, metadata);
        AdminDeviceKeyStore.Descriptor active = rotation.ensureActive();

        // Metadata commit fails while staging: the new key is removed again.
        metadata.failNextCommit = true;
        assertThrows(GeneralSecurityException.class, rotation::preparePending);
        assertFalse(keys.contains(2));
        assertEquals(active.marker(), rotation.ensureActive().marker());

        // The key cannot be hardware-backed: it is deleted before it is ever offered.
        keys.nextLevel = AdminDeviceKeySecurityLevel.SOFTWARE;
        assertThrows(GeneralSecurityException.class, rotation::preparePending);
        assertFalse(keys.contains(2));
        keys.nextLevel = AdminDeviceKeySecurityLevel.STRONGBOX;

        // The operator never registers the key and it is abandoned: v1 remains.
        AdminDeviceKeyStore.Descriptor pending = rotation.preparePending();
        rotation.abandonPending();
        assertFalse(keys.contains(pending.version()));
        assertNull(rotation.pendingOrNull());
        assertEquals(1, rotation.ensureActive().version());
        // A later rotation never reuses a version the server may have seen.
        assertEquals(pending.version() + 1, rotation.preparePending().version());
        assertTrue(keys.contains(1));
    }

    @Test
    public void s18_3_lostConfirmationIsReconciledFromTheServerViewOnly() throws Exception {
        FakeKeys keys = new FakeKeys();
        AdminDeviceKeyRotation rotation = new AdminDeviceKeyRotation(keys, new MemoryMetadata());
        AdminDeviceKeyStore.Descriptor active = rotation.ensureActive();
        AdminDeviceKeyStore.Descriptor pending = rotation.preparePending();

        assertEquals(AdminDeviceKeyRotation.ReconcileOutcome.UNCHANGED,
            rotation.reconcile(1, active.marker().substring(0, 8)));
        assertEquals(AdminDeviceKeyRotation.ReconcileOutcome.UNKNOWN,
            rotation.reconcile(2, active.marker().substring(0, 8)));
        assertEquals(AdminDeviceKeyRotation.ReconcileOutcome.UNKNOWN, rotation.reconcile(2, null));
        assertEquals(1, rotation.ensureActive().version());

        assertEquals(AdminDeviceKeyRotation.ReconcileOutcome.PROMOTED,
            rotation.reconcile(2, pending.marker().substring(0, 8)));
        assertEquals(2, rotation.ensureActive().version());
        assertEquals(AdminDeviceKeyRotation.ReconcileOutcome.NO_PENDING,
            rotation.reconcile(2, pending.marker().substring(0, 8)));
    }

    @Test
    public void s18_3_rotationPayloadMatchesTheBackendCanonicalBytes() {
        String payload = AdminDeviceKeyRotation.rotationPayload(
            ADMIN_ID, DEVICE_ID, SESSION_ID, AdminDeviceProofTest.CORRELATION_ID,
            1, "a".repeat(64), 2, "b".repeat(64)
        );
        assertEquals(
            "{\"admin_id\":\"admin-01\",\"correlation_id\":\"22222222-2222-4222-8222-222222222222\","
                + "\"device_id\":\"" + DEVICE_ID + "\",\"key_marker\":\"" + "b".repeat(64) + "\","
                + "\"key_version\":2,\"previous_key_marker\":\"" + "a".repeat(64) + "\","
                + "\"previous_key_version\":1,"
                + "\"schema_version\":\"walksafe.admin-device-key-rotation.v1\","
                + "\"session_id\":\"" + SESSION_ID + "\"}",
            payload
        );
    }

    @Test
    public void s18_3_confirmSendsOldKeyProofAndNewKeySignatureBoundToOneCorrelationId()
        throws Exception {
        KeyPair pendingKey = p256();
        String pendingMarker = "b".repeat(64);
        FakeSecurityTransport transport = new FakeSecurityTransport();
        transport.enqueue(200, loginJson());
        AdminSecurityHttpClient client = securityClient(transport);
        client.login(ADMIN_ID, "correct horse battery staple", "123456", DEVICE_ID, "test phone");
        transport.enqueue(200, "{\"device_id\":\"" + DEVICE_ID + "\",\"idempotent\":false,"
            + "\"key_marker\":\"" + pendingMarker + "\",\"key_version\":2,"
            + "\"previous_key_version\":1,\"status\":\"ACTIVE\"}");

        AdminSecurityApi.DeviceKeyRotationResult result = client.confirmDeviceKeyRotation(
            ACCESS_TOKEN,
            new AdminSecurityApi.DeviceKeyRotationRequest(
                1, AdminDeviceProofTest.MARKER, 2, pendingMarker,
                payload -> sign(pendingKey, payload)
            )
        );

        assertEquals(2, result.keyVersion());
        Request challenge = transport.challengeRequests.get(1);
        Request confirm = transport.requests.get(1);
        JSONObject intent = new JSONObject(challenge.body);
        assertEquals("device_key.rotation.confirm", intent.getString("action"));
        assertEquals(1, intent.getInt("device_key_version"));
        assertEquals(AdminDeviceProofTest.MARKER, intent.getString("device_key_marker"));
        assertEquals("/admin/security/device-keys/rotation/confirm", intent.getString("path"));
        assertEquals(
            AdminCanonicalEncoding.sha256Hex(confirm.body.getBytes(StandardCharsets.UTF_8)),
            intent.getString("body_sha256")
        );
        String correlationId = confirm.headers.get(AdminOperationsHttpClient.CORRELATION_ID_HEADER);
        assertEquals(intent.getString("correlation_id"), correlationId);
        JSONObject body = new JSONObject(confirm.body);
        String expectedPayload = AdminDeviceKeyRotation.rotationPayload(
            ADMIN_ID, DEVICE_ID, SESSION_ID, correlationId,
            1, AdminDeviceProofTest.MARKER, 2, pendingMarker
        );
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pendingKey.getPublic());
        verifier.update(expectedPayload.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(body.getString("rotation_signature"))));
        assertEquals(1, body.getInt("previous_key_version"));
        assertEquals(2, body.getInt("key_version"));
    }

    @Test
    public void s18_3_confirmRefusesAClientNotBuiltForTheCurrentKey() throws Exception {
        FakeSecurityTransport transport = new FakeSecurityTransport();
        transport.enqueue(200, loginJson());
        AdminSecurityHttpClient client = securityClient(transport);
        client.login(ADMIN_ID, "correct horse battery staple", "123456", DEVICE_ID, "test phone");
        assertThrows(IOException.class, () -> client.confirmDeviceKeyRotation(
            ACCESS_TOKEN,
            new AdminSecurityApi.DeviceKeyRotationRequest(
                2, "c".repeat(64), 3, "b".repeat(64), payload -> new byte[0]
            )
        ));
        assertEquals(1, transport.challengeRequests.size());
    }

    // --- S18-7: reconfirmation bound to the exact body -----------------------------------

    @Test
    public void s18_7_reportLostReauthenticationBindsTheExactBodyItThenSends() throws Exception {
        String otherDevice = "admin-device-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String path = "/admin/security/devices/" + otherDevice + "/report-lost";
        String nonce = "AAECAwQFBgcICQoLDA0ODw";
        AdminHighRiskActionGate.Operation operation = AdminHighRiskActionGate.deviceReportLost(otherDevice);
        assertEquals(AdminCanonicalEncoding.sha256Hex("{}".getBytes(StandardCharsets.UTF_8)),
            operation.intentSha256());
        assertNull(AdminHighRiskActionGate.Action.DATA_DELETE.intentSha256());

        FakeSecurityTransport transport = new FakeSecurityTransport();
        transport.enqueue(200, loginJson());
        AdminSecurityHttpClient client = securityClient(transport);
        client.login(ADMIN_ID, "correct horse battery staple", "123456", DEVICE_ID, "test phone");
        transport.enqueue(200, "{\"reauthenticated_until_epoch_ms\":" + (AdminDeviceProofTest.ISSUED_AT + 300_000L)
            + ",\"action\":\"device.report_lost\",\"method\":\"POST\",\"path\":\"" + path + "\"}");
        client.reauthenticate(
            ACCESS_TOKEN, "correct horse battery staple".toCharArray(), "654321".toCharArray(),
            "device.report_lost", "POST", path, nonce, operation.intentSha256()
        );
        JSONObject reauth = new JSONObject(transport.requests.get(1).body);
        assertEquals(operation.intentSha256(), reauth.getString("intent_sha256"));

        transport.enqueue(200, "{\"security_state\":\"NORMAL\",\"state_version\":\"v2\","
            + "\"observed_at\":\"2026-07-22T00:00:00Z\",\"recovery_custody_state\":\"ATTESTED\","
            + "\"recovery_custody_attested_at\":\"2026-07-21T23:00:00Z\"}");
        client.reportLostDevice(ACCESS_TOKEN, otherDevice,
            AdminJava8Collections.map(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER, nonce));
        Request lost = transport.requests.get(2);
        assertEquals(operation.intentSha256(),
            AdminCanonicalEncoding.sha256Hex(lost.body.getBytes(StandardCharsets.UTF_8)));

        transport.enqueue(200, "{\"reauthenticated_until_epoch_ms\":" + (AdminDeviceProofTest.ISSUED_AT + 300_000L)
            + ",\"action\":\"data.delete\",\"method\":\"POST\",\"path\":\"/admin/operations/data-deletions\"}");
        client.reauthenticate(
            ACCESS_TOKEN, "correct horse battery staple".toCharArray(), "654321".toCharArray(),
            "data.delete", "POST", "/admin/operations/data-deletions", nonce
        );
        assertFalse(new JSONObject(transport.requests.get(3).body).has("intent_sha256"));
    }

    // --- S18-2: KeyInfo security level ---------------------------------------------------

    @Test
    public void s18_2_onlyTeeOrStrongBoxKeysAreAccepted() {
        assertEquals("STRONGBOX", AdminDeviceKeySecurityLevel.classify(2, false));
        assertEquals("TRUSTED_ENVIRONMENT", AdminDeviceKeySecurityLevel.classify(1, false));
        assertEquals("SOFTWARE", AdminDeviceKeySecurityLevel.classify(0, true));
        assertEquals("UNKNOWN", AdminDeviceKeySecurityLevel.classify(-2, true));
        assertEquals("UNKNOWN", AdminDeviceKeySecurityLevel.classify(-1, true));
        assertEquals("TRUSTED_ENVIRONMENT", AdminDeviceKeySecurityLevel.classify(null, true));
        assertEquals("SOFTWARE", AdminDeviceKeySecurityLevel.classify(null, false));
        assertTrue(AdminDeviceKeySecurityLevel.isAccepted("STRONGBOX"));
        assertTrue(AdminDeviceKeySecurityLevel.isAccepted("TRUSTED_ENVIRONMENT"));
        assertFalse(AdminDeviceKeySecurityLevel.isAccepted("SOFTWARE"));
        assertFalse(AdminDeviceKeySecurityLevel.isAccepted("UNKNOWN"));
        assertFalse(AdminDeviceKeySecurityLevel.isAccepted(null));
    }

    @Test
    public void s18_2_softwareFirstKeyIsDeletedAndDescriptorRecordsTheLevel() throws Exception {
        FakeKeys keys = new FakeKeys();
        keys.nextLevel = AdminDeviceKeySecurityLevel.SOFTWARE;
        MemoryMetadata metadata = new MemoryMetadata();
        AdminDeviceKeyRotation rotation = new AdminDeviceKeyRotation(keys, metadata);
        assertThrows(GeneralSecurityException.class, rotation::ensureActive);
        assertFalse(keys.contains(1));
        assertEquals(0, metadata.state.activeVersion());

        keys.nextLevel = AdminDeviceKeySecurityLevel.STRONGBOX;
        AdminDeviceKeyStore.Descriptor descriptor = rotation.ensureActive();
        JSONObject json = new JSONObject(descriptor.registrationDescriptor(DEVICE_ID));
        assertEquals("STRONGBOX", json.getString("security_level"));
        assertEquals(descriptor.marker(), json.getString("key_marker"));
    }

    @Test
    public void s18_2_legacyKeyWhoseLevelCannotBeReadStillWorksAndIsMarkedUnknown() throws Exception {
        FakeKeys keys = new FakeKeys();
        MemoryMetadata metadata = new MemoryMetadata();
        AdminDeviceKeyRotation rotation = new AdminDeviceKeyRotation(keys, metadata);
        rotation.ensureActive();
        keys.levelReadFails = true;
        AdminDeviceKeyStore.Descriptor legacy = rotation.ensureActive();
        assertEquals(AdminDeviceKeySecurityLevel.UNKNOWN, legacy.securityLevel());
        // A new rotation key must still prove its level.
        assertThrows(GeneralSecurityException.class, rotation::preparePending);
        assertFalse(keys.contains(2));
    }

    // --- S18-1: user-authentication-bound key use ----------------------------------------

    // --- w22 review follow-up: S18-1-F2 retry policy ---------------------------------------

    /** Same shape as the Activity's runCredentialOperation worker body. */
    private static void credentialAttempt(
        AdminMutableCredentials credentials,
        List<String> dispatched,
        GeneralSecurityException signingFailure
    ) throws Exception {
        try {
            char[][] values = credentials.takeForDispatch();
            dispatched.add(new String(values[0]) + "/" + new String(values[1]));
            java.util.Arrays.fill(values[0], '\0');
            java.util.Arrays.fill(values[1], '\0');
            throw new IOException("administrator device proof could not be signed", signingFailure);
        } finally {
            credentials.close();
        }
    }

    @Test
    public void s18_1_f2_credentialBoundOperationAsksForReentryAndIsNeverReplayed() throws Exception {
        AdminDeviceKeyUseRetry retry = new AdminDeviceKeyUseRetry(new AdminDeviceKeyUseAuthorization());
        AdminMutableCredentials credentials = new AdminMutableCredentials(
            "correct horse".toCharArray(), "123456".toCharArray()
        );
        List<String> dispatched = new ArrayList<>();
        Exception failure = assertThrows(Exception.class, () -> credentialAttempt(
            credentials, dispatched,
            new AdminDeviceKeyUseAuthorization.AuthenticationRequiredException(null)
        ));
        // Credential-bound work registers no retry operation at all.
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.REQUEST_DEVICE_CREDENTIAL,
            retry.onFailure(failure, null));
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.REENTER_CREDENTIALS,
            retry.onCredentialResult(true));
        assertNull(retry.takeReady());
        assertEquals(1, dispatched.size());
        // Why: replaying the same attempt would only hit the discarded buffers.
        IllegalStateException discarded = assertThrows(IllegalStateException.class, () ->
            credentialAttempt(credentials, dispatched, null));
        assertEquals("credentials were discarded", discarded.getMessage());
        // The operator's next manual attempt (fresh credentials) is a new operation.
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.REQUEST_DEVICE_CREDENTIAL,
            retry.onFailure(failure, null));
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.STOP, retry.onCredentialResult(false));
        assertNull(retry.takeReady());
    }

    @Test
    public void s18_1_f2_operationWithoutSecretsIsRetriedExactlyOnce() {
        AdminDeviceKeyUseRetry retry = new AdminDeviceKeyUseRetry(new AdminDeviceKeyUseAuthorization());
        IOException failure = new IOException(
            new AdminDeviceKeyUseAuthorization.AuthenticationRequiredException(null)
        );
        int[] runs = {0};
        Runnable refresh = () -> runs[0]++;
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.REQUEST_DEVICE_CREDENTIAL,
            retry.onFailure(failure, refresh));
        assertNull(retry.takeReady());
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.RETRY_ONCE, retry.onCredentialResult(true));
        Runnable ready = retry.takeReady();
        ready.run();
        assertNull(retry.takeReady());
        assertEquals(1, runs[0]);
        // The retried operation fails again: stop, never loop.
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.STOP, retry.onFailure(failure, refresh));
        assertNull(retry.takeReady());
        // A launch failure of the credential screen cancels without running anything.
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.REQUEST_DEVICE_CREDENTIAL,
            retry.onFailure(failure, refresh));
        retry.cancelPending();
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.STOP, retry.onCredentialResult(true));
        assertNull(retry.takeReady());
        assertEquals(1, runs[0]);
    }

    // --- w22 review follow-up: S18-1-F1 replacement key ------------------------------------

    @Test
    public void s18_1_f1_missingActiveKeyStillAllowsAReplacementThatPromotesOnlyOnServerLogin()
        throws Exception {
        FakeKeys keys = new FakeKeys();
        MemoryMetadata metadata = new MemoryMetadata();
        AdminDeviceKeyRotation rotation = new AdminDeviceKeyRotation(keys, metadata);
        AdminDeviceKeyStore.Descriptor active = rotation.ensureActive();
        keys.delete(1); // e.g. the keystore dropped the auth-bound key with the lock screen
        assertThrows(AdminDeviceKeyRotation.ActiveKeyUnavailableException.class, rotation::ensureActive);

        AdminDeviceKeyStore.Descriptor replacement = rotation.preparePending();
        assertEquals(2, replacement.version());
        assertTrue(replacement.registrationDescriptor(DEVICE_ID).contains("\"key_version\":2"));
        byte[] payload = "login-challenge".getBytes(StandardCharsets.UTF_8);
        // Clients built for the replacement login sign with exactly that key.
        rotation.signPinned(2, replacement.marker(), payload);
        assertEquals(List.of(2), keys.signedVersions);
        assertThrows(GeneralSecurityException.class, () -> rotation.signPinned(2, active.marker(), payload));
        assertThrows(GeneralSecurityException.class, () -> rotation.signPinned(3, replacement.marker(), payload));
        assertThrows(GeneralSecurityException.class, () -> rotation.signPinned(2, replacement.marker(), new byte[0]));

        // A server view naming another key never switches the local key.
        assertFalse(rotation.promoteAfterReplacementLogin(
            2, replacement.marker(), 1, active.marker().substring(0, 8)));
        assertFalse(rotation.promoteAfterReplacementLogin(
            2, replacement.marker(), 2, active.marker().substring(0, 8)));
        assertFalse(rotation.promoteAfterReplacementLogin(3, replacement.marker(), 0, null));
        assertEquals(replacement.marker(), metadata.state.pendingMarker());
        assertEquals(1, metadata.state.activeVersion());

        // The server accepted the LOGIN proof by v2 (it is ACTIVE there): switch.
        assertTrue(rotation.promoteAfterReplacementLogin(
            2, replacement.marker(), 2, replacement.marker().substring(0, 8)));
        assertEquals(2, rotation.ensureActive().version());
        assertNull(rotation.pendingOrNull());
        // Idempotent afterwards, and the pinned signer keeps working for the live session.
        assertTrue(rotation.promoteAfterReplacementLogin(2, replacement.marker(), 0, null));
        rotation.signPinned(2, replacement.marker(), payload);
        assertEquals(List.of(2, 2), keys.signedVersions);
    }

    @Test
    public void s18_1_f1_replacementLoginSendsOnlyTheReplacementKeyProofThenPromotes() throws Exception {
        FakeKeys keys = new FakeKeys();
        AdminDeviceKeyRotation rotation = new AdminDeviceKeyRotation(keys, new MemoryMetadata());
        rotation.ensureActive();
        keys.invalidated.add(1);
        AdminDeviceKeyStore.Descriptor replacement = rotation.preparePending();
        FakeSecurityTransport transport = new FakeSecurityTransport();
        transport.enqueue(200, loginJson());
        // What the Activity builds in replacement mode: descriptor and signer pinned to v2.
        AdminSecurityHttpClient client = new AdminSecurityHttpClient(
            "http://127.0.0.1:8000",
            true,
            replacement.marker(),
            replacement.version(),
            payload -> rotation.signPinned(replacement.version(), replacement.marker(), payload),
            () -> AdminDeviceProofTest.ISSUED_AT + 1,
            transport
        );
        client.login(ADMIN_ID, "correct horse battery staple", "123456", DEVICE_ID, "test phone");
        JSONObject intent = new JSONObject(transport.challengeRequests.get(0).body);
        assertEquals("LOGIN", intent.getString("purpose"));
        assertEquals(2, intent.getInt("device_key_version"));
        assertEquals(replacement.marker(), intent.getString("device_key_marker"));
        assertEquals(List.of(2), keys.signedVersions);
        // Login accepted by the server → the replacement becomes the local ACTIVE key.
        assertTrue(rotation.promoteAfterReplacementLogin(
            replacement.version(), replacement.marker(), 0, null));
        assertEquals(2, rotation.ensureActive().version());
        assertFalse(keys.contains(1));
    }

    @Test
    public void s18_1_f1_invalidatedActiveKeyKeepsItsDescriptorAndCanBeReplaced() throws Exception {
        FakeKeys keys = new FakeKeys();
        AdminDeviceKeyRotation rotation = new AdminDeviceKeyRotation(keys, new MemoryMetadata());
        rotation.ensureActive();
        keys.invalidated.add(1); // lock screen removed: the alias stays, signing fails
        GeneralSecurityException failure = assertThrows(GeneralSecurityException.class, () ->
            rotation.signActive("proof".getBytes(StandardCharsets.UTF_8)));
        assertTrue(AdminDeviceKeyUseAuthorization.isInvalidated(failure));
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.STOP_KEY_INVALIDATED,
            new AdminDeviceKeyUseRetry(new AdminDeviceKeyUseAuthorization()).onFailure(failure, null));
        assertEquals(1, rotation.ensureActive().version());
        AdminDeviceKeyStore.Descriptor replacement = rotation.preparePending();
        assertEquals(2, replacement.version());
        // Without the operator's registration the login never succeeds, so nothing changes.
        assertEquals(1, rotation.ensureActive().version());
        assertEquals(replacement.marker(), rotation.pendingOrNull().marker());
    }

    @Test
    public void s18_1_f1_missingLockScreenIsReportedAsTheCauseAndStoresNothing() {
        FakeKeys keys = new FakeKeys();
        keys.lockScreenMissing = true;
        MemoryMetadata metadata = new MemoryMetadata();
        AdminDeviceKeyRotation rotation = new AdminDeviceKeyRotation(keys, metadata);
        GeneralSecurityException failure = assertThrows(GeneralSecurityException.class, rotation::ensureActive);
        assertTrue(AdminDeviceKeyUseAuthorization.requiresLockScreen(new IOException(failure)));
        assertFalse(AdminDeviceKeyUseAuthorization.requiresLockScreen(new IOException("status=500")));
        assertEquals(0, metadata.state.activeVersion());
        assertTrue(keys.keys.isEmpty());
        // After the operator sets a lock screen, the next start creates the key.
        keys.lockScreenMissing = false;
        assertDoesNotThrowVersion(rotation, 1);
    }

    private static void assertDoesNotThrowVersion(AdminDeviceKeyRotation rotation, int version) {
        try {
            assertEquals(version, rotation.ensureActive().version());
        } catch (GeneralSecurityException error) {
            throw new AssertionError(error);
        }
    }

    @Test
    public void s18_2_f1_levelConstantsCarryTheAndroidKeyPropertiesNames() {
        // Compile-time constants from the Android SDK stubs (API 31+).
        assertEquals(android.security.keystore.KeyProperties.SECURITY_LEVEL_UNKNOWN,
            AdminDeviceKeySecurityLevel.LEVEL_UNKNOWN);
        assertEquals(android.security.keystore.KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
            AdminDeviceKeySecurityLevel.LEVEL_UNKNOWN_SECURE);
        assertEquals(android.security.keystore.KeyProperties.SECURITY_LEVEL_SOFTWARE,
            AdminDeviceKeySecurityLevel.LEVEL_SOFTWARE);
        assertEquals(android.security.keystore.KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
            AdminDeviceKeySecurityLevel.LEVEL_TRUSTED_ENVIRONMENT);
        assertEquals(android.security.keystore.KeyProperties.SECURITY_LEVEL_STRONGBOX,
            AdminDeviceKeySecurityLevel.LEVEL_STRONGBOX);
        // Both unknown kinds stay unregistered.
        assertEquals(AdminDeviceKeySecurityLevel.UNKNOWN, AdminDeviceKeySecurityLevel.classify(-2, true));
        assertEquals(AdminDeviceKeySecurityLevel.UNKNOWN, AdminDeviceKeySecurityLevel.classify(-1, true));
    }

    @Test
    public void s18_1_authenticationRequiredAsksOnceAndRetriesOnce() {
        AdminDeviceKeyUseAuthorization gate = new AdminDeviceKeyUseAuthorization();
        IOException wrapped = new IOException("administrator device proof could not be signed",
            new AdminDeviceKeyUseAuthorization.AuthenticationRequiredException(null));
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.REQUEST_DEVICE_CREDENTIAL, gate.onFailure(wrapped));
        assertTrue(gate.isAwaitingCredential());
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.RETRY_ONCE, gate.onCredentialResult(true));
        // The retried operation fails the same way: no loop, no further prompt.
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.STOP, gate.onFailure(wrapped));
        // A fresh operation may ask again.
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.REQUEST_DEVICE_CREDENTIAL, gate.onFailure(wrapped));
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.STOP, gate.onCredentialResult(false));
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.STOP, gate.onCredentialResult(true));
    }

    @Test
    public void s18_1_invalidatedKeyStopsWithoutRetryAndOtherErrorsAreUntouched() {
        AdminDeviceKeyUseAuthorization gate = new AdminDeviceKeyUseAuthorization();
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.STOP_KEY_INVALIDATED, gate.onFailure(
            new IOException(new AdminDeviceKeyUseAuthorization.KeyInvalidatedException(null))
        ));
        assertFalse(gate.isAwaitingCredential());
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.NOT_KEY_AUTHENTICATION,
            gate.onFailure(new IOException("administrator API request failed: status=500")));
        gate.onFailure(new AdminDeviceKeyUseAuthorization.AuthenticationRequiredException(null));
        gate.onCredentialResult(true);
        gate.onSuccess();
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.REQUEST_DEVICE_CREDENTIAL,
            gate.onFailure(new AdminDeviceKeyUseAuthorization.AuthenticationRequiredException(null)));
    }

    @Test
    public void s18_1_signingFailureSurfacesTheAuthenticationCauseWithoutAutomaticResend() {
        FakeSecurityTransport transport = new FakeSecurityTransport();
        AdminSecurityHttpClient client = new AdminSecurityHttpClient(
            "http://127.0.0.1:8000",
            true,
            AdminDeviceProofTest.MARKER,
            1,
            ignored -> {
                throw new AdminDeviceKeyUseAuthorization.AuthenticationRequiredException(null);
            },
            () -> AdminDeviceProofTest.ISSUED_AT + 1,
            transport
        );
        IOException error = assertThrows(IOException.class, () ->
            client.login(ADMIN_ID, "correct horse battery staple", "123456", DEVICE_ID, "test phone")
        );
        assertTrue(AdminDeviceKeyUseAuthorization.requiresAuthentication(error));
        assertEquals(1, transport.challengeRequests.size());
        assertTrue(transport.requests.isEmpty());
    }

    // --- S18-36: attempt persistence and lookup ---------------------------------------------

    @Test
    public void s18_36_restartLooksUpOnceAndNeverMintsANewDecisionId() {
        MemoryAttemptStore store = new MemoryAttemptStore();
        AdminReviewDecisionAttempt beforeCrash = new AdminReviewDecisionAttempt(store);
        AdminReportDecision sent = prepareRejected(beforeCrash, "사진이 흐립니다");
        assertEquals(sent.decisionId(), store.snapshot.decisionId());
        assertFalse(store.snapshot.requestDigest().isEmpty());

        // Process death after dispatch; the new process restores and must look up first.
        AdminReviewDecisionAttempt restarted = new AdminReviewDecisionAttempt(store);
        restarted.restore();
        assertTrue(restarted.isPending() && restarted.needsLookup());
        assertEquals(sent.decisionId(), restarted.restoredDecisionId());
        assertThrows(IllegalStateException.class, () -> prepareRejected(restarted, "사진이 흐립니다"));

        int lookups = 0;
        lookups++;
        restarted.applyLookup(restarted.restoredDecisionId(), AdminReviewDecisionAttempt.LookupOutcome.RECORDED);
        assertEquals(1, lookups);
        assertFalse(restarted.isPending());
        assertNull(store.snapshot);
    }

    @Test
    public void s18_36_confirmedAbsenceReusesTheIdForTheSameIntentOnly() {
        MemoryAttemptStore store = new MemoryAttemptStore();
        AdminReportDecision sent = prepareRejected(new AdminReviewDecisionAttempt(store), "사진이 흐립니다");
        AdminReviewDecisionAttempt restarted = new AdminReviewDecisionAttempt(store);
        restarted.restore();
        restarted.applyLookup("99999999-9999-4999-8999-999999999999",
            AdminReviewDecisionAttempt.LookupOutcome.NOT_RECORDED);
        assertTrue(restarted.needsLookup());
        restarted.applyLookup(sent.decisionId(), AdminReviewDecisionAttempt.LookupOutcome.NOT_RECORDED);
        assertFalse(restarted.needsLookup());
        AdminReportDecision replay = prepareRejected(restarted, "사진이 흐립니다");
        assertEquals(sent.decisionId(), replay.decisionId());
        assertArrayEquals(sent.requestBody(REPORT_ID), replay.requestBody(REPORT_ID));

        AdminReviewDecisionAttempt another = new AdminReviewDecisionAttempt(store);
        another.restore();
        another.applyLookup(sent.decisionId(), AdminReviewDecisionAttempt.LookupOutcome.NOT_RECORDED);
        AdminReportDecision different = prepareRejected(another, "다른 사유로 거절합니다");
        assertNotEquals(sent.decisionId(), different.decisionId());
        assertEquals(different.decisionId(), store.snapshot.decisionId());
    }

    @Test
    public void s18_36_sessionChangeKeepsAnUnresolvedAttemptForLookup() {
        MemoryAttemptStore store = new MemoryAttemptStore();
        AdminReviewDecisionAttempt attempt = new AdminReviewDecisionAttempt(store);
        AdminReportDecision sent = prepareRejected(attempt, "사진이 흐립니다");
        attempt.suspendForSessionChange();
        assertTrue(attempt.needsLookup());
        assertEquals(sent.decisionId(), attempt.decisionId());
        attempt.clear();
        attempt.suspendForSessionChange();
        assertFalse(attempt.isPending());
        assertNull(store.snapshot);
    }

    @Test
    public void s18_36_lookupDistinguishesRecordedAbsentAndMissingReport() throws Exception {
        String decisionId = "5555aaaa-5555-4555-8555-55555555bbbb";
        FakeOperationsTransport transport = new FakeOperationsTransport();
        AdminOperationsHttpClient client = operationsClient(transport);
        AdminOperationsApi.SessionContext session = new AdminOperationsApi.SessionContext(
            ACCESS_TOKEN, ADMIN_ID, SESSION_ID, DEVICE_ID
        );
        transport.enqueue(200, reviewDecisionJson(decisionId));
        assertEquals(decisionId, client.lookupReviewDecision(session, REPORT_ID, decisionId).mutationId());
        Request lookup = transport.requests.get(0);
        assertEquals("GET", lookup.method);
        assertTrue(lookup.url.endsWith("/reports/" + REPORT_ID + "/review-decisions/" + decisionId));
        assertEquals("report.review_decisions", lookup.headers.get(AdminOperationsHttpClient.READ_PURPOSE_HEADER));

        transport.enqueue(404, "{\"detail\":{\"code\":\"review_decision_not_found\",\"message\":\"x\"}}");
        AdminOperationsApi.Result absent = client.lookupReviewDecision(session, REPORT_ID, decisionId);
        assertNull(absent.mutationId());
        assertEquals(404, absent.statusCode());

        transport.enqueue(404, "{\"detail\":{\"code\":\"report_not_found\",\"message\":\"x\"}}");
        assertThrows(AdminOperationsApi.HistoryNotFoundException.class,
            () -> client.lookupReviewDecision(session, REPORT_ID, decisionId));

        transport.enqueue(200, reviewDecisionJson("6666aaaa-6666-4666-8666-66666666bbbb"));
        assertThrows(IOException.class, () -> client.lookupReviewDecision(session, REPORT_ID, decisionId));
    }

    // --- helpers -------------------------------------------------------------------------

    private static AdminReportDecision prepareRejected(AdminReviewDecisionAttempt attempt, String reason) {
        return attempt.prepare(
            REPORT_ID,
            AdminReportDecision.Decision.REJECTED,
            reason,
            "사진을 다시 찍어 주세요",
            null,
            false,
            false,
            true,
            3,
            null,
            2L
        );
    }

    private static String reviewDecisionJson(String decisionId) {
        return "{\"id\":\"" + decisionId + "\",\"report_id\":\"" + REPORT_ID + "\",\"revision\":3,"
            + "\"content_revision\":3,\"decision\":\"REJECTED\",\"reason\":\"사진이 흐립니다\","
            + "\"user_visible_reason\":\"사진을 다시 찍어 주세요\",\"duplicate_of_report_id\":null,"
            + "\"location_reviewed\":false,\"photo_reviewed\":false,\"privacy_reviewed\":true,"
            + "\"admin_id\":\"" + ADMIN_ID + "\",\"session_id\":\"" + SESSION_ID + "\","
            + "\"device_id\":\"" + DEVICE_ID + "\","
            + "\"correlation_id\":\"22222222-2222-4222-8222-222222222222\","
            + "\"decided_at\":\"2026-09-29T03:00:00Z\",\"created_at\":\"2026-09-29T03:00:00Z\"}";
    }

    private static KeyPair p256() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] sign(KeyPair key, byte[] payload) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key.getPrivate());
        signature.update(payload);
        return signature.sign();
    }

    private static String loginJson() {
        return "{\"access_token\":\"" + ACCESS_TOKEN
            + "\",\"security_state\":\"NORMAL\",\"current_session_id\":\"" + SESSION_ID + "\"}";
    }

    private static AdminSecurityHttpClient securityClient(AdminSecurityHttpClient.Transport transport) {
        return new AdminSecurityHttpClient(
            "http://127.0.0.1:8000",
            true,
            AdminDeviceProofTest.MARKER,
            1,
            ignored -> new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01},
            () -> AdminDeviceProofTest.ISSUED_AT + 1,
            transport
        );
    }

    private static AdminOperationsHttpClient operationsClient(AdminOperationsHttpClient.Transport transport) {
        return new AdminOperationsHttpClient(
            "http://127.0.0.1:8000",
            true,
            AdminDeviceProofTest.MARKER,
            1,
            ignored -> new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01},
            () -> AdminDeviceProofTest.ISSUED_AT + 1,
            transport
        );
    }

    private static String challengeResponse(String requestBody) {
        try {
            return challengeResponseOrThrow(requestBody);
        } catch (Exception error) {
            throw new AssertionError("failed to build device challenge", error);
        }
    }

    private static String challengeResponseOrThrow(String requestBody) throws Exception {
        JSONObject request = new JSONObject(requestBody);
        Map<String, Object> exact18 = new LinkedHashMap<>();
        exact18.put("action", request.isNull("action") ? null : request.getString("action"));
        exact18.put("admin_id", request.getString("admin_id"));
        exact18.put("body_sha256", request.getString("body_sha256"));
        exact18.put("challenge_id", AdminDeviceProofTest.CHALLENGE_ID);
        exact18.put("correlation_id", request.getString("correlation_id"));
        exact18.put("device_id", request.getString("device_id"));
        exact18.put("device_key_marker", request.getString("device_key_marker"));
        exact18.put("device_key_version", request.getInt("device_key_version"));
        exact18.put("expires_at_epoch_ms", AdminDeviceProofTest.ISSUED_AT + AdminDeviceProof.CHALLENGE_TTL_MS);
        exact18.put("issued_at_epoch_ms", AdminDeviceProofTest.ISSUED_AT);
        exact18.put("method", request.getString("method"));
        exact18.put("nonce", AdminDeviceProofTest.NONCE);
        exact18.put("path", request.getString("path"));
        exact18.put("purpose", request.getString("purpose"));
        exact18.put("query_sha256", request.getString("query_sha256"));
        exact18.put("read_purpose", request.isNull("read_purpose") ? null : request.getString("read_purpose"));
        exact18.put("schema_version", AdminDeviceProof.SCHEMA_VERSION);
        exact18.put("session_id", request.isNull("session_id") ? null : request.getString("session_id"));
        Map<String, Object> exact19 = new LinkedHashMap<>(exact18);
        exact19.put("signing_payload", AdminCanonicalEncoding.canonicalJson(exact18));
        return AdminCanonicalEncoding.canonicalJson(exact19);
    }

    private static final class Request {
        final String method;
        final String url;
        final Map<String, String> headers;
        final String body;

        Request(String method, String url, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final class FakeSecurityTransport implements AdminSecurityHttpClient.Transport {
        final List<AdminSecurityHttpClient.Response> responses = new ArrayList<>();
        final List<Request> requests = new ArrayList<>();
        final List<Request> challengeRequests = new ArrayList<>();

        void enqueue(int status, String body) {
            responses.add(new AdminSecurityHttpClient.Response(status, body));
        }

        @Override
        public AdminSecurityHttpClient.Response execute(
            String method, String url, Map<String, String> headers, byte[] body
        ) {
            Request request = new Request(method, url, headers, body);
            if (url.endsWith(AdminOperationsHttpClient.CHALLENGE_PATH)) {
                challengeRequests.add(request);
                return new AdminSecurityHttpClient.Response(200, challengeResponse(request.body));
            }
            requests.add(request);
            return responses.remove(0);
        }
    }

    private static final class FakeOperationsTransport implements AdminOperationsHttpClient.Transport {
        final List<AdminOperationsHttpClient.Response> responses = new ArrayList<>();
        final List<Request> requests = new ArrayList<>();

        void enqueue(int status, String body) {
            responses.add(new AdminOperationsHttpClient.Response(status, body));
        }

        @Override
        public AdminOperationsHttpClient.Response execute(
            String method, String url, Map<String, String> headers, byte[] body
        ) {
            Request request = new Request(method, url, headers, body);
            if (url.endsWith(AdminOperationsHttpClient.CHALLENGE_PATH)) {
                return new AdminOperationsHttpClient.Response(200, challengeResponse(request.body));
            }
            requests.add(request);
            return responses.remove(0);
        }
    }

    private static final class FakeKeys implements AdminDeviceKeyRotation.KeyBackend {
        final Map<Integer, byte[]> keys = new HashMap<>();
        final Map<Integer, String> levels = new HashMap<>();
        final List<Integer> signedVersions = new ArrayList<>();
        String nextLevel = AdminDeviceKeySecurityLevel.TRUSTED_ENVIRONMENT;
        boolean levelReadFails;
        boolean lockScreenMissing;
        final java.util.Set<Integer> invalidated = new java.util.HashSet<>();
        int counter;

        @Override public boolean contains(int version) { return keys.containsKey(version); }

        @Override
        public void generate(int version) throws GeneralSecurityException {
            if (lockScreenMissing) {
                throw new AdminDeviceKeyUseAuthorization.LockScreenRequiredException(null);
            }
            if (keys.containsKey(version)) throw new GeneralSecurityException("alias exists");
            counter++;
            keys.put(version, ("spki-" + version + "-" + counter).getBytes(StandardCharsets.UTF_8));
            levels.put(version, nextLevel);
        }

        @Override
        public byte[] publicKeySpki(int version) throws GeneralSecurityException {
            byte[] spki = keys.get(version);
            if (spki == null) throw new GeneralSecurityException("missing");
            return spki.clone();
        }

        @Override
        public String securityLevel(int version) throws GeneralSecurityException {
            if (levelReadFails) throw new GeneralSecurityException("KeyInfo unavailable");
            return levels.get(version);
        }

        @Override
        public byte[] sign(int version, byte[] payload) throws GeneralSecurityException {
            if (!keys.containsKey(version)) throw new GeneralSecurityException("missing");
            if (invalidated.contains(version)) {
                throw new AdminDeviceKeyUseAuthorization.KeyInvalidatedException(null);
            }
            signedVersions.add(version);
            return new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01};
        }

        @Override public void delete(int version) { keys.remove(version); levels.remove(version); }
    }

    private static final class MemoryMetadata implements AdminDeviceKeyRotation.MetadataStore {
        AdminDeviceKeyRotation.State state = AdminDeviceKeyRotation.State.EMPTY;
        boolean failNextCommit;

        @Override public AdminDeviceKeyRotation.State load() { return state; }

        @Override
        public boolean commit(AdminDeviceKeyRotation.State next) {
            if (failNextCommit) {
                failNextCommit = false;
                return false;
            }
            state = next;
            return true;
        }
    }

    private static final class MemoryAttemptStore implements AdminReviewDecisionAttempt.Store {
        AdminReviewDecisionAttempt.Snapshot snapshot;

        @Override public AdminReviewDecisionAttempt.Snapshot load() { return snapshot; }
        @Override public void save(AdminReviewDecisionAttempt.Snapshot value) { snapshot = value; }
        @Override public void clear() { snapshot = null; }
    }
}
