package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostLoginDeviceCheckControllerTest {
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
        assertEquals(180_000L, f.scheduler.tasks.single().delay)
        f.ready()
        f.controller.maybeContinue(binding)
        assertEquals(PostLoginDeviceCheckState.FULL, f.controller.snapshot.state)
        assertEquals(1, f.store.saves)
        assertEquals(1, f.effects.passes)
        f.controller.maybeContinue(binding)
        assertEquals(1, f.store.saves)
        assertTrue(f.scheduler.tasks.single().cancelled)
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

    private class Fixture {
        val calls = mutableListOf<String>()
        val reader = Reader()
        val store = Store()
        val probes = Probes(calls)
        val permissions = Permissions()
        val scheduler = Scheduler()
        val effects = Effects(calls)
        val controller = PostLoginDeviceCheckController(reader, store, probes, permissions, effects, scheduler, PostLoginDeviceCheckController.Config())
        init { effects.snapshot = { controller.snapshot } }
        fun running() = apply { controller.bindSession("actor", 1L); controller.beginFromPrimaryAction() }
        fun ready() { probes.value = PostLoginDeviceCheckProbeSignals(PostLoginDeviceCheckSignal.READY, PostLoginDeviceCheckSignal.READY, PostLoginDeviceCheckSignal.READY, PostLoginMetricDepthState.SUPPORTED) }
    }

    private class Reader : PostLoginDeviceCheckFactsReader {
        var reads = 0
        var installationId = "installation"
        var actor = PostLoginDeviceCheckActorFacts(true, "actor", 1L, "actor", true, true, false, false)
        var hardware = PostLoginDeviceCheckHardwareFacts(
            WalkSafeStartupCapabilityInput(true, true, true, true, true, true, true, true, true, "profile"),
            WalkSessionReadinessStatus.READY, false,
            PostLoginDeviceCheckDetectorFacts(true, false, true, true, PostLoginDeviceCheckSignal.READY),
            emptyList(), true, true, true, true, false, null, null,
        )
        override fun actor() = actor.also { reads++ }
        override fun hardware() = hardware.also { reads++ }
        override fun resultBinding(actorId: String) = PostLoginDeviceCheckResultBinding(
            actorId, installationId, "maker", "model", "device", 36, "fingerprint", "app", 1, "1", "source", "policy",
            null, null, "registry", "sha", null, null, emptySet(), true, true, true, true,
        ).also { reads++ }
    }

    private class Store : PostLoginDeviceCheckSavedResults {
        var restored: PersistedPostLoginDeviceCheckResult? = null
        var expectedBinding: PostLoginDeviceCheckResultBinding? = null
        var saveSucceeds = true
        var saves = 0
        override fun candidatePresent() = restored != null
        override fun restore(binding: PostLoginDeviceCheckResultBinding) =
            restored.takeIf { expectedBinding == null || expectedBinding == binding }
        override fun save(snapshot: PostLoginDeviceCheckSnapshot, binding: PostLoginDeviceCheckResultBinding, cameraDependentChecksDeferred: Boolean, metricDepthState: PostLoginMetricDepthState): Boolean { saves++; return saveSucceeds }
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
            override fun cancel() { cancelled = true }
        }
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMs: Long, action: () -> Unit) = Task(delayMs, action).also(tasks::add)
    }

    private class Probes(private val calls: MutableList<String>) : PostLoginDeviceCheckProbeSuite {
        var value = pending()
        var changed: (() -> Unit)? = null
        override val signals get() = value
        override val metricPreflightStarted = false
        override val capabilityProbeStarted = false
        override val storedBindingValidationPending = false
        override fun resetForAttempt(binding: PostLoginDeviceCheckBinding) { value = pending() }
        override fun restartCapabilityAndResources(binding: PostLoginDeviceCheckBinding, onChanged: () -> Unit) { changed = onChanged }
        override fun prepareVoiceModelAndDetector() { calls += "prepareModels" }
        override fun startAutomaticProbes(binding: PostLoginDeviceCheckBinding) { calls += "startAutomatic" }
        override fun startCamera(binding: PostLoginDeviceCheckBinding) { calls += "cameraStart" }
        override fun startMetricPreflight(binding: PostLoginDeviceCheckBinding) { calls += "metricStart" }
        override fun cancelCalibration(reason: String) { calls += "calibrationCancel:$reason" }
        override fun cancelFunctionalProbes() { calls += "functionalCancel" }
        override fun cancelMetricPreflight(reason: String) { calls += "metricCancel:$reason" }
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
        override fun invalidateRouteResponseLease() { calls += "route"; statesDuringInvalidation += snapshot().state }
        override fun invalidateSpeechStorageLease() { calls += "speech"; statesDuringInvalidation += snapshot().state }
        override fun applyCapabilityOverrides(overrides: PostLoginDeviceCheckCapabilityOverrides) { this.overrides += overrides }
        override fun clearAttemptPresentation() { calls += "clearPresentation" }
        override fun showStatus(title: String, detail: String) { calls += "status:$title" }
        override fun announceTerminal(message: String) { terminals++ }
        override fun failureMessage(failure: PostLoginDeviceCheckFailure?) = failure?.name.orEmpty()
        override fun refreshReadinessPresentation() = Unit
        override fun noteCancellation(reason: String) { calls += "cancel:$reason" }
        override fun recordPermissionObservation(binding: PostLoginDeviceCheckBinding) = true
        override fun afterPermissionObservation(binding: PostLoginDeviceCheckBinding) = Unit
        override fun afterPassed(snapshot: PostLoginDeviceCheckSnapshot): PostLoginDeviceCheckPassContinuation { passes++; return PostLoginDeviceCheckPassContinuation.COMPLETED }
        override fun afterRestoredResult() = Unit
        override fun scheduleCalibration() { calibrations++ }
        override fun metricCapabilityChanged(available: Boolean) { calls += "metricChanged:$available" }
        override fun clearCameraCapabilityOverrides() = Unit
        override fun prepareDetectorRetry() = Unit
    }
}
