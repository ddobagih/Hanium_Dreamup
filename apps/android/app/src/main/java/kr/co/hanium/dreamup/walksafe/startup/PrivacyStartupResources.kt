package kr.co.hanium.dreamup.walksafe.startup

/**
 * Owns acquired startup resources, including incomplete preparation and ready publication.
 * Factories run on their caller's lane; cleanup stays on that lane and captures the instance,
 * never a mutable Activity field. Storage bindings without a close operation are not deleted.
 */
internal class PrivacyStartupResources {
    private class Entry(val resource: Any, val release: () -> Unit)
    private class Lane {
        val lock = Any()
        var closed = false
        val entries = mutableListOf<Entry>()
    }
    private val worker = Lane()
    private val ui = Lane()

    fun prepareOnUiThread(prepare: () -> Unit) {
        try { prepare() } catch (failure: Throwable) {
            runCatching { closeOnUiThread() }.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
    }

    fun <T : Any> acquireOnWorker(create: () -> T, release: (T) -> Unit): T = acquire(worker, create, release)
    fun <T : Any> acquireOnUiThread(create: () -> T, release: (T) -> Unit): T = acquire(ui, create, release)

    private fun <T : Any> acquire(lane: Lane, create: () -> T, release: (T) -> Unit): T {
        synchronized(lane.lock) { check(!lane.closed) { "Startup resources are closed" } }
        val resource = create()
        val accepted = synchronized(lane.lock) {
            if (lane.closed) false else {
                lane.entries += Entry(resource) { release(resource) }
                true
            }
        }
        if (!accepted) {
            val closed = IllegalStateException("Startup resources closed during acquisition")
            runCatching { release(resource) }.exceptionOrNull()?.let(closed::addSuppressed)
            throw closed
        }
        return resource
    }

    /** Replacing a readiness probe retires only the original instance, once. */
    fun releaseOnUiThread(resource: Any) = release(ui, resource)
    fun releaseOnWorker(resource: Any) = release(worker, resource)

    private fun release(lane: Lane, resource: Any) {
        val entry = synchronized(lane.lock) {
            val index = lane.entries.indexOfFirst { it.resource === resource }
            if (index < 0) null else lane.entries.removeAt(index)
        }
        entry?.release?.invoke()
    }

    fun closeOnWorker() = close(worker)
    fun closeOnUiThread() = close(ui)

    private fun close(lane: Lane) {
        val entries = synchronized(lane.lock) {
            if (lane.closed) return
            lane.closed = true
            lane.entries.toList().also { lane.entries.clear() }
        }
        var failure: Throwable? = null
        entries.forEach { entry ->
            try { entry.release() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
