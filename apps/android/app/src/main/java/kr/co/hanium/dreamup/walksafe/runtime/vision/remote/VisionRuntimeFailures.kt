package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

/** CompletableFuture/adapter wrappers must not turn resource contention into bad model evidence. */
internal object VisionRuntimeFailures {
    fun busy(error: Throwable?): VisionCohortBusyException? {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current = error
        while (current != null && visited.add(current)) {
            if (current is VisionCohortBusyException) return current
            current = current.cause
        }
        return null
    }
}
