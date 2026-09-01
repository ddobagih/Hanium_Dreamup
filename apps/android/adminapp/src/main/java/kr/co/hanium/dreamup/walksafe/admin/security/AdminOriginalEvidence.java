package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;

/** One report-original view held only in mutable process memory. */
public final class AdminOriginalEvidence implements AutoCloseable {
    static final String SCHEMA_VERSION = "walksafe.report-original-access-grant.v2";
    static final String PURPOSE = "report_review";
    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    static final long MAX_GRANT_TTL_MS = 300_000L;

    static final class IssuedGrant implements AutoCloseable {
        private final String reportId;
        private final String sessionId;
        private final String deviceId;
        private final String grantId;
        private final int contentRevision;
        private final long expiresAtEpochMs;
        private final double latitude;
        private final double longitude;
        private final Double accuracyMeters;
        private final String resourcePath;
        private final String contentType;
        private final String sha256;
        private final int byteCount;
        private char[] accessToken;

        IssuedGrant(
            String reportId,
            String sessionId,
            String deviceId,
            String grantId,
            int contentRevision,
            String expiresAt,
            double latitude,
            double longitude,
            Double accuracyMeters,
            String resourcePath,
            String contentType,
            String sha256,
            int byteCount,
            String accessToken,
            long nowEpochMs
        ) throws IOException {
            this.reportId = canonicalUuid(reportId, "report_id");
            this.sessionId = canonicalUuid(sessionId, "session_id");
            this.deviceId = requireDeviceId(deviceId);
            this.grantId = canonicalUuid(grantId, "grant_id");
            if (contentRevision < 0) throw new IOException("original evidence content revision is invalid");
            this.contentRevision = contentRevision;
            this.expiresAtEpochMs = expiry(expiresAt, nowEpochMs);
            if (!Double.isFinite(latitude) || latitude < -90d || latitude > 90d
                || !Double.isFinite(longitude) || longitude < -180d || longitude > 180d
                || (accuracyMeters != null
                    && (!Double.isFinite(accuracyMeters) || accuracyMeters < 0d))) {
                throw new IOException("original evidence exact location is invalid");
            }
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracyMeters = accuracyMeters;
            this.contentType = requireContentType(contentType);
            this.resourcePath = requireResourcePath(resourcePath, this.reportId, this.contentType);
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IOException("original evidence SHA-256 is invalid");
            }
            this.sha256 = sha256;
            if (byteCount < 1 || byteCount > MAX_IMAGE_BYTES) {
                throw new IOException("original evidence byte count is invalid");
            }
            this.byteCount = byteCount;
            if (!canonicalAccessToken(accessToken)) {
                throw new IOException("original evidence access token is invalid");
            }
            this.accessToken = accessToken.toCharArray();
        }

        String reportId() { return reportId; }
        String sessionId() { return sessionId; }
        String deviceId() { return deviceId; }
        String grantId() { return grantId; }
        int contentRevision() { return contentRevision; }
        long expiresAtEpochMs() { return expiresAtEpochMs; }
        double latitude() { return latitude; }
        double longitude() { return longitude; }
        Double accuracyMeters() { return accuracyMeters; }
        String resourcePath() { return resourcePath; }
        String contentType() { return contentType; }
        String sha256() { return sha256; }
        int byteCount() { return byteCount; }

        synchronized char[] consumeAccessToken(long nowEpochMs) throws IOException {
            if (accessToken == null) throw new IOException("original evidence access token was already consumed");
            if (expiresAtEpochMs <= nowEpochMs) {
                close();
                throw new IOException("original evidence grant expired before image access");
            }
            char[] consumed = accessToken;
            accessToken = null;
            return consumed;
        }

