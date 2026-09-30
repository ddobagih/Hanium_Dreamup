package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16-17 #37 wiring: the heartbeat timer is not driven by onDrawFrame and exists only in debug. */
class MainActivityFieldHeartbeatStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun heartbeatRunsOnItsOwnScheduledThreadStartedOnlyForDebugFieldLogs() {
        val starter = source.substringAfter("private fun startFieldSessionHeartbeat()")
            .substringBefore("override fun onDrawFrame(gl: GL10?)")
        assertTrue(source.contains("if (BuildConfig.DEBUG) startFieldSessionHeartbeat()"))
        assertTrue(starter.contains("newSingleThreadScheduledExecutor"))
        assertTrue(starter.contains("\"walksafe-field-heartbeat\""))
        assertTrue(starter.contains("scheduleWithFixedDelay("))
        assertTrue(starter.contains("FIELD_HEARTBEAT_INTERVAL_MS"))
        assertFalse(starter.contains("freshTrustedLocationOrNull("))
        listOf("latitude", "longitude", "currentDestination").forEach { forbidden ->
            assertFalse(forbidden, starter.contains(forbidden))
        }
        val destroy = source.substringAfter("override fun onDestroy() {").substringBefore("\n    }\n")
        assertTrue(destroy.contains("fieldSessionHeartbeatExecutor?.shutdownNow()"))
    }

    @Test
    fun heartbeatStartAndDestroyShareOneLockAndADestroyedFlag() {
        // S1617-37-F1: started on the privacy-inspection worker while onDestroy runs on main.
        val starter = source.substringAfter("private fun startFieldSessionHeartbeat()")
            .substringBefore("override fun onDrawFrame(gl: GL10?)")
        val lock = starter.indexOf("synchronized(fieldHeartbeatLock) {")
        val guard = starter.indexOf("if (fieldHeartbeatDestroyed || fieldSessionHeartbeatExecutor != null) return")
        val create = starter.indexOf("newSingleThreadScheduledExecutor")
        val publish = starter.indexOf("fieldSessionHeartbeatExecutor = executor")
        assertTrue(lock >= 0)
        assertTrue(guard > lock)
        assertTrue("created only after the destroyed check", create > guard)
        assertTrue(publish > create)
        val destroy = source.substringAfter("override fun onDestroy() {").substringBefore("\n    }\n")
        val destroyLock = destroy.indexOf("synchronized(fieldHeartbeatLock) {")
        val flag = destroy.indexOf("fieldHeartbeatDestroyed = true")
        val shutdown = destroy.indexOf("fieldSessionHeartbeatExecutor?.shutdownNow()")
        assertTrue(destroyLock >= 0)
        assertTrue(flag > destroyLock)
        assertTrue(shutdown > flag)
        assertTrue(source.contains("private val fieldHeartbeatLock = Any()"))
    }

    @Test
    fun onDrawFrameKeepsItsOwnDocumentation() {
        // S1617-37-F2: the heartbeat starter was inserted between onDrawFrame and its KDoc.
        assertTrue(
            source.contains(
                "/** Keeps preview, coordinate mapping and depth acquisition on the same ARCore frame. */\n" +
                    "    override fun onDrawFrame(gl: GL10?) {",
            ),
        )
    }

    @Test
    fun onDrawFrameOnlyStampsTheHeartbeat() {
        val drawFrame = source.substringAfter("override fun onDrawFrame(gl: GL10?) {")
            .substringBefore("fieldSessionLog.appendTelemetry(")
        assertTrue(
            drawFrame.trimStart().startsWith(
                "fieldSessionHeartbeat?.onRenderCallback(SystemClock.elapsedRealtime())",
            ),
        )
        assertTrue(drawFrame.contains("fieldSessionHeartbeat?.onFrameProduced(SystemClock.elapsedRealtime())"))
        assertFalse(drawFrame.contains("heartbeat.tick("))
    }
}
