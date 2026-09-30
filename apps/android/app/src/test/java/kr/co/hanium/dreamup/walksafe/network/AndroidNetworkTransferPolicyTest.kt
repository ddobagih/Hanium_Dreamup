package kr.co.hanium.dreamup.walksafe.network

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

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
        val controller = ActivityOriginalUploadAdmissionController()
        val actionStarted = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val admitted = executor.submit<Boolean> {
                controller.admit(
                    walkState = WalkSessionState.ENDED,
                    consentAllowed = true,
                    charging = { true },
                    preference = MobileNetworkPreference.WIFI_ONLY,
                    transport = ActiveNetworkTransport.WIFI,
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
                // Owner decision 3(b): a new walk that becomes ACTIVE closes and cancels.
                controller.onWalkStateChanged(WalkSessionState.ACTIVE) {
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
                    walkState = WalkSessionState.ACTIVE,
                    consentAllowed = true,
                    charging = { true },
                    preference = MobileNetworkPreference.WIFI_ONLY,
                    transport = ActiveNetworkTransport.WIFI,
                    action = { error("an active walk must not re-enter") },
                ),
            )
        } finally {
            releaseAction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun aWalkThatBecomesActiveOrPausedClosesAndCancelsAndOnlyThePostWalkStateAdmits() {
        val controller = ActivityOriginalUploadAdmissionController()
        val actionRan = AtomicBoolean(false)
        var cancellations = 0
        controller.onWalkStateChanged(WalkSessionState.PAUSED) { cancellations += 1 }
        assertFalse(
            controller.admit(
                walkState = WalkSessionState.PAUSED,
                consentAllowed = true,
                charging = { true },
                preference = MobileNetworkPreference.ALLOW_CELLULAR,
                transport = ActiveNetworkTransport.WIFI,
            ) { actionRan.set(true) },
        )
        assertFalse(actionRan.get())
        controller.onWalkStateChanged(WalkSessionState.ENDED) { cancellations += 1 }
        assertEquals(1, cancellations)
        // R4: a system SAFE_STOP is not an explicit end; it admits nothing.
        assertFalse(
            controller.admit(
                walkState = WalkSessionState.SAFE_STOP,
                consentAllowed = true,
                charging = { true },
                preference = MobileNetworkPreference.WIFI_ONLY,
                transport = ActiveNetworkTransport.WIFI,
                action = {},
            ),
        )
        for (after in listOf(WalkSessionState.ENDED, WalkSessionState.READY)) {
            assertTrue(
                controller.admit(
                    walkState = after,
                    consentAllowed = true,
                    charging = { true },
                    preference = MobileNetworkPreference.WIFI_ONLY,
                    transport = ActiveNetworkTransport.WIFI,
                    action = {},
                ),
            )
        }
    }

    @Test
    fun postWalkAdmissionNeedsConsentChargingAndTheNetworkSettingButNoStillness() {
        val decide = { state: WalkSessionState, consent: Boolean, charging: Boolean,
            preference: MobileNetworkPreference, transport: ActiveNetworkTransport ->
            AndroidActivityOriginalUploadPolicy.decideAfterWalk(state, consent, { charging }, preference, transport)
        }
        assertEquals(
            ActivityOriginalUploadDecision.BLOCKED_WHILE_WALKING,
            decide(WalkSessionState.ACTIVE, true, true, MobileNetworkPreference.WIFI_ONLY, ActiveNetworkTransport.WIFI),
        )
        assertEquals(
            ActivityOriginalUploadDecision.FAIL_CLOSED,
            decide(WalkSessionState.ENDED, false, true, MobileNetworkPreference.WIFI_ONLY, ActiveNetworkTransport.WIFI),
        )
        assertEquals(
            ActivityOriginalUploadDecision.QUEUED_UNTIL_CHARGING,
            decide(WalkSessionState.ENDED, true, false, MobileNetworkPreference.WIFI_ONLY, ActiveNetworkTransport.WIFI),
        )
        assertEquals(
            ActivityOriginalUploadDecision.WIFI_ALLOWED,
            decide(WalkSessionState.ENDED, true, true, MobileNetworkPreference.WIFI_ONLY, ActiveNetworkTransport.WIFI),
        )
        assertEquals(
            ActivityOriginalUploadDecision.QUEUED_UNTIL_WIFI,
            decide(WalkSessionState.ENDED, true, true, MobileNetworkPreference.WIFI_ONLY, ActiveNetworkTransport.CELLULAR),
        )
        // transfer-F4: after the walk originals follow the RAW rule (Wi-Fi only) even with mobile data allowed.
        assertEquals(
            ActivityOriginalUploadDecision.QUEUED_UNTIL_WIFI,
            decide(WalkSessionState.READY, true, true, MobileNetworkPreference.ALLOW_CELLULAR, ActiveNetworkTransport.CELLULAR),
        )
        assertEquals(
            ActivityOriginalUploadDecision.WIFI_ALLOWED,
            decide(WalkSessionState.READY, true, true, MobileNetworkPreference.ALLOW_CELLULAR, ActiveNetworkTransport.WIFI),
        )
        assertEquals(
            ActivityOriginalUploadDecision.FAIL_CLOSED,
            decide(WalkSessionState.ENDED, true, true, MobileNetworkPreference.ALLOW_CELLULAR, ActiveNetworkTransport.OFFLINE),
        )
    }

    @Test
    fun chargingIsReadOnlyAfterTheWalkAndConsentGatesAndAFailingReadIsNotCharging() {
        var reads = 0
        val controller = ActivityOriginalUploadAdmissionController()
        assertFalse(
            controller.isAllowed(
                walkState = WalkSessionState.ACTIVE,
                consentAllowed = true,
                charging = { reads += 1; true },
                preference = MobileNetworkPreference.WIFI_ONLY,
                transport = ActiveNetworkTransport.WIFI,
            ),
        )
        assertEquals(0, reads)
        assertFalse(
            controller.isAllowed(
                walkState = WalkSessionState.ENDED,
                consentAllowed = true,
                charging = { error("battery state unreadable") },
                preference = MobileNetworkPreference.WIFI_ONLY,
                transport = ActiveNetworkTransport.WIFI,
            ),
        )
    }
}
