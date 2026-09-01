package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdminHighRiskActionGateTest {
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
}
