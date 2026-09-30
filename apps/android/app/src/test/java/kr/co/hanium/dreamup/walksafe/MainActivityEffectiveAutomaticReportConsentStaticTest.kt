package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S02-23: a local withdrawal leaves the server receipt's selection unchanged until the server
 * confirms it, so automatic report creation and queue drain must read the effective consent.
 */
class MainActivityEffectiveAutomaticReportConsentStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val effective =
        "integratedConsentSession.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING)"

    @Test
    fun automaticCandidateCreationRechecksEffectiveConsentBeforeEnqueue() {
        val process = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun processReportCandidate",
        )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                process,
                "integratedConsentSession.currentConfirmationOrNull()",
                "!explicitRequest &&",
                effective,
                "reportCandidate=blocked:automatic_report_consent_required",
                "reportQueueStore.enqueue(",
            ),
        )
    }

    @Test
    fun automaticQueueDrainUsesEffectiveConsent() {
        val context = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun reportQueueDrainContext",
        )
        val automatic = context.substringAfter("automaticReportingAllowed =")
            .substringBefore("authorityAllowed = authorityAllowed")
        assertTrue(automatic.contains("consentAllowed &&"))
        assertTrue(automatic.contains(effective))
    }
}
