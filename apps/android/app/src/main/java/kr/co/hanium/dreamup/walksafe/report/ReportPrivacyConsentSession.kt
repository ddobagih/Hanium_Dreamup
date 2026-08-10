package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.device.WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO
import kr.co.hanium.dreamup.walksafe.device.WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkCalls
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall

const val REPORT_PRIVACY_DISCLOSURE_KO =
    "$WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO $WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO " +
        "손상 점자블록 신고 데이터 전송·보관(기본 꺼짐): 자동 또는 직접 손상 점자블록 신고 시 " +
        "긴 변 최대 960px 이내(현재 Android 앱은 320px) JPEG를 메타데이터 제거를 위해 " +
        "재인코딩하며 모자이크하지 않습니다. 정확한 GPS 좌표와 GPS가 제공한 이동 heading을 함께 " +
        "WalkSafe 서버에 전송해 180일간 보관합니다. 카메라 권한 및 기기 내 위험 탐지·안내와 " +
        "별도이며, 철회해도 기기 내 탐지·안내는 계속됩니다. 동의는 사용자가 철회할 때까지 " +
        "이 기기에 유지되며, 자동신고 사용 여부는 별도 설정으로 관리합니다."

enum class ReportTransferPurpose(val wireValue: String) {
    EXPLICIT("explicit"),
    AUTOMATIC("automatic"),
}

internal sealed interface ReportUploadPermit

private class CanonicalReportUploadPermit private constructor(
    val generation: Long,
    val purpose: ReportTransferPurpose,
) : ReportUploadPermit {
    companion object {
        fun issue(
            generation: Long,
            purpose: ReportTransferPurpose,
        ): ReportUploadPermit =
            CanonicalReportUploadPermit(generation, purpose)
    }
}

/** Consent and per-purpose cancellation boundary for privacy-sensitive report uploads. */
internal object ReportPrivacyConsentSession {
    private val lock = Any()
    private val explicitCalls = ActiveNetworkCalls()
    private val automaticCalls = ActiveNetworkCalls()
    private var granted = false
    private var accountDeletionBlocked = false
    private var permitGeneration = 0L

    internal fun grantFromServerConfirmedIntegratedConsent(): Boolean {
        return synchronized(lock) {
            if (accountDeletionBlocked) return@synchronized false
            val changed = !granted
            granted = true
            permitGeneration += 1L
            explicitCalls.cancelAll()
            automaticCalls.cancelAll()
            changed
        }
    }

    internal fun withdraw(): Boolean {
        return synchronized(lock) {
            val changed = granted
            granted = false
            permitGeneration += 1L
            explicitCalls.cancelAll()
            automaticCalls.cancelAll()
            changed
        }
    }

    internal fun isGranted(): Boolean =
        synchronized(lock) { granted && !accountDeletionBlocked }

    internal fun blockForAccountDeletion(): Boolean {
        return synchronized(lock) {
            val changed = !accountDeletionBlocked || granted
            accountDeletionBlocked = true
            granted = false
            permitGeneration += 1L
            explicitCalls.cancelAll()
            automaticCalls.cancelAll()
            changed
        }
    }

    internal fun resetForNewEnrollment(): Boolean {
        return synchronized(lock) {
            val changed = accountDeletionBlocked || granted
            accountDeletionBlocked = false
            granted = false
            permitGeneration += 1L
            explicitCalls.cancelAll()
            automaticCalls.cancelAll()
            changed
        }
    }

    internal fun isAccountDeletionBlocked(): Boolean =
        synchronized(lock) { accountDeletionBlocked }

    internal fun issueUploadPermit(
        purpose: ReportTransferPurpose,
    ): ReportUploadPermit? = synchronized(lock) {
        if (!granted || accountDeletionBlocked) return@synchronized null
        CanonicalReportUploadPermit.issue(permitGeneration, purpose)
    }

    internal fun <T> withLiveUploadPermit(
        permit: ReportUploadPermit,
        expectedPurpose: ReportTransferPurpose,
        opener: () -> T,
    ): T = synchronized(lock) {
        check(isPermitLiveLocked(permit, expectedPurpose)) {
            "report upload permit is stale or revoked"
        }
        opener()
    }

    internal fun <T> trackIfLive(
        permit: ReportUploadPermit,
        call: CancellableNetworkCall<T>,
        purpose: ReportTransferPurpose,
    ): CancellableNetworkCall<T>? = synchronized(lock) {
        if (!isPermitLiveLocked(permit, purpose)) {
            call.cancel()
            null
        } else {
            callsFor(purpose).track(call)
        }
    }

    internal fun <T> trackIfGranted(
        call: CancellableNetworkCall<T>,
        purpose: ReportTransferPurpose = ReportTransferPurpose.EXPLICIT,
    ): CancellableNetworkCall<T>? {
        return synchronized(lock) {
            if (!granted || accountDeletionBlocked) {
                call.cancel()
                null
            } else {
                callsFor(purpose).track(call)
            }
        }
    }

    internal fun complete(call: CancellableNetworkCall<*>) {
        explicitCalls.complete(call)
        automaticCalls.complete(call)
    }

    internal fun cancelActiveCalls(purpose: ReportTransferPurpose? = null) {
        synchronized(lock) {
            if (purpose == null || purpose == ReportTransferPurpose.EXPLICIT) {
                explicitCalls.cancelAll()
            }
            if (purpose == null || purpose == ReportTransferPurpose.AUTOMATIC) {
                automaticCalls.cancelAll()
            }
        }
    }

    private fun callsFor(purpose: ReportTransferPurpose): ActiveNetworkCalls {
        return when (purpose) {
            ReportTransferPurpose.EXPLICIT -> explicitCalls
            ReportTransferPurpose.AUTOMATIC -> automaticCalls
        }
    }

    private fun isPermitLiveLocked(
        permit: ReportUploadPermit,
        expectedPurpose: ReportTransferPurpose,
    ): Boolean {
        val issued = permit as? CanonicalReportUploadPermit ?: return false
        return granted &&
            !accountDeletionBlocked &&
            issued.generation == permitGeneration &&
            issued.purpose == expectedPurpose
    }
}
