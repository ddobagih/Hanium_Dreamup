package kr.co.hanium.dreamup.walksafe.inference.unknown;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.os.SystemClock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.DecodeResult;
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.FastSamDecoder;
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionCohortLease;
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionModelHandle;
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeContext;
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPurpose;
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeProtocol;

/** Independent FastSAM768 service. It never borrows the primary detector's owner or closes an Image.
 * One latest pending snapshot plus one whole inference/decode/consumer flight bounds memory/work.
 * The consumer runs on the CPU post thread and must recheck age/epoch after its depth processing. */
public final class FastSamRuntimeService<T> implements AutoCloseable {
    public enum Admission { ACCEPTED, REPLACED_PENDING, NOT_READY, CLOSED, STALE, WRONG_EPOCH, OUT_OF_ORDER, COPY_BUSY }
    public interface Listener<T> {
        void onResult(FastSamResult<T> result)throws Exception;
        default void onError(Throwable error) {}
        default void onDiscard(FastSamFrameToken token,String reason) {}
    }
    public static final class Stats {
        public final long admitted,replaced,completed,stale,discardedAtClose,discardedPending,rejected,cpuPostCompleted;
        public final boolean pending,inflight,copying,stopping,modelOwnerAlive,cpuPostOwnerAlive,nativeReleaseConfirmed;
        public final String failure;
        Stats(FastSamRuntimeService<?> s) {
            admitted=s.admitted;replaced=s.replaced;completed=s.completed;stale=s.stale;
            discardedAtClose=s.discardedAtClose;discardedPending=s.discardedPending;rejected=s.rejected;cpuPostCompleted=s.cpuPostCompleted;
            pending=s.pending!=null;inflight=s.inflight;copying=s.copying;stopping=s.stopping;
            modelOwnerAlive=s.modelOwner.isAlive();cpuPostOwnerAlive=s.cpuPostOwner.isAlive();
            nativeReleaseConfirmed=s.physicalRelease.confirmed;
            failure=s.failure==null?null:s.failure.toString();
        }
    }
    private final Object lock=new Object();
    private final long sessionEpoch;
    private final FastSamRuntimeOptions options;
    private final Listener<T> listener;
    private final FastSamEngine.Factory factory;
    private final LongSupplier clock;
    private final CompletableFuture<FastSamRuntimeInfo> ready=new CompletableFuture<>();
    private final CompletableFuture<Void> closed=new CompletableFuture<>();
    private final PhysicalReleaseState physicalRelease=new PhysicalReleaseState();
    private final CompletableFuture<Void> released=physicalRelease.released;
    private final CompletableFuture<FastSamTransferredModel> transferred=new CompletableFuture<>();
    private CompletableFuture<Void> consumerIdle=CompletableFuture.completedFuture(null);
    private final Thread modelOwner,cpuPostOwner,loadWatchdog;
    private final FastSamYuvCopy.YuvScratch copyScratch=new FastSamYuvCopy.YuvScratch();
    private enum TerminalMode { FINAL_CLOSE, TRANSFER_DRAIN }
    private TerminalMode terminalMode;
    private final boolean transferredConsumer;
    private boolean activated;
    private boolean started,stopping,inflight,copying,cpuPostTerminated;
    private FrozenInput<T> pending;
    private PostJob<T> pendingPost;
    private Throwable failure;
    private long lastFrameId=-1,lastCpuTimestampNs=-1;
    private long admitted,replaced,completed,stale,discardedAtClose,discardedPending,rejected,cpuPostCompleted;

