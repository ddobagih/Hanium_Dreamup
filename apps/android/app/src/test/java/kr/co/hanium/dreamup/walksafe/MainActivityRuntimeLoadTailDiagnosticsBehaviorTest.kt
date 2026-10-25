package kr.co.hanium.dreamup.walksafe

import java.io.File

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Files
import java.nio.file.Paths
import kr.co.hanium.dreamup.walksafe.inference.RuntimeLoadCompletion
import kr.co.hanium.dreamup.walksafe.inference.RuntimeLoadMonitor
import org.junit.Assert.*
import org.junit.Test

/** Actual Main private diagnostic formatter and its real monitor; no GL/UI/device execution. */
class MainActivityRuntimeLoadTailDiagnosticsBehaviorTest {
    @Test
    fun actualMainDiagnosticDistinguishesDifferentTailsWithTheSameCentralStatistics() {
        val first = MainActivity()
        val second = MainActivity()
        val firstMonitor = monitor(first)
        val secondMonitor = monitor(second)
        completeWindow(firstMonitor, List(9) { 100L } + 500L)
        completeWindow(secondMonitor, List(9) { 100L } + 2_000L)
        assertEquals(firstMonitor.snapshot().baseline, secondMonitor.snapshot().baseline)
        assertEquals(firstMonitor.snapshot().pendingReason, secondMonitor.snapshot().pendingReason)
        val firstDiagnostic = diagnostic(first)
        val secondDiagnostic = diagnostic(second)
        assertNotEquals("Actual Main output must preserve a different admitted primary tail", firstDiagnostic, secondDiagnostic)
        assertTrue(firstDiagnostic, firstDiagnostic.contains("primaryTicketWindowMax=500ms"))
        assertTrue(secondDiagnostic, secondDiagnostic.contains("primaryTicketWindowMax=2000ms"))
        assertEquals(firstDiagnostic.replace("primaryTicketWindowMax=500ms", "primaryTicketWindowMax=2000ms"), secondDiagnostic)
    }

    @Test
    fun actualMainDiagnosticPreservesUnknownZeroAndClearedScopeWithoutTurningThemIntoLimits() {
        val main = MainActivity()
        val load = monitor(main)
        assertTrue(diagnostic(main).contains("primaryTicketWindowMax=unknownms"))
        completeWindow(load, List(10) { 0L })
        assertTrue(diagnostic(main).contains("primaryTicketWindowMax=0ms"))
        load.pause()
        assertTrue(diagnostic(main).contains("primaryTicketWindowMax=unknownms"))
        completeWindow(load, List(10) { 0L }, firstReceipt = 30_000L)
        assertTrue(diagnostic(main).contains("primaryTicketWindowMax=0ms"))
        load.reset()
        assertTrue(diagnostic(main).contains("primaryTicketWindowMax=unknownms"))
    }

    @Test
    fun productionPrimaryAxisAndTheExistingDiagnosticCallersRemainConnected() {
        val source = mainSource()
        val formatter = source.substring(source.indexOf("    private fun adaptiveInferencePacingStatusText(): String {"),
            source.indexOf("    private fun scheduleDetectionIfDue("))
        assertEquals(1, Regex("runtimeLoadMonitor\\.snapshot\\(\\)").findAll(formatter).count())
        assertTrue(formatter.contains("load.lastCompletedWindowMaxEndToEndMs"))
        val debugBranch = formatter.substring(formatter.indexOf("val loadDiagnostic = if (BuildConfig.DEBUG) {"),
            formatter.indexOf("} else \"\""))
        assertTrue(debugBranch.contains("runtimeLoadMonitor.snapshot()"))
        assertTrue(debugBranch.contains("primaryTicketWindowMax="))
        assertTrue(MainActivityRefactorSource.property(source, "framePresentation").contains("currentUserMotionStatusText(), adaptiveInferencePacingStatusText(), latestVisualTrackingStatus"))
        val presentation = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePresentationController.kt").readText()
        assertEquals(2, Regex("suffix\\(").findAll(MainActivityRefactorSource.member(presentation, "showStatus")).count())
        assertTrue(presentation.contains("parts.inferencePacing"))
        val completion = source.substring(source.indexOf("    private fun finishDetectionWork("),
            source.indexOf("    private fun runtimeLoadEnvironmentKey(): String"))
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorExecutionOwner.kt").readText()
        val finish = MainActivityRefactorSource.member(owner, "finishWork")
        assertTrue(completion.contains("primaryDetectorOwner.finishWork(ticket, detectorInvoked, inferenceDurationMs, validCompletion,"))
        assertTrue(finish.contains("if (accepted && validCompletion)"))
        assertTrue(finish.contains("val endToEnd = completedAt - ticket.startedAtElapsedRealtimeMs"))
        assertTrue(completion.contains("observeRuntimeInferenceLoad(completion = RuntimeLoadCompletion("))
        assertTrue(finish.contains("onValidCompletion(endToEnd, endToEnd > sourceAgeMs)"))
    }

    private fun monitor(main: MainActivity) = MainActivity::class.java.getDeclaredField("runtimeLoadMonitor")
        .apply { isAccessible = true }.get(main) as RuntimeLoadMonitor

    private fun diagnostic(main: MainActivity): String = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
        .findVirtual(MainActivity::class.java, "adaptiveInferencePacingStatusText", MethodType.methodType(String::class.java))
        .bindTo(main).invokeWithArguments() as String

    private fun completeWindow(load: RuntimeLoadMonitor, timings: List<Long>, firstReceipt: Long = 0L) {
        timings.forEachIndexed { index, elapsed ->
            load.observe(firstReceipt + index * 1_900L, "GPU:4:tracking-depth", true,
                inputObserved = true, completion = RuntimeLoadCompletion(elapsed))
        }
        load.observe(firstReceipt + 20_000L, "GPU:4:tracking-depth", true, inputObserved = true)
    }

    private fun mainSource(): String {
        val relative = "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"
        val paths = listOf(Paths.get(relative), Paths.get("app", relative), Paths.get("apps/android/app", relative))
        return String(Files.readAllBytes(requireNotNull(paths.firstOrNull { Files.isRegularFile(it) })), Charsets.UTF_8)
    }
}
