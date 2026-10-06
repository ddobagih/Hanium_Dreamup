package kr.co.hanium.dreamup.walksafe.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S19-44: a new account deletion refused for a stale login becomes a retry that waits for a
 * password login (same request ID), not a generic retry or a terminal failure.
 */
class AccountDeletionReauthenticationDispositionTest {
    @Test
    fun staleLoginRefusalWaitsForReauthentication() {
        assertEquals(
            AccountDeletionHttpFailureDisposition.Retry(ACCOUNT_DELETION_REAUTHENTICATION_RETRY_CODE),
            accountDeletionHttpFailureDisposition(
                401,
                """{"code":"account_deletion_reauthentication_required","message":"x","reauthentication_required":true}""",
            ),
        )
    }

    @Test
    fun otherUnauthorizedBodiesKeepTheGenericRetry() {
        for (body in listOf(
            """{"code":"gateway_auth_required"}""",
            """{"code":"account_deletion_reauthentication_required"}""",
            """{"code":"account_deletion_reauthentication_required","reauthentication_required":"true"}""",
            "not-json",
        )) {
            assertEquals(body, AccountDeletionHttpFailureDisposition.Retry("http_401"),
                accountDeletionHttpFailureDisposition(401, body))
        }
        assertEquals(
            AccountDeletionHttpFailureDisposition.Retry("http_403"),
            accountDeletionHttpFailureDisposition(
                403,
                """{"code":"account_deletion_reauthentication_required","reauthentication_required":true}""",
            ),
        )
    }

    @Test
    fun deletionScreenTellsTheUserToLogInAgain() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")
            .readText(Charsets.UTF_8)
        val ui = source.substringAfter("private fun updateAccountDeletionUi()")
            .substringBefore("AccountDeletionPhase.RESTRICTED ->")
        val retryWait = ui.substringAfter("AccountDeletionPhase.RETRY_WAIT ->")
        assertTrue(retryWait.contains("ACCOUNT_DELETION_REAUTHENTICATION_RETRY_CODE"))
        assertTrue(retryWait.contains("삭제 복구 인증을 눌러 같은 계정으로 다시 인증하세요."))
        assertTrue(source.contains("onClick = ::onAccountDeletionRefreshClicked"))
        val click = source.substringAfter("private fun onAccountDeletionRefreshClicked()")
            .substringBefore("private fun refreshAccountDeletionStatusAllowed(")
        assertTrue(click.contains("accountDeletionRecoveryGatewaySessionOrNull() == null"))
        assertTrue(click.contains("backendFieldTokenInput.requestFocus()"))
        assertTrue(click.contains("계정 토큰 입력 방식"))
        assertTrue(click.indexOf("return") < click.lastIndexOf("refreshAccountDeletionStatus()"))
        val refresh = source.substringAfter("private fun refreshAccountDeletionStatusAllowed(")
            .substringBefore("private fun startAccountDeletionCall(")
        val gate = refresh.substringAfter("if ((recoveryBinding != null || journal.requiresDeletionReauthentication())")
            .substringBefore("if (recoveryBinding != null && recoverySession != null)")
        assertTrue(gate.contains("recoverySession == null"))
        assertTrue(gate.contains("return"))
        assertTrue(refresh.indexOf("journal.requiresDeletionReauthentication()") <
            refresh.indexOf("accountDeletionClient.replayDeletionRequestCall("))
    }
}
