package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.*
import org.junit.Test

/** Production capture/selection/Main consumers/actuator; only captures and platform effects are fake. */
class TactileObservationSpeechIntegrationTest {
    @Test fun currentReferencesReachNativeStartAndActualDonePreservesTheirInterval() {
        for (label in LABELS) {
            val f = Fixture(label)
            val frame = f.stableFrames()
            assertEquals(LocalRouteMode.TMAP, frame.guidance.decision.mode)
            assertTrue(f.publishAndDispatch(frame))
            val first = f.runtime.spoken.single()
            assertEquals(f.referenceMessage(frame), first.message)
            assertFalse(first.message.contains("미터"))
            assertFalse(first.message.contains("몇 보"))
            f.runtime.start(first.id)
            assertEquals(listOf(first.message), f.runtime.starts.map { it.message })
            f.runtime.advanceBy(20L)
            f.runtime.done(first.id)
            assertEquals(0, f.runtime.stops)
            assertFalse(f.actuator.isAppSpeechActive())
            val route = f.routeState()
            val next = f.frame(1_900L)
            assertNotNull(f.referenceMessage(next))
            assertFalse("A recently completed reference remains suppressed", f.publishAndDispatch(next))
            assertEquals(1, f.runtime.spoken.size)
            assertEquals(route, f.routeState())
        }
    }

    @Test fun missingCurrentObservationCancelsTheHeldNativeStartWithoutCompletingIt() {
        for (label in LABELS) {
            val f = Fixture(label)
            assertTrue(f.publishAndDispatch(f.stableFrames()))
            val old = f.runtime.spoken.single()
            val route = f.routeState()
            val empty = f.frame(1_850L, hasDetection = false)
            assertNull(f.referenceMessage(empty))
            f.publish(empty)
            f.runtime.start(old.id)
            assertEquals(1, f.runtime.stops)
            assertEquals(RecordingRuntime.Cancellation(f.runtime.now, old.message, old.id), f.runtime.cancellations.single())
            assertFalse(f.actuator.isAppSpeechActive())
            f.runtime.done(old.id)
            assertEquals(route, f.routeState())
        }
    }

    @Test fun anExpiredReferenceRemainsCancelledWhenALateNativeStartArrives() {
        for (label in LABELS) {
            val f = Fixture(label)
            val frame = f.stableFrames()
            assertTrue(f.publishAndDispatch(frame))
            val old = f.runtime.spoken.single()
            val expiry = f.referenceExpiry(frame)
            f.runtime.advanceBy(expiry + 1L - f.runtime.now)
            f.runtime.start(old.id)
            assertEquals(1, f.runtime.stops)
            val cancelled = f.runtime.cancellations.single()
            assertEquals(old.id, cancelled.id)
            assertEquals(old.message, cancelled.message)
            assertTrue(cancelled.atMs <= f.runtime.now)
            assertFalse(f.actuator.isAppSpeechActive())
            assertEquals(1, f.runtime.spoken.size)
        }
    }

    @Test fun aRouteReplacementCancelsTheOldCrosswalkBeforeItCanComplete() {
        val f = Fixture("crosswalk")
        assertTrue(f.publishAndDispatch(f.stableFrames()))
        val old = f.runtime.spoken.single()
        f.navigator.setRoute(route("replacement"))
        f.runtime.start(old.id)
        assertEquals(1, f.runtime.stops)
        assertEquals(RecordingRuntime.Cancellation(f.runtime.now, old.message, old.id), f.runtime.cancellations.single())
        assertFalse(f.actuator.isAppSpeechActive())
        f.runtime.done(old.id)
        assertEquals("replacement", f.navigator.currentRouteId())
    }

