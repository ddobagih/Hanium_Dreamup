package kr.co.hanium.dreamup.walksafe

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CompletableFuture
import java.lang.ref.WeakReference
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamTransferredModel
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeFailures

/** Main-thread owner. Model failures require explicit retry; Busy waits only for foreign availability. */
internal class CameraUnknownRuntime<T>(
    private val create: (Long) -> FastSamRuntimeService<T>,
    private val dispatch: (() -> Unit) -> Unit,
    private val changed: () -> Unit,
    private val requiresTransferredPair: Boolean = false,
) {
    enum class Status { IDLE, LOADING, READY, FAILED, WAITING_RELEASE, WAITING_AVAILABILITY, RELEASE_FAILED }

    @Volatile var service: FastSamRuntimeService<T>? = null
        private set
    @Volatile private var active = false
    @Volatile private var destroyed = false
    var status = Status.IDLE
        private set
    var backendName: String? = null
        private set
    private var retrySerial = 0L
    private var transferEpoch: Long? = null
    private var transferring = false
    // Futures contain release metadata only. A detached service/listener must not be retained here.
    private val retainedReleases = mutableListOf<CompletableFuture<Void>>()

    /** Reserve listener identity before target wrappers are attached. No model is created here. */
    fun reserveTransferredEpoch(): Long? {
        if (destroyed || transferring || transferEpoch != null || service != null) return null
        return nextEpoch.incrementAndGet().also { transferEpoch = it }
    }

    /** The caller has already confirmed the two-wrapper adoption and activated both consumers. */
    fun adoptTransferred(epoch: Long, prepared: FastSamRuntimeService<T>): Boolean {
        if (destroyed || transferring || transferEpoch != epoch || service != null ||
            prepared.sessionEpoch != epoch || !prepared.isReady) return false
        transferEpoch = null
        retrySerial++
        service = prepared
        retain(prepared)
        backendName = prepared.start().getNow(null)?.actualBackend?.name
        status = Status.READY
        if (active) changed()
        return true
    }

    fun cancelTransferredEpoch(epoch: Long) {
        if (transferEpoch == epoch) transferEpoch = null
    }

    /** Undo only this partially published target; a late rollback cannot remove another service. */
    fun rollbackTransferred(epoch: Long, expected: FastSamRuntimeService<T>): Boolean {
        if (service !== expected || expected.sessionEpoch != epoch) return false
        retrySerial++
        service = null
        backendName = null
        status = Status.FAILED
        expected.closeAsync()
        if (active && !destroyed) changed()
        return true
    }

    /** Detach the old listener/worker only; its native model remains owned by the pair ticket. */
    fun detachForTransfer(): CompletableFuture<FastSamTransferredModel> {
        val old = service ?: return CompletableFuture<FastSamTransferredModel>().also {
            it.completeExceptionally(IllegalStateException("No camera auxiliary consumer to transfer"))
        }
        check(!destroyed && !transferring && old.isReady)
        transferring = true
        service = null
        backendName = null
        retrySerial++
        status = Status.IDLE
        val receipt = old.releasedFuture()
        val ownerRef = WeakReference(this)
        val drained = old.drainForTransfer()
        drained.whenComplete { _, error ->
            if (error != null) old.closeAsync()
            // The physical receipt is retained until the Java consumer drain succeeds. Keeping
            // it afterwards would make source teardown wait for a model now owned by the target.
            ownerRef.get()?.let { owner ->
                if (error == null) synchronized(owner.retainedReleases) { owner.retainedReleases.remove(receipt) }
                owner.dispatch {
                    owner.transferring = false
                    if (error != null) {
                        if (!owner.destroyed) owner.status = Status.FAILED
                    }
                    if (owner.active && !owner.destroyed) owner.changed()
                }
            }
        }
        return drained
    }

    fun resume() {
        if (destroyed || active) return
        active = true
        if (service == null && status == Status.IDLE) startNew() else {
            if (service?.stats()?.stopping == true &&
                status != Status.WAITING_RELEASE && status != Status.WAITING_AVAILABILITY && status != Status.RELEASE_FAILED
            ) status = Status.FAILED
            changed()
        }
    }

    fun pause() {
        active = false
        retrySerial++ // A release callback cannot carry a retry into another foreground session.
        if (status == Status.WAITING_RELEASE || status == Status.WAITING_AVAILABILITY) status = Status.FAILED
        service?.discardPending("camera_test_paused")
    }

    /** Stop native admission on leaving CameraTest; a resumed owner waits for confirmed release. */
    fun pauseAndRelease() {
        pause()
        val old = service ?: return
        val request = ++retrySerial
        status = Status.WAITING_RELEASE
        old.closeAsync()
        val epoch = old.sessionEpoch
        val ownerRef = WeakReference(this)
        old.releasedFuture().whenComplete { _, error -> ownerRef.get()?.dispatch callback@{
            val owner = ownerRef.get() ?: return@callback
            val current = owner.service ?: return@callback
            if (owner.destroyed || request != owner.retrySerial || current.sessionEpoch != epoch) return@callback
            if (error != null || !current.stats().nativeReleaseConfirmed) {
                owner.status = Status.RELEASE_FAILED
                if (owner.active) owner.changed()
            } else {
                owner.service = null
                owner.backendName = null
                owner.status = Status.IDLE
                if (owner.active) owner.startNew()
            }
        } }
    }

    fun retry() {
        if (!active || destroyed) return
        val old = service ?: return startNew()
        if (!old.stats().stopping || status == Status.WAITING_RELEASE) return
        val request = ++retrySerial
        status = Status.WAITING_RELEASE
        changed()
        old.closeAsync()
        val epoch = old.sessionEpoch
        val ownerRef = WeakReference(this)
        old.releasedFuture().whenComplete { _, error -> ownerRef.get()?.dispatch callback@{
            val owner = ownerRef.get() ?: return@callback
            val current = owner.service ?: return@callback
            if (!owner.active || owner.destroyed || request != owner.retrySerial || current.sessionEpoch != epoch) return@callback
            if (error != null || !current.stats().nativeReleaseConfirmed) {
                owner.status = Status.RELEASE_FAILED
                owner.changed()
            } else owner.startNew()
        } }
    }

    fun failed(epoch: Long) = dispatch {
        if (!owns(epoch)) return@dispatch
        if (status != Status.WAITING_RELEASE && status != Status.WAITING_AVAILABILITY && status != Status.RELEASE_FAILED)
            status = Status.FAILED
        if (active) changed()
    }

    fun accepts(epoch: Long): Boolean {
        val current = service ?: return false
        return active && !destroyed && current.sessionEpoch == epoch && current.isReady
    }

    /** Physical receipts survive detaching a service from the camera's current slot. */
    fun retainedReleaseReceipts(): List<CompletableFuture<Void>> = synchronized(retainedReleases) {
        retainedReleases.removeAll { it.isDone && !it.isCompletedExceptionally && !it.isCancelled }
        retainedReleases.toList()
    }

    fun destroy() {
        active = false
        destroyed = true
        retrySerial++
        transferEpoch = null
        service?.closeAsync()
        service = null
    }

    private fun owns(epoch: Long) = !destroyed && service?.sessionEpoch == epoch

    private fun startNew() {
        if (!active || destroyed) return
        if (requiresTransferredPair || transferring || transferEpoch != null) {
            status = Status.IDLE
            changed()
            return
        }
        service = null // Retired callbacks lose ownership before the replacement is constructed.
        backendName = null
        val created = try { create(nextEpoch.incrementAndGet()) } catch (error: Exception) {
            if (waitForAvailability(null, error)) return
            status = Status.FAILED
            changed()
            return
        }
        retain(created)
        service = created
        status = Status.LOADING
        changed()
        val epoch = created.sessionEpoch
        val ownerRef = WeakReference(this)
        try {
            created.start().whenComplete { info, error -> ownerRef.get()?.dispatch callback@{
                val owner = ownerRef.get() ?: return@callback
                if (!owner.owns(epoch)) return@callback
                if (error != null && owner.status != Status.WAITING_RELEASE &&
                    owner.status != Status.RELEASE_FAILED && owner.waitForAvailability(epoch, error)) return@callback
                owner.backendName = info?.actualBackend?.name
                if (owner.status != Status.WAITING_RELEASE && owner.status != Status.WAITING_AVAILABILITY &&
                    owner.status != Status.RELEASE_FAILED) {
                    owner.status = if (error == null && owner.service?.stats()?.stopping == false) Status.READY else Status.FAILED
                }
                if (owner.active) owner.changed()
            } }
        } catch (error: Exception) {
            created.closeAsync()
            if (waitForAvailability(epoch, error)) return
            status = Status.FAILED
            changed()
        }
    }

    private fun retain(created: FastSamRuntimeService<T>) = synchronized(retainedReleases) {
        retainedReleases.removeAll { it.isDone && !it.isCompletedExceptionally && !it.isCancelled }
        retainedReleases += created.releasedFuture()
    }

    /** A foreign cohort is availability, not a new model failure or permission to reuse its receipt. */
    private fun waitForAvailability(epoch: Long?, error: Throwable): Boolean {
        val busy = VisionRuntimeFailures.busy(error) ?: return false
        if (!active || destroyed) return true
        val request = ++retrySerial
        status = Status.WAITING_AVAILABILITY
        changed()
        val current = service
        current?.closeAsync()
        val ownRelease = current?.releasedFuture() ?: CompletableFuture.completedFuture<Void>(null)
        val ownerRef = WeakReference(this)
        CompletableFuture.allOf(ownRelease, busy.availabilityReceipt.toCompletableFuture())
            .whenComplete { _, releaseError -> ownerRef.get()?.dispatch callback@{
                val owner = ownerRef.get() ?: return@callback
                if (!owner.active || owner.destroyed || request != owner.retrySerial ||
                    owner.service?.sessionEpoch != epoch) return@callback
                if (releaseError != null) {
                    owner.status = Status.RELEASE_FAILED
                    owner.changed()
                } else owner.startNew()
            } }
        return true
    }

    private companion object {
        val nextEpoch = AtomicLong()
    }
}
