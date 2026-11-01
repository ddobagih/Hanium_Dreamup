package kr.co.hanium.dreamup.walksafe.inference.composite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class JointRuntimeCheckResult(val choice: ValidatedJointRuntimeChoice?, val reason: String,
    val releaseConfirmed: Boolean)

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

/** Four bundled images only. Model initialization/invoke/close never executes in the app process. */
class AndroidJointRuntimeCheck(
    context: Context,
    private val config: TwoModelRuntimeConfig,
    private val baselinePrimary: RuntimeCandidate,
    private val deadlineMs: Long,
    private val isCurrent: () -> Boolean,
    @Suppress("UNUSED_PARAMETER") speechActive: () -> Boolean = { false },
    private val onFinished: (JointRuntimeCheckResult) -> Unit,
    private val onProgress: (String) -> Unit = {},
    private val onReleaseConfirmed: () -> Unit = {},
) {
    private val app = context.applicationContext
    private val workDeadlineMs = deadlineMs - 20_000L
    private val main = Handler(Looper.getMainLooper())
    private val owner = Any()
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val completion = JointRuntimeCompletionGate(initiallyReleased = true)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "WalkSafeFixtureCheck") }
    private val watchdog = ScheduledThreadPoolExecutor(1) { Thread(it, "WalkSafeFixtureCheckDeadline") }.apply {
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false); removeOnCancelPolicy = true
    }
    @Volatile private var watch: ScheduledFuture<*>? = null
    @Volatile private var remote: RemoteFixtureRuntime? = null
    @Volatile var running = false
        private set
    @Volatile var draining = false
        private set
    @Volatile private var reason = "fixture_unverified"

    fun start(): Boolean {
        if (deadlineMs - now() !in 1L..300_000L || !isCurrent() || cancelled.get() ||
            !started.compareAndSet(false, true) || !FixtureRuntimeSupervisor.acquire(owner)) return false
        running = true
        watch = watchdog.schedule({
            cancelled.set(true)
            remote?.cancel()
            report(null, "fixture_total_deadline", completion.releaseConfirmed)
        }, (deadlineMs - now()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        worker.execute(::run)
        return true
    }

    fun cancel(reason: String) {
        this.reason = reason
        cancelled.set(true)
        draining = running
        remote?.cancel()
    }

    private fun run() {
        val choices = mutableListOf<ValidatedJointRuntimeChoice>()
        var released = true
        var finalReason = "no_usable_fixture_candidate"
        try {
            val binding = requireNotNull(AndroidJointRuntimeChoiceStore.createBinding(app, config)) { "fixture_binding_unavailable" }
            val processors = Runtime.getRuntime().availableProcessors()
            for (candidate in candidates(processors)) {
                if (!current() || workDeadlineMs - now() < 35_000L) break
                val trialToken = Any()
                if (!completion.acquireResource(trialToken)) break
                emit("내장 시험 자료로 두 인식 모델의 실행 조합을 점검하고 있습니다.")
                released = false
                lateinit var client: RemoteFixtureRuntime
                client = RemoteFixtureRuntime(app, candidate, binding, workDeadlineMs, ::current, progress = { stage ->
                    Log.i(TAG, "candidate=$candidate stage=$stage")
                    emit(when (stage) {
                        "references", "output_check" -> "내장 시험 자료의 인식 결과를 확인하고 있습니다."
                        "benchmark" -> "두 인식 모델을 함께 실행해 처리 속도를 확인하고 있습니다."
                        "closing" -> "시험용 실행 환경을 정리하고 있습니다."
                        else -> "인식 모델의 실행 환경을 준비하고 있습니다."
                    })
                }, onReleased = { acknowledgeRelease(trialToken) })
                remote = client
                val outcome = client.run()
                released = outcome.releaseConfirmed
                if (released) { acknowledgeRelease(trialToken); remote = null }
                Log.i(TAG, "candidate=$candidate released=$released reason=${outcome.reason}")
                if (!released) { finalReason = "fixture_worker_death_unconfirmed"; break }
                val evidence = outcome.evidence
                val choice = evidence?.let { JointRuntimeChoicePolicy.evaluate(binding, candidate, it, processors) }
                if (choice != null) choices += choice
                if (evidence != null) Log.i(TAG, "candidate=$candidate durationMs=${evidence.durationMs} " +
                    "primary=${evidence.primaryTurnaroundMs.size}/${evidence.primarySubmittedFrames} " +
                    "auxiliary=${evidence.auxiliaryTurnaroundMs.size}/${evidence.auxiliarySubmittedFrames} " +
                    "accepted=${choice != null} rejection=${JointRuntimeChoicePolicy.rejectionReason(candidate, evidence, processors)}")
            }
            val choice = if (released && current()) JointRuntimeChoicePolicy.select(choices, processors) else null
            if (choice != null) finalReason = "sample_images_joint_candidate_verified"
            if (cancelled.get()) finalReason = reason
            report(choice, finalReason, released)
        } catch (error: Throwable) {
            if (remote == null) released = true
            report(null, (error.message ?: "fixture_check_failed").take(160), released)
        } finally { worker.shutdown() }
    }

    private fun candidates(processors: Int): List<JointRuntimeCandidate> {
        val threads = minOf(4, processors).coerceAtLeast(1)
        val cpu = RuntimeCandidate(RuntimeBackend.CPU, threads)
        val gpu = RuntimeCandidate(RuntimeBackend.GPU, threads)
        val baseline = baselinePrimary.copy(numThreads = baselinePrimary.numThreads.coerceIn(1, threads))
        return buildList {
            add(JointRuntimeCandidate(cpu, cpu)) // A CPU alternative must be measured, never presumed usable.
            add(JointRuntimeCandidate(baseline, gpu))
            add(JointRuntimeCandidate(gpu, cpu))
            add(JointRuntimeCandidate(cpu, gpu))
            add(JointRuntimeCandidate(gpu, gpu))
            if (threads > 2) add(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 2),
                RuntimeCandidate(RuntimeBackend.CPU, 2), 250L, 500L))
        }.distinct()
    }

    private fun report(choice: ValidatedJointRuntimeChoice?, resultReason: String, released: Boolean): Boolean {
        val confirmed = completion.claim(released) ?: return false
        running = !confirmed; draining = !confirmed
        watch?.cancel(false); watchdog.shutdown()
        val accepted = choice.takeIf { confirmed && current() }
        if (confirmed) FixtureRuntimeSupervisor.release(owner, true)
        Log.i(TAG, "finished=$resultReason releaseConfirmed=$confirmed selected=${accepted?.candidate}")
        main.post { onFinished(JointRuntimeCheckResult(accepted.takeIf { !cancelled.get() && isCurrent() && now() < deadlineMs }, resultReason, confirmed)) }
        return true
    }
    private fun acknowledgeRelease(trialToken: Any) {
        if (completion.acknowledgeRelease(trialToken)) {
            running = false; draining = false
            FixtureRuntimeSupervisor.release(owner, true)
            Log.i(TAG, "late_fixture_worker_death_confirmed")
            main.post { onReleaseConfirmed() }
        }
    }
    private fun emit(value: String) = main.post { if (current()) onProgress(value) }
    private fun current() = !cancelled.get() && isCurrent() && now() < deadlineMs && FixtureRuntimeSupervisor.owns(owner)
    private fun now() = SystemClock.elapsedRealtime()
    private companion object { const val TAG = "WalkSafeJointRuntime" }
}