    public FastSamRuntimeService(Context context,long sessionEpoch,FastSamRuntimeOptions options,Listener<T> listener) {
        this(sessionEpoch,options,listener,engineFactory(context,options),
            SystemClock::elapsedRealtimeNanos);
    }
    private static FastSamEngine.Factory engineFactory(Context context,FastSamRuntimeOptions options) {
        String owner=VisionRuntimeContext.ownerId(Objects.requireNonNull(context,"context"));
        // The factory captures the immutable owner ref before normalizing its stored Context.
        return RemoteFastSamEngine.factory(context,options,owner);
    }
    /** Transitional fixture owner only. A fixture child must never spawn a production grandchild. */
    public static <T> FastSamRuntimeService<T> createLocalForFixture(Context context,long sessionEpoch,
            FastSamRuntimeOptions options,Listener<T> listener) {
        FastSamNativeBridge.requireFixtureProcess(Objects.requireNonNull(context,"context"));
        Context app=context.getApplicationContext();
        return new FastSamRuntimeService<>(sessionEpoch,options,listener,()->new TfliteFastSamEngine(app,options),
            SystemClock::elapsedRealtimeNanos);
    }
    FastSamRuntimeService(long sessionEpoch,FastSamRuntimeOptions options,Listener<T> listener,
            FastSamEngine.Factory factory,LongSupplier clock) {
        this(sessionEpoch,options,listener,factory,clock,false);
    }
    FastSamRuntimeService(long sessionEpoch,FastSamRuntimeOptions options,Listener<T> listener,
            FastSamEngine.Factory factory,LongSupplier clock,boolean dormant) {
        if(sessionEpoch<=0)throw new IllegalArgumentException("session epoch must be positive");
        this.sessionEpoch=sessionEpoch;this.options=Objects.requireNonNull(options);
        this.listener=Objects.requireNonNull(listener);this.factory=Objects.requireNonNull(factory);this.clock=clock;
        transferredConsumer=dormant;activated=!dormant;
        modelOwner=new Thread(this::modelLoop,"walksafe-fastsam-model");
        cpuPostOwner=new Thread(this::postLoop,"walksafe-fastsam-post");
        loadWatchdog=new Thread(this::watchLoad,"walksafe-fastsam-load-deadline");
        modelOwner.setDaemon(true);cpuPostOwner.setDaemon(true);loadWatchdog.setDaemon(true);
    }

    public CompletableFuture<FastSamTransferredModel> drainForTransfer() {
        synchronized(lock) {
            if(terminalMode==TerminalMode.TRANSFER_DRAIN)return transferred;
            if(terminalMode!=null || stopping || !started || !ready.isDone() || ready.isCompletedExceptionally()
                    || !activated || !factory.canAdmit() || !factory.supportsTransfer()
                    || factory.actualReleasedFuture()==null)return failedFuture(new IllegalStateException("FastSAM transfer is not available"));
            terminalMode=TerminalMode.TRANSFER_DRAIN;stopping=true;dropPending();lock.notifyAll();
            return transferred;
        }
    }
    public boolean activateTransferred(){synchronized(lock){
        if(!transferredConsumer || stopping || !ready.isDone() || ready.isCompletedExceptionally())return false;
        try{factory.activateTransferred();activated=true;return factory.canAdmit();}
        catch(RuntimeException rejected){return false;}
    }}
    public static <T> FastSamRuntimeService<T> attachTransferred(Context context,long epoch,
            FastSamRuntimeOptions options,Listener<T> listener,FastSamTransferredModel token,
            VisionCohortLease lease,VisionModelHandle handle) {
        return attachTransferred(epoch,options,listener,token,lease,handle,
            RemoteFastSamEngine.attachedFactory(context,options,lease,handle),SystemClock::elapsedRealtimeNanos);
    }
    static <T> FastSamRuntimeService<T> attachTransferred(long epoch,FastSamRuntimeOptions options,
            Listener<T> listener,FastSamTransferredModel token,VisionCohortLease lease,VisionModelHandle handle,
            FastSamEngine.Factory factory,LongSupplier clock) {
        if(epoch<=0)throw new IllegalArgumentException("session epoch must be positive");
        Objects.requireNonNull(listener);Objects.requireNonNull(clock);
        if(factory.actualReleasedFuture()==null)throw new IllegalArgumentException("Transferred model needs a physical receipt");
        factory.validateTransferredAttachment();
        token.claimForAttach(options,lease,handle);
        return new FastSamRuntimeService<>(epoch,options,listener,factory,clock,true);
    }

