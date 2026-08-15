package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

public final class AdminRecoveryMessagePolicyTest {
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
