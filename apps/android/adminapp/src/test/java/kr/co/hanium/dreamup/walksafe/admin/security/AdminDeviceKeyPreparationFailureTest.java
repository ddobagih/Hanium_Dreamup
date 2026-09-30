package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * S18-23 / S18-21: key preparation failures are split into secret-free codes, and each code
 * has one next action that never tells the operator to delete a possibly valid key.
 */
public final class AdminDeviceKeyPreparationFailureTest {
    private static final String MARKER = "b".repeat(64);

    @Test
    public void invalidOrIncompleteMetadataIsMetadataInvalid() {
        assertCode(AdminDeviceKeyPreparationException.Code.METADATA_INVALID,
            new AdminDeviceKeyRotation.State(1, null, 0, null, 1));
        assertCode(AdminDeviceKeyPreparationException.Code.METADATA_INVALID,
            new AdminDeviceKeyRotation.State(1, "not-a-marker", 0, null, 1));
        assertCode(AdminDeviceKeyPreparationException.Code.METADATA_INVALID,
            new AdminDeviceKeyRotation.State(1, MARKER, 1, MARKER, 1));
        assertCode(AdminDeviceKeyPreparationException.Code.METADATA_INVALID, null);
    }

    @Test
    public void negativeStoredVersionIsVersionUnsupported() {
        assertCode(AdminDeviceKeyPreparationException.Code.VERSION_UNSUPPORTED,
            new AdminDeviceKeyRotation.State(-1, MARKER, 0, null, 0));
        assertCode(AdminDeviceKeyPreparationException.Code.VERSION_UNSUPPORTED,
            new AdminDeviceKeyRotation.State(0, null, 0, null, -3));
    }

    @Test
    public void keyEntryNotMatchingTheStoredMarkerIsMarkerMismatch() {
        Keys keys = new Keys();
        keys.spki.put(1, "spki-1".getBytes(StandardCharsets.UTF_8));
        assertCode(AdminDeviceKeyPreparationException.Code.MARKER_MISMATCH, keys,
            new AdminDeviceKeyRotation.State(1, MARKER, 0, null, 1));
    }

    @Test
    public void missingRegisteredEntryKeepsTheReplacementPathCode() {
        assertCode(AdminDeviceKeyPreparationException.Code.REGISTERED_KEY_MISSING, new Keys(),
            new AdminDeviceKeyRotation.State(1, MARKER, 0, null, 1));
    }

    @Test
    public void metadataWriteFailureSoftwareKeyLockScreenAndKeyStoreAreSeparate() {
        Keys keys = new Keys();
        Metadata failingWrite = new Metadata(AdminDeviceKeyRotation.State.EMPTY);
        failingWrite.commitResult = false;
        assertEquals(AdminDeviceKeyPreparationException.Code.METADATA_WRITE_FAILED,
            classifyEnsure(keys, failingWrite));

        Keys software = new Keys();
        software.level = AdminDeviceKeySecurityLevel.SOFTWARE;
        assertEquals(AdminDeviceKeyPreparationException.Code.HARDWARE_KEY_UNSUPPORTED,
            classifyEnsure(software, new Metadata(AdminDeviceKeyRotation.State.EMPTY)));
        assertTrue("a refused software key is not left behind", software.spki.isEmpty());

        Keys noLockScreen = new Keys();
        noLockScreen.generateFailure = new AdminDeviceKeyUseAuthorization.LockScreenRequiredException(null);
        assertEquals(AdminDeviceKeyPreparationException.Code.LOCK_SCREEN_REQUIRED,
            classifyEnsure(noLockScreen, new Metadata(AdminDeviceKeyRotation.State.EMPTY)));

        Keys keyStoreDown = new Keys();
        keyStoreDown.containsFailure = new AdminDeviceKeyPreparationException(
            AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE, "AndroidKeyStore could not be loaded");
        assertEquals(AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
            classifyEnsure(keyStoreDown, new Metadata(new AdminDeviceKeyRotation.State(1, MARKER, 0, null, 1))));

        assertEquals(AdminDeviceKeyPreparationException.Code.UNKNOWN,
            AdminDeviceKeyPreparationException.classify(new IllegalStateException("x")));
        assertEquals(AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
            AdminDeviceKeyPreparationException.classify(new RuntimeException(
                new AdminDeviceKeyPreparationException(
                    AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE, "wrapped"))));
    }

