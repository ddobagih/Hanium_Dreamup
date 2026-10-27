package kr.co.hanium.dreamup.walksafe.navigation.start

import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityTier
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupRequirement
import org.junit.Assert.*
import org.junit.Test

class NativePrewalkControllerTest {
    @Test fun constructorMakesNoReadOrSpeechAndDoesNotStartAnyCheck() {
        val f = NativeGuidanceTestFixture()
        assertEquals(0, f.reads)
        assertEquals(0, f.speechSubmissions)
        assertTrue(f.events.isEmpty())
    }

    @Test fun permissionRecoveryStartsOwnedMeasurementsWithoutADestinationOrWalkStart() {
        val f = NativeGuidanceTestFixture()
        assertFalse(f.prewalk.beginPermissionRecoveryRecheck())
        val request = requireNotNull(f.workflow.nativePhoneMountingCheckRequest)
        assertNull(request.destination)
        assertEquals(f.actor, request.actorId)
        assertEquals(f.gatewayGeneration, request.gatewayGeneration)
        assertEquals(f.epoch, request.checkRequest.epoch)
        assertTrue(f.events.contains("confirmEnvironment"))
        assertFalse(f.events.contains("requestDevelopmentGuidanceStart"))
        assertFalse(f.events.contains("completeStartupConfirmation"))
        assertTrue(f.startupPosts.isEmpty())
    }

    @Test fun recoveryCompletesTheRecheckWithoutRequiringOrPostingAStartToken() {
        val f = NativeGuidanceTestFixture()
        f.prewalk.beginPermissionRecoveryRecheck()
        f.prewalk.continueFeaturePreparation()
        assertFalse(f.events.contains("completePermissionRecoveryRecheck"))
        f.completeReadiness()
        f.walk = f.walk.copy(readiness = null)
        f.events.clear()
        f.prewalk.continueFeaturePreparation()
        assertTrue(f.events.contains("completePermissionRecoveryRecheck"))
        assertFalse(f.events.contains("completeStartupConfirmation"))
        assertTrue(f.startupPosts.isEmpty())
    }

    @Test fun recoverySpeechOwnsItsDestinationlessRequestWhilePreservingTheSelectedDestination() {
        for (adjustment in listOf(false, true)) {
            val f = ready()
            f.prewalk.beginPermissionRecoveryRecheck()
            val request = requireNotNull(f.workflow.nativePhoneMountingCheckRequest)
            assertNull(request.destination)
            assertSame(f.destination, f.workflow.pendingUiDestination)
            f.now += 6_000L
            if (adjustment) f.prewalk.announceAdjustment(request) else f.prewalk.announceFailure(request)
            assertEquals(1, f.speechSubmissions)
            assertTrue(requireNotNull(f.lastCurrent).invoke())
            f.gatewayGeneration++
            assertFalse(requireNotNull(f.lastCurrent).invoke())
        }
    }

    @Test fun ordinaryDestinationSpeechStillRejectsChangedDestinationAndRecoveryRejectsChangedEpoch() {
        val ordinary = ready()
        ordinary.prewalk.beginFromHome()
        val ordinaryRequest = requireNotNull(ordinary.workflow.nativePhoneMountingCheckRequest)
        ordinary.workflow.pendingUiDestination = null
        ordinary.prewalk.announceFailure(ordinaryRequest)
        assertEquals(0, ordinary.speechSubmissions)

        val recovery = NativeGuidanceTestFixture()
        recovery.prewalk.beginPermissionRecoveryRecheck()
        val recoveryRequest = requireNotNull(recovery.workflow.nativePhoneMountingCheckRequest)
        recovery.walk = recovery.walk.copy(epoch = recovery.epoch.copy(recoveryGeneration = 1L), readiness = null)
        recovery.prewalk.announceFailure(recoveryRequest)
        assertEquals(0, recovery.speechSubmissions)
    }

    @Test fun recoveryRetiresAnEarlierQueuedStartWithoutDiscardingTheSelectedDestination() {
        val f = ready()
        f.prewalk.beginFromHome()
        f.completeReadiness()
        f.prewalk.continueIfReady()
        val oldStart = f.startupPosts.removeAt(0)
        val oldRequest = requireNotNull(f.workflow.nativePhoneMountingCheckRequest)
        f.prewalk.beginPermissionRecoveryRecheck()
        assertNotEquals(oldRequest, f.workflow.nativePhoneMountingCheckRequest)
        assertSame(f.destination, f.workflow.pendingUiDestination)
        f.events.clear()
        oldStart()
        assertFalse(f.events.contains("completeStartupConfirmation"))
        f.prewalk.continueFeaturePreparation()
        assertTrue(f.events.contains("completePermissionRecoveryRecheck"))
        assertFalse(f.events.contains("completeStartupConfirmation"))
        assertTrue(f.startupPosts.isEmpty())
    }

