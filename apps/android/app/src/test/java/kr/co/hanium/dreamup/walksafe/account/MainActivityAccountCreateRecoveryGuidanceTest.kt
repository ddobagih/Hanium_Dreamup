package kr.co.hanium.dreamup.walksafe.account

import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
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
        val create = MainActivityRefactorSource.member(MainActivityRefactorSource.email, "createEmailAccount")
        val mark = create.indexOf("storage.markCreationPending(ownerBinding)")
        val request = create.indexOf("services.createAccount(")
        val completion = create.indexOf("accountRequestFence.completeIfCurrent", request)
        val clear = create.indexOf("storage.clearUnconfirmedCreation(ownerBinding)", request)
        assertTrue(mark >= 0 && request > mark)
        assertTrue(completion > request && clear > completion)
        assertTrue(create.contains("if (!storage.markCreationPending(ownerBinding))"))
        assertTrue(create.contains("if (!storage.clearUnconfirmedCreation(ownerBinding))"))
        val presenter = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/AccountAccessPresenter.kt").readText()
        assertTrue(MainActivityRefactorSource.email.contains("if (unconfirmedCreation) ui.showMessage(UNCONFIRMED_CREATION_MESSAGE)"))
        assertTrue(presenter.contains("accountAccessNotice != null -> requireNotNull(accountAccessNotice)"))

    }

    @Test
    fun uncertainCreateFailureGuidesSameInfoRetryOrLoginWithoutChangingOtherFailures() {
        val failure = MainActivityRefactorSource.function(source, "private fun postAccountFailure(")

        val condition =
            "} else if (token.action == AccountRemoteAction.CREATE_ACCOUNT &&\n" +
                "                (statusCode == 0 || statusCode == 408 || statusCode >= 500)) {"
        val branchStart = failure.indexOf(condition)
        assertTrue("Recovery guidance must be limited to uncertain create failures", branchStart >= 0)
        val branchEnd = failure.indexOf("} else when (serverCode)", branchStart)
        assertTrue("Recovery guidance must precede explicit server errors", branchEnd > branchStart)
        val branch = failure.substring(branchStart, branchEnd)

        assertTrue(branch.contains("UNCONFIRMED_CREATION_MESSAGE"))
        val owner = MainActivityRefactorSource.email
        val message = owner.substringAfter("const val UNCONFIRMED_CREATION_MESSAGE =")
            .substringBefore("\n    }")
        assertTrue(message.contains("가입 결과를 확인하지 못했습니다."))
        assertTrue(message.contains("계정이 이미 만들어졌을 수 있습니다."))
        assertTrue(message.contains("같은 가입 정보로 다시 시도하거나 이메일과 비밀번호로 로그인하세요."))
        assertFalse(branch.contains("storage.clear()"))
        assertFalse(branch.contains("emailEnrollmentPartial = null"))
        assertFalse(branch.contains("flow.snapshot ="))
        assertTrue(failure.contains("account_enrollment_conflict"))
        assertTrue(failure.contains("이미 계정이 만들어졌을 수 있습니다. 이메일과 비밀번호로 로그인하세요."))
        assertTrue(failure.contains("계정 서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요."))
    }
}
