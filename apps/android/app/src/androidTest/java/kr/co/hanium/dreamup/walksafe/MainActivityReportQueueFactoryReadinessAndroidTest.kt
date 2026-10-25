package kr.co.hanium.dreamup.walksafe

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Looper
import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import kr.co.hanium.dreamup.walksafe.device.AndroidAllocatableBytes
import kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStore
import kr.co.hanium.dreamup.walksafe.report.FileReportQueueStorage
import kr.co.hanium.dreamup.walksafe.report.REPORT_QUEUE_KEY_POLICY
import kr.co.hanium.dreamup.walksafe.report.ReportQueueStorageUsage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First access to the actual attached Main lazy factory; no onCreate or ContextWrapper.
 * The canonical comparison is a second read-only storage observation, never Main's input.
 * No enqueue, lock acquisition, allocation query, AEAD operation, purge, reset or cleanup write.
 * Application/provider initialization may already have occurred before this fixture's entry.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityReportQueueFactoryReadinessAndroidTest {
    @Test
    fun ordinaryAttachedFilesDirReachesTheActualLazyReportFactoryReadOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        assertEquals("Only the existing isolated fixture package may run this condition",
            "kr.co.hanium.dreamup.walksafe.speechstorefixture", context.packageName)
        assertEquals(context.applicationInfo.uid, Process.myUid())
        @Suppress("DEPRECATION")
        val targetInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        assertNull(targetInfo.sharedUserId)
        // The external existing runner proves fixture/USER UID separation before execution.
        // This condition performs no USER package, data, preferences or key query.
        assertTrue("The approved default constructor must have no compromised-key deletion work",
            REPORT_QUEUE_KEY_POLICY.compromisedVersions.isEmpty())

        // Context.filesDir may create a missing directory. Require the ordinary existing leaf
        // before calling that getter; missing preparation must fail without mkdir or a new root.
        val existingFiles = File(context.applicationInfo.dataDir, "files")
        assertTrue("ENV_EXISTING_FILES_DIR_REQUIRED before any filesDir getter",
            Files.isDirectory(existingFiles.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals(Process.myUid(), Os.stat(existingFiles.absolutePath).st_uid)
        val application = context.applicationContext as Application
        val ordinaryFiles = application.filesDir
        assertTrue(Files.isSameFile(existingFiles.toPath(), ordinaryFiles.toPath()))
        assertTrue(ordinaryFiles.isDirectory)
        assertEquals(Process.myUid(), Os.stat(ordinaryFiles.absolutePath).st_uid)
        val canonicalFiles = ordinaryFiles.canonicalFile
        assertTrue(Files.isSameFile(ordinaryFiles.toPath(), canonicalFiles.toPath()))
        val queueRoot = File(ordinaryFiles, "report_queue_v1")
        val queuePaths = listOf(queueRoot, File(queueRoot, "reports"),
            File(queueRoot, "fences"), File(queueRoot, ".queue.lock"))
        val existedBefore = queuePaths.map { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }

        val inspection = FutureTask {
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            val main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0),
                "Main ordinary report factory readiness", null, null, null) as MainActivity
            assertSame(application, main.applicationContext)
            assertEquals(ordinaryFiles.absolutePath, main.applicationContext.filesDir.absolutePath)
            val factory = readField(main, "reportQueueStore\$delegate") as Lazy<*>
            assertFalse("This condition must exercise the first factory access", factory.isInitialized())
            val getter = MainActivity::class.java.getDeclaredMethod("getReportQueueStore")
                .apply { isAccessible = true }
            val store = getter.invoke(main) as AndroidReportQueueStore
            assertTrue(factory.isInitialized())
            assertSame(store, factory.value)
            assertSame("A second getter read must retain the actual first factory result",
                store, getter.invoke(main))

            // Read the original lazy storage factory; no delegate or assembled store is installed.
            val storage = (readField(store, "storage\$delegate") as Lazy<*>).value as FileReportQueueStorage
            val storageBase = readField(storage, "storageBase") as File
            assertTrue(Files.isSameFile(storageBase.toPath(), ordinaryFiles.toPath()))
            val capacityProvider = readField(store, "allocatableBytes") as AndroidAllocatableBytes
            val capacityDirectory = readField(capacityProvider, "directory") as File
            assertTrue(Files.isSameFile(capacityDirectory.toPath(), ordinaryFiles.toPath()))
            assertTrue(Files.isSameFile(storageBase.toPath(), capacityDirectory.toPath()))
            val ordinaryUsage = storage.measureUsage()
            val canonicalUsage = FileReportQueueStorage(canonicalFiles).measureUsage()
            println("REPORT_FACTORY_READINESS " + JSONObject()
                .put("fixture_uid", Process.myUid())
                .put("ordinary_files_dir", ordinaryFiles.absolutePath)
                .put("actual_storage_base", storageBase.absolutePath)
                .put("actual_capacity_directory", capacityDirectory.absolutePath)
                .put("canonical_same_physical_directory", canonicalFiles.absolutePath)
                .put("ordinary_usage", usageJson(ordinaryUsage))
                .put("canonical_control_usage", usageJson(canonicalUsage)))

            assertEquals("Read-only inspection must not create queue directories or its lock file",
                existedBefore, queuePaths.map { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) })
            assertNotNull("Actual Main ordinary filesDir storage must reach a measurable ready state; " +
                "canonical control alone is not the default factory ACK", ordinaryUsage)
            assertEquals("Same physical directory read through two expressions has the same usage",
                canonicalUsage, ordinaryUsage)
        }
        instrumentation.runOnMainSync(inspection)
        try {
            inspection.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure // Preserve the original assertion/linkage failure.
        }
    }

    private fun readField(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun usageJson(usage: ReportQueueStorageUsage?): Any = usage?.let {
        JSONObject().put("report_file_count", it.reportFileCount).put("total_bytes", it.totalBytes)
    } ?: JSONObject.NULL
}
