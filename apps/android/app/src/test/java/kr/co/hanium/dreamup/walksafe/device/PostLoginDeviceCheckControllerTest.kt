package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

class PostLoginDeviceCheckControllerTest {
    @Test fun phaseDiagnosticsDistinguishSavingFromPassAndNeverExposeActorText() {
        val events = mutableListOf<DeviceCheckTraceEvent>()
        val f = Fixture(DeviceCheckTraceSink { events += it })
        f.running(); f.ready(); f.controller.evaluate()
        val saving = events.indexOfFirst { it.stage == DeviceCheckTraceStage.SAVING }
        val passed = events.indexOfFirst { it.stage == DeviceCheckTraceStage.PUBLISH && it.outcome == DeviceCheckTraceOutcome.SUCCEEDED }
        assertTrue(saving >= 0); assertTrue(passed > saving)
        val attemptEvents = events.filter { it.attemptGeneration == 1L }
        assertEquals(1, attemptEvents.map { it.flowId }.distinct().size)
        assertFalse(events.joinToString("\n", transform = ::formatDeviceCheckTrace).contains("actor"))
        assertEquals(PostLoginDeviceCheckState.FULL, f.controller.snapshot.state)
    }

    @Test fun failingDiagnosticsDoNotChangePassOrFailurePublication() {
        val passed = Fixture(DeviceCheckTraceSink { error("diagnostic channel failed") })
        passed.running(); passed.ready(); passed.controller.evaluate()
        assertEquals(PostLoginDeviceCheckState.FULL, passed.controller.snapshot.state)
        val failed = Fixture(DeviceCheckTraceSink { error("diagnostic channel failed") })
        failed.running(); failed.controller.enteredBackground()
        assertEquals(PostLoginDeviceCheckState.FAIL, failed.controller.snapshot.state)
        assertEquals(PostLoginDeviceCheckFailure.BACKGROUNDED, failed.controller.snapshot.failure)
    }

    @Test fun nativeRetirementMustBeConfirmedBeforeRuntimeProbesOrReadyAcceptance() {
        val f = Fixture()
        var released: ((Boolean) -> Unit)? = null
        f.probes.lease = { _, callback -> released = callback }
        f.running(); f.ready(); f.controller.evaluate()
        assertEquals(0, f.calls.count { it == "prepareModels" })
        assertEquals(0, f.calls.count { it == "startAutomatic" })
        assertEquals(0, f.store.saves)
        released!!.invoke(true)
        assertEquals(1, f.calls.count { it == "prepareModels" })
        released!!.invoke(false)
        assertEquals(1, f.calls.count { it == "prepareModels" })
    }

    @Test fun overdueRetirementAckCannotRestartTheTimedOutAttempt() {
        val f = Fixture()
        var released: ((Boolean) -> Unit)? = null
        f.probes.lease = { _, callback -> released = callback }
        f.running(); f.nowMs = f.controller.runtimeDeadlineMs!!
        released!!.invoke(true)
        assertEquals(PostLoginDeviceCheckFailure.CHECK_TIMEOUT, f.controller.snapshot.failure)
        assertEquals(0, f.calls.count { it == "prepareModels" })
        assertEquals(0, f.store.saves)
    }

    @Test fun strictImageCheckQueriesSupportFirstAndDoesNotRequireACpuBootstrap() {
        val f = Fixture()
        f.probes.joint = true
        f.reader.hardware = f.reader.hardware.copy(detector = PostLoginDeviceCheckDetectorFacts(
            true, false, false, false, PostLoginDeviceCheckSignal.UNAVAILABLE))
        f.running()
        f.probes.value = f.probes.value.copy(wakePhrase = PostLoginDeviceCheckSignal.READY)
        f.controller.maybeContinue()
        assertTrue(f.calls.contains("metricStart"))
        assertFalse(f.calls.contains("cameraStart"))
        f.probes.value = f.probes.value.copy(metricDepth = PostLoginMetricDepthState.SUPPORTED)
        f.controller.maybeContinue()
        assertTrue(f.calls.contains("cameraStart"))
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
        assertEquals(0, f.store.saves)
    }

    @Test fun compositeFailureEndsLogicallyWhileItsPhysicalCleanupIsStillPending() {
        val f = Fixture().running()
        f.ready()
        f.probes.value = f.probes.value.copy(cameraPipeline = PostLoginDeviceCheckSignal.PENDING,
            compositeRuntimeActive = true, compositeRuntimeFailed = true)
        f.controller.evaluate()
        assertEquals(PostLoginDeviceCheckFailure.CAMERA_PIPELINE_UNAVAILABLE, f.controller.snapshot.failure)
        assertEquals(0, f.store.saves)
        assertFalse(f.controller.passesFeatureGate())
    }
    @Test fun timerCancellationCannotMixANewerActorsSignalsIntoTheDecidedWrite() {
        val f = Fixture().running()
        f.ready()
        f.store.pendingSave = CompletableFuture()
        f.store.onSave = { assertEquals("actor", f.reader.actor.actorId) }
        f.scheduler.tasks.first().onCancel = {
            f.scheduler.tasks.first().onCancel = {}
            f.reader.actor = f.reader.actor.copy(actorId = "other", verifiedActorId = "other", sessionGeneration = 2L)
            f.controller.bindSession("other", 2L)
            f.controller.beginFromPrimaryAction()
        }
        f.controller.evaluate()
        assertEquals(1, f.store.saves)
        assertEquals(listOf(PostLoginMetricDepthState.SUPPORTED), f.store.savedDepths)
        assertEquals("other", f.controller.snapshot.actorId)
        assertTrue(f.controller.runtimeDeadlineMs != null)
        assertFalse(f.scheduler.tasks.last().cancelled)
    }

