package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S15-42: re-applying the exact current server confirmation must not cancel report work. */
class MainActivityConsentRedeliveryStaticTest {
    private val source = ReportStaticSourceInspector.read(MAIN_ACTIVITY_PATH)
    private val apply = ReportStaticSourceInspector.functionBlock(
        source,
        "private fun applyIntegratedConsentConfirmation",
    )

    @Test
    fun exactRedeliveryKeepsReportPermitsButAnyAuthorityChangeStillCancels() {
        assertTrue(apply.contains("integratedConsentSession.applyConfirmation(confirmation, authorityGuard)"))
        assertTrue(apply.contains("integratedConsentSession.applyExactPendingConfirmation(confirmation, authorityGuard)"))
        val guard = apply.substringAfter("val unchangedReportAuthority =")
            .substringBefore("synchronized(reportUploadSafetyLock)")
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

        val lockBlock = ReportStaticSourceInspector.blockAfter(apply, "synchronized(reportUploadSafetyLock)")
        val cancellation = ReportStaticSourceInspector.blockAfter(
            lockBlock,
            "if (!unchangedReportAuthority || !reportPrivacyConsentSession.isGranted())",
        )
        assertTrue(cancellation.contains("reportUploadSafetyGeneration += 1L"))
        assertTrue(cancellation.contains("automaticReportUploadSafetyGeneration += 1L"))
        assertTrue(cancellation.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertFalse(cancellation.contains("grantFromServerConfirmedIntegratedConsent"))
        assertTrue(
            lockBlock.substringAfter(cancellation)
                .contains("reportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent(authorityGuard)"),
        )
        // No other unconditional report cancellation remains in this confirmation path.
        assertEquals(
            1,
            Regex("reportPrivacyConsentSession\\.cancelActiveCalls\\(\\)").findAll(apply).count(),
        )
    }

    private companion object {
        const val MAIN_ACTIVITY_PATH =
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"
    }
}
