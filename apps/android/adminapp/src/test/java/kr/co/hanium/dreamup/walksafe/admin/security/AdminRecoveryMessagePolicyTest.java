package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

public final class AdminRecoveryMessagePolicyTest {
    @Test
    public void custodyVersionConflictGivesFreshStateGuidanceWithoutClaimingSuccess() {
        String guidance = message(
            AdminRecoveryMessagePolicy.Phase.CUSTODY_ATTESTATION,
            AdminSecurityApiException.Code.CUSTODY_STATE_VERSION_CONFLICT
        );
        assertTrue(guidance.contains("이전 시도는 폐기"));
        assertTrue(guidance.contains("다시 로그인"));
        assertFalse(guidance.contains("기록했습니다"));
    }

    @Test
    public void recoveryStartErrorsGiveSafeNextActions() {
        String expired = message(
            AdminRecoveryMessagePolicy.Phase.RECOVERY_START,
            AdminSecurityApiException.Code.RECOVERY_EXPIRED
        );
        String invalid = message(
            AdminRecoveryMessagePolicy.Phase.RECOVERY_START,
            AdminSecurityApiException.Code.RECOVERY_INVALID
        );
        String inProgress = message(
            AdminRecoveryMessagePolicy.Phase.RECOVERY_START,
            AdminSecurityApiException.Code.RECOVERY_IN_PROGRESS
        );
        String deviceKeyRequired = message(
            AdminRecoveryMessagePolicy.Phase.RECOVERY_START,
            AdminSecurityApiException.Code.RECOVERY_DEVICE_KEY_REQUIRED
        );

        assertTrue(expired.contains("같은 복구코드"));
        assertTrue(expired.contains("이 기기"));
        assertTrue(invalid.contains("휴대전화 밖에 보관한 복구자료"));
        assertTrue(inProgress.contains("처음 사용한 기기"));
        assertTrue(deviceKeyRequired.contains("기기 키가 서버에 등록되지 않았습니다"));
        assertTrue(deviceKeyRequired.contains("공개키 등록 정보"));
        assertTrue(deviceKeyRequired.contains("로컬 운영자"));
        assertTrue(deviceKeyRequired.contains("복구 시작을 다시 시도"));
    }

    @Test
    public void recoveryCompletionErrorsGiveSafeNextActions() {
        String expired = message(
            AdminRecoveryMessagePolicy.Phase.RECOVERY_COMPLETE,
            AdminSecurityApiException.Code.RECOVERY_EXPIRED
        );
        String secret = message(
            AdminRecoveryMessagePolicy.Phase.RECOVERY_COMPLETE,
            AdminSecurityApiException.Code.TOTP_SECRET_NOT_REPLACED
        );
        String verification = message(
            AdminRecoveryMessagePolicy.Phase.RECOVERY_COMPLETE,
            AdminSecurityApiException.Code.RECOVERY_VERIFICATION_FAILED
        );
        String deviceKeyNotActive = message(
            AdminRecoveryMessagePolicy.Phase.RECOVERY_COMPLETE,
            AdminSecurityApiException.Code.DEVICE_KEY_NOT_ACTIVE
        );

        assertTrue(expired.contains("복구 시작부터"));
        assertTrue(secret.contains("별도 관리 경로"));
        assertTrue(secret.contains("새 값으로 교체"));
        assertTrue(verification.contains("새 비밀번호"));
        assertTrue(verification.contains("6자리 코드"));
        assertTrue(deviceKeyNotActive.contains("기기 키가 활성 상태가 아닙니다"));
        assertTrue(deviceKeyNotActive.contains("공개키 등록 정보"));
        assertTrue(deviceKeyNotActive.contains("활성 키로 등록"));
        assertTrue(deviceKeyNotActive.contains("복구 완료를 다시 시도"));
    }

