package kr.co.hanium.dreamup.walksafe.admin.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.security.auth.x500.X500Principal;

/** Creates and retains the non-exportable P-256 administrator device signing key. */
public final class AdminDeviceKeyStore implements AdminDeviceProof.Signer {
    public static final int CURRENT_KEY_VERSION = 1;

    public static final class Descriptor {
        private final String marker;
        private final int version;
        private final String publicKeySpkiBase64Url;

        Descriptor(String marker, int version, String publicKeySpkiBase64Url) {
            this.marker = marker;
            this.version = version;
            this.publicKeySpkiBase64Url = publicKeySpkiBase64Url;
        }

        public String marker() { return marker; }
        public int version() { return version; }
        public String publicKeySpkiBase64Url() { return publicKeySpkiBase64Url; }

        /** Public-only descriptor copied into the trusted local device-provisioning CLI. */
        public String registrationDescriptor(String deviceId) {
            if (deviceId == null || !deviceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) {
                throw new IllegalArgumentException("device_id is invalid");
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("device_id", deviceId);
            fields.put("key_marker", marker);
            fields.put("key_version", version);
            fields.put("public_key_spki_base64url", publicKeySpkiBase64Url);
            return AdminCanonicalEncoding.canonicalJson(fields);
        }
    }

    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String ALIAS_PREFIX = "walksafe.admin.device.p256.v";
    private static final String METADATA_PREFERENCES = "walksafe_admin_device_key_metadata";
    private static final String ACTIVE_VERSION = "active_version";
    private static final String ACTIVE_MARKER = "active_marker";
    private static final long CERTIFICATE_NOT_AFTER_EPOCH_MS = 4_102_444_800_000L;

    private final Context context;

    public AdminDeviceKeyStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext == null ? context : applicationContext;
    }

    public synchronized Descriptor ensureKey() throws GeneralSecurityException {
        SharedPreferences preferences = context.getSharedPreferences(METADATA_PREFERENCES, Context.MODE_PRIVATE);
        int storedVersion;
        String storedMarker;
        try {
            storedVersion = preferences.getInt(ACTIVE_VERSION, 0);
            storedMarker = preferences.getString(ACTIVE_MARKER, null);
        } catch (ClassCastException error) {
            throw new GeneralSecurityException("administrator device key metadata is invalid", error);
        }
        if ((storedVersion == 0) != (storedMarker == null)) {
            throw new GeneralSecurityException("administrator device key metadata is incomplete");
        }
        if (storedVersion != 0 && storedVersion != CURRENT_KEY_VERSION) {
            throw new GeneralSecurityException("administrator device key version is unsupported");
        }
        if (storedMarker != null && !storedMarker.matches("[0-9a-f]{64}")) {
            throw new GeneralSecurityException("administrator device key marker is invalid");
        }

        KeyStore keyStore = loadedKeyStore();
        String alias = alias(CURRENT_KEY_VERSION);
        if (!keyStore.containsAlias(alias)) {
            if (storedVersion != 0) {
                throw new GeneralSecurityException("registered administrator device key is unavailable");
            }
            generate(alias);
            keyStore = loadedKeyStore();
        }
        ECPublicKey publicKey = requireP256PublicKey(keyStore, alias);
        byte[] spki = publicKey.getEncoded();
        if (spki == null || spki.length == 0) throw new GeneralSecurityException("device public key cannot be encoded");
        String marker = AdminCanonicalEncoding.sha256Hex(spki);
        if (storedMarker != null && !storedMarker.equals(marker)) {
            throw new GeneralSecurityException("administrator device key marker does not match stored metadata");
        }
        if (storedMarker == null && !preferences.edit()
            .putInt(ACTIVE_VERSION, CURRENT_KEY_VERSION)
            .putString(ACTIVE_MARKER, marker)
            .commit()) {
            throw new GeneralSecurityException("administrator device key metadata could not be committed");
        }
        return new Descriptor(
            marker,
            CURRENT_KEY_VERSION,
            Base64.getUrlEncoder().withoutPadding().encodeToString(spki)
        );
    }

    @Override
    public synchronized byte[] sign(byte[] payload) throws GeneralSecurityException {
        if (payload == null || payload.length == 0) throw new GeneralSecurityException("device proof payload is required");
        Descriptor descriptor = ensureKey();
        KeyStore keyStore = loadedKeyStore();
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias(descriptor.version()), null);
        if (privateKey == null || !KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(privateKey.getAlgorithm())) {
            throw new GeneralSecurityException("administrator device private key is unavailable");
        }
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        signature.update(payload);
        return signature.sign();
    }

    private static KeyStore loadedKeyStore() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            return keyStore;
        } catch (GeneralSecurityException error) {
            throw error;
        } catch (Exception error) {
            throw new GeneralSecurityException("AndroidKeyStore could not be loaded", error);
        }
    }

    private static void generate(String alias) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEY_STORE
        );
        KeyGenParameterSpec specification = new KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN
        )
            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(new X500Principal("CN=WalkSafe Admin Device"))
            .setCertificateSerialNumber(BigInteger.valueOf(CURRENT_KEY_VERSION))
            .setCertificateNotBefore(new Date(0L))
            .setCertificateNotAfter(new Date(CERTIFICATE_NOT_AFTER_EPOCH_MS))
            .setUserAuthenticationRequired(false)
            .build();
        generator.initialize(specification);
        generator.generateKeyPair();
    }

    private static ECPublicKey requireP256PublicKey(KeyStore keyStore, String alias)
        throws GeneralSecurityException {
        var certificate = keyStore.getCertificate(alias);
        if (certificate == null || !(certificate.getPublicKey() instanceof ECPublicKey publicKey)) {
            throw new GeneralSecurityException("administrator device public key is unavailable");
        }
        if (publicKey.getParams() == null
            || publicKey.getParams().getCurve().getField().getFieldSize() != 256
            || publicKey.getParams().getOrder().bitLength() != 256) {
            throw new GeneralSecurityException("administrator device key is not P-256");
        }
        return publicKey;
    }

    private static String alias(int version) {
        return ALIAS_PREFIX + version;
    }
}
