package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityWithdrawalStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun durableMutationCommitPrecedesCancellationAndHttp() {
        val function = MainActivityRefactorSource.function(source, "private fun persistIntegratedConsentDraft(")

        val commit = function.indexOf(".commit()")
        val cancellation = function.indexOf("integratedConsentCall?.cancel()")
        val http = function.indexOf("remote.saveCall(")

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
        val apply = MainActivityRefactorSource.function(source, "private fun applyIntegratedConsentConfirmation(")
        assertTrue(
            apply.indexOf("editor.commit()") <
                apply.indexOf("applyExactPendingConfirmation"),
        )
    }
}
