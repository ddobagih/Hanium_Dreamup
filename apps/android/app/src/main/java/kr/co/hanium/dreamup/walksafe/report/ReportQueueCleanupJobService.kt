package kr.co.hanium.dreamup.walksafe.report

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Best-effort physical expiry cleanup independent of report queue reads and uploads. */
internal class ReportQueueCleanupJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "walksafe-report-queue-cleanup").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeJob: ActiveJob? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val job = ActiveJob(params)
        activeJob = job
        job.future = executor.submit {
            var needsReschedule = false
            try {
                AndroidReportQueueStore(applicationContext.filesDir).pruneExpired()
            } catch (failure: Exception) {
                Log.w(TAG, "Report queue expiry cleanup failed", failure)
                needsReschedule = true
            }
            mainHandler.post {
                if (activeJob === job) {
                    activeJob = null
                    jobFinished(params, needsReschedule)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeJob?.takeIf { it.params === params }?.let { job ->
            activeJob = null
            job.future?.cancel(true)
        }
        return true
    }

    override fun onDestroy() {
        activeJob?.future?.cancel(true)
        activeJob = null
        executor.shutdownNow()
        super.onDestroy()
    }

    private class ActiveJob(val params: JobParameters) {
        var future: Future<*>? = null
    }

    companion object {
        private const val TAG = "ReportQueueCleanup"
        private const val JOB_ID = 0x57535251
        private val CLEANUP_INTERVAL_MS = TimeUnit.DAYS.toMillis(1)

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            try {
                if (scheduler.getPendingJob(JOB_ID) != null) return
                val job = JobInfo.Builder(
                    JOB_ID,
                    ComponentName(context, ReportQueueCleanupJobService::class.java),
                ).setPeriodic(CLEANUP_INTERVAL_MS)
                    .setPersisted(true)
                    .build()
                if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                    Log.w(TAG, "Report queue expiry cleanup could not be scheduled")
                }
            } catch (failure: RuntimeException) {
                Log.w(TAG, "Report queue expiry cleanup scheduling failed", failure)
            }
        }
    }
}
