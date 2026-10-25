package kr.co.hanium.dreamup.walksafe.fieldlog.diagnostics

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.MetadataCaptureLog
import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry
import kr.co.hanium.dreamup.walksafe.fieldlog.*

/** Independent heartbeat lifecycle and bounded metadata history; constructors read no live source. */
internal class FieldDiagnosticRecordingPipeline(
    private val log: () -> FieldSessionLog,
    private val heartbeatSensors: (Long) -> FieldHeartbeatSensors,
    private val clock: () -> Long,
    private val heartbeatLock: Any,
    private val createExecutor: () -> ScheduledExecutorService = {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "walksafe-field-heartbeat").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    },
) {
    private val captureLog = MetadataCaptureLog()
    @Volatile private var fieldSessionHeartbeat: FieldSessionHeartbeat? = null
    private var fieldHeartbeatDestroyed = false // guarded by heartbeatLock
    private var fieldSessionHeartbeatExecutor: ScheduledExecutorService? = null // guarded by heartbeatLock

    fun startFieldSessionHeartbeat() {
        synchronized(heartbeatLock) {
            // Startup inspection runs off main; destroy may already have won this same lock.
            if (fieldHeartbeatDestroyed || fieldSessionHeartbeatExecutor != null) return
            val heartbeat = FieldSessionHeartbeat(log())
            val executor = createExecutor()
            fieldSessionHeartbeat = heartbeat
            fieldSessionHeartbeatExecutor = executor
            executor.scheduleWithFixedDelay({
                runCatching {
                    val now = clock()
                    heartbeat.tick(now, heartbeatSensors(now))
                }
            }, FIELD_HEARTBEAT_INTERVAL_MS, FIELD_HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun onRenderCallback() { fieldSessionHeartbeat?.onRenderCallback(clock()) }
    fun onFrameProduced() { fieldSessionHeartbeat?.onFrameProduced(clock()) }

    fun closeHeartbeat() {
        synchronized(heartbeatLock) {
            fieldHeartbeatDestroyed = true
            fieldSessionHeartbeatExecutor?.shutdownNow()
            fieldSessionHeartbeatExecutor = null
            fieldSessionHeartbeat = null
        }
    }

    /** The admitted caller still holds the original frameStateLock and owns upload authorization. */
    fun appendMetadata(entry: MetadataCaptureLogEntry) { captureLog.append(entry) }
    fun clearMetadata() { captureLog.clear() }
    fun metadataSummaryText() = captureLog.summaryText()
}
