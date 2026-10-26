package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ReportCancellationFailureTest(private val event: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun events() = listOf("withdraw", "lifecycle", "deletion", "reset").map { arrayOf(it) }
    }

    @Test
    fun failedExplicitCancellationCannotKeepAutomaticCallOrOldPermitLive() {
        val session = ReportPrivacyConsentSession
        session.resetForNewEnrollment()
        session.grantFromServerConfirmedIntegratedConsent()
        val permits = ReportTransferPurpose.entries.associateWith { requireNotNull(session.issueUploadPermit(it)) }
        var explicitCancels = 0
        var automaticCancels = 0
        val explicit = CancellableNetworkCall(executeBlock = { error("must not execute") },
            cancelBlock = { explicitCancels++; error("synthetic cancel failure") })
        val automatic = CancellableNetworkCall(executeBlock = { error("must not execute") },
            cancelBlock = { automaticCancels++ })
        session.trackIfGranted(explicit, ReportTransferPurpose.EXPLICIT)
        session.trackIfGranted(automatic, ReportTransferPurpose.AUTOMATIC)
        try {
            when (event) {
                "withdraw" -> session.withdraw()
                "lifecycle" -> session.cancelActiveCalls()
                "deletion" -> session.blockForAccountDeletion()
                "reset" -> session.resetForNewEnrollment()
            }
            assertEquals(1, explicitCancels)
            assertEquals(1, automaticCancels)
            listOf(explicit, automatic).forEach {
                assertTrue(it.isCancelled())
                assertThrows(CancellationException::class.java) { it.execute() }
            }
            permits.forEach { (purpose, permit) ->
                assertThrows(IllegalStateException::class.java) {
                    session.withLiveUploadPermit(permit, purpose) { fail("stale permit opened transport") }
                }
            }
            assertEquals(event == "lifecycle", session.isGranted())
            if (event == "deletion") {
                assertTrue(session.isAccountDeletionBlocked())
                assertFalse(session.grantFromServerConfirmedIntegratedConsent())
            } else {
                if (event != "lifecycle") session.grantFromServerConfirmedIntegratedConsent()
                val newPermit = requireNotNull(session.issueUploadPermit(ReportTransferPurpose.AUTOMATIC))
                assertEquals("fresh", session.withLiveUploadPermit(newPermit, ReportTransferPurpose.AUTOMATIC) { "fresh" })
            }
        } finally {
            session.complete(explicit)
            session.complete(automatic)
            session.resetForNewEnrollment()
        }
    }
}
