package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.inference.InferenceLoadObservation
import org.junit.Assert.*
import org.junit.Test

class CameraAuxiliaryPacingTest {
    @Test fun aSlowOrStaleResultReducesNewWorkAndReleasesItsFlight() {
        val control = CameraAuxiliaryPacing()
        control.begin(1)
        assertTrue(control.tryStart(1, 10, 100))
        assertFalse(control.tryStart(1, 11, 101))
        assertTrue(control.complete(1, 10, 1300, inferenceMs = 900))
        assertFalse(control.snapshot().inFlight)
        assertEquals(1L, control.snapshot().staleSamples)
        assertFalse(control.isDue(1, 1300))
        assertTrue(control.isDue(1, control.snapshot().nextEligibleAtElapsedRealtimeMs))
    }

    @Test fun rejectionCancelsOnlyTheFrameWhoseSubmissionFailed() {
        val control = CameraAuxiliaryPacing()
        control.begin(1)
        assertTrue(control.tryStart(1, 10, 100))
        assertFalse(control.cancel(1, 9))
        assertTrue(control.snapshot().inFlight)
        assertTrue(control.cancel(1, 10))
        assertFalse(control.snapshot().inFlight)
        assertTrue(control.tryStart(1, 11, 1000))
        assertFalse(control.cancel(1, 10))
        assertTrue(control.snapshot().inFlight)
    }

    @Test fun pauseWithNativeWorkStillRunningCannotWedgeTheNextSession() {
        val control = CameraAuxiliaryPacing()
        control.begin(1)
        assertTrue(control.tryStart(1, 10, 100))
        control.reset()
        control.begin(2)
        assertTrue(control.tryStart(2, 20, 1000))
        assertFalse(control.complete(1, 10, 1200))
        control.cancelService(1)
        assertTrue(control.snapshot().inFlight)
        assertTrue(control.complete(2, 20, 1250))
    }

    @Test fun failedServiceAndDuplicateTerminalCallbacksCannotRetainATicket() {
        val control = CameraAuxiliaryPacing()
        control.begin(1)
        assertTrue(control.tryStart(1, 10, 100))
        control.cancelService(1)
        assertFalse(control.snapshot().inFlight)
        assertFalse(control.complete(1, 10, 500))
        control.begin(2)
        assertTrue(control.tryStart(2, 20, 1000))
        assertTrue(control.complete(2, 20, 1100))
        assertFalse(control.complete(2, 20, 1101))
        assertEquals(1L, control.snapshot().completedSamples)
    }

    @Test fun measuredUiPressureAndThermalsAreGivenToAuxiliaryPacing() {
        val control = CameraAuxiliaryPacing()
        control.begin(1)
        assertTrue(control.tryStart(1, 10, 100))
        assertTrue(control.complete(1, 10, 400, thermalThrottled = true,
            load = InferenceLoadObservation(observedAtElapsedRealtimeMs = 400,
                uiDispatchDelayMs = 120, uiDispatchBudgetMs = 17)))
        assertTrue(control.snapshot().thermalHoldActive)
        assertTrue(control.snapshot().observedLoadPressure > 0.0)
        assertFalse(control.isDue(1, 400))
    }
}
