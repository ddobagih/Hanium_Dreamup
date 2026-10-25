package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.TactileMaskCaptureBundle
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.navigation.TactileSnapshotFrameResult
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.*
import org.junit.Test

/**
 * Actual Main result admission/composition, default Coordinator, feedbackPolicy and Actor. Capture,
 * device facts and native speech effects are controlled inputs. Positive delivery does not run the
 * private Activity/GL/UI domain graph; its unchanged fail-closed gate and wiring are checked apart.
 */
class MainActivityTactileResultConsumptionBehaviorTest {
    @Test fun originalMaskAxisReachesExistingCoordinatorDeliveryAfterNewerArrivalOnly() {
        val f = Fixture()
        val bundle = f.capture.complete()
        set(f.main, "lastRuntimeCameraFrameId", f.capture.token.frameId + 1)
        val before = f.capture.depthCalls
        var delivered: TactileSnapshotFrameResult? = null
        f.main.tactileFrameFeedbackDelivery = { f.dispatches++ }
        f.forward(bundle) { frame, receipt ->
            delivered = frame
            assertNotNull(f.main.dispatchConsumedTactileFrameFeedback(receipt, frame, false,
                DeviceGateState().alertsAllowed, f.now))
        }
        assertEquals("Original mask result never reached Main Coordinator feedback delivery", 1, f.dispatches)
        val surface = requireNotNull(delivered?.guidance?.observation?.surfaceEvidence)
        assertEquals(TactileObservedRouteSupport.AXIS_AND_MULTIPOINT_OVERLAP, surface.observedRouteSupport)
        assertSame(bundle.semanticCapture.imageReceipt, surface.surface.candidate.capture.imageReceipt)
        assertEquals(bundle.semanticCapture.token, surface.surface.candidate.capture.token)
        assertSame(bundle.snapshot.frameEvidence, delivered!!.matchedEvidence)
        assertEquals(bundle.outputs.single().copy(userFacing = delivered!!.guidance.outputs.single().userFacing),
            delivered!!.guidance.outputs.single())
        assertEquals(before, f.capture.depthCalls)
        assertEquals(1, f.dispatches)
        assertEquals(LocalRouteMode.TMAP, delivered!!.guidance.decision.mode)
        assertNull(delivered!!.guidance.decision.instruction)
    }

    @Test fun oneOriginalMaskUpgradeFollowsTheGlResultAndCannotBeRepeatedOrRolledBack() {
        val f = Fixture(); val bundle = f.capture.complete()
        val base = f.emptyFrame()
        var baseReceipt: MainActivity.TactileResultReceipt? = null
        assertTrue(f.main.consumeTactileFrameResult(f.source, base) { _, receipt -> baseReceipt = receipt })
        assertTrue(f.forward(bundle) { _, receipt -> f.lastReceipt = receipt; f.dispatches++ })
        assertFalse(f.main.tactileResultIsCurrent(requireNotNull(baseReceipt)))
        assertFalse(f.forward(bundle) { _, _ -> fail("duplicate mask was consumed") })
        assertFalse(f.main.consumeTactileFrameResult(f.source, base) { _, _ -> fail("GL rolled back its mask upgrade") })
        assertEquals(1, f.dispatches)
    }

    @Test fun newerActualEmptyResultRejectsTheOldBundleWithoutReplacingItsDeliveryState() {
        val f = Fixture(); val bundle = f.capture.complete(); val empty = f.emptyFrame()
        f.main.tactileFrameFeedbackDelivery = { f.lastDispatch = it; f.dispatches++ }
        assertTrue(f.main.consumeTactileFrameResult(f.newerSource(), empty) { frame, receipt ->
            f.lastReceipt = receipt
            f.main.dispatchConsumedTactileFrameFeedback(receipt, frame, false, DeviceGateState().alertsAllowed, f.now)
        })
        val latest = f.lastDispatch
        assertNotNull(latest)
        assertFalse(f.forward(bundle) { _, _ -> fail("old LINE cleared a newer empty result") })
        assertSame(latest, f.lastDispatch)
        assertTrue(f.main.tactileResultIsCurrent(requireNotNull(f.lastReceipt)))
        assertEquals(1, f.dispatches)
    }

