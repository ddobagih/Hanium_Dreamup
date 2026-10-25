package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.navigation.TactileSnapshotFrameResult
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.ReportFrameCaptureFacts
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.report.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.*
import org.junit.Test

/**
 * Actual stable depth producer and Main's consumed-frame Coordinator/Policy leaf. Sensor arrays,
 * device facts and native effect ports are controlled inputs. This does not exercise Main's final
 * emitFeedbackAction, OS qualification, GL/UI publication, real speech/haptics or server delivery.
 * B is a normal obstacle warning; C is a separate silent report-only damage result from the same
 * capture. The historical exact method name is preserved; the unchanged report proof belongs to C.
 */
class MainActivityCandidateDepthLeaseBehaviorTest {
    @Test
    fun liveIndependentAIsSelectedWhileOnlyOriginalMeasuredBExpiresWithTheSameReportProof() {
        val f = Fixture()
        f.capture(350L)
        f.capture(700L)
        val selected = f.capture(1_050L)
        val b = selected.second.guidance.outputs.single { it.sourceDetectionIndex == 1 }
        val c = selected.second.guidance.outputs.single { it.sourceDetectionIndex == 2 }
        val reportSnapshot = selected.first
        val reportFrame = selected.second
        val reportEvidence = requireNotNull(reportSnapshot.frameEvidence)
        val reportImage = requireNotNull(reportSnapshot.reportImage)
        val captureKey = requireNotNull(reportEvidence.visualFrameKey)
        val before = requireNotNull(selected.first.capturedAtElapsedRealtimeMs) + 40L
        val aCapture = f.capture(1_600L, onlyUnnamedA = true)
        val producedA = aCapture.second.guidance.outputs.single()
        assertTrue("The actual estimator must calculate the ray required by public unnamed admission",
            producedA.rayDistanceM?.let { it.isFinite() && it > 0f && it <= 3f } == true)
        val aCompleted = requireNotNull(aCapture.first.capturedAtElapsedRealtimeMs) + 40L
        val epoch = WalkRuntimeEpoch("original-result", 0L)
        val batch = requireNotNull(UnknownObjectFeedbackPolicy().admit(
            outputs = listOf(producedA), sourceFrameId = producedA.frameId,
            sourceTimestampMs = producedA.timestampMs,
            sourceCapturedAtElapsedRealtimeNs = requireNotNull(aCapture.first.capturedAtElapsedRealtimeMs) * 1_000_000L,
            completedAtElapsedRealtimeMs = aCompleted, sourceEpoch = epoch, currentEpoch = epoch,
            nowElapsedRealtimeMs = aCompleted,
        ))
        val a = batch.outputs.single()
        val aDeadline = requireNotNull(a.sourceValidUntilElapsedRealtimeMs)
        val bDeadline = requireNotNull(b.sourceValidUntilElapsedRealtimeMs)
        val cDeadline = requireNotNull(c.sourceValidUntilElapsedRealtimeMs)
        val after = maxOf(Math.addExact(maxOf(bDeadline, cDeadline), 1L), batch.completedAtElapsedRealtimeMs)

        // Each real independent sensor capture keeps its own frame/elapsed receipt and lease.
        // The public unnamed admission, rather than the internal batch constructor, freezes A.
        assertEquals(UnknownObjectFeedbackPolicy.CLASS_NAME, a.className)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, a.source)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, b.source)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, c.source)
        assertEquals("bollard", b.className)
        assertEquals("damaged_tactile_block", c.className)
        // These outputs have no standalone epoch field: their original source indices and frames
        // bind both to this unchanged capture evidence and Main's original-result epoch owner.
        assertEquals(WalkRuntimeEpoch("original-result", captureKey.epoch), epoch)
        assertEquals(captureKey.frameId, b.frameId)
        assertEquals(captureKey.frameId, c.frameId)
        assertEquals(captureKey.frameId / 1_000_000L, b.timestampMs)
        assertEquals(captureKey.frameId / 1_000_000L, c.timestampMs)
        assertEquals(producedA.frameId, a.frameId)
        assertEquals(producedA.sourceObservedAtElapsedRealtimeMs, a.sourceObservedAtElapsedRealtimeMs)
        assertEquals(producedA.sourceValidUntilElapsedRealtimeMs, a.sourceValidUntilElapsedRealtimeMs)
        assertEquals(selected.first.capturedAtElapsedRealtimeMs, b.sourceObservedAtElapsedRealtimeMs)
        assertEquals(selected.first.capturedAtElapsedRealtimeMs, c.sourceObservedAtElapsedRealtimeMs)
        assertTrue(a.trackAgeFrames >= 3 && a.trackStableMs >= 700L)
        assertTrue(b.trackAgeFrames >= 3 && b.trackStableMs >= 700L)
        assertTrue(c.trackAgeFrames >= 3 && c.trackStableMs >= 700L)
        assertTrue(before < bDeadline)
        assertTrue(before < cDeadline)
        assertTrue(bDeadline < after && after <= aDeadline)
        assertTrue(cDeadline < after && after <= aDeadline)
        assertTrue(batch.isFreshAt(after))
        val beforeGate = f.deviceGate(selected.second.guidance.outputs, before)
        val gate = f.deviceGate(batch.outputs, after)
        assertEquals("The calculated controlled device premise must be the same", beforeGate, gate)
        assertTrue(gate.alertsAllowed)
        assertTrue(gate.reportCandidatesAllowed)
        val feedback = field(f.main, "feedbackPolicy") as WalkSafeFeedbackPolicy
        val bDescription = "B actual class=${b.className} source=${b.source} z=${b.zDistanceM} " +
            "risk=${b.riskDistanceM} confidence=${b.confidence.finalScore} level=${b.userFacing.messageLevel} " +
            "message=${b.userFacing.message} observed=${b.sourceObservedAtElapsedRealtimeMs} " +
            "deadline=${b.sourceValidUntilElapsedRealtimeMs} track=${b.trackAgeFrames}/${b.trackStableMs}"
        val cDescription = "C actual class=${c.className} source=${c.source} z=${c.zDistanceM} " +
            "risk=${c.riskDistanceM} confidence=${c.confidence.finalScore} level=${c.userFacing.messageLevel} " +
            "message=${c.userFacing.message} observed=${c.sourceObservedAtElapsedRealtimeMs} " +
            "deadline=${c.sourceValidUntilElapsedRealtimeMs} track=${c.trackAgeFrames}/${c.trackStableMs}"
        val candidateB = requireNotNull(b.toFeedbackCandidate()) { bDescription }
        assertTrue(bDescription, b.userFacing.messageLevel in setOf(MessageLevel.WARNING, MessageLevel.STOP))
        assertEquals(cDescription, MessageLevel.NONE, c.userFacing.messageLevel)
        assertNull("C must retain the existing silent report-only contract: $cDescription", c.toFeedbackCandidate())

        // Positive B control has no completed history. Its unplayed reservation is retired through
        // the original public policy API before the combined consumed-frame observation.
        assertEquals(bDeadline, candidateB.validUntilMs)
        assertTrue(feedback.activeFeedbackDeliveryKeys(listOf(candidateB), gate.alertsAllowed, before)
            .any { it.startsWith("${b.trackId}|") })
        val controlB = requireNotNull(feedback.evaluateCandidates(listOf(candidateB), gate.alertsAllowed, before))
        assertEquals(b.trackId, controlB.trackId)
        assertEquals(b.trackId, requireNotNull(controlB.deliveryOwner).trackId)
        val beforeCReport = f.reportInput(c, selected, gate, before)
        assertSame(c, beforeCReport.depth)
        assertEquals(cDescription, "pass", beforeCReport.coordinateGateStatus)
        assertTrue("C must be reportable before its original lease expires: $cDescription",
            f.reportPolicy.evaluate(beforeCReport) is AndroidReportCandidateResult.Ready)
        feedback.rejectUndeliveredFeedback(controlB, before)
        assertFalse(feedback.hasClaimedFeedbackDelivery(controlB))

        val runtime = Runtime(after)
        val actor = AndroidFeedbackActuator(runtime = runtime, hapticAllowed = { gate.actuatorsAllowed })
        var failure: Throwable? = null
        try {
            runtime.ready()
            var receipt: MainActivity.TactileResultReceipt? = null
            val issued = mutableListOf<FeedbackAction>()
            f.main.tactileFrameFeedbackDelivery = { dispatch ->
                dispatch.action?.let { action ->
                    assertEquals(a.trackId, action.trackId)
                    assertTrue(feedback.claimFeedbackDelivery(action, dispatch.policyEvaluatedAtMs, runtime.now))
                    issued += action
                    runtime.emittingOwner = requireNotNull(action.deliveryOwner)
                    actor.emit(action,
                        isStillValidAtStart = {
                            f.main.tactileResultIsCurrent(requireNotNull(receipt)) &&
                                feedback.hasClaimedFeedbackDelivery(action)
                        },
                        onSpeechCompleted = {
                            feedback.confirmFeedbackDelivery(action, dispatch.policyEvaluatedAtMs, runtime.now)
                        },
                        onSpeechFailed = { feedback.rejectUndeliveredFeedback(action, dispatch.policyEvaluatedAtMs) },
                        onSpeechCancelled = { feedback.rejectUndeliveredFeedback(action, dispatch.policyEvaluatedAtMs) },
                    )
                }
            }
            var dispatch: TactileFrameFeedbackDispatch? = null
            assertTrue(f.main.consumeTactileFrameResult(f.source(selected), selected.second) { frame, admitted ->
                receipt = admitted
                dispatch = f.main.dispatchConsumedTactileFrameFeedback(admitted, frame, false,
                    gate.alertsAllowed, after, independentFreshOutputs = listOf(batch))
            })
            val current = requireNotNull(dispatch)
            assertTrue(f.main.tactileResultIsCurrent(requireNotNull(receipt)))
            assertTrue(current.activeFeedbackDeliveryKeys.any { it.startsWith("${a.trackId}|") })
            assertFalse(current.activeFeedbackDeliveryKeys.any { it.startsWith("${b.trackId}|") })
            assertFalse(current.activeRiskTrackIds.contains(b.trackId))
            val actionA = issued.single()
            val ownerA = requireNotNull(actionA.deliveryOwner)
            assertEquals(a.trackId, ownerA.trackId)
            assertTrue(actionA.validUntilMs > after && actionA.validUntilMs <= aDeadline)
            assertNotSame(controlB.deliveryOwner, ownerA)
            assertTrue(feedback.hasClaimedFeedbackDelivery(actionA))
            assertEquals(listOf(a.trackId), runtime.spoken.map { it.owner.trackId })
            assertTrue("A must exercise the existing haptic effect port", runtime.vibrations.isNotEmpty())
            assertTrue(runtime.vibrations.all { it.first.trackId == a.trackId })
            assertFalse(runtime.spoken.any { it.owner.trackId == b.trackId })
            assertFalse(runtime.vibrations.any { it.first.trackId == b.trackId })

            // C's selected output/snapshot/image/frame/coordinate proof is unchanged; only the
            // request clock advances. The silent report class is never forced into B's warning.
            assertSame(reportSnapshot, selected.first)
            assertSame(reportFrame, selected.second)
            assertSame(reportEvidence, selected.first.frameEvidence)
            assertSame(reportImage, selected.first.reportImage)
            val expiredCReport = f.reportInput(c, selected, gate, after)
            assertSame(c, expiredCReport.depth)
            assertSame(beforeCReport.depth, expiredCReport.depth)
            assertEquals(beforeCReport.capturedAtMs, expiredCReport.capturedAtMs)
            assertEquals(beforeCReport.coordinateGateStatus, expiredCReport.coordinateGateStatus)
            assertEquals(cDescription, "pass", expiredCReport.coordinateGateStatus)
            assertEquals(AndroidReportCandidateResult.Rejected(AndroidReportCandidateRejection.DEPTH_EXPIRED),
                f.reportPolicy.evaluate(expiredCReport))
            assertTrue(requireNotNull(expiredCReport.detectionAgeMs) in 0L..AndroidReportCandidatePolicy.REPORT_MAX_DETECTION_AGE_MS)
            assertEquals(aDeadline, a.sourceValidUntilElapsedRealtimeMs)
            assertEquals(bDeadline, b.sourceValidUntilElapsedRealtimeMs)
            assertEquals(cDeadline, c.sourceValidUntilElapsedRealtimeMs)

            val second = requireNotNull(f.main.dispatchConsumedTactileFrameFeedback(
                requireNotNull(receipt), selected.second, false, gate.alertsAllowed, after,
                independentFreshOutputs = listOf(batch)))
            assertFalse(second.activeFeedbackDeliveryKeys.any { it.startsWith("${b.trackId}|") })
            assertEquals(1, issued.size)
            assertSame(ownerA, issued.single().deliveryOwner)
            assertTrue(feedback.hasClaimedFeedbackDelivery(actionA))
            assertEquals(1, runtime.spoken.size)
            runtime.start(runtime.spoken.single().id)
            runtime.done(runtime.spoken.single().id)
            assertFalse(actor.isAppSpeechActive())
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            var first = failure
            try {
                actor.close()
            } catch (cleanup: Throwable) {
                if (first == null) first = cleanup else first.addSuppressed(cleanup)
            }
            try {
                feedback.cancelPendingFeedbackDeliveries()
            } catch (cleanup: Throwable) {
                if (first == null) first = cleanup else first.addSuppressed(cleanup)
            }
            if (failure == null) first?.let { throw it }
        }
    }

    private class Fixture {
        val main = MainActivity()
        private val pipeline = field(main, "objectDepthPipeline") as ObjectDepthRuntimePipeline
        val reportPolicy = field(main, "reportCandidatePolicy") as AndroidReportCandidatePolicy
        private val coordinator = (field(main, "frozenReportFrameCoordinator\$delegate") as Lazy<*>).value as AndroidTactileFrameCoordinator
        private val navigator = field(main, "routeNavigator") as RouteNavigator

        fun capture(at: Long, onlyUnnamedA: Boolean = false)
            : Pair<DetectionSnapshot, TactileSnapshotFrameResult> {
            val geometry = field(main, "visualGeometryVersion") as Long
            val key = VisualFrameKey(0L, at * 1_000_000L, at * 1_000_000L + 400_000L, at + 50_000L, geometry)
            val raw = DepthImage16(100, 100, IntArray(10_000) { 1_000 })
            val cameraIntrinsics = TactileCameraIntrinsics(100, 100, 70f, 70f, 50f, 50f)
            // Same captured image/depth basis in one controlled reference. This typed sensor
            // premise is not an actual ARCore pose, Main permission or runtime qualification.
            val pose = CameraPoseEvidence(
                referenceId = key.epoch, timestampMs = key.cameraTimestampNs / 1_000_000L,
                positionX = 0f, positionY = 0f, positionZ = 0f,
                forwardX = 0f, forwardY = 0f, forwardZ = 1f,
                imageProjection = CameraImageProjection(
                    raw.width, raw.height, cameraIntrinsics.focalLengthXPx, cameraIntrinsics.focalLengthYPx,
                    cameraIntrinsics.principalPointXPx, cameraIntrinsics.principalPointYPx,
                    1f, 0f, 0f, 0f, 1f, 0f,
                ),
            )
            val confidence = ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() })
            val depth = DepthFrameSnapshot(key.frameId, raw, confidence,
                DepthImage16(100, 100, raw.millimeters.copyOf()),
                rawDepthTimestampNs = key.cameraTimestampNs, cameraPoseEvidence = pose,
                fullDepthTimestampNs = key.cameraTimestampNs,
                cameraImageTimestampNs = key.cameraTimestampNs, rawConfidenceTimestampNs = key.cameraTimestampNs)
            assertSame(pose, depth.cameraPoseEvidence)
            assertSame(pose, MotionContext(cameraPoseEvidence = pose).reliableCameraPoseEvidence())
            val transform = requireNotNull(FrozenImageToDepthTransform.create(key.frameId,
                listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f)))
            val context = TactileProjectionContext(key.frameId, null, null, null, null, null,
                TactileCameraFrame(key.frameId, RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                    cameraIntrinsics, key.capturedAtElapsedRealtimeMs, true),
                40L, key.capturedAtElapsedRealtimeMs, key.capturedAtElapsedRealtimeMs + 40L)
            val evidence = DetectionFrameEvidence(key.frameId, at, depth,
                FrozenImageToTextureCoordinateMapper(key.frameId, transform, ImageSize(100, 100)), context,
                MotionContext(), false, false, visualFrameKey = key, geometryVersion = geometry)
            val detections = buildList {
                add(DetectionCandidate(UnknownObjectFeedbackPolicy.CLASS_NAME, .95f, RectNorm(.1f, .3f, .3f, .2f)))
                if (!onlyUnnamedA) {
                    add(DetectionCandidate("bollard", .95f, RectNorm(.6f, .3f, .3f, .2f)))
                    add(DetectionCandidate("damaged_tactile_block", .95f, RectNorm(.6f, .6f, .3f, .2f)))
                }
            }
            val facts = ReportFrameCaptureFacts(key.capturedAtElapsedRealtimeMs,
                TrustedLocation(37.0, 127.0, 8f, key.capturedAtElapsedRealtimeMs),
                "fixture-actor", 7L, WalkRuntimeEpoch("original-result", 0L))
            val snapshot = DetectionSnapshot(detections, AndroidDetectionSnapshotFrameIdentity(key.frameId, at),
                key.capturedAtElapsedRealtimeMs, key.capturedAtElapsedRealtimeMs, key.capturedAtElapsedRealtimeMs,
                100, 100, 0L, null, reportImage = byteArrayOf(1, 2, (at / 10).toByte()),
                partial = false, frameEvidence = evidence, capturedAtElapsedRealtimeMs = key.capturedAtElapsedRealtimeMs,
                reportCaptureFacts = facts)
            val frame = main.prepareTactileFrameDispatch(snapshot, key.capturedAtElapsedRealtimeMs + 40L,
                at, 800L, 800L, 40L, key.capturedAtElapsedRealtimeMs + 40L, coordinator) { capture, values ->
                pipeline.process(capture.depthSnapshot, capture.frameId, capture.frameTimestampMs, values,
                    detectionSequenceId = capture.frameId, detectionCompleted = true,
                    mapper = requireNotNull(capture.depthMapper), mapperFrameId = capture.depthMapper.frameId,
                    motionContext = capture.motionContext, imageQuarterTurns = capture.imageQuarterTurns,
                    capturedAtElapsedRealtimeMs = key.capturedAtElapsedRealtimeMs)
            }.frame
            assertTrue(frame.depthProcessingAttempted)
            assertSame(evidence, frame.matchedEvidence)
            assertEquals(if (onlyUnnamedA) 1 else 3, frame.guidance.outputs.size)
            return snapshot to frame
        }

        fun deviceGate(outputs: List<TrackedObjectDepth>, now: Long) = DeviceGateState(
            cameraPermissionGranted = true, arCoreSupported = true, depthSupported = true,
            tfliteConfigLoaded = true, detectorAvailable = true, arSessionRunning = true,
            freshDepthObject = outputs.any { output ->
                output.source.metric && output.riskDistanceM != null &&
                    output.sourceObservedAtElapsedRealtimeMs?.let { it <= now } == true &&
                    output.sourceValidUntilElapsedRealtimeMs?.let { now <= it } == true
            },
        )

        fun source(capture: Pair<DetectionSnapshot, TactileSnapshotFrameResult>) =
            MainActivity.TactileResultSource(
                TactileDotReferenceDeliveryOwner(WalkRuntimeEpoch("original-result", 0L), 0, 0, 1L),
                requireNotNull(capture.first.captureFrameId), requireNotNull(capture.first.capturedAtElapsedRealtimeMs),
                capture.first.frameEvidence!!.depthSnapshot.cameraImageTimestampNs,
                requireNotNull(capture.first.frameEvidence!!.geometryVersion), navigator.currentRouteId(), navigator.currentTactileRouteProgress())

        fun reportInput(output: TrackedObjectDepth,
            capture: Pair<DetectionSnapshot, TactileSnapshotFrameResult>,
            gate: DeviceGateState, requestAt: Long): AndroidReportCandidateInput {
            val facts = requireNotNull(capture.first.reportCaptureFacts)
            assertEquals(capture.first.capturedAtElapsedRealtimeMs, facts.capturedAtElapsedRealtimeMs)
            assertEquals("fixture-actor", facts.reporterActorId)
            assertEquals(7L, facts.accountGeneration)
            assertEquals(WalkRuntimeEpoch("original-result", 0L), facts.walkEpoch)
            val lookup = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            val built = lookup.findVirtual(MainActivity::class.java, "buildFrozenReportCandidateInput", MethodType.methodType(
                AndroidReportCandidateInput::class.java, TrackedObjectDepth::class.java, TrustedLocation::class.java,
                String::class.java, String::class.java, java.lang.Float.TYPE, java.lang.Long.TYPE, java.lang.Long.TYPE,
                java.lang.Long.TYPE, String::class.java, String::class.java, DeviceGateState::class.java,
                ByteArray::class.java, Pair::class.java)).bindTo(main).invokeWithArguments(
                output, requireNotNull(facts.trustedLocation),
                "custom_tactile", "android/custom_tactile", .35f, capture.first.capturedAtMs,
                requestAt, requestAt, "auto", facts.reporterActorId, gate, capture.first.reportImage, capture) as AndroidReportCandidateInput
            // Same second stage as prepareReportCandidate: use the captured facts, never a later fix.
            return built.copy(capturedAtElapsedRealtimeMs = facts.capturedAtElapsedRealtimeMs).also {
                assertSame(facts.trustedLocation, it.location)
            }
        }
    }

    private class Runtime(var now: Long) : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val id: String, val owner: FeedbackDeliveryOwner)
        val spoken = mutableListOf<Spoken>()
        val vibrations = mutableListOf<Pair<FeedbackDeliveryOwner, LongArray>>()
        var emittingOwner: FeedbackDeliveryOwner? = null
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        private val delayed = linkedMapOf<Runnable, Long>()
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized get() = initialized != null
        override fun elapsedRealtime() = now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(utteranceId, requireNotNull(emittingOwner))
            return TextToSpeech.SUCCESS
        }
        override fun vibrate(patternMs: LongArray): Boolean {
            vibrations += requireNotNull(emittingOwner) to patternMs.copyOf()
            return true
        }
        override fun cancelVibration() = Unit
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("risk emits no progress tone")
        override fun post(task: Runnable): Boolean { task.run(); return true }
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean { delayed[task] = delayMs; return true }
        override fun removeCallbacks(task: Runnable) { delayed.remove(task) }
        override fun removeCallbacksAndMessages(token: Any?) { delayed.clear() }
        fun ready() { requireNotNull(initialized).invoke(TextToSpeech.SUCCESS) }
        fun start(id: String) { requireNotNull(listener).onStart(id) }
        fun done(id: String) { requireNotNull(listener).onDone(id) }
    }

    companion object {
        private fun field(main: MainActivity, name: String): Any? =
            MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)
    }
}
