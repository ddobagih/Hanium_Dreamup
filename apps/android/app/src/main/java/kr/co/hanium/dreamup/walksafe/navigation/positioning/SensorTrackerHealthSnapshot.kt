package kr.co.hanium.dreamup.walksafe.navigation.positioning

import java.util.Collections

/** Producer facts, not a permission check or proof that a registered sensor is still measuring. */
data class SensorTrackerHealthSnapshot(
    val registrationGeneration: Int,
    val lifecycle: SensorTrackerLifecycle,
    val stoppedReason: SensorTrackerStoppedReason?,
    val cleanupPending: Boolean,
    val sources: List<SensorSourceHealth>,
) {
    fun source(source: TrackedSensorSource): SensorSourceHealth? = sources.firstOrNull { it.source == source }
}

enum class SensorTrackerLifecycle { IDLE, STARTING, ACTIVE, STOPPED, CLOSED }
enum class SensorTrackerStoppedReason { EXPLICIT_STOP, FAILED_START, CLOSED }
enum class TrackedSensorSource { STEP_COUNTER, STEP_DETECTOR, ACCELEROMETER, GYROSCOPE }
enum class SensorRegistrationOutcome {
    NOT_ATTEMPTED, UNAVAILABLE, REGISTERED, RETURNED_FALSE, SECURITY_EXCEPTION, RUNTIME_EXCEPTION,
}

data class SensorSourceHealth(
    val source: TrackedSensorSource,
    /** Null until this producer probes availability; this does not describe permission. */
    val available: Boolean?,
    /** The last attempt's outcome survives cleanup, separately from current input admission. */
    val lastRegistrationOutcome: SensorRegistrationOutcome,
    val acceptingSamples: Boolean,
    val lastAcceptedSample: AcceptedSensorSample?,
)

data class AcceptedSensorSample(
    val producerRegistrationGeneration: Int,
    val measurementElapsedRealtimeNanos: Long,
    /** Actual callback receipt, or null when the original receipt is unknown. */
    val receivedAtElapsedRealtimeNanos: Long?,
)

/** Both Android adapters publish one immutable snapshot on their existing serial sensor context. */
internal class SensorTrackerHealthRecorder(sources: List<TrackedSensorSource>) {
    @Volatile
    var snapshot = SensorTrackerHealthSnapshot(
        registrationGeneration = 0,
        lifecycle = SensorTrackerLifecycle.IDLE,
        stoppedReason = null,
        cleanupPending = false,
        sources = immutable(sources.map {
            SensorSourceHealth(it, null, SensorRegistrationOutcome.NOT_ATTEMPTED, false, null)
        }),
    )
        private set

    fun beginAttempt(generation: Int) {
        snapshot = snapshot.copy(
            registrationGeneration = generation,
            lifecycle = SensorTrackerLifecycle.STARTING,
            stoppedReason = null,
            cleanupPending = false,
            sources = immutable(snapshot.sources.map {
                it.copy(available = null, lastRegistrationOutcome = SensorRegistrationOutcome.NOT_ATTEMPTED,
                    acceptingSamples = false, lastAcceptedSample = null)
            }),
        )
    }

    fun availability(source: TrackedSensorSource, available: Boolean) {
        updateSource(source) {
            it.copy(available = available, lastRegistrationOutcome = if (available) {
                SensorRegistrationOutcome.NOT_ATTEMPTED
            } else SensorRegistrationOutcome.UNAVAILABLE)
        }
    }

    fun generationStarted(generation: Int) {
        snapshot = snapshot.copy(registrationGeneration = generation)
    }

    fun register(source: TrackedSensorSource, attempt: () -> Boolean): Boolean {
        val registered = try {
            attempt()
        } catch (failure: RuntimeException) {
            updateSource(source) { it.copy(lastRegistrationOutcome = if (failure is SecurityException) {
                SensorRegistrationOutcome.SECURITY_EXCEPTION
            } else SensorRegistrationOutcome.RUNTIME_EXCEPTION) }
            throw failure
        }
        updateSource(source) { it.copy(lastRegistrationOutcome = if (registered) {
            SensorRegistrationOutcome.REGISTERED
        } else SensorRegistrationOutcome.RETURNED_FALSE) }
        return registered
    }

    fun activate() {
        snapshot = snapshot.copy(
            lifecycle = SensorTrackerLifecycle.ACTIVE,
            sources = immutable(snapshot.sources.map {
                it.copy(acceptingSamples = it.lastRegistrationOutcome == SensorRegistrationOutcome.REGISTERED)
            }),
        )
    }

    fun acceptedSample(source: TrackedSensorSource, generation: Int, measurementNs: Long, receiptNs: Long?) {
        if (snapshot.lifecycle != SensorTrackerLifecycle.ACTIVE ||
            snapshot.registrationGeneration != generation || snapshot.source(source)?.acceptingSamples != true
        ) return
        updateSource(source) {
            it.copy(lastAcceptedSample = AcceptedSensorSample(generation, measurementNs, receiptNs))
        }
    }

    fun retire(generation: Int, reason: SensorTrackerStoppedReason, cleanupPending: Boolean) {
        snapshot = snapshot.copy(
            registrationGeneration = generation,
            lifecycle = if (reason == SensorTrackerStoppedReason.CLOSED) {
                SensorTrackerLifecycle.CLOSED
            } else SensorTrackerLifecycle.STOPPED,
            stoppedReason = reason,
            cleanupPending = cleanupPending,
            sources = immutable(snapshot.sources.map { it.copy(acceptingSamples = false, lastAcceptedSample = null) }),
        )
    }

    private fun updateSource(source: TrackedSensorSource, update: (SensorSourceHealth) -> SensorSourceHealth) {
        snapshot = snapshot.copy(sources = immutable(snapshot.sources.map { if (it.source == source) update(it) else it }))
    }

    private companion object {
        fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
    }
}