    @Test fun ordinaryTmapPreemptsAHeldReferenceAndRepeatedFallbackCannotReplayItsCue() {
        for (label in LABELS) {
            val f = Fixture(label)
            assertTrue(f.publishAndDispatch(f.stableFrames()))
            val old = f.runtime.spoken.single()
            val cue = requireNotNull(f.tmapUpdate)
            val route = f.routeState()
            assertTrue(f.submitTmap(cue))
            val navigation = f.runtime.spoken.last()
            assertEquals(cue.instruction, navigation.message)
            assertEquals(1, f.runtime.stops)
            f.runtime.start(navigation.id)
            val cancelled = f.runtime.cancellations.single()
            assertEquals(old.id, cancelled.id)
            assertEquals(old.message, cancelled.message)
            assertTrue(cancelled.atMs <= f.runtime.starts.last().atMs)
            assertTrue(f.runtime.effects.indexOfFirst { it.kind == "cancel" && it.id == old.id } <
                f.runtime.effects.indexOfFirst { it.kind == "start" && it.id == navigation.id })
            f.runtime.done(old.id)
            assertEquals(0, f.actionCompleted)
            assertTrue(f.actuator.isAppSpeechActive())
            f.runtime.done(navigation.id)
            assertEquals(1, f.actionCompleted)
            f.finishSubmittedInformation()
            for (atMs in listOf(1_900L, 2_000L, 2_100L)) {
                val fallback = f.frame(atMs, hasDetection = false)
                assertEquals(LocalRouteMode.TMAP, fallback.guidance.decision.mode)
                assertNull(fallback.guidance.decision.instruction)
                f.publish(fallback)
                assertEquals(route, f.routeState())
                assertFalse(f.navigator.reserveInstruction(cue))
            }
            assertEquals(1, f.runtime.starts.count { it.message == cue.instruction })
            assertEquals(1, f.actionCompleted)
            assertEquals(0, f.actionFailed)
        }
    }

    @Test fun unusableObservationCancelsTheOldReferenceBeforeTheOriginalTmapCueStarts() {
        for (label in LABELS) {
            val f = Fixture(label)
            assertTrue(f.publishAndDispatch(f.stableFrames()))
            val old = f.runtime.spoken.single()
            val cue = requireNotNull(f.tmapUpdate)
            val route = f.routeState()
            val unavailable = f.frame(1_850L, hasDetection = false)
            assertEquals(LocalRouteMode.TMAP, unavailable.guidance.decision.mode)
            assertNull(f.referenceMessage(unavailable))
            f.publish(unavailable)
            f.runtime.start(old.id)
            val cancellation = f.runtime.cancellations.single()
            assertEquals(old.id, cancellation.id)
            assertEquals(old.message, cancellation.message)
            assertFalse(f.actuator.isAppSpeechActive())
            assertEquals(route, f.routeState())

            assertTrue(f.submitTmap(cue))
            val ordinary = f.runtime.spoken.last()
            assertEquals(cue.instruction, ordinary.message)
            f.runtime.start(ordinary.id)
            assertTrue(cancellation.atMs <= f.runtime.starts.last().atMs)
            assertTrue(f.runtime.effects.indexOfFirst { it.kind == "cancel" && it.id == old.id } <
                f.runtime.effects.indexOfFirst { it.kind == "start" && it.id == ordinary.id })
            f.runtime.done(ordinary.id)
            f.finishSubmittedInformation()
            assertEquals(1, f.actionCompleted)
            assertEquals(0, f.actionFailed)
            assertEquals(1, f.runtime.starts.count { it.message == cue.instruction })
            assertFalse(f.navigator.reserveInstruction(cue))
            assertEquals(route, f.routeState())
        }
    }

    private class Fixture(private val label: String) {
        val runtime = RecordingRuntime()
        val actuator = AndroidFeedbackActuator(runtime = runtime).also { runtime.ready() }
        val navigator = RouteNavigator(nowElapsedRealtimeMs = { runtime.now }).apply { setRoute(route("route")) }
        private val main = MainActivity()
        private val epoch = WalkRuntimeEpoch("observation-speech", 0L)
        private val crosswalkOwner = CrosswalkReferenceDeliveryOwner(epoch, 1, 2, 3L)
        private val dotOwner = TactileDotReferenceDeliveryOwner(epoch, 1, 2, 3L)
        private val pipeline = ObjectDepthRuntimePipeline()
        private val coordinator = AndroidTactileFrameCoordinator(
            AndroidTactileRouteGuidance(), WalkSafeFeedbackPolicy(), TactileFrameFeedbackActuator {},
        )
        private var location = TrustedLocation(37.0, 127.0, 2f, runtime.now)
        var tmapUpdate: RouteNavigatorUpdate? = null
        var actionCompleted = 0
        var actionFailed = 0

        fun stableFrames(): TactileSnapshotFrameResult {
            var last: TactileSnapshotFrameResult? = null
            for (atMs in listOf(1_000L, 1_400L, 1_800L)) {
                runtime.advanceBy(atMs + 50_040L - runtime.now)
                location = TrustedLocation(37.0, 127.0, 2f, atMs + 50_000L)
                navigator.update(location, runtime.now, false, facingObservation = facing()).let {
                    if (it.instruction != null) tmapUpdate = it
                }
                last = frame(atMs)
            }
            return requireNotNull(last).also { assertNotNull(referenceMessage(it)) }
        }

