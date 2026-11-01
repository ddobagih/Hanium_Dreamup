package kr.co.hanium.dreamup.walksafe.inference.unknown;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*;

/** Real Java owner threads with controlled, shared native identity and an independent physical ACK. */
public final class UnknownRuntimeTransferTestFactory {
    public final CompletableFuture<Void> physical = new CompletableFuture<>();
    public final AtomicInteger nativeCreates = new AtomicInteger(), nativeCloses = new AtomicInteger(), detaches = new AtomicInteger();
    public volatile RuntimeException closeFailure;
    public volatile RuntimeException detachFailure;
    public final String cohort = "e".repeat(64), modelId = "unknown-transfer-model";
    public VisionCohortLease lease(long epoch) { return new VisionCohortLease(cohort,"original-main",epoch); }
    public VisionModelHandle handle(long epoch) {
        return new VisionModelHandle(cohort,modelId,epoch,VisionRole.FASTSAM,FastSamModelContract.MODEL_ID,"{}");
    }
    public <T> FastSamRuntimeService<T> source(long epoch, FastSamRuntimeOptions options,
            FastSamRuntimeService.Listener<T> listener, LongSupplier clock) {
        return new FastSamRuntimeService<>(epoch,options,listener,new Factory(options,false,lease(1),handle(1)),clock);
    }
    public <T> FastSamRuntimeService<T> attach(long epoch, FastSamRuntimeOptions options,
            FastSamRuntimeService.Listener<T> listener, FastSamTransferredModel token,
            VisionCohortLease lease, VisionModelHandle handle, LongSupplier clock) {
        return FastSamRuntimeService.attachTransferred(epoch,options,listener,token,lease,handle,
            new Factory(options,true,lease,handle),clock);
    }
    private final class Factory implements FastSamEngine.Factory {
        final FastSamRuntimeOptions options; final boolean attached;
        final VisionCohortLease lease; final VisionModelHandle handle; boolean activated;
        Factory(FastSamRuntimeOptions options, boolean attached, VisionCohortLease lease, VisionModelHandle handle) {
            this.options=options;this.attached=attached;this.lease=lease;this.handle=handle;activated=!attached;
        }
        public boolean supportsTransfer(){ return true; }
        public CompletableFuture<Void> actualReleasedFuture(){ return physical; }
        public boolean canAdmit(){ return activated; }
        public void validateTransferredAttachment(){ if(!attached)throw new IllegalStateException("not attached"); }
        public void activateTransferred(){ activated=true; }
        public FastSamEngine create(){
            if(!attached)nativeCreates.incrementAndGet();
            return new FastSamEngine(){
                final long owner=Thread.currentThread().getId(); boolean detached;
                public FastSamRuntimeInfo runtime(){return new FastSamRuntimeInfo(options,options.requestedBackend,null,
                    owner,0,"not_applicable",null,null,0,1234,modelId,cohort);}
                public Output invoke(int[] argb,int width,int height){
                    if(owner!=Thread.currentThread().getId())throw new AssertionError("wrong model owner");
                    return new Output(new float[][]{new float[FastSamModelContract.DETECTION_FLOATS],new float[FastSamModelContract.PROTOTYPE_FLOATS]},0,0,0);
                }
                public FastSamTransferredModel detachForTransfer(){
                    if(owner!=Thread.currentThread().getId())throw new AssertionError("wrong detach owner");
                    if(detachFailure!=null)throw detachFailure;
                    detached=true;detaches.incrementAndGet();return new FastSamTransferredModel(lease,handle,options);
                }
                public void close(){
                    if(owner!=Thread.currentThread().getId())throw new AssertionError("wrong close owner");
                    if(!detached){nativeCloses.incrementAndGet();if(closeFailure!=null)throw closeFailure;physical.complete(null);}
                }
            };
        }
    }
}
