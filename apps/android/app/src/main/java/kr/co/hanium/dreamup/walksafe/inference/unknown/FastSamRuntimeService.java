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
            nativeReleaseConfirmed=s.nativeReleaseConfirmed;
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
    private final CompletableFuture<Void> released=new CompletableFuture<>();
    private final Thread modelOwner,cpuPostOwner,loadWatchdog;
    private final FastSamYuvCopy.YuvScratch copyScratch=new FastSamYuvCopy.YuvScratch();
    private boolean started,stopping,inflight,copying,cpuPostTerminated,nativeReleaseConfirmed;
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
        Context app=Objects.requireNonNull(context,"context").getApplicationContext();
        return ()->new TfliteFastSamEngine(app,options);
    }
    FastSamRuntimeService(long sessionEpoch,FastSamRuntimeOptions options,Listener<T> listener,
            FastSamEngine.Factory factory,LongSupplier clock) {
        if(sessionEpoch<=0)throw new IllegalArgumentException("session epoch must be positive");
        this.sessionEpoch=sessionEpoch;this.options=Objects.requireNonNull(options);
        this.listener=Objects.requireNonNull(listener);this.factory=Objects.requireNonNull(factory);this.clock=clock;
        modelOwner=new Thread(this::modelLoop,"walksafe-fastsam-model");
        cpuPostOwner=new Thread(this::postLoop,"walksafe-fastsam-post");
        loadWatchdog=new Thread(this::watchLoad,"walksafe-fastsam-load-deadline");
        modelOwner.setDaemon(true);cpuPostOwner.setDaemon(true);loadWatchdog.setDaemon(true);
    }

    /** Absolute elapsedRealtime deadline belongs to the caller's total (at most300s) check budget. */
    public CompletableFuture<FastSamRuntimeInfo> start() {
        synchronized(lock) {
            if(started || stopping)return ready;
            long remaining=options.loadDeadlineElapsedNs-clock.getAsLong();
            if(remaining<=0) {
                TimeoutException timeout=new TimeoutException("FastSAM load budget expired before start");
                failure=timeout;stopping=true;nativeReleaseConfirmed=true;released.complete(null);
                ready.completeExceptionally(timeout);closed.completeExceptionally(timeout);return ready;
            }
            if(remaining>TimeUnit.SECONDS.toNanos(300))
                throw new IllegalArgumentException("Load deadline must be within the remaining300s total budget");
            started=true;cpuPostOwner.start();modelOwner.start();loadWatchdog.start();return ready;
        }
    }

    /** Synchronous, stride-aware copy while the caller still owns an open Image. */
    public Admission submit(Image image,FastSamFrameToken token,T frozenAttachment) {
        Objects.requireNonNull(image,"image");Objects.requireNonNull(token,"token");
        if(token.source!=FastSamFrameToken.Source.LIVE_CAMERA || image.getFormat()!=ImageFormat.YUV_420_888
                || image.getWidth()!=token.width || image.getHeight()!=token.height
                || image.getTimestamp()!=token.cpuImageTimestampNs)
            throw new IllegalArgumentException("Image does not match frozen FastSAM token");
        Rect crop=image.getCropRect();
        if(crop==null || crop.left!=0 || crop.top!=0 || crop.right!=token.width || crop.bottom!=token.height)
            throw new IllegalArgumentException("FastSAM requires the same full CPU-image geometry as its depth token");
        Admission gate=beginCopy(token);if(gate!=null)return gate;
        long copyStarted=clock.getAsLong();
        try {
            Image.Plane[] p=image.getPlanes();if(p.length!=3)throw new IllegalArgumentException("YUV420 needs three planes");
            int[] argb=FastSamYuvCopy.yuv420ToArgb(token.width,token.height,
                p[0].getBuffer(),p[0].getRowStride(),p[0].getPixelStride(),
                p[1].getBuffer(),p[1].getRowStride(),p[1].getPixelStride(),
                p[2].getBuffer(),p[2].getRowStride(),p[2].getPixelStride(),copyScratch);
            return publish(new FrozenInput<>(token,frozenAttachment,argb,(clock.getAsLong()-copyStarted)/1e6));
        } finally { synchronized(lock){copying=false;lock.notifyAll();} }
    }

    /** Fixture/owned RGB entry. Clone completes before return; later caller mutation is harmless. */
    public Admission submitArgb(int width,int height,int[] argb,FastSamFrameToken token,T frozenAttachment) {
        Objects.requireNonNull(argb,"argb");Objects.requireNonNull(token,"token");
        if(width!=token.width || height!=token.height || argb.length!=Math.multiplyExact(width,height))
            throw new IllegalArgumentException("ARGB does not match frozen FastSAM token");
        Admission gate=beginCopy(token);if(gate!=null)return gate;
        long copyStarted=clock.getAsLong();
        try{return publish(new FrozenInput<>(token,frozenAttachment,argb.clone(),(clock.getAsLong()-copyStarted)/1e6));}
        finally{synchronized(lock){copying=false;lock.notifyAll();}}
    }
    private Admission beginCopy(FastSamFrameToken token) {
        synchronized(lock) {
            Admission gate=gate(token);
            if(gate!=null){rejected++;return gate;}
            if(copying){rejected++;return Admission.COPY_BUSY;}
            copying=true;return null;
        }
    }
    private Admission gate(FastSamFrameToken token) {
        if(stopping)return Admission.CLOSED;
        if(!ready.isDone() || ready.isCompletedExceptionally())return Admission.NOT_READY;
        if(token.sessionEpoch!=sessionEpoch)return Admission.WRONG_EPOCH;
        if(!token.isFreshAt(clock.getAsLong(),options.maximumResultAgeMs))return Admission.STALE;
        if(token.frameId<=lastFrameId || (token.source==FastSamFrameToken.Source.LIVE_CAMERA
                && token.cpuImageTimestampNs<=lastCpuTimestampNs))return Admission.OUT_OF_ORDER;
        return null;
    }
    private Admission publish(FrozenInput<T> input) {
        synchronized(lock) {
            Admission gate=gate(input.token);if(gate!=null){rejected++;return gate;}
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
                    if(!input.token.isFreshAt(clock.getAsLong(),options.maximumResultAgeMs)) {
                        synchronized(lock){stale++;}
                        listener.onDiscard(input.token,"stale_before_inference");continue;
                    }
                    long invokeStarted=clock.getAsLong();
                    FastSamEngine.Output output=engine.invoke(input.ownedArgb,input.token.width,input.token.height);
                    synchronized(lock) {
                        // Admission reserved this flight. Stop cannot close native state ahead of its post drain.
                        if(cpuPostTerminated)throw new IllegalStateException("FastSAM CPU post worker terminated");
                        if(pendingPost!=null)throw new IllegalStateException("FastSAM post queue overflow");
                        pendingPost=new PostJob<>(input,output,engine.runtime(),invokeStarted,clock.getAsLong());
                        transferred=true;lock.notifyAll();
                    }
                } finally {if(!transferred)synchronized(lock){inflight=false;lock.notifyAll();}}
            }
        } catch(Throwable error){if(error instanceof TfliteFastSamEngine.NativeReleaseFailure)releaseFailure=error;fail(error);}
        finally {
            synchronized(lock){stopping=true;dropPending();lock.notifyAll();}
            boolean interrupted=false;
            synchronized(lock) {
                while(inflight || copying)try{lock.wait();}catch(InterruptedException error){interrupted=true;recordFailure(error);}
            }
            while(cpuPostOwner.isAlive())try{cpuPostOwner.join();}catch(InterruptedException error){interrupted=true;recordFailure(error);}
            if(engine!=null)try{engine.close();}catch(Throwable error){releaseFailure=error;fail(error);}
            synchronized(lock){nativeReleaseConfirmed=releaseFailure==null;}
            if(releaseFailure==null)released.complete(null);else released.completeExceptionally(releaseFailure);
            ready.completeExceptionally(new IllegalStateException("FastSAM closed before ready"));
            Throwable terminal;synchronized(lock){terminal=failure;}
            if(terminal==null)closed.complete(null);else closed.completeExceptionally(terminal);
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
                    long postStarted=clock.getAsLong();
                    DecodeResult decoded=decoder.decode(job.output.ownedTensors[0],job.output.ownedTensors[1],
                        job.input.token.width,job.input.token.height);
                    long finished=clock.getAsLong();
                    FastSamResult<T> result=new FastSamResult<>(job.input.token,job.input.attachment,decoded,job.runtime,
                        finished,Thread.currentThread().getId(),job.input.copyMs,
                        (job.invokeStarted-job.input.token.capturedElapsedNs)/1e6,
                        job.output.preprocessingMs,job.output.inferenceMs,job.output.outputReadMs,
                        (postStarted-job.handedOffNs)/1e6,
                        options.captureRawOutputs && job.input.token.source==FastSamFrameToken.Source.CALIBRATION_FIXTURE
                            ?new FastSamResult.RawOutputs(job.output.ownedTensors):null);
                    boolean deliver;synchronized(lock){completed++;deliver=!stopping;}
                    if(deliver && job.input.token.isFreshAt(clock.getAsLong(),options.maximumResultAgeMs)) {
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
                } finally {synchronized(lock){inflight=false;lock.notifyAll();}}
            }
        } catch(Throwable error){fail(error);}
        finally {
            synchronized(lock) {
                cpuPostTerminated=true;
                if(pendingPost!=null){pendingPost=null;inflight=false;}
                lock.notifyAll();
            }
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

    /** Stops admission/callbacks immediately; future completes after copying, CPU drain and owner close.
     * Native cold load cannot be forcibly interrupted; UI callers must never block awaiting this. */
    public CompletableFuture<Void> closeAsync() {
        synchronized(lock) {
            stopping=true;dropPending();lock.notifyAll();
            ready.completeExceptionally(new IllegalStateException("FastSAM closing"));
            if(!started){nativeReleaseConfirmed=true;released.complete(null);closed.complete(null);}
            return closed;
        }
    }
    @Override public void close(){closeAsync();}
    /** Independent of inference/load success: only completes after all owned native/copy/post work
     * is released. Failure here means the next tuning candidate must not assume its slot is free. */
    public CompletableFuture<Void> releasedFuture(){return released;}
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
        synchronized(lock){stopping=true;dropPending();lock.notifyAll();}
        ready.completeExceptionally(error);
        if(first)try{listener.onError(error);}catch(Throwable callbackError){recordFailure(callbackError);}
    }
    private static final class FrozenInput<T> {
        final FastSamFrameToken token;final T attachment;final int[] ownedArgb;final double copyMs;
        FrozenInput(FastSamFrameToken token,T attachment,int[] argb,double copyMs){this.token=token;this.attachment=attachment;ownedArgb=argb;this.copyMs=copyMs;}
    }
    private static final class PostJob<T> {
        final FrozenInput<T> input;final FastSamEngine.Output output;final FastSamRuntimeInfo runtime;
        final long invokeStarted,handedOffNs;
        PostJob(FrozenInput<T> input,FastSamEngine.Output output,FastSamRuntimeInfo runtime,long started,long handoff) {
            this.input=input;this.output=output;this.runtime=runtime;invokeStarted=started;handedOffNs=handoff;
        }
    }
}