        fun frame(atMs: Long, hasDetection: Boolean = true): TactileSnapshotFrameResult {
            val source = key(atMs)
            val target = key(atMs + 40L)
            runtime.advanceBy(target.capturedAtElapsedRealtimeMs - runtime.now)
            val detection = DetectionCandidate(label, .9f, RectNorm(.4f, .4f, .2f, .4f))
            val detections = listOf(detection).takeIf { hasDetection }.orEmpty()
            val snapshot = DetectionSnapshot(detections,
                AndroidDetectionSnapshotFrameIdentity(source.frameId, atMs),
                source.capturedAtElapsedRealtimeMs, source.capturedAtElapsedRealtimeMs,
                source.capturedAtElapsedRealtimeMs, 100, 100, 0L, null, partial = false,
                capturedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs, frameEvidence = evidence(source))
            val rows = detections.mapIndexed { index, candidate -> VisualTrackingObservation(
                index, source, target, VisualTrackingStatus.TRACKED, candidate, .95f, null,
                Point2(0f, 0f), 8, 8, 0f, false,
            ) }
            val result = VisualTrackingResult(source, target, rows, VisualTrackingMetrics(0L, rows.size, 0L, false, 2, 0))
            val admitted = requireNotNull(CurrentTrackedFrameEvidence.fromTrackingResult(snapshot, result, evidence(target), runtime.now))
            return coordinator.processTrackedObservation(admitted, runtime.now, true, true,
                navigator.currentRouteId(), navigator.currentTactileRouteProgress()) { observation, capture ->
                pipeline.processTrackedObservation(capture.depthSnapshot, observation, requireNotNull(capture.depthMapper),
                    capture.frameId, runtime.now)
            }
        }

        fun referenceMessage(frame: TactileSnapshotFrameResult): String? =
            if (label == "crosswalk") frame.guidance.crosswalkReferenceNotice?.message else frame.guidance.dotReferenceNotice?.message

        fun referenceExpiry(frame: TactileSnapshotFrameResult): Long =
            if (label == "crosswalk") requireNotNull(frame.guidance.crosswalkReferenceNotice).validUntilElapsedRealtimeMs
            else requireNotNull(frame.guidance.dotReferenceNotice).validUntilElapsedRealtimeMs

        fun publish(frame: TactileSnapshotFrameResult) {
            main.updateCrosswalkReferenceObservation(frame.guidance.crosswalkReferenceNotice, crosswalkOwner, runtime.now)
            main.updateTactileDotReferenceObservation(frame.guidance.dotReferenceNotice, dotOwner, runtime.now)
        }

        fun publishAndDispatch(frame: TactileSnapshotFrameResult): Boolean {
            publish(frame)
            return if (label == "crosswalk") main.dispatchCrosswalkReference(crosswalkOwner, { runtime.now }, { true }, {
                it.isValidForRoute(navigator.currentRouteId(), navigator.currentTactileRouteProgress())
            }) { notice, current, completed, failed ->
                actuator.speakAdvisory(notice.message, notice.validUntilElapsedRealtimeMs, current, completed, failed)
            } else main.dispatchTactileDotReference(dotOwner, { runtime.now }, { true }) { notice, current, completed, failed ->
                actuator.speakAdvisory(notice.message, notice.validUntilElapsedRealtimeMs, current, completed, failed)
            }
        }

        fun routeState() = listOf(navigator.currentRouteId(), navigator.currentTactileRouteProgress(),
            navigator.currentRouteMatch()?.geometricProgressM, navigator.pendingDecisionToken())

        fun submitTmap(update: RouteNavigatorUpdate): Boolean {
            assertTrue(navigator.reserveInstruction(update))
            return dispatchRouteInstructionSpeech(update,
                isActionCurrent = { navigator.isInstructionCurrent(update, runtime.now, facing()) },
                isInformationCurrent = { navigator.isInformationCurrent(it, runtime.now) },
                reserveInformation = { navigator.reserveInformation(it, runtime.now) },
                finishInformation = { navigator.finishInformation(it) },
                onActionCompleted = { actionCompleted++; navigator.acknowledgeInstruction(update, runtime.now) },
                onActionFailed = { actionFailed++; navigator.releaseInstruction(update, runtime.now) },
                speak = { message, current, completed, failed -> actuator.speakNavigation(message,
                    onCompleted = completed, onFailed = failed, requiresExplicitTerminalCallback = true,
                    onCancelled = failed, isStillValidAtStart = current) == NavigationSpeechDispatchResult.ACCEPTED },
            )
        }

