package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import kr.co.hanium.dreamup.walksafe.ReportStaticSourceInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16-17 #14: a receipt change ends earlier RAW segments only when RAW continuity is broken. */
class MainActivityRawConsentContinuityStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun receiptChangeBranchesOnRawContinuityAndWithdrawalStillRevokes() {
        val apply = MainActivityRefactorSource.function(source, "fun applyIntegratedConsentConfirmation(")
        assertTrue(apply.contains("val previousRawConfirmation = integratedConsentSession.cachedConfirmationOrNull()"))
        assertTrue(apply.contains("raw.confirmedReceiptChangedIfReady("))
        assertTrue(apply.contains("previousReceipt, previousRawConfirmation, confirmation,"))
        assertTrue(apply.contains("effectiveSelections.rawSourceCollection,"))
        val effect = ReportStaticSourceInspector.functionBlock(source, "override fun confirmedReceiptChangedIfReady")
        assertTrue(effect.contains("val rawSupersede = rawConsentSupersede(previousConfirmation, confirmation, currentRawEffective = rawEffective)"))
        val continued = effect.indexOf("if (rawSupersede == RawConsentSupersede.RAW_CONTINUED) {")
        val onContinued = effect.indexOf("rawCollectionRuntimeCoordinator.onConsentContinued(")
        val onRevoked = effect.indexOf("rawCollectionRuntimeCoordinator.onConsentRevoked(previousReceipt)")
        assertTrue(continued in 0 until onContinued)
        assertTrue(onRevoked > onContinued)
        // An explicit RAW withdrawal keeps revoking the receipt (and, in the store, its chain).
        val withdraw = MainActivityRefactorSource.function(source, "fun applyImmediateConsentWithdrawals(").substringBefore("items.forEach { item ->")
        assertTrue(withdraw.contains("raw.withdrawReceiptIfReady(previousReceipt)"))
        val withdrawEffect = ReportStaticSourceInspector.functionBlock(source, "override fun withdrawReceiptIfReady")
        assertTrue(withdrawEffect.contains("rawCollectionRuntimeCoordinator.onConsentRevoked(previousReceipt)"))
        assertEquals(1, Regex("""onConsentContinued\(""").findAll(source).count())
    }

    @Test
    fun theEarlierReceiptIsHeldBeforeACaptureCanSeeTheNewConfirmation() {
        // S1617-14-F2: the hold precedes both session apply paths; the decision task releases it.
        val apply = MainActivityRefactorSource.function(source, "fun applyIntegratedConsentConfirmation(")
        val hold = apply.indexOf("raw.holdReceiptUntilDecidedIfReady(previousReceipt)")
        assertTrue(hold >= 0)
        assertTrue(apply.lastIndexOf("previousReceipt != confirmation.backendConsentReceiptSha256", hold) in 0 until hold)
        assertTrue(hold < apply.indexOf("integratedConsentSession.applyExactPendingConfirmation(confirmation, authorityGuard)"))
        assertTrue(hold < apply.indexOf("integratedConsentSession.applyConfirmation(confirmation, authorityGuard)"))
        assertTrue(hold < apply.indexOf("raw.confirmedReceiptChangedIfReady("))
        val holdEffect = ReportStaticSourceInspector.functionBlock(source, "override fun holdReceiptUntilDecidedIfReady")
        assertTrue(holdEffect.contains("rawCollectionRuntimeCoordinator.holdReceiptUntilDecided(receipt)"))
        val changeEffect = ReportStaticSourceInspector.functionBlock(source, "override fun confirmedReceiptChangedIfReady")
        assertTrue(changeEffect.contains("executeRawCollectionTask {"))
        assertEquals(1, Regex("""holdReceiptUntilDecided\(""").findAll(source).count())
    }
}
