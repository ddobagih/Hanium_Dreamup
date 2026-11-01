package kr.co.hanium.dreamup.walksafe.inference.unknown;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPurpose;

public final class FastSamRuntimeServiceTest {
    @Test public void a900msLiveSampleIsSoftDiscardedWithoutClosingItsPreparedNativeModel()throws Exception {
        AtomicLong clock=new AtomicLong(1_000_000_000L);
        FastSamRuntimeOptions o=new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,1,false,
            clock.get()+TimeUnit.SECONDS.toNanos(60),800,false);
        CountDownLatch discarded=new CountDownLatch(1);AtomicInteger results=new AtomicInteger();
        AtomicReference<Engine> nativeModel=new AtomicReference<>();
        FastSamRuntimeService<Void> service=new FastSamRuntimeService<>(12,o,new FastSamRuntimeService.Listener<Void>(){
            public void onResult(FastSamResult<Void> result){results.incrementAndGet();}
            public void onDiscard(FastSamFrameToken token,String reason){assertEquals("stale_after_decode",reason);discarded.countDown();}
        },()->{
            Engine model=new Engine(o){public Output invoke(int[] pixels,int width,int height){
                clock.addAndGet(900_000_000L);return super.invoke(pixels,width,height);
            }};nativeModel.set(model);return model;
        },clock::get);
        try {
            service.start().get(3,TimeUnit.SECONDS);
            FastSamFrameToken frame=new FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA,12,1,1,1,
                clock.get(),"geometry",2,1);
            assertEquals(FastSamRuntimeService.Admission.ACCEPTED,service.submitArgb(2,1,new int[2],frame,null));
            await(discarded);service.consumerIdleFuture().get(3,TimeUnit.SECONDS);
            assertEquals(0,results.get());assertEquals(1,service.stats().stale);
            assertTrue(service.isReady());assertFalse(service.stats().stopping);
            assertFalse(nativeModel.get().closed.get());assertFalse(service.releasedFuture().isDone());
        } finally {service.closeAsync().get(3,TimeUnit.SECONDS);}
    }

    @Test public void pairSampleReadinessWaitsForTheWholeConsumerInsteadOfOnlyModelReady()throws Exception {
        FastSamRuntimeOptions o=options(true);
        CountDownLatch entered=new CountDownLatch(1),allowConsumerReturn=new CountDownLatch(1);
        FastSamRuntimeService<Void> service=new FastSamRuntimeService<>(12,o,result->{
            assertNotNull(result.rawOutputs);entered.countDown();await(allowConsumerReturn);
        },()->new Engine(o),System::nanoTime);
        try {
            service.start().get(3,TimeUnit.SECONDS);
            assertEquals(FastSamRuntimeService.Admission.ACCEPTED,
                service.submitArgb(2,1,new int[2],token(1),null,strictJob()));
            await(entered);
            assertTrue(service.isReady());
            CompletableFuture<Void> drained=service.consumerIdleFuture();
            assertFalse("A delivered sample is not complete while its listener still owns the flight",drained.isDone());
            allowConsumerReturn.countDown();
            drained.get(3,TimeUnit.SECONDS);
            assertFalse(service.stats().inflight);
            assertFalse(service.stats().stopping);
            assertFalse(service.releasedFuture().isDone());
            assertTrue(service.isReady());
        } finally {
            allowConsumerReturn.countDown();service.closeAsync().get(3,TimeUnit.SECONDS);
        }
    }

    @Test public void anIdlePreparedAuxiliaryStillOwnsItsNativeInstance()throws Exception {
        FastSamRuntimeOptions o=options(false);
        FastSamRuntimeService<Void> service=new FastSamRuntimeService<>(12,o,result->{},()->new Engine(o),System::nanoTime);
        try {
            service.start().get(3,TimeUnit.SECONDS);
            service.consumerIdleFuture().get(3,TimeUnit.SECONDS);
            assertTrue(service.isReady());
            assertFalse(service.releasedFuture().isDone());
            assertFalse(service.stats().nativeReleaseConfirmed);
        } finally {service.closeAsync().get(3,TimeUnit.SECONDS);}
    }

    @Test public void aSecondPairSampleCannotReuseThePreviousConsumersIdleAcknowledgement()throws Exception {
        FastSamRuntimeOptions o=options(true);
        CountDownLatch firstResult=new CountDownLatch(1),secondEntered=new CountDownLatch(1),allowSecondReturn=new CountDownLatch(1);
        FastSamRuntimeService<Void> service=new FastSamRuntimeService<>(12,o,result->{
            if(result.token.frameId==1)firstResult.countDown();
            else{secondEntered.countDown();await(allowSecondReturn);}
        },()->new Engine(o),System::nanoTime);
        try {
            service.start().get(3,TimeUnit.SECONDS);
            service.submitArgb(2,1,new int[2],token(1),null);await(firstResult);
            CompletableFuture<Void> first=service.consumerIdleFuture();first.get(3,TimeUnit.SECONDS);
            assertEquals(FastSamRuntimeService.Admission.ACCEPTED,
                service.submitArgb(2,1,new int[2],token(2),null));
            await(secondEntered);
            CompletableFuture<Void> second=service.consumerIdleFuture();
            assertNotSame(first,second);assertFalse(second.isDone());
            allowSecondReturn.countDown();second.get(3,TimeUnit.SECONDS);
            assertEquals(2,service.stats().cpuPostCompleted);
            assertFalse(service.releasedFuture().isDone());
        } finally {allowSecondReturn.countDown();service.closeAsync().get(3,TimeUnit.SECONDS);}
    }

    private static void await(CountDownLatch latch)throws Exception{assertTrue(latch.await(3,TimeUnit.SECONDS));}
    private static FastSamRuntimeOptions options(boolean raw){return new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,1,false,System.nanoTime()+TimeUnit.SECONDS.toNanos(10),5000,raw);}
    private static FastSamFrameToken token(long id){return token(id,FastSamFrameToken.Source.CALIBRATION_FIXTURE);}
    private static FastSamExecutionRequest strictJob(){return new FastSamExecutionRequest(VisionPurpose.BENCHMARK,
        System.nanoTime()+TimeUnit.SECONDS.toNanos(10),true);}
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
        s.start().get(3,TimeUnit.SECONDS);int[] pixels={7,9};assertEquals(FastSamRuntimeService.Admission.ACCEPTED,s.submitArgb(2,1,pixels,first,attachment,strictJob()));pixels[0]=99;await(entered);
        assertEquals(FastSamRuntimeService.Admission.ACCEPTED,s.submitArgb(2,1,new int[]{2,0},token(2),attachment,strictJob()));
        assertEquals(FastSamRuntimeService.Admission.REPLACED_PENDING,s.submitArgb(2,1,new int[]{3,0},token(3),attachment,strictJob()));
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
        assertEquals("A closed flight must not finish an unnecessary full prototype decode",0,s.stats().completed);
        assertTrue(s.stats().nativeReleaseConfirmed);assertFalse(s.stats().inflight);
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

    @Test public void remoteCloseFailureWaitsForActualDeathAndCanRecoverItsReleaseReceipt()throws Exception {
        FastSamRuntimeOptions o=options(false);RuntimeException marker=new RuntimeException("remote close result failed");
        CompletableFuture<Void> physical=new CompletableFuture<>();
        FastSamEngine.Factory factory=new FastSamEngine.Factory(){
            public FastSamEngine create(){Engine e=new Engine(o);e.closeFailure=marker;return e;}
            public CompletableFuture<Void> actualReleasedFuture(){return physical;}
        };
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,r->{},factory,System::nanoTime);
        s.start().get(3,TimeUnit.SECONDS);failed(s.closeAsync(),marker);
        assertFalse(s.releasedFuture().isDone());assertFalse(s.stats().nativeReleaseConfirmed);
        physical.complete(null);s.releasedFuture().get(3,TimeUnit.SECONDS);
        assertTrue(s.stats().nativeReleaseConfirmed);failed(s.closeAsync(),marker);
    }

    @Test public void remoteOpenFailureCannotLoseTheAcquiredCohortsPhysicalReceipt()throws Exception {
        FastSamRuntimeOptions o=options(false);RuntimeException marker=new RuntimeException("remote open failed");
        CompletableFuture<Void> physical=new CompletableFuture<>();
        FastSamEngine.Factory factory=new FastSamEngine.Factory(){
            public FastSamEngine create(){throw marker;}
            public CompletableFuture<Void> actualReleasedFuture(){return physical;}
        };
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,r->{},factory,System::nanoTime);
        failed(s.start(),marker);failed(s.closeAsync(),marker);
        assertFalse(s.releasedFuture().isDone());assertFalse(s.stats().nativeReleaseConfirmed);
        physical.complete(null);s.releasedFuture().get(3,TimeUnit.SECONDS);assertTrue(s.stats().nativeReleaseConfirmed);
    }

    @Test public void physicalRemoteAckCannotReleaseAConsumerThatIsStillDraining()throws Exception {
        FastSamRuntimeOptions o=options(false);CompletableFuture<Void> physical=new CompletableFuture<>();
        CountDownLatch entered=new CountDownLatch(1),consumerRelease=new CountDownLatch(1);
        FastSamEngine.Factory factory=new FastSamEngine.Factory(){
            public FastSamEngine create(){return new Engine(o);}
            public CompletableFuture<Void> actualReleasedFuture(){return physical;}
        };
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,r->{entered.countDown();await(consumerRelease);},factory,System::nanoTime);
        s.start().get(3,TimeUnit.SECONDS);s.submitArgb(2,1,new int[2],token(1),null);await(entered);
        CompletableFuture<Void> close=s.closeAsync();physical.complete(null);
        assertFalse(close.isDone());assertFalse(s.releasedFuture().isDone());
        consumerRelease.countDown();close.get(3,TimeUnit.SECONDS);s.releasedFuture().get(3,TimeUnit.SECONDS);
    }

    @Test public void closingDuringRemoteCreationRequestsControlCancellationOnceWithoutForgingRelease()throws Exception {
        FastSamRuntimeOptions o=options(false);CompletableFuture<Void> physical=new CompletableFuture<>();
        CountDownLatch entered=new CountDownLatch(1),cancelled=new CountDownLatch(1),returnLoad=new CountDownLatch(1);
        AtomicInteger cancellations=new AtomicInteger();RuntimeException marker=new RuntimeException("retired remote OPEN");
        FastSamEngine.Factory factory=new FastSamEngine.Factory(){
            public FastSamEngine create()throws Exception{entered.countDown();await(returnLoad);throw marker;}
            public CompletableFuture<Void> actualReleasedFuture(){return physical;}
            public void cancelPendingLoad(){cancellations.incrementAndGet();cancelled.countDown();}
        };
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,r->{},factory,System::nanoTime);
        s.start();await(entered);CompletableFuture<Void> close=s.closeAsync();s.closeAsync();await(cancelled);
        assertEquals(1,cancellations.get());assertFalse(close.isDone());assertFalse(s.releasedFuture().isDone());
        returnLoad.countDown();failed(close,marker);assertFalse(s.releasedFuture().isDone());
        physical.complete(null);s.releasedFuture().get(3,TimeUnit.SECONDS);
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

    private static FastSamFrameToken timedToken(long id,FastSamFrameToken.Source source,long captured) {
        return new FastSamFrameToken(source,12,id,source==FastSamFrameToken.Source.LIVE_CAMERA?id*100:0,
            source==FastSamFrameToken.Source.LIVE_CAMERA?id*100:0,captured,"geometry",2,1);
    }

    @Test public void slowUntimedCpuReferenceCanFinishBeforeNextGpuLiveCandidate()throws Exception {
        AtomicLong clock=new AtomicLong(TimeUnit.SECONDS.toNanos(1));
        long loadDeadline=TimeUnit.SECONDS.toNanos(120),fixtureDeadline=TimeUnit.SECONDS.toNanos(100);
        FastSamRuntimeOptions cpu=new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,1,false,
            loadDeadline,800,true,false,true,fixtureDeadline);
        CountDownLatch referenceSeen=new CountDownLatch(1);AtomicReference<FastSamResult<Void>> reference=new AtomicReference<>();
        FastSamFrameToken original=timedToken(1,FastSamFrameToken.Source.CALIBRATION_FIXTURE,clock.get());
        FastSamRuntimeService<Void> baseline=new FastSamRuntimeService<>(12,cpu,r->{reference.set(r);referenceSeen.countDown();},
            ()->new Engine(cpu){public Output invoke(int[] pixels,int w,int h){
                Output output=super.invoke(pixels,w,h);clock.addAndGet(TimeUnit.SECONDS.toNanos(6));return output;
            }},clock::get);
        baseline.start().get(3,TimeUnit.SECONDS);
        assertEquals(FastSamRuntimeService.Admission.ACCEPTED,baseline.submitArgb(2,1,new int[2],original,null,
            new FastSamExecutionRequest(VisionPurpose.REFERENCE,fixtureDeadline,true)));
        await(referenceSeen);assertSame(original,reference.get().token);assertEquals(TimeUnit.SECONDS.toNanos(1),original.capturedElapsedNs);
        assertNotNull(reference.get().rawOutputs);assertTrue(reference.get().captureToDecodeCompleteMs>5000);
        baseline.closeAsync().get(3,TimeUnit.SECONDS);assertTrue(baseline.stats().nativeReleaseConfirmed);

        FastSamRuntimeOptions gpu=new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.GPU,1,false,
            loadDeadline,800,false,false,true,fixtureDeadline);
        CountDownLatch liveSeen=new CountDownLatch(1);
        FastSamRuntimeService<Void> next=new FastSamRuntimeService<>(12,gpu,r->{
            assertEquals(FastSamRuntimeOptions.Backend.GPU,r.runtime.actualBackend);
            assertTrue(r.captureToDecodeCompleteMs<=800);assertNull(r.rawOutputs);liveSeen.countDown();
        },()->new Engine(gpu){
            public FastSamRuntimeInfo runtime(){return new FastSamRuntimeInfo(gpu,FastSamRuntimeOptions.Backend.GPU,null,owner,0);}
            public Output invoke(int[] pixels,int w,int h){Output output=super.invoke(pixels,w,h);clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));return output;}
        },clock::get);
        next.start().get(3,TimeUnit.SECONDS);
        assertEquals(FastSamRuntimeService.Admission.ACCEPTED,next.submitArgb(2,1,new int[2],
            timedToken(1,FastSamFrameToken.Source.LIVE_CAMERA,clock.get()),null));
        await(liveSeen);next.closeAsync().get(3,TimeUnit.SECONDS);assertTrue(next.stats().nativeReleaseConfirmed);
    }

    @Test public void fixtureDeadlineRejectsExpiredAdmissionAndPendingInference()throws Exception {
        AtomicLong clock=new AtomicLong(TimeUnit.SECONDS.toNanos(1));long deadline=TimeUnit.SECONDS.toNanos(10);
        FastSamRuntimeOptions o=new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,1,false,
            TimeUnit.SECONDS.toNanos(30),800,true,false,true,deadline);
        CountDownLatch firstSeen=new CountDownLatch(1),release=new CountDownLatch(1),discarded=new CountDownLatch(1);
        AtomicReference<Engine> engine=new AtomicReference<>();AtomicInteger results=new AtomicInteger();
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,new FastSamRuntimeService.Listener<Void>(){
            public void onResult(FastSamResult<Void> r)throws Exception{results.incrementAndGet();firstSeen.countDown();await(release);}
            public void onDiscard(FastSamFrameToken token,String reason){assertEquals(2,token.frameId);assertEquals("stale_before_inference",reason);discarded.countDown();}
        },()->{Engine e=new Engine(o);engine.set(e);return e;},clock::get);
        s.start().get(3,TimeUnit.SECONDS);
        s.submitArgb(2,1,new int[2],timedToken(1,FastSamFrameToken.Source.CALIBRATION_FIXTURE,clock.get()),null,
            new FastSamExecutionRequest(VisionPurpose.REFERENCE,deadline,true));await(firstSeen);
        assertEquals(FastSamRuntimeService.Admission.ACCEPTED,s.submitArgb(2,1,new int[2],
            timedToken(2,FastSamFrameToken.Source.CALIBRATION_FIXTURE,clock.get()),null,
            new FastSamExecutionRequest(VisionPurpose.REFERENCE,deadline,true)));
        clock.set(deadline);release.countDown();await(discarded);
        assertEquals(FastSamRuntimeService.Admission.STALE,s.submitArgb(2,1,new int[2],
            timedToken(3,FastSamFrameToken.Source.CALIBRATION_FIXTURE,clock.get()),null,
            new FastSamExecutionRequest(VisionPurpose.REFERENCE,deadline,true)));
        assertEquals(1,results.get());assertEquals(1,engine.get().calls.get());s.closeAsync().get(3,TimeUnit.SECONDS);
    }

    @Test public void fixtureDeadlineStillRejectsCompletionAtWorkDeadline()throws Exception {
        AtomicLong clock=new AtomicLong(TimeUnit.SECONDS.toNanos(1));long deadline=TimeUnit.SECONDS.toNanos(10);
        FastSamRuntimeOptions o=new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,1,false,
            TimeUnit.SECONDS.toNanos(30),800,true,false,true,deadline);
        CountDownLatch discarded=new CountDownLatch(1);AtomicInteger results=new AtomicInteger();
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,new FastSamRuntimeService.Listener<Void>(){
            public void onResult(FastSamResult<Void> r){results.incrementAndGet();}
            public void onDiscard(FastSamFrameToken token,String reason){assertEquals("stale_after_decode",reason);discarded.countDown();}
        },()->new Engine(o){public Output invoke(int[] pixels,int w,int h){Output output=super.invoke(pixels,w,h);clock.set(deadline);return output;}},clock::get);
        s.start().get(3,TimeUnit.SECONDS);s.submitArgb(2,1,new int[2],timedToken(1,FastSamFrameToken.Source.CALIBRATION_FIXTURE,clock.get()),null,
            new FastSamExecutionRequest(VisionPurpose.REFERENCE,deadline,true));
        await(discarded);assertEquals(0,results.get());s.closeAsync().get(3,TimeUnit.SECONDS);assertTrue(s.stats().nativeReleaseConfirmed);
    }

    @Test public void extendedFixtureDeadlineNeverExtendsLiveEightHundredMillisecondAge()throws Exception {
        AtomicLong clock=new AtomicLong(TimeUnit.SECONDS.toNanos(1));
        FastSamRuntimeOptions o=new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,1,false,
            TimeUnit.SECONDS.toNanos(120),800,true,false,true,TimeUnit.SECONDS.toNanos(100));
        CountDownLatch discarded=new CountDownLatch(1);AtomicInteger results=new AtomicInteger();
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,new FastSamRuntimeService.Listener<Void>(){
            public void onResult(FastSamResult<Void> r){results.incrementAndGet();}
            public void onDiscard(FastSamFrameToken token,String reason){assertEquals("stale_after_decode",reason);discarded.countDown();}
        },()->new Engine(o){public Output invoke(int[] pixels,int w,int h){
            Output output=super.invoke(pixels,w,h);clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(801));return output;
        }},clock::get);
        s.start().get(3,TimeUnit.SECONDS);s.submitArgb(2,1,new int[2],timedToken(1,FastSamFrameToken.Source.LIVE_CAMERA,clock.get()),null);
        await(discarded);assertEquals(0,results.get());s.closeAsync().get(3,TimeUnit.SECONDS);assertTrue(s.stats().nativeReleaseConfirmed);
    }

    @Test public void purposeAndRawRetentionAreFrozenPerJobAndNeverGuessedFromFixtureOptions()throws Exception {
        AtomicLong clock=new AtomicLong(TimeUnit.SECONDS.toNanos(1));
        FastSamRuntimeOptions o=new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,1,false,
            TimeUnit.SECONDS.toNanos(120),800,true,false,true,TimeUnit.SECONDS.toNanos(100));
        BlockingQueue<FastSamResult<Void>> results=new LinkedBlockingQueue<>();
        List<FastSamExecutionRequest> jobs=Collections.synchronizedList(new ArrayList<>());
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,results::add,()->new Engine(o){
            public Output invoke(int[] pixels,int w,int h,FastSamFrameToken token,FastSamExecutionRequest request){
                jobs.add(request);return super.invoke(pixels,w,h);
            }
        },clock::get);
        try {
            s.start().get(3,TimeUnit.SECONDS);
            s.submitArgb(2,1,new int[2],timedToken(1,FastSamFrameToken.Source.CALIBRATION_FIXTURE,clock.get()),null);
            FastSamResult<Void> performance=results.poll(3,TimeUnit.SECONDS);assertNotNull(performance);assertNull(performance.rawOutputs);
            assertEquals(VisionPurpose.BENCHMARK,jobs.get(0).purpose);assertFalse(jobs.get(0).captureRawOutputs);
            FastSamExecutionRequest strict=new FastSamExecutionRequest(VisionPurpose.BENCHMARK,TimeUnit.SECONDS.toNanos(4),true);
            s.submitArgb(2,1,new int[2],timedToken(2,FastSamFrameToken.Source.CALIBRATION_FIXTURE,clock.get()),null,strict);
            FastSamResult<Void> verified=results.poll(3,TimeUnit.SECONDS);assertNotNull(verified);assertNotNull(verified.rawOutputs);
            assertSame(strict,jobs.get(1));assertEquals(VisionPurpose.BENCHMARK,jobs.get(1).purpose);
            FastSamExecutionRequest reference=new FastSamExecutionRequest(VisionPurpose.REFERENCE,TimeUnit.SECONDS.toNanos(10),true);
            s.submitArgb(2,1,new int[2],timedToken(3,FastSamFrameToken.Source.CALIBRATION_FIXTURE,clock.get()),null,reference);
            FastSamResult<Void> referenced=results.poll(3,TimeUnit.SECONDS);assertNotNull(referenced);assertNotNull(referenced.rawOutputs);
            assertSame(reference,jobs.get(2));
        } finally {s.closeAsync().get(3,TimeUnit.SECONDS);}
    }

    @Test public void preDispatchJobExpiryIsDiscardedWithoutRetiringThePreparedModel()throws Exception {
        FastSamRuntimeOptions o=options(false);
        CountDownLatch discarded=new CountDownLatch(1),delivered=new CountDownLatch(1);
        AtomicReference<Engine> engine=new AtomicReference<>();
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,new FastSamRuntimeService.Listener<Void>(){
            public void onResult(FastSamResult<Void> r){delivered.countDown();}
            public void onDiscard(FastSamFrameToken token,String reason){assertEquals("execution_deadline_before_dispatch",reason);discarded.countDown();}
        },()->{
            Engine value=new Engine(o){public Output invoke(int[] pixels,int w,int h,FastSamFrameToken token,FastSamExecutionRequest request){
                if(token.frameId==1)throw new FastSamExecutionRequest.Expired();
                return super.invoke(pixels,w,h);
            }};engine.set(value);return value;
        },System::nanoTime);
        try {
            s.start().get(3,TimeUnit.SECONDS);s.submitArgb(2,1,new int[2],token(1),null);await(discarded);
            s.consumerIdleFuture().get(3,TimeUnit.SECONDS);
            assertTrue(s.isReady());assertFalse(s.stats().stopping);assertFalse(engine.get().closed.get());assertFalse(s.releasedFuture().isDone());
            s.submitArgb(2,1,new int[2],token(2),null);await(delivered);assertEquals(1,engine.get().calls.get());
        } finally {s.closeAsync().get(3,TimeUnit.SECONDS);}
    }

    @Test public void aRawRequestCannotExposeOutputsWhenTheFactoryCaptureOptionIsDisabled()throws Exception {
        FastSamRuntimeOptions o=options(false);BlockingQueue<FastSamResult<Void>> seen=new LinkedBlockingQueue<>();
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,seen::add,()->new Engine(o),System::nanoTime);
        try {
            s.start().get(3,TimeUnit.SECONDS);s.submitArgb(2,1,new int[2],token(1),null,strictJob());
            FastSamResult<Void> result=seen.poll(3,TimeUnit.SECONDS);assertNotNull(result);assertNull(result.rawOutputs);
        } finally {s.closeAsync().get(3,TimeUnit.SECONDS);}
    }

    @Test public void oversizedGeometryIsRejectedBeforeArgbCopyAndDoesNotRetireTheReadyModel()throws Exception {
        FastSamRuntimeOptions o=options(false);AtomicReference<Engine> engine=new AtomicReference<>();CountDownLatch delivered=new CountDownLatch(1);
        FastSamRuntimeService<Void> s=new FastSamRuntimeService<>(12,o,r->delivered.countDown(),()->{
            Engine value=new Engine(o);engine.set(value);return value;
        },System::nanoTime);
        try {
            s.start().get(3,TimeUnit.SECONDS);
            FastSamFrameToken tooWide=new FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE,12,1,0,0,
                System.nanoTime(),"original-geometry",2049,1);
            try {s.submitArgb(2049,1,new int[0],tooWide,null,strictJob());fail("Oversized geometry accepted");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("2048"));}
            assertEquals(2049,tooWide.width);assertEquals("original-geometry",tooWide.geometryId);
            assertEquals(0,s.stats().admitted);assertEquals(0,engine.get().calls.get());
            assertTrue(s.isReady());assertFalse(s.stats().stopping);assertFalse(s.releasedFuture().isDone());
            s.submitArgb(2,1,new int[2],token(2),null);await(delivered);assertEquals(1,engine.get().calls.get());
        } finally {s.closeAsync().get(3,TimeUnit.SECONDS);}
    }
}
