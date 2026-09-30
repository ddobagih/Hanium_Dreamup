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
        assertTrue(retryWait.contains("비밀번호로 다시 로그인한 뒤 계정 삭제를 다시 요청하세요."))
    }
}
