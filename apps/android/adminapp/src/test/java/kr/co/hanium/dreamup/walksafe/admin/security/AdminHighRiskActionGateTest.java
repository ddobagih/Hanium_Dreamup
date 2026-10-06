package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdminHighRiskActionGateTest {
    @Test
    public void changedOrMissingBodyCannotReuseTheSameActionApproval() {
        var base = AdminHighRiskActionGate.reportStatus("11111111-1111-4111-8111-111111111111");
        byte[] body = AdminReportHttpClient.statusBody("reviewed", 1);
        var approved = AdminHighRiskActionGate.bindBody(base, body);
        var binding = new AdminHighRiskActionGate.Binding(approved, "AAECAwQFBgcICQoLDA0ODw", 20_000L);
        body[0] = 0; // Mutating a caller buffer cannot change the approved digest.
        for (var candidate : new AdminHighRiskActionGate.Operation[] {
            base,
            AdminHighRiskActionGate.bindBody(base, AdminReportHttpClient.statusBody("resolved", 1)),
            AdminHighRiskActionGate.bindBody(base, AdminReportHttpClient.statusBody("reviewed", 2))
        }) {
            var result = AdminHighRiskActionGate.evaluate(candidate, AdminSecurityState.NORMAL,
                AdminRecoveryCustodyState.ATTESTED, binding, 10_000L, true);
            assertFalse(result.isAllowed());
            assertEquals("reconfirmation_binding_mismatch", result.reason());
        }
        assertTrue(AdminHighRiskActionGate.evaluate(
            AdminHighRiskActionGate.bindBody(base, AdminReportHttpClient.statusBody("reviewed", 1)),
            AdminSecurityState.NORMAL, AdminRecoveryCustodyState.ATTESTED,
            binding, 10_000L, true).isAllowed());
    }

    @Test
    public void actionCatalogIsExactlyTheThreeCanonicalTriples() {
        String[][] expected = {
            {"RELEASE_APPROVAL", "release.approval", "POST", "/admin/operations/release-approvals"},
            {"PRIVILEGE_CHANGE", "privilege.change", "POST", "/admin/operations/privilege-changes"},
            {"DATA_DELETE", "data.delete", "POST", "/admin/operations/data-deletions"}
        };
        AdminHighRiskActionGate.Action[] actions = AdminHighRiskActionGate.Action.values();

        assertEquals(3, actions.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index][0], actions[index].name());
            assertEquals(expected[index][1], actions[index].reauthenticationAction());
            assertEquals(expected[index][2], actions[index].method());
            assertEquals(expected[index][3], actions[index].path());
            assertEquals(
                actions[index],
                AdminHighRiskActionGate.Action.fromExactTriple(
                    expected[index][0], expected[index][2], expected[index][3]
                )
            );
        }
    }

    @Test
    public void arbitraryOrCrossPairedTriplesAreRejected() {
        String[][] rejected = {
            {"report.status.update", "PATCH", "/reports/report-1/status"},
            {"RELEASE_APPROVAL", "GET", "/admin/operations/release-approvals"},
            {"RELEASE_APPROVAL", "POST", "/admin/operations/privilege-changes"},
            {"PRIVILEGE_CHANGE", "POST", "/admin/security/privilege-changes"},
            {"DATA_DELETE", "POST", "/admin/security/data-deletions"}
        };
        for (String[] triple : rejected) {
            assertThrows(
                IllegalArgumentException.class,
                () -> AdminHighRiskActionGate.Action.fromExactTriple(
                    triple[0], triple[1], triple[2]
                )
            );
        }
    }

    @Test
    public void recoveryAndUnknownStatesAlwaysFreezeEveryHighRiskAction() {
        for (AdminSecurityState state : new AdminSecurityState[] {
            AdminSecurityState.SIGNED_OUT,
            AdminSecurityState.AUTHENTICATING,
            AdminSecurityState.RECOVERY_REQUIRED,
            AdminSecurityState.RECOVERY_IN_PROGRESS,
            AdminSecurityState.FAIL_CLOSED
        }) {
            for (AdminHighRiskActionGate.Action action : AdminHighRiskActionGate.Action.values()) {
                var decision = AdminHighRiskActionGate.evaluate(
                    action,
                    state,
                    AdminRecoveryCustodyState.ATTESTED,
                    20_000L,
                    10_000L,
                    true
                );
                assertFalse(decision.isAllowed());
                assertEquals("administrator_access_not_normal", decision.reason());
            }
        }
    }

    @Test
    public void normalAuthenticationStillFreezesHighRiskActionsWithoutConfirmedCustody() {
        for (AdminRecoveryCustodyState custodyState : new AdminRecoveryCustodyState[] {
            null,
            AdminRecoveryCustodyState.UNATTESTED
        }) {
            var decision = AdminHighRiskActionGate.evaluate(
                AdminHighRiskActionGate.Action.RELEASE_APPROVAL,
                AdminSecurityState.NORMAL,
                custodyState,
                20_000L,
                10_000L,
                true
            );
            assertFalse(decision.isAllowed());
            assertEquals("recovery_custody_not_attested", decision.reason());
        }
    }

    @Test
    public void operationalBuildGateStaysLockedAfterSuccessfulMfaAndReauthentication() {
        var decision = AdminHighRiskActionGate.evaluate(
            AdminHighRiskActionGate.Action.DATA_DELETE,
            AdminSecurityState.NORMAL,
            AdminRecoveryCustodyState.ATTESTED,
            20_000L,
            10_000L,
            false
        );

        assertFalse(decision.isAllowed());
        assertEquals("operational_workflows_locked", decision.reason());
    }

    @Test
    public void exactBindingRejectsExpiryAndMismatchAndCarriesOnlyTheNonceHeader() {
        var binding = new AdminHighRiskActionGate.Binding(
            AdminHighRiskActionGate.Action.DATA_DELETE,
            "AAECAwQFBgcICQoLDA0ODw",
            20_000L
        );
        var expired = AdminHighRiskActionGate.evaluate(
            AdminHighRiskActionGate.Action.DATA_DELETE,
            AdminSecurityState.NORMAL,
            AdminRecoveryCustodyState.ATTESTED,
            binding,
            20_000L,
            true
        );
        var mismatch = AdminHighRiskActionGate.evaluate(
            AdminHighRiskActionGate.Action.PRIVILEGE_CHANGE,
            AdminSecurityState.NORMAL,
            AdminRecoveryCustodyState.ATTESTED,
            binding,
            10_000L,
            true
        );
        var current = AdminHighRiskActionGate.evaluate(
            AdminHighRiskActionGate.Action.DATA_DELETE,
            AdminSecurityState.NORMAL,
            AdminRecoveryCustodyState.ATTESTED,
            binding,
            10_000L,
            true
        );
        var custodyUnknown = AdminHighRiskActionGate.evaluate(
            AdminHighRiskActionGate.Action.DATA_DELETE,
            AdminSecurityState.NORMAL,
            null,
            binding,
            10_000L,
            true
        );

        assertFalse(expired.isAllowed());
        assertEquals("recent_reauthentication_required", expired.reason());
        assertFalse(mismatch.isAllowed());
        assertEquals("reconfirmation_binding_mismatch", mismatch.reason());
        assertFalse(custodyUnknown.isAllowed());
        assertEquals("recovery_custody_not_attested", custodyUnknown.reason());
        assertTrue(current.isAllowed());
        assertEquals(
            "AAECAwQFBgcICQoLDA0ODw",
            current.requestHeaders().get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER)
        );
    }

    @Test
    public void nonceMustBeCanonicalUnpaddedBase64UrlForExactlySixteenBytes() {
        for (String nonce : new String[] {
            "A".repeat(21),
            "A".repeat(23),
            "AAECAwQFBgcICQoLDA0ODw==",
            "AAECAwQFBgcICQoLDA0ODx"
        }) {
            assertThrows(
                IllegalArgumentException.class,
                () -> new AdminHighRiskActionGate.Binding(
                    AdminHighRiskActionGate.Action.RELEASE_APPROVAL,
                    nonce,
                    20_000L
                )
            );
        }
    }

    @Test
    public void nonceValidationMatchesTheBackendContractVectors() {
        // Same table as backend/tests/test_s18_13_reconfirmation_nonce_contract.py.
        String a21 = "A".repeat(21);
        Object[][] vectors = {
            {a21 + "A", true}, {a21 + "Q", true}, {a21 + "g", true}, {a21 + "w", true},
            {"-_".repeat(10) + "A" + "w", true}, {a21, false}, {"A".repeat(23), false},
            {"A".repeat(128), false}, {a21 + "B", false}, {a21 + "R", false},
            {a21 + "h", false}, {a21 + "x", false}, {a21 + "=", false},
            {a21 + "+", false}, {a21 + "/", false},
        };
        for (Object[] vector : vectors) {
            assertEquals(
                (String) vector[0],
                vector[1],
                AdminHighRiskActionGate.isCanonicalNonce((String) vector[0])
            );
        }
    }

    @Test
    public void reportRequestStatusRequiresExactStepUpBinding() {
        String requestId = "88888888-8888-4888-8888-888888888888";
        AdminHighRiskActionGate.Operation operation =
            AdminHighRiskActionGate.reportRequestStatus(requestId);
        assertEquals("admin.report_request.status.update", operation.reauthenticationAction());
        assertEquals("PATCH", operation.method());
        assertEquals("/admin/report-requests/" + requestId + "/status", operation.path());

        var withoutStepUp = AdminHighRiskActionGate.evaluate(
            operation,
            AdminSecurityState.NORMAL,
            AdminRecoveryCustodyState.ATTESTED,
            (AdminHighRiskActionGate.Binding) null,
            10_000L,
            true
        );
        assertFalse(withoutStepUp.isAllowed());
        assertEquals("reconfirmation_binding_required", withoutStepUp.reason());
    }

    @Test
    public void incidentStatusUsesOnlyTheCanonicalHighRiskBinding() {
        String incidentId = "99999999-9999-4999-8999-999999999999";
        AdminHighRiskActionGate.Operation operation =
            AdminHighRiskActionGate.incidentStatus(incidentId);

        assertEquals("admin.incident.status.update", operation.reauthenticationAction());
        assertEquals("PATCH", operation.method());
        assertEquals("/admin/incidents/" + incidentId + "/status", operation.path());
        assertThrows(
            IllegalArgumentException.class,
            () -> AdminHighRiskActionGate.incidentStatus("not-a-canonical-uuid")
        );
    }

    @Test
    public void rawReviewUsesResourceBoundDecisionAndLegalHoldTriples() {
        String collectionId = "11111111-1111-4111-8111-111111111111";
        AdminHighRiskActionGate.Operation decision =
            AdminHighRiskActionGate.rawCollectionPurposeDecision(collectionId);
        AdminHighRiskActionGate.Operation hold =
            AdminHighRiskActionGate.rawCollectionLegalHold(collectionId);
        AdminHighRiskActionGate.Operation list = AdminHighRiskActionGate.rawCollectionList();

        assertEquals("admin.raw_collection.list", list.reauthenticationAction());
        assertEquals("GET", list.method());
        assertEquals("/admin/raw-collections/quarantine", list.path());
        assertEquals("admin.raw_collection.purpose_decide", decision.reauthenticationAction());
        assertEquals("POST", decision.method());
        assertEquals(
            "/admin/raw-collections/" + collectionId + "/decisions",
            decision.path()
        );
        assertEquals("admin.raw_collection.legal_hold", hold.reauthenticationAction());
        assertEquals(
            "/admin/raw-collections/" + collectionId + "/legal-holds",
            hold.path()
        );
        var binding = new AdminHighRiskActionGate.Binding(
            decision, "AAECAwQFBgcICQoLDA0ODw", 20_000L
        );
        assertTrue(AdminHighRiskActionGate.evaluate(
            decision,
            AdminSecurityState.NORMAL,
            AdminRecoveryCustodyState.ATTESTED,
            binding,
            10_000L,
            true
        ).isAllowed());
        assertFalse(AdminHighRiskActionGate.evaluate(
            hold,
            AdminSecurityState.NORMAL,
            AdminRecoveryCustodyState.ATTESTED,
            binding,
            10_000L,
            true
        ).isAllowed());
        assertThrows(
            IllegalArgumentException.class,
            () -> AdminHighRiskActionGate.rawCollectionLegalHold("not-a-canonical-uuid")
        );
    }

    @Test
    public void originalEvidenceUsesOnlyTheCanonicalReportBoundGrantTriple() {
        String reportId = "11111111-1111-4111-8111-111111111111";
        AdminHighRiskActionGate.Operation operation =
            AdminHighRiskActionGate.originalEvidence(reportId);

        assertEquals("report.original.grant", operation.reauthenticationAction());
        assertEquals("POST", operation.method());
        assertEquals("/reports/" + reportId + "/original-access-grants", operation.path());
        assertThrows(
            IllegalArgumentException.class,
            () -> AdminHighRiskActionGate.originalEvidence("not-a-canonical-uuid")
        );

        var binding = new AdminHighRiskActionGate.Binding(
            operation,
            "AAECAwQFBgcICQoLDA0ODw",
            20_000L
        );
        assertTrue(AdminHighRiskActionGate.evaluate(
            operation,
            AdminSecurityState.NORMAL,
            AdminRecoveryCustodyState.ATTESTED,
            binding,
            10_000L,
            true
        ).isAllowed());
        assertFalse(AdminHighRiskActionGate.evaluate(
            AdminHighRiskActionGate.originalEvidence(
                "22222222-2222-4222-8222-222222222222"
            ),
            AdminSecurityState.NORMAL,
            AdminRecoveryCustodyState.ATTESTED,
            binding,
            10_000L,
            true
        ).isAllowed());
    }

    @Test
    public void s18_29_lostDeviceReportIsAnExactDeviceBoundHighRiskOperation() {
        AdminHighRiskActionGate.Operation operation =
            AdminHighRiskActionGate.deviceReportLost("android-device-0002");
        assertEquals("device.report_lost", operation.reauthenticationAction());
        assertEquals("POST", operation.method());
        assertEquals("/admin/security/devices/android-device-0002/report-lost", operation.path());
        for (String invalid : new String[] {null, "short", "../admin-device-1234", "device id 1234"}) {
            assertThrows(IllegalArgumentException.class, () ->
                AdminHighRiskActionGate.deviceReportLost(invalid));
        }
        // A grant for one device never authorizes reporting another device lost.
        AdminHighRiskActionGate.Binding binding = new AdminHighRiskActionGate.Binding(
            operation, "AAECAwQFBgcICQoLDA0ODw", 20_000L
        );
        AdminHighRiskActionGate.Decision mismatch = AdminHighRiskActionGate.evaluate(
            AdminHighRiskActionGate.deviceReportLost("android-device-0003"),
            AdminSecurityState.NORMAL,
            AdminRecoveryCustodyState.ATTESTED,
            binding,
            1_000L,
            true
        );
        assertFalse(mismatch.isAllowed());
        assertEquals("reconfirmation_binding_mismatch", mismatch.reason());
    }
}
