package kr.co.hanium.dreamup.walksafe.inference.unknown;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Test package bridge to the real SDK-free service owner; no fake readiness/release future. */
public final class UnknownRuntimeServiceTestFactory {
    public final AtomicInteger creations = new AtomicInteger();
    public final AtomicInteger nativeCloses = new AtomicInteger();
    public volatile RuntimeException closeFailure;
    public volatile RuntimeException loadFailure;
    public CountDownLatch loadEntered;
    public CountDownLatch releaseLoad;
    public volatile CountDownLatch closeEntered;
    public volatile CountDownLatch releaseClose;

    public <T> FastSamRuntimeService<T> create(long epoch, FastSamRuntimeOptions options,
            FastSamRuntimeService.Listener<T> listener, LongSupplier clock) {
        creations.incrementAndGet();
        return new FastSamRuntimeService<>(epoch, options, listener, () -> {
            if (loadEntered != null) loadEntered.countDown();
            if (releaseLoad != null && !releaseLoad.await(3, TimeUnit.SECONDS))
                throw new IllegalStateException("test load was not released");
            if (loadFailure != null) throw loadFailure;
            final long owner = Thread.currentThread().getId();
            final FastSamRuntimeInfo runtime = new FastSamRuntimeInfo(options,
                FastSamRuntimeOptions.Backend.CPU, null, owner, 0);
            return new FastSamEngine() {
                public FastSamRuntimeInfo runtime() { return runtime; }
                public Output invoke(int[] argb, int width, int height) {
                    if (Thread.currentThread().getId() != owner) throw new AssertionError("foreign native owner");
                    return new Output(new float[][] { new float[FastSamModelContract.DETECTION_FLOATS],
                        new float[FastSamModelContract.PROTOTYPE_FLOATS] }, 0, 0, 0);
                }
                public void close() {
                    if (Thread.currentThread().getId() != owner) throw new AssertionError("foreign close owner");
                    nativeCloses.incrementAndGet();
                    CountDownLatch entered = closeEntered;
                    CountDownLatch proceed = releaseClose;
                    if (entered != null) entered.countDown();
                    if (proceed != null) {
                        try {
                            if (!proceed.await(3, TimeUnit.SECONDS))
                                throw new IllegalStateException("test close was not released");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("test close interrupted", error);
                        }
                    }
                    if (closeFailure != null) throw closeFailure;
                }
            };
        }, clock);
    }
}
