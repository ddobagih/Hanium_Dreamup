package kr.co.hanium.dreamup.walksafe.admin.security;

import java.security.GeneralSecurityException;

/**
 * S18-23 / S18-21: a device key preparation failure with a secret-free code, so the screen
 * can say which step failed (metadata, key entry, platform key store) instead of one "key
 * unusable" text. The guidance never tells the operator to delete or re-register a key that
 * may still be valid; it names the code to hand to the operator.
 */
public final class AdminDeviceKeyPreparationException extends GeneralSecurityException {
    public enum Code {
        /** Stored public metadata (versions, markers) is unreadable, incomplete or inconsistent. */
        METADATA_INVALID,
        /** Stored key version is outside what this app build understands. */
        VERSION_UNSUPPORTED,
        /** The key entry's public key no longer matches the stored marker. */
        MARKER_MISMATCH,
        /** Metadata could not be written (storage). */
        METADATA_WRITE_FAILED,
        /** AndroidKeyStore could not be loaded, queried or asked to create/read the key. */
        KEYSTORE_UNAVAILABLE,
        /** The platform created only a software key, which the admin policy refuses. */
        HARDWARE_KEY_UNSUPPORTED,
        /** The registered key entry is gone from this device (replacement key path). */
        REGISTERED_KEY_MISSING,
        /** No secure lock screen, so a user-authentication-bound key cannot exist. */
        LOCK_SCREEN_REQUIRED,
        /** Anything else; still shown only as this code. */
        UNKNOWN
    }

    private final Code code;

    public AdminDeviceKeyPreparationException(Code code, String message) {
        this(code, message, null);
    }

    public AdminDeviceKeyPreparationException(Code code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || code == Code.REGISTERED_KEY_MISSING || code == Code.LOCK_SCREEN_REQUIRED
            || code == Code.UNKNOWN) {
            // Those three are derived from their own exception types by classify().
            throw new IllegalArgumentException("device key preparation code is invalid");
        }
        this.code = code;
    }

    public Code code() {
        return code;
    }

    /** Maps a key preparation failure (and its causes) to a code without reading messages. */
    public static Code classify(Throwable error) {
        if (AdminDeviceKeyUseAuthorization.requiresLockScreen(error)) return Code.LOCK_SCREEN_REQUIRED;
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof AdminDeviceKeyRotation.ActiveKeyUnavailableException) {
                return Code.REGISTERED_KEY_MISSING;
            }
            if (cursor instanceof AdminDeviceKeyPreparationException typed) return typed.code();
            if (cursor.getCause() == cursor) break;
        }
        // adm-F3: AndroidKeyStore alias lookup, load, key and certificate reads rethrow raw
        // platform/provider exceptions; they are key store failures, not "unknown". A key that
        // needs authentication or was invalidated is a key-use state, not a store outage.
        if (!AdminDeviceKeyUseAuthorization.requiresAuthentication(error)
            && !AdminDeviceKeyUseAuthorization.isInvalidated(error)) {
            for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
                if (isRawKeyStoreFailure(cursor)) return Code.KEYSTORE_UNAVAILABLE;
                if (cursor.getCause() == cursor) break;
            }
        }
        return Code.UNKNOWN;
    }

    private static boolean isRawKeyStoreFailure(Throwable error) {
        return error instanceof java.security.KeyStoreException
            || error instanceof java.security.UnrecoverableKeyException
            || error instanceof java.security.ProviderException
            || error instanceof java.security.cert.CertificateException
            || error instanceof java.security.NoSuchProviderException;
    }

    /** One next action per code; every text carries the code and no secret or raw message. */
    public static String guidance(Code code) {
        Code safe = code == null ? Code.UNKNOWN : code;
        String next = switch (safe) {
            case METADATA_INVALID, VERSION_UNSUPPORTED, MARKER_MISMATCH ->
                "이 기기의 기기키 정보가 서버에 등록된 키와 같은지 확인할 수 없습니다. 앱 데이터나 기기키를 "
                    + "지우지 말고 키를 새로 만들지도 마세요. 이 오류 코드를 운영자에게 전달해 등록 상태를 확인하세요.";
            case METADATA_WRITE_FAILED ->
                "기기키 정보를 저장하지 못했습니다. 기기 저장공간을 확인한 뒤 앱을 다시 여세요. "
                    + "기기키는 지우지 마세요.";
            case KEYSTORE_UNAVAILABLE ->
                "기기 보안 저장소(AndroidKeyStore)를 지금 사용할 수 없습니다. 기기키는 지우지 말고 "
                    + "잠시 뒤 또는 기기를 다시 시작한 뒤 앱을 다시 여세요.";
            case HARDWARE_KEY_UNSUPPORTED ->
                "이 기기는 하드웨어 보안 키를 제공하지 않아 관리자 기기로 쓸 수 없습니다. "
                    + "승인된 다른 기기를 사용하세요.";
            // adm-F4: "absent" can be a transient key store answer; restart first, replace last.
            case REGISTERED_KEY_MISSING ->
                "등록된 관리자 기기키를 이 기기에서 찾지 못했습니다. 앱 데이터나 기기키를 지우지 말고 "
                    + "먼저 기기를 다시 시작한 뒤 앱을 다시 여세요. 그 뒤에도 같을 때만 교체용 새 기기키를 "
                    + "준비해 운영자에게 등록을 요청하세요.";
            case LOCK_SCREEN_REQUIRED ->
                "화면 잠금(PIN·패턴·비밀번호)이 없어 관리자 기기키를 만들 수 없습니다. "
                    + "기기 설정에서 화면 잠금을 켠 뒤 앱을 다시 실행하세요.";
            case UNKNOWN ->
                "기기키를 준비하지 못했습니다. 기기키는 지우지 말고 앱을 다시 연 뒤에도 같으면 "
                    + "이 오류 코드를 운영자에게 전달하세요.";
        };
        return next + " 관리자 기능은 잠금 상태로 유지합니다. (오류 코드: " + safe.name() + ")";
    }
}