    @Test
    public void rateLimitUsesOnlyValidatedRetrySeconds() {
        assertEquals(
            "요청이 너무 잦습니다. 45초 후 다시 시도하세요.",
            AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.RECOVERY_START,
                new AdminSecurityApiException(AdminSecurityApiException.Code.RATE_LIMITED, 45L)
            )
        );
        assertEquals(
            "요청이 너무 잦습니다. 잠시 기다린 뒤 다시 시도하세요.",
            AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.RECOVERY_COMPLETE,
                new AdminSecurityApiException(AdminSecurityApiException.Code.RATE_LIMITED, null)
            )
        );
    }

    @Test
    public void s18_19TimeoutSaysOutcomeUnknownAndIsNotShownAsAnAuthenticationFailure() {
        String generic = "요청을 확인하지 못했습니다. 안전을 위해 관리자 업무를 잠갔습니다.";
        for (AdminRecoveryMessagePolicy.Phase phase : new AdminRecoveryMessagePolicy.Phase[] {
            AdminRecoveryMessagePolicy.Phase.GENERAL,
            AdminRecoveryMessagePolicy.Phase.RECOVERY_START,
            AdminRecoveryMessagePolicy.Phase.RECOVERY_COMPLETE
        }) {
            String timeout = AdminRecoveryMessagePolicy.failureMessage(
                phase, new java.net.SocketTimeoutException(AdminHttpRequestDeadline.DEADLINE_MESSAGE)
            );
            assertEquals(AdminRecoveryMessagePolicy.OUTCOME_UNKNOWN_TIMEOUT, timeout);
            assertTrue(timeout.contains("처리했는지 알 수 없어"));
            assertTrue(timeout.contains("자동으로 다시 보내지 않았고"));
            assertFalse(timeout.contains("비밀번호"));
            assertFalse(timeout.contains("server outcome"));
            // A server rejection keeps the existing generic text (it is not "unknown").
            assertEquals(generic, AdminRecoveryMessagePolicy.failureMessage(
                phase, new IOException("administrator API request failed with status 401")
            ));
        }
        // Phase-specific safety outcomes stay as they were.
        assertFalse(AdminRecoveryMessagePolicy.failureMessage(
            AdminRecoveryMessagePolicy.Phase.LOST_DEVICE_REPORT, new java.net.SocketTimeoutException("t")
        ).equals(AdminRecoveryMessagePolicy.OUTCOME_UNKNOWN_TIMEOUT));
        assertFalse(AdminRecoveryMessagePolicy.failureMessage(
            AdminRecoveryMessagePolicy.Phase.CUSTODY_ATTESTATION, new java.net.SocketTimeoutException("t")
        ).equals(AdminRecoveryMessagePolicy.OUTCOME_UNKNOWN_TIMEOUT));
    }

    @Test
    public void s18_18FailedLogoutReportsTheLocalLockAndTheUnconfirmedServerRevokeSeparately() {
        Throwable[] failures = {
            new java.net.SocketTimeoutException(AdminHttpRequestDeadline.DEADLINE_MESSAGE),
            new IOException("administrator HTTP request was cancelled; server outcome unknown"),
            new IOException("administrator API request failed with status 401"),
            new AdminSecurityApiException(AdminSecurityApiException.Code.RATE_LIMITED, 30L),
            new IllegalStateException("administrator session is unavailable")
        };
        for (Throwable failure : failures) {
            // adm-F6: the controller no longer retains a session (it cleared it on the failure).
            String shown = AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.CURRENT_SESSION_LOGOUT, failure, false
            );
            assertEquals(AdminRecoveryMessagePolicy.LOGOUT_SERVER_REVOKE_UNCONFIRMED, shown);
            assertTrue(shown.startsWith("이 기기에서는 로그아웃해 관리자 업무를 잠그고"));
            assertTrue(shown.contains("서버 세션 폐기는 확인하지 못했습니다"));
            assertTrue(shown.contains("세션 목록"));
            assertFalse(shown.contains("30초"));
        }
    }

    @Test
    public void admF6LogoutTextNeverClaimsALocalDeletionWhileTheSessionIsRetained() {
        // Before adm-F6 every logout failure, including a pre-check failure that left the
        // session in place, claimed "보관하던 세션 값을 지웠습니다" (over-claim).
        Throwable[] failures = {
            new IllegalStateException("administrator access is locally locked"),
            new IllegalStateException("administrator session is unavailable"),
            new IOException("invalid_session_id"),
            new IOException("invalid_admin_id"),
            new java.net.SocketTimeoutException(AdminHttpRequestDeadline.DEADLINE_MESSAGE),
            new AdminSecurityApiException(AdminSecurityApiException.Code.RATE_LIMITED, 30L)
        };
        for (Throwable failure : failures) {
            String retained = AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.CURRENT_SESSION_LOGOUT, failure, true
            );
            assertEquals(failure.toString(), "요청을 확인하지 못했습니다. 안전을 위해 관리자 업무를 잠갔습니다.", retained);
            assertFalse(retained.contains("세션 값을 지웠습니다"));
            // Without the controller's answer the text must not claim the deletion either.
            assertEquals(retained, AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.CURRENT_SESSION_LOGOUT, failure));
        }
        // Other phases do not depend on the flag.
        IOException rejected = new IOException("administrator API request failed with status 401");
        for (AdminRecoveryMessagePolicy.Phase phase : AdminRecoveryMessagePolicy.Phase.values()) {
            if (phase == AdminRecoveryMessagePolicy.Phase.CURRENT_SESSION_LOGOUT) continue;
            assertEquals(phase.name(), AdminRecoveryMessagePolicy.failureMessage(phase, rejected, true),
                AdminRecoveryMessagePolicy.failureMessage(phase, rejected, false));
        }
    }

    @Test
    public void unknownErrorsAndCodesUsedInTheWrongPhaseStayGeneric() {
        String generic = "요청을 확인하지 못했습니다. 안전을 위해 관리자 업무를 잠갔습니다.";
        assertEquals(
            generic,
            AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.RECOVERY_START,
                new IOException("server body must not be shown")
            )
        );
        String wrongPhase = AdminRecoveryMessagePolicy.failureMessage(
            AdminRecoveryMessagePolicy.Phase.RECOVERY_START,
            new AdminSecurityApiException(
                AdminSecurityApiException.Code.TOTP_SECRET_NOT_REPLACED,
                null
            )
        );
        assertEquals(generic, wrongPhase);
        assertEquals(
            generic,
            message(
                AdminRecoveryMessagePolicy.Phase.RECOVERY_START,
                AdminSecurityApiException.Code.DEVICE_KEY_NOT_ACTIVE
            )
        );
        assertEquals(
            generic,
            message(
                AdminRecoveryMessagePolicy.Phase.RECOVERY_COMPLETE,
                AdminSecurityApiException.Code.RECOVERY_DEVICE_KEY_REQUIRED
            )
        );
        assertFalse(wrongPhase.contains("server"));
    }

    @Test
    public void custodyAndLostDeviceFailuresKeepTheSafetyOutcomeVisible() {
        String custody = AdminRecoveryMessagePolicy.failureMessage(
            AdminRecoveryMessagePolicy.Phase.CUSTODY_ATTESTATION,
            new IOException("private response")
        );
        String lost = AdminRecoveryMessagePolicy.failureMessage(
            AdminRecoveryMessagePolicy.Phase.LOST_DEVICE_REPORT,
            new IOException("private response")
        );

        assertTrue(custody.contains("잠금을 유지"));
        assertTrue(lost.contains("세션·키 폐기를 확인하지 못했습니다"));
        assertFalse(custody.contains("private"));
        assertFalse(lost.contains("private"));
    }

    @Test
    public void w20aR3LostDeviceReauthenticationFailureGivesReauthGuidance() {
        String lost = message(
            AdminRecoveryMessagePolicy.Phase.LOST_DEVICE_REPORT,
            AdminSecurityApiException.Code.REAUTHENTICATION_FAILED
        );

        assertEquals(
            message(
                AdminRecoveryMessagePolicy.Phase.GENERAL,
                AdminSecurityApiException.Code.REAUTHENTICATION_FAILED
            ),
            lost
        );
        assertTrue(lost.contains("비밀번호와 추가 인증 코드를 확인"));
        assertTrue(lost.contains("새 코드가 표시된 뒤 입력"));
        assertFalse(lost.contains("새로고침"));
    }

    @Test
    public void w20aR3LostDeviceTotpReplayGivesFreshCodeGuidance() {
        String lost = message(
            AdminRecoveryMessagePolicy.Phase.LOST_DEVICE_REPORT,
            AdminSecurityApiException.Code.TOTP_REPLAYED
        );

        assertEquals(
            "이 추가 인증 코드는 이미 사용되었습니다. 새 코드가 표시된 뒤 입력하세요.",
            lost
        );
    }

    @Test
    public void w20aR3LocalCustodyGateSendsTheAdminToRecoveryCodeRecovery() {
        AdminRecoveryCustodyRequiredException localGate =
            new AdminRecoveryCustodyRequiredException();

        assertEquals("recovery custody is not attested", localGate.getMessage());
        assertEquals(
            LOST_DEVICE_CUSTODY_REQUIRED,
            AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.LOST_DEVICE_REPORT,
                localGate
            )
        );
        // Other phases keep their existing wording; an untyped IllegalStateException with
        // the same text is not treated as the custody gate.
        assertEquals(
            "요청을 확인하지 못했습니다. 안전을 위해 관리자 업무를 잠갔습니다.",
            AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.GENERAL,
                localGate
            )
        );
        assertTrue(AdminRecoveryMessagePolicy.failureMessage(
            AdminRecoveryMessagePolicy.Phase.LOST_DEVICE_REPORT,
            new IllegalStateException("recovery custody is not attested")
        ).contains("세션·키 폐기를 확인하지 못했습니다"));
    }

    @Test
    public void w20aR3ServerCustodyRequiredSendsTheAdminToRecoveryCodeRecovery() {
        assertEquals(
            AdminSecurityApiException.Code.RECOVERY_CUSTODY_REQUIRED,
            AdminSecurityApiException.Code.fromWireValue("admin_recovery_custody_required")
        );
        assertEquals(
            LOST_DEVICE_CUSTODY_REQUIRED,
            message(
                AdminRecoveryMessagePolicy.Phase.LOST_DEVICE_REPORT,
                AdminSecurityApiException.Code.RECOVERY_CUSTODY_REQUIRED
            )
        );
        assertEquals(
            "요청을 확인하지 못했습니다. 안전을 위해 관리자 업무를 잠갔습니다.",
            message(
                AdminRecoveryMessagePolicy.Phase.GENERAL,
                AdminSecurityApiException.Code.RECOVERY_CUSTODY_REQUIRED
            )
        );
    }

    private static final String LOST_DEVICE_CUSTODY_REQUIRED =
        "복구자료 보관이 확인되지 않아 분실 처리를 할 수 없습니다. "
            + "휴대전화 밖에 보관한 복구코드로 분실 복구를 시작하세요.";

    @Test
    public void exactLocalLoginValidationReasonsGiveInputGuidanceOnlyInGeneralPhase() {
        String[][] cases = {
            {"invalid_admin_id", "관리자 ID를 입력하고 형식을 확인한 뒤 다시 로그인하세요."},
            {"invalid_password", "비밀번호를 12자 이상 256자 이하로 입력한 뒤 다시 로그인하세요."},
            {"invalid_totp_code", "6자리 숫자 추가 인증 코드를 입력한 뒤 다시 로그인하세요."}
        };
        for (String[] testCase : cases) {
            assertEquals(testCase[1], AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.GENERAL,
                new IOException(testCase[0])
            ));
            for (AdminRecoveryMessagePolicy.Phase phase : AdminRecoveryMessagePolicy.Phase.values()) {
                if (phase == AdminRecoveryMessagePolicy.Phase.GENERAL) continue;
                assertEquals(
                    AdminRecoveryMessagePolicy.failureMessage(phase, new IOException("synthetic failure")),
                    AdminRecoveryMessagePolicy.failureMessage(phase, new IOException(testCase[0]))
                );
            }
            assertEquals(
                "요청을 확인하지 못했습니다. 안전을 위해 관리자 업무를 잠갔습니다.",
                AdminRecoveryMessagePolicy.failureMessage(
                    AdminRecoveryMessagePolicy.Phase.GENERAL,
                    new IllegalStateException(testCase[0])
                )
            );
        }
    }

    @Test
    public void partialReasonMatchesAndPrivateServerTextRemainGeneric() {
        String generic = "요청을 확인하지 못했습니다. 안전을 위해 관리자 업무를 잠갔습니다.";
        String[] reasons = {
            " invalid_admin_id", "invalid_admin_id ", "server: invalid_password",
            "invalid_totp_code: synthetic-private-data",
            "{\"detail\":{\"code\":\"invalid_password\",\"message\":\"synthetic-private-data\"}}",
            null
        };
        for (String reason : reasons) {
            String shown = AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.GENERAL,
                new IOException(reason)
            );
            assertEquals(generic, shown);
            assertFalse(shown.contains("synthetic-private-data"));
            assertFalse(shown.contains("invalid_password"));
        }
    }

    @Test
    public void challengeTimeFailureGivesOnlyGeneralPhaseClockGuidance() {
        String guidance = AdminRecoveryMessagePolicy.failureMessage(
            AdminRecoveryMessagePolicy.Phase.GENERAL,
            new AdminDeviceProof.ChallengeTimeException()
        );
        assertEquals("기기의 자동 날짜·시간 설정을 확인하고 새 도전값으로 다시 시도하세요.", guidance);
        assertEquals(
            "요청을 확인하지 못했습니다. 안전을 위해 관리자 업무를 잠갔습니다.",
            AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.RECOVERY_START,
                new AdminDeviceProof.ChallengeTimeException()
            )
        );
        assertFalse(guidance.contains("device challenge"));
    }

    private static String message(
        AdminRecoveryMessagePolicy.Phase phase,
        AdminSecurityApiException.Code code
    ) {
        String value = AdminRecoveryMessagePolicy.failureMessage(
            phase,
            new AdminSecurityApiException(code, null)
        );
        assertFalse(value.isBlank());
        return value;
    }
}
