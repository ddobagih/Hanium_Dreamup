package kr.co.hanium.dreamup.walksafe.runtime.metric

import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthorityOutcome
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthorityRead
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckNativeReleaseProof
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

internal data class MetricJournalReleaseReceipt(val childPid: Int, val proof: DeviceCheckNativeReleaseProof)

/** Metadata only. One normal/late cleanup publication; an uncertain writer is joined, never cancelled. */
internal class MetricReleaseJournal(
    private val write: (MetricJournalReleaseReceipt) -> CompletionStage<DeviceCheckAuthorityOutcome<Unit>>,
    private val settleExactRelease: (String, MetricJournalReleaseReceipt) -> CompletionStage<DeviceCheckAuthorityOutcome<Unit>>,
    private val reconcile: (String) -> CompletionStage<DeviceCheckAuthorityRead>,
    private val markUnconfirmed: (String) -> String,
    private val changed: () -> Unit,
) {
    private val lock = Any()
    private var intentCommand: String? = null
    private var intentPending = false
    private var published = false
    private var uncertain = false
    private var sealed = false
    private var proof: MetricJournalReleaseReceipt? = null
    private var requested = false
    private var recovering = false
    private var operation: String? = null
    val firstOutcome = CompletableFuture<DeviceCheckAuthorityOutcome<Unit>>()
    val confirmed = CompletableFuture<Unit>()

    fun intentStarting(command: String) = synchronized(lock) {
        check(!sealed && intentCommand == null)
        intentCommand = command; intentPending = true
    }

    fun <T> observeIntent(future: CompletionStage<DeviceCheckAuthorityOutcome<T>>) {
        future.whenComplete { result, error ->
            synchronized(lock) {
                intentPending = false
                published = result is DeviceCheckAuthorityOutcome.Committed
                uncertain = error != null || result is DeviceCheckAuthorityOutcome.Indeterminate || result == null
                if (uncertain) operation = (result as? DeviceCheckAuthorityOutcome.Indeterminate)?.operationId ?: intentCommand
            }
            advance()
        }
    }

    fun intentInvocationFailed() {
        synchronized(lock) { intentPending = false; uncertain = true; operation = intentCommand }
        advance()
    }

    fun sealIntent() { synchronized(lock) { sealed = true }; advance() }
    fun observePhysical(receipt: MetricJournalReleaseReceipt) {
        synchronized(lock) { check(proof == null || proof == receipt); proof = receipt }
        advance()
    }

    private fun advance() {
        val request = synchronized(lock) {
            if (requested || !sealed || proof == null || intentPending) return
            requested = true
            Triple(requireNotNull(proof), published, uncertain)
        }
        if (!request.second && !request.third) {
            finishConfirmed()
            firstOutcome.complete(DeviceCheckAuthorityOutcome.Committed(Unit, 0L, "metric_no_load_intent"))
            return
        }
        val future = try {
            if (request.third) settleExactRelease(requireNotNull(intentCommand), request.first) else write(request.first)
        } catch (_: Throwable) {
            acceptOutcome(DeviceCheckAuthorityOutcome.Indeterminate(requireNotNull(intentCommand))); return
        }
        future.whenComplete { outcome, error ->
            acceptOutcome(if (error != null || outcome == null)
                DeviceCheckAuthorityOutcome.Indeterminate(requireNotNull(intentCommand)) else outcome)
        }
    }

    private fun acceptOutcome(outcome: DeviceCheckAuthorityOutcome<Unit>) {
        when (outcome) {
            is DeviceCheckAuthorityOutcome.Committed -> { finishConfirmed(); firstOutcome.complete(outcome) }
            else -> {
                val marker = (outcome as? DeviceCheckAuthorityOutcome.Indeterminate)?.operationId ?: requireNotNull(intentCommand)
                val current = runCatching { markUnconfirmed(marker) }.getOrDefault(marker)
                synchronized(lock) {
                    operation = current
                }
                firstOutcome.complete(outcome)
                recheck()
            }
        }
    }

    /** One bounded reconciliation per invocation. A still-blocked store waits for an explicit retry. */
    fun recheck() {
        val id = synchronized(lock) {
            if (confirmed.isDone || recovering || !requested) return
            (operation ?: return).also { recovering = true }
        }
        val future = try { reconcile(id) }
        catch (_: Throwable) { synchronized(lock) { recovering = false }; changed(); return }
        future.whenComplete { read, error ->
            val ready = synchronized(lock) {
                if (error != null || read == null || read is DeviceCheckAuthorityRead.Blocked && read.operationId != null) {
                    (read as? DeviceCheckAuthorityRead.Blocked)?.operationId?.let { operation = it }
                    recovering = false
                    false
                } else true
            }
            if (!ready) { changed(); return@whenComplete }
            // Reconciliation joins the original writer and merges retained cleanup. Its generic
            // read result is not an exact receipt: verify this captured LOAD again before unblocking.
            val retry = try { settleExactRelease(requireNotNull(intentCommand), requireNotNull(proof)) }
            catch (_: Throwable) { synchronized(lock) { recovering = false }; changed(); return@whenComplete }
            retry.whenComplete { outcome, failure ->
                if (failure == null && outcome is DeviceCheckAuthorityOutcome.Committed) {
                    finishConfirmed()
                    synchronized(lock) { recovering = false }
                } else {
                    synchronized(lock) {
                        recovering = false
                        (outcome as? DeviceCheckAuthorityOutcome.Indeterminate)?.let { operation = it.operationId }
                    }
                    changed()
                }
            }
        }
    }

    private fun finishConfirmed() { if (confirmed.complete(Unit)) changed() }
}
