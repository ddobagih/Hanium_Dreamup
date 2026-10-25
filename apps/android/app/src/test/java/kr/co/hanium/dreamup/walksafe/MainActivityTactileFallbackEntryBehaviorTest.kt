package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.navigation.PreparedTactileFrameDispatch
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import java.io.File
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.NoopAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.RunningDetectorIdentity
import kr.co.hanium.dreamup.walksafe.inference.RunningDetectorLoadEvidence
import kr.co.hanium.dreamup.walksafe.inference.RunningDetectorVerificationStep
import kr.co.hanium.dreamup.walksafe.feedback.TactileDotReferenceDeliveryPolicy
import kr.co.hanium.dreamup.walksafe.feedback.TactileDotReferenceDeliveryOwner
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Main's default coordinator and actual publication/output entries; no prepared Activity/AR graph. */
class MainActivityTactileFallbackEntryBehaviorTest {
    private val nativeLease = MainActivity.RuntimeArObservation(Any(), Any(), MainActivity.ArSessionPurpose.RUNTIME, 0L)
    private val owner = TactileDotReferenceDeliveryOwner(WalkRuntimeEpoch("tactile-entry", 0L), 0, 0, 0L)

    @Test fun defaultMainCoordinatorConsumesRealMappedDepthAndProducesOnlyTheDotReference() {
        val f = FrameFixture()
        val result = f.stableFrames().frame
        val notice = requireNotNull(result.guidance.dotReferenceNotice)

        assertTrue(result.depthProcessingAttempted)
        assertEquals(3, f.depthCalls)
        assertTrue(result.guidance.outputs.single().source.metric)
        assertEquals(f.lastEvidence.frameId, notice.captureFrameId)
        assertEquals(f.lastEvidence.visualFrameKey!!.capturedAtElapsedRealtimeMs, notice.capturedAtElapsedRealtimeMs)
        assertEquals(TACTILE_DOT_REFERENCE_NOTICE_KO, notice.message)
        assertTrue(notice.isValidAt(f.now))
        assertEquals(LocalRouteMode.TMAP, result.guidance.decision.mode)
        assertNull(result.guidance.decision.instruction)
        assertNull(result.guidance.decision.steering)
    }

    @Test fun currentEmptyCaptureRemovesTheDotInsteadOfRenewingItsOriginalDeadline() {
        val f = FrameFixture()
        val old = requireNotNull(f.stableFrames().frame.guidance.dotReferenceNotice)
        val empty = f.frame(1_850L, detected = false).frame

        assertNull(empty.guidance.dotReferenceNotice)
        assertFalse(empty.depthProcessingAttempted)
        assertEquals(3, f.depthCalls)
        assertEquals(LocalRouteMode.TMAP, empty.guidance.decision.mode)
        assertNull(empty.guidance.decision.instruction)
        assertTrue(old.isValidAt(f.now)) // Source loss, rather than deadline expiry, explains this absence.
    }

    @Test fun foreignDepthEvidenceCannotBecomeACurrentDotReference() {
        val f = FrameFixture()
        f.stableFrames()
        val foreign = f.frame(1_850L, foreignEvidence = true).frame

        assertNull(foreign.matchedEvidence)
        assertNull(foreign.guidance.dotReferenceNotice)
        assertFalse(foreign.depthProcessingAttempted)
        assertEquals(3, f.depthCalls)
    }

    @Test fun actualPublicationEntryRejectsAnUnpreparedOwnerBeforeReadingTheClock() {
        val f = FrameFixture()
        val prepared = f.stableFrames()
        var clocks = 0
        val accepted = f.main.publishTactileFrameReferenceObservation(prepared.frame, owner) { clocks++; f.now }

        assertFalse(accepted)
        assertEquals(0, clocks)
    }

    @Test fun retiredOwnerAndFrameGenerationsCannotPublishThroughTheActualEntry() {
        val f = FrameFixture()
        val frame = f.stableFrames().frame
        val retired = listOf(owner.copy(frameGeneration = 7), owner.copy(epoch = owner.epoch.copy(recoveryGeneration = 1L)))
        retired.forEach { captured ->
            assertFalse(f.main.publishTactileFrameReferenceObservation(frame, captured) {
                fail("Rejected owner cannot publish or renew an observation"); f.now
            })
        }
    }

