package kr.co.hanium.dreamup.walksafe

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.voice.output.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Private bounded probe: real Main/Guide/Nav/Output/Actuator and OutputGate; native TTS/audio-focus,
 * callbacks/time and UI/acoustic/storage leaves are controlled. Stable SDK-admitted owner and
 * usable capability/microphone-idle/no-risk are assumptions, not platform admission assertions.
 * Coordinator GPS and route transport use the existing cold fixture's declared boundaries.
 */
class DelayedNativeStartRecoveryProbeTest {
    @After fun retireGateway() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }

    @Test fun nativeFailureRecoveryCompletesFirstClockCue() = verifyNativeFailureRecovery(1, false)
    @Test fun twoNativeFailuresRecoverBeforeTheThirdFastStart() = verifyNativeFailureRecovery(2, false)
    @Test fun nativeFailureRecoveryRejectsOldDoneDuringNewReservation() = verifyNativeFailureRecovery(1, true)

    private fun verifyNativeFailureRecovery(failures: Int, lateWhileReserved: Boolean) {
        val f = Fixture()
        try {
            val initial = f.startWithNativeIntro()
            var action = initial
            val originalFixAt = f.fix.elapsedRealtimeMs
            val oldIds = mutableListOf<String>()
            repeat(failures) { index ->
                val oldToken: RouteSpeechCueToken = read(f.navigator, "pendingSpeechCue")
                assertTrue(requireNotNull(oldToken.facingObservedAtElapsedRealtimeMs) in (f.clock.now - 500)..f.clock.now)
                assertEquals(requireNotNull(oldToken.facingObservedAtElapsedRealtimeMs) + 500L, oldToken.facingValidUntilElapsedRealtimeMs)
                f.clock.advanceBy(300)
                f.face()
                f.clock.advanceBy(300)
                f.face() // Real new GOOD supports the same packet without changing its original lease.
                val stopsBefore = f.native.stops
                assertTrue(f.clock.now > requireNotNull(oldToken.facingValidUntilElapsedRealtimeMs))
                f.native.start(action.id)
                assertEquals("Fresh supporting native START must not stop", stopsBefore, f.native.stops)
                assertTrue(f.actor.isAppSpeechActive())
                assertEquals(oldToken, read<RouteSpeechCueToken>(f.navigator, "pendingSpeechCue"))
                assertFalse(f.navigator.isDirectionRecheckPending())
                f.native.error(action.id, TextToSpeech.ERROR_SYNTHESIS)
                // A terminal synthesis error ends this utterance; it does not request native stop.
                assertEquals(stopsBefore, f.native.stops)
                assertNull("Actual native failure releases reservation", read<Any?>(f.navigator, "pendingSpeechCue"))
                assertNull(read<Any?>(f.navigator, "completedSpeechCue"))
                assertFalse(f.actor.isAppSpeechActive())
                assertEquals(oldToken, read<RouteSpeechCueToken>(f.navigator, "failedSpeechCue"))
                assertEquals(f.clock.now + 2_000L, read<Long?>(f.navigator, "speechRetryNotBeforeMs"))
                assertTrue("Same real retry runnable must be queued", f.clock.contains(read<Runnable?>(f.guide, "navigationSpeechRetryRunnable")))
                oldIds += action.id
                f.trace += "NATIVE_ERROR_${index + 1}@${f.clock.now}:cue=${oldToken.cueRevision}"
                val before = f.native.spoken.size
                // This probe assumes stable GOOD facing during native synthesis recovery.
                // Supply new sensor observations within the real 500 ms lease instead of creating
                // an unrelated direction-loss episode while the two-second retry timer runs.
                repeat(7) {
                    f.clock.advanceBy(250)
                    f.face()
                }
                f.clock.advanceBy(249)
                f.face()
                assertEquals("No new native request before original two-second cooldown; " +
                    "now=${f.clock.now} due=${read<Long?>(f.navigator, "speechRetryNotBeforeMs")} " +
                    "pending=${read<Any?>(f.navigator, "pendingSpeechCue")} new=${f.native.spoken.drop(before)}",
                    before, f.native.spoken.size)
                f.clock.advanceBy(1)
                action = f.native.spoken.last()
                assertEquals(before + 1, f.native.spoken.size)
                val newToken: RouteSpeechCueToken = read(f.navigator, "pendingSpeechCue")
                assertNotEquals(oldToken.cueRevision, newToken.cueRevision)
                assertEquals(f.clock.now - 1, newToken.facingObservedAtElapsedRealtimeMs)
                assertTrue(action.message, Regex("(?:[1-9]|1[0-2])시.*[0-9]+(?:미터|m)").containsMatchIn(action.message))
                if (lateWhileReserved || index + 1 < failures) {
                    val pending: Any = read(f.navigator, "pendingSpeechCue")
                    f.native.done(oldIds.last())
                    assertSame("Old native DONE cannot consume the newer reserved cue", pending, read(f.navigator, "pendingSpeechCue"))
                    assertNull(read<Any?>(f.navigator, "completedSpeechCue"))
                }
                f.trace += "RETRY@${f.clock.now}:cue=${newToken.cueRevision}"
            }
            val acceptedToken: RouteSpeechCueToken = read(f.navigator, "pendingSpeechCue")
            f.clock.advanceBy(100)
            f.face()
            f.native.start(action.id)
            assertTrue(f.actor.isAppSpeechActive())
            assertEquals(acceptedToken, read<RouteSpeechCueToken>(f.navigator, "pendingSpeechCue"))
            assertNull(read<Any?>(f.navigator, "completedSpeechCue"))
            for (old in oldIds) f.native.done(old)
            assertNull("Late prior DONE is not current completion", read<Any?>(f.navigator, "completedSpeechCue"))
            f.clock.advanceBy(100)
            f.native.done(action.id)
            assertEquals(acceptedToken, read<RouteSpeechCueToken>(f.navigator, "completedSpeechCue"))
            assertNull(read<Any?>(f.navigator, "pendingSpeechCue"))
            assertNull(read<Any?>(f.navigator, "speechRetryNotBeforeMs"))
            assertNull(f.intro.pending)
            assertEquals(1, f.native.spoken.count { it.message.contains("길안내를 시작") })
            assertEquals(originalFixAt, f.fix.elapsedRealtimeMs)
            assertEquals(originalFixAt, requireNotNull(f.positions.rawRouteLocation).elapsedRealtimeMs)
            f.trace += "FAST_START_DONE@${f.clock.now}:cue=${acceptedToken.cueRevision}"
            println(f.trace.joinToString(" -> "))
        } finally { f.output.closeActuator() }
    }

    private class Fixture {
        private val cold = Class.forName(MainActivityColdFirstDirectionFlowIntegrationTest::class.java.name + "\$ColdFixture")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        init {
            invokeCold("acquire", arrayOf(Double::class.javaPrimitiveType!!), 5.0)
            invokeCold("requestAndInstall", arrayOf(Boolean::class.javaPrimitiveType!!), false)
        }
        val base: MainActivityContinuousLocationStartBehaviorTest.Fixture = read(cold, "base")
        val main = base.main
        val guide: RouteGuidanceController = read(cold, "guide")
        val positions: PositioningSessionController = read(cold, "positions")
        val fix: TrustedLocation = read(cold, "fix")
        val navigator = read<NavigationRunState>(guide, "navigationRunState").navigator
        val intro: InstalledRouteStartSpeechCoordinator = read(guide, "installedRouteStartSpeechCoordinator")
        val output: SpeechOutputController = read(main, "speechOutputController")
        val trace = mutableListOf<String>()
        val clock = Clock(base)
        lateinit var native: NativeRuntime
        val actor get() = requireNotNull(output.currentActuatorOrNull())
        init {
            val originalReader: SpeechOutputReader = read(output, "reader")
            put(output, "reader", object : SpeechOutputReader by originalReader {
                override fun screenReaderActive() = false // controlled SDK screen-reader observation
            })
            put(output, "scheduler", clock)
            put(output, "input", object : SpeechOutputInputEffects {
                override fun invalidateHomeWakeInput() = Unit
                override fun promptState(token: CommandResponseToken, effect: CommandPromptEffect) = Unit
                override fun cancelDeferredInput() = Unit
                override fun refreshHomeWakeListening() = Unit
                override fun cancelHomeWakeListening() = Unit
                override fun cancelCommandDialogs(token: CommandResponseToken) = Unit
            })
            put(output, "presentation", object : SpeechOutputPresentation {
                override fun showLastResult(message: String) = Unit
                override fun showVoiceStatus(message: String) = Unit
                override fun refreshVoiceControls() = Unit
                override fun navigationStatus(message: String) = Unit
                override fun commandTerminalDiagnostic(completed: Boolean) = Unit
                override fun accessibilityTargetOrNull(): SpeechAccessibilityTarget? = null
                override fun canScheduleInteraction() = true
                override fun postInteraction(task: Runnable, delayMs: Long) = clock.post(this, task, delayMs)
                override fun removeInteraction(task: Runnable) = clock.remove(this, task)
            })
            put(output, "storage", object : kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputStorageBinding {
                override fun invalidate() = Unit
                override fun refresh(actuator: AndroidFeedbackActuator) = Unit
            })
            put(output, "debugDiagnostic", { value: String -> trace += value })
            put(output, "readyEffects", object : SpeechOutputReadyEffects {
                override fun refreshReadyUi() = Unit
                override fun reevaluateCurrentRisk(actuator: AndroidFeedbackActuator, lifecycleGeneration: Int, nowMs: Long) = Unit
                override fun retryCurrentNavigation(nowMs: Long) = guide.retryCurrentNavigationSpeechAfterReady(nowMs)
                override fun offlineSpeechUnavailable(lifecycleGeneration: Int, actuator: AndroidFeedbackActuator) = error("Unexpected engine unavailability")
                override fun beforeCommandReplacement(previousGeneration: Long) = guide.failInterruptedRouteDecisionSpeech(previousGeneration)
            })
            put(output, "actuatorFactory", SpeechOutputActuatorFactory { unavailable, ready ->
                native = NativeRuntime(clock)
                // Use the real actor/gate/registry. Capability and native focus are admitted fixture leaves.
                AndroidFeedbackActuator(runtime = native, onOfflineKoreanSpeechUnavailable = unavailable,
                    onOfflineKoreanSpeechReady = ready)
            })
            val composition: NavigationSessionComposition = read(main, "navigationSessions")
            val bindings: GuidanceAssemblyBindings = read(composition, "guides")
            val productionSpeech = bindings.speech()
            put(guide, "speech", object : RouteGuidanceController.SpeechPort by productionSpeech {
                // Avoid OS accessibility queries only; registry/native activity and actual input admission remain.
                override fun busy() = productionSpeech.inputActive() || output.hasPendingCommandResponse() ||
                    output.currentActuatorOrNull()?.isAppSpeechActive() == true
                override fun outputBusy() = output.hasPendingCommandResponse() || output.currentActuatorOrNull()?.isAppSpeechActive() == true
            })
            put(guide, "callbacks", object : RouteCallbackScheduler {
                override fun post(task: Runnable) = clock.post(this, task, 0)
                override fun postDelayed(task: Runnable, delayMs: Long) = clock.post(this, task, delayMs)
                override fun remove(task: Runnable) = clock.remove(this, task)
            })
            // The notice owner captures the same native speech and callback leaves at construction.
            // Keep real notice/retry policy, and connect those leaves to this probe's controlled runtime.
            val notices: Any = read(guide, "recheckNotices")
            put(notices, "speech", read<RouteGuidanceController.SpeechPort>(guide, "speech"))
            put(notices, "callbacks", read<RouteCallbackScheduler>(guide, "callbacks"))
            put(notices, "elapsedRealtimeMs", { clock.now })
        }
        fun face() { invokeCold("faceAt", arrayOf(Long::class.javaPrimitiveType!!), clock.now) }
        fun startWithNativeIntro(): NativeRuntime.Spoken {
            invokeCold("installIntro", emptyArray())
            face()
            guide.updateRouteGuidance(fix, requireNotNull(positions.rawRouteLocation))
            assertTrue(native.spoken.isEmpty())
            assertTrue(output.hasPendingCommandResponse())
            native.ready()
            assertEquals(1, native.spoken.size)
            val introSpeech = native.spoken.single()
            assertTrue(introSpeech.message, introSpeech.message.contains("길안내를 시작"))
            clock.advanceBy(100); face(); native.start(introSpeech.id)
            assertNull(read<Any?>(navigator, "completedSpeechCue"))
            assertEquals(1, native.spoken.size)
            clock.advanceBy(100); face(); native.done(introSpeech.id)
            assertNull(intro.pending)
            assertEquals(2, native.spoken.size)
            assertNull(read<Any?>(navigator, "completedSpeechCue"))
            trace += "INTRO_NATIVE_START_DONE@${clock.now}"
            return native.spoken.last()
        }
        private fun invokeCold(name: String, types: Array<Class<*>>, vararg values: Any?) =
            cold.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(cold, *values)
    }

    private class Clock(private val base: MainActivityContinuousLocationStartBehaviorTest.Fixture) : SpeechOutputScheduler {
        private data class Task(val owner: Any, val due: Long, val order: Long, val task: Runnable)
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        val now get() = base.now
        override fun elapsedRealtimeMs() = now
        override fun isMainThread() = true
        override fun runOnMain(action: () -> Unit) = action()
        override fun postReadiness(task: Runnable, delayMs: Long) = post(this, task, delayMs)
        override fun removeReadiness(task: Runnable) = remove(this, task)
        override fun postRetired(action: () -> Unit) = post(this, Runnable(action), 0)
        fun post(owner: Any, task: Runnable, delayMs: Long) { tasks += Task(owner, now + delayMs, sequence++, task) }
        fun remove(owner: Any, task: Runnable) { tasks.removeAll { it.owner === owner && it.task === task } }
        fun clear(owner: Any) { tasks.removeAll { it.owner === owner } }
        fun contains(task: Runnable?) = task != null && tasks.any { it.task === task }
        fun advanceBy(delta: Long) {
            val target = now + delta
            var steps = 0
            while (true) {
                check(++steps < 1_000) { "Unexpected callback loop" }
                val next = tasks.filter { it.due <= target }.minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next); base.now = maxOf(now, next.due); next.task.run()
            }
            base.now = target
        }
    }
    private class NativeRuntime(private val clock: Clock) : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String, val atMs: Long)
        val spoken = mutableListOf<Spoken>()
        var stops = 0
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized get() = initialized != null
        override fun elapsedRealtime() = clock.now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int { spoken += Spoken(message, utteranceId, clock.now); return TextToSpeech.SUCCESS }
        override fun stop() { stops++ }
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No tone expected")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean { clock.post(this, task, delayMs); return true }
        override fun removeCallbacks(task: Runnable) = clock.remove(this, task)
        override fun removeCallbacksAndMessages(token: Any?) = clock.clear(this)
        fun ready() { requireNotNull(initialized)(TextToSpeech.SUCCESS); clock.advanceBy(0) }
        fun start(id: String) { requireNotNull(listener).onStart(id); clock.advanceBy(0) }
        fun error(id: String, code: Int) { requireNotNull(listener).onError(id, code); clock.advanceBy(0) }
        fun done(id: String) { requireNotNull(listener).onDone(id); clock.advanceBy(0) }
    }
    companion object {
        @Suppress("UNCHECKED_CAST") private fun <T> read(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
        private fun put(owner: Any, name: String, value: Any?) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
