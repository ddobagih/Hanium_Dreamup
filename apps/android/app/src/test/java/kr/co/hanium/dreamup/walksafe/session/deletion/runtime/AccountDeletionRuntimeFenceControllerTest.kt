package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import org.junit.Assert.*
import org.junit.Test

class AccountDeletionRuntimeFenceControllerTest {
    @Test fun successfulFenceUsesTheOriginalCrossDomainOrderAndNoFailureStatus() {
        val f = Fixture(true)
        assertTrue(f.events.isEmpty())
        assertTrue(f.controller.apply())
        assertEquals(f.expected, f.events)
    }
    @Test fun failedPositionPurgeStillClosesAllOtherAdmissionBeforeReturningFailure() {
        val f = Fixture(false)
        assertFalse(f.controller.apply())
        assertEquals(f.expected + "position-failure", f.events)
    }
    private class Fixture(position: Boolean) {
        val events = mutableListOf<String>()
        val expected = listOf("speech", "position", "report-drain", "raw", "report-requests", "field", "consent", "report-generation",
            "permission", "metadata", "report-uploads", "frames", "report-attempts", "walk")
        val controller = AccountDeletionRuntimeFenceController({ events += "speech" }, { events += "position"; position },
            object : AccountDeletionReportFenceActions {
                override fun cancelDrainAndFenceQueue() { events += "report-drain" }
                override fun clearUserRequests() { events += "report-requests" }
                override fun advanceUploadAuthority() { events += "report-generation" }
                override fun cancelOriginalUploads() { events += "report-uploads" }
                override fun clearAttemptState() { events += "report-attempts" }
            }, { events += "raw" }, { events += "field" }, { events += "consent" }, { events += "permission" }, { events += "metadata" },
            { events += "frames" }, { events += "walk" }, { events += "position-failure" })
    }
}
