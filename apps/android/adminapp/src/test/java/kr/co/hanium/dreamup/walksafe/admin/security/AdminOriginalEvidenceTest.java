package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import org.junit.Test;

public final class AdminOriginalEvidenceTest {
    @Test
    public void verifiedBytesStayBoundToReportRevisionSessionDeviceAndExpiry() throws Exception {
        byte[] image = pngSignature();
        AdminOriginalEvidence evidence = AdminOriginalEvidence.verifyAndTake(
            grant(image),
            200,
            "image/png",
            image.length,
            image,
            NOW
        );

        assertTrue(evidence.matches(REPORT_ID, 3, SESSION_ID, DEVICE_ID, NOW));
        assertFalse(evidence.matches(OTHER_REPORT_ID, 3, SESSION_ID, DEVICE_ID, NOW));
        assertFalse(evidence.matches(REPORT_ID, 4, SESSION_ID, DEVICE_ID, NOW));
        assertFalse(evidence.matches(REPORT_ID, 3, OTHER_SESSION_ID, DEVICE_ID, NOW));
        assertFalse(evidence.matches(REPORT_ID, 3, SESSION_ID, OTHER_DEVICE_ID, NOW));
        assertFalse(evidence.matches(REPORT_ID, 3, SESSION_ID, DEVICE_ID, NOW + 120_000L));

        byte[] displayCopy = evidence.copyImageBytes(NOW);
        assertArrayEquals(pngSignature(), displayCopy);
        Arrays.fill(displayCopy, (byte) 0);
        evidence.discardEncodedImageAfterDisplay();
        assertTrue(evidence.matches(REPORT_ID, 3, SESSION_ID, DEVICE_ID, NOW));
        assertThrows(IllegalStateException.class, () -> evidence.copyImageBytes(NOW));
        evidence.close();
        assertTrue(evidence.isDestroyed());
        assertFalse(evidence.matches(REPORT_ID, 3, SESSION_ID, DEVICE_ID, NOW));
    }

    @Test
    public void mimeLengthShaAndSignatureMismatchFailClosedAndZeroOwnedBytes() throws Exception {
        byte[] wrongMime = pngSignature();
        assertThrows(IOException.class, () -> AdminOriginalEvidence.verifyAndTake(
            grant(wrongMime), 200, "image/jpeg", wrongMime.length, wrongMime, NOW
        ));
        assertAllZero(wrongMime);

        byte[] wrongLength = pngSignature();
        assertThrows(IOException.class, () -> AdminOriginalEvidence.verifyAndTake(
            grant(wrongLength), 200, "image/png", wrongLength.length + 1L, wrongLength, NOW
        ));
        assertAllZero(wrongLength);

        byte[] wrongSha = pngSignature();
        AdminOriginalEvidence.IssuedGrant shaGrant = grant(wrongSha, "0".repeat(64));
        assertThrows(IOException.class, () -> AdminOriginalEvidence.verifyAndTake(
            shaGrant, 200, "image/png", wrongSha.length, wrongSha, NOW
        ));
        assertAllZero(wrongSha);

        byte[] wrongSignature = "not-png!".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        AdminOriginalEvidence.IssuedGrant signatureGrant = grant(wrongSignature);
        assertThrows(IOException.class, () -> AdminOriginalEvidence.verifyAndTake(
            signatureGrant, 200, "image/png", wrongSignature.length, wrongSignature, NOW
        ));
        assertAllZero(wrongSignature);
    }

    @Test
    public void grantTokenIsCanonicalOneUseAndNeverRetainedAfterConsumption() throws Exception {
        AdminOriginalEvidence.IssuedGrant grant = grant(pngSignature());
        char[] consumed = grant.consumeAccessToken(NOW);

        assertArrayEquals(ACCESS_TOKEN.toCharArray(), consumed);
        assertThrows(IOException.class, () -> grant.consumeAccessToken(NOW));
        Arrays.fill(consumed, '\0');
        grant.close();

        assertThrows(IOException.class, () -> new AdminOriginalEvidence.IssuedGrant(
            REPORT_ID, SESSION_ID, DEVICE_ID, GRANT_ID, 3, expiry(), 37.5, 127.0, 4.5,
            "/uploads/" + REPORT_ID + ".png", "image/png", "0".repeat(64), 8,
            "not-a-token", NOW
        ));
        assertThrows(IOException.class, () -> new AdminOriginalEvidence.IssuedGrant(
            REPORT_ID, SESSION_ID, DEVICE_ID, GRANT_ID, 3, expiry(), 37.5, 127.0, 4.5,
            "/uploads/" + OTHER_REPORT_ID + ".png", "image/png", "0".repeat(64), 8,
            ACCESS_TOKEN, NOW
        ));
    }

    private static AdminOriginalEvidence.IssuedGrant grant(byte[] image) throws IOException {
        return grant(image, AdminCanonicalEncoding.sha256Hex(image));
    }

    private static AdminOriginalEvidence.IssuedGrant grant(byte[] image, String sha) throws IOException {
        return new AdminOriginalEvidence.IssuedGrant(
            REPORT_ID,
            SESSION_ID,
            DEVICE_ID,
            GRANT_ID,
            3,
            expiry(),
            37.5,
            127.0,
            4.5,
            "/uploads/" + REPORT_ID + ".png",
            "image/png",
            sha,
            image.length,
            ACCESS_TOKEN,
            NOW
        );
    }

    private static String expiry() {
        return Instant.ofEpochMilli(NOW + 120_000L).toString();
    }

    private static byte[] pngSignature() {
        return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    }

    private static void assertAllZero(byte[] bytes) {
        for (byte value : bytes) assertTrue(value == 0);
    }

    private static final long NOW = 1_700_000_000_001L;
    private static final String REPORT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_REPORT_ID = "22222222-2222-4222-8222-222222222222";
    private static final String SESSION_ID = "33333333-3333-4333-8333-333333333333";
    private static final String OTHER_SESSION_ID = "44444444-4444-4444-8444-444444444444";
    private static final String GRANT_ID = "55555555-5555-4555-8555-555555555555";
    private static final String DEVICE_ID = "admin-device-12345678-1234-1234-1234-123456789abc";
    private static final String OTHER_DEVICE_ID = "admin-device-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ACCESS_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
}
