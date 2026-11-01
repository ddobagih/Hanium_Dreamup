package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Production wiring: selection never runs at camera entry, nor does loading imply verified output. */
class CameraRuntimePreparationContractTest {
    private val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val camera = File("src/debug/java/kr/co/hanium/dreamup/walksafe/CameraTestActivity.kt").readText()
    private val primary = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorLifecycleController.kt").readText()
    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature).also { check(it >= 0) }
        val next = Regex("\n    (private |internal |override )fun ").find(source, start + signature.length)?.range?.first
        return source.substring(start, next ?: source.length)
    }

    @Test fun devicePreparationRunsOffUiAndKeepsTheLoadingStatePending() {
        val load = body(main, "private fun loadDetectorForCurrentProcess()")
        assertTrue(load.contains("maybePrepareHistoricalRuntime()"))
        assertFalse(load.contains("loadForCurrentProcess("))
        val initial = body(main, "private fun loadDetectorForPostLoginDeviceCheck()")
        assertFalse(initial.contains("allowComparison"))
        val prepare = body(main, "private fun prepareMainRuntimePair(")
        assertTrue(prepare.indexOf("reservePreparedPair(current)") < prepare.indexOf("tuningLane.execute"))
        assertTrue(prepare.indexOf("tuningLane.execute") < prepare.indexOf("AndroidJointRuntimeLoader.preparePair("))
        assertTrue(prepare.contains("retainCreatedLoad(primarySlot, it)"))
        assertTrue(prepare.contains("registerPreparedPair(auxiliarySlot, it)"))
        assertTrue(prepare.contains("finishPreparedCreation(primarySlot, it)"))
        assertTrue(prepare.contains("finishPreparedNotStarted(primarySlot)"))
        assertTrue(prepare.contains("adoptPreparedLoad("))
        assertTrue(prepare.contains("adoptPreparedPair(auxiliarySlot, pair.auxiliary, pair.choice)"))
        assertTrue(main.contains("override val requiresJointRuntimeCheck = true"))
        val support = body(main, "override fun prepareVoiceModelAndDetector()")
        assertFalse(support.contains("loadDetectorForPostLoginDeviceCheck()"))
    }

    @Test fun homeDoesNotReplaceTheSavedChoiceWithUnconditionalGpu() {
        val preparation = body(main, "private fun prepareRuntimeServices()")
        assertFalse(preparation.contains("createWithStatus"))
        assertTrue(preparation.contains("nativeUiPage == NativeUiPage.GUIDANCE || isWalkSessionRuntimeActive()"))
    }

    @Test fun cameraAdoptsThePreparedPairWithoutOpeningModelsOrStartingAnotherBenchmark() {
        val prepare = body(camera, "private fun prepareCameraDetector()")
        assertFalse(prepare.contains("AndroidJointRuntimeLoader.restore("))
        assertFalse(prepare.contains("createWithStatus("))
        assertFalse(prepare.contains("AndroidFixedFixtureRuntimeSelector"))
        assertTrue(prepare.contains("transfers.adopt("))
        assertTrue(prepare.contains("reservation::retainPrimary"))
        assertTrue(prepare.contains("reservation::retainAuxiliary"))
        assertTrue(prepare.contains("unknownRuntime.rollbackTransferred("))
        assertTrue(camera.contains("requiresTransferredPair = true"))
        val resume = body(camera, "private fun resumeUnknownAfterPrimaryLiveAttempt()")
        assertTrue(resume.indexOf("auxiliaryStartup.mayStart(generation, busy.get())") < resume.indexOf("unknownRuntime.resume()"))
    }

    @Test fun cameraSetupCannotPublishAfterPauseOrDestroy() {
        val start = body(camera, "private fun startCamera()")
        assertTrue(start.indexOf("cameraWorker.execute") < start.indexOf("Session(applicationContext)"))
        assertTrue(start.contains("generation == startGeneration"))
        assertTrue(start.contains("foreground && !isDestroyed && !isFinishing"))
        assertTrue(start.contains("releasePreparedCamera("))
        assertTrue(body(camera, "override fun onPause()").contains("generation++"))
        assertTrue(body(camera, "override fun onPause()").contains("unknownRuntime.pause()"))
        val destroy = body(camera, "override fun onDestroy()")
        assertTrue(destroy.contains("if (!startingCamera && (!closingCamera || cameraRelease.failure != null)) cameraWorker.shutdown()"))
        val release = body(camera, "private fun queueCameraRelease(")
        assertTrue(release.indexOf("cameraRelease.execute(attempt)") in 0 until
            release.indexOf("if (isDestroyed || isFinishing) cameraWorker.shutdown()"))
        assertTrue(start.indexOf("if (cameraRelease.failure != null)") in 0 until start.indexOf("cameraWorker.execute"))
    }

    @Test fun normalCameraEntryTransfersTheTwoExistingModelsAndTheirImmutableOwnerScopes() {
        val open = body(main, "private fun openLiveCameraScreen()")
        assertFalse(open.contains("createWithStatus("))
        assertFalse(open.contains("AndroidJointRuntimeLoader.restore("))
        assertFalse(open.contains("loadDetectorForCurrentProcess()"))
        assertTrue(open.contains("BuildConfig.DEBUG"))
        assertTrue(open.contains("claimForTransfer()"))
        assertTrue(open.contains("detachForTransferAfterDrain()"))
        assertTrue(open.contains("detachPreparedPairForTransfer(runtime.pair.auxiliary)"))
        assertTrue(open.indexOf("transfers.prepare(") < open.indexOf("startActivityForResult("))
        val create = body(camera, "override fun onCreate(")
        assertTrue(create.contains("VisionRuntimeContext(applicationContext, ticket.targetOwner)"))
        assertFalse(create.contains("AndroidFixedFixtureRuntimeSelector"))
        val returned = body(camera, "override fun finish()")
        assertTrue(returned.contains("transfers.prepare("))
        assertFalse(returned.contains("createWithStatus("))
    }

    @Test fun recheckDuringLoadingCannotResetAttemptAndLeaveSuccessPending() {
        assertTrue(main.contains("override fun prepareDetectorRetry() { primaryDetectorLifecycleController.allowLoadRetryIfUnavailable() }"))
        val recheck = body(primary, "fun allowLoadRetryIfUnavailable()")
        assertTrue(recheck.contains("!detector.projection().available && !detector.projection().closing && !loadInFlight"))
        val load = body(primary, "fun loadForCurrentProcess(allowComparison: Boolean = false,")
        // A physical-cleanup failure has a separate completion branch. Check the successful
        // admission transaction, which must publish metadata before readiness callbacks.
        val complete = load.substringAfter("if (!detector.admitLoadedOnMain(")
        assertTrue(complete.indexOf("loadAttempted = true") < complete.indexOf("preparation.loaded()"))
    }
    @Test fun existingDeviceCheckOwnsCompositeWorkAndSettingsHaveNoSeparateAction() {
        val support = body(main, "override fun startCompositeCameraCheck(")
        assertTrue(support.contains("startDeviceCompositeCameraCheck(binding, onFinished)"))
        assertTrue(main.contains("runtimeDeadlineMs"))
        assertFalse(main.contains("startCameraPerformanceCheck"))
        val settings = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/SettingsPrivacyPanel.kt").readText()
        assertFalse(settings.contains("onCameraPerformanceCheck"))
        val apply = body(main, "private fun finishDeviceCompositeApplicationIfReady()")
        assertTrue(apply.contains("RunningDetectorVerificationState.VERIFIED"))
        assertTrue(apply.contains("runtime.pair.proof.isCurrent()"))
        assertTrue(apply.contains("acceptPrepared"))
    }

    @Test fun samplePerformanceCheckerAndWorkerHaveNoCameraOrArCoreDependency() {
        val directory = File("src/main/java/kr/co/hanium/dreamup/walksafe/inference/composite")
        val source = listOf("AndroidJointRuntimeCheck.kt", "JointRuntimeSampleHost.kt", "JointRuntimeReferenceSet.kt",
            "JointRuntimeFinalPairPreparation.kt", "JointRuntimeCheckedSelection.kt").joinToString("\n") { File(directory, it).readText() }
        listOf("AndroidRuntimeCalibrationSession", "com.google.ar.core", "android.media.Image",
            "android.hardware.camera2", "androidx.camera", "ACTION_IMAGE_CAPTURE").forEach { forbidden ->
            assertFalse("Sample-image performance check must not use $forbidden", source.contains(forbidden))
        }
        assertTrue(source.contains("RuntimeCalibrationFixtures"))
        assertFalse(body(main, "private fun startDeviceCompositeCameraCheck(").contains("비춰"))
        val attempt = main.substringAfter("private class DeviceCompositeAttempt(")
            .substringBefore("@Volatile private var deviceCompositeAttempt")
        assertTrue(attempt.contains("metricDepth = null"))
        val complete = body(main, "private fun completeDeviceCompositeAttempt(")
        assertFalse(complete.contains("PostLoginMetricDepthState.SUPPORTED"))
    }

    @Test fun unconfirmedWorkerDeathFinishesFailurePresentationWhileRetainingItsAllocationFence() {
        val failure = body(main, "private fun publishDeviceCompositeReleaseFailure(")
        assertTrue(failure.contains("attempt.state.fail(reason)"))
        assertTrue(failure.contains("requestDeviceCompositeNativeCleanup(attempt)"))
        val state = File("src/main/java/kr/co/hanium/dreamup/walksafe/device/postlogin/DeviceCompositeAttemptState.kt").readText()
        assertTrue(state.contains("onCompletion(Completion(false, false, reason))"))
        assertFalse(failure.contains("deviceCompositeAttempt = null"))
    }

    @Test fun actualPairIsPreparedBeforeTheControllerAloneCommitsTheBundle() {
        val start = body(main, "private fun startDeviceCompositeCameraCheck(")
        val candidate = body(main, "private fun receiveCompositeSelection(")
        assertTrue(candidate.contains("claimForFinal("))
        assertTrue(candidate.contains("prepareMainRuntimePair("))
        val prepared = body(main, "private fun prepareMainRuntimePair(")
        assertTrue(prepared.contains("JointRuntimeFinalPairPreparation.prepare("))
        assertFalse(start.contains("choiceOverride"))
        assertFalse(start.contains(".save("))
        val applied = body(main, "private fun finishDeviceCompositeApplicationIfReady()")
        assertTrue(applied.indexOf("RunningDetectorVerificationState.VERIFIED") < applied.indexOf("acceptPrepared"))
        assertFalse(applied.contains("persistVerified("))
        assertFalse(applied.contains(".save("))
        assertTrue(main.contains("savedResults = authorityDeviceCheckResults"))
        assertTrue(main.contains("postLoginDeviceCheckResultStore = AndroidDeviceCheckResultStore(deviceCheckAuthority)"))
        val cleanup = body(main, "private fun retireMainPairPreparation(")
        assertTrue(cleanup.contains("workerFinished.whenComplete"))
        assertTrue(cleanup.contains("primarySlot.actualReleasedFuture"))
        assertTrue(cleanup.contains("actualReleased"))
        assertTrue(start.contains("CameraRuntimeHandoff.begin()"))
    }

    @Test fun auxiliaryFailureRetiresTheWholeOperationalPairWithoutWaitingForAnotherPrimaryFrame() {
        val interaction = body(main, "override fun requirePairPreparation(reason: String)")
        assertTrue(interaction.contains("if (reason == \"auxiliary_runtime_failed\") retireOperationalRuntime(reason)"))
        assertTrue(interaction.indexOf("retireOperationalRuntime(reason)") < interaction.indexOf("if (!isActivityForeground)"))
        val retire = body(main, "private fun retireOperationalRuntime(")
        assertTrue(retire.indexOf("deviceCheckAuthority.retireChoice(") < retire.indexOf("client.requestExit("))
        assertTrue(retire.contains("quiesceForRuntimeError(captured)"))
        assertTrue(retire.contains("reportRuntimeLost(runtime.binding)"))
        assertFalse(retire.contains("closeDetectorAsync()"))
    }

    @Test fun everyPreCreationReceiverRejectionClosesTheClaimedReferenceAndFixtureSet() {
        val prepare = body(main, "private fun prepareMainRuntimePair(")
        val rejection = prepare.substringAfter("fun rejectPreparation(").substringBefore("val transferredRuntime")
        assertTrue(rejection.indexOf("measured?.close()") < rejection.indexOf("onFailed(reason)"))
        listOf("primary_pair_receiver_unavailable", "auxiliary_pair_receiver_unavailable",
            "transferred_cohort_missing", "primary_return_reservation_rejected", "auxiliary_return_reservation_rejected")
            .forEach { reason -> assertTrue(prepare.contains("rejectPreparation(\"$reason\")")) }
    }

    @Test fun historicalResultsRequireFreshActualPairAndFrozenOwnerContext() {
        val historical = body(main, "private fun maybePrepareHistoricalRuntime()")
        assertTrue(historical.contains("authorityDeviceCheckResults.historical()"))
        assertTrue(historical.contains("attemptGeneration.coerceAtLeast(1L)"))
        assertTrue(historical.indexOf("prepareMainRuntimePair(") < historical.indexOf("restoreBoundResultIfPossible()"))
        assertTrue(historical.contains("bundleRef === read.bundleRef"))
        val lease = body(main, "private fun prepareDeviceRuntimeLease(")
        assertTrue(lease.contains("registerRuntimeOwner(binding,"))
        assertTrue(lease.contains("VisionRuntimeContext(applicationContext, owner)"))
        assertTrue(lease.contains("retirementSnapshot(it)"))
        assertTrue(lease.contains("oldRegistryReleased"))
        assertTrue(historical.contains("deviceAuthorityBindingCurrent(binding) && mainRuntimeSource === source"))
    }

    @Test fun physicalCompletionKeepsOnlyWeakActivityDispatchAndDestroyedUiCallbacksAreDiscarded() {
        val observer = body(main, "private fun observeRuntimeCompletion(")
        assertTrue(observer.contains("java.lang.ref.WeakReference(this)"))
        assertTrue(observer.contains("activity.get()?.runtimeUiCompletions?.remove(serial)"))
        assertTrue(observer.contains("onOwnerGone(error)"))
        val destroy = body(main, "override fun onDestroy()")
        assertTrue(destroy.indexOf("runtimeUiCompletionsClosed = true") < destroy.indexOf("beginDestroyedVisionRetirement()"))
        assertTrue(destroy.contains("runtimeUiCompletions.clear()"))
    }

    @Test fun explicitRecheckCanSettleOldStorageBeforeAnyNewAttemptAndAllocationWaitsForTheCapturedAck() {
        val cleanup = body(main, "private fun recheckPendingDeviceRuntimeCleanup()")
        assertTrue(cleanup.contains("RemoteMetricSupportProbe.recheckPendingRelease()"))
        assertTrue(cleanup.contains("RemoteVisionClient.recheckPendingRelease()"))
        assertFalse(cleanup.contains("forMainProcess("))
        listOf("private fun startPostLoginDeviceCheckFromPrimaryAction()",
            "private fun handlePostLoginDeviceCheckPrimaryAction()",
            "private fun beginPostLoginDeviceCheckFromUserAction(").forEach { signature ->
            val entry = body(main, signature)
            assertTrue(entry.indexOf("recheckPendingDeviceRuntimeCleanup()") in 0 until entry.indexOf("postLoginDeviceCheckController."))
        }
        val lease = body(main, "private fun prepareDeviceRuntimeLease(")
        assertTrue(lease.contains("captured.actualReleased.toCompletableFuture()"))
        assertTrue(lease.contains("captured.allocationAvailable.toCompletableFuture()"))
    }

    @Test fun samplePerformanceUsesTheProductionPrivateRuntimeRatherThanAFixtureChild() {
        val check = File("src/main/java/kr/co/hanium/dreamup/walksafe/inference/composite/AndroidJointRuntimeCheck.kt").readText()
        assertFalse(check.contains("RemoteFixtureRuntime"))
        assertFalse(check.contains("createWithStatusLocal"))
        assertTrue(check.contains("JointRuntimeSampleHost.create("))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = Regex("<service[^>]*VisionRuntimeService[\\s\\S]*?/>").find(manifest)?.value
        assertNotNull("Production native models require a private Service", service)
        assertTrue(service!!.contains("android:exported=\"false\""))
        assertTrue(service.contains("android:process=\":vision_runtime\""))
    }

}
