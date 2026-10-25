package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S15-42: re-applying the exact current server confirmation must not cancel report work. */
class MainActivityConsentRedeliveryStaticTest {
    private val source = ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)
    private val apply = MainActivityRefactorSource.function(
        source,
        "private fun applyIntegratedConsentConfirmation",
    )

    @Test
    fun exactRedeliveryKeepsReportPermitsButAnyAuthorityChangeStillCancels() {
        assertTrue(apply.contains("integratedConsentSession.applyConfirmation(confirmation, authorityGuard)"))
        assertTrue(apply.contains("integratedConsentSession.applyExactPendingConfirmation(confirmation, authorityGuard)"))
        val guard = apply.substringAfter("val unchangedReportAuthority =")
            .substringBefore("reports.applyConfirmationAuthority(")
        for (part in listOf(
            "!exactPending",
            "evaluation == IntegratedConsentApplyResult.DUPLICATE",
            "previousReceipt == confirmation.backendConsentReceiptSha256",
            "previousConfirmedActorSha256 == expectedActorSha256",
        )) {
            assertTrue("missing redelivery identity check: $part", guard.contains(part))
        }
        val capture = apply.indexOf(
            "val previousConfirmedActorSha256 = integratedConsentConfirmedActorSha256",
        )
        val overwrite = apply.indexOf("integratedConsentConfirmedActorSha256 = expectedActorSha256")
        assertTrue(capture in 0 until overwrite)

        assertTrue(apply.contains("reports.applyConfirmationAuthority(unchangedReportAuthority, authorityGuard)"))
        val effect = ReportStaticSourceInspector.functionBlock(
            source, "override fun applyConfirmationAuthority",
        )
        val lockBlock = ReportStaticSourceInspector.blockAfter(effect, "synchronized(reportUploadSafetyLock)")
        val cancellation = ReportStaticSourceInspector.blockAfter(
            lockBlock,
            "if (!unchanged || !reportConsent().isGranted())",
        )
        assertTrue(cancellation.contains("reportUploadSafetyGeneration += 1L"))
        assertTrue(cancellation.contains("automaticReportUploadSafetyGeneration += 1L"))
        assertTrue(cancellation.contains("reportConsent().cancelActiveCalls()"))
        assertFalse(cancellation.contains("grantFromServerConfirmedIntegratedConsent"))
        assertTrue(
            lockBlock.substringAfter(cancellation)
                .contains("reportConsent().grantFromServerConfirmedIntegratedConsent(guard)"),
        )
        // No other unconditional report cancellation remains in this confirmation path.
        assertEquals(
            1,
            Regex("reportConsent\\(\\)\\.cancelActiveCalls\\(\\)").findAll(effect).count(),
        )
    }

    private companion object {
        const val MAIN_ACTIVITY_PATH =
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"
    }
}
