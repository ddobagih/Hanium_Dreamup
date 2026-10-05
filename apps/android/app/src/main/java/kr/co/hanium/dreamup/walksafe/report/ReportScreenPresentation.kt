package kr.co.hanium.dreamup.walksafe.report

internal data class ReportScreenPresentation(val message: String, val canCheckTarget: Boolean)

/** Local queue acceptance is not server or agency acceptance. */
internal fun reportScreenPresentation(queueEnabled: Boolean, walkActive: Boolean, status: String): ReportScreenPresentation {
    val message = when {
        !queueEnabled -> "신고 저장을 사용할 수 없습니다.\n현재 앱 버전에서는 신고 저장·전송 기능이 꺼져 있습니다. 신고 기능이 활성화된 앱 버전이 필요합니다."
        status.startsWith("reportCandidate=queued ") -> "기기에 저장됨 · 전송 대기\n확인한 신고를 기기에 저장했습니다. 서버나 기관에 접수된 상태는 아닙니다."
        !walkActive -> "보행을 시작한 뒤 신고할 수 있습니다.\n길라잡이에서 위치·카메라 확인을 마친 뒤 손상 점자블록을 확인해 주세요."
        status.contains("gps_missing") -> "현재 위치를 확인할 수 없습니다.\n정확한 위치 권한과 위치 서비스를 확인한 뒤 다시 시도해 주세요."
        status.contains("permission_unavailable") -> "카메라와 정확한 위치 권한이 필요합니다.\n권한 설정에서 허용한 뒤 다시 시도해 주세요."
        status.contains("consent_confirmation_required") || status.contains("gateway_authority_unavailable") -> "로그인과 신고 처리 동의를 확인해야 합니다.\n설정에서 현재 계정과 동의 상태를 확인해 주세요."
        status.contains("no_depth_object") || status.contains("not_reportable_class") -> "신고할 손상 점자블록이 없습니다.\n현재 카메라에서 확인한 손상 점자블록만 신고할 수 있습니다."
        status.contains("runtime_metric_unavailable") -> "카메라·거리 정보를 확인할 수 없습니다.\n기기 점검과 휴대전화 장착 상태를 확인한 뒤 다시 시도해 주세요."
        status.contains("confirmation_") || status.contains("queue_disabled_or_rejected") -> "신고를 저장하지 못했습니다.\n확인 시간이 지났거나 기기·연결 상태가 바뀌었습니다. 다시 확인해 주세요."
        status.contains("blocked:") -> "현재 신고 조건을 충족하지 못했습니다.\n위치·카메라·장착 상태를 확인한 뒤 다시 시도해 주세요."
        else -> "손상 점자블록 신고\n현재 감지된 대상을 확인한 뒤 사진과 위치 등 전송 내용을 안내합니다. 별도로 확인하기 전에는 신고를 저장하지 않습니다."
    }
    return ReportScreenPresentation(message, queueEnabled && walkActive && !status.startsWith("reportCandidate=queued "))
}
