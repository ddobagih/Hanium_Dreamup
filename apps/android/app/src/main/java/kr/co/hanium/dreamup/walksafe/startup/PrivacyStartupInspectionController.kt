package kr.co.hanium.dreamup.walksafe.startup

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionActivityLease
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStateMachine

internal enum class PrivacyStartupInspectionResult {
    READY,
    WAIT_FOR_ACCOUNT_DELETION_RESET,
    FINISH,
}

/** The exact captured participants, retaining their original futures and ownership checks. */
internal interface PreparedPrivacyStartupReset {
    fun awaitOriginalAcknowledgements(): Boolean
    fun isCurrent(): Boolean

    companion object {
        /** A failed second preparation still retains the first participant's original ACK. */
        fun <S, R> capture(
            prepareSpeech: () -> S,
            prepareRoute: () -> R?,
            awaitSpeech: (S) -> Boolean,
            awaitRoute: (R?) -> Boolean,
            speechIsCurrent: (S) -> Boolean,
            routeIsCurrent: (R) -> Boolean,
        ): PreparedPrivacyStartupReset {
            val speech = prepareSpeech()
            val route = runCatching(prepareRoute).getOrNull()
            return object : PreparedPrivacyStartupReset {
                override fun awaitOriginalAcknowledgements(): Boolean {
                    val speechPurgedAndDrained = awaitSpeech(speech)
                    val routePurged = awaitRoute(route)
                    return speechPurgedAndDrained && routePurged
                }
                override fun isCurrent(): Boolean =
                    speechIsCurrent(speech) && route?.let(routeIsCurrent) == true
            }
        }
    }
}

internal interface PrivacyStartupResetPreparation {
    fun isRequired(): Boolean
    fun prepare(): PreparedPrivacyStartupReset
}

/** Resource and Activity effects; no mutable Activity graph is exposed to the controller. */
internal interface PrivacyStartupActions {
    fun showStatus(message: String)
    fun finishActivity()
    fun recreateActivity()
    fun drainSpeechOutputOnWorker()
    fun closeLegacyResourcesOnWorker()
    fun closeReadyResourcesOnUiThread()
    fun prepareHandsFreeVoiceModel()
    fun resumeWalkRuntime()
    fun restoreGatewaySessionOnWorker()
    fun rejectGatewayRestore()
    fun resumePrivacyControlOperations()
    fun rejectPrivacyControlOperations()
}

