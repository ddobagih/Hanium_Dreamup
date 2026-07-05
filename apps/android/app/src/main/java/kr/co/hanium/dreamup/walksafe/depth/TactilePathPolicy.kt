package kr.co.hanium.dreamup.walksafe.depth

class TactilePathPolicy {
    fun buildGuidance(geometry: ObjectGeometry, stepLengthM: Float = 0.65f): UserFacingDepth {
        if (!isTactileBlockClass(geometry.className)) {
            return UserFacingDepth(stepsAhead = null, messageLevel = MessageLevel.NONE, message = null)
        }

        val message = when {
            geometry.centerlineNorm.size >= 2 && geometry.centerlineNorm.last().x > geometry.centerlineNorm.first().x + 0.08f ->
                "전방 점자블럭이 오른쪽으로 이어집니다."
            geometry.centerlineNorm.size >= 2 && geometry.centerlineNorm.last().x < geometry.centerlineNorm.first().x - 0.08f ->
                "전방 점자블럭이 왼쪽으로 이어집니다."
            geometry.centerNorm.x < 0.40f ->
                "점자블럭이 왼쪽 약 1보 옆에 있습니다."
            geometry.centerNorm.x > 0.60f ->
                "점자블럭이 오른쪽 약 1보 옆에 있습니다."
            else ->
                "전방 점자블럭을 따라가세요."
        }

        return UserFacingDepth(
            stepsAhead = null,
            messageLevel = MessageLevel.INFO,
            message = message,
        )
    }
}
