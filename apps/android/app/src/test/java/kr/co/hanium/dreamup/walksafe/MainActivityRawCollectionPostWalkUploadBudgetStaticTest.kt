package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S03-27: one post-walk raw-upload task (owner decision 3(b)) has an explicit item budget and re-enters every gate. */
class MainActivityRawCollectionPostWalkUploadBudgetStaticTest {
    private val source = listOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first(File::isFile).readText()

    @Test
    fun postWalkUploadTaskIsBoundedAndContinuesThroughAFreshlyGatedTask() {
        val upload = functionBlock("private fun scheduleRawCollectionPostWalkUpload()")
        assertFalse(upload.contains("while (true)"))
        val batch = upload.indexOf("repeat(RAW_COLLECTION_POST_WALK_UPLOADS_PER_TASK) {")
        assertTrue(batch > upload.indexOf("!initial.walk.state.allowsPostWalkTransfer"))
        assertTrue(batch > upload.indexOf("initial.gatewayWalkLease != null"))
        assertTrue(batch > upload.indexOf("!initial.charging"))
        val loop = upload.substring(batch)
        assertTrue(loop.contains("currentRawCollectionRuntimeContextOrNull()"))
        assertTrue(loop.contains("rawCollectionRuntimeCoordinator.startNextUpload {"))
        assertTrue(loop.contains("RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT"))
        assertTrue(
            loop.substringAfterLast("return@executeRawCollectionTask")
                .contains("scheduleRawCollectionPostWalkUpload()"),
        )
        val budget = Regex("""const val RAW_COLLECTION_POST_WALK_UPLOADS_PER_TASK = (\d+)""")
            .find(File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/RawCollectionTransferController.kt").readText())?.groupValues?.get(1)?.toInt()
        assertTrue(budget != null && budget in 1..8)
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)
}