    @Test fun newerRealRiskKeepsItsClaimedOwnerWhenTheOldBundleCompletes() {
        val f = Fixture(); val bundle = f.capture.complete(); val risk = f.riskFrame()
        val runtime = Runtime(f.now)
        val actor = AndroidFeedbackActuator(runtime = runtime, hapticAllowed = { false })
        runtime.ready()
        val policy = field(f.main, "feedbackPolicy") as WalkSafeFeedbackPolicy
        var action: FeedbackAction? = null
        try {
            f.main.tactileFrameFeedbackDelivery = { dispatch ->
                val current = requireNotNull(dispatch.action)
                action = current
                assertTrue(policy.claimFeedbackDelivery(current, dispatch.policyEvaluatedAtMs, runtime.now))
                actor.emit(current, isStillValidAtStart = {
                    f.main.tactileResultIsCurrent(requireNotNull(f.lastReceipt)) && policy.hasClaimedFeedbackDelivery(current)
                }, onSpeechCompleted = { policy.confirmFeedbackDelivery(current, dispatch.policyEvaluatedAtMs, runtime.now) },
                    onSpeechFailed = { policy.rejectUndeliveredFeedback(current, dispatch.policyEvaluatedAtMs) },
                    onSpeechCancelled = { policy.rejectUndeliveredFeedback(current, dispatch.policyEvaluatedAtMs) })
            }
            val gate = DeviceGateState(cameraPermissionGranted = true, arCoreSupported = true, depthSupported = true,
                tfliteConfigLoaded = true, detectorAvailable = true, arSessionRunning = true,
                freshDepthObject = risk.guidance.outputs.any { it.source.metric && it.riskDistanceM != null })
            assertTrue(f.main.consumeTactileFrameResult(f.sourceOf(risk), risk) { frame, receipt ->
                f.lastReceipt = receipt
                f.main.dispatchConsumedTactileFrameFeedback(receipt, frame, false, gate.alertsAllowed, f.now)
            })
            val issued = requireNotNull(action)
            assertTrue(policy.hasClaimedFeedbackDelivery(issued))
            assertFalse(f.forward(bundle) { _, _ -> fail("old LINE reconciled the new risk as safe") })
            assertTrue(policy.hasClaimedFeedbackDelivery(issued))
            assertTrue(actor.isAppSpeechActive())
            assertEquals(1, runtime.spoken.size)
            runtime.start(runtime.spoken.single())
            runtime.done(runtime.spoken.single())
            assertFalse(actor.isAppSpeechActive())
        } finally { actor.close() }
    }

    @Test fun newerRealDotNoticeRemainsCurrentInsteadOfBeingClearedByOldLineNullNotice() {
        val f = Fixture(); val bundle = f.capture.complete(); val dot = f.dotFrame()
        val notice = requireNotNull(dot.guidance.dotReferenceNotice)
        assertTrue(f.main.consumeTactileFrameResult(f.sourceOf(dot), dot) { frame, receipt ->
            f.lastReceipt = receipt
            f.main.updateTactileDotReferenceObservation(frame.guidance.dotReferenceNotice, f.owner, f.now)
        })
        val policy = field(f.main, "tactileDotReferenceDeliveryPolicy") as TactileDotReferenceDeliveryPolicy
        val reservation = requireNotNull(policy.reserveCurrent(f.owner, f.now))
        assertSame(notice, reservation.notice)
        assertFalse(f.forward(bundle) { frame, _ ->
            f.main.updateTactileDotReferenceObservation(frame.guidance.dotReferenceNotice, f.owner, f.now)
        })
        assertTrue(policy.isCurrent(reservation, f.now))
        assertSame(notice, reservation.notice)
    }

