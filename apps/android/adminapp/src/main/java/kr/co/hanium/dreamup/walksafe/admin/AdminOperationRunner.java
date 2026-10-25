package kr.co.hanium.dreamup.walksafe.admin;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One dispatch boundary for screen-owned requests; never retries a mutation. */
final class AdminOperationRunner {
    private AdminOperationRunner() {}

    static <T> void execute(Executor worker, Executor ui, BooleanSupplier current,
                           Callable<T> operation, Consumer<T> success, Consumer<Exception> failure) {
        Runnable task = () -> {
            if (!current.getAsBoolean()) return;
            final T result;
            try {
                result = operation.call();
            } catch (Exception error) {
                ui.execute(() -> { if (current.getAsBoolean()) failure.accept(error); });
                return;
            }
            ui.execute(() -> { if (current.getAsBoolean()) success.accept(result); });
        };
        try {
            worker.execute(task);
        } catch (RejectedExecutionException error) {
            ui.execute(() -> { if (current.getAsBoolean()) failure.accept(error); });
        }
    }
}
