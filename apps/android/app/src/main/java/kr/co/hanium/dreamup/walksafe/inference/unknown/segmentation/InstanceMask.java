package kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation;

import java.util.Arrays;
import java.util.BitSet;

/** Immutable original-image mask. Bits preserve holes and disconnected components. */
public final class InstanceMask {
    @FunctionalInterface public interface PixelConsumer { void accept(int x, int y); }
    private final int imageWidth, imageHeight, left, top, right, bottom, area, anchorIndex;
    private final float bboxLeft, bboxTop, bboxRight, bboxBottom, score;
    private final BitSet bits;

    InstanceMask(int imageWidth, int imageHeight, int left, int top, int right, int bottom,
                 int area, int anchorIndex, float bboxLeft, float bboxTop, float bboxRight,
                 float bboxBottom, float score, BitSet ownedBits) {
        this.imageWidth = imageWidth; this.imageHeight = imageHeight;
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        this.area = area; this.anchorIndex = anchorIndex;
        this.bboxLeft = bboxLeft; this.bboxTop = bboxTop; this.bboxRight = bboxRight;
        this.bboxBottom = bboxBottom; this.score = score; this.bits = ownedBits;
    }
    public int imageWidth() { return imageWidth; }
    public int imageHeight() { return imageHeight; }
    /** Integer crop ROI enclosing all true bits; it need not be the tight foreground bounds. */
    public int maskLeft() { return left; }
    public int maskTop() { return top; }
    public int maskRightExclusive() { return right; }
    public int maskBottomExclusive() { return bottom; }
    public int area() { return area; }
    public int anchorIndex() { return anchorIndex; }
    public float bboxLeft() { return bboxLeft; }
    public float bboxTop() { return bboxTop; }
    public float bboxRight() { return bboxRight; }
    public float bboxBottom() { return bboxBottom; }
    public float score() { return score; }
    public boolean contains(int x, int y) {
        return x >= left && x < right && y >= top && y < bottom
            && bits.get((y - top) * (right - left) + x - left);
    }
    public void forEachPixel(PixelConsumer consumer) {
        if (consumer == null) throw new NullPointerException("consumer");
        int stride = right - left;
        for (int bit = bits.nextSetBit(0); bit >= 0; bit = bits.nextSetBit(bit + 1))
            consumer.accept(left + bit % stride, top + bit / stride);
    }
    /** ROI row-major, low bit first in every byte; copy cannot mutate this mask. */
    public byte[] copyPackedBits() {
        int length = ((right - left) * (bottom - top) + 7) / 8;
        return Arrays.copyOf(bits.toByteArray(), length);
    }
}
