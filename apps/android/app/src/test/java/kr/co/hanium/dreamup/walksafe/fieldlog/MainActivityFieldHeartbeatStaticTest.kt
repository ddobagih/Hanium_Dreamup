package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16-17 #37 wiring: the heartbeat timer is independent of the delegated GL frame transaction. */
class MainActivityFieldHeartbeatStaticTest {
    private val root = "src/main/java/kr/co/hanium/dreamup/walksafe/"
    private val source = File(root + "MainActivity.kt").readText()
    private val heartbeat = File(root + "fieldlog/diagnostics/FieldDiagnosticRecordingPipeline.kt").readText()
    private fun member(text: String, name: String) = MainActivityRefactorSource.member(text, name)

    @Test
    fun heartbeatRunsOnItsOwnScheduledThreadStartedOnlyForDebugFieldLogs() {
        val wiring = MainActivityRefactorSource.composition(source, "fieldDiagnosticRecording")
        assertHeartbeatStartBinding()
        val starter = member(heartbeat, "startFieldSessionHeartbeat")
        assertTrue(source.contains("if (BuildConfig.DEBUG) startFieldSessionHeartbeat()"))
        assertTrue(MainActivityRefactorSource.property(heartbeat, "createExecutor").contains("newSingleThreadScheduledExecutor"))
        assertTrue(heartbeat.contains("\"walksafe-field-heartbeat\""))
        assertTrue(starter.contains("val executor = createExecutor()"))
        assertTrue(starter.contains("scheduleWithFixedDelay("))
        assertTrue(starter.contains("FIELD_HEARTBEAT_INTERVAL_MS"))
        assertFalse(wiring.contains("freshTrustedLocationOrNull("))
        listOf("latitude", "longitude", "currentDestination").forEach { forbidden ->
            assertFalse(forbidden, wiring.contains(forbidden) || starter.contains(forbidden))
        }
        assertTrue(member(source, "onDestroy").contains("fieldDiagnosticRecording.closeHeartbeat()"))
        assertTrue(member(heartbeat, "closeHeartbeat").contains("fieldSessionHeartbeatExecutor?.shutdownNow()"))
    }

    @Test
    fun heartbeatStartAndDestroyShareOneLockAndADestroyedFlag() {
        val wiring = MainActivityRefactorSource.composition(source, "fieldDiagnosticRecording")
        assertTrue(wiring.contains("SystemClock::elapsedRealtime, fieldHeartbeatLock"))
        assertTrue(source.contains("private val fieldHeartbeatLock = Any()"))
        val starter = member(heartbeat, "startFieldSessionHeartbeat")
        val lock = starter.indexOf("synchronized(heartbeatLock) {")
        val guard = starter.indexOf("if (fieldHeartbeatDestroyed || fieldSessionHeartbeatExecutor != null) return")
        val create = starter.indexOf("val executor = createExecutor()")
        val publish = starter.indexOf("fieldSessionHeartbeatExecutor = executor")
        assertTrue(lock >= 0 && guard > lock)
        assertTrue("created only after the destroyed check", create > guard)
        assertTrue(publish > create)
        assertTrue(member(source, "onDestroy").contains("fieldDiagnosticRecording.closeHeartbeat()"))
        val destroy = member(heartbeat, "closeHeartbeat")
        val destroyLock = destroy.indexOf("synchronized(heartbeatLock) {")
        val flag = destroy.indexOf("fieldHeartbeatDestroyed = true")
        val shutdown = destroy.indexOf("fieldSessionHeartbeatExecutor?.shutdownNow()")
        assertTrue(destroyLock >= 0 && flag > destroyLock && shutdown > flag)
    }

    @Test
    fun onDrawFrameKeepsItsOwnDocumentedFrameOwner() {
        assertTrue(member(source, "onDrawFrame").contains("= framePipelineController.draw()"))
        val wiring = MainActivityRefactorSource.composition(source, "framePipelineController")
        assertTrue(wiring.contains("FramePipelineController(frameStateLock, frameRenderController, runtime,"))
        val pipeline = File(root + "runtime/vision/frame/FramePipelineController.kt").readText()
        assertTrue(pipeline.contains("/** Actual frame transaction: acquire, qualify, synthesize and publish, all on the original GL call. */\ninternal class FramePipelineController"))
        assertTrue(member(pipeline, "draw").contains("render.draw { source ->"))
        assertFalse(member(heartbeat, "startFieldSessionHeartbeat").contains("fun draw("))
    }

