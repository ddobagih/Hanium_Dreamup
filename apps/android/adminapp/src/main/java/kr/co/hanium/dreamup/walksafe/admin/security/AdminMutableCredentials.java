package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.Arrays;

/**
 * Owns mutable login/recovery credentials until one dispatch (S18-20). The dispatch hands the
 * caller its own char[] copies (never a String) and zeroes this object's buffers at once; the
 * caller zeroes the copies after the request, and a queued-then-discarded attempt is zeroed by
 * {@link #close()}. Zeroing these buffers does not guarantee that no other copy remains in
 * memory (platform text fields, the HTTP stack), so it is not described as complete erasure.
 */
public final class AdminMutableCredentials implements AutoCloseable {
    private final char[] primary;
    private final char[] secondary;
    private boolean closed;

    public AdminMutableCredentials(char[] primary, char[] secondary) {
        if (primary == null || secondary == null) {
            throw new IllegalArgumentException("credentials are required");
        }
        this.primary = primary;
        this.secondary = secondary;
    }

    /** Returns {primary, secondary} as caller-owned copies; the caller must zero both. */
    public synchronized char[][] takeForDispatch() {
        if (closed) throw new IllegalStateException("credentials were discarded");
        try {
            return new char[][] {primary.clone(), secondary.clone()};
        } finally {
            close();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        Arrays.fill(primary, '\0');
        Arrays.fill(secondary, '\0');
        closed = true;
    }
}
