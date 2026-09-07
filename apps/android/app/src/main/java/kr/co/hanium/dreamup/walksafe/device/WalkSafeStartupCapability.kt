package kr.co.hanium.dreamup.walksafe.device

const val WALKSAFE_DISPLAY_NAME = "WalkSafe(워크세이프)"
const val WALKSAFE_PRODUCT_NOTICE_BASELINE = "FP-001/FP-009 1.0.1"
const val WALKSAFE_DEVICE_PROFILE_POLICY_STATUS = "PENDING_DESIGNATED_DEVICE_VERIFICATION"
val WALKSAFE_APPROVED_DEVICE_PROFILE_VERSION: String? = null
const val WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO =
    "워크세이프는 시각장애인의 도심 보행 중 가까운 위험과 이동 방향을 알려 주고 손상 점자블록 신고를 돕는 안드로이드 보행 보조 서비스입니다."
const val WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO =
    "워크세이프는 보행 안전을 보장하지 않으며 흰지팡이·안내견·보호자를 대신하지 않습니다."
const val WALKSAFE_LIMITED_DISTANCE_NOTICE_KO =
    "사용할 수 없는 기능: 미터 단위 거리 측정. " +
        "이 휴대폰은 거리를 잴 수 없어 물체 종류만 알려드립니다. 거리와 안전 여부는 판단하지 않습니다"

val WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO = """
    $WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO
    ${WALKSAFE_DISPLAY_NAME}는 다음 세 가지를 돕습니다.
    1. 카메라로 가까운 위험을 안내합니다.
    2. TMAP으로 큰 이동 방향을 안내합니다.
    3. 손상된 점자블록 신고를 돕습니다.
    기기점검은 카메라·위치·마이크·음성 지원 여부를 확인하며 실제 보행이나 신고를 시작하지 않습니다.
    호출어 음성은 휴대폰 안에서 처리하고 원본을 저장하거나 서버로 보내지 않습니다.
    안전 제한: $WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO
    고지 기준: $WALKSAFE_PRODUCT_NOTICE_BASELINE
""".trimIndent()

enum class WalkSafeStartupCapabilityTier {
    FULL,
    LIMITED,
    BLOCKED,
}

enum class WalkSafeStartupRequirement(val labelKo: String) {
    ANDROID_VERSION("지원 Android 버전"),
    CAMERA("카메라"),
    GPS("GPS 위치"),
    MICROPHONE("마이크"),
    VIBRATION("진동"),
    ON_DEVICE_STT("휴대폰 내부 음성 인식"),
    OFFLINE_KOREAN_TTS("오프라인 한국어 음성 안내"),
    METRIC_DISTANCE("미터 단위 거리 측정"),
    APPROVED_DEVICE_PROFILE("승인된 지정 기기 프로필"),
}

val USER_INSTALLABLE_REQUIREMENTS: Set<WalkSafeStartupRequirement> = setOf(
    WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS,
)

data class WalkSafeStartupCapabilityInput(
    val androidVersionSupported: Boolean?,
    val cameraAvailable: Boolean?,
    val gpsAvailable: Boolean?,
    val microphoneAvailable: Boolean?,
    val vibrationAvailable: Boolean?,
    val onDeviceSpeechRecognitionAvailable: Boolean?,
    val offlineKoreanTextToSpeechAvailable: Boolean?,
    val metricDistanceAvailable: Boolean?,
    val approvedDesignatedDeviceProfile: Boolean?,
    val designatedDeviceProfileVersion: String?,
)

data class WalkSafeStartupCapabilityDecision(
    val tier: WalkSafeStartupCapabilityTier,
    val unavailableRequirements: List<WalkSafeStartupRequirement>,
    val pendingRequirements: List<WalkSafeStartupRequirement>,
    val noticeKo: String,
) {
    val mayConfirmAndStart: Boolean
        get() = tier != WalkSafeStartupCapabilityTier.BLOCKED
}

