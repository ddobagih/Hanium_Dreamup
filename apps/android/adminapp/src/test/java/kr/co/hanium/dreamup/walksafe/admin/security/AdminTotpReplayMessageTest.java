package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdminTotpReplayMessageTest {
    @Test
    public void loginReplayUsesTypedServerCodeAndExplainsTheNextStep() {
        assertEquals(
            AdminSecurityApiException.Code.TOTP_REPLAYED,
            AdminSecurityApiException.Code.fromWireValue("admin_totp_replayed")
        );
        String message = AdminRecoveryMessagePolicy.failureMessage(
            AdminRecoveryMessagePolicy.Phase.GENERAL,
            new AdminSecurityApiException(AdminSecurityApiException.Code.TOTP_REPLAYED, null)
        );
        assertEquals(
            "이 추가 인증 코드는 이미 사용되었습니다. 새 코드가 표시된 뒤 입력하세요.",
            message
        );
    }

    @Test
    public void immediateReauthenticationFailureGuidesFreshCodeWithoutClaimingReplayCause() {
        assertEquals(
            AdminSecurityApiException.Code.REAUTHENTICATION_FAILED,
            AdminSecurityApiException.Code.fromWireValue("admin_reauthentication_failed")
        );
        String message = AdminRecoveryMessagePolicy.failureMessage(
            AdminRecoveryMessagePolicy.Phase.GENERAL,
            new AdminSecurityApiException(
                AdminSecurityApiException.Code.REAUTHENTICATION_FAILED, null
            )
        );
        assertTrue(message.contains("비밀번호와 추가 인증 코드를 확인"));
        assertTrue(message.contains("이미 사용된 코드"));
        assertTrue(message.contains("새 코드가 표시된 뒤 입력"));
    }

    @Test
    public void replayCodesDoNotChangeRecoveryPhaseMessages() {
        String generic = "요청을 확인하지 못했습니다. 안전을 위해 관리자 업무를 잠갔습니다.";
        for (AdminSecurityApiException.Code code : new AdminSecurityApiException.Code[] {
            AdminSecurityApiException.Code.TOTP_REPLAYED,
            AdminSecurityApiException.Code.REAUTHENTICATION_FAILED
        }) {
            assertEquals(generic, AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.RECOVERY_START,
                new AdminSecurityApiException(code, null)
            ));
        }
    }
}
