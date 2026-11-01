package kr.co.hanium.dreamup.walksafe.ui

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.ObstacleLabels

/** Display-only copies. Never retains camera images, depth buffers, models or session owners. */
internal object GuidanceRecognitionDisplay {
    const val REFRESH_MS = 250L
    const val MAX_AGE_MS = DepthAgeBudget.PROXIMITY_DISPLAY_MAX_AGE_MS
    const val MAX_BOXES = 5

    data class Item(
        val name: String,
        val score: Float,
        val rect: RectNorm?, // Already mapped screen pixels; null for independent unknown regions.
        val capturedAtMs: Long,
        val validUntilMs: Long,
        val distanceM: Float? = null,
        val distanceUntilMs: Long? = null,
        val distanceKind: String = "판단 거리",
    ) {
        fun current(nowMs: Long) = nowMs >= capturedAtMs && nowMs <= validUntilMs
        fun label(nowMs: Long): String {
            val distance = distanceM?.takeIf { current(nowMs) && distanceUntilMs?.let { nowMs <= it } == true }
            val confidence = score.takeIf { it.isFinite() && it in 0f..1f }
                ?.let { String.format(Locale.KOREA, " · 분류 %.0f%%", it * 100) }.orEmpty()
            return name + confidence + (distance?.let { String.format(Locale.KOREA, " · %s %.2fm", distanceKind, it) }
                ?: " · 거리 미확인")
        }
    }

    data class Frame(val capturedAtMs: Long, val count: Int?, val items: List<Item>, val note: String) {
        fun current(nowMs: Long) = nowMs - capturedAtMs in 0L..MAX_AGE_MS
        fun visibleItems(nowMs: Long) = if (current(nowMs)) items.filter { it.current(nowMs) } else emptyList()
    }

    fun item(className: String, score: Float, rect: RectNorm?, capturedAtMs: Long,
        output: TrackedObjectDepth? = null, unknown: Boolean = false, validUntilMs: Long = capturedAtMs + MAX_AGE_MS,
    ): Item {
        val remaining = output?.let { ProximityDepthDisplay.remainingMs(it, capturedAtMs, capturedAtMs) }
        val trusted = output?.takeIf { it.confidence.hardGate > 0f && it.confidence.freshnessQuality > 0f }
        return Item(if (unknown) "미학습 경고 후보" else ObstacleLabels.labelFor(className),
            if (unknown) Float.NaN else score, rect, capturedAtMs, validUntilMs,
            trusted?.riskDistanceM?.takeIf { remaining != null }, remaining?.let { minOf(validUntilMs, capturedAtMs + it) },
            if (output?.source == DepthSource.ARCORE_FULL_DEPTH) "판단 거리(Full 추정)" else "판단 거리(Raw)")
    }

    fun status(frame: Frame?, nowMs: Long, cameraFresh: Boolean, running: Boolean): String {
        if (!running) return "인식 현황 · 실행 중인 보행 인식 없음\n화면을 열어도 카메라나 모델을 새로 시작하지 않습니다."
        val camera = if (cameraFresh) "카메라 영상 수신 중" else "카메라 영상 갱신 대기"
        if (!cameraFresh) return "$camera\n객체·거리 갱신 대기 · 이전 결과 숨김"
        if (frame == null) return "$camera\n객체 인식 결과 대기"
        if (!frame.current(nowMs)) return "$camera\n인식 결과 갱신 대기 · 이전 결과 숨김"
        val count = frame.count?.let { "객체 영역 ${it}개" } ?: "객체 인식 결과 대기"
        val items = frame.visibleItems(nowMs)
        return "$camera · $count\n${frame.note}" + items.mapIndexed { index, item ->
            "\n${index + 1}. ${item.label(nowMs)}"
        }.joinToString("")
    }
}
