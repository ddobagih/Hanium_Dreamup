package kr.co.hanium.dreamup.walksafe.inference.unknown;

import java.util.Objects;

/** Identity of the original camera/geometry/depth capture; never refreshed at completion. */
public final class FastSamFrameToken {
    public enum Source { LIVE_CAMERA, CALIBRATION_FIXTURE }
    public final Source source;
    public final long sessionEpoch, frameId, cameraTimestampNs, cpuImageTimestampNs, capturedElapsedNs;
    public final String geometryId;
    public final int width, height;

    public FastSamFrameToken(Source source,long sessionEpoch,long frameId,long cameraTimestampNs,
            long cpuImageTimestampNs,long capturedElapsedNs,String geometryId,int width,int height) {
        if(sessionEpoch<=0 || frameId<0 || capturedElapsedNs<=0 || width<=0 || height<=0
                || width>4096 || height>4096) throw new IllegalArgumentException("Invalid FastSAM frame identity");
        this.source=Objects.requireNonNull(source,"source");
        if(source==Source.LIVE_CAMERA && (cameraTimestampNs<=0 || cpuImageTimestampNs<=0))
            throw new IllegalArgumentException("Live timestamps must be actual observations");
        if(source==Source.CALIBRATION_FIXTURE && (cameraTimestampNs!=0 || cpuImageTimestampNs!=0))
            throw new IllegalArgumentException("Static fixture has no camera clock");
        this.geometryId=Objects.requireNonNull(geometryId,"geometryId");
        if(geometryId.isEmpty())throw new IllegalArgumentException("geometryId required");
        this.sessionEpoch=sessionEpoch;this.frameId=frameId;this.cameraTimestampNs=cameraTimestampNs;
        this.cpuImageTimestampNs=cpuImageTimestampNs;this.capturedElapsedNs=capturedElapsedNs;
        this.width=width;this.height=height;
    }

    public boolean isFreshAt(long nowElapsedNs,long maximumAgeMs) {
        return maximumAgeMs>0 && nowElapsedNs>=capturedElapsedNs
            && nowElapsedNs-capturedElapsedNs<=maximumAgeMs*1_000_000L;
    }
}