    @Test fun retiredCaptureCannotReplaceTheNewGeometryReceiptAfterTheEarlierOwnerRead() {
        val f = Fixture(); val bundle = f.capture.complete()
        // The real capture was current at the worker's earlier read, then its owner retired.
        assertFalse(f.capture.join.isCancelled())
        f.capture.join.cancel()
        val replacementSource = f.newerSource().copy(geometryVersion = f.source.geometryVersion + 1L)
        var replacementReceipt: MainActivity.TactileResultReceipt? = null
        assertTrue(f.main.consumeTactileFrameResult(replacementSource, f.emptyFrame()) { _, receipt ->
            replacementReceipt = receipt
        })
        var oldConsumerCalls = 0
        val admitted = f.main.deliverTactileMaskCaptureBundle(bundle, f.capture.decisionNs,
            f.surfaces(bundle), f.source, admissionIsCurrent = { !f.capture.join.isCancelled() }) { _, _ ->
            oldConsumerCalls++
        }
        assertTrue("Retired capture replaced the new geometry receipt", f.main.tactileResultIsCurrent(requireNotNull(replacementReceipt)))
        assertFalse(admitted)
        assertEquals(0, oldConsumerCalls)
        assertEquals(4, f.capture.depthCalls)
    }

    @Test fun queuedReceiptCannotDispatchAfterANewerResultOrRouteReinstall() {
        val f = Fixture(); val bundle = f.capture.complete()
        var queued: TactileSnapshotFrameResult? = null
        assertTrue(f.forward(bundle) { frame, receipt -> queued = frame; f.lastReceipt = receipt })
        f.main.tactileFrameFeedbackDelivery = { fail("stale result reached delivery") }
        assertTrue(f.main.consumeTactileFrameResult(f.newerSource(), f.emptyFrame()) { _, _ -> })
        assertNull(f.main.dispatchConsumedTactileFrameFeedback(requireNotNull(f.lastReceipt), requireNotNull(queued),
            false, DeviceGateState().alertsAllowed, f.now))
        val replacement = Fixture(); val second = replacement.capture.complete()
        assertTrue(replacement.forward(second) { frame, receipt -> queued = frame; replacement.lastReceipt = receipt })
        replacement.capture.navigator.setRoute(replacement.capture.route)
        assertFalse(replacement.main.tactileResultIsCurrent(requireNotNull(replacement.lastReceipt)))
    }

    @Test fun originalRouteProgressAndReinstallAreRecheckedBeforeTheConsumer() {
        listOf(false, true).forEach { reinstall ->
            val f = Fixture(); val bundle = f.capture.complete(); val context = f.capture.context
            if (reinstall) f.capture.navigator.setRoute(f.capture.route)
            else f.capture.navigator.update(TrustedLocation(37.0015, 127.0, .5f, 100_050L), 100_050L, false)
            assertFalse(f.forward(bundle) { _, _ -> fail("changed route consumed an original capture") })
            assertSame(context, bundle.snapshot.frameEvidence!!.tactileContext)
            assertEquals(4, f.capture.depthCalls)
        }
    }

    @Test fun originalRouteTupleIsRejectedIfProgressOrReinstallChangesAfterRealEvaluation() {
        listOf(false, true).forEach { reinstall ->
            val f = Fixture(); val bundle = f.capture.complete()
            val capturedSource = f.source
            val guidance = f.main.evaluateTactileMaskCaptureBundle(bundle, f.capture.decisionNs, f.surfaces(bundle))
            assertNotNull(guidance.observation)
            val frame = TactileSnapshotFrameResult(bundle.snapshot.frameEvidence,
                bundle.snapshot.detections, depthProcessingAttempted = false, guidance)
            // Actual evaluation is already complete; the Navigator then changes its live tuple.
            if (reinstall) f.capture.navigator.setRoute(f.capture.route)
            else f.capture.navigator.update(TrustedLocation(37.0015, 127.0, .5f, 100_050L), 100_050L, false)
            assertFalse(f.main.consumeTactileFrameResult(capturedSource, frame, maskUpgrade = true) { _, _ ->
                fail("An old evaluation was admitted against a later route tuple")
            })
            assertEquals(bundle.snapshot.frameEvidence!!.tactileContext.expectedRouteId, capturedSource.routeId)
            assertEquals(bundle.snapshot.frameEvidence!!.tactileContext.routeProjection!!.routeRevision,
                capturedSource.routeProgress!!.routeVersion)
            assertEquals(4, f.capture.depthCalls)
        }
    }

