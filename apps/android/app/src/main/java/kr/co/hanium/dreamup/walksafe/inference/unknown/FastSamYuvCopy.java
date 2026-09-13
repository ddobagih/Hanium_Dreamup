package kr.co.hanium.dreamup.walksafe.inference.unknown;

import java.nio.ByteBuffer;

/** Stride-aware copies; independent of Android so non-contiguous fixtures can exercise real code. */
final class FastSamYuvCopy {
    private FastSamYuvCopy() {}

    /** Owned only by one capture source; these temporary bytes are never published in a snapshot. */
    static final class YuvScratch {
        byte[] y, u, v;
        long lastBulkCopyNs, lastArgbConvertNs;
    }

    static int[] yuv420ToArgb(int width, int height, ByteBuffer y, int yr, int yp,
            ByteBuffer u, int ur, int up, ByteBuffer v, int vr, int vp) {
        return yuv420ToArgb(width, height, y, yr, yp, u, ur, up, v, vr, vp, new YuvScratch());
    }

    static int[] yuv420ToArgb(int width, int height, ByteBuffer y, int yr, int yp,
            ByteBuffer u, int ur, int up, ByteBuffer v, int vr, int vp, YuvScratch scratch) {
        long copyStartedNs = System.nanoTime();
        scratch.y = bulkCopy(y, width, height, yr, yp, scratch.y);
        scratch.u = bulkCopy(u, (width + 1) / 2, (height + 1) / 2, ur, up, scratch.u);
        scratch.v = bulkCopy(v, (width + 1) / 2, (height + 1) / 2, vr, vp, scratch.v);
        scratch.lastBulkCopyNs = System.nanoTime() - copyStartedNs;
        long convertStartedNs = System.nanoTime();
        int[] out = new int[Math.multiplyExact(width, height)];
        byte[] yBytes = scratch.y, uBytes = scratch.u, vBytes = scratch.v;
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) {
            int yy = yBytes[row * yr + col * yp] & 255;
            int uu = (uBytes[(row / 2) * ur + (col / 2) * up] & 255) - 128;
            int vv = (vBytes[(row / 2) * vr + (col / 2) * vp] & 255) - 128;
            // Preserve primary YuvImagePreprocessor full-range float arithmetic and rounding.
            int r = clamp(Math.round(yy + 1.402f * vv));
            int g = clamp(Math.round(yy - 0.344136f * uu - 0.714136f * vv));
            int b = clamp(Math.round(yy + 1.772f * uu));
            out[row * width + col] = 0xff000000 | r << 16 | g << 8 | b;
        }
        scratch.lastArgbConvertNs = System.nanoTime() - convertStartedNs;
        return out;
    }

    private static byte[] bulkCopy(ByteBuffer plane, int width, int height, int rowStride,
            int pixelStride, byte[] reuse) {
        check(plane, width, height, rowStride, pixelStride, 1);
        // The final row need not include padding. Copy exactly through its final pixel, from the
        // caller's current position. A duplicate preserves the original position, limit and mark.
        int bytes = Math.toIntExact((long) (height - 1) * rowStride + (long) (width - 1) * pixelStride + 1);
        byte[] out = reuse != null && reuse.length >= bytes ? reuse : new byte[bytes];
        plane.duplicate().get(out, 0, bytes);
        return out;
    }

    private static int clamp(int n) { return Math.max(0, Math.min(255, n)); }

    private static void check(ByteBuffer data, int width, int height, int rowStride, int pixelStride, int bytes) {
        if (data == null || width <= 0 || height <= 0 || rowStride <= 0 || pixelStride < bytes
                || rowStride < (long) (width - 1) * pixelStride + bytes)
            throw new IllegalArgumentException("Invalid plane layout");
        long lastExclusive = data.position() + (long) (height - 1) * rowStride
                + (long) (width - 1) * pixelStride + bytes;
        if (lastExclusive > data.limit()) throw new IllegalArgumentException("Truncated image plane");
    }
}
