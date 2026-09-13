package kr.co.hanium.dreamup.walksafe.inference;

import java.nio.ByteBuffer;

/** RGB uint8 letterbox followed by float /255. Bilinear pixel centers are explicit. */
public final class RgbLetterboxPreprocessor {
    public static void write(int[] argb, int width, int height, int size, ByteBuffer tensor) {
        write(argb, width, height, size, tensor, new float[size * size * 3]);
    }
    public static void write(int[] argb, int width, int height, int size, ByteBuffer tensor, float[] values) {
        checkShape(argb, width, height, size, tensor, values);
        double gain = Math.min((double) size / width, (double) size / height);
        int resizedWidth = Math.max(1, (int) Math.rint(width * gain));
        int resizedHeight = Math.max(1, (int) Math.rint(height * gain));
        int left = (int) Math.rint((size - resizedWidth) / 2.0 - 0.1);
        int top = (int) Math.rint((size - resizedHeight) / 2.0 - 0.1);
        boolean noResize = resizedWidth == width && resizedHeight == height;
        int[] x0ByColumn = new int[resizedWidth], x1ByColumn = new int[resizedWidth];
        int[] y0ByRow = new int[resizedHeight], y1ByRow = new int[resizedHeight];
        int[] ax0ByColumn = new int[resizedWidth], ax1ByColumn = new int[resizedWidth];
        int[] ay0ByRow = new int[resizedHeight], ay1ByRow = new int[resizedHeight];
        if (!noResize) {
            // OpenCV 4.13 resize.cpp: uint8 INTER_LINEAR uses float32 coordinates,
            // 11-bit coefficients and staged integer vertical truncation.
            // Reference: https://github.com/opencv/opencv/blob/4.13.0/modules/imgproc/src/resize.cpp
            for (int x = 0; x < resizedWidth; x++) {
                float sx = (float) ((x + 0.5) * ((double) width / resizedWidth) - 0.5);
                int ix = (int) Math.floor(sx);
                float ax = sx - ix;
                if (ix < 0 || ix >= width - 1) ax = 0;
                ix = clamp(ix, width);
                x0ByColumn[x] = ix; x1ByColumn[x] = clamp(ix + 1, width);
                ax0ByColumn[x] = (int) Math.rint((1f - ax) * 2048f);
                ax1ByColumn[x] = (int) Math.rint(ax * 2048f);
            }
            for (int y = 0; y < resizedHeight; y++) {
                float sy = (float) ((y + 0.5) * ((double) height / resizedHeight) - 0.5);
                int iy = (int) Math.floor(sy);
                float ay = sy - iy;
                y0ByRow[y] = clamp(iy, height); y1ByRow[y] = clamp(iy + 1, height);
                ay0ByRow[y] = (int) Math.rint((1f - ay) * 2048f);
                ay1ByRow[y] = (int) Math.rint(ay * 2048f);
            }
        }
        int outputIndex = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (x < left || x >= left + resizedWidth || y < top || y >= top + resizedHeight) {
                    values[outputIndex++] = 114f / 255f;
                    values[outputIndex++] = 114f / 255f;
                    values[outputIndex++] = 114f / 255f;
                    continue;
                }
                if (noResize) {
                    int pixel = argb[(y - top) * width + x - left];
                    values[outputIndex++] = ((pixel >> 16) & 255) / 255f;
                    values[outputIndex++] = ((pixel >> 8) & 255) / 255f;
                    values[outputIndex++] = (pixel & 255) / 255f;
                    continue;
                }
                int ax0 = ax0ByColumn[x - left], ax1 = ax1ByColumn[x - left];
                int ay0 = ay0ByRow[y - top], ay1 = ay1ByRow[y - top];
                int x0 = x0ByColumn[x - left], x1 = x1ByColumn[x - left];
                int y0 = y0ByRow[y - top], y1 = y1ByRow[y - top];
                int p00 = argb[y0 * width + x0], p10 = argb[y0 * width + x1];
                int p01 = argb[y1 * width + x0], p11 = argb[y1 * width + x1];
                for (int shift = 16; shift >= 0; shift -= 8) {
                    int upper = ((p00 >> shift) & 255) * ax0 + ((p10 >> shift) & 255) * ax1;
                    int lower = ((p01 >> shift) & 255) * ax0 + ((p11 >> shift) & 255) * ax1;
                    int value = (((ay0 * (upper >> 4)) >> 16) + ((ay1 * (lower >> 4)) >> 16) + 2) >> 2;
                    values[outputIndex++] = value / 255f;
                }
            }
        }
        tensor.clear(); tensor.asFloatBuffer().put(values); tensor.rewind();
    }

    private static void checkShape(int[] argb, int width, int height, int size, ByteBuffer tensor, float[] values) {
        if (width <= 0 || height <= 0 || size <= 0 || argb.length != width * height)
            throw new IllegalArgumentException("ARGB shape mismatch");
        if (values.length != size * size * 3 || tensor.capacity() != values.length * 4)
            throw new IllegalArgumentException("Tensor scratch shape mismatch");
    }

    private static int clamp(int value, int size) { return Math.max(0, Math.min(size - 1, value)); }
}
