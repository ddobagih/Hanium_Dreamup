package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class ReportPurposePermitRegressionTest {
    @Before fun grant() {
        ReportPrivacyConsentSession.resetForNewEnrollment()
        ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent()
    }

    @After fun reset() {
        ReportPrivacyConsentSession.resetForNewEnrollment()
    }

    @Test
    fun automaticCancellationInvalidatesUntrackedPermitButPreservesExplicitPermit() {
        val automatic = requireNotNull(ReportPrivacyConsentSession.issueUploadPermit(ReportTransferPurpose.AUTOMATIC))
        val explicit = requireNotNull(ReportPrivacyConsentSession.issueUploadPermit(ReportTransferPurpose.EXPLICIT))
        ReportPrivacyConsentSession.cancelActiveCalls(ReportTransferPurpose.AUTOMATIC)
        val stale = CancellableNetworkCall.blocking { true }
        assertNull(ReportPrivacyConsentSession.trackIfLive(automatic, stale, ReportTransferPurpose.AUTOMATIC))
        assertTrue(stale.isCancelled())
        assertThrows(IllegalStateException::class.java) {
            ReportPrivacyConsentSession.withLiveUploadPermit(automatic, ReportTransferPurpose.AUTOMATIC) { true }
        }
        assertTrue(ReportPrivacyConsentSession.withLiveUploadPermit(explicit, ReportTransferPurpose.EXPLICIT) { true })
    }

    @Test
    fun newEnrollmentCannotReusePriorPurposePermit() {
        val permit = requireNotNull(ReportPrivacyConsentSession.issueUploadPermit(ReportTransferPurpose.EXPLICIT))
        ReportPrivacyConsentSession.resetForNewEnrollment()
        ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent()
        assertThrows(IllegalStateException::class.java) {
            ReportPrivacyConsentSession.withLiveUploadPermit(permit, ReportTransferPurpose.EXPLICIT) { true }
        }
    }
}