    /** Absolute elapsedRealtime deadline belongs to the caller's total (at most300s) check budget. */
    public CompletableFuture<FastSamRuntimeInfo> start() {
        boolean retireAttachment=false;
        synchronized(lock) {
            if(started || stopping)return ready;
            long remaining=options.loadDeadlineElapsedNs-clock.getAsLong();
            if(remaining<=0) {
                TimeoutException timeout=new TimeoutException("FastSAM load budget expired before start");
                failure=timeout;stopping=true;terminalMode=TerminalMode.FINAL_CLOSE;
                if(transferredConsumer)retireAttachment=true;else physicalRelease.local(null);
                ready.completeExceptionally(timeout);closed.completeExceptionally(timeout);
            } else {
                if(remaining>TimeUnit.SECONDS.toNanos(300))
                    throw new IllegalArgumentException("Load deadline must be within the remaining300s total budget");
                started=true;cpuPostOwner.start();modelOwner.start();loadWatchdog.start();
            }
        }
        if(retireAttachment){cancelPendingLoad();followPhysicalRelease(null);}
        return ready;
    }

    /** Capture can skip tracking/copying during cold preparation without starting native work. */
    public boolean isReady() {
        synchronized(lock){return !stopping && activated && ready.isDone() && !ready.isCompletedExceptionally() && factory.canAdmit();}
    }

    /** Synchronous, stride-aware copy while the caller still owns an open Image. */
    public Admission submit(Image image,FastSamFrameToken token,T frozenAttachment) {
        return submit(image,token,frozenAttachment,defaultRequest(token));
    }
    public Admission submit(Image image,FastSamFrameToken token,T frozenAttachment,FastSamExecutionRequest request) {
        Objects.requireNonNull(image,"image");Objects.requireNonNull(token,"token");
        validateDimensions(token);
        validateRequest(token,request);
        if(token.source!=FastSamFrameToken.Source.LIVE_CAMERA || image.getFormat()!=ImageFormat.YUV_420_888
                || image.getWidth()!=token.width || image.getHeight()!=token.height
                || image.getTimestamp()!=token.cpuImageTimestampNs)
            throw new IllegalArgumentException("Image does not match frozen FastSAM token");
        Rect crop=image.getCropRect();
        if(crop==null || crop.left!=0 || crop.top!=0 || crop.right!=token.width || crop.bottom!=token.height)
            throw new IllegalArgumentException("FastSAM requires the same full CPU-image geometry as its depth token");
        Admission gate=beginCopy(token,request);if(gate!=null)return gate;
        long copyStarted=clock.getAsLong();
        try {
            Image.Plane[] p=image.getPlanes();if(p.length!=3)throw new IllegalArgumentException("YUV420 needs three planes");
            int[] argb=FastSamYuvCopy.yuv420ToArgb(token.width,token.height,
                p[0].getBuffer(),p[0].getRowStride(),p[0].getPixelStride(),
                p[1].getBuffer(),p[1].getRowStride(),p[1].getPixelStride(),
                p[2].getBuffer(),p[2].getRowStride(),p[2].getPixelStride(),copyScratch);
            return publish(new FrozenInput<>(token,request,frozenAttachment,argb,(clock.getAsLong()-copyStarted)/1e6));
        } finally { synchronized(lock){copying=false;lock.notifyAll();} signalConsumerIdle(); }
    }

