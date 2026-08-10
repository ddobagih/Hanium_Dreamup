package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdminHighRiskActionGateTest {
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
                var decision = AdminHighRiskActionGate.evaluate(action, state, 20_000L, 10_000L, true);
                assertFalse(decision.isAllowed());
                assertEquals("administrator_access_not_normal", decision.reason());
            }
        }
    }

    @Test
    public void operationalBuildGateStaysLockedAfterSuccessfulMfaAndReauthentication() {
        var decision = AdminHighRiskActionGate.evaluate(
            AdminHighRiskActionGate.Action.DATA_DELETE,
            AdminSecurityState.NORMAL,
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
            "report.status.update",
            "PATCH",
            "/reports/report-1/status",
            "AAECAwQFBgcICQoLDA0ODw",
            20_000L
        );
        var expired = AdminHighRiskActionGate.evaluate(
            "report.status.update",
            "PATCH",
            "/reports/report-1/status",
            AdminSecurityState.NORMAL,
            binding,
            20_000L,
            true
        );
        var mismatch = AdminHighRiskActionGate.evaluate(
            "report.status.update",
            "POST",
            "/reports/report-1/status",
            AdminSecurityState.NORMAL,
            binding,
            10_000L,
            true
        );
        var current = AdminHighRiskActionGate.evaluate(
            "report.status.update",
            "PATCH",
            "/reports/report-1/status",
            AdminSecurityState.NORMAL,
            binding,
            10_000L,
            true
        );

        assertFalse(expired.isAllowed());
        assertEquals("recent_reauthentication_required", expired.reason());
        assertFalse(mismatch.isAllowed());
        assertEquals("reconfirmation_binding_mismatch", mismatch.reason());
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
                    "report.status.update",
                    "PATCH",
                    "/reports/report-1/status",
                    nonce,
                    20_000L
                )
            );
        }
    }
}
