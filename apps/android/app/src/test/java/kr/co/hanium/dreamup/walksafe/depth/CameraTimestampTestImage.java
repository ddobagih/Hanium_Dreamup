package android.media;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Native-free image double. Package access is required by the SDK's Image/Plane constructors. */
public final class CameraTimestampTestImage extends Image {
    private final long timeNs;
    private final int pixelStride;
    public int closeCalls;
    public boolean failTimestamp;

    public CameraTimestampTestImage(long timeNs, int pixelStride) {
        this.timeNs = timeNs;
        this.pixelStride = pixelStride;
    }

    @Override public int getFormat() { return pixelStride == 2 ? 1144402265 : 35; }
    @Override public int getWidth() { return 2; }
    @Override public int getHeight() { return 2; }
    @Override public long getTimestamp() {
        if (failTimestamp) throw new IllegalStateException("timestamp unavailable");
        return timeNs;
    }
    @Override public void close() { closeCalls++; }
    @Override public Plane[] getPlanes() {
        return new Plane[] { new Plane() {
            @Override public int getPixelStride() { return pixelStride; }
            @Override public int getRowStride() { return 2 * pixelStride; }
            @Override public ByteBuffer getBuffer() {
                ByteBuffer buffer = ByteBuffer.allocate(4 * pixelStride).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < 4; i++) {
                    if (pixelStride == 2) buffer.putShort((short) 1000);
                    else buffer.put((byte) 255);
                }
                buffer.rewind();
                return buffer;
            }
        } };
    }
}