        fun finishSubmittedInformation() {
            val information = runtime.spoken.last()
            if (actuator.isAppSpeechActive()) { runtime.start(information.id); runtime.done(information.id) }
        }

        private fun facing() = RouteFacingObservation(0.0, 5.0, runtime.now)
        private fun key(atMs: Long) = VisualFrameKey(7L, atMs * 1_000_000L,
            atMs * 1_000_000L + 400_000L, atMs + 50_000L, 3L)

        private fun evidence(key: VisualFrameKey): DetectionFrameEvidence {
            val values = IntArray(10_000) { pixel ->
                if (label == "crosswalk" && pixel % 100 in 35..65 && pixel / 100 in 35..85) 0 else 2_000
            }
            val depth = DepthFrameSnapshot(key.frameId, DepthImage16(100, 100, values),
                ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }), DepthImage16(100, 100, values.copyOf()),
                key.cameraTimestampNs, fullDepthTimestampNs = key.cameraTimestampNs,
                cameraImageTimestampNs = key.cameraTimestampNs, rawConfidenceTimestampNs = key.cameraTimestampNs)
            val transform = requireNotNull(FrozenImageToDepthTransform.create(key.frameId,
                listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f)))
            val context = TactileProjectionContext(key.frameId, navigator.currentRouteId(), navigator.currentProjection(location),
                location, null, null, TactileCameraFrame(key.frameId,
                    RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                    TactileCameraIntrinsics(100, 100, 70f, 70f, 50f, 50f), key.capturedAtElapsedRealtimeMs, true),
                40L, key.capturedAtElapsedRealtimeMs, key.capturedAtElapsedRealtimeMs)
            return DetectionFrameEvidence(key.frameId, key.frameId / 1_000_000L, depth,
                FrozenImageToTextureCoordinateMapper(key.frameId, transform, ImageSize(100, 100)),
                context, MotionContext(), true, true, visualFrameKey = key)
        }
    }

    private class RecordingRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val atMs: Long, val message: String, val id: String)
        data class Cancellation(val atMs: Long, val message: String, val id: String)
        data class NativeEffect(val atMs: Long, val kind: String, val message: String, val id: String)
        private data class Task(val due: Long, val order: Long, val runnable: Runnable)
        var now = 51_000L
        val spoken = mutableListOf<Spoken>()
        val starts = mutableListOf<Spoken>()
        val cancellations = mutableListOf<Cancellation>()
        val effects = mutableListOf<NativeEffect>()
        var stops = 0
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        private var nativeCurrent: Spoken? = null
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime() = now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener; return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            val submitted = Spoken(now, message, utteranceId)
            spoken += submitted
            nativeCurrent = submitted
            effects += NativeEffect(now, "submit", message, utteranceId)
            return TextToSpeech.SUCCESS
        }
        override fun stop() {
            stops++
            nativeCurrent?.let {
                cancellations += Cancellation(now, it.message, it.id)
                effects += NativeEffect(now, "cancel", it.message, it.id)
            }
            nativeCurrent = null
        }
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int) = object : ProgressToneEffects {
            override fun startTone(toneType: Int, durationMs: Int) = true
            override fun stopTone() = Unit
            override fun release() = Unit
        }
        override fun post(task: Runnable) = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs, sequence++, task); return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() { requireNotNull(initialized)(TextToSpeech.SUCCESS); advanceBy(0L) }
        fun start(id: String) {
            val started = spoken.single { it.id == id }.copy(atMs = now)
            starts += started
            effects += NativeEffect(now, "start", started.message, id)
            requireNotNull(listener).onStart(id); advanceBy(0L)
        }
        fun done(id: String) {
            if (nativeCurrent?.id == id) nativeCurrent = null
            requireNotNull(listener).onDone(id); advanceBy(0L)
        }
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = now + deltaMs
            while (true) {
                val next = tasks.filter { it.due <= target }.minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next); now = maxOf(now, next.due); next.runnable.run()
            }
            now = target
        }
    }

    companion object {
        private val LABELS = listOf("crosswalk", "dot_tactile_paving")
        private fun route(id: String) = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(222, 200),
            listOf(RoutePoint(37.0, 127.0), RoutePoint(37.001, 127.0), RoutePoint(37.001, 127.001)),
            listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0, 127.0), null, 0, 222, turnType = 200, pointType = "SP"),
                WalkingRouteGuidePoint(1, RoutePoint(37.001, 127.0), null, 111, 111, turnType = 12)), providerRouteId = id)
    }
}
