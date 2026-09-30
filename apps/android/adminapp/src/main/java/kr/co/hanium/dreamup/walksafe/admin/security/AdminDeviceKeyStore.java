package kr.co.hanium.dreamup.walksafe.admin.security;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.security.auth.x500.X500Principal;

/**
 * Creates and retains the non-exportable P-256 administrator device signing keys.
 *
 * <p>The version lifecycle (active/pending, rotation, reconciliation) lives in the pure
 * {@link AdminDeviceKeyRotation}; this class is its AndroidKeyStore and SharedPreferences
 * backend. S18-1: new keys can sign only within {@link #USER_AUTHENTICATION_VALIDITY_SECONDS}
 * after a device credential or strong biometric authentication. S18-2: a new key that
 * KeyInfo does not report as TEE or StrongBox backed is deleted before use.
 */
public final class AdminDeviceKeyStore
    implements AdminDeviceProof.Signer,
    AdminDeviceKeyRotation.KeyBackend,
    AdminDeviceKeyRotation.MetadataStore {
    /** Version of the first key on a fresh install; later versions come from rotation. */
    public static final int CURRENT_KEY_VERSION = 1;
    public static final int USER_AUTHENTICATION_VALIDITY_SECONDS = 300;

    public static final class Descriptor {
        private final String marker;
        private final int version;
        private final String publicKeySpkiBase64Url;
        private final String securityLevel;

        Descriptor(String marker, int version, String publicKeySpkiBase64Url) {
            this(marker, version, publicKeySpkiBase64Url, null);
        }

        Descriptor(String marker, int version, String publicKeySpkiBase64Url, String securityLevel) {
            this.marker = marker;
            this.version = version;
            this.publicKeySpkiBase64Url = publicKeySpkiBase64Url;
            this.securityLevel = securityLevel;
        }

        public String marker() { return marker; }
        public int version() { return version; }
        public String publicKeySpkiBase64Url() { return publicKeySpkiBase64Url; }
        /** KeyInfo level (S18-2); null only for descriptors built without a key. */
        public String securityLevel() { return securityLevel; }

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
            if (securityLevel != null) fields.put("security_level", securityLevel);
            return AdminCanonicalEncoding.canonicalJson(fields);
        }
    }

    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String ALIAS_PREFIX = "walksafe.admin.device.p256.v";
    private static final String METADATA_PREFERENCES = "walksafe_admin_device_key_metadata";
    private static final String ACTIVE_VERSION = "active_version";
    private static final String ACTIVE_MARKER = "active_marker";
    private static final String PENDING_VERSION = "pending_version";
    private static final String PENDING_MARKER = "pending_marker";
    private static final String HIGHEST_VERSION = "highest_version";
    private static final long CERTIFICATE_NOT_AFTER_EPOCH_MS = 4_102_444_800_000L;

    private final Context context;
    private final AdminDeviceKeyRotation rotation;

    public AdminDeviceKeyStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext == null ? context : applicationContext;
        this.rotation = new AdminDeviceKeyRotation(this, this);
    }

    public synchronized Descriptor ensureKey() throws GeneralSecurityException {
        return rotation.ensureActive();
    }

    @Override
    public synchronized byte[] sign(byte[] payload) throws GeneralSecurityException {
        if (payload == null || payload.length == 0) throw new GeneralSecurityException("device proof payload is required");
        return rotation.signActive(payload);
    }

    public AdminDeviceKeyRotation rotation() {
        return rotation;
    }

    // --- AdminDeviceKeyRotation.MetadataStore -------------------------------------------

    @Override
    public AdminDeviceKeyRotation.State load() throws GeneralSecurityException {
        SharedPreferences preferences = preferences();
        try {
            return new AdminDeviceKeyRotation.State(
                preferences.getInt(ACTIVE_VERSION, 0),
                preferences.getString(ACTIVE_MARKER, null),
                preferences.getInt(PENDING_VERSION, 0),
                preferences.getString(PENDING_MARKER, null),
                preferences.getInt(HIGHEST_VERSION, 0)
            );
        } catch (ClassCastException error) {
            throw new AdminDeviceKeyPreparationException(
                AdminDeviceKeyPreparationException.Code.METADATA_INVALID,
                "administrator device key metadata is invalid", error);
        }
    }

    @Override
    public boolean commit(AdminDeviceKeyRotation.State state) {
        SharedPreferences.Editor editor = preferences().edit()
            .putInt(ACTIVE_VERSION, state.activeVersion())
            .putInt(HIGHEST_VERSION, Math.max(state.highestVersion(), state.activeVersion()));
        if (state.activeMarker() == null) editor.remove(ACTIVE_MARKER);
        else editor.putString(ACTIVE_MARKER, state.activeMarker());
        if (state.pendingVersion() == 0) {
            editor.remove(PENDING_VERSION).remove(PENDING_MARKER);
        } else {
            editor.putInt(PENDING_VERSION, state.pendingVersion())
                .putString(PENDING_MARKER, state.pendingMarker());
        }
        return editor.commit();
    }

    // --- AdminDeviceKeyRotation.KeyBackend ----------------------------------------------

    @Override
    public boolean contains(int version) throws GeneralSecurityException {
        try {
            return loadedKeyStore().containsAlias(alias(version));
        } catch (GeneralSecurityException error) {
            throw error;
        } catch (Exception error) {
            throw new AdminDeviceKeyPreparationException(
                AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
                "AndroidKeyStore alias lookup failed", error);
        }
    }

    @Override
    public void generate(int version) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEY_STORE
        );
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
            alias(version),
            KeyProperties.PURPOSE_SIGN
        )
            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(new X500Principal("CN=WalkSafe Admin Device"))
            .setCertificateSerialNumber(BigInteger.valueOf(version))
            .setCertificateNotBefore(new Date(0L))
            .setCertificateNotAfter(new Date(CERTIFICATE_NOT_AFTER_EPOCH_MS))
            // S18-1: using the key requires a recent device credential/strong biometric.
            .setUserAuthenticationRequired(true)
            // S18-1-F3: this has no effect on this time-bound key (positive validity window);
            // Android applies it only to biometric-per-use keys. What permanently invalidates
            // this key is removing or resetting the secure lock screen
            // (KeyPermanentlyInvalidatedException → KeyInvalidatedException).
            .setInvalidatedByBiometricEnrollment(true);
        applyUserAuthenticationWindow(builder);
        // S18-1-F1: without a secure lock screen the platform refuses the key; name the cause.
        if (!deviceSecure()) throw new AdminDeviceKeyUseAuthorization.LockScreenRequiredException(null);
        try {
            generator.initialize(builder.build());
            generator.generateKeyPair();
        } catch (GeneralSecurityException | RuntimeException error) {
            if (!deviceSecure()) throw new AdminDeviceKeyUseAuthorization.LockScreenRequiredException(error);
            // S18-23: name the failing step with a secret-free code; the cause is kept.
            throw new AdminDeviceKeyPreparationException(
                AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
                "AndroidKeyStore key generation failed", error);
        }
    }

    private boolean deviceSecure() {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    @SuppressWarnings("deprecation")
    private static void applyUserAuthenticationWindow(KeyGenParameterSpec.Builder builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                USER_AUTHENTICATION_VALIDITY_SECONDS,
                KeyProperties.AUTH_DEVICE_CREDENTIAL | KeyProperties.AUTH_BIOMETRIC_STRONG
            );
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(USER_AUTHENTICATION_VALIDITY_SECONDS);
        }
    }

    @Override
    public byte[] publicKeySpki(int version) throws GeneralSecurityException {
        byte[] spki = requireP256PublicKey(loadedKeyStore(), alias(version)).getEncoded();
        if (spki == null || spki.length == 0) throw new GeneralSecurityException("device public key cannot be encoded");
        return spki;
    }

    @Override
    @SuppressWarnings("deprecation")
    public String securityLevel(int version) throws GeneralSecurityException {
        PrivateKey privateKey = privateKey(version);
        KeyFactory factory = KeyFactory.getInstance(privateKey.getAlgorithm(), ANDROID_KEY_STORE);
        KeyInfo info = factory.getKeySpec(privateKey, KeyInfo.class);
        Integer level = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ? Integer.valueOf(info.getSecurityLevel())
            : null;
        return AdminDeviceKeySecurityLevel.classify(level, info.isInsideSecureHardware());
    }

    @Override
    public byte[] sign(int version, byte[] payload) throws GeneralSecurityException {
        if (payload == null || payload.length == 0) throw new GeneralSecurityException("device proof payload is required");
        PrivateKey privateKey;
        try {
            privateKey = privateKey(version);
        } catch (GeneralSecurityException error) {
            // Keystore2 reports an invalidated key while loading it (UnrecoverableKeyException
            // caused by KeyPermanentlyInvalidatedException), not only at initSign.
            if (causedByInvalidation(error)) {
                throw new AdminDeviceKeyUseAuthorization.KeyInvalidatedException(error);
            }
            throw error;
        }
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(privateKey);
            signature.update(payload);
            return signature.sign();
        } catch (UserNotAuthenticatedException error) {
            throw new AdminDeviceKeyUseAuthorization.AuthenticationRequiredException(error);
        } catch (KeyPermanentlyInvalidatedException error) {
            throw new AdminDeviceKeyUseAuthorization.KeyInvalidatedException(error);
        }
    }

    @Override
    public void delete(int version) throws GeneralSecurityException {
        try {
            loadedKeyStore().deleteEntry(alias(version));
        } catch (GeneralSecurityException error) {
            throw error;
        } catch (Exception error) {
            throw new GeneralSecurityException("AndroidKeyStore entry could not be deleted", error);
        }
    }

    private static boolean causedByInvalidation(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof KeyPermanentlyInvalidatedException) return true;
            if (cursor.getCause() == cursor) break;
        }
        return false;
    }

    private PrivateKey privateKey(int version) throws GeneralSecurityException {
        PrivateKey privateKey = (PrivateKey) loadedKeyStore().getKey(alias(version), null);
        if (privateKey == null || !KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(privateKey.getAlgorithm())) {
            throw new GeneralSecurityException("administrator device private key is unavailable");
        }
        return privateKey;
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(METADATA_PREFERENCES, Context.MODE_PRIVATE);
    }

    private static KeyStore loadedKeyStore() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            return keyStore;
        } catch (GeneralSecurityException error) {
            throw error;
        } catch (Exception error) {
            throw new AdminDeviceKeyPreparationException(
                AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
                "AndroidKeyStore could not be loaded", error);
        }
    }

    private static ECPublicKey requireP256PublicKey(KeyStore keyStore, String alias)
        throws GeneralSecurityException {
        java.security.cert.Certificate certificate;
        try {
            certificate = keyStore.getCertificate(alias);
        } catch (RuntimeException error) {
            // adm-F3: keystore2 may report a failed certificate read as ProviderException etc.
            throw new AdminDeviceKeyPreparationException(
                AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
                "AndroidKeyStore certificate lookup failed", error);
        }
        if (certificate == null || !(certificate.getPublicKey() instanceof ECPublicKey publicKey)) {
            throw new AdminDeviceKeyPreparationException(
                AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
                "administrator device public key is unavailable");
        }
        if (publicKey.getParams() == null
            || publicKey.getParams().getCurve().getField().getFieldSize() != 256
            || publicKey.getParams().getOrder().bitLength() != 256) {
            throw new AdminDeviceKeyPreparationException(
                AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
                "administrator device key is not P-256");
        }
        return publicKey;
    }

    private static String alias(int version) {
        if (version < 1) throw new IllegalArgumentException("device key version is invalid");
        return ALIAS_PREFIX + version;
    }
}