    @Test fun nativeLeaseObservationCannotGrantTheMissingMainDomainReadiness() {
        val main = MainActivity()
        val results = mutableListOf<Boolean>()
        var nativeObservations = 0
        var clocks = 0
        main.maybeSpeakTactileDotReference(
            owner = owner,
            arLease = nativeLease,
            observeRuntimeAr = { nativeObservations++; nativeLease },
            nowElapsedRealtimeMs = { clocks++; 51_800L },
            onUiThread = { action -> action() },
            onDispatchResult = { results += it },
        )

        assertEquals(listOf(false), results)
        assertEquals(0, nativeObservations) // The real foreground/domain owner gate fails first.
        assertEquals(1, clocks) // The existing safety evaluation precedes admission.
    }

    @Test fun actualOutputEntryWaitsForItsPlatformDispatchAndStillFailsClosedWhenRun() {
        val main = MainActivity()
        val results = mutableListOf<Boolean>()
        var task: (() -> Unit)? = null
        var clocks = 0
        main.maybeSpeakTactileDotReference(
            owner = owner,
            arLease = nativeLease,
            observeRuntimeAr = { fail("A retired owner must not reach the native lease observation"); nativeLease },
            nowElapsedRealtimeMs = { clocks++; 51_800L },
            onUiThread = { action -> task = action },
            onDispatchResult = { results += it },
        )

        assertTrue(results.isEmpty())
        assertEquals(0, clocks)
        requireNotNull(task).invoke()
        assertEquals(listOf(false), results)
        assertEquals(1, clocks)
    }