    /** Fixture/owned RGB entry. Clone completes before return; later caller mutation is harmless. */
    public Admission submitArgb(int width,int height,int[] argb,FastSamFrameToken token,T frozenAttachment) {
        Objects.requireNonNull(token,"token");
        // Compatibility admission still rejects an already-old capture. Explicit static requests
        // carry their own whole-job deadline; neither path guesses REFERENCE from an options TTL.
        if(!token.isFreshAt(clock.getAsLong(),options.maximumResultAgeMs))return Admission.STALE;
        return submitArgb(width,height,argb,token,frozenAttachment,defaultRequest(token));
    }
    public Admission submitArgb(int width,int height,int[] argb,FastSamFrameToken token,T frozenAttachment,
            FastSamExecutionRequest request) {
        Objects.requireNonNull(argb,"argb");Objects.requireNonNull(token,"token");
        validateDimensions(token);
        validateRequest(token,request);
        if(width!=token.width || height!=token.height || argb.length!=Math.multiplyExact(width,height))
            throw new IllegalArgumentException("ARGB does not match frozen FastSAM token");
        Admission gate=beginCopy(token,request);if(gate!=null)return gate;
        long copyStarted=clock.getAsLong();
        try{return publish(new FrozenInput<>(token,request,frozenAttachment,argb.clone(),(clock.getAsLong()-copyStarted)/1e6));}
        finally{synchronized(lock){copying=false;lock.notifyAll();}signalConsumerIdle();}
    }
    private FastSamExecutionRequest defaultRequest(FastSamFrameToken token) {
        Objects.requireNonNull(token,"token");
        VisionPurpose purpose=token.source==FastSamFrameToken.Source.LIVE_CAMERA?VisionPurpose.LIVE:VisionPurpose.BENCHMARK;
        long budget=TimeUnit.MILLISECONDS.toNanos(VisionRuntimeProtocol.INSTANCE.operationBudget(purpose));
        long now=clock.getAsLong();
        return new FastSamExecutionRequest(purpose,now>Long.MAX_VALUE-budget?Long.MAX_VALUE:now+budget,false);
    }
    private static void validateRequest(FastSamFrameToken token,FastSamExecutionRequest request) {
        Objects.requireNonNull(request,"request");
        if((token.source==FastSamFrameToken.Source.LIVE_CAMERA)!=(request.purpose==VisionPurpose.LIVE))
            throw new IllegalArgumentException("Execution purpose does not match the captured source");
    }
    private static void validateDimensions(FastSamFrameToken token) {
        if(token.width>2048 || token.height>2048)
            throw new IllegalArgumentException("FastSAM parent input exceeds the 2048-pixel wire contract");
    }
    private Admission beginCopy(FastSamFrameToken token,FastSamExecutionRequest request) {
        synchronized(lock) {
            Admission gate=gate(token,request);
            if(gate!=null){rejected++;return gate;}
            if(copying){rejected++;return Admission.COPY_BUSY;}
            if(consumerIdle.isDone())consumerIdle=new CompletableFuture<>();
            copying=true;return null;
        }
    }
    private Admission gate(FastSamFrameToken token,FastSamExecutionRequest request) {
        if(stopping)return Admission.CLOSED;
        if(!activated || !ready.isDone() || ready.isCompletedExceptionally() || !factory.canAdmit())return Admission.NOT_READY;
        if(token.sessionEpoch!=sessionEpoch)return Admission.WRONG_EPOCH;
        if(!isFresh(token,request))return Admission.STALE;
        if(token.frameId<=lastFrameId || (token.source==FastSamFrameToken.Source.LIVE_CAMERA
                && token.cpuImageTimestampNs<=lastCpuTimestampNs))return Admission.OUT_OF_ORDER;
        return null;
    }
    private boolean isFresh(FastSamFrameToken token,FastSamExecutionRequest request) {
        long now=clock.getAsLong();
        return now>=token.capturedElapsedNs && now<request.absoluteDeadlineElapsedNs &&
            (token.source!=FastSamFrameToken.Source.LIVE_CAMERA || token.isFreshAt(now,options.maximumResultAgeMs));
    }
    private Admission publish(FrozenInput<T> input) {
        synchronized(lock) {
            Admission gate=gate(input.token,input.request);if(gate!=null){rejected++;return gate;}
            boolean replace=pending!=null;if(replace)replaced++;
            pending=input;lastFrameId=input.token.frameId;
            if(input.token.source==FastSamFrameToken.Source.LIVE_CAMERA)lastCpuTimestampNs=input.token.cpuImageTimestampNs;
            admitted++;lock.notifyAll();return replace?Admission.REPLACED_PENDING:Admission.ACCEPTED;
        }
    }

