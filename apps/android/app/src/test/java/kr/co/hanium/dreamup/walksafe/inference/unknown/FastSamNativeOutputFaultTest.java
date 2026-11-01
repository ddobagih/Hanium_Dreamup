package kr.co.hanium.dreamup.walksafe.inference.unknown;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.FastSamDecoder;
import org.junit.Test;

public final class FastSamNativeOutputFaultTest {
    @Test public void confirmedRawShapeAndNonfiniteOutputReportOnlyTheirTypedFault()throws Exception {
        for(boolean shape:new boolean[]{false,true}) {
            Fixture f=new Fixture();
            if(shape)f.raw=new float[][]{new float[1],new float[FastSamModelContract.PROTOTYPE_FLOATS]};
            else f.raw[1][f.raw[1].length-1]=Float.NaN;
            f.run();
            assertTrue(f.failure.get() instanceof FastSamModelContract.NativeOutputException);
            assertEquals(Collections.singletonList(shape?FastSamModelContract.NativeOutputFailureReason.OUTPUT_SHAPE_MISMATCH:
                FastSamModelContract.NativeOutputFailureReason.NON_FINITE_OUTPUT),f.reported);
            assertTrue(f.nativeClosed.get());
        }
    }

    @Test public void listenerExceptionsAreNotReclassifiedEvenWhenTheyUseTheOutputExceptionType()throws Exception {
        Fixture f=new Fixture();
        f.listenerFailure=new FastSamModelContract.NativeOutputException(
            FastSamModelContract.NativeOutputFailureReason.INVALID_NATIVE_OUTPUT,"parent consumer rejected its own work");
        f.run();assertSame(f.listenerFailure,f.failure.get());assertTrue(f.reported.isEmpty());
    }

    @Test public void parentOomIoCancellationAndMatchingErrorMessagesNeverReportNativeOutputFaults()throws Exception {
        List<Throwable> failures=Arrays.asList(new OutOfMemoryError("parent output copy"),
            new java.io.UncheckedIOException(new java.io.IOException("parent IO")),new CancellationException("retired actor"),
            new IllegalStateException("Non-finite native FastSAM output"));
        for(Throwable marker:failures) {
            Fixture f=new Fixture();f.invokeFailure=marker;f.run();
            assertSame(marker,f.failure.get());assertTrue(f.reported.isEmpty());
        }
    }

    @Test public void invalidInputGeometryRemainsAParentArgumentFailure() {
        try {
            new FastSamDecoder().decode(new float[FastSamModelContract.DETECTION_FLOATS],
                new float[FastSamModelContract.PROTOTYPE_FLOATS],0,1);
            fail();
        }catch(IllegalArgumentException failure){assertFalse(failure instanceof FastSamModelContract.NativeOutputException);}
    }

    @Test public void staleFaultReporterDoesNotReplaceTheActualRawFailureOrLoseNativeCleanup()throws Exception {
        Fixture f=new Fixture();f.raw[1][0]=Float.POSITIVE_INFINITY;
        f.reportFailure=new IllegalStateException("captured owner is no longer current");
        f.run();
        assertTrue(f.failure.get() instanceof FastSamModelContract.NativeOutputException);
        assertTrue(Arrays.asList(f.failure.get().getSuppressed()).contains(f.reportFailure));
        assertTrue(f.nativeClosed.get());
    }

    private static final class Fixture {
        float[][] raw={new float[FastSamModelContract.DETECTION_FLOATS],new float[FastSamModelContract.PROTOTYPE_FLOATS]};
        Throwable invokeFailure;RuntimeException listenerFailure,reportFailure;
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final AtomicBoolean nativeClosed=new AtomicBoolean();
        final List<FastSamModelContract.NativeOutputFailureReason> reported=Collections.synchronizedList(new ArrayList<>());
        void run()throws Exception {
            FastSamRuntimeOptions options=new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,1,false,
                System.nanoTime()+TimeUnit.SECONDS.toNanos(10),5000,false);
            CountDownLatch error=new CountDownLatch(1);
            FastSamEngine.Factory factory=new FastSamEngine.Factory(){
                public FastSamEngine create(){
                    long owner=Thread.currentThread().getId();
                    FastSamRuntimeInfo info=new FastSamRuntimeInfo(options,FastSamRuntimeOptions.Backend.CPU,null,owner,0);
                    return new FastSamEngine(){
                        public FastSamRuntimeInfo runtime(){return info;}
                        public Output invoke(int[] pixels,int width,int height){
                            if(invokeFailure instanceof Error)throw (Error)invokeFailure;
                            if(invokeFailure!=null)throw (RuntimeException)invokeFailure;
                            return new Output(raw,0,0,0);
                        }
                        public void close(){assertEquals(owner,Thread.currentThread().getId());nativeClosed.set(true);}
                    };
                }
                public void reportNativeOutputFault(FastSamModelContract.NativeOutputException value){
                    reported.add(value.reason);if(reportFailure!=null)throw reportFailure;
                }
            };
            FastSamRuntimeService<Void> service=new FastSamRuntimeService<>(1,options,new FastSamRuntimeService.Listener<Void>(){
                public void onResult(FastSamResult<Void> result){if(listenerFailure!=null)throw listenerFailure;fail("fault expected");}
                public void onError(Throwable value){failure.set(value);error.countDown();}
            },factory,System::nanoTime);
            try {
                service.start().get(3,TimeUnit.SECONDS);
                FastSamFrameToken frame=new FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE,1,1,0,0,
                    System.nanoTime(),"fixture",2,1);
                assertEquals(FastSamRuntimeService.Admission.ACCEPTED,service.submitArgb(2,1,new int[2],frame,null));
                assertTrue(error.await(3,TimeUnit.SECONDS));
                service.releasedFuture().get(3,TimeUnit.SECONDS);
            } finally {
                Throwable functionalCloseFailure=null;
                try{service.closeAsync().get(3,TimeUnit.SECONDS);}
                catch(ExecutionException expected){functionalCloseFailure=expected.getCause();}
                catch(CancellationException expected){functionalCloseFailure=expected;}
                assertSame("Functional close must preserve the observed operation failure",failure.get(),functionalCloseFailure);
                service.releasedFuture().get(3,TimeUnit.SECONDS);
            }
        }
    }
}
