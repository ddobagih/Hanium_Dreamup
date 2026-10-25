package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.*
import org.junit.Test

class CameraAuxiliaryStartupCoordinatorTest {
    @Test fun busyPrimaryDefersAuxiliaryAndARepeatedCompletionCanStartWhenIdle() {
        val startup = CameraAuxiliaryStartupCoordinator()
        startup.begin(1)
        assertTrue(startup.complete(1))
        repeat(20) { assertFalse(startup.mayStart(1, primaryBusy = true)) }
        assertFalse(startup.complete(1))
        assertTrue(startup.mayStart(1, primaryBusy = false))
        assertFalse(startup.mayStart(2, primaryBusy = false))
        startup.clear()
        assertFalse(startup.mayStart(1, primaryBusy = false))
    }

    @Test fun detectorPreparationAloneCannotStartAuxiliaryBeforeTheLivePrimaryAttempt() {
        val startup = CameraAuxiliaryStartupCoordinator()
        startup.begin(1)
        assertFalse(startup.mayStart(1))
        assertFalse(startup.complete(0))
        assertFalse(startup.mayStart(1))
        assertTrue(startup.complete(1))
        assertTrue(startup.mayStart(1))
        assertFalse(startup.complete(1))
    }

    @Test fun pauseAndNewCameraEpochRejectLateQueuedLiveCompletions() {
        val startup = CameraAuxiliaryStartupCoordinator()
        startup.begin(1)
        startup.clear()
        assertFalse(startup.complete(1)); assertFalse(startup.mayStart(1))
        startup.begin(2)
        assertFalse(startup.complete(1)); assertFalse(startup.mayStart(2))
        assertTrue(startup.complete(2)); assertTrue(startup.mayStart(2))
        assertFalse(startup.mayStart(1))
    }

    @Test fun repeatedReadsAndSameEpochResumeNeverManufactureAnotherFirstAttempt() {
        val startup = CameraAuxiliaryStartupCoordinator()
        startup.begin(1)
        repeat(20) { assertFalse(startup.mayStart(1)) }
        assertTrue(startup.complete(1))
        startup.begin(1)
        repeat(20) { assertTrue(startup.mayStart(1)); assertFalse(startup.complete(1)) }
        startup.begin(2)
        assertFalse(startup.mayStart(2))
    }
}
