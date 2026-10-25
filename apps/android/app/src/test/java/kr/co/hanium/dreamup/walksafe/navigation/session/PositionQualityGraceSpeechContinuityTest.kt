package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.*
import kr.co.hanium.dreamup.walksafe.startup.capability.OfficialEnvironmentPresentation
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

/** Actual Guide/Nav: an undelivered request cannot contradict the authoritative pause flag. */
class PositionQualityGraceSpeechContinuityTest {
    @Test fun lowQualityWithoutGuidancePauseDoesNotSpeakAStopOrCancelTheCurrentSentence() {
        val f = activeFixture()
        val original = f.sent.single()
        val policy = policy()
        policy.observe(PositionQuality.HIGH, f.now)
        f.now += 100L
        val decision = withUndeliveredNotice(policy.observe(PositionQuality.LOW, f.now), f.now)
        assertFalse(decision.guidancePaused)
        bindUndeliveredLease(f)

        assertFalse(f.controller.applyPositionConfidenceDecision(snapshot(decision)))
        assertFalse(f.controller.positionPaused)
        assertTrue("Unexpected pause voice: ${f.statusSent.map { it.message }}", f.statusSent.isEmpty())
        assertEquals(0, f.events.count { it == "cancel" })
        assertTrue(original.current())
    }

    @Test fun unavailableDegradationPendingRetainsSpeechWithoutAFalseStopNotice() {
        val f = activeFixture()
        val original = f.sent.single()
        val policy = policy()
        policy.observe(PositionQuality.HIGH, f.now)
        f.now += 100L
        val decision = withUndeliveredNotice(policy.observe(PositionQuality.UNAVAILABLE, f.now), f.now)
        assertEquals(PositionConfidenceTransition.DEGRADATION_PENDING, decision.transition)
        assertFalse(decision.guidancePaused)
        bindUndeliveredLease(f)

        assertFalse(f.controller.applyPositionConfidenceDecision(snapshot(decision)))
        assertFalse(f.controller.positionPaused)
        assertTrue("Unexpected pause voice: ${f.statusSent.map { it.message }}", f.statusSent.isEmpty())
        assertEquals(0, f.events.count { it == "cancel" })
        assertTrue(original.current())
    }

    @Test fun aRealPositionPauseProducesOneCurrentAutomaticRecoveryNotice() {
        val f = activeFixture()
        val policy = policy()
        val decision = pausedDecision(f, policy)
        bindLease(f, policy, decision)

        assertTrue(f.controller.applyPositionConfidenceDecision(snapshot(decision)))
        assertTrue(f.controller.positionPaused)
        assertEquals(1, f.events.count { it == "cancel" })
        assertAutomaticRecovery(f.statusSent.single())
    }

    @Test fun qualityLostRecheckExplainsAutomaticRecoveryWithoutDemandingManualConfirmation() {
        val f = activeFixture()
        f.nav.onUntrustedLocation()
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, f.nav.pendingUserDecision())
        assertTrue(f.nav.canResumeAfterPositionRecovery())
        val policy = policy()
        val decision = pausedDecision(f, policy)
        bindLease(f, policy, decision)

        assertTrue(f.controller.applyPositionConfidenceDecision(snapshot(decision)))
        assertAutomaticRecovery(f.statusSent.single())
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, f.nav.pendingUserDecision())
    }

    private fun withUndeliveredNotice(decision: PositionConfidenceDecision, atMs: Long) = decision.copy(
        announcementRequest = PositionConfidenceAnnouncementRequest(1L, 1L,
            if (decision.quality == PositionQuality.LOW) PositionConfidenceAnnouncementKind.LOW
            else PositionConfidenceAnnouncementKind.UNAVAILABLE, atMs, repeated = false),
    )

    private fun bindUndeliveredLease(f: GuidanceFixture) {
        f.captureStatus = true
        // The request is still owned; only a real delivered notice may consume it.
        f.confidenceLease = object : RouteGuidanceController.ConfidenceLease {
            override fun isCurrent() = true
            override fun delivered(nowMs: Long) = fail("A grace notice must not be marked delivered")
        }
    }

    private fun assertAutomaticRecovery(speech: GuidanceFixture.Submission) {
        assertTrue(speech.message, speech.message.contains("현재 위치를 확인할 수 없어"))
        assertTrue(speech.message, speech.message.contains("위치가 확인되면") && speech.message.contains("이어갑니다"))
        assertFalse(speech.message, speech.message.contains("위치 다시 확인이라고"))
        assertTrue(speech.current())
    }

    private fun activeFixture(): GuidanceFixture {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val formatter = (field.get(null) as Unsafe).allocateInstance(OfficialEnvironmentPresentation::class.java)
            as OfficialEnvironmentPresentation
        return GuidanceFixture().apply {
            unavailableMessage = formatter::locationRecoveryActionMessage
            install()
            controller.updateRouteGuidance(fix)
            assertEquals(1, sent.size)
            assertTrue(sent.single().current())
        }
    }

    private fun policy() = PositionConfidencePolicy(
        PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 9_000L),
    )

    private fun pausedDecision(f: GuidanceFixture, policy: PositionConfidencePolicy): PositionConfidenceDecision {
        policy.observe(PositionQuality.HIGH, f.now)
        val onset = f.now + 100L
        assertFalse(policy.observe(PositionQuality.UNAVAILABLE, onset).guidancePaused)
        f.now = onset + 9_000L
        return policy.observe(PositionQuality.UNAVAILABLE, f.now).also {
            assertTrue(it.guidancePaused)
            assertNotNull(it.announcementRequest)
        }
    }

    private fun bindLease(f: GuidanceFixture, policy: PositionConfidencePolicy, decision: PositionConfidenceDecision) {
        f.captureStatus = true
        val request = requireNotNull(decision.announcementRequest)
        f.confidenceLease = object : RouteGuidanceController.ConfidenceLease {
            override fun isCurrent() = policy.isAnnouncementCurrent(request.token)
            override fun delivered(nowMs: Long) { policy.commitAnnouncementDelivered(request.token, nowMs) }
        }
    }

    private fun snapshot(decision: PositionConfidenceDecision) = PositioningSnapshot(
        null, null, decision.quality, decision,
        GnssQualitySnapshot(0L, null, 0, 0, null, null, null, null,
            GnssFrequencySummary(0, 0, 0, 0, 0, DualFrequencyObservationState.UNKNOWN),
            SignalEnvironmentRisk.UNKNOWN, null, 1.0, true),
    )
}
