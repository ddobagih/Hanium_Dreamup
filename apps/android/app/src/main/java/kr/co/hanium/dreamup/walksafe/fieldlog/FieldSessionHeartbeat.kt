package kr.co.hanium.dreamup.walksafe.fieldlog

/** What the debug field session observed when the render-independent heartbeat fired. */
enum class FieldHeartbeatState {
    FRAMES_FLOWING,
    NO_FRAME,
    SENSOR_NOT_READY,
    RENDERING_STOPPED,
}

/** Non-identifying sensor liveness only: no coordinates, route geometry or user input. */
data class FieldHeartbeatSensors(
    val stepCount: Int,
    val trustedLocationAvailable: Boolean,
    val locationAgeMs: Long?,
    val routeActive: Boolean,
    /**
     * S09-20 (w25 review pos-F10): enum name of the step tracker's Counter baseline state
     * (NOT_REGISTERED / NOT_ESTABLISHED / ESTABLISHED); omitted from the record when unknown.
     */
    val stepCounterBaseline: String? = null,
)

/**
 * Rendering-independent liveness record for the debug field log (16-17 #37).
 *
 * The periodic telemetry sample is produced by onDrawFrame, so a session whose ARCore render loop
 * never starts or stalls used to leave no periodic evidence at all. A separate timer calls [tick];
 * the GL thread only stamps [onRenderCallback] / [onFrameProduced]. While frames flow the per-frame
 * telemetry already covers the session, so FRAMES_FLOWING is written only when it (re)starts. Gap
 * states are written on every state change and then every [FIELD_HEARTBEAT_GAP_REPEAT_MS] with
 * `gap_duration_ms`, so the observation gap itself is recorded (FP-052) without one event per second
 * (S1617-37-F2). The FRAMES_FLOWING record carries the length of the gap it ended.
 */
class FieldSessionHeartbeat(
    private val log: FieldSessionLog,
    private val staleAfterMs: Long = FIELD_HEARTBEAT_STALE_AFTER_MS,
) {
    @Volatile
    private var lastRenderCallbackElapsedMs: Long? = null

    @Volatile
    private var lastFrameElapsedMs: Long? = null
    private var lastRecordedState: FieldHeartbeatState? = null
    private var lastRecordedAtElapsedMs: Long? = null
    private var gapStartedAtElapsedMs: Long? = null

    init {
        require(staleAfterMs > 0L)
    }

    fun onRenderCallback(nowElapsedMs: Long) {
        lastRenderCallbackElapsedMs = nowElapsedMs
    }

    fun onFrameProduced(nowElapsedMs: Long) {
        lastRenderCallbackElapsedMs = nowElapsedMs
        lastFrameElapsedMs = nowElapsedMs
    }

    fun classify(nowElapsedMs: Long, sensors: FieldHeartbeatSensors): FieldHeartbeatState {
        val render = lastRenderCallbackElapsedMs
        val frame = lastFrameElapsedMs
        return when {
            render == null || nowElapsedMs - render > staleAfterMs -> FieldHeartbeatState.RENDERING_STOPPED
            frame == null || nowElapsedMs - frame > staleAfterMs -> FieldHeartbeatState.NO_FRAME
            !sensors.trustedLocationAvailable -> FieldHeartbeatState.SENSOR_NOT_READY
            else -> FieldHeartbeatState.FRAMES_FLOWING
        }
    }

    /** Returns the recorded state, or null when nothing was written. */
    @Synchronized
    fun tick(nowElapsedMs: Long, sensors: FieldHeartbeatSensors): FieldHeartbeatState? {
        if (!log.isActive()) {
            lastRecordedState = null
            lastRecordedAtElapsedMs = null
            gapStartedAtElapsedMs = null
            return null
        }
        val state = classify(nowElapsedMs, sensors)
        val changed = state != lastRecordedState
        val gapDurationMs: Long?
        if (state == FieldHeartbeatState.FRAMES_FLOWING) {
            if (!changed) return null
            gapDurationMs = gapStartedAtElapsedMs?.let { (nowElapsedMs - it).coerceAtLeast(0L) }
            gapStartedAtElapsedMs = null
        } else {
            val lastRecordedAt = lastRecordedAtElapsedMs
            if (
                !changed &&
                lastRecordedAt != null &&
                nowElapsedMs - lastRecordedAt in 0L until FIELD_HEARTBEAT_GAP_REPEAT_MS
            ) return null
            val gapStartedAt = gapStartedAtElapsedMs ?: nowElapsedMs.also { gapStartedAtElapsedMs = it }
            gapDurationMs = (nowElapsedMs - gapStartedAt).coerceAtLeast(0L)
        }
        lastRecordedState = state
        lastRecordedAtElapsedMs = nowElapsedMs
        log.recordEvent(
            "field_heartbeat",
            mapOf(
                "state" to state.name,
                "last_frame_age_ms" to lastFrameElapsedMs?.let { (nowElapsedMs - it).coerceAtLeast(0L) },
                "last_render_age_ms" to lastRenderCallbackElapsedMs?.let {
                    (nowElapsedMs - it).coerceAtLeast(0L)
                },
                "step_count" to sensors.stepCount,
                "trusted_location_available" to sensors.trustedLocationAvailable,
                "location_age_ms" to sensors.locationAgeMs,
                "route_active" to sensors.routeActive,
                "gap_duration_ms" to gapDurationMs,
            ) + listOfNotNull(sensors.stepCounterBaseline?.let { "step_counter_baseline" to it }),
        )
        return state
    }
}

/** An unchanged gap state is re-recorded at this period (with its duration), not every tick. */
const val FIELD_HEARTBEAT_GAP_REPEAT_MS = 10_000L

/** Same period as the onDrawFrame telemetry sample (PersistentFieldSessionLog 1 s). */
const val FIELD_HEARTBEAT_INTERVAL_MS = 1_000L

/** A render/frame stamp older than three heartbeat periods is a gap, not jitter. */
const val FIELD_HEARTBEAT_STALE_AFTER_MS = 3 * FIELD_HEARTBEAT_INTERVAL_MS
