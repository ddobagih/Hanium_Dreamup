package kr.co.hanium.dreamup.walksafe.navigation

const val CROSSWALK_REFERENCE_NOTICE_KO =
    "횡단보도 정보는 참고용입니다. 신호와 차량 등 주변 안전을 직접 확인하세요. 이 안내는 건너도 안전하다는 판단을 대신하지 않습니다."

object CrosswalkReferencePolicy {
    private val tmapCrosswalkTurnTypes = setOf(211, 212, 213)
    private val crosswalkTextMarkers = listOf("횡단보도", "crosswalk", "zebra")
    private val detectorClassNames = setOf("crosswalk", "zebra")

    fun noticeFor(guide: WalkingRouteGuidePoint): String? {
        return if (isCrosswalkGuide(guide)) CROSSWALK_REFERENCE_NOTICE_KO else null
    }

    fun isCrosswalkDetectorClass(className: String): Boolean {
        return className.trim().lowercase() in detectorClassNames
    }

    private fun isCrosswalkGuide(guide: WalkingRouteGuidePoint): Boolean {
        return guide.turnType in tmapCrosswalkTurnTypes ||
            containsCrosswalkMarker(guide.instruction) ||
            containsCrosswalkMarker(guide.pointType) ||
            containsCrosswalkMarker(guide.point.name)
    }

    private fun containsCrosswalkMarker(value: String?): Boolean {
        val normalized = value?.lowercase() ?: return false
        return crosswalkTextMarkers.any(normalized::contains)
    }
}
