package kr.co.hanium.dreamup.walksafe.inference.unknown;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real service age checks with controlled model work; no GPU or device timing claim. */
public class FastSamFreshFrameSubmissionTest {
    private void replay(boolean acquireAfterPrimary, long auxiliaryMs, boolean expectResult) throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicInteger results = new AtomicInteger();
        AtomicReference<String> discard = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        FastSamRuntimeOptions options = new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,
            1, false, 100_000_000_000L, 800L, false);
        FastSamRuntimeService<Void> service = new FastSamRuntimeService<>(12L, options,
            new FastSamRuntimeService.Listener<Void>() {
                @Override public void onResult(FastSamResult<Void> result) {
                    results.incrementAndGet(); terminal.countDown();
                }
                @Override public void onDiscard(FastSamFrameToken token, String reason) {
                    discard.set(reason); terminal.countDown();
                }
                @Override public void onError(Throwable error) {
                    failure.set(error); terminal.countDown();
                }
            }, () -> new FastSamEngine() {
                final FastSamRuntimeInfo info = new FastSamRuntimeInfo(options,
                    FastSamRuntimeOptions.Backend.CPU, null, Thread.currentThread().getId(), 0);
                @Override public FastSamRuntimeInfo runtime() { return info; }
                @Override public Output invoke(int[] pixels, int width, int height) {
                    clock.addAndGet(auxiliaryMs * 1_000_000L);
                    return new Output(new float[][] {new float[FastSamModelContract.DETECTION_FLOATS],
                        new float[FastSamModelContract.PROTOTYPE_FLOATS]}, 0, auxiliaryMs, 0);
                }
                @Override public void close() { }
            }, clock::get);
        try {
            service.start().get(3, TimeUnit.SECONDS);
            long primaryCapturedNs = clock.get();
            clock.addAndGet(500_000_000L);
            long auxiliaryCapturedNs = acquireAfterPrimary ? clock.get() : primaryCapturedNs;
            FastSamFrameToken token = new FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA,
                12, 1, 100, 100, auxiliaryCapturedNs, "frozen-current-camera", 2, 1);
            assertEquals(FastSamRuntimeService.Admission.ACCEPTED,
                service.submitArgb(2, 1, new int[2], token, null));
            assertTrue(terminal.await(3, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(expectResult ? 1 : 0, results.get());
            assertEquals(expectResult ? null : "stale_after_decode", discard.get());
        } finally {
            service.closeAsync().get(3, TimeUnit.SECONDS);
        }
        assertTrue(service.stats().nativeReleaseConfirmed);
    }

    @Test public void newCameraCaptureDoesNotInheritThePreviousPrimaryWork() throws Exception {
        replay(true, 400L, true);
    }
    @Test public void originalPrimaryCaptureRemainsExpiredEvenWhenAuxiliaryWorkIsFast() throws Exception {
        replay(false, 400L, false);
    }
    @Test public void freshCaptureDoesNotDisableTheRealEightHundredMillisecondLimit() throws Exception {
        replay(true, 801L, false);
    }
}
