package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class AdminCanonicalEncodingTest {
    @Test
    public void queryVectorsPreserveDuplicatesBlanksUnicodeSpaceAndLiteralPlus() {
        var parameters = List.of(
            new AdminCanonicalEncoding.QueryParameter("tag", "b"),
            new AdminCanonicalEncoding.QueryParameter("q", "한 글"),
            new AdminCanonicalEncoding.QueryParameter("empty", ""),
            new AdminCanonicalEncoding.QueryParameter("tag", "a"),
            new AdminCanonicalEncoding.QueryParameter("plus", "a+b")
        );

        String canonical = AdminCanonicalEncoding.canonicalQuery(parameters);

        assertEquals("empty=&plus=a%2Bb&q=%ED%95%9C%20%EA%B8%80&tag=a&tag=b", canonical);
        assertEquals(
            "0cb34d7f20d8a37fd1861e6f30b2311e1c551f46ad9c34105c62fd0eacd5cfc6",
            AdminCanonicalEncoding.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8))
        );

        String second = AdminCanonicalEncoding.canonicalQuery(List.of(
            new AdminCanonicalEncoding.QueryParameter("b", "2"),
            new AdminCanonicalEncoding.QueryParameter("a", "~"),
            new AdminCanonicalEncoding.QueryParameter("b", "1"),
            new AdminCanonicalEncoding.QueryParameter("", "")
        ));
        assertEquals("=&a=~&b=1&b=2", second);
        assertEquals(
            "c88f73c4de91f58377bb88d5a00ea106498f105d322450cf9bc477efdc3caab2",
            AdminCanonicalEncoding.sha256Hex(second.getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    public void rawQueryTreatsPlusLiterallyAndRejectsMalformedPercentUtf8() {
        var parsed = AdminCanonicalEncoding.parseRawQuery("q=%ED%95%9C%20%EA%B8%80&plus=a+b&tag=b&tag=a");
        assertEquals("plus=a%2Bb&q=%ED%95%9C%20%EA%B8%80&tag=a&tag=b", AdminCanonicalEncoding.canonicalQuery(parsed));

        assertThrows(IllegalArgumentException.class, () -> AdminCanonicalEncoding.parseRawQuery("a=%"));
        assertThrows(IllegalArgumentException.class, () -> AdminCanonicalEncoding.parseRawQuery("a=%GG"));
        assertThrows(IllegalArgumentException.class, () -> AdminCanonicalEncoding.parseRawQuery("a=%C3%28"));
    }

    @Test
    public void proofVectorUsesSortedCompactUtf8Exact18Json() {
        Map<String, Object> exact18 = new LinkedHashMap<>();
        exact18.put("session_id", "33333333-3333-4333-8333-333333333333");
        exact18.put("schema_version", "walksafe.admin-device-proof.v2");
        exact18.put("read_purpose", "report.review_decisions");
        exact18.put("query_sha256", EMPTY_SHA);
        exact18.put("purpose", "ACTION");
        exact18.put("path", "/api/admin/reports");
        exact18.put("nonce", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        exact18.put("method", "GET");
        exact18.put("issued_at_epoch_ms", 1_700_000_000_000L);
        exact18.put("expires_at_epoch_ms", 1_700_000_120_000L);
        exact18.put("device_key_version", 1);
        exact18.put("device_key_marker", "a".repeat(64));
        exact18.put("device_id", "device-001");
        exact18.put("correlation_id", "22222222-2222-4222-8222-222222222222");
        exact18.put("challenge_id", "11111111-1111-4111-8111-111111111111");
        exact18.put("body_sha256", EMPTY_SHA);
        exact18.put("admin_id", "admin-001");
        exact18.put("action", null);

        String canonical = AdminCanonicalEncoding.canonicalJson(exact18);

        assertEquals(
            "43dd9899c2d8ab5ed3f0a71b33856e828e7b57203792959490739390fa2ba8e6",
            AdminCanonicalEncoding.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(canonical.startsWith("{\"action\":null,\"admin_id\":"));
        assertTrue(canonical.contains("한글") == false);
    }

    @Test
    public void canonicalJsonKeepsUnicodeUnescaped() {
        assertEquals("{\"reason\":\"한글 사유\"}", AdminCanonicalEncoding.canonicalJson(Map.of("reason", "한글 사유")));
    }

    private static final String EMPTY_SHA =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
}
