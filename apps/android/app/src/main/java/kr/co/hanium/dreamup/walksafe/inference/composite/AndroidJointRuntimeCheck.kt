package kr.co.hanium.dreamup.walksafe.inference.composite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletionStage
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeContext
import java.util.concurrent.atomic.AtomicBoolean

data class JointRuntimeCheckResult(val selection: JointRuntimeCheckedSelection?, val reason: String,
    val releaseConfirmed: Boolean) {
    @Deprecated("A provisional selection is not a validated choice; run the final strict/soak stage")
    val choice: ValidatedJointRuntimeChoice? get() = null
}

/** The terminal decision cannot be overwritten by late IPC/native work or a blocked UI. */
internal class JointRuntimeCompletionGate(initiallyReleased: Boolean = false) {
    private var terminalRelease: Boolean? = null
    private var actualReleased = initiallyReleased
    private var lateAcknowledged = false
    private var resourceToken: Any? = null
    @Synchronized fun acquireResource(token: Any): Boolean {
        if (terminalRelease != null) return false
        resourceToken = token
        actualReleased = false
        return true
    }
    @Synchronized fun claim(releaseConfirmed: Boolean): Boolean? {
        if (terminalRelease != null) return null
        actualReleased = actualReleased || releaseConfirmed
        terminalRelease = actualReleased
        return actualReleased
    }
    /** A late physical-release fact has its own one-shot acknowledgement, never another result. */
    @Synchronized fun acknowledgeRelease(token: Any): Boolean {
        if (resourceToken !== token) return false
        actualReleased = true
        return if (terminalRelease == false && !lateAcknowledged) { lateAcknowledged = true; true } else false
    }
    val releaseConfirmed: Boolean get() = synchronized(this) { actualReleased }
}