    @Test
    fun onDrawFrameOnlyStampsTheHeartbeat() {
        assertTrue(member(source, "onDrawFrame").contains("framePipelineController.draw()"))
        val wiring = MainActivityRefactorSource.composition(source, "framePipelineController")
        val renderWiring = MainActivityRefactorSource.property(source, "frameRenderController")
        assertTrue(renderWiring.contains("ArFrameRenderController(frameStateLock,"))
        assertTrue(renderWiring.contains("override fun onRenderCallback() { fieldDiagnosticRecording.onRenderCallback() }"))
        assertTrue(wiring.contains("override fun frameProduced() = fieldDiagnosticRecording.onFrameProduced()"))
        assertTrue(wiring.contains("val publication = FramePublicationController("))
        assertTrue(wiring.contains("runtime::visualOutputAllowsWork, tactile, risk, recording,"))
        assertTrue(wiring.contains("{ framePresentation }, publication, { it.statusTextForNoDetection() }, clock)"))
        assertTrue(member(File(root + "runtime/vision/frame/FramePipelineController.kt").readText(), "draw").contains("publication.publish(outputs)"))
        val render = member(File(root + "runtime/vision/frame/ArFrameRenderController.kt").readText(), "draw")
        assertTrue(render.substringAfter('{').trimStart().startsWith("observer.onRenderCallback()"))
        val publication = File(root + "runtime/vision/frame/FramePublicationController.kt").readText()
        assertTrue(publication.contains("recording.frameProduced()"))
        assertTrue(member(heartbeat, "onRenderCallback").contains("fieldSessionHeartbeat?.onRenderCallback(clock())"))
        assertTrue(member(heartbeat, "onFrameProduced").contains("fieldSessionHeartbeat?.onFrameProduced(clock())"))
        listOf(render, publication, wiring, renderWiring, member(heartbeat, "onRenderCallback"), member(heartbeat, "onFrameProduced")).forEach {
            assertFalse(it.contains("heartbeat.tick("))
        }
    }

    private fun assertHeartbeatStartBinding(mainSource: String = source) {
        MainActivityRefactorSource.composition(mainSource, "fieldDiagnosticRecording")
        val caller = MainActivityRefactorSource.factory(mainSource, "startFieldSessionHeartbeat")
        // Whole declaration comparison preserves guards, argument forwarding and effect order.
        val expectedCaller = """
private fun startFieldSessionHeartbeat() {
        privacyStartupResources.acquireOnWorker(
            { fieldDiagnosticRecording }, { it.closeHeartbeat() },
        ).startFieldSessionHeartbeat()
    }
        """.trimIndent()
        org.junit.Assert.assertEquals("Main heartbeat must register its original cleanup before starting", expectedCaller.filterNot(Char::isWhitespace),
            caller.filterNot(Char::isWhitespace))
    }

    @Test
    fun heartbeatBindingRejectsMissingOrNonExecutingStartAndRelease() {
        assertHeartbeatStartBinding()
        val caller = MainActivityRefactorSource.factory(source, "startFieldSessionHeartbeat")
        val start = ").startFieldSessionHeartbeat()"
        org.junit.Assert.assertTrue(caller.contains(start))
        val variants = mapOf(
            "conditional start" to caller.replace(start, ").let { if (false) it.startFieldSessionHeartbeat() }"),
            "uninvoked start" to caller.replace(start, ").let { { it.startFieldSessionHeartbeat() } }"),
            "commented start" to caller.replace(start, ") /* .startFieldSessionHeartbeat() */"),
            "omitted start" to caller.replace(start, ")"),
            "commented cleanup" to caller.replace("{ it.closeHeartbeat() }", "{ /* it.closeHeartbeat() */ }"),
            "wrong instance cleanup" to caller.replace("{ it.closeHeartbeat() }", "{ fieldDiagnosticRecording.closeHeartbeat() }"),
        )
        val accepted = variants.filter { (_, changed) ->
            runCatching { assertHeartbeatStartBinding(source.replace(caller, changed)) }.isSuccess
        }.keys
        org.junit.Assert.assertTrue("Non-equivalent heartbeat bindings accepted: $accepted", accepted.isEmpty())
    }

}
