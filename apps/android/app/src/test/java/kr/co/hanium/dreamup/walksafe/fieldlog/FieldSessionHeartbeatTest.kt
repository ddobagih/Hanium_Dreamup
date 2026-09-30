package kr.co.hanium.dreamup.walksafe.fieldlog

import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16-17 #37: periodic field evidence without ARCore rendering. */
class FieldSessionHeartbeatTest {
    @Test
    fun withoutAnyRenderCallbackTheGapIsRecordedOnChangeAndEveryTenSecondsWithItsDuration() {
        val log = RecordingFieldLog()
        val heartbeat = FieldSessionHeartbeat(log)
        (1..10).forEach { heartbeat.tick(it * 1_000L, SENSORS) }

        // S1617-37-F2: one gap event per state change, not one per second.
        assertEquals(1, log.events.size)
        assertEquals(FieldHeartbeatState.RENDERING_STOPPED, heartbeat.tick(11_000L, SENSORS))
        assertEquals(2, log.events.size)
        assertTrue(log.events.all { it.first == "field_heartbeat" && it.second["state"] == "RENDERING_STOPPED" })
        assertEquals(listOf(0L, 10_000L), log.events.map { it.second["gap_duration_ms"] })
        assertEquals(42, log.events.last().second["step_count"])
        assertEquals(true, log.events.last().second["trusted_location_available"])
        assertNull(log.events.last().second["last_frame_age_ms"])
    }

    @Test
    fun renderLoopWithoutFramesIsNoFrameAndResumedFramesAreRecordedOnce() {
        val log = RecordingFieldLog()
        val heartbeat = FieldSessionHeartbeat(log)
        heartbeat.onRenderCallback(900L)
        assertEquals(FieldHeartbeatState.NO_FRAME, heartbeat.tick(1_000L, SENSORS))
        heartbeat.onFrameProduced(1_900L)
        assertEquals(FieldHeartbeatState.FRAMES_FLOWING, heartbeat.tick(2_000L, SENSORS))
        heartbeat.onFrameProduced(2_900L)
        assertNull(heartbeat.tick(3_000L, SENSORS))
        // The render loop stalls: after the stale limit the gap is recorded on change, then every 10 s.
        assertEquals(FieldHeartbeatState.RENDERING_STOPPED, heartbeat.tick(6_000L, SENSORS))
        assertNull(heartbeat.tick(7_000L, SENSORS))
        assertNull(heartbeat.tick(15_000L, SENSORS))
        assertEquals(FieldHeartbeatState.RENDERING_STOPPED, heartbeat.tick(16_000L, SENSORS))
        assertEquals(
            listOf("NO_FRAME", "FRAMES_FLOWING", "RENDERING_STOPPED", "RENDERING_STOPPED"),
            log.events.map { it.second["state"] },
        )
        // The resumed-frames record carries the length of the gap it ended.
        assertEquals(listOf(0L, 1_000L, 0L, 10_000L), log.events.map { it.second["gap_duration_ms"] })
        assertEquals(13_100L, log.events.last().second["last_frame_age_ms"])
    }

    @Test
    fun aDifferentGapStateIsRecordedImmediatelyWithinOneContinuousGap() {
        val log = RecordingFieldLog()
        val heartbeat = FieldSessionHeartbeat(log)
        assertEquals(FieldHeartbeatState.RENDERING_STOPPED, heartbeat.tick(1_000L, SENSORS))
        heartbeat.onRenderCallback(1_500L)
        assertEquals(FieldHeartbeatState.NO_FRAME, heartbeat.tick(2_000L, SENSORS))
        assertNull(heartbeat.tick(3_000L, SENSORS.copy(stepCount = 43)))
        // Still one continuous observation gap since 1 s.
        assertEquals(listOf(0L, 1_000L), log.events.map { it.second["gap_duration_ms"] })
    }

    @Test
    fun framesWithoutTrustedLocationAreSensorNotReady() {
        val heartbeat = FieldSessionHeartbeat(RecordingFieldLog())
        heartbeat.onFrameProduced(1_000L)
        assertEquals(
            FieldHeartbeatState.SENSOR_NOT_READY,
            heartbeat.classify(1_500L, SENSORS.copy(trustedLocationAvailable = false, locationAgeMs = null)),
        )
    }

    @Test
    fun noHeartbeatIsWrittenWhenTheFieldSessionIsNotActive() {
        val log = RecordingFieldLog(active = false)
        val heartbeat = FieldSessionHeartbeat(log)
        assertNull(heartbeat.tick(1_000L, SENSORS))
        assertTrue(log.events.isEmpty())
        log.active = true
        assertEquals(FieldHeartbeatState.RENDERING_STOPPED, heartbeat.tick(2_000L, SENSORS))
        log.active = false
        assertNull(heartbeat.tick(3_000L, SENSORS))
        assertEquals(1, log.events.size)
    }

    @Test
    fun heartbeatFieldsCarryNoCoordinatesOrText() {
        val log = RecordingFieldLog()
        FieldSessionHeartbeat(log).tick(1_000L, SENSORS)
        assertEquals(
            setOf(
                "state", "last_frame_age_ms", "last_render_age_ms", "step_count",
                "trusted_location_available", "location_age_ms", "route_active", "gap_duration_ms",
            ),
            log.events.single().second.keys,
        )
        assertFalse(log.events.single().second.keys.any { it.contains("lat") || it.contains("lon") })
    }

    /** S09-20 (w25 review pos-F10): the Counter baseline state is an enum name next to the step count. */
    @Test
    fun theStepCounterBaselineStateIsRecordedAsAnEnumNameWhenKnown() {
        val log = RecordingFieldLog()
        FieldSessionHeartbeat(log).tick(1_000L, SENSORS.copy(stepCounterBaseline = "NOT_ESTABLISHED"))
        val fields = log.events.single().second
        assertEquals("NOT_ESTABLISHED", fields["step_counter_baseline"])
        assertEquals(
            setOf(
                "state", "last_frame_age_ms", "last_render_age_ms", "step_count", "step_counter_baseline",
                "trusted_location_available", "location_age_ms", "route_active", "gap_duration_ms",
            ),
            fields.keys,
        )
    }

    private class RecordingFieldLog(var active: Boolean = true) : FieldSessionLog {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun start(): String? = null
        override fun startAfterUserConfirmation(): String? = null
        override fun stop(): String? = null
        override fun isActive(): Boolean = active
        override fun activeSessionId(): String? = null
        override fun appendTelemetry(entry: MetadataCaptureLogEntry, runtime: FieldRuntimeSnapshot) = Unit
        override fun appendCameraNonMetricSample(sample: CameraNonMetricFieldSample) = Unit
        override fun recordEvent(name: String, fields: Map<String, Any?>) {
            events += name to fields
        }
        override fun statusText(): String = ""
        override fun purgeAll(): Boolean = true
        override fun close() = Unit
    }

    private companion object {
        val SENSORS = FieldHeartbeatSensors(
            stepCount = 42,
            trustedLocationAvailable = true,
            locationAgeMs = 300L,
            routeActive = false,
        )
    }
}
