package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16-17 #6: MainActivity supplies the verified instance hashes and the executed delegate. */
class MainActivityRawDetectorIdentityStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun rawWindowsCarryTheVerifiedRunningDetectorIdentity() {
        val record = source.substringAfter("private fun recordRawCollectionDetectionMetadata(")
            .substringBefore("private fun revalidateRawCollectionRuntime(")
        assertTrue(record.contains("executedRuntime: AndroidDetectorRuntime?,"))
        assertTrue(record.contains("val verifiedDetector = runningDetectorVerification.current()?.identity"))
        assertTrue(record.contains("modelArtifactSha256 = verifiedDetector?.modelSetSha256,"))
        assertTrue(record.contains("runtimeConfigSha256 = verifiedDetector?.configSha256,"))
        assertTrue(record.contains("classListSha256 = verifiedDetector?.classListSha256,"))
        assertTrue(record.contains("activeDelegate = executedRuntime?.activeDelegate,"))
        assertTrue(record.contains("runtimeIdentity = runtimeIdentity,"))
        // The configured model name is never a substitute for the executed identity.
        assertFalse(record.contains("activeDelegate = detectorLoadedModelKey"))
        val callers = Regex(
            """recordRawCollectionDetectionMetadata\([\s\S]{0,700}?executedRuntime = result\.timing\.modelRuntime \?: result\.timing\.customRuntime,""",
        ).findAll(source).count()
        assertEquals(2, callers)
        assertEquals(3, Regex("""recordRawCollectionDetectionMetadata\(""").findAll(source).count())
    }

    @Test
    fun theIdentityNeedsTheLiveTokenAndSplitWindowsAreCounted() {
        // S1617-6-F1: a token of an earlier instance or load generation never names a window.
        val record = source.substringAfter("private fun recordRawCollectionDetectionMetadata(")
            .substringBefore("private fun revalidateRawCollectionRuntime(")
        val guard = record.substringAfter("val runtimeIdentity = RawDetectorRuntimeIdentity.ofOrNull(")
            .substringBefore("val dueButInFlightDropCount")
        assertTrue(guard.contains(")?.takeIf {"))
        assertTrue(guard.contains("verifiedDetector.generation == detectorLoadGeneration"))
        assertTrue(guard.contains("runningDetectorVerification.allowsOutput(frameDetector, verifiedDetector)"))
        val ended = source.substringAfter("transition.current.state == WalkSessionState.ENDED -> {")
            .substringBefore("transition.current.state == WalkSessionState.PAUSED &&")
        val split = ended.indexOf("rawCollectionRuntimeCoordinator.recordIdentitySplitWindows(rawRuntimeWindow.takeIdentitySplitCount())")
        assertTrue(split >= 0)
        assertTrue(split < ended.indexOf("\"raw_collection_outcomes\""))
    }
}
