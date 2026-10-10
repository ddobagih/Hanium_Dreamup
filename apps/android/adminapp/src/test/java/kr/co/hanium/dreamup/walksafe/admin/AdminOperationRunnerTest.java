package kr.co.hanium.dreamup.walksafe.admin;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdminOperationRunnerTest {
    @Test public void stoppedBeforeDispatchNeverExecutesMutation() {
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger writes = new AtomicInteger();
        AdminOperationRunner.execute(worker::add, Runnable::run, current::get,
            writes::incrementAndGet, result -> fail(), error -> fail());
        current.set(false);
        worker.remove().run();
        assertEquals(0, writes.get());
    }
    @Test public void staleSuccessAndFailureNeverUpdateNewScreen() {
        for (boolean failRequest : new boolean[]{false, true}) {
            ArrayDeque<Runnable> ui = new ArrayDeque<>();
            AtomicBoolean current = new AtomicBoolean(true);
            AdminOperationRunner.execute(Runnable::run, ui::add, current::get,
                () -> { if (failRequest) throw new Exception("offline"); return 1; },
                result -> fail(), error -> fail());
            current.set(false);
            ui.remove().run();
        }
    }
    @Test public void rejectionAndRequestErrorAreDeliveredOnceWithoutRetry() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        Executor rejected = task -> { throw new RejectedExecutionException(); };
        AdminOperationRunner.execute(rejected, Runnable::run, () -> true,
            attempts::incrementAndGet, result -> fail(), error -> failures.incrementAndGet());
        assertEquals(0, attempts.get());
        AdminOperationRunner.execute(Runnable::run, Runnable::run, () -> true,
            () -> { attempts.incrementAndGet(); throw new Exception(); },
            result -> fail(), error -> failures.incrementAndGet());
        assertEquals(1, attempts.get());
        assertEquals(2, failures.get());
    }
    @Test public void successIsDeliveredOnUiExecutorOnly() {
        ArrayDeque<Runnable> ui = new ArrayDeque<>();
        AtomicInteger value = new AtomicInteger();
        AdminOperationRunner.execute(Runnable::run, ui::add, () -> true,
            () -> 7, value::set, error -> fail());
        assertEquals(0, value.get());
        ui.remove().run();
        assertEquals(7, value.get());
    }
}
