package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16-17 #14: a receipt change ends earlier RAW segments only when RAW continuity is broken. */
class MainActivityRawConsentContinuityStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun receiptChangeBranchesOnRawContinuityAndWithdrawalStillRevokes() {
        val apply = source.substringAfter("private fun applyIntegratedConsentConfirmation(")
            .substringBefore("postIntegratedConsentUiRefresh()")
        assertTrue(apply.contains("val previousRawConfirmation = integratedConsentSession.cachedConfirmationOrNull()"))
        assertTrue(apply.contains(
            "val rawSupersede = rawConsentSupersede(\n" +
                "                    previousRawConfirmation,\n" +
                "                    confirmation,\n" +
                "                    currentRawEffective = effectiveSelections.rawSourceCollection,\n",
        ))
        val continued = apply.indexOf("if (rawSupersede == RawConsentSupersede.RAW_CONTINUED) {")
        val onContinued = apply.indexOf("rawCollectionRuntimeCoordinator.onConsentContinued(")
        val onRevoked = apply.indexOf("rawCollectionRuntimeCoordinator.onConsentRevoked(previousReceipt)")
        assertTrue(continued in 0 until onContinued)
        assertTrue(onRevoked > onContinued)
        // An explicit RAW withdrawal keeps revoking the receipt (and, in the store, its chain).
        val withdraw = source.substringAfter("private fun applyImmediateConsentWithdrawals(")
            .substringBefore("items.forEach { item ->")
        assertTrue(withdraw.contains("rawCollectionRuntimeCoordinator.onConsentRevoked(previousReceipt)"))
        assertEquals(1, Regex("""onConsentContinued\(""").findAll(source).count())
    }

    @Test
    fun theEarlierReceiptIsHeldBeforeACaptureCanSeeTheNewConfirmation() {
        // S1617-14-F2: the hold precedes both session apply paths; the decision task releases it.
        val apply = source.substringAfter("private fun applyIntegratedConsentConfirmation(")
            .substringBefore("postIntegratedConsentUiRefresh()")
        val hold = apply.indexOf("rawCollectionRuntimeCoordinator.holdReceiptUntilDecided(previousReceipt)")
        assertTrue(hold >= 0)
        assertTrue(apply.lastIndexOf("previousReceipt != confirmation.backendConsentReceiptSha256", hold) in 0 until hold)
        assertTrue(hold < apply.indexOf("integratedConsentSession.applyExactPendingConfirmation(confirmation, authorityGuard)"))
        assertTrue(hold < apply.indexOf("integratedConsentSession.applyConfirmation(confirmation, authorityGuard)"))
        assertTrue(hold < apply.indexOf("executeRawCollectionTask {"))
        assertEquals(1, Regex("""holdReceiptUntilDecided\(""").findAll(source).count())
    }
}