    private void modelLoop() {
        FastSamEngine engine=null;
        Throwable releaseFailure=null;
        try {
            engine=factory.create();
            synchronized(lock) {
                if(stopping)return;
                if(clock.getAsLong()>=options.loadDeadlineElapsedNs)throw new TimeoutException("FastSAM cold load exceeded caller deadline");
                ready.complete(engine.runtime());
            }
            while(true) {
                FrozenInput<T> input;
                synchronized(lock) {
                    while(!stopping && (pending==null || inflight))lock.wait();
                    if(stopping)return;
                    input=pending;pending=null;inflight=true;
                }
                boolean transferred=false;
                try {
                    if(!isFresh(input.token,input.request)) {
                        synchronized(lock){stale++;}
                        listener.onDiscard(input.token,"stale_before_inference");continue;
                    }
                    long invokeStarted=clock.getAsLong();
                    FastSamEngine.Output output;
                    try { output=engine.invoke(input.ownedArgb,input.token.width,input.token.height,input.token,input.request); }
                    catch(FastSamExecutionRequest.Expired expired) {
                        synchronized(lock){stale++;}
                        listener.onDiscard(input.token,"execution_deadline_before_dispatch");continue;
                    }
                    synchronized(lock) {
                        // Admission reserved this flight. Stop cannot close native state ahead of its post drain.
                        if(cpuPostTerminated)throw new IllegalStateException("FastSAM CPU post worker terminated");
                        if(pendingPost!=null)throw new IllegalStateException("FastSAM post queue overflow");
                        pendingPost=new PostJob<>(input,output,engine.runtime(),invokeStarted,clock.getAsLong());
                        transferred=true;lock.notifyAll();
                    }
                } finally {if(!transferred){synchronized(lock){inflight=false;lock.notifyAll();}signalConsumerIdle();}}
            }
        } catch(Throwable error){if(error instanceof TfliteFastSamEngine.NativeReleaseFailure)releaseFailure=error;fail(error);}
        finally {
            synchronized(lock){stopping=true;dropPending();lock.notifyAll();}
            boolean interrupted=false;
            synchronized(lock) {
                while(inflight || copying)try{lock.wait();}catch(InterruptedException error){interrupted=true;recordFailure(error);}
            }
            while(cpuPostOwner.isAlive())try{cpuPostOwner.join();}catch(InterruptedException error){interrupted=true;recordFailure(error);}
            signalConsumerIdle();
            FastSamTransferredModel transferToken=null;
            TerminalMode mode;Throwable beforeTransfer;synchronized(lock){mode=terminalMode;beforeTransfer=failure;}
            if(mode==TerminalMode.TRANSFER_DRAIN) {
                try {
                    if(beforeTransfer!=null || engine==null)throw new IllegalStateException("FastSAM consumer drain failed",beforeTransfer);
                    transferToken=engine.detachForTransfer();
                } catch(Throwable error){fail(error);cancelPendingLoad();}
            } else if(engine!=null)try{engine.close();}catch(Throwable error){releaseFailure=error;fail(error);}
            followPhysicalRelease(releaseFailure);
            ready.completeExceptionally(new IllegalStateException("FastSAM closed before ready"));
            Throwable terminal;synchronized(lock){terminal=failure;}
            if(terminal==null)closed.complete(null);else closed.completeExceptionally(terminal);
            // A transfer callback can synchronously call late close. Publish it only after this
            // consumer's functional close is complete; physical native release remains separate.
            if(mode==TerminalMode.TRANSFER_DRAIN) {
                if(transferToken!=null && terminal==null)transferred.complete(transferToken);
                else transferred.completeExceptionally(terminal!=null?terminal:new IllegalStateException("FastSAM transfer did not drain"));
            }
            if(interrupted)Thread.currentThread().interrupt();
        }
    }