    @Test fun actualGlAndNativeWrapperDelegateWhileTheProductionOutputChecksRemainPresent() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val pipelineSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePipelineController.kt").readText()
        val publicationSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePublicationController.kt").readText()
        val referenceSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/delivery/TactileReferenceDeliveryController.kt").readText()
        val dispatchSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/capture/TactileDotReferenceDispatchController.kt").readText()
        val gl = MainActivityRefactorSource.member(pipelineSource, "synthesize")
        val publication = MainActivityRefactorSource.member(publicationSource, "publish")
        val mainPipeline = MainActivityRefactorSource.property(source, "framePipelineController")
        val tactilePeer = MainActivityRefactorSource.property(source, "frameTactileResults")
        assertTrue(mainPipeline.contains("val tactile = frameTactileResults"))
        assertTrue(mainPipeline.contains("frameRouteEvidence, tactile, risk, recording"))
        assertTrue(tactilePeer.contains("object : FrameTactileResultPeer<ArSessionLease, TactileResultSource, TactileResultReceipt>"))
        assertTrue(gl.contains("tactile.consume("))
        assertTrue(gl.contains("tactile.publishReference("))
        assertTrue(tactilePeer.contains("consumeTactileFrameResult(source, frame, admissionIsCurrent = admissionIsCurrent, consume = accepted)"))
        assertTrue(tactilePeer.contains("publishConsumedTactileFrameReferenceObservation(frame, owner, receipt)"))
        assertTrue(gl.contains("val result = frame ?: return null"))
        assertTrue(publication.contains("tactile.dotReference(input.dotOwner, scope.lease, input.sourceIsCurrent)"))
        assertTrue(tactilePeer.contains("maybeSpeakTactileDotReference(owner, lease, current)"))
        val publish = MainActivityRefactorSource.member(referenceSource, "publishConsumedTactileFrameReferenceObservation")
        assertTrue(publish.contains("if (!source.currentFrame(owner.frameGeneration, owner.epoch) ||"))
        assertTrue(publish.contains("sourceReceipt != null && !source.currentReceipt(sourceReceipt)"))
        assertTrue(publish.contains("sourceIsCurrent?.invoke() == false) return@synchronized false"))
        assertTrue(publish.indexOf("updateCrosswalkReferenceObservation(") < publish.indexOf("updateTactileDotReferenceObservationLocked("))
        val referenceBinding = MainActivityRefactorSource.member(source, "createTactileReferenceDelivery")
        assertTrue(MainActivityRefactorSource.property(source, "tactileReferenceDelivery").contains("createTactileReferenceDelivery()"))
        assertTrue(referenceBinding.contains("isCurrentFrameGeneration(generation, epoch)"))
        assertTrue(referenceBinding.contains("tactileResultIsCurrent(receipt)"))
        val native = source.substringAfter("private fun maybeSpeakTactileDotReference(")
            .substringBefore("internal fun maybeSpeakTactileDotReference(")
        assertTrue(native.contains("arLease = RuntimeArObservation(arLease.session, arLease.provider, arLease.purpose, arLease.generation)"))
        val entry = MainActivityRefactorSource.member(dispatchSource, "maybeSpeak")
        val nativeEntry = MainActivityRefactorSource.member(source, "maybeSpeakTactileDotReference", occurrence = 1)
        val authority = MainActivityRefactorSource.composition(source, "tactileDotDispatch")
        listOf("authority.feedbackLifecycleCurrent(owner.feedbackLifecycleGeneration)",
            "authority.frameCurrent(owner.frameGeneration, owner.epoch)", "authority.leaseCurrent(arLease, observeRuntimeAr)",
            "authority.metricOutputAllowed(owner.arGeneration, observeRuntimeAr)", "dotReferenceDeviceGateAllowsSpeech(authority.deviceGate(",
            "authority.voiceFeatureEnabled()", "authority.offlineTtsAllowed()",
            "safety.epoch == owner.epoch && safety.safetyOutputsAllowed", "!authority.guidanceVoiceInputActive()",
            "val actor = actor.ensureActor()", "actor.speakAdvisoryWithReceipt(", "validUntilMs = notice.validUntilElapsedRealtimeMs",
            "isStillValid = current", "completionOwnerIsCurrent = ownerIsCurrent").forEach { assertTrue(it, entry.contains(it)) }
        listOf("isFeedbackLifecycleCurrent(generation)", "isCurrentFrameGeneration(generation, epoch)",
            "isRuntimeArLeaseCurrent(lease, observe)", "currentRuntimeMetricOutputAllowsWork(arGeneration, observe)",
            "postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.VOICE_GUIDANCE)",
            "startupCapabilityDecision?.allowsRequirement(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS) == true",
            "TactileDotDispatchActor { ensureFeedbackActuator() }", "isGuidanceVoiceInputActive()").forEach { assertTrue(it, authority.contains(it)) }
        assertTrue(MainActivityRefactorSource.member(source, "isGuidanceVoiceInputActive").contains("guidanceVoiceInputActive { voiceInputController }"))
        assertTrue(nativeEntry.contains("nowElapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime"))
        assertTrue(nativeEntry.contains("onUiThread: (() -> Unit) -> Unit = { action -> runOnUiThread(action) }"))
        assertFalse(entry.contains("outputIsCurrent = { true }"))
    }

    @Test fun nativeLeaseFactsUseExactIdentityPurposeAndGenerationWhileColdDomainStillBlocks() {
        val main = MainActivity()
        assertTrue(main.isRuntimeArLeaseCurrent(nativeLease) { nativeLease })
        assertTrue(nativeLease.allowsMetricLease(0L))
        assertTrue(nativeLease.allowsMetricLease(null))
        val replacements = listOf(nativeLease.copy(session = Any()), nativeLease.copy(provider = Any()),
            nativeLease.copy(purpose = MainActivity.ArSessionPurpose.PREFLIGHT), nativeLease.copy(generation = 1L),
            nativeLease.copy(session = null), nativeLease.copy(provider = null))
        replacements.forEach { current -> assertFalse(main.isRuntimeArLeaseCurrent(nativeLease) { current }) }
        assertFalse(nativeLease.copy(purpose = MainActivity.ArSessionPurpose.PREFLIGHT).allowsMetricLease(0L))
        assertFalse(nativeLease.copy(session = null).allowsMetricLease(0L))
        assertFalse(nativeLease.copy(provider = null).allowsMetricLease(0L))
        assertFalse(nativeLease.allowsMetricLease(1L))
        var observations = 0
        assertFalse(main.currentRuntimeMetricOutputAllowsWork(0L) { observations++; nativeLease })
        assertEquals(0, observations) // Native identity never substitutes for onboarding/walk readiness.
    }

    @Test fun publicNoArgConstructorKeepsTheUnconfiguredProductionSafetyOwner() {
        val main = MainActivity::class.java.getConstructor().newInstance()
        assertNull(WalkRuntimeSafetyCoordinator.productionThresholdProfile)
        assertEquals(WalkRuntimeSafetyDisposition.NOT_CONFIGURED, main.beginWalkRuntimeSafetyEpoch(owner.epoch).disposition)
        assertFalse(main.evaluateWalkRuntimeSafety(1_000L).safetyOutputsAllowed)
    }

    @Test fun syntheticProfileWithoutAnEpochStillCannotAuthorizeTheActualSafetyOwner() {
        val main = MainActivity(syntheticProfile())
        val decision = main.evaluateWalkRuntimeSafety(1_000L)
        assertEquals(WalkRuntimeSafetyDisposition.NOT_CONFIGURED, decision.disposition)
        assertNull(decision.epoch)
        assertFalse(decision.safetyOutputsAllowed)
    }

    @Test fun syntheticProfileAndTypedObservationsReachTheSamePrivateSafetyOwner() {
        val main = MainActivity(syntheticProfile())
        assertEquals(owner.epoch, main.beginWalkRuntimeSafetyEpoch(owner.epoch).epoch)
        val observation = WalkRuntimeSafetyObservation(owner.epoch, 1_000L,
            cameraTrusted = true, depthTrusted = true, gpsTrusted = true, riskTrusted = true, ttsTrusted = true,
            batteryCritical = false, storageCritical = false, thermalCritical = false,
            frameCapturedAtElapsedRealtimeMs = 1_000L, inferenceLatencyMs = 100L)
        assertTrue(main.observeWalkRuntimeSafety(observation).safetyOutputsAllowed)
        assertTrue(main.evaluateWalkRuntimeSafety(1_010L).safetyOutputsAllowed)
        // This single component's authorization does not grant Main's remaining domain gates.
        assertFalse(main.currentRuntimeMetricOutputAllowsWork(0L) { nativeLease })
    }

    @Test fun detectorRegistrationObservesTheCurrentInstanceAndRejectsForeignOrOldResultsBeforeTheClock() {
        val main = MainActivity()
        val current = main.captureRunningDetectorObservation()
        val identity = RunningDetectorIdentity("synthetic", "test", mapOf("test" to "1".repeat(64)),
            "2".repeat(64), "3".repeat(64), "cpu", false, current.generation)
        val evidence = RunningDetectorLoadEvidence(true, true, true)
        main.beginRunningDetectorVerification(current.instance, 1_000L)
        assertEquals(RunningDetectorVerificationStep.STALE, main.recordRunningDetectorVerification(
            NoopAndroidFrameDetector(), identity, evidence, current.generation) {
                fail("Foreign result must not record native evidence"); 1_000L })
        assertEquals(RunningDetectorVerificationStep.STALE, main.recordRunningDetectorVerification(
            current.instance, identity, evidence, current.generation + 1L) {
                fail("Old generation must not record native evidence"); 1_000L })
        assertEquals(RunningDetectorVerificationStep.VERIFIED, main.recordRunningDetectorVerification(
            current.instance, identity, evidence, current.generation) { 1_000L })
        assertSame(current.instance, main.captureRunningDetectorObservation().instance)
        assertNull(current.configSha256) // No native model or configuration bytes were loaded by this double.
    }

    @Test fun sourceLossReturnsOnlyTheExactPendingOwnerOnceAndKeepsTerminalPolicySeparate() {
        val policy = TactileDotReferenceDeliveryPolicy()
        policy.updateObservation(sourceNotice(), owner, 1_000L)
        val reserved = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        assertSame(reserved, policy.updateObservation(null, owner, 1_100L))
        assertFalse(policy.isSourceCurrent(reserved))
        assertFalse(policy.isCurrent(reserved, 1_100L))
        assertNull(policy.updateObservation(null, owner, 1_101L))
        // Policy still accepts actual DONE; Main/Actor exact cancel determines whether DONE can arrive.
        assertTrue(policy.complete(reserved, 1_102L))
    }

    @Test fun changedTrackReturnsTheOriginalPendingOwnerAndCannotRestoreItsReceipt() {
        val policy = TactileDotReferenceDeliveryPolicy()
        policy.updateObservation(sourceNotice(), owner, 1_000L)
        val old = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        assertSame(old, policy.updateObservation(sourceNotice("other", 2L, 1_100L), owner, 1_100L))
        assertFalse(policy.isSourceCurrent(old))
        assertNull(policy.updateObservation(sourceNotice("dot", 3L, 1_150L), owner, 1_150L))
        assertFalse(policy.isSourceCurrent(old)) // A new capture cannot restore this retired source receipt.
        assertNull(policy.reserveCurrent(owner, 1_150L))
        assertTrue(policy.fail(old))
        assertTrue(policy.isSourceCurrent(requireNotNull(policy.reserveCurrent(owner, 1_150L))))
    }

    @Test fun sourceLossBetweenValidatorReadsCannotReviveTheSamePendingStart() {
        val policy = TactileDotReferenceDeliveryPolicy()
        policy.updateObservation(sourceNotice(), owner, 1_000L)
        val reserved = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        // First read of Main's actual start validator precedes the intervening GL publications.
        val sourceCurrentBeforePublication = policy.isSourceCurrent(reserved)
        assertTrue(sourceCurrentBeforePublication)
        assertTrue(policy.isCurrent(reserved, 1_000L))
        assertSame(reserved, policy.updateObservation(null, owner, 1_100L))
        assertNull(policy.updateObservation(sourceNotice(frame = 2L, captured = 1_150L), owner, 1_150L))
        assertFalse(policy.isSourceCurrent(reserved))
        assertFalse("The second synchronized read must reject the exact source-invalidated reservation",
            sourceCurrentBeforePublication && policy.isCurrent(reserved, 1_150L))
    }

    @Test fun startedTtlExpiryAloneReturnsNoCancellationOwner() {
        val policy = TactileDotReferenceDeliveryPolicy()
        val notice = sourceNotice()
        policy.updateObservation(notice, owner, 1_000L)
        val started = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        assertNull(policy.updateObservation(notice, owner, 1_401L))
        assertTrue(policy.isSourceCurrent(started))
        assertFalse(policy.isCurrent(started, 1_401L))
        assertTrue(policy.complete(started, 2_000L))
    }

    @Test fun lifetimeResetReturnsExactPendingAndLateOldOwnerCannotRetireTheNewOne() {
        val policy = TactileDotReferenceDeliveryPolicy()
        policy.updateObservation(sourceNotice(), owner, 1_000L)
        val old = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        assertSame(old, policy.reset(owner))
        assertFalse(policy.isSourceCurrent(old))
        val nextOwner = owner.copy(feedbackLifecycleGeneration = 1)
        policy.updateObservation(sourceNotice(frame = 2L, captured = 1_100L), nextOwner, 1_100L)
        val next = requireNotNull(policy.reserveCurrent(nextOwner, 1_100L))
        assertNull(policy.reset(owner))
        assertTrue(policy.isSourceCurrent(next))
        assertFalse(policy.fail(old))
    }

    @Test fun rejectedObservationClockCannotProduceACancellationOwner() {
        val policy = TactileDotReferenceDeliveryPolicy()
        policy.updateObservation(sourceNotice(), owner, 1_000L)
        val pending = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        assertNull(policy.updateObservation(null, owner, 999L))
        assertNull(policy.updateObservation(null, owner, -1L))
        assertTrue(policy.isSourceCurrent(pending))
        assertTrue(policy.isCurrent(pending, 1_000L))
    }

    @Test fun ownerReplacementReturnsTheExactOldReservationEvenWhenNoticeValuesMatch() {
        val policy = TactileDotReferenceDeliveryPolicy()
        val notice = sourceNotice()
        policy.updateObservation(notice, owner, 1_000L)
        val old = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        val nextOwner = owner.copy(arGeneration = 1L)
        assertSame(old, policy.updateObservation(notice, nextOwner, 1_000L))
        assertFalse(policy.isSourceCurrent(old))
        val next = requireNotNull(policy.reserveCurrent(nextOwner, 1_000L))
        assertNotSame(old, next)
        assertTrue(policy.isSourceCurrent(next))
    }

    @Test fun terminalReceiptBeforeRegistrationCannotBecomeASourceOwnerAgain() {
        val policy = TactileDotReferenceDeliveryPolicy()
        policy.updateObservation(sourceNotice(), owner, 1_000L)
        val reserved = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        assertTrue(policy.fail(reserved))
        assertFalse(policy.isSourceCurrent(reserved))
        assertTrue(policy.isSourceCurrent(requireNotNull(policy.reserveCurrent(owner, 1_000L))))
    }

    @Test fun actualMainReceiptBindingChecksOriginalSourceActorAndLifetimeBeforeExactCancellation() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val referenceSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/delivery/TactileReferenceDeliveryController.kt").readText()
        val dispatchSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/capture/TactileDotReferenceDispatchController.kt").readText()
        val referenceBinding = MainActivityRefactorSource.member(source, "createTactileReferenceDelivery")
        assertTrue(MainActivityRefactorSource.property(source, "tactileReferenceDelivery").contains("createTactileReferenceDelivery()"))
        assertTrue(referenceBinding.contains("TactileReferenceDeliveryController("))
        val registration = MainActivityRefactorSource.member(referenceSource, "registerTactileDotReferenceSpeech")
        assertTrue(registration.contains("val ownerCurrent = ownerIsCurrent()"))
        assertTrue(registration.indexOf("val ownerCurrent = ownerIsCurrent()") < registration.indexOf("synchronized(frameStateLock)"))
        assertTrue(registration.contains("output.currentActor() !== actor"))
        assertTrue(registration.contains("!tactileDotReferenceDeliveryPolicy.isSourceCurrent(reservation)"))
        assertTrue(registration.contains("tactileDotReferenceSpeech = TactileDotReferenceSpeech(reservation, actor, receipt)"))
        assertTrue(registration.contains("if (!bound) cancelRetiredTactileDotReferenceSpeech("))
        val detach = MainActivityRefactorSource.member(referenceSource, "detachTactileDotReferenceSpeechLocked")
        assertTrue(detach.contains("if (reservation !== current.reservation) return null"))
        assertTrue(detach.contains("tactileDotReferenceSpeech = null"))
        val entry = MainActivityRefactorSource.member(dispatchSource, "maybeSpeak")
        MainActivityRefactorSource.composition(source, "tactileDotDispatch")
        val dispatch = MainActivityRefactorSource.member(referenceSource, "dispatchTactileDotReference")
        assertTrue(dispatch.contains("outputIsCurrent() && tactileDotReferenceDeliveryPolicy.isSourceCurrent(reservation) &&"))
        assertTrue(dispatch.contains("tactileDotReferenceDeliveryPolicy.isCurrent(reservation, nowElapsedRealtimeMs())"))
        assertTrue(entry.contains("onReserved = { reserved = it }"))
        assertTrue(entry.contains("onRegistered = { receipt ->"))
        assertTrue(entry.contains("registerTactileDotReferenceSpeech(reservation, actor, receipt, ownerIsCurrent)"))
        assertTrue(entry.contains("onCancelled = failed"))
        assertFalse(entry.substringAfter("onDispatchResult?.invoke(accepted)").contains("tactileDotReferenceSpeech ="))
        val publication = MainActivityRefactorSource.member(referenceSource, "publishConsumedTactileFrameReferenceObservation")
        assertTrue(publication.contains("retired = updateTactileDotReferenceObservationLocked("))
        assertTrue(publication.indexOf("retired?.let(::cancelRetiredTactileDotReferenceSpeech)") > publication.indexOf("val published = synchronized(frameStateLock)"))
        assertFalse(publication.contains("cancelAdvisorySpeech("))
        val cancel = MainActivityRefactorSource.member(referenceSource, "cancelRetiredTactileDotReferenceSpeech")
        assertTrue(cancel.contains("Thread.holdsLock(frameStateLock)"))
        assertTrue(cancel.contains("output.postRetiredCancellation { speech.actor.cancelAdvisorySpeech(speech.receipt) }"))
        assertTrue(referenceBinding.contains("override fun currentActor() = feedbackActuator"))
        assertTrue(referenceBinding.contains("override fun postRetiredCancellation(action: () -> Unit) { detectorWatchdogHandler.post(action) }"))
    }

    private fun sourceNotice(track: String = "dot", frame: Long = 1L, captured: Long = 1_000L) =
        TactileDotReferenceNotice(track, frame, captured, captured + 400L)

    @Test fun typedMetricEvidenceCannotAuthorizeAColdDomainOrReadItsNativeOwner() {
        val main = MainActivity(syntheticProfile())
        main.beginRuntimeMetricActivation(1L, 1_000L)
        var nativeReads = 0
        val evidence = RuntimeMetricFrameEvidence(1L, 1_000_000_000L, 1_010L, true, true,
            RuntimeMetricPreflightPolicy.MIN_VALID_SAMPLES_PER_PASSING_FRAME)
        listOf(1L to owner.epoch, 2L to owner.epoch,
            1L to owner.epoch.copy(recoveryGeneration = 1L)).forEach { (generation, epoch) ->
            main.updateRuntimeMetricOutputGate(evidence, generation, epoch,
                observeRuntimeAr = { nativeReads++; nativeLease.copy(generation = generation) })
            assertFalse(main.currentRuntimeMetricOutputAllowsWork(generation) { nativeLease.copy(generation = generation) })
        }
        assertEquals(0, nativeReads) // Domain admission masks epoch/generation here; no whole-Activity claim.
    }

    @Test fun nativeFrameQualificationAndTypedEvidenceDelegateToTheSameOriginalAssigner() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val preflightSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricPreflightCoordinator.kt").readText()
        val nativeEvidenceSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/AndroidRuntimeMetricPreflight.kt").readText()
        val native = MainActivityRefactorSource.member(source, "updateRuntimeMetricOutputGate", occurrence = 0)
        val observationDispatch = MainActivityRefactorSource.member(preflightSource, "updateOutputGate")
        val observation = MainActivityRefactorSource.member(nativeEvidenceSource, "observe")
        val preflightBinding = MainActivityRefactorSource.property(source, "runtimeMetricPreflight")
        assertTrue(native.contains("runtimeMetricPreflight.updateOutputGate(frame, snapshot, observedAtElapsedRealtimeMs, expectedArSessionGeneration, expectedWalkEpoch, acquisitionFailure, recognitionCameraTimestampNs)"))
        assertTrue(preflightBinding.contains("runtimeMetricStateLock, { runtimeMetricSession }, { arSessionOwner }, runtimeCameraPlatform"))
        assertTrue(preflightBinding.contains("AndroidRuntimeMetricFrameEvidence(RUNTIME_METRIC_MIN_RAW_CONFIDENCE)"))
        assertTrue(observationDispatch.contains("metric().updateRuntimeMetricFrameEvidence(expectedGeneration, expectedEpoch, acquisitionFailure,"))
        assertTrue(observationDispatch.contains("observeFrameEvidence = { frames.observe(frame, snapshot, observedAtMs, expectedGeneration, recognitionCameraTimestampNs) }"))
        listOf("FrameRecognitionAdmission.metricSampleCount(recognitionCameraTimestampNs, frame.timestamp, snapshot)",
            "frameTimestampNanos = frame.timestamp", "observedAtElapsedRealtimeMs = observedAtMs",
            "tracking = frame.camera.trackingState == TrackingState.TRACKING",
            "FrameRecognitionAdmission.hasMetricDepth(recognitionCameraTimestampNs, frame.timestamp, snapshot)").forEach {
            assertTrue(it, observation.contains(it))
        }
        val typed = source.substringAfter("internal fun updateRuntimeMetricOutputGate(")
            .substringBefore("private fun updateRuntimeMetricFrameEvidence(")
        assertTrue(typed.contains("observeRuntimeAr, observeRuntimeAdmission, observeFrameEvidence = { frameEvidence }"))
        val metricSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricSessionController.kt").readText()
        val shared = MainActivityRefactorSource.member(metricSource, "updateRuntimeMetricFrameEvidence")
        val admission = MainActivityRefactorSource.member(source, "updateRuntimeMetricFrameEvidence")
        MainActivityRefactorSource.composition(source, "runtimeMetricSession")
        assertTrue(shared.indexOf("!observeRuntimeAdmission()") < shared.indexOf("val frameEvidence = observeFrameEvidence()"))
        listOf("!scope.lifecycle.isRuntimeEpochCurrent(expectedWalkEpoch)",
            "hasPurposeAndGeneration(runtimePurpose, expectedArSessionGeneration)",
            "!scope.foreground", "!observeRuntimeAdmission()",
            "frameEvidence.generation != expectedArSessionGeneration", "activation.observe(frameEvidence)",
            "runtimeMetricActivationSession === activation", "RuntimeMetricPreflightPolicy.MIN_VALID_SAMPLES_PER_PASSING_FRAME",
            "assessRuntimeDepthFrame(", "runtimeMetricOutputAllowed = assessment.outputAllowed",
            "runtimeMetricOutputAllowed = frameEvidence.tracking &&").forEach { assertTrue(it, shared.contains(it)) }
        assertFalse(shared.contains("runtimeMetricOutputAllowed = true"))
        assertTrue(admission.contains("isWalkSessionRuntimeActive() && isStartupCapabilityConfirmed()"))
        val start = MainActivityRefactorSource.member(metricSource, "beginRuntimeMetricActivation")
        val metricBinding = MainActivityRefactorSource.composition(source, "runtimeMetricSession")
        assertTrue(start.contains("runtimeMetricOutputAllowed = false"))
        assertTrue(start.contains("runtimeMetricActivationSession = RuntimeMetricPreflightSession("))
        assertTrue(start.contains("generation = generation,"))
        assertTrue(start.contains("startedAtElapsedRealtimeMs = startedAtElapsedRealtimeMs,"))
        assertTrue(start.contains("profile = scope.profile,"))
        val transactions = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/RuntimeArLifecycleTransactions.kt").readText()
        val publisher = MainActivityRefactorSource.member(transactions, "start")
        val cameraBinding = MainActivityRefactorSource.composition(source, "runtimeCameraLifecycle")
        assertTrue(publisher.contains("arOwner.publish(newSession, provider, runtimePurpose) { generation ->"))
        assertTrue(publisher.contains("metric.beginActivation(generation, runtimeStartedAtMs)"))
        assertTrue(cameraBinding.contains("beginRuntimeMetricActivation(generation, startedAtMs)"))
        assertTrue(metricBinding.contains("override val profile get() = activeEnvironmentProfiles?.runtimeMetric"))
        assertTrue(publisher.contains("val runtimeStartedAtMs = elapsedRealtimeMs()"))
    }

    // Exact existing WalkRuntimeSafetyCoordinatorTest configuredCoordinator fixture; synthetic only.
    private fun syntheticProfile() = ApprovedWalkRuntimeSafetyThresholdProfile("approved-profile", 100L, 200L)

    private class FrameFixture {
        val main = MainActivity()
        private val pipeline = ObjectDepthRuntimePipeline()
        var depthCalls = 0
        var now = 51_000L
        lateinit var lastEvidence: DetectionFrameEvidence

        fun stableFrames(): PreparedTactileFrameDispatch {
            var last: PreparedTactileFrameDispatch? = null
            listOf(1_000L, 1_400L, 1_800L).forEach { last = frame(it) }
            return requireNotNull(last)
        }

        fun frame(atMs: Long, detected: Boolean = true, foreignEvidence: Boolean = false): PreparedTactileFrameDispatch {
            now = atMs + 50_040L
            val key = VisualFrameKey(0L, atMs * 1_000_000L, atMs * 1_000_000L + 400_000L, atMs + 50_000L, 3L)
            lastEvidence = evidence(key)
            val candidate = DetectionCandidate("dot_tactile_paving", .9f, RectNorm(.4f, .4f, .2f, .4f))
            val snapshot = DetectionSnapshot(
                detections = if (detected) listOf(candidate) else emptyList(),
                identity = AndroidDetectionSnapshotFrameIdentity(key.frameId, atMs),
                capturedAtMs = key.capturedAtElapsedRealtimeMs,
                startedAtMs = key.capturedAtElapsedRealtimeMs,
                completedAtMs = key.capturedAtElapsedRealtimeMs,
                imageWidth = 100, imageHeight = 100, detectDurationMs = 0L, detectorTiming = null,
                partial = false,
                frameEvidence = if (foreignEvidence) lastEvidence.copy(frameId = key.frameId + 1L) else lastEvidence,
                capturedAtElapsedRealtimeMs = key.capturedAtElapsedRealtimeMs,
            )
            return main.prepareTactileFrameDispatch(
                detectionSnapshot = snapshot,
                nowMs = now,
                currentFrameTimestampMs = atMs,
                maxDetectionSourceAgeMs = 400L,
                maxDetectionFrameDeltaMs = 400L,
                detectionAgeMs = 40L,
                decisionElapsedRealtimeMs = now,
            ) { capture, detections ->
                depthCalls++
                pipeline.process(
                    snapshot = capture.depthSnapshot,
                    frameId = capture.frameId,
                    timestampMs = capture.frameTimestampMs,
                    detections = detections,
                    mapper = requireNotNull(capture.depthMapper),
                    mapperFrameId = capture.depthMapper.frameId,
                    capturedAtElapsedRealtimeMs = key.capturedAtElapsedRealtimeMs,
                )
            }
        }

        private fun evidence(key: VisualFrameKey): DetectionFrameEvidence {
            val values = IntArray(10_000) { 2_000 }
            val depth = DepthFrameSnapshot(key.frameId, DepthImage16(100, 100, values),
                ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }), DepthImage16(100, 100, values.copyOf()),
                key.cameraTimestampNs, fullDepthTimestampNs = key.cameraTimestampNs,
                cameraImageTimestampNs = key.cameraTimestampNs, rawConfidenceTimestampNs = key.cameraTimestampNs)
            val transform = requireNotNull(FrozenImageToDepthTransform.create(key.frameId,
                listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f)))
            val context = TactileProjectionContext(key.frameId, null, null, null, null, null,
                TactileCameraFrame(key.frameId, RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                    TactileCameraIntrinsics(100, 100, 70f, 70f, 50f, 50f), key.capturedAtElapsedRealtimeMs, true),
                40L, key.capturedAtElapsedRealtimeMs, now)
            return DetectionFrameEvidence(key.frameId, key.frameId / 1_000_000L, depth,
                FrozenImageToTextureCoordinateMapper(key.frameId, transform, ImageSize(100, 100)),
                context, MotionContext(), false, false, visualFrameKey = key)
        }
    }
}
