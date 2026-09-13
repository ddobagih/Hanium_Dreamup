package kr.co.hanium.dreamup.walksafe.inference.unknown;

/** GPU attachment is observed; whole-graph GPU delegation is not inferred from it. */
public final class FastSamRuntimeInfo {
    public final String modelId=FastSamModelContract.MODEL_ID,modelAsset=FastSamModelContract.ASSET,
        modelSha256=FastSamModelContract.SHA256;
    public final FastSamRuntimeOptions.Backend requestedBackend,actualBackend;
    public final int numThreads;
    public final boolean fallbackUsed,gpuDelegateAttached,gpuPrecisionLossAllowed,gpuPrecisionLossRequested;
    public final boolean gpuSerializationCacheRequested,gpuSerializationCacheConfigured;
    public final String gpuSerializationCacheStatus,gpuSerializationCacheToken,gpuSerializationCacheFailureReason;
    public final int gpuInitializationAttempts;
    public final String fallbackReason;
    public final long modelOwnerThreadId;
    public final double loadMs;
    FastSamRuntimeInfo(FastSamRuntimeOptions options,FastSamRuntimeOptions.Backend actual,
            String fallbackReason,long owner,double loadMs) {
        this(options,actual,fallbackReason,owner,loadMs,
            actual==FastSamRuntimeOptions.Backend.GPU?"disabled":"not_applicable",null,null,0);
    }
    FastSamRuntimeInfo(FastSamRuntimeOptions options,FastSamRuntimeOptions.Backend actual,
            String fallbackReason,long owner,double loadMs,String cacheStatus,String cacheToken,
            String cacheFailureReason,int gpuAttempts) {
        requestedBackend=options.requestedBackend;actualBackend=actual;numThreads=options.numThreads;
        this.fallbackReason=fallbackReason;fallbackUsed=actual!=requestedBackend;
        gpuDelegateAttached=actual==FastSamRuntimeOptions.Backend.GPU;
        gpuPrecisionLossRequested=options.gpuPrecisionLossAllowed;
        gpuPrecisionLossAllowed=gpuDelegateAttached && options.gpuPrecisionLossAllowed;
        gpuSerializationCacheRequested=options.gpuSerializationCacheEnabled;
        gpuSerializationCacheStatus=cacheStatus;gpuSerializationCacheToken=cacheToken;
        gpuSerializationCacheFailureReason=cacheFailureReason;
        gpuSerializationCacheConfigured=gpuDelegateAttached && "configured".equals(cacheStatus);
        gpuInitializationAttempts=gpuAttempts;
        modelOwnerThreadId=owner;this.loadMs=loadMs;
    }
}