object WalkSafeStartupCapabilityResolver {
    fun resolve(
        input: WalkSafeStartupCapabilityInput,
        approvedDeviceProfileRequired: Boolean = true,
    ): WalkSafeStartupCapabilityDecision {
        val approvedProfileStatus = when (input.approvedDesignatedDeviceProfile) {
            null -> null
            false -> false
            true -> !input.designatedDeviceProfileVersion.isNullOrBlank()
        }
        val statuses = linkedMapOf(
            WalkSafeStartupRequirement.ANDROID_VERSION to input.androidVersionSupported,
            WalkSafeStartupRequirement.CAMERA to input.cameraAvailable,
            Pair(
                WalkSafeStartupRequirement.GPS,
                input.gpsAvailable,
            ),
            WalkSafeStartupRequirement.MICROPHONE to input.microphoneAvailable,
            WalkSafeStartupRequirement.ON_DEVICE_STT to input.onDeviceSpeechRecognitionAvailable,
            WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS to input.offlineKoreanTextToSpeechAvailable,
            WalkSafeStartupRequirement.METRIC_DISTANCE to input.metricDistanceAvailable,
        )
        if (approvedDeviceProfileRequired) {
            statuses[WalkSafeStartupRequirement.APPROVED_DEVICE_PROFILE] = approvedProfileStatus
        }
        val pending = statuses.filterValues { it == null }.keys.toList()
        val unavailable = statuses.filterValues { it == false }.keys.toList()

        if (WalkSafeStartupRequirement.ANDROID_VERSION in unavailable) {
            return WalkSafeStartupCapabilityDecision(
                tier = WalkSafeStartupCapabilityTier.BLOCKED,
                unavailableRequirements = unavailable,
                pendingRequirements = pending,
                noticeKo = "지원 Android 버전이 아니어서 WalkSafe를 시작할 수 없습니다. " +
                    "사용할 수 없는 기능: ${unavailable.labelsKo()}",
            )
        }
        if (WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS in unavailable) {
            val pendingNotice = if (pending.isEmpty()) {
                ""
            } else {
                " 확인 중인 기능: ${pending.labelsKo()}."
            }
            return WalkSafeStartupCapabilityDecision(
                tier = WalkSafeStartupCapabilityTier.BLOCKED,
                unavailableRequirements = unavailable,
                pendingRequirements = pending,
                noticeKo = "오프라인 한국어 음성 안내를 사용할 수 없어 WalkSafe를 시작할 수 없습니다. " +
                    "사용할 수 없는 기능: ${unavailable.labelsKo()}." +
                    pendingNotice,
            )
        }
        if (pending.isNotEmpty()) {
            val unavailableNotice = if (unavailable.isEmpty()) {
                ""
            } else {
                " 확인된 미지원 기능: ${unavailable.labelsKo()}"
            }
            return WalkSafeStartupCapabilityDecision(
                tier = WalkSafeStartupCapabilityTier.BLOCKED,
                unavailableRequirements = unavailable,
                pendingRequirements = pending,
                noticeKo = "기기 기능을 확인하는 중입니다: ${pending.labelsKo()}." +
                    unavailableNotice,
            )
        }
        if (unavailable.isNotEmpty()) {
            val noticeKo = if (
                unavailable == listOf(WalkSafeStartupRequirement.METRIC_DISTANCE)
            ) {
                WALKSAFE_LIMITED_DISTANCE_NOTICE_KO
            } else {
                "일부 기기 기능을 사용할 수 없습니다. " +
                    "사용할 수 없는 기능: ${unavailable.labelsKo()}. " +
                    "해당 기능과 관련된 기능만 제한됩니다."
            }
            return WalkSafeStartupCapabilityDecision(
                tier = WalkSafeStartupCapabilityTier.LIMITED,
                unavailableRequirements = unavailable,
                pendingRequirements = emptyList(),
                noticeKo = noticeKo,
            )
        }
        return WalkSafeStartupCapabilityDecision(
            tier = WalkSafeStartupCapabilityTier.FULL,
            unavailableRequirements = emptyList(),
            pendingRequirements = emptyList(),
            noticeKo = "필수 기기 기능 확인을 통과했습니다. 이 결과는 기능 시작 가능 여부만 뜻하며 보행 안전을 보장하지 않습니다.",
        )
    }
}

private fun Collection<WalkSafeStartupRequirement>.labelsKo(): String =
    joinToString(", ") { it.labelKo }