    @Test fun successfulOldProbeTeardownCannotStopANewActorsCameraOrAdvanceOnboarding() {
        val f = Fixture().running()
        f.store.pendingSave = CompletableFuture()
        f.ready(); f.controller.evaluate()
        var stopsAfterNewAttempt = -1
        f.probes.onFunctionalCancel = {
            f.probes.onFunctionalCancel = {}
            f.reader.actor = f.reader.actor.copy(actorId = "other", verifiedActorId = "other", sessionGeneration = 2L)
            f.controller.bindSession("other", 2L)
            f.controller.beginFromPrimaryAction()
            stopsAfterNewAttempt = f.calls.count { it == "cameraStop" }
        }
        f.store.pendingSave!!.complete(DeviceCheckAuthorityOutcome.Committed(Unit, 1, "write"))
        assertEquals("other", f.controller.snapshot.actorId)
        assertEquals(stopsAfterNewAttempt, f.calls.count { it == "cameraStop" })
        assertEquals(0, f.effects.passes)
        assertTrue(f.controller.runtimeDeadlineMs != null)
    }
    @Test fun aBackgroundedCapabilityAckCannotBeMisreportedAsAnUnknownWrite() {
        val f = Fixture()
        f.store.restored = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.LIMITED,
            setOf(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE), false, false, PostLoginMetricDepthState.PENDING)
        f.controller.bindSession("actor", 1L)
        f.store.pendingSave = CompletableFuture()
        f.controller.completeStoredMetricRefresh(true, f.controller.snapshot.bindingOrNull!!)
        val timer = f.scheduler.tasks.lastOrNull()
        f.reader.actor = f.reader.actor.copy(foreground = false)
        f.controller.enteredBackground()
        f.store.pendingSave!!.complete(DeviceCheckAuthorityOutcome.Committed(Unit, 2, "update"))
        f.reader.actor = f.reader.actor.copy(foreground = true)
        timer?.action?.invoke()
        assertEquals(0, f.store.unconfirmed)
        assertEquals(PostLoginDeviceCheckState.LIMITED, f.controller.snapshot.state)
        assertFalse(f.calls.contains("metricChanged:true"))
    }
    @Test fun capabilityRefreshDoesNotApplyBeforeItsDurableAck() {
        for (speech in listOf(false, true)) {
            val f = Fixture()
            val disabled = if (speech) PostLoginDeviceCheckFeature.VOICE_GUIDANCE
                else PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE
            f.store.restored = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.LIMITED,
                setOf(disabled), false, false, PostLoginMetricDepthState.PENDING)
            f.controller.bindSession("actor", 1L)
            f.store.pendingSave = CompletableFuture()
            if (speech) f.controller.recoverKoreanSpeechPassedGate()
            else f.controller.completeStoredMetricRefresh(true, f.controller.snapshot.bindingOrNull!!)
            assertTrue(disabled in f.controller.snapshot.disabledFeatures)
            f.store.pendingSave!!.complete(DeviceCheckAuthorityOutcome.Committed(Unit, 2, "update"))
            assertFalse(disabled in f.controller.snapshot.disabledFeatures)
        }
    }

    @Test fun lateCapabilityRefreshCannotPublishIntoAnotherActor() {
        val f = Fixture()
        f.store.expectedBinding = f.reader.resultBinding("actor")
        f.store.restored = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.LIMITED,
            setOf(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE), false, false, PostLoginMetricDepthState.PENDING)
        f.controller.bindSession("actor", 1L)
        f.store.pendingSave = CompletableFuture()
        f.controller.completeStoredMetricRefresh(true, f.controller.snapshot.bindingOrNull!!)
        f.reader.actor = f.reader.actor.copy(actorId = "other", verifiedActorId = "other", sessionGeneration = 2L)
        f.controller.bindSession("other", 2L)
        f.store.pendingSave!!.complete(DeviceCheckAuthorityOutcome.Committed(Unit, 2, "update"))
        assertEquals("other", f.controller.snapshot.actorId)
        assertEquals(PostLoginDeviceCheckState.NOT_RUN, f.controller.snapshot.state)
        assertFalse(f.calls.contains("metricChanged:true"))
    }
    @Test fun theCommitRequestIsFrozenWhileTheActorAndPairAreStillCurrent() {
        val f = Fixture().running()
        f.store.pendingSave = CompletableFuture()
        f.store.onSave = { assertTrue(f.reader.actor.foreground) }
        f.effects.onRefresh = {
            if (f.controller.snapshot.state == PostLoginDeviceCheckState.SAVING) {
                f.reader.actor = f.reader.actor.copy(foreground = false)
                f.controller.enteredBackground()
            }
        }
        f.ready(); f.controller.evaluate()
        assertEquals(1, f.store.saves)
        assertEquals(0, f.effects.passes)
    }

    @Test fun pairRevocationDuringAckPublicationLeavesAnExplicitRetryableFailure() {
        val f = Fixture().running()
        f.store.pendingSave = CompletableFuture()
        f.ready(); f.controller.evaluate()
        f.effects.onInvalidation = { f.store.pairReady = false }
        f.store.pendingSave!!.complete(DeviceCheckAuthorityOutcome.Committed(Unit, 1, "write"))
        assertFalse(f.controller.snapshot.passesFeatureGate)
        assertEquals(PostLoginDeviceCheckState.FAIL, f.controller.snapshot.state)
        assertEquals(0, f.effects.passes)
    }

    @Test fun rejectedOldFailurePublicationCannotCancelANewActorsRuntime() {
        for (pairFailure in listOf(false, true)) {
            val f = Fixture().running()
            f.store.pendingSave = CompletableFuture()
            f.ready(); f.controller.evaluate()
            f.effects.onInvalidation = {
                f.effects.onInvalidation = {}
                f.reader.actor = f.reader.actor.copy(actorId = "other", verifiedActorId = "other", sessionGeneration = 2L)
                f.controller.bindSession("other", 2L)
                f.controller.beginFromPrimaryAction()
            }
            if (pairFailure) f.store.pairReady = false
            f.store.pendingSave!!.complete(if (pairFailure) DeviceCheckAuthorityOutcome.Committed(Unit, 1, "write")
                else DeviceCheckAuthorityOutcome.NotPublished("disk"))
            assertEquals("other", f.controller.snapshot.actorId)
            assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
            assertTrue(f.controller.runtimeDeadlineMs != null)
            assertFalse(f.scheduler.tasks.last().cancelled)
        }
    }
    @Test fun noRuntimeWorkOrReadyAcceptanceBeforeAttemptJournalAck() {
        val f = Fixture()
        f.store.pendingBegin = CompletableFuture()
        f.running(); f.ready(); f.controller.evaluate()
        assertEquals(0, f.calls.count { it == "prepareModels" })
        assertEquals(0, f.calls.count { it == "startAutomatic" })
        assertEquals(0, f.store.saves)
        assertFalse(f.controller.passesFeatureGate())
        f.store.pendingBegin!!.complete(DeviceCheckAuthorityOutcome.Committed(Unit, 1, "begin"))
        assertEquals(1, f.calls.count { it == "prepareModels" })
    }

    @Test fun overdueOrCancelledAttemptAckCannotStartRuntimeWork() {
        for (cancel in listOf(false, true)) {
            val f = Fixture()
            f.store.pendingBegin = CompletableFuture()
            f.running()
            if (cancel) f.controller.enteredBackground() else {
                f.nowMs = f.controller.runtimeDeadlineMs!!
                f.scheduler.tasks.first().action()
            }
            f.store.pendingBegin!!.complete(DeviceCheckAuthorityOutcome.Committed(Unit, 1, "begin"))
            assertEquals(0, f.calls.count { it == "prepareModels" })
            assertEquals(0, f.calls.count { it == "startAutomatic" })
            assertFalse(f.controller.passesFeatureGate())
        }
    }

    @Test fun durablePublicationMustFinishBeforeTheFeatureGateOpens() {
        val f = Fixture().running()
        f.store.pendingSave = CompletableFuture()
        f.ready()
        f.controller.evaluate()
        assertFalse(f.controller.passesFeatureGate())
        assertFalse(f.controller.snapshot.passesFeatureGate)
        assertEquals(0, f.effects.passes)
        f.store.pendingSave!!.complete(DeviceCheckAuthorityOutcome.Committed(Unit, 1, "write"))
        assertTrue(f.controller.passesFeatureGate())
        assertEquals(1, f.effects.passes)
    }

    @Test fun lateDurableAckCannotPassAfterBackgroundOrSessionChange() {
        for (sessionChange in listOf(false, true)) {
            val f = Fixture().running()
            f.store.pendingSave = CompletableFuture()
            f.ready(); f.controller.evaluate()
            if (sessionChange) {
                f.reader.actor = f.reader.actor.copy(actorId = "other", verifiedActorId = "other", sessionGeneration = 2L)
                f.controller.bindSession("other", 2L)
            } else {
                f.reader.actor = f.reader.actor.copy(foreground = false)
                f.controller.enteredBackground()
            }
            f.store.pendingSave!!.complete(DeviceCheckAuthorityOutcome.Committed(Unit, 1, "write"))
            assertFalse(f.controller.passesFeatureGate())
            assertEquals(0, f.effects.passes)
        }
    }

    @Test fun currentPairRevocationBeforeDurableAckCannotBecomePassed() {
        val f = Fixture().running()
        f.store.pendingSave = CompletableFuture()
        f.ready(); f.controller.evaluate()
        f.store.pairReady = false
        f.store.pendingSave!!.complete(DeviceCheckAuthorityOutcome.Committed(Unit, 1, "write"))
        assertFalse(f.controller.passesFeatureGate())
        assertEquals(0, f.effects.passes)
    }

    @Test fun savingTimeoutIsIndeterminateAndLateAckDoesNotOpenGate() {
        val f = Fixture().running()
        f.store.pendingSave = CompletableFuture()
        f.ready(); f.controller.evaluate()
        assertFalse(f.controller.passesFeatureGate())
        f.nowMs += 30_000L
        f.scheduler.tasks.last().action()
        assertEquals(1, f.store.unconfirmed)
        assertFalse(f.controller.passesFeatureGate())
        f.store.pendingSave!!.complete(DeviceCheckAuthorityOutcome.Committed(Unit, 1, "write"))
        assertEquals(0, f.effects.passes)
        assertFalse(f.controller.passesFeatureGate())
    }
    @Test fun constructionDoesNotReadFactsStartProbesOrApplyEffects() {
        val f = Fixture()
        assertEquals(0, f.reader.reads)
        assertTrue(f.calls.isEmpty())
        assertEquals(PostLoginDeviceCheckState.NOT_RUN, f.controller.snapshot.state)
    }

    @Test fun snapshotOutputOwnersAreInvalidatedBeforeTheNewStateIsVisible() {
        val f = Fixture()
        f.controller.bindSession("actor", 1L)
        assertEquals(listOf(PostLoginDeviceCheckState.NOT_RUN, PostLoginDeviceCheckState.NOT_RUN), f.effects.statesDuringInvalidation)
        assertEquals(listOf("route", "speech"), f.calls.filter { it == "route" || it == "speech" })
        assertEquals("actor", f.controller.snapshot.actorId)
    }

    @Test fun explicitUserAttemptStartsOnceAndCompletesOnlyAfterCurrentProbeEvidence() {
        val f = Fixture().running()
        val binding = f.controller.snapshot.bindingOrNull!!
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
        assertEquals(300_000L, f.scheduler.tasks.single().delay)
        f.ready()
        f.controller.maybeContinue(binding)
        assertEquals(PostLoginDeviceCheckState.FULL, f.controller.snapshot.state)
        assertEquals(1, f.store.saves)
        assertEquals(1, f.effects.passes)
        f.controller.maybeContinue(binding)
        assertEquals(1, f.store.saves)
        assertTrue(f.scheduler.tasks.single().cancelled)
        assertNull(f.controller.runtimeDeadlineMs)
    }

    @Test fun repeatedRuntimeStartUsesTheOriginalAttemptDeadlineAndDoesNotRestartProbes() {
        val f = Fixture().running()
        val binding = f.controller.snapshot.bindingOrNull!!
        assertEquals(400_000L, f.controller.runtimeDeadlineMs)
        val callsBefore = f.calls.toList()
        f.nowMs += 30_000L
        f.controller.startRuntime(binding)
        assertEquals(400_000L, f.controller.runtimeDeadlineMs)
        assertEquals(1, f.scheduler.tasks.size)
        assertEquals(callsBefore, f.calls)

        f.controller.enteredBackground()
        assertNull(f.controller.runtimeDeadlineMs)
        f.nowMs += 10_000L
        f.controller.beginFromPrimaryAction()
        assertEquals(440_000L, f.controller.runtimeDeadlineMs)
        assertEquals(2, f.scheduler.tasks.size)
        assertTrue(f.scheduler.tasks.first().cancelled)
    }

    @Test fun expiredReadyResultsFailBeforeFullOrLimitedWithoutWaitingForTheTimer() {
        for (directEvaluation in listOf(false, true)) for (offset in listOf(0L, 1L)) for (limited in listOf(false, true)) {
            val f = Fixture().running()
            val binding = f.controller.snapshot.bindingOrNull!!
            val timer = f.scheduler.tasks.single()
            f.ready()
            if (limited) f.probes.value = f.probes.value.copy(metricDepth = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED)
            f.nowMs = f.controller.runtimeDeadlineMs!! + offset
            // Current ownership remains live so an overdue timer can still perform its transition.
            assertTrue(f.controller.isRunningBindingCurrent(binding))
            if (directEvaluation) f.controller.evaluate(binding) else f.controller.maybeContinue(binding)
            assertEquals(PostLoginDeviceCheckState.FAIL, f.controller.snapshot.state)
            assertEquals(PostLoginDeviceCheckFailure.CHECK_TIMEOUT, f.controller.snapshot.failure)
            assertEquals(0, f.store.saves)
            assertEquals(0, f.effects.passes)
            assertEquals(0, f.effects.calibrations)
            assertEquals(1, f.effects.terminals)
            assertTrue(timer.cancelled)
            assertNull(f.controller.runtimeDeadlineMs)
            timer.action()
            f.controller.maybeContinue(binding)
            assertEquals(1, f.effects.terminals)
        }
    }

    @Test fun readyResultsJustBeforeTheDeadlineStillPassThroughEitherEntryPoint() {
        for (directEvaluation in listOf(false, true)) for (limited in listOf(false, true)) {
            val f = Fixture().running()
            f.ready()
            if (limited) f.probes.value = f.probes.value.copy(metricDepth = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED)
            f.nowMs = f.controller.runtimeDeadlineMs!! - 1L
            if (directEvaluation) f.controller.evaluate() else f.controller.maybeContinue()
            assertEquals(if (limited) PostLoginDeviceCheckState.LIMITED else PostLoginDeviceCheckState.FULL, f.controller.snapshot.state)
            assertEquals(1, f.store.saves)
            assertEquals(1, f.effects.passes)
        }
    }

    @Test fun deadlineCrossingDuringHardwareObservationCannotPassOrStartAnotherProbe() {
        val f = Fixture().running()
        f.ready()
        val deadline = f.controller.runtimeDeadlineMs!!
        val cameraStartsBefore = f.calls.count { it == "cameraStart" }
        val metricStartsBefore = f.calls.count { it == "metricStart" }
        f.nowMs = deadline - 1L
        f.reader.onHardware = { f.nowMs = deadline }
        f.controller.maybeContinue()
        assertEquals(PostLoginDeviceCheckFailure.CHECK_TIMEOUT, f.controller.snapshot.failure)
        assertEquals(0, f.store.saves)
        assertEquals(cameraStartsBefore, f.calls.count { it == "cameraStart" })
        assertEquals(metricStartsBefore, f.calls.count { it == "metricStart" })
    }

    @Test fun deadlineCrossingDuringCompletionPublicationCannotSaveThePassedCandidate() {
        val f = Fixture().running()
        f.ready()
        val deadline = f.controller.runtimeDeadlineMs!!
        f.nowMs = deadline - 1L
        f.effects.onInvalidation = { f.nowMs = deadline }
        f.controller.evaluate()
        assertEquals(PostLoginDeviceCheckFailure.CHECK_TIMEOUT, f.controller.snapshot.failure)
        assertEquals(0, f.store.saves)
        assertEquals(0, f.effects.passes)
    }

    @Test fun deadlineCrossingWhileResolvingTheResultBindingCannotStartStorage() {
        val f = Fixture().running()
        f.ready()
        val deadline = f.controller.runtimeDeadlineMs!!
        f.nowMs = deadline - 1L
        f.reader.onResultBinding = { f.nowMs = deadline }
        f.controller.evaluate()
        assertEquals(PostLoginDeviceCheckFailure.CHECK_TIMEOUT, f.controller.snapshot.failure)
        assertEquals(0, f.store.saves)
        assertEquals(0, f.effects.passes)
    }

    @Test fun expiredOldBindingCannotTimeOutANewerAttempt() {
        val f = Fixture().running()
        val old = f.controller.snapshot.bindingOrNull!!
        f.controller.enteredBackground()
        f.controller.beginFromPrimaryAction()
        val current = f.controller.snapshot.bindingOrNull!!
        f.ready()
        f.nowMs = f.controller.runtimeDeadlineMs!! + 1L
        f.controller.evaluate(old)
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
        assertEquals(current, f.controller.snapshot.bindingOrNull)
        f.controller.evaluate(current)
        assertEquals(PostLoginDeviceCheckFailure.CHECK_TIMEOUT, f.controller.snapshot.failure)
        assertEquals(0, f.store.saves)
    }

    @Test fun runtimeClockRollbackCannotAcceptReadyResults() {
        for (now in listOf(-1L, 99_999L)) {
            val f = Fixture().running()
            f.ready()
            f.nowMs = now
            f.controller.evaluate()
            assertEquals(PostLoginDeviceCheckFailure.CHECK_TIMEOUT, f.controller.snapshot.failure)
            assertEquals(0, f.store.saves)
            assertEquals(0, f.effects.passes)
        }
    }

    @Test fun synchronousCapabilityExpiryCannotStartModelsOrAutomaticProbesAfterFailure() {
        val f = Fixture()
        f.probes.onRestart = {
            f.nowMs = f.controller.runtimeDeadlineMs!!
            f.probes.changed!!.invoke()
        }
        f.controller.bindSession("actor", 1L)
        f.controller.beginFromPrimaryAction()
        assertEquals(PostLoginDeviceCheckFailure.CHECK_TIMEOUT, f.controller.snapshot.failure)
        assertEquals(0, f.calls.count { it == "prepareModels" })
        assertEquals(0, f.calls.count { it == "startAutomatic" })
        assertEquals(0, f.store.saves)
    }

    @Test fun synchronousModelPreparationExpiryCannotStartAutomaticProbesAfterFailure() {
        val f = Fixture()
        f.probes.onPrepareModels = {
            f.nowMs = f.controller.runtimeDeadlineMs!!
            f.controller.evaluate()
        }
        f.controller.bindSession("actor", 1L)
        f.controller.beginFromPrimaryAction()
        assertEquals(PostLoginDeviceCheckFailure.CHECK_TIMEOUT, f.controller.snapshot.failure)
        assertEquals(1, f.calls.count { it == "prepareModels" })
        assertEquals(0, f.calls.count { it == "startAutomatic" })
        assertEquals(0, f.store.saves)
    }

    @Test fun synchronousCapabilitySessionChangeCannotPrepareTheOldActorsModels() {
        val f = Fixture()
        f.probes.onRestart = {
            f.reader.actor = f.reader.actor.copy(actorId = "other", verifiedActorId = "other", sessionGeneration = 2L)
            f.controller.bindSession("other", 2L)
        }
        f.controller.bindSession("actor", 1L)
        f.controller.beginFromPrimaryAction()
        assertEquals("other", f.controller.snapshot.actorId)
        assertEquals(PostLoginDeviceCheckState.NOT_RUN, f.controller.snapshot.state)
        assertEquals(0, f.calls.count { it == "prepareModels" })
        assertEquals(0, f.calls.count { it == "startAutomatic" })
    }

    @Test fun reentrantNewAttemptDuringHardwareReadCannotStartAProbeForTheOldBinding() {
        val f = Fixture().running()
        val old = f.controller.snapshot.bindingOrNull!!
        val oldCameraStarts = f.probes.cameraBindings.count { it == old }
        val oldMetricStarts = f.probes.metricBindings.count { it == old }
        var next: PostLoginDeviceCheckBinding? = null
        f.reader.onHardware = {
            f.reader.onHardware = {}
            f.controller.enteredBackground()
            f.controller.beginFromPrimaryAction()
            next = f.controller.snapshot.bindingOrNull
        }
        f.controller.maybeContinue(old)
        assertTrue(next != null && next != old)
        assertEquals(next, f.controller.snapshot.bindingOrNull)
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
        assertEquals(oldCameraStarts, f.probes.cameraBindings.count { it == old })
        assertEquals(oldMetricStarts, f.probes.metricBindings.count { it == old })
        assertEquals(0, f.store.saves)
    }

    @Test fun cameraPipelinePendingBlocksPassingAndSavingUntilCompositeCompletion() {
        val f = Fixture().running()
        f.ready()
        f.probes.value = f.probes.value.copy(cameraPipeline = PostLoginDeviceCheckSignal.PENDING)
        val binding = f.controller.snapshot.bindingOrNull!!
        f.controller.maybeContinue(binding)
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
        assertEquals(0, f.store.saves)
        assertEquals(0, f.effects.passes)

        f.probes.value = f.probes.value.copy(cameraPipeline = PostLoginDeviceCheckSignal.READY)
        f.controller.maybeContinue(binding)
        assertEquals(PostLoginDeviceCheckState.FULL, f.controller.snapshot.state)
        assertEquals(1, f.store.saves)
        assertEquals(1, f.effects.passes)
    }

    @Test fun failedCameraExecutionCannotBecomeFullWhenOtherSignalsAreReady() {
        val f = Fixture().running()
        f.ready()
        f.probes.value = f.probes.value.copy(cameraPipeline = PostLoginDeviceCheckSignal.UNAVAILABLE)
        f.controller.maybeContinue(f.controller.snapshot.bindingOrNull!!)
        assertEquals(PostLoginDeviceCheckState.FAIL, f.controller.snapshot.state)
        assertEquals(PostLoginDeviceCheckFailure.CAMERA_PIPELINE_UNAVAILABLE, f.controller.snapshot.failure)
        assertEquals(0, f.store.saves)
        assertEquals(0, f.effects.passes)
        assertFalse(f.controller.passesFeatureGate())
    }

    @Test fun queuedCompositeFailureCannotBecomeLimitedDuringTheTemporaryDetectorRetirement() {
        val f = Fixture().running()
        f.ready()
        f.reader.hardware = f.reader.hardware.copy(detector = PostLoginDeviceCheckDetectorFacts(
            true, false, false, false, PostLoginDeviceCheckSignal.UNAVAILABLE))
        f.probes.value = f.probes.value.copy(cameraPipeline = PostLoginDeviceCheckSignal.PENDING,
            compositeRuntimeActive = true)
        val binding = f.controller.snapshot.bindingOrNull!!
        // A resource callback arrives after native retirement but before the result dispatcher.
        f.controller.maybeContinue(binding)
        f.controller.evaluate(binding)
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
        assertEquals(PostLoginDeviceCheckSignal.PENDING, f.controller.observation().detector)
        assertFalse(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION in f.controller.observation().unsupportedFeatures)
        assertEquals(0, f.store.saves)
        assertEquals(0, f.effects.passes)

        f.probes.value = f.probes.value.copy(cameraPipeline = PostLoginDeviceCheckSignal.UNAVAILABLE,
            compositeRuntimeActive = false, compositeRuntimeFailed = true)
        f.controller.maybeContinue(binding)
        assertEquals(PostLoginDeviceCheckState.FAIL, f.controller.snapshot.state)
        assertEquals(PostLoginDeviceCheckFailure.CAMERA_PIPELINE_UNAVAILABLE, f.controller.snapshot.failure)
        assertEquals(0, f.store.saves)
    }

    @Test fun activeCompositeCannotPassEvenIfAnOlderCameraSignalIsReady() {
        val f = Fixture().running()
        f.ready()
        f.probes.value = f.probes.value.copy(compositeRuntimeActive = true)
        val binding = f.controller.snapshot.bindingOrNull!!
        f.controller.maybeContinue(binding)
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
        assertEquals(PostLoginDeviceCheckSignal.PENDING, f.controller.observation().cameraPipeline)
        assertEquals(0, f.store.saves)
        f.probes.value = f.probes.value.copy(compositeRuntimeActive = false)
        f.controller.maybeContinue(binding)
        assertEquals(PostLoginDeviceCheckState.FULL, f.controller.snapshot.state)
        assertEquals(1, f.store.saves)
    }

    @Test fun fixtureFailureAfterPrimaryRetirementCannotDowngradeIntoAPassedLimitedResult() {
        val f = Fixture().running()
        f.ready()
        f.reader.hardware = f.reader.hardware.copy(detector = PostLoginDeviceCheckDetectorFacts(
            true, false, false, false, PostLoginDeviceCheckSignal.UNAVAILABLE))
        f.probes.value = f.probes.value.copy(cameraPipeline = PostLoginDeviceCheckSignal.UNAVAILABLE,
            compositeRuntimeFailed = true)
        f.controller.maybeContinue(f.controller.snapshot.bindingOrNull!!)
        assertEquals(PostLoginDeviceCheckState.FAIL, f.controller.snapshot.state)
        assertEquals(PostLoginDeviceCheckFailure.CAMERA_PIPELINE_UNAVAILABLE, f.controller.snapshot.failure)
        assertEquals(0, f.store.saves)
        assertEquals(0, f.effects.passes)
    }

    @Test fun fixtureReadyStillRequiresTheSeparateDepthSupportProbeBeforePassing() {
        val f = Fixture().running()
        f.ready()
        f.probes.value = f.probes.value.copy(metricDepth = PostLoginMetricDepthState.PENDING)
        f.controller.maybeContinue(f.controller.snapshot.bindingOrNull!!)
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
        assertEquals(1, f.calls.count { it == "metricStart" })
        assertEquals(0, f.store.saves)
        f.probes.value = f.probes.value.copy(metricDepth = PostLoginMetricDepthState.SUPPORTED)
        f.controller.maybeContinue(f.controller.snapshot.bindingOrNull!!)
        assertEquals(PostLoginDeviceCheckState.FULL, f.controller.snapshot.state)
        assertEquals(1, f.store.saves)
    }

    @Test fun aLateResultFromThePreviousActorCannotPassOrSave() {
        val f = Fixture().running()
        val binding = f.controller.snapshot.bindingOrNull!!
        f.reader.actor = f.reader.actor.copy(actorId = "other", verifiedActorId = "other", sessionGeneration = 2L)
        f.ready()
        f.controller.evaluate(binding)
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
        assertEquals(0, f.store.saves)
        assertEquals(0, f.effects.passes)
    }

    @Test fun previousAttemptCannotApplyEvidenceToANewAttempt() {
        val f = Fixture().running()
        val old = f.controller.snapshot.bindingOrNull!!
        f.controller.enteredBackground()
        f.controller.beginFromPrimaryAction()
        val current = f.controller.snapshot.bindingOrNull!!
        assertTrue(current.attemptGeneration > old.attemptGeneration)
        f.ready()
        f.controller.evaluate(old)
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
        f.controller.evaluate(current)
        assertEquals(PostLoginDeviceCheckState.FULL, f.controller.snapshot.state)
    }

    @Test fun durableSaveFailureNeverInvokesPassedEffectsOrCalibration() {
        val f = Fixture().running()
        f.store.saveSucceeds = false
        f.ready()
        f.controller.evaluate()
        assertEquals(PostLoginDeviceCheckFailure.RESULT_SAVE_FAILED, f.controller.snapshot.failure)
        assertFalse(f.controller.passesFeatureGate())
        assertEquals(0, f.effects.passes)
        assertEquals(0, f.effects.calibrations)
        assertTrue(f.calls.contains("cameraStop"))
    }

    @Test fun cancelledCapabilityCallbackAndTimeoutCannotAdvanceTheAttempt() {
        val f = Fixture().running()
        val changed = f.probes.changed!!
        val timeout = f.scheduler.tasks.single()
        f.controller.cancel("test_cancel")
        f.ready()
        changed()
        timeout.action()
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
        assertEquals(0, f.store.saves)
        assertEquals(0, f.effects.passes)
        assertTrue(timeout.cancelled)
        val cancelIndex = f.calls.indexOf("calibrationCancel:device_check_test_cancel")
        assertTrue(cancelIndex >= 0)
        assertTrue(f.calls.drop(cancelIndex + 1).contains("functionalCancel"))
    }

    @Test fun currentOverallTimeoutFailsOnceAndLateTimeoutIsIgnored() {
        val f = Fixture().running()
        val timeout = f.scheduler.tasks.single()
        timeout.action()
        assertEquals(PostLoginDeviceCheckFailure.CHECK_TIMEOUT, f.controller.snapshot.failure)
        assertEquals(1, f.effects.terminals)
        timeout.action()
        assertEquals(1, f.effects.terminals)
    }

    @Test fun ownedPermissionDialogRetainsAttemptAndCompletesAfterForegroundReturn() {
        val f = Fixture()
        f.permissions.missing = listOf("camera")
        f.controller.bindSession("actor", 1L)
        f.controller.beginFromPrimaryAction()
        val binding = f.controller.snapshot.bindingOrNull!!
        assertEquals(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS, f.controller.snapshot.state)
        f.controller.enteredBackground()
        assertEquals(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS, f.controller.snapshot.state)
        f.reader.actor = f.reader.actor.copy(foreground = false)
        f.controller.handlePermissionResult(binding)
        assertTrue(f.controller.permissionResultPending)
        f.reader.actor = f.reader.actor.copy(foreground = true)
        f.controller.resumeDeferredPermissionObservation()
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
        assertFalse(f.controller.permissionResultPending)
    }

    @Test fun disclosurePersistenceFailureDoesNotCreateAnAttemptOrStartAProbe() {
        val f = Fixture()
        f.controller.bindSession("actor", 1L)
        f.permissions.disclosureSaved = false
        f.controller.beginFromPrimaryAction()
        assertEquals(PostLoginDeviceCheckState.NOT_RUN, f.controller.snapshot.state)
        assertNull(f.probes.changed)
        assertTrue(f.calls.contains("status:안전 고지 저장 실패"))
    }

    @Test fun permissionObservationRejectionOrExceptionEndsTheAttemptAndAllowsUserRetry() {
        for (throwing in listOf(false, true)) {
            val f = Fixture()
            f.controller.bindSession("actor", 1L)
            val previousResult = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.FULL, emptySet(), false, true,
                PostLoginMetricDepthState.SUPPORTED)
            f.store.restored = previousResult
            f.effects.permissionObservation = { if (throwing) throw IllegalStateException("rejected") else false }
            f.controller.beginFromPrimaryAction()
            val rejected = f.controller.snapshot.bindingOrNull!!
            assertEquals(PostLoginDeviceCheckState.FAIL, f.controller.snapshot.state)
            assertEquals("ONBOARDING_TRANSITION_REJECTED", f.controller.snapshot.failure?.name)
            assertNull(f.controller.runtimeDeadlineMs)
            assertTrue(f.scheduler.tasks.isEmpty())
            assertFalse(f.calls.contains("prepareModels"))
            assertFalse(f.calls.contains("startAutomatic"))
            assertEquals(0, f.store.saves)
            assertTrue(previousResult === f.store.restored)
            assertTrue(f.calls.contains("status:기기 점검 단계 확인 필요"))

            f.effects.permissionObservation = { true }
            f.controller.beginFromPrimaryAction()
            assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
            assertTrue(f.controller.snapshot.attemptGeneration > rejected.attemptGeneration)
            assertTrue(f.controller.runtimeDeadlineMs != null)
            assertEquals(1, f.scheduler.tasks.size)
            assertTrue(previousResult === f.store.restored)
        }
    }

    @Test fun synchronousPermissionObservationReentryPublishesOnlyOneRejectedAttempt() {
        val f = Fixture()
        f.controller.bindSession("actor", 1L)
        f.effects.permissionObservation = { binding ->
            f.controller.completePermissionObservation(binding)
            false
        }
        f.controller.beginFromPrimaryAction()
        assertEquals(1, f.effects.permissionObservations)
        assertEquals(PostLoginDeviceCheckState.FAIL, f.controller.snapshot.state)
        assertEquals(1, f.effects.terminals)
        assertEquals(0, f.store.saves)
        assertTrue(f.scheduler.tasks.isEmpty())
    }

    @Test fun permissionObservationCannotAcceptResultsOrStartRuntimeWorkBeforeApproval() {
        for (outcome in listOf("accept", "reject", "throw")) {
            val f = Fixture()
            var observedDuringEffect: PostLoginDeviceCheckState? = null
            var savesDuringEffect = -1
            var preparationsDuringEffect = -1
            var automaticProbesDuringEffect = -1
            f.effects.permissionObservation = { binding ->
                f.ready()
                f.controller.evaluate(binding)
                f.controller.maybeContinue(binding)
                f.controller.startRuntime(binding)
                observedDuringEffect = f.controller.snapshot.state
                savesDuringEffect = f.store.saves
                preparationsDuringEffect = f.calls.count { it == "prepareModels" }
                automaticProbesDuringEffect = f.calls.count { it == "startAutomatic" }
                if (outcome == "throw") throw IllegalStateException("observation not approved")
                outcome == "accept"
            }
            f.controller.bindSession("actor", 1L)
            f.controller.beginFromPrimaryAction()
            assertEquals(PostLoginDeviceCheckState.RUNNING, observedDuringEffect)
            assertEquals(0, savesDuringEffect)
            assertEquals(0, preparationsDuringEffect)
            assertEquals(0, automaticProbesDuringEffect)
            if (outcome == "accept") {
                assertEquals(PostLoginDeviceCheckState.FULL, f.controller.snapshot.state)
                assertEquals(1, f.store.saves)
            } else {
                assertEquals(PostLoginDeviceCheckState.FAIL, f.controller.snapshot.state)
                assertEquals(PostLoginDeviceCheckFailure.ONBOARDING_TRANSITION_REJECTED, f.controller.snapshot.failure)
                assertEquals(0, f.store.saves)
                assertTrue(f.scheduler.tasks.isEmpty())
            }
        }
    }

    @Test fun stalePermissionRejectionCannotFailTheNewActorsState() {
        for (throwing in listOf(false, true)) {
            val f = Fixture()
            f.controller.bindSession("actor", 1L)
            f.effects.permissionObservation = {
                f.reader.actor = f.reader.actor.copy(actorId = "other", verifiedActorId = "other", sessionGeneration = 2L)
                f.controller.bindSession("other", 2L)
                if (throwing) throw IllegalStateException("old observation") else false
            }
            f.controller.beginFromPrimaryAction()
            assertEquals("other", f.controller.snapshot.actorId)
            assertEquals(PostLoginDeviceCheckState.NOT_RUN, f.controller.snapshot.state)
            assertNull(f.controller.snapshot.failure)
            assertEquals(0, f.effects.terminals)
            assertTrue(f.scheduler.tasks.isEmpty())
        }
    }

    @Test fun rejectedPassContinuationShowsRecoveryAndAllowsRetryWithoutDeletingMeasuredData() {
        val f = Fixture().running()
        val previousResult = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.FULL, emptySet(), false, true,
            PostLoginMetricDepthState.SUPPORTED)
        f.store.restored = previousResult
        f.effects.passContinuation = { PostLoginDeviceCheckPassContinuation.TRANSITION_REJECTED }
        f.ready()
        f.controller.evaluate()
        assertEquals(PostLoginDeviceCheckState.FAIL, f.controller.snapshot.state)
        assertEquals("ONBOARDING_TRANSITION_REJECTED", f.controller.snapshot.failure?.name)
        assertFalse(f.controller.passesFeatureGate())
        assertEquals(1, f.store.saves)
        assertEquals(0, f.effects.calibrations)
        assertTrue(previousResult === f.store.restored)
        assertTrue(f.calls.contains("status:기기 점검 단계 확인 필요"))
        f.effects.passContinuation = { PostLoginDeviceCheckPassContinuation.COMPLETED }
        f.controller.beginFromPrimaryAction()
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
    }

    @Test fun aReentrantNewAttemptCannotBeFailedByTheOlderPassContinuationRejection() {
        val f = Fixture().running()
        var nextBinding: PostLoginDeviceCheckBinding? = null
        f.effects.passContinuation = {
            f.controller.handlePrimaryAction(deferredChecksCanBeRetried = true)
            nextBinding = f.controller.snapshot.bindingOrNull
            PostLoginDeviceCheckPassContinuation.TRANSITION_REJECTED
        }
        f.ready()
        f.controller.evaluate()
        assertEquals(PostLoginDeviceCheckState.RUNNING, f.controller.snapshot.state)
        assertEquals(nextBinding, f.controller.snapshot.bindingOrNull)
        assertNull(f.controller.snapshot.failure)
        assertTrue(f.controller.runtimeDeadlineMs != null)
    }

    @Test fun restoredResultsKeepCameraDeferralAndRequireStoredMetricPolicyRefresh() {
        val f = Fixture()
        f.store.restored = PersistedPostLoginDeviceCheckResult(
            PostLoginDeviceCheckState.LIMITED,
            setOf(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE),
            true, false, PostLoginMetricDepthState.PENDING,
        )
        f.controller.bindSession("actor", 1L)
        assertEquals(PostLoginDeviceCheckState.LIMITED, f.controller.snapshot.state)
        assertTrue(f.controller.cameraDependentChecksDeferred)
        assertTrue(f.controller.storedMetricRefreshIsPending)
        assertEquals(PostLoginDeviceCheckCapabilityOverrides(false, false, null), f.effects.overrides.last())
    }

    @Test fun sameDeviceReauthenticationRebindsTheSavedResultWithoutStartingAnotherAttempt() {
        val f = Fixture()
        f.store.expectedBinding = f.reader.resultBinding("actor")
        f.store.restored = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.FULL,
            emptySet(), false, true, PostLoginMetricDepthState.SUPPORTED)
        f.controller.bindSession("actor", 1L)
        val old = f.controller.snapshot.bindingOrNull!!
        f.reader.actor = f.reader.actor.copy(sessionGeneration = 2L)

        f.controller.bindSession("actor", 2L)

        assertTrue(f.controller.passesFeatureGate())
        assertEquals(2L, f.controller.snapshot.sessionGeneration)
        assertFalse(f.controller.isSnapshotBindingLive(old))
        assertNull(f.probes.changed)
        assertEquals(0, f.store.saves)
        assertTrue(f.scheduler.tasks.isEmpty())
    }

    @Test fun changedInstallationCannotRetainAPassedInMemoryResultWithTheSameSessionGeneration() {
        val f = Fixture()
        f.store.expectedBinding = f.reader.resultBinding("actor")
        f.store.restored = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.FULL,
            emptySet(), false, true, PostLoginMetricDepthState.SUPPORTED)
        f.controller.bindSession("actor", 1L)
        assertTrue(f.controller.passesFeatureGate())
        f.reader.installationId = "replacement-installation"

        f.controller.bindSession("actor", 1L)

        assertEquals(PostLoginDeviceCheckState.NOT_RUN, f.controller.snapshot.state)
        assertFalse(f.controller.passesFeatureGate())
        assertEquals(0, f.store.saves)
        assertNull(f.probes.changed)
        assertTrue(f.store.restored != null)
    }

    @Test fun unchangedDeviceBindingKeepsThePassedResultAndOtherActorsCannotReuseIt() {
        val f = Fixture()
        f.store.expectedBinding = f.reader.resultBinding("actor")
        f.store.restored = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.FULL,
            emptySet(), false, true, PostLoginMetricDepthState.SUPPORTED)
        f.controller.bindSession("actor", 1L)
        val before = f.controller.snapshot
        f.controller.bindSession("actor", 1L)
        assertTrue(f.controller.snapshot === before)
        assertTrue(f.controller.passesFeatureGate())
        f.reader.actor = f.reader.actor.copy(actorId = "other", verifiedActorId = "other", sessionGeneration = 2L)

        f.controller.bindSession("other", 2L)

        assertEquals(PostLoginDeviceCheckState.NOT_RUN, f.controller.snapshot.state)
        assertFalse(f.controller.passesFeatureGate())
        assertEquals(0, f.store.saves)
        assertTrue(f.store.restored != null)
    }

    @Test fun storedMetricRefreshSaveFailureKeepsTheOriginalSnapshotAndRetryContext() {
        val f = Fixture()
        f.store.restored = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.LIMITED,
            setOf(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE), false, false, PostLoginMetricDepthState.PENDING)
        f.controller.bindSession("actor", 1L)
        val before = f.controller.snapshot
        f.store.saveSucceeds = false
        assertFalse(f.controller.completeStoredMetricRefresh(true, before.bindingOrNull!!))
        assertEquals(before, f.controller.snapshot)
        assertTrue(f.controller.storedMetricRefreshIsPending)
        assertFalse(f.calls.contains("metricChanged:true"))
    }

    @Test fun storedMetricRefreshChangesOnlyTheMetricCapabilityAfterDurableSave() {
        val f = Fixture()
        f.store.restored = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.LIMITED,
            setOf(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE, PostLoginDeviceCheckFeature.HANDS_FREE_VOICE),
            false, false, PostLoginMetricDepthState.PENDING)
        f.controller.bindSession("actor", 1L)
        val overridesCount = f.effects.overrides.size
        assertTrue(f.controller.completeStoredMetricRefresh(true, f.controller.snapshot.bindingOrNull!!))
        assertEquals(setOf(PostLoginDeviceCheckFeature.HANDS_FREE_VOICE), f.controller.snapshot.disabledFeatures)
        assertEquals(overridesCount, f.effects.overrides.size)
        assertTrue(f.calls.contains("metricChanged:true"))
        assertFalse(f.controller.storedMetricRefreshIsPending)
    }

    @Test fun changedSavedPrerequisitesRetireThePassedGateAndOutputOwners() {
        val f = Fixture()
        f.store.restored = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.FULL, emptySet(), false, true, PostLoginMetricDepthState.SUPPORTED)
        f.controller.bindSession("actor", 1L)
        f.store.restored = null
        assertTrue(f.controller.revalidateCompletedPrerequisites())
        assertEquals(PostLoginDeviceCheckState.NOT_RUN, f.controller.snapshot.state)
        assertFalse(f.controller.passesFeatureGate())
    }

    @Test fun temporaryConsumerHandoffRetainsHistoricalResultButCannotOpenTheFeatureGate() {
        val f = Fixture()
        f.store.gateRestoreOnPair = true
        f.store.restored = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.FULL,
            emptySet(), false, true, PostLoginMetricDepthState.SUPPORTED)
        f.controller.bindSession("actor", 1L)
        assertTrue(f.controller.passesFeatureGate())
        f.store.pairReady = false
        assertFalse(f.controller.revalidateCompletedPrerequisites())
        f.controller.bindSession("actor", 1L)
        assertEquals(PostLoginDeviceCheckState.FULL, f.controller.snapshot.state)
        assertFalse(f.controller.passesFeatureGate())
        f.store.pairReady = true
        assertTrue(f.controller.passesFeatureGate())
        f.controller.reportRuntimeLost(PostLoginDeviceCheckBinding("previous", 0, 1))
        assertEquals(PostLoginDeviceCheckState.FULL, f.controller.snapshot.state)
        f.store.pairReady = false
        f.controller.reportRuntimeLost(requireNotNull(f.controller.snapshot.bindingOrNull))
        assertEquals(PostLoginDeviceCheckState.FAIL, f.controller.snapshot.state)
        assertEquals(PostLoginDeviceCheckFailure.DETECTOR_UNAVAILABLE, f.controller.snapshot.failure)
        assertEquals(0, f.store.saves)
    }

    @Test fun unsupportedMetricDepthRemainsALimitedFeatureRatherThanForgedSupport() {
        val f = Fixture().running()
        f.ready()
        f.probes.value = f.probes.value.copy(metricDepth = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED)
        val observation = f.controller.observation()
        assertTrue(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE in observation.unsupportedFeatures)
        f.controller.evaluate(observation = observation)
        assertEquals(PostLoginDeviceCheckState.LIMITED, f.controller.snapshot.state)
    }

    @Test fun actualResourceAndSpeechFailuresRetainTheirBlockingReasons() {
        val f = Fixture().running()
        f.ready()
        f.reader.hardware = f.reader.hardware.copy(resourceReadiness = WalkSessionReadinessStatus.UNAVAILABLE)
        assertEquals(PostLoginDeviceCheckFailure.DEVICE_RESOURCE, f.controller.observation().blockingFailure)
        f.reader.hardware = f.reader.hardware.copy(resourceReadiness = WalkSessionReadinessStatus.READY,
            startup = f.reader.hardware.startup.copy(offlineKoreanTextToSpeechAvailable = false))
        assertEquals(PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE, f.controller.observation().blockingFailure)
    }

    private class Fixture(trace: DeviceCheckTraceSink = DeviceCheckTraceSink.NONE) {
        var nowMs = 100_000L
        val calls = mutableListOf<String>()
        val reader = Reader()
        val store = Store()
        val probes = Probes(calls)
        val permissions = Permissions()
        val scheduler = Scheduler()
        val effects = Effects(calls)
        val controller = PostLoginDeviceCheckController(reader, store, probes, permissions, effects, scheduler,
            PostLoginDeviceCheckController.Config(), elapsedRealtimeMs = { nowMs }, trace = trace)
        init { effects.snapshot = { controller.snapshot } }
        fun running() = apply { controller.bindSession("actor", 1L); controller.beginFromPrimaryAction() }
        fun ready() { probes.value = PostLoginDeviceCheckProbeSignals(PostLoginDeviceCheckSignal.READY, PostLoginDeviceCheckSignal.READY, PostLoginDeviceCheckSignal.READY, PostLoginMetricDepthState.SUPPORTED) }
    }

    private class Reader : PostLoginDeviceCheckFactsReader {
        var reads = 0
        var installationId = "installation"
        var onHardware: () -> Unit = {}
        var onResultBinding: () -> Unit = {}
        var actor = PostLoginDeviceCheckActorFacts(true, "actor", 1L, "actor", true, true, false, false)
        var hardware = PostLoginDeviceCheckHardwareFacts(
            WalkSafeStartupCapabilityInput(true, true, true, true, true, true, true, true, true, "profile"),
            WalkSessionReadinessStatus.READY, false,
            PostLoginDeviceCheckDetectorFacts(true, false, true, true, PostLoginDeviceCheckSignal.READY),
            emptyList(), true, true, true, true, false, null, null,
        )
        override fun actor() = actor.also { reads++ }
        override fun hardware() = hardware.also { reads++; onHardware() }
        override fun resultBinding(actorId: String) = PostLoginDeviceCheckResultBinding(
            actorId, installationId, "maker", "model", "device", 36, "fingerprint", "app", 1, "1", "source", "policy",
            null, null, "registry", "sha", null, null, emptySet(), true, true, true, true,
        ).also { reads++; onResultBinding() }
    }

    private class Store : PostLoginDeviceCheckSavedResults {
        var pendingBegin: CompletableFuture<DeviceCheckAuthorityOutcome<Unit>>? = null
        var pendingSave: CompletableFuture<DeviceCheckAuthorityOutcome<Unit>>? = null
        var pairReady = true
        var gateRestoreOnPair = false
        var unconfirmed = 0
        var onSave: () -> Unit = {}
        val savedDepths = mutableListOf<PostLoginMetricDepthState>()
        var restored: PersistedPostLoginDeviceCheckResult? = null
        var expectedBinding: PostLoginDeviceCheckResultBinding? = null
        var saveSucceeds = true
        var saves = 0
        override fun beginAttemptAsync(binding: PostLoginDeviceCheckBinding,
            resultBinding: PostLoginDeviceCheckResultBinding, startedAtMs: Long, deadlineMs: Long) =
            pendingBegin ?: super.beginAttemptAsync(binding, resultBinding, startedAtMs, deadlineMs)
        override fun candidatePresent() = restored != null
        override fun restore(binding: PostLoginDeviceCheckResultBinding) =
            retainedResult(binding).takeIf { !gateRestoreOnPair || pairReady }
        override fun retainedResult(binding: PostLoginDeviceCheckResultBinding) =
            restored.takeIf { expectedBinding == null || expectedBinding == binding }
        override fun save(snapshot: PostLoginDeviceCheckSnapshot, binding: PostLoginDeviceCheckResultBinding, cameraDependentChecksDeferred: Boolean, metricDepthState: PostLoginMetricDepthState): Boolean { saves++; return saveSucceeds }
        override fun saveAsync(snapshot: PostLoginDeviceCheckSnapshot, binding: PostLoginDeviceCheckResultBinding,
            cameraDependentChecksDeferred: Boolean, metricDepthState: PostLoginMetricDepthState,
            acceptedAtMs: Long) = pendingSave?.also { onSave(); savedDepths += metricDepthState; saves++ } ?: super.saveAsync(snapshot, binding,
                cameraDependentChecksDeferred, metricDepthState, acceptedAtMs)
        override fun runtimeReady(binding: PostLoginDeviceCheckBinding) = pairReady
        override fun publicationUnconfirmed(binding: PostLoginDeviceCheckBinding) { unconfirmed++ }
    }

    private class Permissions : PostLoginDeviceCheckPermissionPort {
        var missing = emptyList<String>()
        var disclosureSaved = true
        override fun acknowledgeVoiceDisclosure() = disclosureSaved
        override fun requestedPermissions() = missing
        override fun request(binding: PostLoginDeviceCheckBinding, permissions: List<String>) = 42
        override fun cancel(requestCode: Int) = Unit
        override fun ownsDialog(requestCode: Int) = requestCode == 42
    }

    private class Scheduler : PostLoginDeviceCheckScheduler {
        data class Task(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false) : DeviceCheckScheduledTask {
            var onCancel: () -> Unit = {}
            override fun cancel() { cancelled = true; onCancel() }
        }
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMs: Long, action: () -> Unit) = Task(delayMs, action).also(tasks::add)
    }

    private class Probes(private val calls: MutableList<String>) : PostLoginDeviceCheckProbeSuite {
        var joint = false
        override val requiresJointRuntimeCheck get() = joint
        private var metricStarted = false
        var lease: (PostLoginDeviceCheckBinding, (Boolean) -> Unit) -> Unit = { _, done -> done(true) }
        var value = pending()
        var changed: (() -> Unit)? = null
        var onRestart: () -> Unit = {}
        var onPrepareModels: () -> Unit = {}
        var onFunctionalCancel: () -> Unit = {}
        val cameraBindings = mutableListOf<PostLoginDeviceCheckBinding>()
        val metricBindings = mutableListOf<PostLoginDeviceCheckBinding>()
        override val signals get() = value
        override val metricPreflightStarted get() = metricStarted
        override val capabilityProbeStarted = false
        override val storedBindingValidationPending = false
        override fun resetForAttempt(binding: PostLoginDeviceCheckBinding) { value = pending(); metricStarted = false }
        override fun restartCapabilityAndResources(binding: PostLoginDeviceCheckBinding, onChanged: () -> Unit) { changed = onChanged; onRestart() }
        override fun prepareVoiceModelAndDetector() { calls += "prepareModels"; onPrepareModels() }
        override fun prepareRuntimeLease(binding: PostLoginDeviceCheckBinding, onReady: (Boolean) -> Unit) = lease(binding, onReady)
        override fun startAutomaticProbes(binding: PostLoginDeviceCheckBinding) { calls += "startAutomatic" }
        override fun startCamera(binding: PostLoginDeviceCheckBinding) { calls += "cameraStart"; cameraBindings += binding }
        override fun startMetricPreflight(binding: PostLoginDeviceCheckBinding) { metricStarted = true; calls += "metricStart"; metricBindings += binding }
        override fun cancelCalibration(reason: String) { calls += "calibrationCancel:$reason" }
        override fun cancelFunctionalProbes() { calls += "functionalCancel"; onFunctionalCancel() }
        override fun cancelMetricPreflight(reason: String) { metricStarted = false; calls += "metricCancel:$reason" }
        override fun closeCapabilityAndResetResources() { calls += "capabilityClose" }
        override fun clearStoredBindingValidation() = Unit
        override fun stopCamera() { calls += "cameraStop" }
        override fun restoreMetricDepth(state: PostLoginMetricDepthState) { value = value.copy(metricDepth = state) }
        override fun mayValidateStoredBinding() = false
        override fun startStoredBindingValidation(onChanged: () -> Unit) = false
        override fun mayStartStoredMetricRefresh(binding: PostLoginDeviceCheckBinding) = false
        override fun startStoredMetricRefresh(binding: PostLoginDeviceCheckBinding) { calls += "storedMetricStart" }
        private fun pending() = PostLoginDeviceCheckProbeSignals(PostLoginDeviceCheckSignal.PENDING, PostLoginDeviceCheckSignal.PENDING, PostLoginDeviceCheckSignal.PENDING, PostLoginMetricDepthState.PENDING)
    }

    private class Effects(private val calls: MutableList<String>) : PostLoginDeviceCheckEffects {
        var snapshot: () -> PostLoginDeviceCheckSnapshot = { PostLoginDeviceCheckPolicy.initial() }
        val statesDuringInvalidation = mutableListOf<PostLoginDeviceCheckState>()
        val overrides = mutableListOf<PostLoginDeviceCheckCapabilityOverrides>()
        var passes = 0
        var calibrations = 0
        var terminals = 0
        var permissionObservations = 0
        var permissionObservation: (PostLoginDeviceCheckBinding) -> Boolean = { true }
        var passContinuation: (PostLoginDeviceCheckSnapshot) -> PostLoginDeviceCheckPassContinuation = { PostLoginDeviceCheckPassContinuation.COMPLETED }
        var onInvalidation: () -> Unit = {}
        var onRefresh: () -> Unit = {}
        override fun invalidateRouteResponseLease() { calls += "route"; statesDuringInvalidation += snapshot().state; onInvalidation() }
        override fun invalidateSpeechStorageLease() { calls += "speech"; statesDuringInvalidation += snapshot().state }
        override fun applyCapabilityOverrides(overrides: PostLoginDeviceCheckCapabilityOverrides) { this.overrides += overrides }
        override fun clearAttemptPresentation() { calls += "clearPresentation" }
        override fun showStatus(title: String, detail: String) { calls += "status:$title" }
        override fun announceTerminal(message: String) { terminals++ }
        override fun failureMessage(failure: PostLoginDeviceCheckFailure?) = failure?.name.orEmpty()
        override fun refreshReadinessPresentation() = onRefresh()
        override fun noteCancellation(reason: String) { calls += "cancel:$reason" }
        override fun recordPermissionObservation(binding: PostLoginDeviceCheckBinding): Boolean { permissionObservations++; return permissionObservation(binding) }
        override fun afterPermissionObservation(binding: PostLoginDeviceCheckBinding) = Unit
        override fun afterPassed(snapshot: PostLoginDeviceCheckSnapshot): PostLoginDeviceCheckPassContinuation { passes++; return passContinuation(snapshot) }
        override fun afterRestoredResult() = Unit
        override fun scheduleCalibration() { calibrations++ }
        override fun metricCapabilityChanged(available: Boolean) { calls += "metricChanged:$available" }
        override fun clearCameraCapabilityOverrides() = Unit
        override fun prepareDetectorRetry() = Unit
    }
}
