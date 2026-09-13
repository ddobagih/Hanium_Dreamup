package kr.co.hanium.dreamup.walksafe.device;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** Uses a real JVM scheduler to reproduce early completion with a still-pending 300-second timer. */
public class AndroidRuntimeCalibrationSessionDeadlineTest {
    @Test
    public void earlyCompletionDiscardsDeadlineAndTerminatesExecutor() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidRuntimeCalibrationSession.kt")), StandardCharsets.UTF_8);
        assertTrue(source.contains("private val deadlineWorker = ScheduledThreadPoolExecutor(1)"));
        assertTrue(source.contains("setExecuteExistingDelayedTasksAfterShutdownPolicy(false)"));
        assertFalse(source.contains("shutdownNow("));

        ScheduledThreadPoolExecutor scheduler = scheduler();
        AtomicBoolean deadlineRan = new AtomicBoolean();
        ScheduledFuture<?> deadline = scheduler.schedule(
                () -> deadlineRan.set(true), 300, TimeUnit.SECONDS);
        CountDownLatch workerStarted = new CountDownLatch(1);
        scheduler.execute(workerStarted::countDown);
        try {
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
            scheduler.shutdown();
            assertTrue("early completion must not wait for the five-minute deadline",
                    scheduler.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(deadline.isCancelled());
            assertTrue(scheduler.getQueue().isEmpty());
            assertFalse(deadlineRan.get());
        } finally {
            deadline.cancel(false);
            scheduler.shutdown();
        }
    }

    @Test
    public void shutdownDoesNotInterruptCallbackAlreadyRunning() throws Exception {
        ScheduledThreadPoolExecutor scheduler = scheduler();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        scheduler.schedule(() -> {
            started.countDown();
            try {
                finish.await();
            } catch (InterruptedException error) {
                interrupted.set(true);
            }
        }, 0, TimeUnit.MILLISECONDS);
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            scheduler.shutdown();
            assertFalse("running callbacks must drain naturally",
                    scheduler.awaitTermination(50, TimeUnit.MILLISECONDS));
            assertFalse(interrupted.get());
            finish.countDown();
            assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
            assertFalse(interrupted.get());
        } finally {
            finish.countDown();
            scheduler.shutdown();
        }
    }

    private static ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
