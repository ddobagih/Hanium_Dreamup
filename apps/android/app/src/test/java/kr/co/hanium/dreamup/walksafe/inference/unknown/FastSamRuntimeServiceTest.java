package kr.co.hanium.dreamup.walksafe.inference.unknown;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public final class FastSamRuntimeServiceTest {
    private static void await(CountDownLatch latch)throws Exception{assertTrue(latch.await(3,TimeUnit.SECONDS));}
    private static FastSamRuntimeOptions options(boolean raw){return new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,1,false,System.nanoTime()+TimeUnit.SECONDS.toNanos(10),5000,raw);}
    private static FastSamFrameToken token(long id){return token(id,FastSamFrameToken.Source.CALIBRATION_FIXTURE);}
    private static FastSamFrameToken token(long id,FastSamFrameToken.Source source){return new FastSamFrameToken(source,12,id,source==FastSamFrameToken.Source.LIVE_CAMERA?id*100:0,source==FastSamFrameToken.Source.LIVE_CAMERA?id*100:0,System.nanoTime(),"geometry",2,1);}
    private static void failed(CompletableFuture<?> f,Throwable expected)throws Exception {try{f.get(3,TimeUnit.SECONDS);fail("future succeeded");}catch(ExecutionException error){assertSame(expected,error.getCause());}}
    private static class Engine implements FastSamEngine {
        final long owner=Thread.currentThread().getId();final FastSamRuntimeInfo info;final List<Integer> pixels=Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger calls=new AtomicInteger();final AtomicBoolean closed=new AtomicBoolean();
        Throwable invokeFailure,closeFailure;boolean nonFinite;CountDownLatch invokeStarted,invokeRelease;
        Engine(FastSamRuntimeOptions options){info=new FastSamRuntimeInfo(options,FastSamRuntimeOptions.Backend.CPU,null,owner,0);}
        public FastSamRuntimeInfo runtime(){return info;}
        public Output invoke(int[] argb,int w,int h){assertEquals(owner,Thread.currentThread().getId());calls.incrementAndGet();pixels.add(argb[0]);
            if(invokeStarted!=null){invokeStarted.countDown();try{await(invokeRelease);}catch(Exception e){throw new RuntimeException(e);}}
            if(invokeFailure!=null)throw (RuntimeException)invokeFailure;
            float[][] out={new float[FastSamModelContract.DETECTION_FLOATS],new float[FastSamModelContract.PROTOTYPE_FLOATS]};
            if(nonFinite)out[1][out[1].length-1]=Float.NaN;
            return new Output(out,1,2,3);
        }
        public void close(){assertEquals(owner,Thread.currentThread().getId());if(closeFailure!=null)throw (RuntimeException)closeFailure;closed.set(true);}
    }

    @Test public void latestOneAndOwnedSnapshotSpanSlowConsumer()throws Exception {
        FastSamRuntimeOptions o=options(true);AtomicReference<Engine> engine=new AtomicReference<>();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),second=new CountDownLatch(1);List<Long> frames=Collections.synchronizedList(new ArrayList<>());Object attachment=new Object();FastSamFrameToken first=token(1);
        FastSamRuntimeService<Object> s=new FastSamRuntimeService<>(12,o,r->{
            assertSame(attachment,r.attachment);assertNotEquals(r.runtime.modelOwnerThreadId,Thread.currentThread().getId());
            assertTrue(r.rawOutputs.detections().isReadOnly());assertEquals(0,r.masks.size());frames.add(r.token.frameId);
            if(r.token.frameId==1){assertSame(first,r.token);entered.countDown();await(release);}else second.countDown();
        },()->{Engine e=new Engine(o);engine.set(e);return e;},System::nanoTime);
        s.start().get(3,TimeUnit.SECONDS);int[] pixels={7,9};assertEquals(FastSamRuntimeService.Admission.ACCEPTED,s.submitArgb(2,1,pixels,first,attachment));pixels[0]=99;await(entered);
        assertEquals(FastSamRuntimeService.Admission.ACCEPTED,s.submitArgb(2,1,new int[]{2,0},token(2),attachment));
        assertEquals(FastSamRuntimeService.Admission.REPLACED_PENDING,s.submitArgb(2,1,new int[]{3,0},token(3),attachment));
        assertEquals(1,engine.get().calls.get());assertTrue(s.stats().inflight&&s.stats().pending);release.countDown();await(second);
        s.closeAsync().get(3,TimeUnit.SECONDS);assertEquals(Arrays.asList(1L,3L),frames);assertEquals(Arrays.asList(7,3),engine.get().pixels);assertEquals(1,s.stats().replaced);assertTrue(s.stats().nativeReleaseConfirmed);assertTrue(engine.get().closed.get());
    }

    @Test public void warmCalibrationServiceDoesNotRetainLiveRaw()throws Exception {
        FastSamRuntimeOptions o=options(true);CountDownLatch seen=new CountDownLatch(1);FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,r->{assertNull(r.rawOutputs);seen.countDown();},()->new Engine(o),System::nanoTime);
        s.start().get(3,TimeUnit.SECONDS);assertEquals(12,s.getSessionEpoch());s.submitArgb(2,1,new int[2],token(1,FastSamFrameToken.Source.LIVE_CAMERA),null);await(seen);s.closeAsync().get(3,TimeUnit.SECONDS);
    }

    @Test public void pendingDiscardKeepsWarmModelAndCloseWaitsForConsumer()throws Exception {
        FastSamRuntimeOptions o=options(false);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Engine> engine=new AtomicReference<>();AtomicInteger callbacks=new AtomicInteger();
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,r->{callbacks.incrementAndGet();entered.countDown();await(release);},()->{Engine e=new Engine(o);engine.set(e);return e;},System::nanoTime);
        s.start().get(3,TimeUnit.SECONDS);s.submitArgb(2,1,new int[2],token(1),null);await(entered);s.submitArgb(2,1,new int[2],token(2),null);assertTrue(s.discardPending("paused"));assertFalse(s.stats().pending);assertEquals(1,s.stats().discardedPending);assertFalse(engine.get().closed.get());
        CompletableFuture<Void> closed=s.closeAsync();assertFalse(closed.isDone());assertFalse(s.releasedFuture().isDone());release.countDown();closed.get(3,TimeUnit.SECONDS);assertEquals(1,callbacks.get());assertTrue(engine.get().closed.get());
    }

    @Test public void stopDuringNativeInvokeDrainsButDoesNotPublish()throws Exception {
        FastSamRuntimeOptions o=options(false);CountDownLatch invoke=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger result=new AtomicInteger();AtomicReference<Engine> e=new AtomicReference<>();
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,r->result.incrementAndGet(),()->{Engine x=new Engine(o);x.invokeStarted=invoke;x.invokeRelease=release;e.set(x);return x;},System::nanoTime);
        s.start().get(3,TimeUnit.SECONDS);s.submitArgb(2,1,new int[2],token(1),null);await(invoke);CompletableFuture<Void> f=s.closeAsync();assertFalse(f.isDone());release.countDown();f.get(3,TimeUnit.SECONDS);assertEquals(0,result.get());assertEquals(1,s.stats().discardedAtClose);assertTrue(e.get().closed.get());
    }

    @Test public void callbackFailureCannotReserveSuccessorAndReleaseIsIndependent()throws Exception {
        FastSamRuntimeOptions o=options(false);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),errorSeen=new CountDownLatch(1);RuntimeException marker=new RuntimeException("depth consumer failed");AtomicReference<Engine> engine=new AtomicReference<>();
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,new FastSamRuntimeService.Listener<Void>(){
            public void onResult(FastSamResult<Void> r)throws Exception{entered.countDown();await(release);throw marker;}
            public void onError(Throwable e){assertSame(marker,e);errorSeen.countDown();}
        },()->{Engine e=new Engine(o);engine.set(e);return e;},System::nanoTime);
        s.start().get(3,TimeUnit.SECONDS);s.submitArgb(2,1,new int[2],token(1),null);await(entered);s.submitArgb(2,1,new int[2],token(2),null);release.countDown();await(errorSeen);s.releasedFuture().get(3,TimeUnit.SECONDS);failed(s.closeAsync(),marker);assertEquals(1,engine.get().calls.get());assertTrue(s.stats().nativeReleaseConfirmed);
    }

    @Test public void invokeFailureAndNativeCloseFailureRemainDistinct()throws Exception {
        FastSamRuntimeOptions o=options(false);RuntimeException invoke=new RuntimeException("invoke"),close=new RuntimeException("native close");CountDownLatch error=new CountDownLatch(1);
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,new FastSamRuntimeService.Listener<Void>(){public void onResult(FastSamResult<Void> r){fail();}public void onError(Throwable e){error.countDown();}},()->{Engine e=new Engine(o);e.invokeFailure=invoke;e.closeFailure=close;return e;},System::nanoTime);
        s.start().get(3,TimeUnit.SECONDS);s.submitArgb(2,1,new int[2],token(1),null);await(error);failed(s.releasedFuture(),close);failed(s.closeAsync(),invoke);assertFalse(s.stats().nativeReleaseConfirmed);
    }

    @Test public void finalPrototypeNaNIsFatalBeforeResult()throws Exception {
        FastSamRuntimeOptions o=options(false);CountDownLatch error=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,new FastSamRuntimeService.Listener<Void>(){public void onResult(FastSamResult<Void> r){fail();}public void onError(Throwable e){failure.set(e);error.countDown();}},()->{Engine e=new Engine(o);e.nonFinite=true;return e;},System::nanoTime);
        s.start().get(3,TimeUnit.SECONDS);s.submitArgb(2,1,new int[2],token(1),null);await(error);s.releasedFuture().get(3,TimeUnit.SECONDS);failed(s.closeAsync(),failure.get());assertTrue(s.stats().nativeReleaseConfirmed);
    }

    @Test public void deadlineReportsTimeoutBeforeBlockedLoadCanRelease()throws Exception {
        CountDownLatch loadStarted=new CountDownLatch(1),loadRelease=new CountDownLatch(1);FastSamRuntimeOptions o=new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,1,false,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(100),5000,false);AtomicReference<Engine> e=new AtomicReference<>();
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,r->fail(),()->{loadStarted.countDown();await(loadRelease);Engine x=new Engine(o);e.set(x);return x;},System::nanoTime);
        CompletableFuture<FastSamRuntimeInfo> ready=s.start();await(loadStarted);try{ready.get(2,TimeUnit.SECONDS);fail();}catch(ExecutionException error){assertTrue(error.getCause() instanceof TimeoutException);}assertFalse(s.releasedFuture().isDone());loadRelease.countDown();s.releasedFuture().get(3,TimeUnit.SECONDS);assertTrue(e.get().closed.get());assertTrue(s.stats().nativeReleaseConfirmed);
    }

    @Test public void freshnessEpochAndDuplicateGatesPrecedeCopy()throws Exception {
        FastSamRuntimeOptions o=options(false);CountDownLatch result=new CountDownLatch(1);FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,r->result.countDown(),()->new Engine(o),System::nanoTime);
        assertEquals(FastSamRuntimeService.Admission.NOT_READY,s.submitArgb(2,1,new int[2],token(1),null));s.start().get(3,TimeUnit.SECONDS);
        FastSamFrameToken bad=new FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE,13,1,0,0,System.nanoTime(),"geometry",2,1);assertEquals(FastSamRuntimeService.Admission.WRONG_EPOCH,s.submitArgb(2,1,new int[2],bad,null));
        FastSamFrameToken old=new FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE,12,1,0,0,System.nanoTime()-TimeUnit.SECONDS.toNanos(6),"geometry",2,1);assertEquals(FastSamRuntimeService.Admission.STALE,s.submitArgb(2,1,new int[2],old,null));
        s.submitArgb(2,1,new int[2],token(1),null);await(result);assertEquals(FastSamRuntimeService.Admission.OUT_OF_ORDER,s.submitArgb(2,1,new int[2],token(1),null));s.closeAsync().get(3,TimeUnit.SECONDS);assertEquals(FastSamRuntimeService.Admission.CLOSED,s.submitArgb(2,1,new int[2],token(2),null));
    }

    @Test public void wrongModelRoleAnd640ShapesAreRejected() {
        FastSamModelContract.validate(FastSamModelContract.SHA256,new int[]{1,768,768,3},new int[][]{{1,37,12096},{1,192,192,32}},true);
        assertThrows(IllegalArgumentException.class,()->FastSamModelContract.validate("primary768sha",new int[]{1,768,768,3},new int[][]{{1,300,6}},true));
        assertThrows(IllegalArgumentException.class,()->FastSamModelContract.validate(FastSamModelContract.SHA256,new int[]{1,640,640,3},new int[][]{{1,37,8400},{1,160,160,32}},true));
        assertThrows(IllegalArgumentException.class,()->FastSamModelContract.validate(FastSamModelContract.SHA256,new int[]{1,768,768,3},new int[][]{{1,37,12096},{1,192,192,32}},false));
    }

    @Test public void initializationFailureReleaseProofDoesNotHideCleanupFailure()throws Exception {
        FastSamRuntimeOptions o=options(false);RuntimeException unsupported=new RuntimeException("GPU unavailable, released");
        FastSamRuntimeService<Void> clean=new FastSamRuntimeService<>(12,o,r->fail(),()->{throw unsupported;},System::nanoTime);
        failed(clean.start(),unsupported);clean.releasedFuture().get(3,TimeUnit.SECONDS);failed(clean.closeAsync(),unsupported);assertTrue(clean.stats().nativeReleaseConfirmed);
        TfliteFastSamEngine.NativeReleaseFailure uncertain=new TfliteFastSamEngine.NativeReleaseFailure(new RuntimeException("native cleanup failed"));
        FastSamRuntimeService<Void> dirty=new FastSamRuntimeService<>(12,o,r->fail(),()->{throw uncertain;},System::nanoTime);
        failed(dirty.start(),uncertain);failed(dirty.releasedFuture(),uncertain);failed(dirty.closeAsync(),uncertain);assertFalse(dirty.stats().nativeReleaseConfirmed);
    }
}
