package kr.co.hanium.dreamup.walksafe.inference.unknown;

import java.nio.FloatBuffer;
import java.util.List;
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.DecodeResult;
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask;

/** The attachment must be a frozen depth/pose/named-detection snapshot supplied by the caller. */
public final class FastSamResult<T> {
    public final FastSamFrameToken token;
    public final T attachment;
    public final List<InstanceMask> masks;
    public final FastSamRuntimeInfo runtime;
    public final long completedElapsedNs,cpuPostOwnerThreadId;
    public final double copyMs,queueMs,preprocessingMs,inferenceMs,outputReadMs,cpuPostWaitMs,
        filterNmsMs,maskDecodeMs,decodeMs,captureToDecodeCompleteMs;
    public final RawOutputs rawOutputs;

    FastSamResult(FastSamFrameToken token,T attachment,DecodeResult decoded,FastSamRuntimeInfo runtime,
            long completedElapsedNs,long cpuPostOwnerThreadId,double copyMs,double queueMs,
            double preprocessingMs,double inferenceMs,double outputReadMs,double cpuPostWaitMs,
            RawOutputs rawOutputs) {
        this.token=token;this.attachment=attachment;this.masks=decoded.instances();this.runtime=runtime;
        this.completedElapsedNs=completedElapsedNs;this.cpuPostOwnerThreadId=cpuPostOwnerThreadId;
        this.copyMs=copyMs;this.queueMs=queueMs;this.preprocessingMs=preprocessingMs;
        this.inferenceMs=inferenceMs;this.outputReadMs=outputReadMs;this.cpuPostWaitMs=cpuPostWaitMs;
        filterNmsMs=decoded.filterNmsNanos()/1e6;maskDecodeMs=decoded.maskDecodeNanos()/1e6;
        decodeMs=decoded.totalNanos()/1e6;captureToDecodeCompleteMs=(completedElapsedNs-token.capturedElapsedNs)/1e6;
        this.rawOutputs=rawOutputs;
    }

    /** Calibration only. Read-only views cannot mutate the owned output arrays. */
    public static final class RawOutputs {
        private final float[] detections,prototypes;
        RawOutputs(float[][] owned){detections=owned[0];prototypes=owned[1];}
        public FloatBuffer detections(){return FloatBuffer.wrap(detections).asReadOnlyBuffer();}
        public FloatBuffer prototypes(){return FloatBuffer.wrap(prototypes).asReadOnlyBuffer();}
    }
}