    @Test fun sourceClockCpuIdentityAndOriginalDeadlineCannotBeRetaggedAtForwarding() {
        val f = Fixture(); val bundle = f.capture.complete()
        val invalid = listOf(f.source.copy(frameId = f.source.frameId + 1),
            f.source.copy(capturedAtElapsedRealtimeMs = f.source.capturedAtElapsedRealtimeMs + 1),
            f.source.copy(cpuImageTimestampNs = requireNotNull(f.source.cpuImageTimestampNs) + 1))
        invalid.forEach { source ->
            assertFalse(f.main.deliverTactileMaskCaptureBundle(bundle, f.capture.decisionNs, f.surfaces(bundle), source) { _, _ ->
                fail("foreign source was retagged")
            })
        }
        assertFalse(f.main.deliverTactileMaskCaptureBundle(bundle, bundle.semanticCapture.originalDeadlineElapsedNs + 1,
            f.surfaces(bundle), f.source) { _, _ -> fail("expired bundle got a new deadline") })
        assertEquals(4, f.capture.depthCalls)
    }

    @Test fun consumerFailureDoesNotRestoreAnOlderReceiptAndAReplacementRemainsUsable() {
        val f = Fixture(); val bundle = f.capture.complete(); val failure = IllegalStateException("delivery boundary")
        f.main.tactileFrameFeedbackDelivery = { f.dispatches++ }
        try { f.forward(bundle) { _, _ -> throw failure }; fail("failure was swallowed") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertFalse(f.forward(bundle) { _, _ -> fail("failed capture was replayed") })
        assertTrue(f.main.consumeTactileFrameResult(f.newerSource(), f.emptyFrame()) { frame, receipt ->
            f.lastReceipt = receipt
            assertNotNull(f.main.dispatchConsumedTactileFrameFeedback(receipt, frame, false, DeviceGateState().alertsAllowed, f.now))
        })
        assertEquals(1, f.dispatches)
    }

    @Test fun defaultPrivatePublicationStillRejectsTheUnpreparedDomainOwnerBeforeClockReads() {
        val f = Fixture(); val bundle = f.capture.complete(); var clocks = 0
        assertTrue(f.forward(bundle) { frame, _ ->
            assertFalse(f.main.publishTactileFrameReferenceObservation(frame, f.owner) { clocks++; f.now })
        })
        assertEquals(0, clocks)
    }

    @Test fun multipleCapturedPlaneLayersStayRejectedThroughTheActualMainConsumer() {
        val f = Fixture(); val bundle = f.capture.complete()
        f.main.tactileFrameFeedbackDelivery = { f.dispatches++ }
        val projected = f.surfaces(bundle).single()
        val original = requireNotNull(projected.surface)
        val ground = original.pose.horizontalPlaneCandidates.single()
        val other = ground.copy(polygonInAnchor = ground.polygonInAnchor.map { it.copy(y = it.y + 1.5f) })
        val ambiguous = TactileMaskSurface(original.candidate, original.source, original.depthTimestampNs,
            original.depthMatchesCameraImage, original.pose.copy(horizontalPlaneCandidates = listOf(ground, other)),
            original.samplingStatus, original.samplingReason, original.depthWidth, original.depthHeight,
            original.captureContext, original.samples, original.measuredContact)
        assertTrue(f.main.deliverTactileMaskCaptureBundle(bundle, f.capture.decisionNs,
            listOf(projected.copy(surface = ambiguous)), f.source) { frame, receipt ->
            assertEquals(TactileSurfaceRouteRejection.PLANE_AMBIGUOUS, frame.guidance.surfaceEvaluations[0]?.rejection)
            assertNull(frame.guidance.observation)
            assertEquals(LocalRouteMode.TMAP, frame.guidance.decision.mode)
            assertNull(frame.guidance.decision.instruction)
            assertNotNull(f.main.dispatchConsumedTactileFrameFeedback(receipt, frame, false, DeviceGateState().alertsAllowed, f.now))
        })
        assertEquals(4, f.capture.depthCalls)
        assertEquals(1, f.dispatches)
    }

    @Test fun sourceBothRealProducersUseTheOrderedConsumerAndOriginalUiStartFences() {
        fun owner(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
        val main = source()
        val worker = MainActivityRefactorSource.member(owner("runtime/vision/unknown/UnknownCaptureCoordinator"), "scheduleUnknownTactileJoin")
        listOf("observer.publishTactile(bundle, surfaces, publication, decisionNs,", "admissionIsCurrent = {",
            "join.context.tactileJoin === join", "publication.feedbackLifecycleGeneration == feedbackLifecycleGeneration",
            "unknownTactileResultOwnerIsCurrent(join, bundle, decisionNs)", "if (!finished) join.capture.cancel()",
            "if (join.context.tactileJoin === join) join.context.tactileJoin = null")
            .forEach { assertTrue(it, worker.contains(it)) }
        assertFalse(worker.contains("captureLease.isClosed"))
        assertTrue(main.contains("deliverTactileMaskCaptureBundle(bundle, decisionNs, surfaces, source,"))
        assertTrue(main.contains("publishCompletedTactileMaskCapture(publication, frame, receipt, decisionNs, outputIsCurrent)"))
        val publish = owner("runtime/vision/capture/CompletedTactileCapturePublisher")
        assertTrue(publish.contains("reference.publish("))
        assertTrue(publish.contains("dispatchFeedback("))
        assertTrue(publish.contains("outputIsCurrent"))
        val gl = owner("runtime/vision/frame/FramePipelineController")
        listOf("tactile.consume(sourceLease.issued, raw,", "activeRouteId = sourceLease.routeId",
            "activeRouteProgress = sourceLease.routeProgress", "val sourceLease = issued ?: return null",
            "sourceLease.geometryVersion == synthesis.geometryVersion",
            "sourceLease.owner.feedbackLifecycleGeneration == tactile.feedbackLifecycleGeneration()",
            "runtime.visualOutputAllowsWork(lease, native.timestamp, currentFrameTracking)", "tactile.resultIsCurrent(token)")
            .forEach { assertTrue(it, gl.contains(it)) }
        val publication = owner("runtime/vision/frame/FramePublicationController")
        assertTrue(publication.contains("tactile.dispatchFeedback(input.receipt"))
        assertTrue(publication.contains("risk.emitFeedback(dispatch, producer, input.sourceIsCurrent, input.sourceIsCurrent)"))
    }

    @Test fun sourceArrivalNeverClaimsPublicationAndPolicyDeliveryLeavesTheFrameLock() {
        val capture = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/capture/TactileFrameCaptureController.kt").readText()
        val admission = MainActivityRefactorSource.member(capture, "consumeTactileFrameResult")
        assertFalse(admission.contains("lastRuntimeCameraFrameId")); assertFalse(admission.contains("lastVisualFrameKey"))
        assertTrue(admission.indexOf("admissionIsCurrent?.invoke() == false") < admission.indexOf("latestConsumedResult = receipt"))
        assertTrue(admission.contains("source.frameId < old.frameId")); assertTrue(admission.contains("!maskUpgrade || previous.maskUpgrade"))
        val delivery = MainActivityRefactorSource.member(capture, "dispatchConsumedTactileFrameFeedback")
        assertTrue(delivery.contains("if (!tactileResultIsCurrent(receipt)) return null"))
        assertTrue(delivery.indexOf("}).emit(dispatch)") > delivery.indexOf("        }\n"))
        val dot = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/capture/TactileDotReferenceDispatchController.kt").readText()
        assertTrue(dot.contains("sourceIsCurrent?.invoke() != false && ownerIsCurrent()"))
        assertTrue(dot.contains("completionOwnerIsCurrent = ownerIsCurrent"))
    }

    private class Fixture {
        val capture = MainActivityTactileMaskSurfaceJoinIntegrationTest.Fixture()
        val main = capture.main
        val owner = TactileDotReferenceDeliveryOwner(WalkRuntimeEpoch("original-result", 0L), 0, 0, 1L)
        val source = MainActivity.TactileResultSource(owner, capture.token.frameId,
            capture.token.capturedElapsedNs / 1_000_000L, capture.token.cpuImageTimestampNs, 0L,
            capture.navigator.currentRouteId(), capture.navigator.currentTactileRouteProgress())
        var now = capture.decisionNs / 1_000_000L
        var dispatches = 0
        var lastReceipt: MainActivity.TactileResultReceipt? = null
        var lastDispatch: TactileFrameFeedbackDispatch? = null
        fun surfaces(bundle: TactileMaskCaptureBundle) = main.projectTactileMaskCaptureBundle(bundle, capture.decisionNs)
        fun forward(bundle: TactileMaskCaptureBundle,
            consumer: (TactileSnapshotFrameResult, MainActivity.TactileResultReceipt) -> Unit) =
            main.deliverTactileMaskCaptureBundle(bundle, now * 1_000_000L, surfaces(bundle), source, consume = consumer)
        fun newerSource() = source.copy(frameId = source.frameId + 1, capturedAtElapsedRealtimeMs = now)
        fun emptyFrame() = main.processTactileSnapshotFrame(DetectionSnapshot.empty(), now, now,
            400L, 400L, null, now) { _, _ -> fail("empty capture cannot process depth"); emptyList() }
        fun sourceOf(frame: TactileSnapshotFrameResult): MainActivity.TactileResultSource {
            val evidence = requireNotNull(frame.matchedEvidence)
            return source.copy(frameId = evidence.frameId, capturedAtElapsedRealtimeMs = evidence.tactileContext.nowElapsedRealtimeMs,
                cpuImageTimestampNs = evidence.depthSnapshot.cameraImageTimestampNs)
        }
        fun riskFrame() = producedFrame("person", 1_000)
        fun dotFrame() = producedFrame("dot_tactile_paving", 2_000)
        private fun producedFrame(kind: String, depthMm: Int): TactileSnapshotFrameResult {
            val pipeline = ObjectDepthRuntimePipeline()
            var final: TactileSnapshotFrameResult? = null
            // Original inputs advance together, without completing-time re-tagging any bundle.
            (1L..10L).forEach { step ->
                val captured = 99_150L + step * 100L
                val id = capture.token.frameId + step * 100_000_000L
                val depth = DepthFrameSnapshot(id, DepthImage16(100, 100, IntArray(10_000) { depthMm }),
                    ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }), null,
                    rawDepthTimestampNs = id, rawConfidenceTimestampNs = id, cameraImageTimestampNs = id)
                val transform = requireNotNull(FrozenImageToDepthTransform.create(id,
                    listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f)))
                val evidence = DetectionFrameEvidence(id, id / 1_000_000L, depth,
                    FrozenImageToTextureCoordinateMapper(id, transform, ImageSize(100, 100)),
                    capture.context.copy(captureFrameId = id, nowElapsedRealtimeMs = captured,
                        cameraFrame = capture.context.cameraFrame?.copy(frameId = id, observedAtElapsedRealtimeMs = captured)),
                    MotionContext(), true, true)
                val snapshot = DetectionSnapshot(listOf(DetectionCandidate(kind, .95f, RectNorm(.4f, .4f, .2f, .4f))),
                    AndroidDetectionSnapshotFrameIdentity(id, id / 1_000_000L),
                    captured, captured, captured, 100, 100, 0L, null, partial = false,
                    frameEvidence = evidence, capturedAtElapsedRealtimeMs = captured)
                now = captured + 40L
                final = main.processTactileSnapshotFrame(snapshot, now, id / 1_000_000L, 400L, 400L, 40L, now) { e, detections ->
                    pipeline.process(e.depthSnapshot, e.frameId, e.frameTimestampMs, detections,
                        mapper = requireNotNull(e.depthMapper), mapperFrameId = e.depthMapper.frameId,
                        capturedAtElapsedRealtimeMs = captured)
                }
            }
            return requireNotNull(final)
        }
    }

    private class Runtime(var now: Long) : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        val spoken = mutableListOf<String>()
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized get() = initialized != null
        override fun elapsedRealtime() = now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int { spoken += utteranceId; return TextToSpeech.SUCCESS }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("risk emits no tone")
        override fun post(task: Runnable): Boolean { task.run(); return true }
        override fun postDelayed(task: Runnable, delayMs: Long) = true
        override fun removeCallbacks(task: Runnable) = Unit
        override fun removeCallbacksAndMessages(token: Any?) = Unit
        fun ready() { requireNotNull(initialized).invoke(TextToSpeech.SUCCESS) }
        fun start(id: String) { requireNotNull(listener).onStart(id) }
        fun done(id: String) { requireNotNull(listener).onDone(id) }
    }

    private companion object {
        fun field(main: MainActivity, name: String) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)
        fun set(main: MainActivity, name: String, value: Any?) { MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value) }
        fun source() = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    }
}
