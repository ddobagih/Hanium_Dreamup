package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Map;
import org.junit.Test;

public final class AdminStrictJsonTest {
    @Test
    public void exactObjectsPreservePhysicalNullAndUnicode() throws Exception {
        Map<String, Object> parsed = AdminStrictJson.parseObject(
            "{\"action\":null,\"reason\":\"한글\\n사유\",\"revision\":1,\"ok\":true}"
        );

        assertTrue(parsed.containsKey("action"));
        assertEquals(null, parsed.get("action"));
        assertEquals("한글\n사유", parsed.get("reason"));
        assertEquals(1L, parsed.get("revision"));
        assertEquals(Boolean.TRUE, parsed.get("ok"));
    }

    @Test
    public void duplicatesTrailingDataAndLenientAndroidSyntaxAreRejected() {
        String[] invalid = {
            "{\"a\":1,\"a\":2}",
            "{\"a\":1} trailing",
            "{'a':1}",
            "{a:1}",
            "{\"a\"=1}",
            "{\"a\":1;\"b\":2}",
            "{\"a\":1,}",
            "{\"a\":01}",
            "{\"a\":1\u0661}",
            "{\"a\":1e0}"
        };
        for (String value : invalid) {
            assertThrows(IOException.class, () -> AdminStrictJson.parseObject(value));
        }
    }

    @Test
    public void arraysRejectDuplicateMembersInsideItemsAndNonObjectItems() {
        assertThrows(IOException.class, () -> AdminStrictJson.parseObjectArray("[{\"a\":1,\"a\":2}]"));
        assertThrows(IOException.class, () -> AdminStrictJson.parseObjectArray("[1]"));
    }

    @Test
    public void malformedUtf8IsRejectedBeforeJsonParsing() {
        assertThrows(IOException.class, () -> AdminStrictJson.decodeUtf8(new byte[] {(byte) 0xc3, 0x28}));
    }
}
