package kr.co.hanium.dreamup.walksafe.inference.unknown;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPurpose;

/** Per-job execution authority. Capture freshness and output retention are separate decisions. */
public final class FastSamExecutionRequest {
    /** No native dispatch occurred. This is a caller deadline, never a native/model failure. */
    public static final class Expired extends CancellationException {
        public Expired(){super("FastSAM job deadline expired before native dispatch");}
    }
    public final VisionPurpose purpose;
    public final long absoluteDeadlineElapsedNs;
    public final boolean captureRawOutputs;

    public FastSamExecutionRequest(VisionPurpose purpose,long absoluteDeadlineElapsedNs,boolean captureRawOutputs) {
        this.purpose=Objects.requireNonNull(purpose,"purpose");
        if(absoluteDeadlineElapsedNs<=0)throw new IllegalArgumentException("An absolute execution deadline is required");
        this.absoluteDeadlineElapsedNs=absoluteDeadlineElapsedNs;
        this.captureRawOutputs=captureRawOutputs;
    }
}