    @Test
    public void admF3RawAndroidKeyStoreExceptionsAreKeyStoreUnavailable() {
        // Review probe (sweep/w25/review-adminapp/probes): these raw types used to be UNKNOWN.
        AdminDeviceKeyRotation.State active = new AdminDeviceKeyRotation.State(1, MARKER, 0, null, 1);
        Keys aliasLookup = new Keys();
        aliasLookup.containsFailure = new java.security.KeyStoreException("keystore2 system error");
        assertEquals(AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
            classifyEnsureAny(aliasLookup, new Metadata(active)));

        Keys keyStoreLoad = new Keys();
        keyStoreLoad.containsFailure = new java.security.cert.CertificateException("load");
        assertEquals(AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
            classifyEnsureAny(keyStoreLoad, new Metadata(active)));

        Keys certificateRead = new Keys();
        certificateRead.spki.put(1, new byte[] {1});
        certificateRead.spkiRuntime = new java.security.ProviderException("keystore2 getCertificate");
        assertEquals(AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
            classifyEnsureAny(certificateRead, new Metadata(active)));

        Keys newKeyInfo = new Keys();
        newKeyInfo.levelFailure = new java.security.UnrecoverableKeyException("key info");
        assertEquals(AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
            classifyEnsureAny(newKeyInfo, new Metadata(AdminDeviceKeyRotation.State.EMPTY)));
        assertTrue("an unverifiable new key is not left behind", newKeyInfo.spki.isEmpty());

        Keys provider = new Keys();
        provider.generateFailure = new java.security.NoSuchProviderException("AndroidKeyStore");
        assertEquals(AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
            classifyEnsureAny(provider, new Metadata(AdminDeviceKeyRotation.State.EMPTY)));

        for (Throwable raw : new Throwable[] {
            new java.security.KeyStoreException("x"),
            new java.security.UnrecoverableKeyException("x"),
            new java.security.ProviderException("x"),
            new java.security.cert.CertificateException("x"),
            new java.security.NoSuchProviderException("x"),
            new GeneralSecurityException("wrapped", new java.security.KeyStoreException("x")),
            new RuntimeException(new java.security.ProviderException("x"))
        }) {
            assertEquals(raw.toString(), AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
                AdminDeviceKeyPreparationException.classify(raw));
        }
        // Typed codes still win over a raw cause, and key-use states keep their own handling.
        assertEquals(AdminDeviceKeyPreparationException.Code.METADATA_WRITE_FAILED,
            AdminDeviceKeyPreparationException.classify(new AdminDeviceKeyPreparationException(
                AdminDeviceKeyPreparationException.Code.METADATA_WRITE_FAILED, "x",
                new java.security.KeyStoreException("x"))));
        assertEquals(AdminDeviceKeyPreparationException.Code.REGISTERED_KEY_MISSING,
            AdminDeviceKeyPreparationException.classify(new GeneralSecurityException(
                new AdminDeviceKeyRotation.ActiveKeyUnavailableException())));
        assertEquals(AdminDeviceKeyPreparationException.Code.UNKNOWN,
            AdminDeviceKeyPreparationException.classify(new AdminDeviceKeyUseAuthorization.KeyInvalidatedException(
                new java.security.UnrecoverableKeyException("invalidated"))));
        assertEquals(AdminDeviceKeyPreparationException.Code.UNKNOWN,
            AdminDeviceKeyPreparationException.classify(
                new AdminDeviceKeyUseAuthorization.AuthenticationRequiredException(
                    new java.security.UnrecoverableKeyException("auth"))));
        assertEquals(AdminDeviceKeyPreparationException.Code.UNKNOWN,
            AdminDeviceKeyPreparationException.classify(new java.security.NoSuchAlgorithmException("x")));
    }

