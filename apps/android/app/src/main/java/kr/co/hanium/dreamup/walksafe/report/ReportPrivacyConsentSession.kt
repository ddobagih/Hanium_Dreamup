package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.device.WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO
import kr.co.hanium.dreamup.walksafe.device.WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkCalls
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall

const val REPORT_PRIVACY_DISCLOSURE_KO =
    "$WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO $WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO " +
        "직접 손상 점자블록 신고는 신고할 때마다 전송 내용을 확인해야 합니다. 신고 사진은 긴 변 " +
        "최대 960px 이내(현재 Android 앱은 320px) JPEG로 재인코딩해 메타데이터를 제거하지만 " +
        "모자이크하지 않습니다. 정확한 GPS 좌표, GPS가 제공한 이동 heading, 탐지·시각·모델 " +
        "정보를 신고 처리와 중복 확인을 위해 사용합니다. 운영 수신자와 보유기간 문안이 승인되기 " +
        "전에는 release 빌드의 신고 대기열을 열지 않습니다. 대기열을 명시적으로 켠 개발 빌드는 " +
        "구성된 WalkSafe 테스트 서버에만 전송하며 기관으로 자동 전송하지 않습니다. " +
        "선택 원본·진단수집 raw v2는 영상·이미지·정확한 위치를 수집하지 않는 별도 경로이며, " +
        "거부하거나 철회해도 건별 확인을 거친 직접 신고는 사용할 수 있습니다. 자동신고는 별도 " +
        "선택 동의가 있어야 합니다. 카메라 권한과 기기 내 위험 탐지·안내도 각각 별도입니다."

const val REPORT_EXPLICIT_CONFIRMATION_DISCLOSURE_KO =
    "이번 손상 점자블록 직접 신고를 위해 방금 고정한 장면의 JPEG 사진을 메타데이터 제거 목적으로 " +
        "재인코딩하며 모자이크하지 않습니다. 정확한 GPS 좌표, GPS 이동 방향, 탐지·시각·모델 " +
        "정보와 함께 기기에 암호화해 대기한 뒤, 보행을 멈추고 재확인한 상태에서 WalkSafe " +
        "테스트 서버로 전송합니다. 운영 문안 승인 전의 개발 시험용이며 기관으로 자동 전송하지 " +
        "않습니다. 선택 원본·진단수집 raw v2나 학습 재사용에는 이 확인을 사용하지 않습니다."

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

/**
 * Current-confirmation authorization and per-purpose cancellation boundary for report uploads.
 * This process-local state is not the optional raw/diagnostic collection consent.
 */
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
