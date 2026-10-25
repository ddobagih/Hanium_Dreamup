package android.media;

import android.graphics.Rect;
import java.nio.ByteBuffer;

/** SDK package access only; owned 2x2 YUV fixture, with observable timestamp/close calls. */
public final class FramePipelineTestImage extends Image {
    public final long originalTimestampNs;
    public int closes, timestampReads;
    public boolean rejectTimestamp;
    public FramePipelineTestImage(long timestampNs) { originalTimestampNs = timestampNs; }
    public int getFormat() { return 35; }
    public int getWidth() { return 2; }
    public int getHeight() { return 2; }
    public long getTimestamp() {
        timestampReads++;
        if (rejectTimestamp) throw new IllegalStateException("No CPU clock is available");
        return originalTimestampNs;
    }
    public Rect getCropRect() {
        Rect rect = new Rect(); rect.left = 0; rect.top = 0; rect.right = 2; rect.bottom = 2; return rect;
    }
    public Plane[] getPlanes() {
        return new Plane[] { plane(2, new byte[] {77,77,77,77}),
            plane(1, new byte[] {(byte)128}), plane(1, new byte[] {(byte)128}) };
    }
    private Plane plane(int stride, byte[] pixels) {
        return new Plane() {
            public int getPixelStride() { return 1; }
            public int getRowStride() { return stride; }
            public ByteBuffer getBuffer() { return ByteBuffer.wrap(pixels); }
        };
    }
    public void close() { closes++; }
}