    @Test fun recoveryRejectsRetiredActorGenerationOrEpochAndFailedMeasurements() {
        for (change in 0..3) {
            val f = NativeGuidanceTestFixture()
            f.prewalk.beginPermissionRecoveryRecheck()
            f.completeReadiness()
            when (change) {
                0 -> f.actor = "replacement"
                1 -> f.gatewayGeneration++
                2 -> f.walk = f.walk.copy(epoch = f.epoch.copy(recoveryGeneration = f.epoch.recoveryGeneration + 1L), readiness = null)
                else -> f.preflightFailed = true
            }
            f.events.clear()
            f.prewalk.continueFeaturePreparation()
            assertFalse(f.events.contains("completePermissionRecoveryRecheck"))
            assertFalse(f.events.contains("completeStartupConfirmation"))
            assertTrue(f.startupPosts.isEmpty())
        }
    }

    @Test fun explicitPreparationKeepsWorkflowEpochRequestAndActorInTheirActualOwners() {
        val f = ready()
        assertFalse(f.prewalk.beginFromHome())
        assertEquals(f.epoch, f.workflow.nativePrewalkStartEpoch)
        assertEquals(f.actor, f.prewalk.actorId)
        val request = f.workflow.nativePhoneMountingCheckRequest!!
        assertSame(f.destination, request.destination)
        assertEquals(f.gatewayGeneration, request.gatewayGeneration)
        assertEquals(f.now, request.checkRequest.requestedAtElapsedRealtimeMs)
        assertEquals(listOf("requestDevelopmentGuidanceStart", "clearStartupRetryRequirement", "showStatus", "confirmEnvironment", "renderMain"), f.events)
    }

    @Test fun changedActorCancelsStalePreparationAndDoesNotCompleteAStart() {
        val f = ready()
        f.prewalk.beginFromHome()
        f.events.clear()
        f.actor = "replacement"
        f.prewalk.continueIfReady()
        assertNull(f.workflow.nativePrewalkStartEpoch)
        assertNull(f.workflow.nativePhoneMountingCheckRequest)
        assertEquals("OWNER_CANCELLED", f.lastPhase)
        assertFalse(f.events.contains("completeStartupConfirmation"))
    }

    @Test fun userConfirmationMustStillOwnReadyMeasurementsWhenTheButtonIsPressed() {
        for (invalidate in listOf<(NativeGuidanceTestFixture) -> Unit>(
            { it.foreground = false }, { it.actor = "other" }, { it.gatewayGeneration++ },
            { it.preflightFailed = true }, { it.readinessStatus = kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus.PENDING },
        )) {
            val f = ready().apply { awaitUserStart = true }
            f.prewalk.beginFromHome(); f.completeReadiness(); f.prewalk.continueIfReady()
            f.startupPosts.single().invoke()
            assertFalse(f.events.contains("completeStartupConfirmation"))
            val (current, confirmed) = f.userStartConfirmations.single()
            assertTrue(current())
            invalidate(f)
            assertFalse(current())
            confirmed()
            assertFalse(f.events.contains("completeStartupConfirmation"))
        }
        val f = ready().apply { awaitUserStart = true }
        f.prewalk.beginFromHome(); f.completeReadiness(); f.prewalk.continueIfReady()
        f.startupPosts.single().invoke()
        f.userStartConfirmations.single().second.invoke()
        assertTrue(f.events.contains("completeStartupConfirmation"))
    }

    @Test fun readyPreparationCompletesOnlyAfterItsCurrentPostedConfirmation() {
        val f = ready()
        f.prewalk.beginFromHome()
        f.completeReadiness()
        f.events.clear()
        f.prewalk.continueIfReady()
        f.prewalk.continueIfReady()
        assertEquals(1, f.startupPosts.size)
        assertFalse(f.events.contains("completeStartupConfirmation"))
        f.startupPosts.single().invoke()
        assertEquals(listOf("direct_start", "completeStartupConfirmation", "renderMain"), f.events)
    }

    @Test fun changedReadinessTokenCannotCompleteAnEarlierPostedStart() {
        val f = ready()
        f.prewalk.beginFromHome()
        f.completeReadiness()
        f.prewalk.continueIfReady()
        f.now++
        f.completeReadiness()
        f.events.clear()
        f.startupPosts.single().invoke()
        assertFalse(f.events.contains("completeStartupConfirmation"))
    }

    @Test fun lastCapabilityUpdateWhileConfirmationIsPostedContinuesWithTheCurrentDecision() {
        val f = ready()
        f.prewalk.beginFromHome()
        f.completeReadiness()
        f.decision = f.decision!!.copy(tier = WalkSafeStartupCapabilityTier.LIMITED,
            unavailableRequirements = listOf(WalkSafeStartupRequirement.METRIC_DISTANCE))
        f.prewalk.continueIfReady()
        val old = f.startupPosts.removeAt(0)
        // The real capability refresh reenters prewalk while the old UI post still owns the slot.
        f.completeReadiness()
        f.prewalk.continueIfReady()
        assertTrue(f.startupPosts.isEmpty())
        f.events.clear()
        old()
        assertFalse(f.events.contains("completeStartupConfirmation"))
        assertEquals("The last normal capability update must not need another sensor event or tap", 1, f.startupPosts.size)
        f.startupPosts.removeAt(0)()
        assertEquals(1, f.events.count { it == "completeStartupConfirmation" })
    }

