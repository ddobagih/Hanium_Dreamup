package android.media;

import android.graphics.Rect;
import android.os.Build;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

/** Native-free Image/Plane constructors require android.media package access. */
public final class DeclaredYuvTestImage extends Image {
    private final int width;
    private final int height;
    private final Rect crop;
    private final Plane[] planes;
    public int dataSpace;
    public int dataSpaceReads;
    public boolean rejectDataSpaceRead;
    public final AssertionError dataSpaceReadError = new AssertionError("unexpected metadata access");
    public boolean metadataOnlyFailure;
    private boolean closed;

    public DeclaredYuvTestImage(int width, int height, int dataSpace, int[] crop,
            ByteBuffer y, ByteBuffer u, ByteBuffer v,
            int yRowStride, int yPixelStride, int uvRowStride, int uvPixelStride) {
        this.width = width;
        this.height = height;
        this.dataSpace = dataSpace;
        this.crop = new Rect();
        this.crop.left = crop[0];
        this.crop.top = crop[1];
        this.crop.right = crop[2];
        this.crop.bottom = crop[3];
        this.planes = new Plane[] {
            plane(y, yRowStride, yPixelStride),
            plane(u, uvRowStride, uvPixelStride),
            plane(v, uvRowStride, uvPixelStride)
        };
    }

    private Plane plane(ByteBuffer bytes, int rowStride, int pixelStride) {
        return new Plane() {
            @Override public int getPixelStride() { requireOpen(); return pixelStride; }
            @Override public int getRowStride() { requireOpen(); return rowStride; }
            @Override public ByteBuffer getBuffer() { requireOpen(); return bytes; }
        };
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("fixture image closed");
    }

    @Override public int getFormat() { requireOpen(); return 35; }
    @Override public int getWidth() { requireOpen(); return width; }
    @Override public int getHeight() { requireOpen(); return height; }
    @Override public long getTimestamp() { requireOpen(); return 123456789L; }
    @Override public Rect getCropRect() { requireOpen(); return crop; }
    @Override public Plane[] getPlanes() { requireOpen(); return planes; }
    @Override public int getDataSpace() {
        requireOpen();
        dataSpaceReads++;
        if (metadataOnlyFailure) throw new IllegalStateException("metadata unavailable");
        if (rejectDataSpaceRead) throw dataSpaceReadError;
        return dataSpace;
    }
    @Override public void close() { closed = true; }

    /** Static-final SDK reads can be folded by the JIT; each original test gets fresh SDK/YUV classes. */
    public static final class IsolatedSdkRunner extends BlockJUnit4ClassRunner {
        public IsolatedSdkRunner(Class<?> type) throws InitializationError { super(type); }

        @Override protected Statement methodBlock(FrameworkMethod method) {
            return new Statement() {
                @Override public void evaluate() throws Throwable {
                    ClassLoader loader = new ClassLoader(getTestClass().getJavaClass().getClassLoader()) {
                        @Override protected synchronized Class<?> loadClass(String name, boolean resolve)
                                throws ClassNotFoundException {
                            // Image and Plane share package-private constructors with this fixture.
                            boolean isolated = name.startsWith("kr.co.hanium.dreamup.walksafe.inference.")
                                    || name.startsWith("kr.co.hanium.dreamup.walksafe.UprightCameraImage")
                                    || name.startsWith("android.media.DeclaredYuvTestImage")
                                    || name.equals("android.media.Image") || name.startsWith("android.media.Image$")
                                    || name.equals("android.os.Build$VERSION");
                            if (!isolated) return super.loadClass(name, resolve);
                            Class<?> type = findLoadedClass(name);
                            if (type == null) {
                                try (InputStream bytes = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                                    if (bytes == null) throw new ClassNotFoundException(name);
                                    byte[] data = bytes.readAllBytes();
                                    type = defineClass(name, data, 0, data.length);
                                } catch (IOException error) {
                                    throw new ClassNotFoundException(name, error);
                                }
                            }
                            if (resolve) resolveClass(type);
                            return type;
                        }
                    };
                    Class<?> isolated = Class.forName(getTestClass().getJavaClass().getName(), true, loader);
                    new BlockJUnit4ClassRunner(isolated) {
                        Statement selected() throws NoSuchMethodException {
                            return super.methodBlock(new FrameworkMethod(isolated.getMethod(method.getName())));
                        }
                    }.selected().evaluate();
                }
            };
        }
    }

    /** Runs the real public SDK guard under a synthetic JVM SDK, always restoring the host value. */
    public static void atSdk(int sdk, Runnable action) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Field field = Build.VERSION.class.getField("SDK_INT");
        int previous = field.getInt(null);
        Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, field);
        long offset = (Long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, field);
        java.lang.reflect.Method putInt = unsafeClass.getMethod("putIntVolatile", Object.class, long.class, int.class);
        putInt.invoke(unsafe, base, offset, sdk);
        try {
            if (field.getInt(null) != sdk) throw new AssertionError("SDK fixture not applied");
            action.run();
        } finally {
            putInt.invoke(unsafe, base, offset, previous);
        }
    }
}
