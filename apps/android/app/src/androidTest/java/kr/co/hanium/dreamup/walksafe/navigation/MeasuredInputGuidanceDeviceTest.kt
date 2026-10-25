package kr.co.hanium.dreamup.walksafe.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.navigation.MeasuredInputGuidanceFixture.Companion.report
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real phone pipeline and Korean TTS with explicitly SIMULATED original GPS/heading inputs. */
@RunWith(AndroidJUnit4::class)
class MeasuredInputGuidanceDeviceTest {
    @Test(timeout = 120_000L)
    fun briefGpsJumpBurstRecoversWithoutRenewingRejectedFixOrInterruptingNativeSpeech() = withFixture { f ->
        f.installRoute()
        val action = f.firstStartedSpeech()
        f.burstGps()
        f.awaitRecoveredGps()
        f.assertGpsBurstPreservedOriginalFix()
        f.awaitCompleted(action)
        f.assertHealthyCompleted(action)
        report("CASE_PASS", "case=brief_gps_jump native_action_completed_once=true rejected_original_time_preserved=true")
    }

    @Test(timeout = 120_000L)
    fun briefHeadingAccuracyFaultRecoversThroughCompassWithoutInterruptingNativeSpeech() = withFixture { f ->
        f.installRoute()
        val action = f.firstStartedSpeech()
        f.faultHeading()
        f.awaitRecoveredHeading()
        f.awaitCompleted(action)
        f.assertHealthyCompleted(action)
        report("CASE_PASS", "case=brief_heading_accuracy actual_compass_grace_observed=true native_action_completed_once=true")
    }

    @Test(timeout = 120_000L)
    fun shortSoftGpsJumpIsDownweightedThenRecoversWhileTheNativeInstructionCompletes() = withFixture { f ->
        f.installRoute()
        val action = f.firstStartedSpeech()
        f.burstGps(offsetM = 200.0)
        f.awaitRecoveredGps()
        f.awaitCompleted(action)
        f.assertHealthyCompleted(action)
        f.assertSoftBurstDownweightedAndRecovered(action)
        report("CASE_PASS", "case=short_soft_gps_jump actual_disposition=DOWNWEIGHTED native_action_completed_once=true")
    }

    @Test(timeout = 180_000L)
    fun offRouteApproachSpeaksConcreteDirectionAndDistanceThenFreshGpsJoinsNormalGuidance() = withFixture(30.0) { f ->
        f.installRoute(approach = true)
        val approach = f.firstStartedSpeech()
        assertTrue(approach.message, approach.message.contains("가장 가까운 경로"))
        assertTrue("Approach must give relative clock direction: ${approach.message}", approach.message.contains("시 방향"))
        assertTrue("Approach must label straight distance: ${approach.message}", approach.message.contains("직선거리 약"))
        f.awaitCompleted(approach)
        f.approachRoute()
        val joinedOriginalMs = f.awaitJoined()
        val normal = f.awaitNormalSpeechAfter(approach)
        f.awaitCompleted(normal)
        f.assertApproachCompleted(approach, normal)
        approach.assertCompletedOnce()
        report("CASE_PASS", "case=off_route_approach_to_join new_gps_original_ms=$joinedOriginalMs approach_native_done=1 normal_native_done=1")
    }

    private fun withFixture(northM: Double = 0.0, body: (MeasuredInputGuidanceFixture) -> Unit) {
        assumeTrue("Explicit opt-in required because the actual phone plays Korean speech",
            InstrumentationRegistry.getArguments().getString("allowMeasuredInputGuidanceTest") == "true")
        MeasuredInputGuidanceFixture(northM).use { fixture -> fixture.start(); body(fixture) }
    }
}