    @Test fun changedCapabilityThatBlocksStartingCannotReuseTheEarlierConfirmation() {
        val f = ready()
        f.prewalk.beginFromHome()
        f.completeReadiness()
        f.prewalk.continueIfReady()
        val old = f.startupPosts.removeAt(0)
        f.decision = f.decision!!.copy(tier = WalkSafeStartupCapabilityTier.BLOCKED,
            unavailableRequirements = listOf(WalkSafeStartupRequirement.GPS))
        f.prewalk.continueIfReady()
        f.events.clear()
        old()
        assertTrue(f.startupPosts.isEmpty())
        assertFalse(f.events.contains("completeStartupConfirmation"))
    }

    @Test fun retiredActorCannotRepostAChangedCapabilityConfirmation() {
        val f = ready()
        f.prewalk.beginFromHome()
        f.completeReadiness()
        f.prewalk.continueIfReady()
        val old = f.startupPosts.removeAt(0)
        f.decision = f.decision!!.copy(noticeKo = "새 기능 측정")
        f.actor = "replacement"
        f.events.clear()
        old()
        assertTrue(f.startupPosts.isEmpty())
        assertFalse(f.events.contains("completeStartupConfirmation"))
    }

    @Test fun failedMeasurementKeepsItsAttemptVisibleInsteadOfAutomaticallyRestartingSensors() {
        val f = ready()
        f.prewalk.beginFromHome()
        val request = f.workflow.nativePhoneMountingCheckRequest
        f.events.clear()
        f.preflightFailed = true
        f.prewalk.continueIfReady()
        assertSame(request, f.workflow.nativePhoneMountingCheckRequest)
        assertFalse(f.events.contains("confirmEnvironment"))
        assertEquals("MEASUREMENT_FAILED_OR_TIMED_OUT", f.lastPhase)
        assertEquals(1, f.speechSubmissions)
    }

    @Test fun acceptedFailureSpeechRemainsInFlightUntilActualDoneAndCannotOverlap() {
        val f = ready()
        f.prewalk.beginFromHome()
        val request = f.workflow.nativePhoneMountingCheckRequest!!
        f.prewalk.announceFailure(request)
        f.prewalk.announceFailure(request)
        assertEquals(1, f.speechSubmissions)
        f.lastCompleted!!.invoke()
        f.prewalk.announceFailure(request)
        assertEquals(1, f.speechSubmissions)
    }

    @Test fun transientFailureHasOneOwnedRetryAndExhaustionPublishesSpecificFailure() {
        val f = ready()
        f.prewalk.beginFromHome()
        f.prewalk.announceFailure(f.workflow.nativePhoneMountingCheckRequest!!)
        f.lastFailed!!.invoke()
        assertEquals(1_000L, f.failurePosts.single().first)
        f.failurePosts.single().second.invoke()
        assertEquals(2, f.speechSubmissions)
        f.lastFailed!!.invoke()
        assertEquals(1, f.failurePosts.size)
        assertEquals("음성 안내를 재생하지 못했습니다. 한국어 음성 설정을 확인하고 다시 시도해 주세요.", f.preparation.visibleFailure)
    }

    @Test fun delayedFailureRetryCannotSurviveAnActorReplacement() {
        val f = ready()
        f.prewalk.beginFromHome()
        f.prewalk.announceFailure(f.workflow.nativePhoneMountingCheckRequest!!)
        f.lastFailed!!.invoke()
        f.actor = "replacement"
        f.failurePosts.single().second.invoke()
        assertEquals(1, f.speechSubmissions)
    }

    @Test fun activeContinuationPreservesTheActualSensorRequestAndCancelsOnlyPrewalk() {
        val f = ready()
        f.prewalk.beginFromHome()
        val request = f.workflow.nativePhoneMountingCheckRequest
        f.runtimeActive = true
        f.prewalk.continueIfReady()
        assertSame(request, f.workflow.nativePhoneMountingCheckRequest)
        assertNull(f.workflow.nativePrewalkStartEpoch)
        assertNull(f.prewalk.actorId)
    }

    @Test fun canceledSpeechAttemptTombstoneRejectsAStaleRequestButAllowsANewerOne() {
        val f = ready()
        f.prewalk.beginFromHome()
        val first = f.workflow.nativePhoneMountingCheckRequest!!
        f.prewalk.announceFailure(first)
        f.prewalk.cancel(false)
        f.workflow.nativePhoneMountingCheckRequest = first
        f.workflow.nativePrewalkStartEpoch = first.checkRequest.epoch
        f.prewalk.announceFailure(first)
        assertEquals(1, f.speechSubmissions)
        f.workflow.nativePrewalkStartEpoch = null
        f.prewalk.beginFromHome()
        f.prewalk.announceFailure(f.workflow.nativePhoneMountingCheckRequest!!)
        assertEquals(2, f.speechSubmissions)
    }

    private fun ready() = NativeGuidanceTestFixture().apply {
        workflow.pendingNativeUiPage = NativeUiPage.DESTINATION_CONFIRM
        workflow.pendingUiDestination = destination
    }
}