/** Sample images only; native models use the same production proxy/child path as live consumers. */
class AndroidJointRuntimeCheck(
    context: Context,
    private val config: TwoModelRuntimeConfig,
    @Suppress("UNUSED_PARAMETER") baselinePrimary: RuntimeCandidate,
    private val deadlineMs: Long,
    private val isCurrent: () -> Boolean,
    @Suppress("UNUSED_PARAMETER") speechActive: () -> Boolean = { false },
    private val onFinished: (JointRuntimeCheckResult) -> Unit,
    private val onProgress: (String) -> Unit = {},
    onReleaseConfirmed: () -> Unit = {},
) {
    // Capture before applicationContext normalization or asynchronous work. No newest-source lookup.
    private val capturedOwner = VisionRuntimeContext.ownerRef(context)
    private val capturedSource = capturedOwner.source
    private val capturedSession = capturedOwner.sessionGeneration
    private val diagnostic = VisionRuntimeContext.diagnosticAuthority(context)
    private val app = context.applicationContext
    private val authority = diagnostic ?: AndroidDeviceCheckAuthority.forApp(app)
    private val main = Handler(Looper.getMainLooper())
    private val owner = Any()
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val completion = JointRuntimeCompletionGate(initiallyReleased = true)
    private val state = JointRuntimeCheckRunningState()
    private val releasePublisher = JointRuntimeCheckReleasePublisher(completion, owner, main, state, onReleaseConfirmed)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "WalkSafeSampleCheck") }
    private val watchdog = ScheduledThreadPoolExecutor(1) { Thread(it, "WalkSafeSampleCheckDeadline") }.apply {
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false); removeOnCancelPolicy = true
    }
    @Volatile private var watch: ScheduledFuture<*>? = null
    @Volatile private var sequence: JointRuntimeCheckSequence<JointRuntimeSampleFixtures, JointRuntimeReferenceSet>? = null
    @Volatile private var reason = "sample_check_unverified"
    val running: Boolean get() = state.running
    val draining: Boolean get() = state.draining

    fun start(): Boolean {
        if (deadlineMs - now() !in 1L..JointRuntimeSearchPolicy.TOTAL_MAXIMUM_MS || !sourceCurrent() || cancelled.get() ||
            !started.compareAndSet(false, true)) {
            if (!started.get()) closeExecutors()
            return false
        }
        state.running = true
        val launch = FixtureRuntimeStartup.launch(owner, schedule = {
            watch = watchdog.schedule({
                cancelled.set(true)
                sequence?.cancel("sample_total_deadline")
                report(null, "sample_total_deadline", completion.releaseConfirmed)
            }, (deadlineMs - now()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        }, execute = { worker.execute(::run) }, abort = ::closeExecutors)
        if (!launch.admitted) { state.running = false; closeExecutors(); return false }
        if (launch.failure != null) {
            cancelled.set(true)
            report(null, "sample_check_launch_failed", true)
        }
        return true
    }

    fun cancel(reason: String) {
        this.reason = reason.take(160)
        cancelled.set(true)
        if (!started.get()) { closeExecutors(); state.running = false; state.draining = false; return }
        state.draining = state.running
        sequence?.cancel(reason)
    }

    private fun run() {
        try {
            val binding = requireNotNull(AndroidJointRuntimeChoiceStore.createBinding(app, config)) { "sample_binding_unavailable" }
            check(binding == capturedSource.binding.jointBindingHash) { "sample_source_binding_changed" }
            val publisher = releasePublisher
            val ports = object : JointRuntimeCheckSequencePorts<JointRuntimeSampleFixtures, JointRuntimeReferenceSet> {
                override fun sourceCurrent() = current()
                override fun loadFixtures() = JointRuntimeSampleFixtures.load(app)
                override fun createTrial(fixtures: JointRuntimeSampleFixtures, phase: JointRuntimeSearchPhase): JointRuntimeCheckSequenceTrial<JointRuntimeReferenceSet> {
                    check(current()) { "sample_check_source_retired" }
                    val trialOwner = requireNotNull(authority.registerOwner("sample-" + java.util.UUID.randomUUID(),
                        capturedSource, capturedSession)) { "sample_owner_registration_rejected" }
                    val scoped = if (diagnostic == null) VisionRuntimeContext(app, trialOwner)
                        else VisionRuntimeContext(app, trialOwner, authority)
                    val host = JointRuntimeSampleHost.create(scoped, config, authority, fixtures,
                        epochs.incrementAndGet(), FastSamRuntimeService.Listener<Unit> { })
                    return object : JointRuntimeCheckSequenceTrial<JointRuntimeReferenceSet> {
                        override val actualReleased get() = host.actualReleasedFuture
                        override fun provisional(candidate: JointRuntimeCandidate, budget: JointRuntimePhaseBudget) =
                            host.measureProvisional(candidate, budget, ::current)
                        override fun references(budget: JointRuntimePhaseBudget) = JointRuntimeReferenceSet.capture(host, budget, ::current)
                        override fun cancel(reason: String) = host.cancel(reason)
                        override fun close() = host.finish(false)
                    }
                }
                override fun observeRelease(token: Any, released: CompletionStage<Unit>) = publisher.observe(token, released)
                override fun releaseObserved(token: Any) = publisher.acknowledge(token)
                override fun progress(phase: JointRuntimeSearchPhase, candidate: JointRuntimeCandidate?) {
                    Log.i(TAG, "phase=$phase candidate=$candidate")
                    emit(if (phase == JointRuntimeSearchPhase.REFERENCE) "인식 결과를 비교할 기준 자료를 확인하고 있습니다."
                        else "내장 시험 자료로 두 인식 모델의 실행 조합을 점검하고 있습니다.")
                }
            }
            val check = JointRuntimeCheckSequence(JointRuntimeSearchPolicy(now(), deadlineMs,
                Runtime.getRuntime().availableProcessors()), deadlineMs, completion, SystemClock::elapsedRealtime, ports)
            sequence = check
            if (cancelled.get()) check.cancel(reason)
            val result = check.run()
            val selected = if (result.plan != null && result.releaseConfirmed && current()) JointRuntimeCheckedSelection(
                capturedSource, capturedSession, binding, result.plan, requireNotNull(result.references), requireNotNull(result.fixtures))
                else { try { result.references?.close() } finally { result.fixtures?.close() }; null }
            report(selected, if (cancelled.get()) reason else result.reason, result.releaseConfirmed)
        } catch (error: Throwable) {
            report(null, (error.message ?: "sample_check_failed").take(160), completion.releaseConfirmed)
        } finally { sequence = null; worker.shutdown() }
    }

    private fun report(selection: JointRuntimeCheckedSelection?, resultReason: String, released: Boolean): Boolean {
        val confirmed = completion.claim(released)
        if (confirmed == null) { selection?.discardIfUnclaimed(); return false }
        state.running = !confirmed; state.draining = !confirmed
        watch?.cancel(false); watchdog.shutdown()
        val accepted = selection.takeIf { confirmed && current() }
        if (accepted == null) selection?.discardIfUnclaimed()
        if (confirmed) FixtureRuntimeSupervisor.release(owner, true)
        Log.i(TAG, "finished=$resultReason releaseConfirmed=$confirmed candidate=${accepted?.plan?.candidate} validated=false")
        main.post {
            val delivered = accepted.takeIf { !cancelled.get() && sourceCurrent() && now() < deadlineMs }
            if (delivered == null) accepted?.discardIfUnclaimed()
            onFinished(JointRuntimeCheckResult(delivered, resultReason, confirmed))
        }
        return true
    }
    private fun closeExecutors() { watch?.cancel(false); watchdog.shutdown(); worker.shutdown() }
    private fun emit(value: String) = main.post { if (current()) onProgress(value) }
    private fun sourceCurrent(): Boolean = runCatching {
        if (!isCurrent() || capturedOwner.source !== capturedSource || authority.ownerRef(capturedOwner.ownerId) !== capturedOwner) return@runCatching false
        if (capturedSource is DeviceCheckAttemptRef && !authority.checkAttempt(capturedSource, now())) return@runCatching false
        val snapshot = authority.cachedSnapshot(capturedSource.binding)
        if (snapshot is DeviceCheckAuthorityRead.Blocked) {
            reason = "sample_authority_blocked:" + snapshot.reason
            false
        } else true
    }.getOrDefault(false)
    private fun current() = !cancelled.get() && now() < deadlineMs && FixtureRuntimeSupervisor.owns(owner) && sourceCurrent()
    private fun now() = SystemClock.elapsedRealtime()
    private companion object {
        const val TAG = "WalkSafeJointRuntime"
        val epochs = java.util.concurrent.atomic.AtomicLong()
    }
}

private class JointRuntimeCheckRunningState {
    @Volatile var running = false
    @Volatile var draining = false
}

/** A global physical future retains only metadata and a caller-supplied weak/metadata callback. */
private class JointRuntimeCheckReleasePublisher(private val gate: JointRuntimeCompletionGate, private val owner: Any,
    private val main: Handler, private val state: JointRuntimeCheckRunningState, private val onReleased: () -> Unit) {
    fun observe(token: Any, released: CompletionStage<Unit>) {
        released.whenComplete { _, error -> if (error == null) acknowledge(token) }
    }
    fun acknowledge(token: Any) {
        if (gate.acknowledgeRelease(token)) {
            state.running = false; state.draining = false
            FixtureRuntimeSupervisor.release(owner, true)
            val callback = onReleased
            main.post { callback() }
        }
    }
}
