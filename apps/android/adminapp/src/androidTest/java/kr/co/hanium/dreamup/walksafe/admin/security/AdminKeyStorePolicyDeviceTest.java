package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import android.app.KeyguardManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AdminKeyStorePolicyDeviceTest {
    @Test public void swipeOnlyLockCannotCreateAdministratorKey() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assume.assumeFalse("secure lock configured; swipe-only case is not applicable",
            context.getSystemService(KeyguardManager.class).isDeviceSecure());
        var store = new AdminDeviceKeyStore(context);
        int version = unusedVersion(store);
        assertThrows(AdminDeviceKeyUseAuthorization.LockScreenRequiredException.class, () -> store.generate(version));
        assertFalse("rejected key must not be left behind", store.contains(version));
    }
    @Test public void secureLockAllowsHardwareSigningAfterUserAuthentication() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assume.assumeTrue("PENDING: device owner must configure PIN/pattern/password and authenticate",
            context.getSystemService(KeyguardManager.class).isDeviceSecure());
        var store = new AdminDeviceKeyStore(context);
        int version = unusedVersion(store);
        try {
            store.generate(version);
            assertTrue(AdminDeviceKeySecurityLevel.isAccepted(store.securityLevel(version)));
            byte[] payload = "isolated native signing test".getBytes(StandardCharsets.UTF_8);
            byte[] signed = store.sign(version, payload);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(store.publicKeySpki(version))));
            verifier.update(payload); assertTrue(verifier.verify(signed));
        } finally { store.delete(version); }
    }
    private static int unusedVersion(AdminDeviceKeyStore store) throws Exception {
        int version = 100000 + new SecureRandom().nextInt(1000000);
        while (store.contains(version)) version++;
        return version;
    }
}
