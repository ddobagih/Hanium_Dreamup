package kr.co.hanium.dreamup.walksafe.device.postlogin

/** One attempt owns apply/save/cleanup transitions and exactly one public completion. */
internal class DeviceCompositeAttemptState<C>(private val onCompletion: (Completion) -> Unit) {
    enum class Phase { RETIRING, RUNNING, APPLYING, SAVING, INVALIDATING, FINISHED }
    data class Completion(val success: Boolean, val releaseConfirmed: Boolean, val reason: String)

    @Volatile var phase = Phase.RETIRING
        private set
    @Volatile var cancelled = false
        private set
    var choice: C? = null
        private set
    var completionPublished = false
        private set
    private var cleanupClaimed = false
    private var nativeCleaned = false
    private var storagePending = false
    private var savedByAttempt = false
    private var cleanupStarted = false
    private var savedAndAccepted = false
    private var workerReleaseAcknowledged = false
    private var retireSaved: (() -> Unit)? = null
    val applying get() = phase == Phase.APPLYING && !cancelled

    @Synchronized fun beginRunning(): Boolean {
        if (cancelled || phase != Phase.RETIRING) return false
        phase = Phase.RUNNING
        return true
    }

    fun applyCandidate(candidate: C, isCurrent: () -> Boolean, apply: (C) -> Unit,
        onBlocked: (String) -> Unit): Boolean {
        synchronized(this) {
            if (cancelled || phase != Phase.RUNNING || !isCurrent()) return false
            choice = candidate
            phase = Phase.APPLYING
        }
        try { apply(candidate) } catch (_: RuntimeException) { onBlocked("joint_runtime_application_schedule_failed") }
        return true
    }

    /** A released runner cannot strand an admission rejected at the currentness/deadline boundary. */
    fun receiveReleasedCandidate(candidate: C?, isCurrent: () -> Boolean, apply: (C) -> Unit,
        onRejected: () -> Unit, onBlocked: (String) -> Unit): Boolean {
        synchronized(this) {
            // A duplicate runner result must not retire an already applying/saving parent model.
            if (phase != Phase.RUNNING) return false
            workerReleaseAcknowledged = true
        }
        val admitted = candidate != null && isCurrent() && applyCandidate(candidate, isCurrent, apply, onBlocked)
        if (!admitted && synchronized(this) { phase == Phase.RUNNING }) onRejected()
        return admitted
    }

    /** Failure publication never pretends native ownership has ended; cleanup is claimed once. */
    fun fail(reason: String, requestNativeCleanup: () -> Unit): Boolean {
        val first = synchronized(this) {
            if (phase == Phase.FINISHED) return false
            cancelled = true
            (!completionPublished).also { completionPublished = true }
        }
        if (first) onCompletion(Completion(false, false, reason))
        val actions = synchronized(this) {
            val native = phase != Phase.RUNNING && phase != Phase.FINISHED && !cleanupClaimed
            if (native) cleanupClaimed = true
            native to retireSaved.takeIf { savedByAttempt && !storagePending }
        }
        if (actions.first) requestNativeCleanup()
        actions.second?.invoke()
        return first
    }

    @Synchronized fun nativeCleanupConfirmed() { nativeCleaned = true }
    @Synchronized fun canComplete() = !storagePending && (!cleanupClaimed || nativeCleaned)

    fun complete(success: Boolean, reason: String, beforeNotify: () -> Unit,
        onPreviouslyPublishedRelease: () -> Unit, releaseBudget: () -> Unit): Boolean {
        val published = synchronized(this) {
            if (phase == Phase.FINISHED || !canComplete()) return false
            phase = Phase.FINISHED
            completionPublished.also { completionPublished = true }
        }
        beforeNotify()
        if (published) onPreviouslyPublishedRelease()
        else onCompletion(Completion(success && !cancelled && savedAndAccepted, true, reason))
        releaseBudget()
        return true
    }

    /** Only a runner's death ACK belongs here, never a parent-model or metadata cleanup ACK. */
    @Synchronized fun acknowledgeWorkerRelease(): Boolean {
        if (phase != Phase.RUNNING) return false
        workerReleaseAcknowledged = true
        return completionPublished
    }
    @Synchronized fun hasWorkerReleaseAcknowledgement() = workerReleaseAcknowledged

    /** The verified in-memory candidate is the only choice that can reach durable storage. */
    fun persistVerified(
        isCurrent: () -> Boolean,
        executeStorage: (() -> Unit) -> Unit,
        postUi: (() -> Unit) -> Unit,
        save: (C) -> Boolean,
        retireIfCurrent: (C) -> Boolean,
        onComplete: (Boolean, String) -> Unit,
        onBlocked: (String) -> Unit,
    ): Boolean {
        val candidate = synchronized(this) {
            if (cancelled || phase != Phase.APPLYING) return false
            phase = Phase.SAVING
            storagePending = true
            requireNotNull(choice)
        }
        fun finished(success: Boolean, reason: String) {
            synchronized(this) { storagePending = false }
            onComplete(success, reason)
        }
        fun retire() {
            synchronized(this) {
                if (!savedByAttempt || cleanupStarted) return
                cleanupStarted = true
                storagePending = true
                phase = Phase.INVALIDATING
            }
            try {
                executeStorage {
                    val cleaned = runCatching { retireIfCurrent(candidate) }.getOrDefault(false)
                    postUi {
                        if (cleaned) finished(false, "joint_runtime_saved_choice_retired")
                        else onBlocked("joint_runtime_saved_choice_cleanup_unconfirmed")
                    }
                }
            } catch (_: RuntimeException) { onBlocked("joint_runtime_cleanup_schedule_failed") }
        }
        synchronized(this) { retireSaved = ::retire }
        try {
            executeStorage {
                val saved = runCatching { !cancelled && isCurrent() && save(candidate) }.getOrDefault(false)
                synchronized(this) { savedByAttempt = saved }
                postUi {
                    if (!saved) finished(false, "joint_runtime_save_retired_or_failed")
                    else if (!cancelled && isCurrent()) {
                        synchronized(this) { savedAndAccepted = true }
                        finished(true, "joint_runtime_measured_applied_saved")
                    } else retire()
                }
            }
        } catch (_: RuntimeException) { finished(false, "joint_runtime_save_schedule_failed") }
        return true
    }
}
