package kr.co.hanium.dreamup.walksafe.network

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNetworkTransferPolicyTest {
    @Test
    fun wifiIsAllowedForBothPreferences() {
        assertTrue(
            AndroidNetworkTransferPolicy.isAllowed(
                MobileNetworkPreference.WIFI_ONLY,
                ActiveNetworkTransport.WIFI,
            ),
        )
        assertTrue(
            AndroidNetworkTransferPolicy.isAllowed(
                MobileNetworkPreference.ALLOW_CELLULAR,
                ActiveNetworkTransport.WIFI,
            ),
        )
    }

    @Test
    fun cellularRequiresTheIndependentCellularPreference() {
        assertFalse(
            AndroidNetworkTransferPolicy.isAllowed(
                MobileNetworkPreference.WIFI_ONLY,
                ActiveNetworkTransport.CELLULAR,
            ),
        )
        assertTrue(
            AndroidNetworkTransferPolicy.isAllowed(
                MobileNetworkPreference.ALLOW_CELLULAR,
                ActiveNetworkTransport.CELLULAR,
            ),
        )
    }

    @Test
    fun offlineIsAlwaysBlockedAndNonCellularTransportRemainsAvailable() {
        assertFalse(
            AndroidNetworkTransferPolicy.isAllowed(
                MobileNetworkPreference.ALLOW_CELLULAR,
                ActiveNetworkTransport.OFFLINE,
            ),
        )
        assertTrue(
            AndroidNetworkTransferPolicy.isAllowed(
                MobileNetworkPreference.WIFI_ONLY,
                ActiveNetworkTransport.OTHER,
            ),
        )
    }

    @Test
    fun walkingBlocksEveryActivityOriginalUploadAndCancelsWhenWalkingResumes() {
        ActiveNetworkTransport.entries.forEach { transport ->
            assertEquals(
                ActivityOriginalUploadDecision.BLOCKED_WHILE_WALKING,
                AndroidActivityOriginalUploadPolicy.decide(
                    ActivityOriginalMotionState.WALKING,
                    MobileNetworkPreference.ALLOW_CELLULAR,
                    transport,
                ),
            )
        }
        assertTrue(
            AndroidActivityOriginalUploadPolicy.shouldCancelInFlight(
                ActivityOriginalMotionState.STATIONARY,
                ActivityOriginalMotionState.WALKING,
            ),
        )
        assertTrue(
            AndroidActivityOriginalUploadPolicy.shouldCancelInFlight(
                ActivityOriginalMotionState.WALKING,
                ActivityOriginalMotionState.WALKING,
            ),
        )
        assertFalse(
            AndroidActivityOriginalUploadPolicy.shouldCancelInFlight(
                ActivityOriginalMotionState.WALKING,
                ActivityOriginalMotionState.STATIONARY,
            ),
        )
        assertTrue(
            AndroidActivityOriginalUploadPolicy.shouldCancelInFlight(
                ActivityOriginalMotionState.STATIONARY,
                ActivityOriginalMotionState.UNKNOWN,
            ),
        )
    }

    @Test
    fun stationaryWifiAllowsActivityOriginalUploadForBothPreferences() {
        MobileNetworkPreference.entries.forEach { preference ->
            assertEquals(
                ActivityOriginalUploadDecision.WIFI_ALLOWED,
                AndroidActivityOriginalUploadPolicy.decide(
                    ActivityOriginalMotionState.STATIONARY,
                    preference,
                    ActiveNetworkTransport.WIFI,
                ),
            )
        }
    }

    @Test
    fun stationaryCellularRequiresExplicitOptInForActivityOriginalUpload() {
        assertEquals(
            ActivityOriginalUploadDecision.APPROVED_CELLULAR_ALLOWED,
            AndroidActivityOriginalUploadPolicy.decide(
                ActivityOriginalMotionState.STATIONARY,
                MobileNetworkPreference.ALLOW_CELLULAR,
                ActiveNetworkTransport.CELLULAR,
            ),
        )
        assertEquals(
            ActivityOriginalUploadDecision.QUEUED_UNTIL_WIFI,
            AndroidActivityOriginalUploadPolicy.decide(
                ActivityOriginalMotionState.STATIONARY,
                MobileNetworkPreference.WIFI_ONLY,
                ActiveNetworkTransport.CELLULAR,
            ),
        )
    }

    @Test
    fun unspecifiedPreferenceFailsBackToWifiOnlyQueueing() {
        val fallback = MobileNetworkPreference.fromWireValue(null)
        assertEquals(MobileNetworkPreference.WIFI_ONLY, fallback)
        assertEquals(
            MobileNetworkPreference.WIFI_ONLY,
            MobileNetworkPreference.fromWireValue("unexpected"),
        )
        assertEquals(
            ActivityOriginalUploadDecision.QUEUED_UNTIL_WIFI,
            AndroidActivityOriginalUploadPolicy.decide(
                ActivityOriginalMotionState.STATIONARY,
                fallback,
                ActiveNetworkTransport.CELLULAR,
            ),
        )
    }

    @Test
    fun stationaryOtherAndOfflineActivityOriginalTransfersFailClosed() {
        listOf(
            ActiveNetworkTransport.OTHER,
            ActiveNetworkTransport.OFFLINE,
        ).forEach { transport ->
            assertEquals(
                ActivityOriginalUploadDecision.FAIL_CLOSED,
                AndroidActivityOriginalUploadPolicy.decide(
                    ActivityOriginalMotionState.STATIONARY,
                    MobileNetworkPreference.ALLOW_CELLULAR,
                    transport,
                ),
            )
        }
    }

    @Test
    fun onlyActiveSessionWithConfirmedStationaryEvidenceMapsToStationary() {
        assertEquals(
            ActivityOriginalMotionState.STATIONARY,
            AndroidActivityOriginalUploadPolicy.effectiveMotionState(
                sessionActive = true,
                observedMotionState = ActivityOriginalMotionState.STATIONARY,
            ),
        )
        listOf(
            ActivityOriginalMotionState.UNKNOWN,
            ActivityOriginalMotionState.WALKING,
        ).forEach { observed ->
            assertEquals(
                observed,
                AndroidActivityOriginalUploadPolicy.effectiveMotionState(
                    sessionActive = true,
                    observedMotionState = observed,
                ),
            )
        }
        ActivityOriginalMotionState.entries.forEach { observed ->
            assertEquals(
                ActivityOriginalMotionState.UNKNOWN,
                AndroidActivityOriginalUploadPolicy.effectiveMotionState(
                    sessionActive = false,
                    observedMotionState = observed,
                ),
            )
        }
    }

    @Test
    fun stepEvidenceRequiresObservationWindowAndReturnsToWalkingOnNewStep() {
        val evidence = ActivityOriginalMotionEvidence(
            stationaryConfirmationMs = 5_000L,
        )
        assertEquals(
            ActivityOriginalMotionState.UNKNOWN,
            evidence.motionStateAt(10_000L),
        )

        evidence.beginObservation()
        assertEquals(
            ActivityOriginalMotionState.UNKNOWN,
            evidence.motionStateAt(20_000L),
        )
        assertTrue(evidence.observeStepCount(stepCount = 4, observedAtMs = 10_000L))
        assertEquals(
            ActivityOriginalMotionState.WALKING,
            evidence.motionStateAt(14_999L),
        )
        assertEquals(
            ActivityOriginalMotionState.STATIONARY,
            evidence.motionStateAt(15_000L),
        )

        assertTrue(evidence.observeStepCount(stepCount = 5, observedAtMs = 15_001L))
        assertEquals(
            ActivityOriginalMotionState.WALKING,
            evidence.motionStateAt(15_001L),
        )

        evidence.reset()
        assertEquals(
            ActivityOriginalMotionState.UNKNOWN,
            evidence.motionStateAt(30_000L),
        )
    }

    @Test
    fun stationarySnapshotRequiresActiveSessionRealSampleAndExistingThreshold() {
        val controller = ActivityOriginalUploadAdmissionController(
            stationaryConfirmationMs = 5_000L,
        )
        assertNull(controller.stationarySnapshot(10_000L))

        controller.onSessionActiveChanged(active = true, observedAtMs = 0L) {}
        controller.onTrackingStarted(observedAtMs = 0L) {}
        assertNull(controller.stationarySnapshot(10_000L))

        controller.onSensorSample(stepCount = 7, observedAtMs = 10_000L) {}
        assertNull(controller.stationarySnapshot(-1L))
        assertNull(controller.stationarySnapshot(9_999L))
        assertNull(controller.stationarySnapshot(14_999L))
        assertEquals(
            ActivityOriginalStationarySnapshot(stepCount = 7, observedAtMs = 15_000L),
            controller.stationarySnapshot(15_000L),
        )

        controller.onSensorSample(stepCount = 8, observedAtMs = 15_001L) {}
        assertNull(controller.stationarySnapshot(15_001L))
        controller.onSessionActiveChanged(active = false, observedAtMs = 20_001L) {}
        assertNull(controller.stationarySnapshot(30_000L))
    }

    @Test
    fun unknownMotionFailsClosedEvenOnWifi() {
        assertEquals(
            ActivityOriginalUploadDecision.FAIL_CLOSED,
            AndroidActivityOriginalUploadPolicy.decide(
                ActivityOriginalMotionState.UNKNOWN,
                MobileNetworkPreference.ALLOW_CELLULAR,
                ActiveNetworkTransport.WIFI,
            ),
        )
    }

    @Test
    fun admissionCheckAndEnqueueCompleteBeforeConcurrentCloseAndCancel() {
        val controller = stationaryController()
        val actionStarted = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val admitted = executor.submit<Boolean> {
                controller.admit(
                    consentAllowed = true,
                    preference = MobileNetworkPreference.WIFI_ONLY,
                    transport = ActiveNetworkTransport.WIFI,
                    observedAtMs = 5_000L,
                ) {
                    order.add("enqueue-start")
                    actionStarted.countDown()
                    check(releaseAction.await(2, TimeUnit.SECONDS))
                    order.add("enqueue-end")
                }
            }
            assertTrue(actionStarted.await(2, TimeUnit.SECONDS))
            val closed = executor.submit {
                closeAttempted.countDown()
                controller.onSensorSample(
                    stepCount = 1,
                    observedAtMs = 5_001L,
                ) {
                    order.add("cancel")
                }
            }
            assertTrue(closeAttempted.await(2, TimeUnit.SECONDS))
            releaseAction.countDown()

            assertTrue(admitted.get(2, TimeUnit.SECONDS))
            closed.get(2, TimeUnit.SECONDS)
            assertEquals(
                listOf("enqueue-start", "enqueue-end", "cancel"),
                order,
            )
            assertFalse(
                controller.admit(
                    consentAllowed = true,
                    preference = MobileNetworkPreference.WIFI_ONLY,
                    transport = ActiveNetworkTransport.WIFI,
                    observedAtMs = 5_001L,
                    action = { error("closed admission must not re-enter") },
                ),
            )
        } finally {
            releaseAction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun closeAndCancelBeforeAdmissionPreventsCheckAndEnqueue() {
        val controller = stationaryController()
        val actionRan = AtomicBoolean(false)
        var cancellations = 0

        controller.onSensorSample(
            stepCount = 1,
            observedAtMs = 5_001L,
        ) {
            cancellations += 1
        }
        val admitted = controller.admit(
            consentAllowed = true,
            preference = MobileNetworkPreference.WIFI_ONLY,
            transport = ActiveNetworkTransport.WIFI,
            observedAtMs = 5_001L,
        ) {
            actionRan.set(true)
        }

        assertEquals(1, cancellations)
        assertFalse(admitted)
        assertFalse(actionRan.get())
    }

    @Test
    fun inactiveTransitionClosesAndCancelsEvenFromConfirmedStationary() {
        val controller = stationaryController()
        var cancellations = 0

        controller.onSessionActiveChanged(
            active = false,
            observedAtMs = 5_000L,
        ) {
            cancellations += 1
        }

        assertEquals(1, cancellations)
        assertTrue(
            AndroidActivityOriginalUploadPolicy.shouldCancelInFlight(
                ActivityOriginalMotionState.STATIONARY,
                ActivityOriginalMotionState.UNKNOWN,
            ),
        )
        assertTrue(
            AndroidActivityOriginalUploadPolicy.shouldCancelInFlight(
                ActivityOriginalMotionState.UNKNOWN,
                ActivityOriginalMotionState.UNKNOWN,
            ),
        )
        assertFalse(
            controller.admit(
                consentAllowed = true,
                preference = MobileNetworkPreference.WIFI_ONLY,
                transport = ActiveNetworkTransport.WIFI,
                observedAtMs = 20_000L,
                action = { error("inactive admission must stay closed") },
            ),
        )
    }

    @Test
    fun firstSensorSampleClosesAdmissionAndStartsStationaryWindow() {
        val controller = ActivityOriginalUploadAdmissionController(
            stationaryConfirmationMs = 5_000L,
        )
        var cancellations = 0
        controller.onSessionActiveChanged(
            active = true,
            observedAtMs = 0L,
        ) {
            cancellations += 1
        }
        controller.onTrackingStarted(observedAtMs = 0L) {
            cancellations += 1
        }

        assertFalse(
            controller.admit(
                consentAllowed = true,
                preference = MobileNetworkPreference.WIFI_ONLY,
                transport = ActiveNetworkTransport.WIFI,
                observedAtMs = 50_000L,
                action = { error("tracking start is not a stationary sample") },
            ),
        )

        controller.onSensorSample(stepCount = 0, observedAtMs = 50_001L) {
            cancellations += 1
        }
        assertEquals(3, cancellations)
        assertFalse(
            controller.admit(
                consentAllowed = true,
                preference = MobileNetworkPreference.WIFI_ONLY,
                transport = ActiveNetworkTransport.WIFI,
                observedAtMs = 55_000L,
                action = { error("stationary window is not complete") },
            ),
        )
        assertTrue(
            controller.admit(
                consentAllowed = true,
                preference = MobileNetworkPreference.WIFI_ONLY,
                transport = ActiveNetworkTransport.WIFI,
                observedAtMs = 55_001L,
                action = {},
            ),
        )
    }

    @Test
    fun trackingStopClosesAdmissionResetsMotionAndCancelsBothUploaders() {
        val controller = stationaryController()
        var metadataCancels = 0
        var frameCancels = 0

        controller.onTrackingStopped {
            metadataCancels += 1
            frameCancels += 1
        }

        assertEquals(1, metadataCancels)
        assertEquals(1, frameCancels)
        assertFalse(
            controller.admit(
                consentAllowed = true,
                preference = MobileNetworkPreference.WIFI_ONLY,
                transport = ActiveNetworkTransport.WIFI,
                observedAtMs = 60_000L,
                action = { error("tracking stop must remain fail-closed") },
            ),
        )
    }

    @Test
    fun trackingRestartRequiresANewFirstSensorSampleBeforeStationary() {
        val controller = stationaryController()
        controller.onTrackingStopped {}
        controller.onTrackingStarted(observedAtMs = 10_000L) {}

        assertFalse(
            controller.admit(
                consentAllowed = true,
                preference = MobileNetworkPreference.WIFI_ONLY,
                transport = ActiveNetworkTransport.WIFI,
                observedAtMs = 60_000L,
                action = { error("restart without a sample must stay unknown") },
            ),
        )

        controller.onSensorSample(stepCount = 7, observedAtMs = 60_001L) {}
        assertFalse(
            controller.admit(
                consentAllowed = true,
                preference = MobileNetworkPreference.WIFI_ONLY,
                transport = ActiveNetworkTransport.WIFI,
                observedAtMs = 65_000L,
                action = { error("new first-sample window is not complete") },
            ),
        )
        assertTrue(
            controller.admit(
                consentAllowed = true,
                preference = MobileNetworkPreference.WIFI_ONLY,
                transport = ActiveNetworkTransport.WIFI,
                observedAtMs = 65_001L,
                action = {},
            ),
        )
    }

    private fun stationaryController(): ActivityOriginalUploadAdmissionController {
        return ActivityOriginalUploadAdmissionController(
            stationaryConfirmationMs = 5_000L,
        ).also { controller ->
            controller.onSessionActiveChanged(
                active = true,
                observedAtMs = 0L,
            ) {}
            controller.onTrackingStarted(observedAtMs = 0L) {}
            controller.onSensorSample(stepCount = 0, observedAtMs = 0L) {}
        }
    }
}
