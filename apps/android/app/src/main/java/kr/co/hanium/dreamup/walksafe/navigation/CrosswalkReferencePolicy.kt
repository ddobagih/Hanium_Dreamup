package kr.co.hanium.dreamup.walksafe.navigation

const val CROSSWALK_REFERENCE_NOTICE_KO =
    "횡단보도 참고 안내입니다. 주변을 확인하세요. 횡단 판단은 대신하지 않습니다."

object CrosswalkReferencePolicy {
    private val tmapCrosswalkTurnTypes = setOf(211, 212, 213, 214, 215, 216, 217)
    private val crosswalkTextMarkers = listOf("횡단보도", "crosswalk", "zebra")
    private val detectorClassNames = setOf("crosswalk", "zebra")

    fun noticeFor(guide: WalkingRouteGuidePoint): String? {
        return if (isCrosswalkGuide(guide)) CROSSWALK_REFERENCE_NOTICE_KO else null
    }

    fun isCrosswalkDetectorClass(className: String): Boolean {
        return className.trim().lowercase() in detectorClassNames
    }

    internal fun isCrosswalkGuide(guide: WalkingRouteGuidePoint): Boolean {
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