    private void postLoop() {
        try {
            FastSamDecoder decoder=new FastSamDecoder();
            while(true) {
                PostJob<T> job;
                synchronized(lock) {
                    while(pendingPost==null) {
                        if(stopping && !inflight)return;
                        lock.wait();
                    }
                    job=pendingPost;pendingPost=null;
                }
                try {
                    boolean retired;
                    synchronized(lock){retired=stopping;if(retired)discardedAtClose++;}
                    if(retired) {
                        // Native invoke already drained; do not scan/reconstruct masks for a closed owner.
                        listener.onDiscard(job.input.token,"closed_before_decode");
                        continue;
                    }
                    long postStarted=clock.getAsLong();
                    DecodeResult decoded=decodeNativeOutput(decoder,job);
                    long finished=clock.getAsLong();
                    FastSamResult<T> result=new FastSamResult<>(job.input.token,job.input.attachment,decoded,job.runtime,
                        finished,Thread.currentThread().getId(),job.input.copyMs,
                        (job.invokeStarted-job.input.token.capturedElapsedNs)/1e6,
                        job.output.preprocessingMs,job.output.inferenceMs,job.output.outputReadMs,
                        (postStarted-job.handedOffNs)/1e6,
                        options.captureRawOutputs && job.input.request.captureRawOutputs
                            ?new FastSamResult.RawOutputs(job.output.ownedTensors):null);
                    boolean deliver;synchronized(lock){completed++;deliver=!stopping;}
                    if(deliver && isFresh(job.input.token,job.input.request)) {
                        listener.onResult(result); // Frozen depth/named-result processing is inside this flight.
                        synchronized(lock){cpuPostCompleted++;}
                    } else {
                        synchronized(lock){if(deliver)stale++;else discardedAtClose++;}
                        listener.onDiscard(job.input.token,deliver?"stale_after_decode":"closed_before_callback");
                    }
                } catch(Throwable error) {
                    // Publish failure/stop before releasing the lease: the model owner must not
                    // reserve a successor for a CPU worker that is about to terminate.
                    fail(error);return;
                } finally {synchronized(lock){inflight=false;lock.notifyAll();}signalConsumerIdle();}
            }
        } catch(Throwable error){fail(error);}
        finally {
            synchronized(lock) {
                cpuPostTerminated=true;
                if(pendingPost!=null){pendingPost=null;inflight=false;}
                lock.notifyAll();
            }
            signalConsumerIdle();
        }
    }

    private void watchLoad() {
        try {
            long remaining=options.loadDeadlineElapsedNs-clock.getAsLong();
            if(remaining<=0)throw new TimeoutException("FastSAM load deadline already expired");
            ready.get(remaining,TimeUnit.NANOSECONDS);
        } catch(TimeoutException error){fail(error);}
        catch(ExecutionException ignored){/* The producing thread already reports its actual failure. */}
        catch(InterruptedException error){Thread.currentThread().interrupt();fail(error);}
    }

    private DecodeResult decodeNativeOutput(FastSamDecoder decoder,PostJob<T> job){
        try{
            if(job.output==null)throw new FastSamModelContract.NativeOutputException(
                FastSamModelContract.NativeOutputFailureReason.INVALID_NATIVE_OUTPUT,"Native FastSAM output is missing");
            FastSamModelContract.validateRawOutputShape(job.output.ownedTensors);
            return decoder.decode(job.output.ownedTensors[0],job.output.ownedTensors[1],job.input.token.width,job.input.token.height);
        }catch(FastSamModelContract.NativeOutputException failure){
            try{factory.reportNativeOutputFault(failure);}catch(Throwable report){if(report!=failure)failure.addSuppressed(report);}
            throw failure;
        }
    }

