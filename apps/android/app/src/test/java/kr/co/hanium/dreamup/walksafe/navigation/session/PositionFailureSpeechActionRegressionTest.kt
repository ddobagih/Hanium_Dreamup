package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.*
import org.junit.Assert.*
import org.junit.Test
import kr.co.hanium.dreamup.walksafe.startup.capability.OfficialEnvironmentPresentation
import sun.misc.Unsafe

/** Real confidence grace, Navigator decision latch, controller and queued speech validator. */
class PositionFailureSpeechActionRegressionTest {
    @Test fun manualRecheckKeepsItsRequiredActionAcrossLowAndUnavailablePosition() {
        for (quality in listOf(PositionQuality.LOW, PositionQuality.UNAVAILABLE)) {
            val f = fixture(); f.install()
            f.nav.update(f.fix, f.now, false, facingObservation = f.heading.observation)
            requireManualRecheckOutsideCorridor(f)
            assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, f.nav.pendingUserDecision())
            val policy = PositionConfidencePolicy()
            val decision = degrade(f, policy, quality)
            assertTrue(f.controller.applyPositionConfidenceDecision(snapshot(decision)))
            val speech = f.statusSent.single()
            assertTrue(speech.message, speech.message.contains("위치 다시 확인이라고"))
            assertFalse(speech.message, speech.message.contains("이어갑니다"))
            assertTrue(speech.current())
            speech.done()
            assertFalse(policy.isAnnouncementCurrent(decision.announcementRequest!!.token))
            assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, f.nav.pendingUserDecision())
        }
    }

    @Test fun ordinaryActivePositionLossExplainsItsAutomaticRecovery() {
        val f = fixture(); f.install()
        val policy = PositionConfidencePolicy()
        val decision = degrade(f, policy, PositionQuality.UNAVAILABLE)
        assertNull(f.nav.pendingUserDecision())
        assertTrue(f.controller.applyPositionConfidenceDecision(snapshot(decision)))
        val speech = f.statusSent.single()
        assertTrue(speech.message, speech.message.contains("현재 위치를 확인할 수 없어"))
        assertTrue(speech.message, speech.message.contains("위치가 확인되면") && speech.message.contains("이어갑니다"))
        assertTrue(speech.current())
    }

    @Test fun queuedAutomaticRecoveryPromiseRetiresWhenManualRecheckBecomesRequired() {
        val f = fixture(); f.install()
        f.nav.update(f.fix, f.now, false, facingObservation = f.heading.observation)
        val policy = PositionConfidencePolicy()
        val decision = degrade(f, policy, PositionQuality.UNAVAILABLE)
        f.controller.applyPositionConfidenceDecision(snapshot(decision))
        val queued = f.statusSent.single(); assertTrue(queued.current())
        requireManualRecheckOutsideCorridor(f)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, f.nav.pendingUserDecision())
        assertFalse(queued.current())
        queued.done()
        assertTrue(policy.isAnnouncementCurrent(decision.announcementRequest!!.token))
    }

    @Test fun expiredQuestionDoesNotAuthorizeAutomaticRecoveryFromLatchedDeviation() {
        val f = fixture(); f.install()
        f.nav.update(f.fix, f.now, false, facingObservation = f.heading.observation)
        requireManualRecheckOutsideCorridor(f)
        f.now += 30_000
        assertNull(f.nav.pendingUserDecision())
        assertFalse(f.nav.canResumeAfterPositionRecovery())
        val policy = PositionConfidencePolicy()
        val decision = degrade(f, policy, PositionQuality.UNAVAILABLE)
        f.controller.applyPositionConfidenceDecision(snapshot(decision))
        val speech = f.statusSent.single()
        assertTrue(speech.current())
        assertFalse(speech.message, speech.message.contains("이어갑니다"))
        assertFalse(f.nav.canResumeAfterPositionRecovery())
    }

    private fun requireManualRecheckOutsideCorridor(f: GuidanceFixture) {
        f.now += 1L
        val outside = f.fix.copy(latitude = f.fix.latitude + 22.0 / 111_195.0,
            elapsedRealtimeMs = f.now)
        val update = f.nav.update(outside, f.now, false, facingObservation = f.heading.observation)
        assertEquals(RouteMatchReason.CORRIDOR_OUTSIDE, f.nav.currentRouteMatch()?.reason)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, update.pendingUserDecision)
        assertFalse(f.nav.canResumeAfterPositionRecovery())
    }

    private fun fixture(): GuidanceFixture {
        // The real Main port uses this pure formatter. No SDK or display state is read by it.
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val formatter = (field.get(null) as Unsafe).allocateInstance(OfficialEnvironmentPresentation::class.java)
            as OfficialEnvironmentPresentation
        return GuidanceFixture().apply { unavailableMessage = formatter::locationRecoveryActionMessage }
    }

    private fun degrade(f: GuidanceFixture, policy: PositionConfidencePolicy,
        quality: PositionQuality): PositionConfidenceDecision {
        policy.observe(PositionQuality.HIGH, f.now)
        policy.observe(PositionQuality.UNAVAILABLE, f.now + 100)
        f.now += 3_100
        var decision = policy.observe(PositionQuality.UNAVAILABLE, f.now)
        if (quality == PositionQuality.LOW) {
            f.now += 1
            decision = policy.observe(PositionQuality.LOW, f.now)
        }
        assertTrue(decision.guidancePaused)
        assertNotNull(decision.announcementRequest)
        val request = requireNotNull(decision.announcementRequest)
        f.captureStatus = true
        f.confidenceLease = object : RouteGuidanceController.ConfidenceLease {
            override fun isCurrent() = policy.isAnnouncementCurrent(request.token)
            override fun delivered(nowMs: Long) { policy.commitAnnouncementDelivered(request.token, nowMs) }
        }
        return decision
    }

    private fun snapshot(decision: PositionConfidenceDecision) = PositioningSnapshot(
        null, null, decision.quality, decision,
        GnssQualitySnapshot(0L, null, 0, 0, null, null, null, null,
            GnssFrequencySummary(0, 0, 0, 0, 0, DualFrequencyObservationState.UNKNOWN),
            SignalEnvironmentRisk.UNKNOWN, null, 1.0, true),
    )
}
