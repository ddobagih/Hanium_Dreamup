package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportPrivacyAccountDeletionTest {
    @Test
    fun globalDeletionFenceCancelsBothPurposesAndRejectsLaterGrant() {
        val session = freshSession()
        session.grantFromServerConfirmedIntegratedConsent()
        val explicit = CancellableNetworkCall<Unit>(executeBlock = { Unit })
        val automatic = CancellableNetworkCall<Unit>(executeBlock = { Unit })
        session.trackIfGranted(explicit, ReportTransferPurpose.EXPLICIT)
        session.trackIfGranted(automatic, ReportTransferPurpose.AUTOMATIC)

        session.blockForAccountDeletion()

        assertTrue(explicit.isCancelled())
        assertTrue(automatic.isCancelled())
        assertFalse(session.isGranted())
        assertFalse(session.grantFromServerConfirmedIntegratedConsent())
        assertNull(
            session.trackIfGranted(
                CancellableNetworkCall<Unit>(executeBlock = { Unit }),
            ),
        )
    }

    @Test
    fun onlyNewEnrollmentResetRemovesDeletionFenceAndStillDefaultsOff() {
        val session = freshSession()
        session.blockForAccountDeletion()

        assertTrue(session.resetForNewEnrollment())
        assertFalse(session.isAccountDeletionBlocked())
        assertFalse(session.isGranted())
        assertTrue(session.grantFromServerConfirmedIntegratedConsent())
    }

    @Test
    fun deletionAndResetRevokePermitsBeforeOpenerEntry() {
        val session = freshSession()
        session.grantFromServerConfirmedIntegratedConsent()
        val deletionPermit =
            requireNotNull(session.issueUploadPermit(ReportTransferPurpose.EXPLICIT))
        session.blockForAccountDeletion()
        var opened = false
        assertThrows(IllegalStateException::class.java) {
            session.withLiveUploadPermit(
                deletionPermit,
                ReportTransferPurpose.EXPLICIT,
            ) {
                opened = true
            }
        }
        assertFalse(opened)

        session.resetForNewEnrollment()
        session.grantFromServerConfirmedIntegratedConsent()
        val resetPermit =
            requireNotNull(session.issueUploadPermit(ReportTransferPurpose.EXPLICIT))
        session.resetForNewEnrollment()
        assertThrows(IllegalStateException::class.java) {
            session.withLiveUploadPermit(
                resetPermit,
                ReportTransferPurpose.EXPLICIT,
            ) {
                opened = true
            }
        }
        assertFalse(opened)
    }

    private fun freshSession(): ReportPrivacyConsentSession {
        ReportPrivacyConsentSession.resetForNewEnrollment()
        return ReportPrivacyConsentSession
    }
}
