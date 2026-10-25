package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken

/** Owns one route question until DONE or retirement; construction does not read or start its ports. */
internal class RouteDecisionSpeechCoordinator(
    private val speech: SpeechPort,
    private val callbacks: RouteCallbackScheduler,
    private val pendingDecisionToken: () -> RouteNavigatorDecisionToken?,
) {
    interface SpeechPort {
        val commandGeneration: Long
        val commandPending: Boolean
        fun outputBusy(): Boolean
        fun inputActive(): Boolean
        fun cancelCommand()
        fun failCommand(generation: Long)
        fun submitStatus(message: String, current: () -> Boolean, completed: () -> Unit,
            failed: (() -> Unit)? = null): Boolean
    }

    private class Attempt(
        val token: RouteNavigatorDecisionToken,
        val scopeCurrent: () -> Boolean,
        var responseGeneration: Long? = null,
        var retry: Runnable? = null,
    )

    internal data class State(
        val pendingToken: RouteNavigatorDecisionToken?,
        val announcedToken: RouteNavigatorDecisionToken?,
        val responseGeneration: Long?,
        val retryPending: Boolean,
    )

    private var currentAttempt: Attempt? = null
    private var lastAnnouncedToken: RouteNavigatorDecisionToken? = null
    val state: State get() = State(currentAttempt?.token, lastAnnouncedToken,
        currentAttempt?.responseGeneration, currentAttempt?.retry != null)

    fun failInterruptedSpeech(previousResponseGeneration: Long) {
        if (currentAttempt?.responseGeneration != previousResponseGeneration) return
        speech.failCommand(previousResponseGeneration)
    }

    fun cancel() {
        val retired = currentAttempt
        currentAttempt = null
        lastAnnouncedToken = null
        // A cancellation can synchronously report failure or submit another question.
        // Retire all ownership before invoking either external port.
        val retry = retired?.retry
        retired?.retry = null
        val responseGeneration = retired?.responseGeneration
        retired?.responseGeneration = null
        retry?.let(callbacks::remove)
        if (responseGeneration != null && responseGeneration == speech.commandGeneration) {
            speech.cancelCommand()
        }
    }

    fun speak(
        message: String,
        token: RouteNavigatorDecisionToken,
        repeatRequested: Boolean = false,
        isScopeCurrent: () -> Boolean,
        submitSpeech: (String, () -> Boolean, () -> Unit, () -> Unit) -> Boolean = speech::submitStatus,
        enqueueRetry: (Runnable) -> Unit = { callbacks.postDelayed(it, 1_000L) },
        outputBusy: () -> Boolean = speech::outputBusy,
    ): Boolean {
        if (token != pendingDecisionToken()) return false
        // A route choice can outlive its output owner; only a current attempt suppresses a replay.
        currentAttempt?.let { if (it.token == token && isCurrent(it)) return true }
        if (!repeatRequested && lastAnnouncedToken == token) return true
        cancel()
        // Keep a newer question installed by a synchronous cancellation callback.
        if (currentAttempt != null || !isScopeCurrent()) return false
        val attempt = Attempt(token, isScopeCurrent)
        currentAttempt = attempt

        fun retry() {
            if (!isCurrent(attempt)) {
                retireIfOwned(attempt)
                return
            }
            if (attempt.retry != null) return
            attempt.responseGeneration = null
            lateinit var pending: Runnable
            pending = Runnable {
                if (currentAttempt !== attempt || attempt.retry !== pending) return@Runnable
                attempt.retry = null
                if (!isCurrent(attempt)) {
                    retireIfOwned(attempt)
                    return@Runnable
                }
                // Each submission gets a distinct identity, including retries of the same token.
                currentAttempt = null
                speak(message, token, repeatRequested, isScopeCurrent, submitSpeech, enqueueRetry, outputBusy)
            }
            attempt.retry = pending
            enqueueRetry(pending)
        }

        // Keep the question pending without interrupting a newer command, voice input or risk output.
        if (speech.commandPending || speech.inputActive() || outputBusy()) {
            retry()
            return false
        }
        var terminated = false
        val previousResponseGeneration = speech.commandGeneration
        val accepted = submitSpeech(message, { isCurrent(attempt) }, {
            if (!terminated) {
                terminated = true
                if (isCurrent(attempt)) {
                    currentAttempt = null
                    lastAnnouncedToken = token
                } else {
                    retireIfOwned(attempt)
                }
            }
        }, {
            if (!terminated) {
                terminated = true
                retry()
            }
        })
        if (accepted && !terminated && isCurrent(attempt) &&
            speech.commandGeneration != previousResponseGeneration
        ) {
            attempt.responseGeneration = speech.commandGeneration
        }
        if (!accepted && !terminated) {
            terminated = true
            retry()
        }
        return accepted
    }

    private fun isCurrent(attempt: Attempt): Boolean =
        currentAttempt === attempt && attempt.token == pendingDecisionToken() && attempt.scopeCurrent()

    private fun retireIfOwned(attempt: Attempt) {
        if (currentAttempt === attempt) cancel()
    }
}