    @Test
    public void admF4TransientlyAbsentRegisteredKeyIsRecheckedOnceAfterAPause() throws Exception {
        byte[] spki = "spki-1".getBytes(StandardCharsets.UTF_8);
        String marker = AdminCanonicalEncoding.sha256Hex(spki);
        Keys keys = new Keys();
        keys.spki.put(1, spki);
        keys.containsAnswers.add(false); // a transient "absent" answer, then the real one
        java.util.List<Long> pauses = new java.util.ArrayList<>();

        AdminDeviceKeyStore.Descriptor descriptor = new AdminDeviceKeyRotation(
            keys, new Metadata(new AdminDeviceKeyRotation.State(1, marker, 0, null, 1)), pauses::add
        ).ensureActive();

        assertEquals(1, descriptor.version());
        assertEquals(marker, descriptor.marker());
        assertEquals(java.util.List.of(AdminDeviceKeyRotation.ACTIVE_KEY_RECHECK_DELAY_MS), pauses);
        assertEquals(2, keys.containsCalls);
    }

    @Test
    public void admF4PersistentlyAbsentRegisteredKeyIsMissingAfterExactlyOneRecheck() throws Exception {
        AdminDeviceKeyRotation.State active = new AdminDeviceKeyRotation.State(1, MARKER, 0, null, 1);
        Keys missing = new Keys();
        java.util.List<Long> pauses = new java.util.ArrayList<>();
        GeneralSecurityException error = assertThrows(GeneralSecurityException.class,
            () -> new AdminDeviceKeyRotation(missing, new Metadata(active), pauses::add).ensureActive());
        assertTrue(error instanceof AdminDeviceKeyRotation.ActiveKeyUnavailableException);
        assertEquals(AdminDeviceKeyPreparationException.Code.REGISTERED_KEY_MISSING,
            AdminDeviceKeyPreparationException.classify(error));
        assertEquals(1, pauses.size());
        assertEquals(2, missing.containsCalls);
        assertTrue("nothing is generated or deleted on this path", missing.spki.isEmpty());

        // The re-check itself failing is a key store problem, never "key missing".
        Keys failingRecheck = new Keys();
        failingRecheck.containsAnswers.add(false);
        failingRecheck.containsFailure = new java.security.KeyStoreException("keystore2 busy");
        assertEquals(AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE, classifyEnsureAny(
            () -> new AdminDeviceKeyRotation(failingRecheck, new Metadata(active), ignored -> { }).ensureActive()));

        // An interrupted pause is not evidence that the key is gone.
        Keys interrupted = new Keys();
        try {
            assertEquals(AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE, classifyEnsureAny(
                () -> new AdminDeviceKeyRotation(interrupted, new Metadata(active), ignored -> {
                    throw new InterruptedException("shutdown");
                }).ensureActive()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, interrupted.containsCalls);
    }

    @Test
    public void admF4RegisteredKeyMissingGuidanceRestartsFirstAndReplacesOnlyIfItPersists() throws Exception {
        String text = AdminDeviceKeyPreparationException.guidance(
            AdminDeviceKeyPreparationException.Code.REGISTERED_KEY_MISSING);
        int restart = text.indexOf("먼저 기기를 다시 시작한 뒤 앱을 다시 여세요.");
        assertTrue(text, restart >= 0);
        assertTrue(text, restart < text.indexOf("그 뒤에도 같을 때만 교체용 새 기기키를"));
        assertTrue(text.contains("지우지 말"));
        String activity = new String(Files.readAllBytes(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java")),
            StandardCharsets.UTF_8);
        int start = activity.indexOf("private void showActiveDeviceKeyUnavailable(");
        String shown = activity.substring(start, activity.indexOf("private void completeSecurityInitialization(", start));
        assertTrue(shown.contains("resultText.setText(AdminDeviceKeyPreparationException.guidance(\n"
            + "            AdminDeviceKeyPreparationException.Code.REGISTERED_KEY_MISSING\n        ));"));
        assertFalse(shown.contains("교체 필요"));
        assertFalse(shown.contains("교체용 새 기기키를 준비해"));
    }

    @Test
    public void admF3CertificateReadRuntimeExceptionIsWrappedAsKeyStoreUnavailable() throws Exception {
        java.security.KeyStore keyStore = new java.security.KeyStore(new FailingCertificateSpi(), null, "test") { };
        keyStore.load(null, null);
        java.lang.reflect.Method read = AdminDeviceKeyStore.class
            .getDeclaredMethod("requireP256PublicKey", java.security.KeyStore.class, String.class);
        read.setAccessible(true);

        java.lang.reflect.InvocationTargetException failure = assertThrows(
            java.lang.reflect.InvocationTargetException.class, () -> read.invoke(null, keyStore, "alias"));

        assertTrue(failure.getCause() instanceof AdminDeviceKeyPreparationException);
        assertEquals(AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
            ((AdminDeviceKeyPreparationException) failure.getCause()).code());
        assertTrue(failure.getCause().getCause() instanceof java.security.ProviderException);
    }

    /** A key store whose certificate read fails the way keystore2 reports provider errors. */
    private static final class FailingCertificateSpi extends java.security.KeyStoreSpi {
        @Override public java.security.Key engineGetKey(String alias, char[] password) { return null; }
        @Override public java.security.cert.Certificate[] engineGetCertificateChain(String alias) { return null; }
        @Override public java.security.cert.Certificate engineGetCertificate(String alias) {
            throw new java.security.ProviderException("keystore2 getCertificate");
        }
        @Override public java.util.Date engineGetCreationDate(String alias) { return null; }
        @Override public void engineSetKeyEntry(String alias, java.security.Key key, char[] password,
            java.security.cert.Certificate[] chain) { }
        @Override public void engineSetKeyEntry(String alias, byte[] key, java.security.cert.Certificate[] chain) { }
        @Override public void engineSetCertificateEntry(String alias, java.security.cert.Certificate cert) { }
        @Override public void engineDeleteEntry(String alias) { }
        @Override public java.util.Enumeration<String> engineAliases() { return java.util.Collections.emptyEnumeration(); }
        @Override public boolean engineContainsAlias(String alias) { return false; }
        @Override public int engineSize() { return 0; }
        @Override public boolean engineIsKeyEntry(String alias) { return false; }
        @Override public boolean engineIsCertificateEntry(String alias) { return false; }
        @Override public String engineGetCertificateAlias(java.security.cert.Certificate cert) { return null; }
        @Override public void engineStore(java.io.OutputStream stream, char[] password) { }
        @Override public void engineLoad(java.io.InputStream stream, char[] password) { }
    }

    @Test
    public void everyCodeHasOneSafeNextActionWithTheCodeAndNoRawMessage() {
        for (AdminDeviceKeyPreparationException.Code code : AdminDeviceKeyPreparationException.Code.values()) {
            String text = AdminDeviceKeyPreparationException.guidance(code);
            assertTrue(code.name(), text.endsWith("(오류 코드: " + code.name() + ")"));
            assertTrue(code.name(), text.contains("관리자 기능은 잠금 상태로 유지합니다."));
            assertFalse(code.name(), text.contains("administrator"));
            assertFalse(code.name(), text.contains("AndroidKeyStore could"));
        }
        for (AdminDeviceKeyPreparationException.Code code : new AdminDeviceKeyPreparationException.Code[] {
            AdminDeviceKeyPreparationException.Code.METADATA_INVALID,
            AdminDeviceKeyPreparationException.Code.VERSION_UNSUPPORTED,
            AdminDeviceKeyPreparationException.Code.MARKER_MISMATCH,
            AdminDeviceKeyPreparationException.Code.METADATA_WRITE_FAILED,
            AdminDeviceKeyPreparationException.Code.KEYSTORE_UNAVAILABLE,
            AdminDeviceKeyPreparationException.Code.REGISTERED_KEY_MISSING,
            AdminDeviceKeyPreparationException.Code.UNKNOWN
        }) {
            String text = AdminDeviceKeyPreparationException.guidance(code);
            // The original risk: a wrong hint makes the operator delete a valid key.
            assertTrue(code.name(), text.contains("지우지 말") || text.contains("지우지 마세요"));
        }
        assertEquals(AdminDeviceKeyPreparationException.guidance(AdminDeviceKeyPreparationException.Code.UNKNOWN),
            AdminDeviceKeyPreparationException.guidance(null));
        assertThrows(IllegalArgumentException.class, () -> new AdminDeviceKeyPreparationException(
            AdminDeviceKeyPreparationException.Code.UNKNOWN, "x"));
    }

    @Test
    public void activityShowsTheCodeAndGuidanceAndNeverTheExceptionMessage() throws Exception {
        String activity = new String(Files.readAllBytes(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java")),
            StandardCharsets.UTF_8);
        int start = activity.indexOf("private void initializeDeviceKeyInBackground(");
        String prepare = activity.substring(start, activity.indexOf("private void completeSecurityInitialization(", start));
        assertTrue(prepare.contains("AdminDeviceKeyPreparationException.classify(error)"));
        assertTrue(prepare.contains("deviceKeyFailure = \"기기 키 초기화 실패(\" + failureCode.name() + \")\""));
        assertTrue(prepare.contains(": AdminDeviceKeyPreparationException.guidance(failureCode)"));
        assertTrue(prepare.contains("? DEVICE_KEY_LOCK_SCREEN_MESSAGE"));
        assertFalse(prepare.contains("getMessage()"));
        assertFalse(prepare.contains("\"기기 키를 준비하지 못했습니다. 관리자 기능을 잠금 상태로 유지합니다.\""));
        assertTrue(prepare.contains("Code.REGISTERED_KEY_MISSING.name()"));
    }

    private static void assertCode(AdminDeviceKeyPreparationException.Code expected, AdminDeviceKeyRotation.State state) {
        assertCode(expected, new Keys(), state);
    }

    private static void assertCode(
        AdminDeviceKeyPreparationException.Code expected,
        Keys keys,
        AdminDeviceKeyRotation.State state
    ) {
        assertEquals(expected, classifyEnsure(keys, new Metadata(state)));
    }

    private static AdminDeviceKeyPreparationException.Code classifyEnsure(Keys keys, Metadata metadata) {
        GeneralSecurityException error = assertThrows(GeneralSecurityException.class,
            () -> new AdminDeviceKeyRotation(keys, metadata).ensureActive());
        return AdminDeviceKeyPreparationException.classify(error);
    }

    /** The Activity catches every Exception from key preparation (a provider error is unchecked). */
    private static AdminDeviceKeyPreparationException.Code classifyEnsureAny(Keys keys, Metadata metadata) {
        return classifyEnsureAny(() -> new AdminDeviceKeyRotation(keys, metadata).ensureActive());
    }

    private static AdminDeviceKeyPreparationException.Code classifyEnsureAny(
        org.junit.function.ThrowingRunnable ensure
    ) {
        Exception error = assertThrows(Exception.class, ensure);
        return AdminDeviceKeyPreparationException.classify(error);
    }

    private static final class Keys implements AdminDeviceKeyRotation.KeyBackend {
        final Map<Integer, byte[]> spki = new HashMap<>();
        String level = AdminDeviceKeySecurityLevel.TRUSTED_ENVIRONMENT;
        GeneralSecurityException generateFailure;
        GeneralSecurityException containsFailure;
        RuntimeException spkiRuntime;
        GeneralSecurityException levelFailure;
        /** Scripted answers used before the real map (and before containsFailure). */
        final java.util.Deque<Boolean> containsAnswers = new java.util.ArrayDeque<>();
        int containsCalls;

        @Override public boolean contains(int version) throws GeneralSecurityException {
            containsCalls += 1;
            Boolean scripted = containsAnswers.pollFirst();
            if (scripted != null) return scripted;
            if (containsFailure != null) throw containsFailure;
            return spki.containsKey(version);
        }

        @Override public void generate(int version) throws GeneralSecurityException {
            if (generateFailure != null) throw generateFailure;
            spki.put(version, ("spki-" + version).getBytes(StandardCharsets.UTF_8));
        }

        @Override public byte[] publicKeySpki(int version) throws GeneralSecurityException {
            if (spkiRuntime != null) throw spkiRuntime;
            byte[] value = spki.get(version);
            if (value == null) throw new GeneralSecurityException("missing");
            return value.clone();
        }

        @Override public String securityLevel(int version) throws GeneralSecurityException {
            if (levelFailure != null) throw levelFailure;
            return level;
        }

        @Override public byte[] sign(int version, byte[] payload) { return new byte[0]; }

        @Override public void delete(int version) { spki.remove(version); }
    }

    private static final class Metadata implements AdminDeviceKeyRotation.MetadataStore {
        final AdminDeviceKeyRotation.State state;
        boolean commitResult = true;

        Metadata(AdminDeviceKeyRotation.State state) {
            this.state = state;
        }

        @Override public AdminDeviceKeyRotation.State load() { return state; }

        @Override public boolean commit(AdminDeviceKeyRotation.State next) { return commitResult; }
    }
}
