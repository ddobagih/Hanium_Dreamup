package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pauses real public API operations after admission, rather than modelling a second queue. */
class AndroidFeedbackActuatorConcurrentDispatchRegressionTest {
    @Test
    fun lowerRiskCannotFlushConcurrentStopAfterPriorityCheck() {
        assertStopRemainsOwner { actuator, cancelled ->
            actuator.emit(risk("warning", MessageLevel.WARNING), null, null, { true }, cancelled).speech
        }
    }

    @Test
    fun interactionCannotFlushConcurrentStopAfterDuplicateCheck() {
        assertStopRemainsOwner { actuator, cancelled ->
            actuator.speakInteraction("interaction", null, cancelled)
        }
    }

    @Test
    fun navigationCannotQueueBehindConcurrentStopAfterDuplicateCheck() {
        assertStopRemainsOwner { actuator, cancelled ->
            actuator.speakNavigation("navigation", onCancelled = cancelled, requiresExplicitTerminalCallback = true)
        }
    }

    @Test
    fun closeCannotShutdownWhileAnAdmittedNativeSpeakIsInFlight() {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime)
        runtime.ready()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        runtime.beforeSpeak = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val failure = AtomicReference<Throwable?>()
        val speaking = worker("speaking", failure) { actuator.speakNavigation("route") }
        val closing = worker("closing", failure) { actuator.close() }
        speaking.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            closing.start()
            awaitBlockedOrFinished(closing)
        } finally {
            release.countDown()
            join(speaking, closing)
        }
        failure.get()?.let { throw AssertionError("worker failed", it) }
        assertFalse("native speak ran after engine shutdown", runtime.spokeAfterShutdown)
        assertEquals("tts=closed", actuator.statusText())
        assertEquals(null, runtime.activeSpeechId)
        assertFalse(actuator.isAppSpeechActive())
        assertEquals(NavigationSpeechDispatchResult.UNAVAILABLE, actuator.speakNavigation("after close"))
    }

    @Test
    fun cancellationCallbackCanWaitForAnotherThreadsStopWithoutHoldingDispatchLocks() {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime)
        runtime.ready()
        val failure = AtomicReference<Throwable?>()
        var stopDispatch: NavigationSpeechDispatchResult? = null
        val stop = worker("callback-stop", failure) {
            stopDispatch = actuator.emit(risk("stop", MessageLevel.STOP), null, null, { true }).speech
        }
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actuator.speakNavigation(
            "route", requiresExplicitTerminalCallback = true, onCancelled = {
                stop.start()
                join(stop)
            },
        ))

        val warning = actuator.emit(risk("warning", MessageLevel.WARNING), null, null, { true }).speech

        join(stop)
        failure.get()?.let { throw AssertionError("worker failed", it) }
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, stopDispatch)
        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, warning)
        assertEquals("stop", runtime.spoken.single { it.id == runtime.activeSpeechId }.message)
        assertEquals(listOf("route", "stop"), runtime.spoken.map { it.message })
    }

    @Test
    fun vibrationFailureCallbackCanWaitForAnotherThreadsSpeechWithoutHoldingOutputGate() {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime, hapticAllowed = { true })
        runtime.ready()
        val failure = AtomicReference<Throwable?>()
        var navigation: NavigationSpeechDispatchResult? = null
        val speech = worker("haptic-callback-speech", failure) {
            navigation = actuator.speakNavigation("from haptic cancellation")
        }
        assertTrue(actuator.playPriorityUserTrainingVibration({}, {
            speech.start()
            join(speech)
        }))

        assertTrue(actuator.playVoiceListeningStartVibration())

        join(speech)
        failure.get()?.let { throw AssertionError("worker failed", it) }
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, navigation)
        assertEquals(2, runtime.vibrations)
    }

    @Test
    fun navigationRiskAndCloseCannotLeaveLateOrUnregisteredSpeech() {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime)
        runtime.ready()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        runtime.beforeSpeak = { id ->
            assertRegistered(actuator, id)
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val failure = AtomicReference<Throwable?>()
        val navigation = worker("navigation", failure) { actuator.speakNavigation("route") }
        val stop = worker("stop", failure) { actuator.emit(risk("stop", MessageLevel.STOP), null, null, { true }) }
        val close = worker("close", failure) { actuator.close() }
        navigation.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            stop.start()
            awaitBlockedOrFinished(stop)
            close.start()
            awaitBlockedOrFinished(close)
        } finally {
            release.countDown()
            join(navigation, stop, close)
        }
        failure.get()?.let { throw AssertionError("worker failed", it) }
        assertFalse(runtime.spokeAfterShutdown)
        assertEquals(null, runtime.activeSpeechId)
        assertEquals("tts=closed", actuator.statusText())
        assertFalse(actuator.isAppSpeechActive())
    }

    @Test
    fun externalRiskPreparationCannotStopOrReleaseTheRiskThatReplacedItsRetiredOwner() {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime)
        runtime.ready()
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actuator.speakNavigation("old route"))
        val retired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paused = AtomicBoolean()
        runtime.onRemoveCallbacks = {
            if (Thread.currentThread().name == "external-preparation" && paused.compareAndSet(false, true)) {
                retired.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val failure = AtomicReference<Throwable?>()
        val preparation = worker("external-preparation", failure) { actuator.prepareForExternalRiskAnnouncement() }
        preparation.start()
        var stopId: String? = null
        var stopFocus: AudioManager.OnAudioFocusChangeListener? = null
        try {
            assertTrue(retired.await(5, TimeUnit.SECONDS))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                actuator.emit(risk("stop", MessageLevel.STOP), null, null, { true }).speech)
            stopId = runtime.activeSpeechId
            stopFocus = runtime.latestFocusListener
        } finally {
            release.countDown()
            join(preparation)
        }
        failure.get()?.let { throw AssertionError("worker failed", it) }
        assertEquals("old preparation stopped replacement risk", stopId, runtime.activeSpeechId)
        assertRegistered(actuator, checkNotNull(stopId))
        assertEquals(setOf(stopFocus), runtime.grantedFocusListeners)
    }

    @Test
    fun focusLossCleanupCannotStopTheReplacementRiskThatWonTheEngineLock() {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime)
        runtime.ready()
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actuator.speakNavigation("old route"))
        checkNotNull(runtime.latestFocusListener).onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        val failure = AtomicReference<Throwable?>()
        val cleanup = worker("focus-loss-cleanup", failure) { runtime.runCurrent() }
        val field = AndroidFeedbackActuator::class.java.getDeclaredField("speechEngineOperationLock")
        field.isAccessible = true
        var stopId: String? = null
        synchronized(field.get(actuator)) {
            cleanup.start()
            awaitBlockedOrFinished(cleanup)
            assertTrue("cleanup did not wait at the native operation", cleanup.isAlive)
            // The real cleanup has read no replacement focus and is waiting to stop. Submit
            // STOP reentrantly as the mutex owner to choose the winner without timing guesses.
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                actuator.emit(risk("stop", MessageLevel.STOP), null, null, { true }).speech)
            stopId = runtime.activeSpeechId
        }
        join(cleanup)
        failure.get()?.let { throw AssertionError("worker failed", it) }
        assertEquals("stale focus loss stopped replacement risk", stopId, runtime.activeSpeechId)
        assertRegistered(actuator, checkNotNull(stopId))
        assertEquals(setOf(runtime.latestFocusListener), runtime.grantedFocusListeners)
    }

    @Test
    fun oldDoneCleanupCannotAbandonTheReplacementRisksNewFocus() {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime)
        runtime.ready()
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actuator.speakNavigation("old route"))
        val oldId = checkNotNull(runtime.activeSpeechId)
        val retired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paused = AtomicBoolean()
        runtime.onRemoveCallbacks = {
            if (Thread.currentThread().name == "old-done" && paused.compareAndSet(false, true)) {
                retired.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val failure = AtomicReference<Throwable?>()
        val done = worker("old-done", failure) { runtime.done(oldId) }
        done.start()
        var stopId: String? = null
        var stopFocus: AudioManager.OnAudioFocusChangeListener? = null
        try {
            assertTrue(retired.await(5, TimeUnit.SECONDS))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                actuator.emit(risk("stop", MessageLevel.STOP), null, null, { true }).speech)
            stopId = runtime.activeSpeechId
            stopFocus = runtime.latestFocusListener
        } finally {
            release.countDown()
            join(done)
        }
        failure.get()?.let { throw AssertionError("worker failed", it) }
        assertEquals(stopId, runtime.activeSpeechId)
        assertRegistered(actuator, checkNotNull(stopId))
        assertEquals("old DONE released replacement risk focus", setOf(stopFocus), runtime.grantedFocusListeners)
    }

    private fun assertStopRemainsOwner(
        dispatchLower: (AndroidFeedbackActuator, () -> Unit) -> NavigationSpeechDispatchResult,
    ) {
        val runtime = FakeRuntime()
        val actuator = AndroidFeedbackActuator(runtime)
        runtime.ready()
        runtime.beforeSpeak = { id -> assertRegistered(actuator, id) }
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paused = AtomicBoolean()
        runtime.onClock = {
            if (Thread.currentThread().name == "lower" &&
                Thread.currentThread().stackTrace.any { it.methodName == "isDuplicateMessage" } &&
                paused.compareAndSet(false, true)
            ) {
                admitted.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val failure = AtomicReference<Throwable?>()
        var lowerCancelled = 0
        var stopCancelled = 0
        var stopDispatch: NavigationSpeechDispatchResult? = null
        val lower = worker("lower", failure) { dispatchLower(actuator) { lowerCancelled += 1 } }
        val stop = worker("stop", failure) {
            stopDispatch = actuator.emit(
                risk("stop", MessageLevel.STOP), null, null, { true }, { stopCancelled += 1 },
            ).speech
        }
        lower.start()
        try {
            assertTrue("lower request never reached the duplicate clock", admitted.await(5, TimeUnit.SECONDS))
            stop.start()
            // Before the fix STOP completes here. With serialization it waits for lower's
            // admitted operation, then preempts it. Neither schedule may end with lower speech.
            awaitBlockedOrFinished(stop)
        } finally {
            release.countDown()
            join(lower, stop)
        }
        failure.get()?.let { throw AssertionError("worker failed", it) }
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, stopDispatch)
        assertEquals("stop", runtime.spoken.single { it.id == runtime.activeSpeechId }.message)
        assertEquals("STOP must retain terminal ownership", 0, stopCancelled)
        assertTrue(lowerCancelled <= 1)
        assertTrue(actuator.isAppSpeechActive())
    }

    private fun awaitBlockedOrFinished(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.isAlive && thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(1)
        }
        assertTrue("request neither completed nor waited for ownership", !thread.isAlive || thread.state == Thread.State.BLOCKED)
    }

    private fun worker(name: String, failure: AtomicReference<Throwable?>, action: () -> Unit): Thread =
        Thread({ try { action() } catch (error: Throwable) { failure.compareAndSet(null, error) } }, name)
            .apply { isDaemon = true }

    private fun join(vararg threads: Thread) {
        threads.filter { it.state != Thread.State.NEW }.forEach { it.join(5_000L) }
        threads.forEach { assertFalse("deadlocked worker ${it.name}", it.isAlive) }
    }

    private fun risk(message: String, level: MessageLevel) = FeedbackAction(
        trackId = message,
        message = message,
        level = level,
        vibrationPatternMs = null,
        reason = "concurrent-regression",
        validUntilMs = 20_000L,
    )

    private fun assertRegistered(actuator: AndroidFeedbackActuator, utteranceId: String) {
        val field = AndroidFeedbackActuator::class.java.getDeclaredField("pendingUtterances")
        field.isAccessible = true
        val pending = field.get(actuator) as Set<*>
        synchronized(pending) { assertTrue("unregistered native utterance", utteranceId in pending) }
    }

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        private var initialized: ((Int) -> Unit)? = null
        private val tasks = mutableListOf<Runnable>()
        private lateinit var listener: FeedbackSpeechRuntime.Listener
        val spoken = Collections.synchronizedList(mutableListOf<Spoken>())
        val grantedFocusListeners = Collections.synchronizedSet(mutableSetOf<AudioManager.OnAudioFocusChangeListener>())
        var latestFocusListener: AudioManager.OnAudioFocusChangeListener? = null
        @Volatile var activeSpeechId: String? = null
        @Volatile var shutdown = false
        @Volatile var spokeAfterShutdown = false
        var onClock: (() -> Unit)? = null
        var beforeSpeak: ((String) -> Unit)? = null
        var vibrations = 0
        var onRemoveCallbacks: (() -> Unit)? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long { onClock?.invoke(); return 1_000L }
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            beforeSpeak?.invoke(utteranceId)
            if (shutdown) spokeAfterShutdown = true
            spoken += Spoken(message, utteranceId)
            if (queueMode == TextToSpeech.QUEUE_FLUSH || activeSpeechId == null) activeSpeechId = utteranceId
            return TextToSpeech.SUCCESS
        }
        override fun stop() { activeSpeechId = null }
        override fun shutdown() { shutdown = true }
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean {
            latestFocusListener = listener
            grantedFocusListeners += listener
            return true
        }
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) { grantedFocusListeners -= listener }
        override fun vibrate(patternMs: LongArray): Boolean { vibrations += 1; return true }
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("unused")
        override fun post(task: Runnable): Boolean = synchronized(tasks) { tasks.add(task); true }
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean = true
        override fun removeCallbacks(task: Runnable) {
            synchronized(tasks) { tasks.remove(task) }
            onRemoveCallbacks?.invoke()
        }
        override fun removeCallbacksAndMessages(token: Any?) { synchronized(tasks) { tasks.clear() } }
        fun ready() {
            checkNotNull(initialized).invoke(TextToSpeech.SUCCESS)
            runCurrent()
        }
        fun done(id: String) { listener.onDone(id); runCurrent() }
        fun runCurrent() {
            while (true) {
                val task = synchronized(tasks) { if (tasks.isEmpty()) null else tasks.removeAt(0) } ?: return
                task.run()
            }
        }
    }
}
