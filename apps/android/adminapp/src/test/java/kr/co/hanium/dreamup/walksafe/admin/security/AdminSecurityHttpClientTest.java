package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;

public final class AdminSecurityHttpClientTest {
    @Test
    public void loginAndProtectedStateUseExactAdminContextAndDeviceBinding() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, loginJson());
        transport.enqueue(200, stateJson("NORMAL", "state-v1"));
        AdminSecurityHttpClient client = client(transport);

        var login = client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        var state = client.getState(login.accessToken());

        Request loginRequest = transport.requests.get(0);
        assertEquals("POST", loginRequest.method);
        assertEquals("http://127.0.0.1:8000/admin/security/sessions", loginRequest.url);
        assertEquals("ADMIN_ANDROID", loginRequest.headers.get("X-WalkSafe-App-Kind"));
        assertEquals("ADMIN", loginRequest.headers.get("X-WalkSafe-Role"));
        assertEquals("walksafe-admin-api", loginRequest.headers.get("X-WalkSafe-Audience"));
        assertEquals(DEVICE_ID, loginRequest.headers.get("X-WalkSafe-Device-Id"));
        assertFalse(loginRequest.headers.containsKey("Authorization"));
        JSONObject loginBody = new JSONObject(loginRequest.body);
        assertEquals(PASSWORD, loginBody.getString("password"));
        assertEquals("123456", loginBody.getString("totp_code"));

        Request stateRequest = transport.requests.get(1);
        assertEquals("Bearer " + ACCESS_TOKEN, stateRequest.headers.get("Authorization"));
        assertEquals(DEVICE_ID, stateRequest.headers.get("X-WalkSafe-Device-Id"));
        assertEquals("state-v1", state.stateVersion());
        assertEquals(AdminRecoveryCustodyState.ATTESTED, state.recoveryCustodyState());
        assertEquals("2026-07-21T23:00:00Z", state.recoveryCustodyAttestedAt());
    }

    @Test
    public void custodyAttestationAndLostDeviceReportUseExactAuthenticatedContracts() throws Exception {
        FakeTransport transport = loggedInTransport();
        transport.enqueue(200, stateJson("NORMAL", "state-v2"));
        transport.enqueue(200, stateJson("NORMAL", "state-v3"));
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        AdminSecurityApi.RecoveryCustodyAttestation attestation =
            new AdminSecurityApi.RecoveryCustodyAttestation(
                CUSTODY_REFERENCE,
                AdminSecurityApi.RecoveryMaterialKind.SECURITY_KEY,
                AdminSecurityApi.RecoveryStorageLocation.OFF_PHONE,
                true
            );
        client.attestRecoveryCustody(ACCESS_TOKEN, attestation);
        client.reportLostDevice(ACCESS_TOKEN, OTHER_DEVICE_ID);

        assertEquals(3, transport.challengeRequests.size());
        Request custodyRequest = transport.requests.get(1);
        assertEquals("POST", custodyRequest.method);
        assertEquals(
            "http://127.0.0.1:8000/admin/security/recovery-custody/attest",
            custodyRequest.url
        );
        assertEquals("Bearer " + ACCESS_TOKEN, custodyRequest.headers.get("Authorization"));
        JSONObject custodyBody = new JSONObject(custodyRequest.body);
        assertEquals(Set.of(
            "custody_reference",
            "material_kind",
            "storage_location",
            "separate_encrypted_backup_confirmed"
        ), keys(custodyBody));
        assertEquals(CUSTODY_REFERENCE, custodyBody.getString("custody_reference"));
        assertEquals("SECURITY_KEY", custodyBody.getString("material_kind"));
        assertEquals("OFF_PHONE", custodyBody.getString("storage_location"));
        assertTrue(custodyBody.getBoolean("separate_encrypted_backup_confirmed"));
        assertActionProof(
            transport.challengeRequests.get(1),
            custodyRequest,
            "recovery.custody.attest",
            "/admin/security/recovery-custody/attest"
        );

        Request lostRequest = transport.requests.get(2);
        assertEquals("POST", lostRequest.method);
        assertEquals(
            "http://127.0.0.1:8000/admin/security/devices/" + OTHER_DEVICE_ID + "/report-lost",
            lostRequest.url
        );
        assertEquals(0, new JSONObject(lostRequest.body).length());
        assertEquals("Bearer " + ACCESS_TOKEN, lostRequest.headers.get("Authorization"));
        assertActionProof(
            transport.challengeRequests.get(2),
            lostRequest,
            "device.report_lost",
            "/admin/security/devices/" + OTHER_DEVICE_ID + "/report-lost"
        );
    }

    @Test
    public void custodyContractRejectsNonCanonicalReferencesAndUnconfirmedStorage() {
        assertThrows(IllegalArgumentException.class, () ->
            new AdminSecurityApi.RecoveryCustodyAttestation(
                "A".repeat(42) + "B",
                AdminSecurityApi.RecoveryMaterialKind.RECOVERY_CODE,
                AdminSecurityApi.RecoveryStorageLocation.OFF_PHONE,
                true
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new AdminSecurityApi.RecoveryCustodyAttestation(
                CUSTODY_REFERENCE,
                AdminSecurityApi.RecoveryMaterialKind.RECOVERY_CODE,
                AdminSecurityApi.RecoveryStorageLocation.OFF_PHONE,
                false
            )
        );
    }

    @Test
    public void currentBoundDeviceCannotBeReportedLostByItsOwnSession() throws Exception {
        FakeTransport transport = loggedInTransport();
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        assertThrows(IOException.class, () -> client.reportLostDevice(ACCESS_TOKEN, DEVICE_ID));
        assertEquals(1, transport.requests.size());
    }

    @Test
    public void clearedOrMismatchedSessionBindingCannotCreateAnActionProof() throws Exception {
        FakeTransport transport = loggedInTransport();
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        AdminSecurityApi.RecoveryCustodyAttestation attestation =
            new AdminSecurityApi.RecoveryCustodyAttestation(
                CUSTODY_REFERENCE,
                AdminSecurityApi.RecoveryMaterialKind.RECOVERY_CODE,
                AdminSecurityApi.RecoveryStorageLocation.OFF_PHONE,
                true
            );

        assertThrows(IOException.class, () -> client.attestRecoveryCustody(
            "different-opaque-access-token-for-tests-123456",
            attestation
        ));
        client.clearLocalBinding();
        assertThrows(IOException.class, () -> client.attestRecoveryCustody(ACCESS_TOKEN, attestation));

        assertEquals(1, transport.challengeRequests.size());
        assertEquals(1, transport.requests.size());
    }

    @Test
    public void loginFixesBodyBytesBeforeLoginProofAndReusesThemWithProofHeaders() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, loginJson());

        client(transport).login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        assertEquals(1, transport.challengeRequests.size());
        Request challengeRequest = transport.challengeRequests.get(0);
        Request loginRequest = transport.requests.get(0);
        JSONObject challenge = new JSONObject(challengeRequest.body);
        assertEquals(13, challenge.length());
        assertEquals("LOGIN", challenge.getString("purpose"));
        assertEquals("POST", challenge.getString("method"));
        assertEquals("/admin/security/sessions", challenge.getString("path"));
        assertTrue(challenge.isNull("action"));
        assertTrue(challenge.isNull("read_purpose"));
        assertTrue(challenge.isNull("session_id"));
        assertEquals(
            AdminCanonicalEncoding.sha256Hex(loginRequest.body.getBytes(StandardCharsets.UTF_8)),
            challenge.getString("body_sha256")
        );
        assertEquals(
            challenge.getString("correlation_id"),
            loginRequest.headers.get(AdminOperationsHttpClient.CORRELATION_ID_HEADER)
        );
        assertEquals(
            AdminDeviceProofTest.CHALLENGE_ID,
            loginRequest.headers.get(AdminDeviceProof.CHALLENGE_ID_HEADER)
        );
        assertFalse(loginRequest.headers.get(AdminDeviceProof.SIGNATURE_HEADER).contains("="));
    }

    @Test
    public void proofBoundLoginRequiresExactChallengeAndFinalSuccessStatuses() {
        FakeTransport wrongChallenge = new FakeTransport();
        wrongChallenge.challengeStatus = 201;
        wrongChallenge.enqueue(200, loginJson());
        assertThrows(IOException.class, () -> client(wrongChallenge)
            .login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"));
        assertEquals(1, wrongChallenge.challengeRequests.size());
        assertTrue(wrongChallenge.requests.isEmpty());

        FakeTransport wrongFinal = new FakeTransport();
        wrongFinal.enqueue(201, loginJson());
        assertThrows(IOException.class, () -> client(wrongFinal)
            .login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"));
        assertEquals(1, wrongFinal.challengeRequests.size());
        assertEquals(1, wrongFinal.requests.size());
    }

    @Test
    public void actionProofRequiresExactChallengeAndFinalSuccessStatuses() throws Exception {
        AdminSecurityApi.RecoveryCustodyAttestation attestation =
            new AdminSecurityApi.RecoveryCustodyAttestation(
                CUSTODY_REFERENCE,
                AdminSecurityApi.RecoveryMaterialKind.RECOVERY_CODE,
                AdminSecurityApi.RecoveryStorageLocation.OFF_PHONE,
                true
            );

        FakeTransport wrongChallenge = loggedInTransport();
        AdminSecurityHttpClient challengeClient = client(wrongChallenge);
        challengeClient.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        wrongChallenge.challengeStatus = 201;
        assertThrows(IOException.class, () -> challengeClient.attestRecoveryCustody(
            ACCESS_TOKEN,
            attestation
        ));
        assertEquals(2, wrongChallenge.challengeRequests.size());
        assertEquals(1, wrongChallenge.requests.size());

        FakeTransport wrongFinal = loggedInTransport();
        wrongFinal.enqueue(201, stateJson("NORMAL", "state-v2"));
        AdminSecurityHttpClient finalClient = client(wrongFinal);
        finalClient.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        assertThrows(IOException.class, () -> finalClient.attestRecoveryCustody(
            ACCESS_TOKEN,
            attestation
        ));
        assertEquals(2, wrongFinal.challengeRequests.size());
        assertEquals(2, wrongFinal.requests.size());
    }

    @Test
    public void securityResponsesRejectDuplicateKeysAndTrailingData() {
        String duplicateAccessToken = "{\"access_token\":\"" + ACCESS_TOKEN + "\"," + loginJson().substring(1);
        FakeTransport duplicate = new FakeTransport();
        duplicate.enqueue(200, duplicateAccessToken);
        assertThrows(IOException.class, () -> client(duplicate)
            .login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"));

        FakeTransport trailing = new FakeTransport();
        trailing.enqueue(200, loginJson() + " trailing");
        assertThrows(IOException.class, () -> client(trailing)
            .login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"));
    }

    @Test
    public void namedAdministratorIdsMayUseTheServerContractEmailForm() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, loginJson());

        client(transport).login("owner@example.com", PASSWORD, "123456", DEVICE_ID, "test phone");

        assertEquals(
            "owner@example.com",
            new JSONObject(transport.requests.get(0).body).getString("admin_id")
        );
    }

    @Test
    public void recoveryCallsCarryAdminContextButNeverBearerAccessCredentials() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"recovery_token\":\"" + RECOVERY_TOKEN + "\",\"security_state\":\"RECOVERY_IN_PROGRESS\"}");
        transport.enqueue(200, loginJson());
        AdminSecurityHttpClient client = client(transport);

        client.startRecovery("admin-01", RECOVERY_CODE, DEVICE_ID, "replacement phone");
        client.completeRecovery(RECOVERY_TOKEN, NEW_PASSWORD, "654321", DEVICE_ID, "replacement phone");

        for (Request request : transport.requests) {
            assertEquals("ADMIN_ANDROID", request.headers.get("X-WalkSafe-App-Kind"));
            assertEquals(DEVICE_ID, request.headers.get("X-WalkSafe-Device-Id"));
            assertFalse(request.headers.containsKey("Authorization"));
        }
        assertTrue(transport.requests.get(0).body.contains(RECOVERY_CODE));
        assertTrue(transport.requests.get(1).body.contains(RECOVERY_TOKEN));
        assertEquals(1, transport.challengeRequests.size());
        JSONObject completionChallenge = new JSONObject(transport.challengeRequests.get(0).body);
        assertEquals("RECOVERY_COMPLETE", completionChallenge.getString("purpose"));
        assertEquals("/admin/security/recovery/complete", completionChallenge.getString("path"));
        assertTrue(completionChallenge.isNull("action"));
        assertTrue(completionChallenge.isNull("read_purpose"));
        assertTrue(completionChallenge.isNull("session_id"));
        assertFalse(transport.requests.get(0).headers.containsKey(AdminDeviceProof.CHALLENGE_ID_HEADER));
        assertEquals(
            AdminDeviceProofTest.CHALLENGE_ID,
            transport.requests.get(1).headers.get(AdminDeviceProof.CHALLENGE_ID_HEADER)
        );
    }

    @Test
    public void recoveryCompletionEstablishesTheNewActionProofSessionBinding() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"recovery_token\":\"" + RECOVERY_TOKEN
            + "\",\"security_state\":\"RECOVERY_IN_PROGRESS\"}");
        transport.enqueue(200, loginJson());
        transport.enqueue(200, stateJson("NORMAL", "state-v2"));
        AdminSecurityHttpClient client = client(transport);
        client.startRecovery("admin-01", RECOVERY_CODE, DEVICE_ID, "replacement phone");
        client.completeRecovery(RECOVERY_TOKEN, NEW_PASSWORD, "654321", DEVICE_ID, "replacement phone");
        AdminSecurityApi.RecoveryCustodyAttestation attestation =
            new AdminSecurityApi.RecoveryCustodyAttestation(
                CUSTODY_REFERENCE,
                AdminSecurityApi.RecoveryMaterialKind.SECURITY_KEY,
                AdminSecurityApi.RecoveryStorageLocation.OFF_PHONE,
                true
            );

        client.attestRecoveryCustody(ACCESS_TOKEN, attestation);

        assertEquals(2, transport.challengeRequests.size());
        assertActionProof(
            transport.challengeRequests.get(1),
            transport.requests.get(2),
            "recovery.custody.attest",
            "/admin/security/recovery-custody/attest"
        );
    }

    @Test
    public void unknownStatesExtraFieldsAndStringExpiryAreRejected() throws Exception {
        FakeTransport extra = new FakeTransport();
        extra.enqueue(200, loginJson().replace("}", ",\"unexpected\":true}"));
        assertThrows(IOException.class, () -> client(extra).login(
            "admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"
        ));

        FakeTransport unknown = loggedInTransport();
        unknown.enqueue(200, stateJson("AUTHENTICATED_NORMAL", "state-v1"));
        AdminSecurityHttpClient unknownClient = client(unknown);
        unknownClient.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        assertThrows(IOException.class, () -> unknownClient.getState(ACCESS_TOKEN));

        FakeTransport expiry = loggedInTransport();
        expiry.enqueue(200, """
            {"reauthenticated_until_epoch_ms":"20000","action":"report.status.update",
             "method":"PATCH","path":"/reports/report-1/status"}
            """);
        AdminSecurityHttpClient expiryClient = client(expiry);
        expiryClient.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        assertThrows(IOException.class, () -> expiryClient.reauthenticate(
            ACCESS_TOKEN,
            PASSWORD,
            "123456",
            "report.status.update",
            "PATCH",
            "/reports/report-1/status",
            RECONFIRMATION_NONCE
        ));
    }

    @Test
    public void custodyStateIsExactTypedAndTimestampConsistent() throws Exception {
        String[] invalidStates = {
            stateJson("NORMAL", "state-v1").replace("\"ATTESTED\"", "\"UNKNOWN\""),
            stateJson("NORMAL", "state-v1").replace(
                ",\"recovery_custody_attested_at\":\"2026-07-21T23:00:00Z\"",
                ""
            ),
            stateJson("NORMAL", "state-v1").replace(
                "\"recovery_custody_attested_at\":\"2026-07-21T23:00:00Z\"",
                "\"recovery_custody_attested_at\":null"
            ),
            stateJson("NORMAL", "state-v1").replace(
                "\"recovery_custody_attested_at\":\"2026-07-21T23:00:00Z\"",
                "\"recovery_custody_attested_at\":123"
            ),
            stateJson("NORMAL", "state-v1", "ATTESTED", "2026-07-21T23:00:00"),
            stateJson("NORMAL", "state-v1", "ATTESTED", "not-a-timestamp"),
            stateJson("NORMAL", "state-v1", "ATTESTED", "2026-07-21T23:00:00+25:00"),
            stateJson("NORMAL", "state-v1").replace(
                "\"observed_at\":\"2026-07-22T00:00:00Z\"",
                "\"observed_at\":\"2026-07-22T00:00:00\""
            ),
            stateJson("NORMAL", "state-v1").replace(
                "\"observed_at\":\"2026-07-22T00:00:00Z\"",
                "\"observed_at\":\"not-a-timestamp\""
            )
        };
        for (String invalidState : invalidStates) {
            FakeTransport transport = loggedInTransport();
            transport.enqueue(200, invalidState);
            AdminSecurityHttpClient client = client(transport);
            client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
            assertThrows(IOException.class, () -> client.getState(ACCESS_TOKEN));
        }

        FakeTransport unconfirmed = loggedInTransport();
        unconfirmed.enqueue(200, stateJson(
            "NORMAL", "state-v1", "UNATTESTED", null
        ));
        AdminSecurityHttpClient client = client(unconfirmed);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        assertEquals(
            AdminRecoveryCustodyState.UNATTESTED,
            client.getState(ACCESS_TOKEN).recoveryCustodyState()
        );

        FakeTransport offset = loggedInTransport();
        offset.enqueue(200, stateJson(
            "NORMAL", "state-v2", "ATTESTED", "2026-07-22T08:00:00+09:00"
        ));
        AdminSecurityHttpClient offsetClient = client(offset);
        offsetClient.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        assertEquals(
            "2026-07-22T08:00:00+09:00",
            offsetClient.getState(ACCESS_TOKEN).recoveryCustodyAttestedAt()
        );
    }

    @Test
    public void legacyExactThreeStateResponseDefaultsCustodyToUnattested() throws Exception {
        FakeTransport transport = loggedInTransport();
        transport.enqueue(200, """
            {"security_state":"NORMAL","state_version":"state-v0",
             "observed_at":"2026-07-22T00:00:00Z"}
            """);
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        AdminSecurityApi.StateSnapshot state = client.getState(ACCESS_TOKEN);

        assertEquals(AdminSecurityState.NORMAL, state.securityState());
        assertEquals("state-v0", state.stateVersion());
        assertEquals(AdminRecoveryCustodyState.UNATTESTED, state.recoveryCustodyState());
        assertEquals(null, state.recoveryCustodyAttestedAt());
    }

    @Test
    public void stateResponseRejectsPartialOrExtendedCompatibilityShapes() throws Exception {
        String legacy = """
            {"security_state":"NORMAL","state_version":"state-v0",
             "observed_at":"2026-07-22T00:00:00Z"}
            """;
        String[] invalidResponses = {
            legacy.replace("}", ",\"recovery_custody_state\":\"UNATTESTED\"}"),
            legacy.replace("}", ",\"unexpected\":true}"),
            stateJson("NORMAL", "state-v1").replace("}", ",\"unexpected\":true}")
        };

        for (String response : invalidResponses) {
            FakeTransport transport = loggedInTransport();
            transport.enqueue(200, response);
            AdminSecurityHttpClient client = client(transport);
            client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

            assertThrows(IOException.class, () -> client.getState(ACCESS_TOKEN));
        }
    }

    @Test
    public void reauthenticationUsesExactActionBoundRequestAndRequiresMatchingResponseEcho() throws Exception {
        FakeTransport transport = loggedInTransport();
        transport.enqueue(200, """
            {"reauthenticated_until_epoch_ms":20000,"action":"report.status.update",
             "method":"PATCH","path":"/reports/report-1/status"}
            """);
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        var result = client.reauthenticate(
            ACCESS_TOKEN,
            PASSWORD,
            "123456",
            "report.status.update",
            "PATCH",
            "/reports/report-1/status",
            RECONFIRMATION_NONCE
        );

        JSONObject body = new JSONObject(transport.requests.get(1).body);
        Set<String> bodyKeys = new java.util.TreeSet<>();
        body.keys().forEachRemaining(bodyKeys::add);
        assertEquals(
            Set.of("password", "totp_code", "action", "method", "path", "nonce"),
            bodyKeys
        );
        assertEquals(RECONFIRMATION_NONCE, body.getString("nonce"));
        assertEquals("report.status.update", result.action());
        assertEquals("PATCH", result.method());
        assertEquals("/reports/report-1/status", result.path());

        FakeTransport mismatch = loggedInTransport();
        mismatch.enqueue(200, """
            {"reauthenticated_until_epoch_ms":20000,"action":"report.status.update",
             "method":"POST","path":"/reports/report-1/status"}
            """);
        AdminSecurityHttpClient mismatchClient = client(mismatch);
        mismatchClient.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        assertThrows(IOException.class, () -> mismatchClient.reauthenticate(
            ACCESS_TOKEN,
            PASSWORD,
            "123456",
            "report.status.update",
            "PATCH",
            "/reports/report-1/status",
            RECONFIRMATION_NONCE
        ));
    }

    @Test
    public void reauthenticationRejectsNonCanonicalNonceBeforeNetworkUse() throws Exception {
        FakeTransport transport = loggedInTransport();
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        for (String nonce : new String[] {
            "A".repeat(21),
            "A".repeat(23),
            "AAECAwQFBgcICQoLDA0ODw==",
            "AAECAwQFBgcICQoLDA0ODx"
        }) {
            assertThrows(IOException.class, () -> client.reauthenticate(
                ACCESS_TOKEN,
                PASSWORD,
                "123456",
                "report.status.update",
                "PATCH",
                "/reports/report-1/status",
                nonce
            ));
        }
        assertEquals(1, transport.requests.size());
    }

    @Test
    public void sessionsRequireExactTypedBoundedItems() throws Exception {
        FakeTransport transport = loggedInTransport();
        transport.enqueue(200, """
            {"sessions":[{
              "session_id":"11111111-1111-4111-8111-111111111111",
              "device_id":"admin-device-12345678-1234-1234-1234-123456789abc",
              "device_label":"test phone",
              "current":true,
              "revoked":false,
              "last_seen_at":"2026-07-22T00:00:00Z"
            }],"devices":[
              {"device_id":"admin-device-12345678-1234-1234-1234-123456789abc","current":true},
              {"device_id":"admin-device-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","current":false}
            ]}
            """);
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        var inventory = client.getDeviceInventory(ACCESS_TOKEN);

        assertEquals(1, inventory.sessions().size());
        assertTrue(inventory.sessions().get(0).isCurrent());
        assertEquals(2, inventory.devices().size());
        assertEquals(
            "admin-device-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            inventory.devices().get(1).deviceId()
        );
        assertFalse(inventory.devices().get(1).isCurrent());
    }

    @Test
    public void legacyExactOneSessionsResponseDerivesActiveDevicesDeterministically() throws Exception {
        FakeTransport transport = loggedInTransport();
        transport.enqueue(200, """
            {"sessions":[{
              "session_id":"11111111-1111-4111-8111-111111111111",
              "device_id":"admin-device-12345678-1234-1234-1234-123456789abc",
              "device_label":"test phone",
              "current":true,
              "revoked":false,
              "last_seen_at":"2026-07-22T00:00:00Z"
            },{
              "session_id":"22222222-2222-4222-8222-222222222222",
              "device_id":"admin-device-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "device_label":"other phone",
              "current":false,
              "revoked":false,
              "last_seen_at":"2026-07-21T23:59:00Z"
            },{
              "session_id":"33333333-3333-4333-8333-333333333333",
              "device_id":"admin-device-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              "device_label":"revoked phone",
              "current":false,
              "revoked":true,
              "last_seen_at":"2026-07-21T23:58:00Z"
            },{
              "session_id":"44444444-4444-4444-8444-444444444444",
              "device_id":"admin-device-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "device_label":"older other session",
              "current":false,
              "revoked":false,
              "last_seen_at":"2026-07-21T23:57:00Z"
            }]}
            """);
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        AdminSecurityApi.DeviceInventory inventory = client.getDeviceInventory(ACCESS_TOKEN);

        assertEquals(4, inventory.sessions().size());
        assertEquals(2, inventory.devices().size());
        assertEquals(DEVICE_ID, inventory.devices().get(0).deviceId());
        assertTrue(inventory.devices().get(0).isCurrent());
        assertEquals(OTHER_DEVICE_ID, inventory.devices().get(1).deviceId());
        assertFalse(inventory.devices().get(1).isCurrent());
    }

    @Test
    public void sessionsResponseRejectsPartialOrExtendedCompatibilityShapes() throws Exception {
        String session = """
            {"session_id":"11111111-1111-4111-8111-111111111111",
             "device_id":"admin-device-12345678-1234-1234-1234-123456789abc",
             "device_label":"test phone","current":true,"revoked":false,
             "last_seen_at":"2026-07-22T00:00:00Z"}
            """;
        String device = "{\"device_id\":\"" + DEVICE_ID + "\",\"current\":true}";
        String[] invalidResponses = {
            "{\"devices\":[" + device + "]}",
            "{\"sessions\":[" + session + "],\"unexpected\":true}",
            "{\"sessions\":[" + session + "],\"devices\":[" + device
                + "],\"unexpected\":true}"
        };

        for (String response : invalidResponses) {
            FakeTransport transport = loggedInTransport();
            transport.enqueue(200, response);
            AdminSecurityHttpClient client = client(transport);
            client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

            assertThrows(IOException.class, () -> client.getDeviceInventory(ACCESS_TOKEN));
        }
    }

    @Test
    public void sessionsRejectAResponseWithoutOneCurrentDeviceBoundSession() throws Exception {
        FakeTransport transport = loggedInTransport();
        transport.enqueue(200, """
            {"sessions":[{
              "session_id":"22222222-2222-4222-8222-222222222222",
              "device_id":"admin-device-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "device_label":"other phone",
              "current":false,
              "revoked":false,
              "last_seen_at":"2026-07-22T00:00:00Z"
            }],"devices":[
              {"device_id":"admin-device-12345678-1234-1234-1234-123456789abc","current":true},
              {"device_id":"admin-device-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","current":false}
            ]}
            """);
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        assertThrows(IOException.class, () -> client.getDeviceInventory(ACCESS_TOKEN));
    }

    @Test
    public void sessionsRejectAnOffsetlessLastSeenTimestamp() throws Exception {
        FakeTransport transport = loggedInTransport();
        transport.enqueue(200, """
            {"sessions":[{
              "session_id":"11111111-1111-4111-8111-111111111111",
              "device_id":"admin-device-12345678-1234-1234-1234-123456789abc",
              "device_label":"test phone",
              "current":true,
              "revoked":false,
              "last_seen_at":"2026-07-22T00:00:00"
            }],"devices":[
              {"device_id":"admin-device-12345678-1234-1234-1234-123456789abc","current":true}
            ]}
            """);
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        assertThrows(IOException.class, () -> client.getDeviceInventory(ACCESS_TOKEN));
    }

    @Test
    public void deviceInventoryRejectsMissingDuplicateOrMismatchedCurrentDevices() throws Exception {
        String session = """
            {"session_id":"11111111-1111-4111-8111-111111111111",
             "device_id":"admin-device-12345678-1234-1234-1234-123456789abc",
             "device_label":"test phone","current":true,"revoked":false,
             "last_seen_at":"2026-07-22T00:00:00Z"}
            """;
        String[] invalidDevices = {
            "[]",
            "[{\"device_id\":\"" + DEVICE_ID + "\",\"current\":true},"
                + "{\"device_id\":\"" + DEVICE_ID + "\",\"current\":false}]",
            "[{\"device_id\":\"" + OTHER_DEVICE_ID + "\",\"current\":true}]",
            "[{\"device_id\":\"" + DEVICE_ID + "\",\"current\":true,\"extra\":false}]"
        };
        for (String devices : invalidDevices) {
            FakeTransport transport = loggedInTransport();
            transport.enqueue(200, "{\"sessions\":[" + session + "],\"devices\":" + devices + "}");
            AdminSecurityHttpClient client = client(transport);
            client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
            assertThrows(IOException.class, () -> client.getDeviceInventory(ACCESS_TOKEN));
        }
    }

    @Test
    public void httpErrorsNeverReflectResponseBodiesOrSubmittedSecrets() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(401, "server accidentally echoed " + PASSWORD);

        IOException error = assertThrows(IOException.class, () -> client(transport).login(
            "admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"
        ));

        assertFalse(error.getMessage().contains(PASSWORD));
        assertEquals("administrator API request failed: status=401", error.getMessage());
    }

    @Test
    public void allowlistedStructuredErrorsExposeOnlyTypedCodesAndBoundedRetrySeconds() {
        String[] wireCodes = {
            "admin_recovery_expired",
            "admin_recovery_invalid",
            "admin_auth_rate_limited",
            "admin_recovery_in_progress",
            "admin_totp_secret_not_replaced",
            "admin_recovery_verification_failed",
            "admin_recovery_device_key_required",
            "admin_device_key_not_active"
        };
        AdminSecurityApiException.Code[] expectedCodes = AdminSecurityApiException.Code.values();

        for (int index = 0; index < wireCodes.length; index++) {
            FakeTransport transport = new FakeTransport();
            transport.enqueue(
                429,
                errorJson(wireCodes[index], "server-only detail " + PASSWORD),
                Map.of("retry-after", "45")
            );

            AdminSecurityApiException error = assertThrows(AdminSecurityApiException.class, () ->
                client(transport).login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone")
            );

            assertEquals(expectedCodes[index], error.code());
            assertEquals(Long.valueOf(45L), error.retryAfterSeconds());
            assertFalse(error.getMessage().contains(PASSWORD));
            assertFalse(error.getMessage().contains("server-only"));
        }
    }

    @Test
    public void unknownMalformedAndExtendedErrorBodiesRemainGenericAndFailClosed() {
        String[] bodies = {
            errorJson("admin_new_unapproved_error", "must stay private"),
            "{\"detail\":{\"code\":\"admin_recovery_expired\"}}",
            "{\"detail\":{\"code\":\"admin_recovery_expired\",\"message\":\"private\",\"extra\":true}}",
            "not-json"
        };

        for (String body : bodies) {
            FakeTransport transport = new FakeTransport();
            transport.enqueue(400, body, Map.of("Retry-After", "99999"));

            IOException error = assertThrows(IOException.class, () -> client(transport).login(
                "admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"
            ));

            assertFalse(error instanceof AdminSecurityApiException);
            assertEquals("administrator API request failed: status=400", error.getMessage());
        }
    }

    @Test
    public void invalidOrExcessiveRetryAfterIsNotPresentedAsTrustedTiming() {
        String[] retryValues = {"0", "3601", "tomorrow", " 45", "45 "};
        for (String retryValue : retryValues) {
            FakeTransport transport = new FakeTransport();
            transport.enqueue(
                429,
                errorJson("admin_auth_rate_limited", "wait"),
                Map.of("Retry-After", retryValue)
            );

            AdminSecurityApiException error = assertThrows(AdminSecurityApiException.class, () ->
                client(transport).login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone")
            );

            assertEquals(null, error.retryAfterSeconds());
        }
    }

    @Test
    public void passwordLengthMatchesTheServerTwelveToTwoHundredFiftySixContract() {
        FakeTransport transport = new FakeTransport();
        AdminSecurityHttpClient client = client(transport);
        String tooLong = new String(new char[257]).replace('\0', 'x');

        assertThrows(IOException.class, () -> client.login(
            "admin-01", "12345678901", "123456", DEVICE_ID, "test phone"
        ));
        assertThrows(IOException.class, () -> client.login(
            "admin-01", tooLong, "123456", DEVICE_ID, "test phone"
        ));
        assertTrue(transport.requests.isEmpty());
    }

    private static AdminSecurityHttpClient client(FakeTransport transport) {
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

    private static FakeTransport loggedInTransport() {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, loginJson());
        return transport;
    }

    private static String loginJson() {
        return "{\"access_token\":\"" + ACCESS_TOKEN
            + "\",\"security_state\":\"NORMAL\",\"current_session_id\":\"11111111-1111-4111-8111-111111111111\"}";
    }

    private static String stateJson(String state, String version) {
        return stateJson(state, version, "ATTESTED", "2026-07-21T23:00:00Z");
    }

    private static String stateJson(
        String state,
        String version,
        String custodyState,
        String custodyAttestedAt
    ) {
        String attestedAt = custodyAttestedAt == null ? "null" : "\"" + custodyAttestedAt + "\"";
        return "{\"security_state\":\"" + state + "\",\"state_version\":\"" + version
            + "\",\"observed_at\":\"2026-07-22T00:00:00Z\","
            + "\"recovery_custody_state\":\"" + custodyState + "\","
            + "\"recovery_custody_attested_at\":" + attestedAt + "}";
    }

    private static Set<String> keys(JSONObject value) {
        Set<String> keys = new java.util.HashSet<>();
        value.keys().forEachRemaining(keys::add);
        return keys;
    }

    private static void assertActionProof(
        Request challengeRequest,
        Request operationRequest,
        String action,
        String path
    ) throws Exception {
        assertEquals("POST", challengeRequest.method);
        assertEquals(
            "http://127.0.0.1:8000" + AdminOperationsHttpClient.CHALLENGE_PATH,
            challengeRequest.url
        );
        assertEquals("Bearer " + ACCESS_TOKEN, challengeRequest.headers.get("Authorization"));
        assertEquals(DEVICE_ID, challengeRequest.headers.get("X-WalkSafe-Device-Id"));
        JSONObject challenge = new JSONObject(challengeRequest.body);
        assertEquals(Set.of(
            "action", "admin_id", "body_sha256", "correlation_id", "device_id",
            "device_key_marker", "device_key_version", "method", "path", "purpose",
            "query_sha256", "read_purpose", "session_id"
        ), keys(challenge));
        assertEquals("ACTION", challenge.getString("purpose"));
        assertEquals(action, challenge.getString("action"));
        assertEquals("admin-01", challenge.getString("admin_id"));
        assertEquals("11111111-1111-4111-8111-111111111111", challenge.getString("session_id"));
        assertEquals(DEVICE_ID, challenge.getString("device_id"));
        assertEquals("POST", challenge.getString("method"));
        assertEquals(path, challenge.getString("path"));
        assertTrue(challenge.isNull("read_purpose"));
        assertEquals(
            AdminCanonicalEncoding.sha256Hex(operationRequest.body.getBytes(StandardCharsets.UTF_8)),
            challenge.getString("body_sha256")
        );
        assertEquals(
            AdminCanonicalEncoding.sha256Hex(new byte[0]),
            challenge.getString("query_sha256")
        );
        assertEquals(
            challenge.getString("correlation_id"),
            operationRequest.headers.get(AdminOperationsHttpClient.CORRELATION_ID_HEADER)
        );
        assertEquals(
            AdminDeviceProofTest.CHALLENGE_ID,
            operationRequest.headers.get(AdminDeviceProof.CHALLENGE_ID_HEADER)
        );
        assertTrue(operationRequest.headers.containsKey(AdminDeviceProof.SIGNATURE_HEADER));
    }

    private static String errorJson(String code, String message) {
        return new JSONObject(Map.of("detail", Map.of("code", code, "message", message))).toString();
    }

    private static final class FakeTransport implements AdminSecurityHttpClient.Transport {
        final Deque<AdminSecurityHttpClient.Response> responses = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();
        final List<Request> challengeRequests = new ArrayList<>();
        int challengeStatus = 200;

        void enqueue(int status, String body) {
            responses.addLast(new AdminSecurityHttpClient.Response(status, body));
        }

        void enqueue(int status, String body, Map<String, String> headers) {
            responses.addLast(new AdminSecurityHttpClient.Response(status, body, headers));
        }

        @Override
        public AdminSecurityHttpClient.Response execute(
            String method,
            String url,
            Map<String, String> headers,
            byte[] body
        ) {
            Request request = new Request(
                method,
                url,
                headers,
                body == null ? "" : new String(body, StandardCharsets.UTF_8)
            );
            if (url.endsWith(AdminOperationsHttpClient.CHALLENGE_PATH)) {
                challengeRequests.add(request);
                return challengeResponse(request.body);
            }
            requests.add(request);
            return responses.removeFirst();
        }

        private AdminSecurityHttpClient.Response challengeResponse(String requestBody) {
            try {
                JSONObject request = new JSONObject(requestBody);
                Map<String, Object> exact18 = new java.util.LinkedHashMap<>();
                exact18.put("action", request.isNull("action") ? null : request.getString("action"));
                exact18.put("admin_id", request.getString("admin_id"));
                exact18.put("body_sha256", request.getString("body_sha256"));
                exact18.put("challenge_id", AdminDeviceProofTest.CHALLENGE_ID);
                exact18.put("correlation_id", request.getString("correlation_id"));
                exact18.put("device_id", request.getString("device_id"));
                exact18.put("device_key_marker", request.getString("device_key_marker"));
                exact18.put("device_key_version", request.getInt("device_key_version"));
                exact18.put(
                    "expires_at_epoch_ms",
                    AdminDeviceProofTest.ISSUED_AT + AdminDeviceProof.CHALLENGE_TTL_MS
                );
                exact18.put("issued_at_epoch_ms", AdminDeviceProofTest.ISSUED_AT);
                exact18.put("method", request.getString("method"));
                exact18.put("nonce", AdminDeviceProofTest.NONCE);
                exact18.put("path", request.getString("path"));
                exact18.put("purpose", request.getString("purpose"));
                exact18.put("query_sha256", request.getString("query_sha256"));
                exact18.put("read_purpose", request.isNull("read_purpose") ? null : request.getString("read_purpose"));
                exact18.put("schema_version", AdminDeviceProof.SCHEMA_VERSION);
                exact18.put("session_id", request.isNull("session_id") ? null : request.getString("session_id"));
                Map<String, Object> exact19 = new java.util.LinkedHashMap<>(exact18);
                exact19.put("signing_payload", AdminCanonicalEncoding.canonicalJson(exact18));
                return new AdminSecurityHttpClient.Response(
                    challengeStatus,
                    AdminCanonicalEncoding.canonicalJson(exact19)
                );
            } catch (Exception error) {
                throw new AssertionError("failed to build device challenge", error);
            }
        }
    }

    private static final class Request {
        final String method;
        final String url;
        final Map<String, String> headers;
        final String body;

        Request(String method, String url, Map<String, String> headers, String body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final String DEVICE_ID = "admin-device-12345678-1234-1234-1234-123456789abc";
    private static final String OTHER_DEVICE_ID = "admin-device-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String NEW_PASSWORD = "new correct horse battery staple";
    private static final String RECOVERY_CODE = "recovery-code-for-tests-123456";
    private static final String ACCESS_TOKEN = "opaque-access-token-for-tests-123456";
    private static final String RECOVERY_TOKEN = "opaque-recovery-token-for-tests-123456";
    private static final String RECONFIRMATION_NONCE = "AAECAwQFBgcICQoLDA0ODw";
    private static final String CUSTODY_REFERENCE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
}
