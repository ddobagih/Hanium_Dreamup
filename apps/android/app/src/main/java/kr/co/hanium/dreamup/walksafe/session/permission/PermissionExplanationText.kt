package kr.co.hanium.dreamup.walksafe.session.permission

import android.Manifest
import kr.co.hanium.dreamup.walksafe.session.*

internal object PermissionExplanationText {
    fun messageKo(explanation: PermissionExplanation, walkState: WalkSessionState? = null): String = with(explanation) {
        if (unavailablePermissions.isEmpty()) {
            return "현재 필요한 권한 상태를 확인할 수 없습니다. 앱 설정에서 권한 상태를 확인해 주세요."
        }
        val details = buildList {
            when (locationState) {
                PermissionLocationState.APPROXIMATE_ONLY ->
                    add("대략적 위치는 허용됐지만 정확한 위치는 허용되지 않아 정확한 위치가 필요한 기능이 제한됩니다.")
                PermissionLocationState.NO_LOCATION ->
                    add("정확한 위치와 대략적 위치 모두 허용되지 않아 위치 기능이 제한됩니다.")
                PermissionLocationState.NOT_APPLICABLE -> Unit
            }
            unavailablePermissions.forEach { permission ->
                when (permission) {
                    PermissionExplanationKind.CAMERA -> add("카메라 권한이 필요합니다.")
                    PermissionExplanationKind.MICROPHONE -> add("마이크 권한이 필요합니다.")
                    PermissionExplanationKind.ACTIVITY_RECOGNITION -> add("보행 센서 권한이 필요합니다.")
                    PermissionExplanationKind.PRECISE_LOCATION,
                    PermissionExplanationKind.APPROXIMATE_LOCATION,
                    -> Unit
                }
            }
        }.joinToString(" ")
        val recovery = unavailablePermissions.joinToString(" ") { permission ->
            val name = when (permission) {
                PermissionExplanationKind.CAMERA -> "카메라"
                PermissionExplanationKind.PRECISE_LOCATION -> "정확한 위치"
                PermissionExplanationKind.APPROXIMATE_LOCATION -> "대략적 위치"
                PermissionExplanationKind.MICROPHONE -> "마이크"
                PermissionExplanationKind.ACTIVITY_RECOGNITION -> "보행 센서"
            }
            when (reRequestabilityByPermission[permission]) {
                PermissionReRequestability.NOT_EVALUATED, null ->
                    "$name 권한은 요청 결과가 확인되지 않아 거부 원인이나 재요청 가능 여부를 단정할 수 없습니다. 앱 설정에서 상태를 확인해 주세요."
                PermissionReRequestability.RATIONALE_RECOMMENDED ->
                    "$name 권한은 Android가 앱 내 재요청 전 이유 안내를 권장하는 상태입니다. 앱 설정에서도 허용할 수 있습니다."
                PermissionReRequestability.NO_RATIONALE_SIGNAL ->
                    "$name 권한은 앱 설정에서 허용할 수 있습니다. 앱 내 요청 화면이 다시 표시될지는 Android가 결정합니다."
            }
        }
        val prefix = if (requestResultReceived) "이번 권한 요청 결과, " else "현재 "
        val availability = if (walkState == WalkSessionState.SAFE_STOP) {
            "보행 안내가 중지되었습니다. 권한과 필수 기능을 다시 점검한 뒤 보행 시작을 직접 확인해 주세요."
        } else {
            "해당 기능만 제한되며 나머지 기능은 계속 사용할 수 있습니다."
        }
        return "$prefix$details $availability $recovery"
    }

    fun permissionLabelKo(permission: String): String = when (permission) {
        Manifest.permission.CAMERA -> "카메라"
        Manifest.permission.ACCESS_FINE_LOCATION -> "정확한 위치"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "대략적 위치"
        Manifest.permission.RECORD_AUDIO -> "마이크"
        Manifest.permission.ACTIVITY_RECOGNITION -> "보행 센서"
        Manifest.permission.POST_NOTIFICATIONS -> "알림"
        else -> permission
    }
}
