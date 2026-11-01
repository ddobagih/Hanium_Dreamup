package kr.co.hanium.dreamup.walksafe.inference.unknown;

import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*;

public final class FastSamTransferTest {
    private static FastSamRuntimeOptions options(){return new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,1,false,System.nanoTime()+TimeUnit.SECONDS.toNanos(10),5000,false);}
    private static FastSamFrameToken frame(long epoch,long id){return new FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE,epoch,id,0,0,System.nanoTime(),"geometry",2,1);}
    private static void await(CountDownLatch latch)throws Exception{assertTrue(latch.await(3,TimeUnit.SECONDS));}
    private static void failure(CompletableFuture<?> future)throws Exception{try{future.get(3,TimeUnit.SECONDS);fail("unexpected success");}catch(ExecutionException expected){}}
    private static final VisionCohortLease LEASE=new VisionCohortLease("a".repeat(64),"foreground",1);
    private static final VisionModelHandle HANDLE=new VisionModelHandle(LEASE.getCohortId(),"fastsam-instance",1,VisionRole.FASTSAM,FastSamModelContract.MODEL_ID,"{}");

    @Test public void transferDrainWaitsForListenerAndLateCloseCannotReleaseTheTransferredNativeModel()throws Exception {
        Model model=new Model();FastSamRuntimeOptions o=options();Factory factory=new Factory(model,o,false,LEASE,HANDLE);
        CountDownLatch listenerEntered=new CountDownLatch(1),listenerReturn=new CountDownLatch(1);
        FastSamRuntimeService<String> old=new FastSamRuntimeService<>(12,o,r->{assertEquals("old",r.attachment);listenerEntered.countDown();await(listenerReturn);},factory,System::nanoTime);
        old.start().get(3,TimeUnit.SECONDS);old.submitArgb(2,1,new int[2],frame(12,1),"old");await(listenerEntered);
        CompletableFuture<FastSamTransferredModel> drained=old.drainForTransfer();
        CompletableFuture<Void> lateClose=old.closeAsync();
        assertFalse(drained.isDone());assertFalse(lateClose.isDone());assertFalse(old.releasedFuture().isDone());
        listenerReturn.countDown();FastSamTransferredModel token=drained.get(3,TimeUnit.SECONDS);lateClose.get(3,TimeUnit.SECONDS);
        assertEquals(HANDLE.getModelInstanceId(),token.getModelInstanceId());assertFalse(token.isConsumed());
        assertEquals(0,model.proxyCloses.get());assertEquals(0,model.nativeCloses.get());
        assertFalse(old.isReady());assertFalse(old.stats().nativeReleaseConfirmed);assertFalse(old.releasedFuture().isDone());
        assertEquals(FastSamRuntimeService.Admission.CLOSED,old.submitArgb(2,1,new int[2],frame(12,2),"late"));
        model.physical.complete(null);old.releasedFuture().get(3,TimeUnit.SECONDS);assertTrue(old.stats().nativeReleaseConfirmed);
    }

    @Test public void finalCloseWinsBeforeTransferAndCannotBeChangedIntoDetach()throws Exception {
        Model model=new Model();FastSamRuntimeOptions o=options();Factory factory=new Factory(model,o,false,LEASE,HANDLE);
        FastSamRuntimeService<Void> old=new FastSamRuntimeService<>(12,o,r->{},factory,System::nanoTime);
        old.start().get(3,TimeUnit.SECONDS);old.closeAsync();failure(old.drainForTransfer());
        old.releasedFuture().get(3,TimeUnit.SECONDS);assertEquals(1,model.nativeCloses.get());assertEquals(0,model.detaches.get());
    }

    @Test public void dormantNewTypedListenerNeedsActivationAndUsesTheSameNativeInstance()throws Exception {
        Model model=new Model();FastSamRuntimeOptions o=options();Factory source=new Factory(model,o,false,LEASE,HANDLE);
        FastSamRuntimeService<String> old=new FastSamRuntimeService<>(12,o,r->fail("old listener revived"),source,System::nanoTime);
        old.start().get(3,TimeUnit.SECONDS);FastSamTransferredModel token=old.drainForTransfer().get(3,TimeUnit.SECONDS);
        Factory target=new Factory(model,options(),true,LEASE,HANDLE);CountDownLatch result=new CountDownLatch(1);
        FastSamRuntimeService<Integer> next=FastSamRuntimeService.attachTransferred(13,target.options,r->{assertEquals(42,r.attachment.intValue());result.countDown();},token,LEASE,HANDLE,target,System::nanoTime);
        next.start().get(3,TimeUnit.SECONDS);assertFalse(next.isReady());assertFalse(next.activateTransferred());
        assertEquals(FastSamRuntimeService.Admission.NOT_READY,next.submitArgb(2,1,new int[2],frame(13,1),42));
        assertEquals(0,model.invokes.get());target.adoptionAllowed=true;
        assertTrue(next.activateTransferred());assertTrue(next.isReady());
        assertEquals(FastSamRuntimeService.Admission.ACCEPTED,next.submitArgb(2,1,new int[2],frame(13,1),42));await(result);
        assertEquals(1,model.nativeCreations.get());assertEquals(1,model.detaches.get());
        assertFalse(old.releasedFuture().isDone());next.closeAsync().get(3,TimeUnit.SECONDS);
        old.releasedFuture().get(3,TimeUnit.SECONDS);assertEquals(1,model.nativeCloses.get());
    }

    @Test public void tokenReplayAndOptionMismatchCannotCreateAnotherWrapper()throws Exception {
        Model model=new Model();FastSamRuntimeOptions o=options();FastSamTransferredModel token=new FastSamTransferredModel(LEASE,HANDLE,o);
        FastSamRuntimeOptions wrong=new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.GPU,1,false,o.loadDeadlineElapsedNs,800,false);
        Factory target=new Factory(model,o,true,LEASE,HANDLE);
        assertThrows(IllegalArgumentException.class,()->FastSamRuntimeService.attachTransferred(13,wrong,r->{},token,LEASE,HANDLE,target,System::nanoTime));
        assertFalse(token.isConsumed());
        FastSamRuntimeService<Void> first=FastSamRuntimeService.attachTransferred(13,o,r->{},token,LEASE,HANDLE,target,System::nanoTime);
        assertTrue(token.isConsumed());
        assertThrows(IllegalStateException.class,()->FastSamRuntimeService.attachTransferred(14,o,r->{},token,LEASE,HANDLE,target,System::nanoTime));
        assertEquals(0,model.parentWrappers.get());first.closeAsync();model.physical.complete(null);
    }

    @Test public void staleAttachmentAuthorityDoesNotConsumeTheToken() {
        Model model=new Model();FastSamRuntimeOptions o=options();FastSamTransferredModel token=new FastSamTransferredModel(LEASE,HANDLE,o);
        Factory target=new Factory(model,o,true,LEASE,HANDLE);target.valid=false;
        assertThrows(IllegalStateException.class,()->FastSamRuntimeService.attachTransferred(13,o,r->{},token,LEASE,HANDLE,target,System::nanoTime));
        assertFalse(token.isConsumed());assertEquals(0,model.parentWrappers.get());
    }

    @Test public void unstartedTransferredAttachmentCancellationWaitsForActualRelease()throws Exception {
        Model model=new Model();FastSamRuntimeOptions o=options();FastSamTransferredModel token=new FastSamTransferredModel(LEASE,HANDLE,o);
        Factory target=new Factory(model,o,true,LEASE,HANDLE);
        FastSamRuntimeService<Void> next=FastSamRuntimeService.attachTransferred(13,o,r->{},token,LEASE,HANDLE,target,System::nanoTime);
        next.closeAsync();next.closeAsync();
        assertEquals(1,target.cancellations.get());assertEquals(0,model.parentWrappers.get());
        assertFalse(next.releasedFuture().isDone());assertFalse(next.stats().nativeReleaseConfirmed);
        model.physical.complete(null);next.releasedFuture().get(3,TimeUnit.SECONDS);assertTrue(next.stats().nativeReleaseConfirmed);
    }

    @Test public void transferredEpochMustMatchTheSameModelAndAtMostOneCohortTransfer() {
        FastSamRuntimeOptions o=options();FastSamTransferredModel token=new FastSamTransferredModel(LEASE,HANDLE,o);
        VisionCohortLease skipped=new VisionCohortLease(LEASE.getCohortId(),"camera",3);
        VisionModelHandle skippedHandle=new VisionModelHandle(skipped.getCohortId(),HANDLE.getModelInstanceId(),3,VisionRole.FASTSAM,FastSamModelContract.MODEL_ID,"{}");
        assertThrows(IllegalArgumentException.class,()->token.claimForAttach(o,skipped,skippedHandle));assertFalse(token.isConsumed());
        VisionCohortLease next=new VisionCohortLease(LEASE.getCohortId(),"camera",2);
        VisionModelHandle wrong=new VisionModelHandle(next.getCohortId(),"other-model",2,VisionRole.FASTSAM,FastSamModelContract.MODEL_ID,"{}");
        assertThrows(IllegalArgumentException.class,()->token.claimForAttach(o,next,wrong));assertFalse(token.isConsumed());
        VisionModelHandle current=new VisionModelHandle(next.getCohortId(),HANDLE.getModelInstanceId(),2,VisionRole.FASTSAM,FastSamModelContract.MODEL_ID,"{}");
        token.claimForAttach(o,next,current);assertTrue(token.isConsumed());
    }

    private static final class Model {
        final CompletableFuture<Void> physical=new CompletableFuture<>();
        final AtomicInteger nativeCreations=new AtomicInteger(),parentWrappers=new AtomicInteger(),nativeCloses=new AtomicInteger(),proxyCloses=new AtomicInteger(),detaches=new AtomicInteger(),invokes=new AtomicInteger();
    }
    private static final class Factory implements FastSamEngine.Factory {
        final Model model;final FastSamRuntimeOptions options;final boolean attached;
        final VisionCohortLease lease;final VisionModelHandle handle;final AtomicInteger cancellations=new AtomicInteger();
        volatile boolean adoptionAllowed,activated,valid=true;
        Factory(Model model,FastSamRuntimeOptions options,boolean attached,VisionCohortLease lease,VisionModelHandle handle){this.model=model;this.options=options;this.attached=attached;this.lease=lease;this.handle=handle;activated=!attached;}
        public boolean supportsTransfer(){return true;}
        public CompletableFuture<Void> actualReleasedFuture(){return model.physical;}
        public void cancelPendingLoad(){cancellations.incrementAndGet();}
        public void validateTransferredAttachment(){if(!valid||!attached)throw new IllegalStateException("stale attachment");}
        public void activateTransferred(){validateTransferredAttachment();if(!adoptionAllowed)throw new IllegalStateException("not adopted");activated=true;}
        public boolean canAdmit(){return valid&&activated;}
        public FastSamEngine create(){model.parentWrappers.incrementAndGet();if(!attached)model.nativeCreations.incrementAndGet();return new FastSamEngine(){
            final long owner=Thread.currentThread().getId();boolean detached;
            public FastSamRuntimeInfo runtime(){return new FastSamRuntimeInfo(options,options.requestedBackend,null,17,1,"not_applicable",null,null,0,1234,handle.getModelInstanceId(),handle.getCohortId());}
            public Output invoke(int[] argb,int width,int height){assertEquals(owner,Thread.currentThread().getId());model.invokes.incrementAndGet();return new Output(new float[][]{new float[FastSamModelContract.DETECTION_FLOATS],new float[FastSamModelContract.PROTOTYPE_FLOATS]},0,0,0);}
            public FastSamTransferredModel detachForTransfer(){assertEquals(owner,Thread.currentThread().getId());detached=true;model.detaches.incrementAndGet();return new FastSamTransferredModel(lease,handle,options);}
            public void close(){assertEquals(owner,Thread.currentThread().getId());model.proxyCloses.incrementAndGet();if(!detached){model.nativeCloses.incrementAndGet();model.physical.complete(null);}}
        };}
    }
}
