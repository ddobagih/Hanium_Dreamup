package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityWithdrawalStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun durableMutationCommitPrecedesCancellationAndHttp() {
        val function = source.substringAfter(
            "private fun persistIntegratedConsentDraft(announce: Boolean)",
        ).substringBefore("private fun retryPendingIntegratedConsentMutation")

        val commit = function.indexOf(".commit()")
        val cancellation = function.indexOf("integratedConsentCall?.cancel()")
        val http = function.indexOf("integratedConsentClient.saveCall(")

        assertTrue(commit >= 0)
        assertTrue(cancellation > commit)
        assertTrue(http > cancellation)
    }

    @Test
    fun restoreAndExactCommitPrecedeNormalRefreshAndFenceRelease() {
        val onCreate = source.substringAfter("override fun onCreate(")
            .substringBefore("private fun restoreStepLengthFromPrefs")
        assertTrue(
            onCreate.indexOf("restorePrivacyControlStateFromPrefs()") <
                onCreate.indexOf("resumePrivacyControlOperations()"),
        )
        val apply = source.substringAfter(
            "private fun applyIntegratedConsentConfirmation(",
        ).substringBefore("private fun persistedIntegratedConsentSelections")
        assertTrue(
            apply.indexOf("editor.commit()") <
                apply.indexOf("applyExactPendingConfirmation"),
        )
    }
}
