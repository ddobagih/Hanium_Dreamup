package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidReportPurposeHeaderStaticTest {
    @Test
    fun uploaderBindsPurposeHeaderToValidatedMetadataPurpose() {
        val source =
            File("src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt")
                .readText()
        val uploadCall = source.substringAfter("fun queuedUploadCall(")
        val authority =
            File("src/main/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSession.kt")
                .readText()

        assertTrue(source.contains("REPORT_PURPOSE_HEADER"))
        assertTrue(!source.contains("fun upload("))
        assertTrue(source.contains("internal class AndroidReportUploader internal constructor()"))
        assertTrue(uploadCall.contains("permit: ReportUploadPermit"))
        assertTrue(
            uploadCall.indexOf("ReportPrivacyConsentSession.withLiveUploadPermit(") <
                uploadCall.indexOf("openConnection(URL(endpoint))"),
        )
        assertTrue(authority.contains("internal object ReportPrivacyConsentSession"))
        assertTrue(!authority.contains("class ReportPrivacyConsentSession"))
        assertTrue(authority.contains("private class CanonicalReportUploadPermit private constructor("))
        val linearized = authority.substringAfter("internal fun <T> withLiveUploadPermit(")
        assertTrue(linearized.indexOf("synchronized(lock)") < linearized.indexOf("opener()"))
        assertTrue(
            source.contains(
                "setRequestProperty(REPORT_PURPOSE_HEADER, purpose.wireValue)",
            ),
        )
        assertTrue(uploadCall.contains("val purpose = report.priority.toTransferPurpose()"))
        assertTrue(
            !uploadCall.contains(
                "consentConfirmation.backendConsentReceiptSha256 ==\n                report.consentReceiptSha256",
            ),
        )
        assertTrue(
            source.contains(
                "setRequestProperty(\n                    CONSENT_RECEIPT_HEADER,\n                    consentConfirmation.backendConsentReceiptSha256",
            ),
        )
        assertTrue(!source.contains("consentConfirmation.gatewayAuditRecordSha256"))
    }

    @Test
    fun explicitMainActivityPathDoesNotRequireAutomaticConsent() {
        val source =
            File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")
                .readText()
        val process = source.substringAfter("private fun processReportCandidate(")
            .substringBefore("private fun requestExplicitReport(")

        assertTrue(
            process.contains(
                "ReportQueuePriority.AUTOMATIC",
            ),
        )
        assertTrue(process.contains("if (!explicitRequest &&"))
        assertTrue(!process.contains("rawSourceCollection"))
    }
}
