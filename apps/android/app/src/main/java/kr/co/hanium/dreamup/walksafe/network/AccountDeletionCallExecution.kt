package kr.co.hanium.dreamup.walksafe.network

import java.util.concurrent.CancellationException

internal sealed interface AccountDeletionCallExecution<out T> {
    data class Succeeded<T>(val value: T) : AccountDeletionCallExecution<T>
    data object Cancelled : AccountDeletionCallExecution<Nothing>
    data class Failed(
        val error: Exception,
    ) : AccountDeletionCallExecution<Nothing>
}

/**
 * Executes one deletion call. Cancellation cleans up immediately; success and failure retain
 * ownership until their UI-thread disposition is linearized.
 */
internal fun <T> executeAccountDeletionCall(
    call: CancellableNetworkCall<T>,
    releaseCancellationOwnership: () -> Boolean,
): AccountDeletionCallExecution<T> =
    try {
        AccountDeletionCallExecution.Succeeded(call.execute())
    } catch (_: CancellationException) {
        releaseCancellationOwnership()
        AccountDeletionCallExecution.Cancelled
    } catch (error: Exception) {
        AccountDeletionCallExecution.Failed(
            error = error,
        )
    }
