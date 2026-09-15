package kr.co.hanium.dreamup.walksafe

import java.util.concurrent.atomic.AtomicLong
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService

/** Main-thread owner. A failed model is replaced only by an explicit, still-active retry. */
internal class CameraUnknownRuntime<T>(
    private val create: (Long) -> FastSamRuntimeService<T>,
    private val dispatch: (() -> Unit) -> Unit,
    private val changed: () -> Unit,
) {
    enum class Status { IDLE, LOADING, READY, FAILED, WAITING_RELEASE, RELEASE_FAILED }

    @Volatile var service: FastSamRuntimeService<T>? = null
        private set
    @Volatile private var active = false
    @Volatile private var destroyed = false
    var status = Status.IDLE
        private set
    var backendName: String? = null
        private set
    private var retrySerial = 0L

    fun resume() {
        if (destroyed) return
        active = true
        if (service == null && status == Status.IDLE) startNew() else {
            if (service?.stats()?.stopping == true && status != Status.RELEASE_FAILED) status = Status.FAILED
            changed()
        }
    }

    fun pause() {
        active = false
        retrySerial++ // A release callback cannot carry a retry into another foreground session.
        if (status == Status.WAITING_RELEASE) status = Status.FAILED
        service?.discardPending("camera_test_paused")
    }

    fun retry() {
        if (!active || destroyed) return
        val old = service ?: return startNew()
        if (!old.stats().stopping || status == Status.WAITING_RELEASE) return
        val request = ++retrySerial
        status = Status.WAITING_RELEASE
        changed()
        old.closeAsync()
        old.releasedFuture().whenComplete { _, error -> dispatch {
            if (!active || destroyed || request != retrySerial || service !== old) return@dispatch
            if (error != null || !old.stats().nativeReleaseConfirmed) {
                status = Status.RELEASE_FAILED
                changed()
            } else startNew()
        } }
    }

    fun failed(epoch: Long) = dispatch {
        if (!owns(epoch)) return@dispatch
        if (status != Status.WAITING_RELEASE && status != Status.RELEASE_FAILED) status = Status.FAILED
        if (active) changed()
    }

    fun accepts(epoch: Long): Boolean = active && owns(epoch) && service?.stats()?.stopping == false

    fun destroy() {
        active = false
        destroyed = true
        retrySerial++
        service?.closeAsync()
        service = null
    }

    private fun owns(epoch: Long) = !destroyed && service?.sessionEpoch == epoch

    private fun startNew() {
        if (!active || destroyed) return
        service = null // Retired callbacks lose ownership before the replacement is constructed.
        backendName = null
        val created = try { create(nextEpoch.incrementAndGet()) } catch (_: Exception) {
            status = Status.FAILED
            changed()
            return
        }
        service = created
        status = Status.LOADING
        changed()
        try {
            created.start().whenComplete { info, error -> dispatch {
                if (!owns(created.sessionEpoch)) return@dispatch
                backendName = info?.actualBackend?.name
                if (status != Status.WAITING_RELEASE && status != Status.RELEASE_FAILED) {
                    status = if (error == null && !created.stats().stopping) Status.READY else Status.FAILED
                }
                if (active) changed()
            } }
        } catch (_: Exception) {
            created.closeAsync()
            status = Status.FAILED
            changed()
        }
    }

    private companion object {
        val nextEpoch = AtomicLong()
    }
}
