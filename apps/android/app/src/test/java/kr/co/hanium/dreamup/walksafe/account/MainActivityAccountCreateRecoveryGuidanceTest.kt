package kr.co.hanium.dreamup.walksafe.account

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityAccountCreateRecoveryGuidanceTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun pendingMarkerPrecedesNetworkAndOnlyCurrentSuccessClearsIt() {
        val create = source.substringAfter("private fun createEmailAccount() {")
            .substringBefore("private fun loginEmailAccount(")
        assertTrue(create.indexOf("emailEnrollmentStore.markCreationPending()") <
            create.indexOf("gatewaySessionExecutor.execute"))
        assertTrue(create.indexOf("accountRequestFence.completeIfCurrent") <
            create.indexOf("emailEnrollmentStore.clear(creationConfirmed = true)"))
        assertTrue(create.contains("if (accountCreationRecoveryPending) accountCreationRecoveryMessage"))
        assertTrue(source.contains("if (signupVisible && !accountCreationRecoveryPending) View.GONE else View.VISIBLE"))
        assertTrue(source.contains("accountCreationRecoveryPending -> accountCreationRecoveryMessage"))
    }

    @Test
    fun uncertainCreateFailureGuidesSameInfoRetryOrLoginWithoutChangingOtherFailures() {
        val failureStart = source.indexOf("private fun postAccountFailure(")
        assertTrue("Missing account failure handler", failureStart >= 0)
        val failureEnd = source.indexOf("private fun showAccountMessage(", failureStart)
        assertTrue("Missing end of account failure handler", failureEnd > failureStart)
        val failure = source.substring(failureStart, failureEnd)

        val condition =
            "} else if (token.action == AccountRemoteAction.CREATE_ACCOUNT &&\n" +
                "                (statusCode == 0 || statusCode == 408 || statusCode >= 500)) {"
        val branchStart = failure.indexOf(condition)
        assertTrue("Recovery guidance must be limited to uncertain create failures", branchStart >= 0)
        val branchEnd = failure.indexOf("} else when (serverCode)", branchStart)
        assertTrue("Recovery guidance must precede explicit server errors", branchEnd > branchStart)
        val branch = failure.substring(branchStart, branchEnd)

        assertTrue(branch.contains("가입 결과를 확인하지 못했습니다."))
        assertTrue(branch.contains("계정이 이미 만들어졌을 수 있습니다."))
        assertTrue(branch.contains("같은 가입 정보로 다시 시도하거나 이메일과 비밀번호로 로그인하세요."))
        assertFalse(branch.contains("emailEnrollmentStore.clear()"))
        assertFalse(branch.contains("emailEnrollmentPartial = null"))
        assertFalse(branch.contains("firstRunOnboardingSnapshot ="))
        assertTrue(failure.contains("account_enrollment_conflict"))
        assertTrue(failure.contains("이미 계정이 만들어졌을 수 있습니다. 이메일과 비밀번호로 로그인하세요."))
        assertTrue(failure.contains("계정 서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요."))
    }
}
