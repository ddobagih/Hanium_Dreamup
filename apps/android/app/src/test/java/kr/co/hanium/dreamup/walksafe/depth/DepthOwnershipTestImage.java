package android.media;

/** SDK-compatible image double; no camera, native buffer, or Android runtime is created. */
public final class DepthOwnershipTestImage extends Image {
    public final String name;
    public final java.util.List<String> calls;
    public long timeNs = 100L;
    public int closeCalls;
    public int timestampReads;
    public int failTimestampAtRead;
    public RuntimeException timestampFailure;
    public RuntimeException closeFailure;
    public Runnable onClose = () -> {};

    public DepthOwnershipTestImage(String name, java.util.List<String> calls) {
        this.name = name;
        this.calls = calls;
    }

    @Override public int getFormat() { return 1144402265; }
    @Override public int getWidth() { return 2; }
    @Override public int getHeight() { return 2; }
    @Override public long getTimestamp() {
        timestampReads++;
        if (timestampFailure != null && timestampReads == failTimestampAtRead) throw timestampFailure;
        return timeNs;
    }
    @Override public Plane[] getPlanes() { throw new AssertionError("No pixel conversion in ownership tests"); }
    @Override public void close() {
        closeCalls++;
        calls.add("close:" + name);
        onClose.run();
        if (closeFailure != null) throw closeFailure;
    }
}
