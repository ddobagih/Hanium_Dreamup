package kr.co.hanium.dreamup.walksafe.inference.unknown;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionCohortLease;
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionModelHandle;
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRole;

/** One-use authority metadata only. It never retains a service, listener, frame, Context or future. */
public final class FastSamTransferredModel {
    private final VisionCohortLease oldLease;
    private final VisionModelHandle oldHandle;
    private final FastSamRuntimeOptions options;
    private final AtomicBoolean consumed=new AtomicBoolean();

    FastSamTransferredModel(VisionCohortLease lease,VisionModelHandle handle,FastSamRuntimeOptions options) {
        oldLease=Objects.requireNonNull(lease);oldHandle=Objects.requireNonNull(handle);
        this.options=Objects.requireNonNull(options);
        if(handle.getRole()!=VisionRole.FASTSAM || !FastSamModelContract.MODEL_ID.equals(handle.getModelKey())
                || !lease.getCohortId().equals(handle.getCohortId()) || lease.getOwnerEpoch()!=handle.getOwnerEpoch())
            throw new IllegalArgumentException("FastSAM transfer identity differs");
    }
    public String getCohortId(){return oldHandle.getCohortId();}
    public String getModelInstanceId(){return oldHandle.getModelInstanceId();}
    public VisionCohortLease getOldLease(){return oldLease;}
    public VisionModelHandle getOldHandle(){return oldHandle;}
    public FastSamRuntimeOptions getOptions(){return options;}
    public boolean isConsumed(){return consumed.get();}

    void claimForAttach(FastSamRuntimeOptions next,VisionCohortLease lease,VisionModelHandle handle) {
        Objects.requireNonNull(next);Objects.requireNonNull(lease);Objects.requireNonNull(handle);
        if(!getCohortId().equals(lease.getCohortId()) || !getCohortId().equals(handle.getCohortId())
                || !getModelInstanceId().equals(handle.getModelInstanceId()) || handle.getRole()!=VisionRole.FASTSAM
                || !oldHandle.getModelKey().equals(handle.getModelKey()) || handle.getOwnerEpoch()!=lease.getOwnerEpoch()
                || !(lease.getOwnerEpoch()==oldLease.getOwnerEpoch()
                    || oldLease.getOwnerEpoch()<Long.MAX_VALUE && lease.getOwnerEpoch()==oldLease.getOwnerEpoch()+1)
                || next.requestedBackend!=options.requestedBackend || next.numThreads!=options.numThreads
                || next.allowCpuFallback!=options.allowCpuFallback || next.gpuPrecisionLossAllowed!=options.gpuPrecisionLossAllowed
                || next.gpuSerializationCacheEnabled!=options.gpuSerializationCacheEnabled)
            throw new IllegalArgumentException("FastSAM transfer model/options differ");
        if(!consumed.compareAndSet(false,true))throw new IllegalStateException("FastSAM transfer token already consumed");
    }
}
