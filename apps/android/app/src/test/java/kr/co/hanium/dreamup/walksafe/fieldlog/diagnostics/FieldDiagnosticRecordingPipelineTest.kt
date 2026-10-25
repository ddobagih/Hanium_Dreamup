package kr.co.hanium.dreamup.walksafe.fieldlog.diagnostics

import java.util.concurrent.*
import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry
import kr.co.hanium.dreamup.walksafe.fieldlog.*
import org.junit.Assert.*
import org.junit.Test

class FieldDiagnosticRecordingPipelineTest {
    @Test fun constructorAndMissingHeartbeatDoNotReadLogSensorsOrClock() {
        val f = Fixture()
        f.pipeline.onRenderCallback(); f.pipeline.onFrameProduced()
        assertEquals(0, f.logReads); assertEquals(0, f.clockReads); assertEquals(0, f.factories)
        assertEquals("capture log: empty", f.pipeline.metadataSummaryText())
    }
    @Test fun destroyBeforeStartupWinsTheOriginalLockAndCannotCreateAWorker() {
        val f = Fixture()
        f.pipeline.closeHeartbeat(); f.pipeline.startFieldSessionHeartbeat()
        assertEquals(0, f.factories); assertEquals(0, f.logReads)
        assertNull(f.timer.task)
    }
    @Test fun startIsOneShotAndActualHeartbeatRetainsGapAndRenderTimestamps() {
        val f = Fixture()
        f.pipeline.startFieldSessionHeartbeat(); f.pipeline.startFieldSessionHeartbeat()
        assertEquals(1, f.factories); assertEquals(1, f.logReads)
        assertEquals(1_000L, f.timer.initial); assertEquals(1_000L, f.timer.delay)
        f.now = 100L; f.timer.task!!.run()
        assertEquals("RENDERING_STOPPED", f.events.last()["state"])
        f.now = 120L; f.pipeline.onRenderCallback(); f.timer.task!!.run()
        assertEquals("NO_FRAME", f.events.last()["state"])
        f.now = 140L; f.pipeline.onFrameProduced(); f.timer.task!!.run()
        assertEquals("FRAMES_FLOWING", f.events.last()["state"])
        assertEquals(40L, f.events.last()["gap_duration_ms"])
        f.now = 141L; f.timer.task!!.run()
        assertEquals(3, f.events.size)
    }
    @Test fun tickExceptionsStayContainedAndCloseRetiresStampsAndWorkerInOrder() {
        val f = Fixture()
        f.pipeline.startFieldSessionHeartbeat()
        f.throwSensors = true; f.timer.task!!.run()
        assertTrue(f.events.isEmpty())
        f.pipeline.closeHeartbeat()
        assertTrue(f.timer.closed)
        val reads = f.clockReads
        f.pipeline.onFrameProduced(); f.pipeline.onRenderCallback(); f.pipeline.startFieldSessionHeartbeat()
        assertEquals(reads, f.clockReads); assertEquals(1, f.factories)
    }
    @Test fun shutdownFailurePreservesTheOriginalCauseAndDoesNotPretendTheTailCleared() {
        val f=Fixture();f.pipeline.startFieldSessionHeartbeat()
        val failure=IllegalStateException("native timer shutdown");f.timer.shutdownFailure=failure
        try { f.pipeline.closeHeartbeat();fail("shutdown failure must propagate") }
        catch (seen:IllegalStateException) { assertSame(failure,seen) }
        f.pipeline.startFieldSessionHeartbeat();assertEquals(1,f.factories)
        val reads=f.clockReads;f.pipeline.onRenderCallback()
        assertEquals(reads+1,f.clockReads) // Original exceptional tail kept the heartbeat pointer.
    }
    @Test fun failedSchedulingRetainsThePublishedExecutorInsteadOfCreatingASecondTimer() {
        val f=Fixture();val failure=IllegalStateException("schedule rejected");f.timer.scheduleFailure=failure
        try { f.pipeline.startFieldSessionHeartbeat();fail("scheduling failure must propagate") }
        catch (seen:IllegalStateException) { assertSame(failure,seen) }
        f.pipeline.startFieldSessionHeartbeat();assertEquals(1,f.factories)
        f.pipeline.closeHeartbeat();assertTrue(f.timer.closed)
    }
    @Test fun admittedMetadataUsesTheActualBoundedCacheAndClearDoesNotRestartHeartbeat() {
        val f = Fixture()
        for (i in 1L..35L) f.pipeline.appendMetadata(entry(i))
        val text = f.pipeline.metadataSummaryText()
        assertFalse(text.contains("frameTs=1 ")); assertTrue(text.contains("frameTs=35 "))
        assertEquals(3, text.lineSequence().count())
        f.pipeline.clearMetadata()
        assertEquals("capture log: empty", f.pipeline.metadataSummaryText())
        assertEquals(0, f.factories)
    }
    private class Fixture {
        val lock = Any(); val timer = Timer(lock)
        var logReads = 0; var clockReads = 0; var factories = 0; var now = 0L; var throwSensors = false
        val events = mutableListOf<Map<String, Any?>>()
        val log = object : FieldSessionLog by NoopFieldSessionLog() {
            override fun isActive() = true
            override fun recordEvent(name: String, fields: Map<String, Any?>) { assertEquals("field_heartbeat",name); events += fields }
        }
        val pipeline = FieldDiagnosticRecordingPipeline({ logReads++; assertTrue(Thread.holdsLock(lock)); log }, { at ->
            assertEquals(now, at); if (throwSensors) throw IllegalStateException("sensor read")
            FieldHeartbeatSensors(7,true,10L,true,"ESTABLISHED")
        }, { clockReads++; now }, lock, { factories++; assertTrue(Thread.holdsLock(lock)); timer })
    }
    private class Timer(private val lock: Any) : ScheduledThreadPoolExecutor(1) {
        var task: Runnable? = null; var initial = -1L; var delay = -1L; var closed = false
        var shutdownFailure:RuntimeException?=null;var scheduleFailure:RuntimeException?=null
        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            assertTrue(Thread.holdsLock(lock)); assertEquals(TimeUnit.MILLISECONDS, unit)
            scheduleFailure?.let { throw it }
            task = command; initial = initialDelay; this.delay = delay
            return object : FutureTask<Unit>({}, Unit), ScheduledFuture<Unit> {
                override fun getDelay(unit: TimeUnit) = 0L
                override fun compareTo(other: Delayed) = 0
            }
        }
        override fun shutdownNow(): MutableList<Runnable> { assertTrue(Thread.holdsLock(lock)); closed = true; shutdownFailure?.let { throw it }; return mutableListOf() }
    }
    private fun entry(at: Long) = MetadataCaptureLogEntry(at,null,null,detectionCount=0,detectionsUsedForDepth=false,
        staleReason=null,topDetectionClassName=null,topDetectionConfidence=null,topDetectionBbox=null,
        bestDepthClassName=null,bestDepthSource=null,bestDepthMedianM=null,bestDepthValidSampleCount=null,
        bestDepthValidSampleRatio=null,bestDepthBbox=null)
}
