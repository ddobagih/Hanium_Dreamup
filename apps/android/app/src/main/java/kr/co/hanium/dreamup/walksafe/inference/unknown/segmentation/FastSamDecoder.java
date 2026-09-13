package kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/** Fixed FastSAM-s 768 FP32 decoder. Stateless, thread-safe and free of Android dependencies. */
public final class FastSamDecoder {
    public static final int INPUT_SIZE = 768, ANCHORS = 12096, CHANNELS = 37;
    public static final int PROTO_SIZE = 192, MASK_CHANNELS = 32, MAX_DETECTIONS = 100;
    public static final float CONFIDENCE = .25f, NMS_IOU = .7f;

    private static final class Candidate {
        final int anchor;
        final float score, left, top, right, bottom;
        Candidate(int anchor, float score, float left, float top, float right, float bottom) {
            this.anchor = anchor; this.score = score; this.left = left; this.top = top;
            this.right = right; this.bottom = bottom;
        }
    }

    /**
     * detections: [1,37,12096] channel-major, normalized xywh, one score, 32 coefficients.
     * prototypes: [1,192,192,32] NHWC. Both arrays are borrowed only during this call.
     */
    public DecodeResult decode(float[] detections, float[] prototypes, int originalWidth, int originalHeight) {
        long begin = System.nanoTime();
        if (detections == null || detections.length != CHANNELS * ANCHORS)
            throw new IllegalArgumentException("Expected 37*12096 detection floats");
        if (prototypes == null || prototypes.length != PROTO_SIZE * PROTO_SIZE * MASK_CHANNELS)
            throw new IllegalArgumentException("Expected 192*192*32 NHWC prototype floats");
        if (originalWidth <= 0 || originalHeight <= 0)
            throw new IllegalArgumentException("Original dimensions must be positive");
        Math.multiplyExact(originalWidth, originalHeight);
        for (float value : detections) if (!(value <= Float.MAX_VALUE && value >= -Float.MAX_VALUE))
            throw new IllegalArgumentException("Non-finite detection output");
        for (float value : prototypes) if (!(value <= Float.MAX_VALUE && value >= -Float.MAX_VALUE))
            throw new IllegalArgumentException("Non-finite prototype output");
        List<Candidate> candidates = new ArrayList<>();
        for (int anchor = 0; anchor < ANCHORS; anchor++) {
            float score = detections[4 * ANCHORS + anchor];
            if (!(score > CONFIDENCE)) continue;
            float cx = detections[anchor] * INPUT_SIZE;
            float cy = detections[ANCHORS + anchor] * INPUT_SIZE;
            float width = detections[2 * ANCHORS + anchor] * INPUT_SIZE;
            float height = detections[3 * ANCHORS + anchor] * INPUT_SIZE;
            if (!(width > 0 && height > 0)) continue;
            candidates.add(new Candidate(anchor, score, cx - width * .5f, cy - height * .5f,
                                         cx + width * .5f, cy + height * .5f));
        }
        candidates.sort(Comparator.<Candidate>comparingDouble(c -> -c.score).thenComparingInt(c -> c.anchor));
        List<Candidate> selected = new ArrayList<>(MAX_DETECTIONS);
        for (Candidate candidate : candidates) {
            boolean suppressed = false;
            for (Candidate accepted : selected) {
                if (iou(candidate, accepted) > NMS_IOU) { suppressed = true; break; }
            }
            if (!suppressed) {
                selected.add(candidate);
                if (selected.size() == MAX_DETECTIONS) break;
            }
        }
        long filterDone = System.nanoTime();
        double gain = Math.min((double) INPUT_SIZE / originalWidth, (double) INPUT_SIZE / originalHeight);
        int padX = roundEven((INPUT_SIZE - roundEven(originalWidth * gain)) / 2.0 - .1);
        int padY = roundEven((INPUT_SIZE - roundEven(originalHeight * gain)) / 2.0 - .1);
        double protoGain = Math.min((double) PROTO_SIZE / originalWidth, (double) PROTO_SIZE / originalHeight);
        double protoPadX = (PROTO_SIZE - roundEven(originalWidth * protoGain)) / 2.0;
        double protoPadY = (PROTO_SIZE - roundEven(originalHeight * protoGain)) / 2.0;
        int protoLeft = roundEven(protoPadX - .1), protoTop = roundEven(protoPadY - .1);
        int cropWidth = PROTO_SIZE - roundEven(protoPadX + .1) - protoLeft;
        int cropHeight = PROTO_SIZE - roundEven(protoPadY + .1) - protoTop;
        if (cropWidth <= 0 || cropHeight <= 0) throw new IllegalArgumentException("Empty prototype letterbox crop");
        float[] logits = new float[PROTO_SIZE * PROTO_SIZE];
        float[] coefficients = new float[MASK_CHANNELS];
        AxisMap xMap = new AxisMap(originalWidth, cropWidth, protoLeft);
        AxisMap yMap = new AxisMap(originalHeight, cropHeight, protoTop);
        List<InstanceMask> masks = new ArrayList<>(selected.size());
        long projectionNanos = 0, interpolationNanos = 0, protoPixels = 0, outputPixels = 0;
        for (Candidate candidate : selected) {
            float left = clip((candidate.left - padX) / (float) gain, originalWidth);
            float top = clip((candidate.top - padY) / (float) gain, originalHeight);
            float right = clip((candidate.right - padX) / (float) gain, originalWidth);
            float bottom = clip((candidate.bottom - padY) / (float) gain, originalHeight);
            // Fixed GPU-style crop: integer pixel x>=left && x<right, y>=top && y<bottom.
            int roiLeft = (int) Math.ceil(left), roiTop = (int) Math.ceil(top);
            int roiRight = (int) Math.ceil(right), roiBottom = (int) Math.ceil(bottom);
            if (roiRight <= roiLeft || roiBottom <= roiTop) continue;
            long projectionStart = System.nanoTime();
            for (int ch = 0; ch < MASK_CHANNELS; ch++) coefficients[ch] = detections[(ch + 5) * ANCHORS + candidate.anchor];
            int gridLeft = xMap.low[roiLeft], gridRight = xMap.high[roiRight - 1];
            int gridTop = yMap.low[roiTop], gridBottom = yMap.high[roiBottom - 1];
            for (int y = gridTop; y <= gridBottom; y++) {
                for (int x = gridLeft; x <= gridRight; x++) {
                    int pixel = y * PROTO_SIZE + x, base = pixel * MASK_CHANNELS;
                    float value = 0f;
                    // Deliberately ordered IEEE float32 accumulation, no FMA or parallel reduction.
                    for (int ch = 0; ch < MASK_CHANNELS; ch++) value += coefficients[ch] * prototypes[base + ch];
                    logits[pixel] = value;
                }
            }
            protoPixels += (long) (gridRight - gridLeft + 1) * (gridBottom - gridTop + 1);
            projectionNanos += System.nanoTime() - projectionStart;
            long interpolateStart = System.nanoTime();
            int stride = roiRight - roiLeft;
            BitSet bits = new BitSet(stride * (roiBottom - roiTop));
            int area = 0;
            for (int y = roiTop; y < roiBottom; y++) {
                int row0 = yMap.low[y] * PROTO_SIZE, row1 = yMap.high[y] * PROTO_SIZE;
                float wy = yMap.weight[y], oneMinusY = 1f - wy;
                int bit = (y - roiTop) * stride;
                for (int x = roiLeft; x < roiRight; x++, bit++) {
                    int x0 = xMap.low[x], x1 = xMap.high[x];
                    float wx = xMap.weight[x], oneMinusX = 1f - wx;
                    float upper = oneMinusX * logits[row0 + x0] + wx * logits[row0 + x1];
                    float lower = oneMinusX * logits[row1 + x0] + wx * logits[row1 + x1];
                    float value = oneMinusY * upper + wy * lower;
                    if (value > 0f) { bits.set(bit); area++; }
                }
            }
            outputPixels += (long) stride * (roiBottom - roiTop);
            if (area > 0) {
                // FastSAMPredictor adjusts returned boxes after mask assembly; masks stay unchanged.
                float boxLeft = left < 20f ? 0f : left, boxTop = top < 20f ? 0f : top;
                float boxRight = right > originalWidth - 20f ? originalWidth : right;
                float boxBottom = bottom > originalHeight - 20f ? originalHeight : bottom;
                float covered = (boxRight - boxLeft) * (boxBottom - boxTop);
                if (covered / ((float) originalWidth * originalHeight) > .9f) {
                    boxLeft = 0; boxTop = 0; boxRight = originalWidth; boxBottom = originalHeight;
                }
                masks.add(new InstanceMask(originalWidth, originalHeight, roiLeft, roiTop, roiRight, roiBottom,
                    area, candidate.anchor, boxLeft, boxTop, boxRight, boxBottom, candidate.score, bits));
            }
            interpolationNanos += System.nanoTime() - interpolateStart;
        }
        return new DecodeResult(masks, candidates.size(), selected.size(), filterDone - begin,
            projectionNanos, interpolationNanos, System.nanoTime() - begin, protoPixels, outputPixels);
    }

    private static int roundEven(double value) { return (int) Math.rint(value); }
    private static float clip(float value, int limit) { return Math.max(0f, Math.min(value, limit)); }
    private static float iou(Candidate a, Candidate b) {
        float width = Math.max(0f, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        float height = Math.max(0f, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        float intersection = width * height;
        float union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - intersection;
        return union > 0f ? intersection / union : 0f;
    }
    private static final class AxisMap {
        final int[] low, high;
        final float[] weight;
        AxisMap(int outputLength, int croppedLength, int offset) {
            low = new int[outputLength]; high = new int[outputLength]; weight = new float[outputLength];
            float scale = (float) croppedLength / outputLength;
            for (int i = 0; i < outputLength; i++) {
                float source = Math.max(0f, (i + .5f) * scale - .5f);
                int lo = Math.min((int) Math.floor(source), croppedLength - 1);
                low[i] = offset + lo; high[i] = offset + Math.min(lo + 1, croppedLength - 1);
                weight[i] = source - lo;
            }
        }
    }
}