/** Owns startup admission, foreground readiness, and retirement of one Activity's resources. */
internal class PrivacyStartupInspectionController(
    private val inspectionExecutor: Executor,
    private val cleanupExecutor: () -> Executor,
    private val postToUi: (() -> Unit) -> Boolean,
    private val stateMachine: () -> AccountDeletionStateMachine,
    private val activityLease: () -> AccountDeletionActivityLease,
    private val isForeground: () -> Boolean,
    private val resetPreparation: PrivacyStartupResetPreparation,
    private val actions: PrivacyStartupActions,
) {
    private val privacyStartupInspectionLock = Any()
    private val privacyStartupResourceLock = Any()
    private var privacyStartupInspectionGeneration = 0L
    @Volatile private var privacyStartupInspectionComplete = false
    @Volatile private var privacyStartupInspectionDestroyed = false
    private var privacyStartupResourcesClosed = false
    private var privacyStartupResourcesCloseScheduled = false
    private var privacyStartupResetHandoffScheduled = false
    private data class PendingReady(val generation: Long, val action: () -> Unit)
    private var pendingPrivacyStartupReadyAction: PendingReady? = null

    val isComplete: Boolean get() = privacyStartupInspectionComplete
    val isDestroyed: Boolean get() = privacyStartupInspectionDestroyed

    fun startInspection(
        prepare: () -> PrivacyStartupInspectionResult?,
        inspect: (Boolean?) -> PrivacyStartupInspectionResult,
        onReady: () -> Unit,
    ) {
        val generation = synchronized(privacyStartupInspectionLock) {
            privacyStartupInspectionGeneration += 1L
            privacyStartupInspectionGeneration
        }
        try {
            inspectionExecutor.execute {
                var preparedReset: PreparedPrivacyStartupReset? = null
                var result = synchronized(PRIVACY_STARTUP_PROCESS_LOCK) {
                    synchronized(privacyStartupResourceLock) {
                        if (privacyStartupResourcesClosed || !isCurrent(generation)) {
                            null
                        } else if (stateMachine().phase() == AccountDeletionPhase.RESET_PENDING) {
                            PrivacyStartupInspectionResult.WAIT_FOR_ACCOUNT_DELETION_RESET
                        } else {
                            runCatching {
                                prepare() ?: if (resetPreparation.isRequired()) {
                                    preparedReset = resetPreparation.prepare()
                                    null
                                } else {
                                    inspect(null)
                                }
                            }.getOrNull()
                        }
                    }
                }
                preparedReset?.let { preparation ->
                    // Await both original participant acknowledgements outside the privacy locks.
                    val purgedAndDrained = preparation.awaitOriginalAcknowledgements()
                    result = synchronized(PRIVACY_STARTUP_PROCESS_LOCK) {
                        synchronized(privacyStartupResourceLock) {
                            if (privacyStartupResourcesClosed || !isCurrent(generation) ||
                                !preparation.isCurrent()
                            ) null else runCatching { inspect(purgedAndDrained) }.getOrNull()
                        }
                    }
                }
                val workerCurrent = isCurrent(generation)
                if (result != PrivacyStartupInspectionResult.READY || !workerCurrent) {
                    closeResourcesOnWorker()
                }
                val callbackPosted = postToUi {
                    val current = synchronized(privacyStartupInspectionLock) {
                        !privacyStartupInspectionDestroyed &&
                            generation == privacyStartupInspectionGeneration
                    }
                    if (!current) {
                        runCatching { actions.closeReadyResourcesOnUiThread() }
                        scheduleResourcesClose()
                        return@postToUi
                    }
                    if (!stateMachine().activityLeaseIsCurrent(activityLease())) {
                        runCatching { actions.closeReadyResourcesOnUiThread() }
                        scheduleResourcesClose()
                        return@postToUi
                    }
                    if (result != PrivacyStartupInspectionResult.READY) {
                        runCatching { actions.closeReadyResourcesOnUiThread() }
                    }
                    if (result == PrivacyStartupInspectionResult.FINISH) {
                        actions.finishActivity()
                        return@postToUi
                    }
                    if (result == PrivacyStartupInspectionResult.WAIT_FOR_ACCOUNT_DELETION_RESET) {
                        scheduleResetHandoff(generation)
                        return@postToUi
                    }
                    if (result != PrivacyStartupInspectionResult.READY) {
                        actions.showStatus(INSPECTION_FAILED)
                        return@postToUi
                    }
                    pendingPrivacyStartupReadyAction = PendingReady(generation, onReady)
                    completeReadyIfForeground()
                }
                if (!callbackPosted) scheduleResourcesClose()
            }
        } catch (_: RejectedExecutionException) {
            runCatching { actions.closeReadyResourcesOnUiThread() }
            scheduleResourcesClose()
            actions.showStatus(INSPECTION_FAILED)
        }
    }

    fun isCurrent(generation: Long): Boolean =
        synchronized(privacyStartupInspectionLock) {
            !privacyStartupInspectionDestroyed && generation == privacyStartupInspectionGeneration
        } && stateMachine().activityLeaseIsCurrent(activityLease())

    fun scheduleResourcesClose() {
        val shouldSchedule = synchronized(privacyStartupResourceLock) {
            if (privacyStartupResourcesClosed || privacyStartupResourcesCloseScheduled) {
                false
            } else {
                privacyStartupResourcesCloseScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        try {
            cleanupExecutor().execute(::closeResourcesOnWorker)
        } catch (_: RejectedExecutionException) {
            synchronized(privacyStartupResourceLock) {
                privacyStartupResourcesCloseScheduled = false
            }
        }
    }

    fun scheduleResetHandoff(generation: Long) {
        if (privacyStartupResetHandoffScheduled) return
        privacyStartupResetHandoffScheduled = true
        actions.showStatus(RESET_HANDOFF_PENDING)
        try {
            cleanupExecutor().execute {
                val resetStillPending = stateMachine().phase() == AccountDeletionPhase.RESET_PENDING
                postToUi {
                    if (!isCurrent(generation)) return@postToUi
                    privacyStartupResetHandoffScheduled = false
                    if (resetStillPending) {
                        actions.showStatus(RESET_HANDOFF_FAILED)
                    } else {
                        actions.recreateActivity()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            privacyStartupResetHandoffScheduled = false
            actions.showStatus(RESET_HANDOFF_FAILED)
        }
    }

    fun closeResourcesOnWorker() {
        // Cleanup failures cannot prevent independent resources or the failure presentation.
        runCatching { actions.drainSpeechOutputOnWorker() }
        synchronized(privacyStartupResourceLock) {
            privacyStartupResourcesCloseScheduled = false
            if (privacyStartupResourcesClosed) return
            privacyStartupResourcesClosed = true
            runCatching { actions.closeLegacyResourcesOnWorker() }
        }
    }

    fun completeReadyIfForeground() {
        if (privacyStartupInspectionDestroyed || privacyStartupInspectionComplete || !isForeground()) return
        val pending = pendingPrivacyStartupReadyAction ?: return
        pendingPrivacyStartupReadyAction = null
        if (!isCurrent(pending.generation)) {
            runCatching { actions.closeReadyResourcesOnUiThread() }
            scheduleResourcesClose()
            return
        }
        privacyStartupInspectionComplete = true
        try {
            pending.action()
            if (!isCurrent(pending.generation)) {
                privacyStartupInspectionComplete = false
                runCatching { actions.closeReadyResourcesOnUiThread() }
                scheduleResourcesClose()
                return
            }
            actions.prepareHandsFreeVoiceModel()
            if (isForeground()) actions.resumeWalkRuntime()
        } catch (_: Exception) {
            privacyStartupInspectionComplete = false
            runCatching { actions.closeReadyResourcesOnUiThread() }
            scheduleResourcesClose()
            actions.showStatus(INSPECTION_FAILED)
        }
    }

    fun scheduleGatewayRestore() {
        try {
            inspectionExecutor.execute {
                if (privacyStartupInspectionComplete && !privacyStartupInspectionDestroyed) {
                    actions.restoreGatewaySessionOnWorker()
                }
            }
        } catch (_: RejectedExecutionException) {
            actions.rejectGatewayRestore()
        }
    }

    fun schedulePrivacyOperations() {
        try {
            inspectionExecutor.execute {
                if (!privacyStartupInspectionComplete || privacyStartupInspectionDestroyed) return@execute
                if (stateMachine().processingBlocked()) {
                    postToUi {
                        if (!privacyStartupInspectionDestroyed) actions.resumePrivacyControlOperations()
                    }
                    return@execute
                }
                actions.resumePrivacyControlOperations()
            }
        } catch (_: RejectedExecutionException) {
            actions.rejectPrivacyControlOperations()
        }
    }

    /** Preserve the existing generation fence before the process Activity lease is detached. */
    fun destroy() {
        synchronized(privacyStartupInspectionLock) {
            privacyStartupInspectionDestroyed = true
            privacyStartupInspectionGeneration += 1L
        }
    }

    /** Kept separate to preserve the original onDestroy order around other resource retirement. */
    fun clearPendingReady() { pendingPrivacyStartupReadyAction = null }

    companion object {
        private val PRIVACY_STARTUP_PROCESS_LOCK = Any()
        var accountDeletionStartupResetHandoffPending = false
        private const val INSPECTION_FAILED = "개인정보 보호 상태를 확인할 수 없어 기능을 시작하지 않았습니다."
        private const val RESET_HANDOFF_PENDING = "계정 삭제 완료 상태를 안전하게 정리하고 있습니다."
        private const val RESET_HANDOFF_FAILED = "계정 삭제 초기화를 완료하지 못해 기능을 시작하지 않았습니다."
    }
}