        @Override
        public synchronized void close() {
            if (accessToken != null) Arrays.fill(accessToken, '\0');
            accessToken = null;
        }
    }

    private final String reportId;
    private final String sessionId;
    private final String deviceId;
    private final String grantId;
    private final int contentRevision;
    private final long expiresAtEpochMs;
    private final double latitude;
    private final double longitude;
    private final Double accuracyMeters;
    private final String contentType;
    private final String sha256;
    private final int byteCount;
    private byte[] imageBytes;
    private boolean verified = true;

    private AdminOriginalEvidence(IssuedGrant grant, byte[] imageBytes) {
        reportId = grant.reportId();
        sessionId = grant.sessionId();
        deviceId = grant.deviceId();
        grantId = grant.grantId();
        contentRevision = grant.contentRevision();
        expiresAtEpochMs = grant.expiresAtEpochMs();
        latitude = grant.latitude();
        longitude = grant.longitude();
        accuracyMeters = grant.accuracyMeters();
        contentType = grant.contentType();
        sha256 = grant.sha256();
        byteCount = grant.byteCount();
        this.imageBytes = imageBytes;
    }

    static AdminOriginalEvidence verifyAndTake(
        IssuedGrant grant,
        int statusCode,
        String responseContentType,
        long declaredLength,
        byte[] ownedImageBytes,
        long nowEpochMs
    ) throws IOException {
        if (grant == null || ownedImageBytes == null) {
            zero(ownedImageBytes);
            throw new IOException("original evidence response is unavailable");
        }
        try {
            if (statusCode != 200) throw new IOException("original evidence image returned unexpected status");
            if (grant.expiresAtEpochMs() <= nowEpochMs) {
                throw new IOException("original evidence grant expired before verification");
            }
            if (!grant.contentType().equals(normalizedContentType(responseContentType))) {
                throw new IOException("original evidence MIME type does not match the grant");
            }
            if (declaredLength != grant.byteCount()
                || ownedImageBytes.length != grant.byteCount()) {
                throw new IOException("original evidence byte count does not match the grant");
            }
            if (!AdminCanonicalEncoding.sha256Hex(ownedImageBytes).equals(grant.sha256())) {
                throw new IOException("original evidence SHA-256 does not match the grant");
            }
            requireImageSignature(grant.contentType(), ownedImageBytes);
            return new AdminOriginalEvidence(grant, ownedImageBytes);
        } catch (IOException | RuntimeException error) {
            zero(ownedImageBytes);
            throw error;
        }
    }

    public String grantId() { return grantId; }
    public int contentRevision() { return contentRevision; }
    public long expiresAtEpochMs() { return expiresAtEpochMs; }
    public double latitude() { return latitude; }
    public double longitude() { return longitude; }
    public Double accuracyMeters() { return accuracyMeters; }
    public String contentType() { return contentType; }
    public String sha256() { return sha256; }
    public int byteCount() { return byteCount; }

    public synchronized boolean matches(
        String expectedReportId,
        int expectedContentRevision,
        String expectedSessionId,
        String expectedDeviceId,
        long nowEpochMs
    ) {
        return verified
            && expiresAtEpochMs > nowEpochMs
            && reportId.equals(expectedReportId)
            && contentRevision == expectedContentRevision
            && sessionId.equals(expectedSessionId)
            && deviceId.equals(expectedDeviceId);
    }

    public synchronized byte[] copyImageBytes(long nowEpochMs) {
        if (!verified || imageBytes == null || expiresAtEpochMs <= nowEpochMs) {
            close();
            throw new IllegalStateException("original evidence is unavailable or expired");
        }
        return Arrays.copyOf(imageBytes, imageBytes.length);
    }

    public synchronized void discardEncodedImageAfterDisplay() {
        if (!verified) throw new IllegalStateException("original evidence is unavailable");
        zero(imageBytes);
        imageBytes = null;
    }

    public synchronized boolean isDestroyed() {
        return !verified;
    }

    @Override
    public synchronized void close() {
        zero(imageBytes);
        imageBytes = null;
        verified = false;
    }

    static String normalizedContentType(String value) throws IOException {
        if (value == null) throw new IOException("original evidence MIME type is missing");
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        int separator = normalized.indexOf(';');
        if (separator >= 0) normalized = normalized.substring(0, separator).trim();
        return requireContentType(normalized);
    }

    private static String requireContentType(String value) throws IOException {
        if (!"image/jpeg".equals(value)
            && !"image/png".equals(value)
            && !"image/webp".equals(value)) {
            throw new IOException("original evidence MIME type is unsupported");
        }
        return value;
    }

    private static String requireResourcePath(String value, String reportId, String contentType)
        throws IOException {
        String extension = switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new IOException("original evidence MIME type is unsupported");
        };
        String expected = "/uploads/" + reportId + "." + extension;
        if (!expected.equals(value)) throw new IOException("original evidence resource path is invalid");
        return value;
    }

    private static long expiry(String value, long nowEpochMs) throws IOException {
        try {
            long parsed = OffsetDateTime.parse(value).toInstant().toEpochMilli();
            if (parsed <= nowEpochMs || parsed > nowEpochMs + MAX_GRANT_TTL_MS + 30_000L) {
                throw new IOException("original evidence grant expiry is invalid");
            }
            return parsed;
        } catch (DateTimeException | ArithmeticException error) {
            throw new IOException("original evidence grant expiry is invalid", error);
        }
    }

    private static boolean canonicalAccessToken(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length == 32
                && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String canonicalUuid(String value, String field) throws IOException {
        try {
            return AdminReportModels.canonicalUuid(value, field);
        } catch (IllegalArgumentException error) {
            throw new IOException("original evidence " + field + " is invalid", error);
        }
    }

    private static String requireDeviceId(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) {
            throw new IOException("original evidence device_id is invalid");
        }
        return value;
    }

    private static void requireImageSignature(String contentType, byte[] bytes) throws IOException {
        boolean valid = switch (contentType) {
            case "image/jpeg" -> bytes.length >= 4
                && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[bytes.length - 2] & 0xff) == 0xff
                && (bytes[bytes.length - 1] & 0xff) == 0xd9;
            case "image/png" -> bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e
                && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
                && bytes[6] == 0x1a && bytes[7] == 0x0a;
            case "image/webp" -> bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
        if (!valid) throw new IOException("original evidence bytes do not match the declared MIME type");
    }

    private static void zero(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
}
