package kr.co.hanium.dreamup.walksafe.inference.unknown;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Controlled remote observations for the real Java invoke/decode/consumer service. No native SDK. */
public final class JointPairTestAuxiliaryFactory {
    public final AtomicInteger creates=new AtomicInteger(),invokes=new AtomicInteger(),closes=new AtomicInteger();
    public final CompletableFuture<Void> physical=new CompletableFuture<>();
    public String cohortId="c".repeat(64),modelId="auxiliary-final";
    public long childPid=321,nativeOwnerThread=42;
    public RuntimeException loadFailure,invokeFailure,closeFailure;
    public int failInvokeAt=2;
    public boolean holdRelease;
    public final java.util.List<FastSamExecutionRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

    public <T> FastSamRuntimeService<T> create(long epoch,FastSamRuntimeOptions options,
            FastSamRuntimeService.Listener<T> listener,LongSupplier clock){
        creates.incrementAndGet();
        FastSamEngine.Factory factory=new FastSamEngine.Factory(){
            public CompletableFuture<Void> actualReleasedFuture(){return physical;}
            public FastSamEngine create(){
                if(loadFailure!=null){physical.complete(null);throw loadFailure;}
                long owner=Thread.currentThread().getId();
                FastSamRuntimeInfo info=new FastSamRuntimeInfo(options,options.requestedBackend,null,nativeOwnerThread,0,
                    "not_applicable",null,null,0,childPid,modelId,cohortId);
                return new FastSamEngine(){
                    public FastSamRuntimeInfo runtime(){return info;}
                    public Output invoke(int[] pixels,int width,int height){
                        if(Thread.currentThread().getId()!=owner)throw new AssertionError("foreign parent owner");
                        int call=invokes.incrementAndGet();
                        if(invokeFailure!=null && call==failInvokeAt)throw invokeFailure;
                        return new Output(new float[][]{new float[FastSamModelContract.DETECTION_FLOATS],
                            new float[FastSamModelContract.PROTOTYPE_FLOATS]},0,0,0);
                    }
                    public Output invoke(int[] pixels,int width,int height,FastSamFrameToken token,FastSamExecutionRequest request){
                        requests.add(request);
                        return invoke(pixels,width,height);
                    }
                    public void close(){
                        if(Thread.currentThread().getId()!=owner)throw new AssertionError("foreign close owner");
                        closes.incrementAndGet();
                        if(closeFailure!=null)throw closeFailure;
                        if(!holdRelease)physical.complete(null);
                    }
                };
            }
        };
        return new FastSamRuntimeService<>(epoch,options,listener,factory,clock);
    }
}
