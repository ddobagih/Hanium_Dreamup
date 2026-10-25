package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16-17 #6: MainActivity supplies the verified instance hashes and the executed delegate. */
class MainActivityRawDetectorIdentityStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private val transfer = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/RawCollectionTransferController.kt").readText()

    @Test
    fun rawWindowsCarryTheVerifiedRunningDetectorIdentity() {
        val record = source.substringAfter("override fun runtimeIdentity(executedRuntime:").substringBefore("override fun peekMotion(")
        assertTrue(transfer.contains("executedRuntime: AndroidDetectorRuntime?,"))
        assertTrue(record.contains("val verifiedDetector = primaryDetectorLifecycleController.verifiedIdentity()"))
        assertTrue(record.contains("modelArtifactSha256 = verifiedDetector?.modelSetSha256,"))
        assertTrue(record.contains("runtimeConfigSha256 = verifiedDetector?.configSha256,"))
        assertTrue(record.contains("classListSha256 = verifiedDetector?.classListSha256,"))
        assertTrue(record.contains("activeDelegate = executedRuntime?.activeDelegate,"))
        assertTrue(transfer.contains("runtimeIdentity = runtimeIdentity,"))
        // The configured model name is never a substitute for the executed identity.
        assertFalse(record.contains("activeDelegate = detectorLoadedModelKey"))
        val completed = MainActivityRefactorSource.member(source, "recordPrimaryCompletedInference")
        assertTrue(completed.contains("event.result.timing.modelRuntime ?: event.result.timing.customRuntime"))
        assertTrue(completed.contains("recordRawCollectionDetectionMetadata(event.epoch"))
        assertTrue(source.contains("override fun completed(event: PrimaryCompletedInference) { recordPrimaryCompletedInference(event) }"))
        for (name in listOf("PrimaryArDetectorWorker", "PrimaryCameraFallbackDetectorWorker")) {
            val worker = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/$name.kt").readText()
            assertEquals(1, Regex("recording\\.completed\\(PrimaryCompletedInference").findAll(worker).count())
        }

    }

    @Test
    fun theIdentityNeedsTheLiveTokenAndSplitWindowsAreCounted() {
        // S1617-6-F1: a token of an earlier instance or load generation never names a window.
        val record = source.substringAfter("override fun runtimeIdentity(executedRuntime:").substringBefore("override fun peekMotion(")
        val guard = record.substringAfter("return RawDetectorRuntimeIdentity.ofOrNull(")
            .substringBefore("override fun peekMotion(")
        assertTrue(guard.contains(")?.takeIf {"))
        assertTrue(guard.contains("verifiedDetector.generation == primaryDetectorOwner.projection().loadGeneration"))
        assertTrue(guard.contains("primaryDetectorLifecycleController.verificationAllows(primaryDetectorOwner.projection().detector, verifiedDetector)"))
        val ended = transfer.substringAfter("transition.current.state == WalkSessionState.ENDED ||")
            .substringBefore("transition.current.state == WalkSessionState.PAUSED &&")
        val split = ended.indexOf("rawCollectionRuntimeCoordinator.recordIdentitySplitWindows(rawRuntimeWindow.takeIdentitySplitCount())")
        assertTrue(split >= 0)
        assertTrue(split < ended.indexOf("\"raw_collection_outcomes\""))
    }
}
