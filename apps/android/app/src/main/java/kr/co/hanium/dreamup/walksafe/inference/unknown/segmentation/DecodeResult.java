package kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation;

import java.util.Collections;
import java.util.List;

/** Decoder-only timings; model inference and depth sampling are deliberately outside this result. */
public final class DecodeResult {
    private final List<InstanceMask> instances;
    private final int candidatesAboveConfidence, selectedBeforeEmptyMaskFilter;
    private final long filterNmsNanos, prototypeProjectionNanos, upsampleCropNanos, totalNanos;
    private final long prototypePixelsComputed, sampledOutputPixels;

    DecodeResult(List<InstanceMask> ownedInstances, int candidates, int selected, long filterNmsNanos,
                 long prototypeProjectionNanos, long upsampleCropNanos, long totalNanos,
                 long prototypePixelsComputed, long sampledOutputPixels) {
        this.instances = Collections.unmodifiableList(ownedInstances);
        this.candidatesAboveConfidence = candidates; this.selectedBeforeEmptyMaskFilter = selected;
        this.filterNmsNanos = filterNmsNanos; this.prototypeProjectionNanos = prototypeProjectionNanos;
        this.upsampleCropNanos = upsampleCropNanos; this.totalNanos = totalNanos;
        this.prototypePixelsComputed = prototypePixelsComputed; this.sampledOutputPixels = sampledOutputPixels;
    }
    public List<InstanceMask> instances() { return instances; }
    public int candidatesAboveConfidence() { return candidatesAboveConfidence; }
    public int selectedBeforeEmptyMaskFilter() { return selectedBeforeEmptyMaskFilter; }
    public int emptyMasksDropped() { return selectedBeforeEmptyMaskFilter - instances.size(); }
    public long filterNmsNanos() { return filterNmsNanos; }
    public long prototypeProjectionNanos() { return prototypeProjectionNanos; }
    public long upsampleCropNanos() { return upsampleCropNanos; }
    public long maskDecodeNanos() { return prototypeProjectionNanos + upsampleCropNanos; }
    public long totalNanos() { return totalNanos; }
    public long prototypePixelsComputed() { return prototypePixelsComputed; }
    public long sampledOutputPixels() { return sampledOutputPixels; }
}
