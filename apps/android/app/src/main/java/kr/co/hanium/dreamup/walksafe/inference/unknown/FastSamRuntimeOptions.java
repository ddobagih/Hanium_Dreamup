package kr.co.hanium.dreamup.walksafe.inference.unknown;

import java.util.Objects;

/** A selected configuration, not a claim that the configuration passed device calibration. */
public final class FastSamRuntimeOptions {
    public enum Backend { CPU, GPU }
    public final Backend requestedBackend;
    public final int numThreads;
    public final boolean allowCpuFallback, captureRawOutputs;
    public final boolean gpuPrecisionLossAllowed, gpuSerializationCacheEnabled;
    public final long loadDeadlineElapsedNs, maximumResultAgeMs;

    public FastSamRuntimeOptions(Backend requestedBackend,int numThreads,boolean allowCpuFallback,
            long loadDeadlineElapsedNs,long maximumResultAgeMs,boolean captureRawOutputs) {
        this(requestedBackend,numThreads,allowCpuFallback,loadDeadlineElapsedNs,maximumResultAgeMs,
            captureRawOutputs,false,false);
    }

    /** Precision relaxation/cache use are explicit experiments until device evidence selects them.
     * Cache configuration does not prove a native cache hit or numerical output equivalence. */
    public FastSamRuntimeOptions(Backend requestedBackend,int numThreads,boolean allowCpuFallback,
            long loadDeadlineElapsedNs,long maximumResultAgeMs,boolean captureRawOutputs,
            boolean gpuPrecisionLossAllowed,boolean gpuSerializationCacheEnabled) {
        this.requestedBackend=Objects.requireNonNull(requestedBackend,"backend");
        if(numThreads<1 || numThreads>Math.max(1,Runtime.getRuntime().availableProcessors()))
            throw new IllegalArgumentException("threads must be within 1..availableProcessors");
        if(loadDeadlineElapsedNs<=0 || maximumResultAgeMs<1 || maximumResultAgeMs>5000)
            throw new IllegalArgumentException("Invalid load deadline or result age");
        this.numThreads=numThreads;this.allowCpuFallback=allowCpuFallback;
        this.loadDeadlineElapsedNs=loadDeadlineElapsedNs;this.maximumResultAgeMs=maximumResultAgeMs;
        this.captureRawOutputs=captureRawOutputs;
        this.gpuPrecisionLossAllowed=gpuPrecisionLossAllowed;
        this.gpuSerializationCacheEnabled=gpuSerializationCacheEnabled;
    }
}
