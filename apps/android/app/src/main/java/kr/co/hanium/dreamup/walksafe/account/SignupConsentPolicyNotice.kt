package kr.co.hanium.dreamup.walksafe.account

/**
 * S01-3 / FP-013-DETAIL-02: the server refuses a signup whose required documents changed with
 * 409 `signup_consent_outdated`. This build can only show the documents bundled with it
 * ([SIGNUP_DOCUMENT_VERSIONS]), so the only safe next step is to update the app and read the
 * new documents; sending newer version strings without showing them is not allowed.
 * A changed optional document never blocks signup: the server records that grant as not given
 * and integrated-consent bootstrap asks for it again (RECONSENT_REQUIRED).
 */
internal object SignupConsentPolicyNotice {
    const val OUTDATED_CODE = "signup_consent_outdated"
    const val OUTDATED_MESSAGE =
        "가입 약관이 바뀌어 이 앱 버전으로는 가입할 수 없습니다. " +
            "앱을 최신 버전으로 업데이트한 뒤 바뀐 약관을 다시 확인하고 가입하세요."

    fun messageOrNull(statusCode: Int, serverCode: String?): String? =
        OUTDATED_MESSAGE.takeIf { statusCode == 409 && serverCode == OUTDATED_CODE }
}