    /** Stops admission immediately and requests control cancellation of a pending remote load.
     * The returned functional-close result is separate from the physical releasedFuture receipt. */
    public CompletableFuture<Void> closeAsync() {
        boolean cancelLoad,unstarted;
        synchronized(lock) {
            if(terminalMode==TerminalMode.TRANSFER_DRAIN || terminalMode==TerminalMode.FINAL_CLOSE)return closed;
            terminalMode=TerminalMode.FINAL_CLOSE;
            cancelLoad=started && !ready.isDone();
            stopping=true;dropPending();lock.notifyAll();
            ready.completeExceptionally(new IllegalStateException("FastSAM closing"));
            unstarted=!started;
            if(unstarted && !transferredConsumer){physicalRelease.local(null);closed.complete(null);}
            if(unstarted && transferredConsumer)cancelLoad=true;
        }
        signalConsumerIdle();
        if(cancelLoad)cancelPendingLoad();
        if(unstarted && transferredConsumer){
            followPhysicalRelease(null);
            Throwable terminal;synchronized(lock){terminal=failure;}
            if(terminal==null)closed.complete(null);else closed.completeExceptionally(terminal);
        }
        return closed;
    }
    @Override public void close(){closeAsync();}
    /** Independent of inference/load success: only completes after all owned native/copy/post work
     * is released. Failure here means the next tuning candidate must not assume its slot is free. */
    public CompletableFuture<Void> releasedFuture(){return released;}
    /** An idle boundary for already-admitted copy/invoke/decode/listener work. This does not close
     * admission, release a native model, prove successful inference, or acknowledge cohort death. */
    public CompletableFuture<Void> consumerIdleFuture(){synchronized(lock){return consumerIdle;}}
    /** Reads actual prepared metadata only; never calls start, OPEN or native inference. */
    public FastSamRuntimeInfo runtimeIfReady(){synchronized(lock){return isReady()?ready.getNow(null):null;}}
    public long getSessionEpoch(){return sessionEpoch;}
    /** Keep the warmed model. Inflight results retain their old token/attachment; the consumer's
     * walk/AR/geometry fence must reject them after pause or session replacement. */
    public boolean discardPending(String reason) {
        Objects.requireNonNull(reason,"reason");FrozenInput<T> discarded;
        synchronized(lock) {
            discarded=pending;if(discarded==null)return false;
            pending=null;discardedPending++;lock.notifyAll();
        }
        try{listener.onDiscard(discarded.token,reason);}catch(Throwable error){fail(error);}
        return true;
    }
    public Stats stats(){synchronized(lock){return new Stats(this);}}
    private void dropPending(){if(pending!=null){discardedAtClose++;pending=null;}}
    private boolean recordFailure(Throwable error) {
        synchronized(lock){if(failure==null){failure=error;return true;}if(failure!=error)failure.addSuppressed(error);return false;}
    }
    private void fail(Throwable error) {
        boolean first=recordFailure(error);
        boolean cancelLoad;
        synchronized(lock){cancelLoad=started && !ready.isDone();if(terminalMode==null)terminalMode=TerminalMode.FINAL_CLOSE;stopping=true;dropPending();lock.notifyAll();}
        ready.completeExceptionally(error);
        signalConsumerIdle();
        if(cancelLoad)cancelPendingLoad();
        if(first)try{listener.onError(error);}catch(Throwable callbackError){recordFailure(callbackError);}
    }
    private void signalConsumerIdle(){
        CompletableFuture<Void> completed;
        synchronized(lock){
            if(copying || inflight || pending!=null || pendingPost!=null || consumerIdle.isDone())return;
            completed=consumerIdle;
            // A new copy must get a new boundary even if a completion callback reenters submit.
            consumerIdle=CompletableFuture.completedFuture(null);
        }
        completed.complete(null);
    }
    private void cancelPendingLoad(){try{factory.cancelPendingLoad();}catch(Throwable error){recordFailure(error);}}
    private void followPhysicalRelease(Throwable localFailure){
        CompletableFuture<Void> actual;
        try{actual=factory.actualReleasedFuture();}catch(Throwable error){recordFailure(error);physicalRelease.local(error);return;}
        if(actual==null)physicalRelease.local(localFailure);else physicalRelease.follow(actual);
    }
    private static <R> CompletableFuture<R> failedFuture(Throwable error){CompletableFuture<R> result=new CompletableFuture<>();result.completeExceptionally(error);return result;}
    /** Global physical receipts must never capture a service/Activity/listener after transfer. */
    private static final class PhysicalReleaseState {
        final CompletableFuture<Void> released=new CompletableFuture<>();
        volatile boolean confirmed;
        void local(Throwable error){confirmed=error==null;if(error==null)released.complete(null);else released.completeExceptionally(error);}
        void follow(CompletableFuture<Void> actual){actual.whenComplete((ignored,error)->local(error));}
    }
    private static final class FrozenInput<T> {
        final FastSamFrameToken token;final FastSamExecutionRequest request;final T attachment;final int[] ownedArgb;final double copyMs;
        FrozenInput(FastSamFrameToken token,FastSamExecutionRequest request,T attachment,int[] argb,double copyMs){
            this.token=token;this.request=request;this.attachment=attachment;ownedArgb=argb;this.copyMs=copyMs;
        }
    }
    private static final class PostJob<T> {
        final FrozenInput<T> input;final FastSamEngine.Output output;final FastSamRuntimeInfo runtime;
        final long invokeStarted,handedOffNs;
        PostJob(FrozenInput<T> input,FastSamEngine.Output output,FastSamRuntimeInfo runtime,long started,long handoff) {
            this.input=input;this.output=output;this.runtime=runtime;invokeStarted=started;handedOffNs=handoff;
        }
    }
}
