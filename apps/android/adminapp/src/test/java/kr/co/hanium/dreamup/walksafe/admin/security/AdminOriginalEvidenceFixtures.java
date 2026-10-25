package kr.co.hanium.dreamup.walksafe.admin.security;

import java.time.Instant;

/** Test-only fixture factory: package-private grants remain inaccessible to production UI. */
public final class AdminOriginalEvidenceFixtures {
    private AdminOriginalEvidenceFixtures() {}
    public static AdminOriginalEvidence verified(String report, String session, String device,
        int revision, long now) throws Exception {
        byte[] bytes = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        AdminOriginalEvidence.IssuedGrant grant = new AdminOriginalEvidence.IssuedGrant(
            report, session, device, "55555555-5555-4555-8555-555555555555", revision,
            Instant.ofEpochMilli(now + 120_000).toString(), 37.5, 127.0, 4.5,
            "/uploads/" + report + ".png", "image/png", AdminCanonicalEncoding.sha256Hex(bytes),
            bytes.length, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", now);
        return AdminOriginalEvidence.verifyAndTake(grant, 200, "image/png", bytes.length, bytes, now);
    }
}
