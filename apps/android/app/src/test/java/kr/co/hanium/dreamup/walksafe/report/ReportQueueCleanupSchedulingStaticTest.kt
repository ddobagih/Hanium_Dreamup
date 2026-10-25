package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportQueueCleanupSchedulingStaticTest {
    private val application = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/voice/WalkSafeApplication.kt",
    ).readText()
    private val service = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/report/ReportQueueCleanupJobService.kt",
    ).readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun applicationRegistersOneDailyPersistedCleanupJob() {
        assertTrue(application.contains("ReportQueueCleanupJobService.schedule(this)"))
        assertTrue(service.contains("scheduler.getPendingJob(JOB_ID)"))
        assertTrue(service.contains(".setPeriodic(CLEANUP_INTERVAL_MS)"))
        assertTrue(service.contains("TimeUnit.DAYS.toMillis(1)"))
        assertTrue(service.contains(".setPersisted(true)"))
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(manifest.contains("android:name=\".report.ReportQueueCleanupJobService\""))
        assertTrue(manifest.contains("android.permission.BIND_JOB_SERVICE"))
    }

    @Test
    fun jobPrunesWithoutReportActivityOrNetworkAndCanBeStopped() {
        assertTrue(service.contains("executor.submit {"))
        assertTrue(service.contains("val store = AndroidReportQueueStore(applicationContext.filesDir)"))
        assertTrue(service.contains("store.pruneExpired()"))
        assertTrue(service.contains("needsReschedule = !store.maintenanceSucceeded()"))
        assertTrue(service.contains("jobFinished(params, needsReschedule)"))
        assertTrue(service.contains("override fun onStopJob(params: JobParameters)"))
        assertTrue(service.contains("job.future?.cancel(true)"))
        assertFalse(service.contains("setRequiredNetworkType"))
    }
}
