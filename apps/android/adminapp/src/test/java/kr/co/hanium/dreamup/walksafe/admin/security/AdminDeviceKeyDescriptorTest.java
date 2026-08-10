package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;

public final class AdminDeviceKeyDescriptorTest {
    @Test
    public void trustedProvisionDescriptorContainsOnlyPublicSpkiMarkerAndVersion() throws Exception {
        AdminDeviceKeyStore.Descriptor descriptor = new AdminDeviceKeyStore.Descriptor(
            "a".repeat(64),
            1,
            "public-spki-base64url"
        );

        JSONObject registration = new JSONObject(descriptor.registrationDescriptor("admin-device-001"));
        java.util.Set<String> keys = new java.util.HashSet<>();
        registration.keys().forEachRemaining(keys::add);

        assertEquals(Set.of(
            "device_id", "key_marker", "key_version", "public_key_spki_base64url"
        ), keys);
        assertEquals("admin-device-001", registration.getString("device_id"));
        assertEquals("a".repeat(64), registration.getString("key_marker"));
        assertEquals(1, registration.getInt("key_version"));
        assertEquals("public-spki-base64url", registration.getString("public_key_spki_base64url"));
        assertFalse(descriptor.registrationDescriptor("admin-device-001").contains("private"));
    }

    @Test
    public void trustedProvisionDescriptorRejectsDeviceIdsShorterThanBackendContract() throws Exception {
        AdminDeviceKeyStore.Descriptor descriptor = new AdminDeviceKeyStore.Descriptor(
            "a".repeat(64),
            1,
            "public-spki-base64url"
        );

        assertThrows(IllegalArgumentException.class, () -> descriptor.registrationDescriptor("device1"));
        assertEquals(
            "12345678",
            new JSONObject(descriptor.registrationDescriptor("12345678")).optString("device_id")
        );
    }
}
