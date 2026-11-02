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
    private var preparedAndAccepted = false
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
        settleEffects(
            { if (first) onCompletion(Completion(false, false, reason)) },
            {
                val actions = synchronized(this) {
                    val native = phase != Phase.RUNNING && phase != Phase.FINISHED && !cleanupClaimed
                    if (native) cleanupClaimed = true
                    native to retireSaved.takeIf { savedByAttempt && !storagePending }
                }
                settleEffects({ if (actions.first) requestNativeCleanup() }, { actions.second?.invoke() })
            },
        )
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
        var preparationNotified = false
        settleEffects(
            { beforeNotify(); preparationNotified = true },
            {
                if (published) onPreviouslyPublishedRelease()
                else onCompletion(Completion(success && preparationNotified && !cancelled &&
                    (preparedAndAccepted || savedAndAccepted), true,
                    if (preparationNotified) reason else "joint_runtime_completion_callback_failed"))
            },
            releaseBudget,
        )
        return true
    }

    /** External notification failures cannot skip cleanup or the already-proven budget return. */
    private fun settleEffects(vararg effects: () -> Unit) {
        var failure: Throwable? = null
        effects.forEach { effect ->
            try { effect() } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    /** Only a runner's death ACK belongs here, never a parent-model or metadata cleanup ACK. */
    @Synchronized fun acknowledgeWorkerRelease(): Boolean {
        if (phase != Phase.RUNNING) return false
        workerReleaseAcknowledged = true
        return completionPublished
    }
    @Synchronized fun hasWorkerReleaseAcknowledgement() = workerReleaseAcknowledged

    /** Actual pair readiness is not durable success. The Controller alone commits the bundle. */
    fun acceptPrepared(isCurrent: () -> Boolean): Boolean {
        synchronized(this) { if (cancelled || phase != Phase.APPLYING) return false }
        if (!runCatching(isCurrent).getOrDefault(false)) return false
        return synchronized(this) {
            if (cancelled || phase != Phase.APPLYING) false
            else { preparedAndAccepted = true; true }
        }
    }

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

/** The same transaction drives Main's real receivers and deterministic queue tests. No SDK types. */
internal class DevicePreparedPairAdoption<P : Any>(
    private val current: () -> Boolean,
    private val adoptPrimary: (P, (Boolean) -> Unit) -> Unit,
    private val adoptAuxiliary: (P) -> Boolean,
    private val verification: (P) -> Verification,
    private val publish: (P) -> Boolean,
    private val onReady: (P) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    enum class Verification { PENDING, VERIFIED, FAILED }
    private enum class Phase { CREATING, ADOPTING, VERIFYING, PUBLISHING, READY, RETIRED }
    private var phase = Phase.CREATING
    private var pair: P? = null
    private var primaryAnswered = false
    private fun live() = runCatching(current).getOrDefault(false)

    fun prepared(value: P) {
        synchronized(this) {
            if (phase != Phase.CREATING) return
            pair = value
            phase = Phase.ADOPTING
        }
        if (!live()) { fail("pair_adoption_retired"); return }
        try { adoptPrimary(value) { accepted ->
            synchronized(this) {
                if (phase != Phase.ADOPTING || primaryAnswered) return@adoptPrimary
                primaryAnswered = true
            }
            if (!accepted || !live()) { fail("pair_primary_adoption_failed"); return@adoptPrimary }
            val auxiliary = runCatching { adoptAuxiliary(value) }.getOrDefault(false)
            if (!auxiliary || !live()) { fail("pair_auxiliary_adoption_failed"); return@adoptPrimary }
            synchronized(this) {
                if (phase != Phase.ADOPTING) return@adoptPrimary
                phase = Phase.VERIFYING
            }
            poll()
        } } catch (_: Throwable) { fail("pair_primary_adoption_failed") }
    }

    fun poll() {
        val value = synchronized(this) { pair.takeIf { phase == Phase.VERIFYING } } ?: return
        if (!live()) { fail("pair_verification_retired"); return }
        val state = runCatching { verification(value) }.getOrDefault(Verification.FAILED)
        if (!live()) { fail("pair_verification_retired"); return }
        if (state == Verification.PENDING) return
        if (state != Verification.VERIFIED) { fail("pair_verification_failed"); return }
        synchronized(this) {
            if (phase != Phase.VERIFYING) return
            phase = Phase.PUBLISHING
        }
        val accepted = runCatching { publish(value) }.getOrDefault(false)
        if (!accepted || !live()) { fail("pair_publication_retired"); return }
        synchronized(this) {
            if (phase != Phase.PUBLISHING) return
            phase = Phase.READY
        }
        onReady(value)
    }

    fun fail(reason: String) {
        synchronized(this) {
            if (phase == Phase.RETIRED) return
            phase = Phase.RETIRED
        }
        onFailure(reason)
    }
}

/** Query the captured owner only after queued old creation and both host drains have returned. */
internal object DeviceRuntimeLeaseRetirement {
    fun <O : Any, R : Any> afterHostDrain(owner: O?, hostDrained: java.util.concurrent.CompletionStage<Unit>,
        snapshot: (O) -> R?, actualReleased: (R) -> java.util.concurrent.CompletionStage<*>,
        requestExit: (R) -> Unit, knownRetirement: R? = null): java.util.concurrent.CompletableFuture<Unit> {
        val finished = java.util.concurrent.CompletableFuture<Unit>()
        hostDrained.whenComplete { _, hostError ->
            val captured = try { owner?.let(snapshot) ?: knownRetirement } catch (error: Throwable) {
                finished.completeExceptionally(error); return@whenComplete
            }
            if (captured == null) {
                if (hostError == null) finished.complete(Unit) else finished.completeExceptionally(hostError)
            } else {
                val physical = try { actualReleased(captured) } catch (error: Throwable) {
                    finished.completeExceptionally(error); return@whenComplete
                }
                physical.whenComplete { _, nativeError ->
                    val error = hostError ?: nativeError
                    if (error == null) finished.complete(Unit) else finished.completeExceptionally(error)
                }
                // A control exception does not prove native death; keep waiting for the actual ACK.
                runCatching { requestExit(captured) }
            }
        }
        return finished
    }
}

/** The delegated attach owns its own create/failure receipt; admission failure owns NO_CREATE. */
internal class DeviceRuntimeCreationBoundary(private val noCreate: () -> Unit) {
    private var entered = false
    private var finished = false
    fun enteredAttachment() { check(!finished); entered = true }
    fun finish() {
        if (finished) return
        finished = true
        if (!entered) noCreate()
    }
}

/** Failed attempts may return their budget only after every captured cleanup proof succeeds. */
internal object DeviceRuntimeLeaseFailureCleanup {
    fun afterRelease(primary: java.util.concurrent.CompletionStage<Unit>,
        auxiliary: java.util.concurrent.CompletionStage<Boolean>,
        actualReleased: java.util.concurrent.CompletionStage<*>,
        allocationAvailable: java.util.concurrent.CompletionStage<*>,
        hostDrained: java.util.concurrent.CompletableFuture<Unit>,
        consumerDrained: java.util.concurrent.CompletableFuture<Unit>?,
        consumerReleased: java.util.concurrent.CompletionStage<*>?): java.util.concurrent.CompletableFuture<Unit> {
        val auxiliaryProof = auxiliary.thenApply { check(it) { "Auxiliary release unconfirmed" }; Unit }
        return java.util.concurrent.CompletableFuture.allOf(primary.toCompletableFuture(),
            auxiliaryProof.toCompletableFuture()).thenCompose {
            hostDrained.complete(Unit)
            consumerDrained?.complete(Unit)
            java.util.concurrent.CompletableFuture.allOf(actualReleased.toCompletableFuture(),
                allocationAvailable.toCompletableFuture(),
                consumerReleased?.toCompletableFuture() ?: java.util.concurrent.CompletableFuture.completedFuture(Unit))
        }.thenApply { Unit }
    }
}
