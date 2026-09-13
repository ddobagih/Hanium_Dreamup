package kr.co.hanium.dreamup.walksafe

import kotlin.math.sqrt

/**
 * Presentation-only interpolation between already mapped detector rectangles. It neither tracks
 * camera motion nor extrapolates an object's position, and must never supply depth/risk evidence.
 * All times are elapsed realtime; rendering and repeated source frames cannot renew source age.
 */
internal class DebugOverlayContinuity(
    private val transitionMs: Long = 80L,
    private val maxSourceAgeMs: Long = 1_200L,
) {
    init {
        require(transitionMs > 0L)
        require(maxSourceAgeMs > 0L)
    }

    private var frame: Frame? = null

    fun update(
        boxes: List<Box>,
        sourceFrameId: Long,
        sourceCapturedAtElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long,
        animate: Boolean = true,
        sourceComplete: Boolean = true,
    ) {
        val previous = frame
        if (previous != null && sourceFrameId < previous.sourceFrameId) return
        // Partial and complete results can share an identity, but never a different capture time.
        if (
            previous?.sourceFrameId == sourceFrameId &&
            previous.sourceCapturedAtMs != sourceCapturedAtElapsedRealtimeMs
        ) return
        if (previous?.sourceFrameId == sourceFrameId && previous.sourceComplete && !sourceComplete) return
        if (previous != null && nowElapsedRealtimeMs < previous.acceptedAtMs) {
            clear()
            return
        }

        val ageMs = nowElapsedRealtimeMs - sourceCapturedAtElapsedRealtimeMs
        val validBoxes = if (
            sourceCapturedAtElapsedRealtimeMs < 0L || ageMs < 0L || ageMs >= maxSourceAgeMs
        ) {
            emptyList()
        } else {
            boxes.filter { it.rect.isValid() }
        }
        if (
            previous?.sourceFrameId == sourceFrameId &&
            previous.transitions.map { it.target } == validBoxes
        ) {
            frame = previous.copy(sourceComplete = sourceComplete)
            return
        }

        val visiblePrevious = render(nowElapsedRealtimeMs).boxes.mapIndexed { index, rendered ->
            requireNotNull(previous).transitions[index] to rendered
        }.toMutableList()
        // Reserve all unchanged observations before matching additions: a newly inserted same-class
        // box must not consume the transition of an existing box that appears later in the list.
        val unchangedByIndex = mutableMapOf<Int, Transition>()
        if (previous?.sourceFrameId == sourceFrameId) {
            validBoxes.forEachIndexed { index, box ->
                val unchanged = visiblePrevious.firstOrNull { (transition, _) ->
                    transition.target.matchesRole(box) && transition.target.rect == box.rect
                }
                if (unchanged != null) {
                    visiblePrevious.remove(unchanged)
                    unchangedByIndex[index] = unchanged.first
                }
            }
        }
        val transitions = validBoxes.mapIndexed { index, box ->
            unchangedByIndex[index]?.let { return@mapIndexed it.copy(target = box) }
            val match = if (animate) {
                visiblePrevious
                    .filter { (_, rendered) ->
                        rendered.box.matchesRole(box) && rendered.box.rect.canInterpolateTo(box.rect)
                    }
                    .minByOrNull { (_, rendered) -> rendered.box.rect.centerDistance(box.rect) }
            } else {
                null
            }
            if (match != null) visiblePrevious.remove(match)
            Transition(
                from = match?.second?.box?.rect ?: box.rect,
                target = box,
                startedAtMs = nowElapsedRealtimeMs,
            )
        }
        frame = Frame(
            sourceFrameId = sourceFrameId,
            sourceCapturedAtMs = sourceCapturedAtElapsedRealtimeMs,
            acceptedAtMs = nowElapsedRealtimeMs,
            sourceComplete = sourceComplete,
            transitions = transitions,
        )
    }

    fun render(nowElapsedRealtimeMs: Long): RenderedFrame {
        val current = frame ?: return RenderedFrame.EMPTY
        val ageMs = nowElapsedRealtimeMs - current.sourceCapturedAtMs
        if (
            ageMs < 0L || ageMs >= maxSourceAgeMs ||
            nowElapsedRealtimeMs < current.acceptedAtMs
        ) return RenderedFrame.EMPTY

        val rendered = current.transitions.map { transition ->
            val progress = ((nowElapsedRealtimeMs - transition.startedAtMs).toFloat() / transitionMs)
                .coerceIn(0f, 1f)
            val interpolated = transition.from != transition.target.rect && progress < 1f
            RenderedBox(
                box = transition.target.copy(
                    rect = if (interpolated) {
                        transition.from.interpolate(transition.target.rect, progress)
                    } else {
                        transition.target.rect
                    },
                ),
                interpolated = interpolated,
                sourceFrameId = current.sourceFrameId,
                sourceCapturedAtElapsedRealtimeMs = current.sourceCapturedAtMs,
                sourceAgeMs = ageMs,
            )
        }
        return RenderedFrame(
            boxes = rendered,
            nextRedrawDelayMs = when {
                rendered.isEmpty() -> null
                rendered.any { it.interpolated } -> 0L
                else -> maxSourceAgeMs - ageMs
            },
        )
    }

    /** Called at camera/session/route/display-geometry boundaries by the owning View/activity. */
    fun clear() {
        frame = null
    }

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        private val width: Float get() = right - left
        private val height: Float get() = bottom - top

        fun isValid(): Boolean = left.isFinite() && top.isFinite() && right.isFinite() &&
            bottom.isFinite() && width > 0f && height > 0f

        fun centerDistance(other: Rect): Float {
            val dx = (left + right - other.left - other.right) / 2f
            val dy = (top + bottom - other.top - other.bottom) / 2f
            return sqrt(dx * dx + dy * dy)
        }

        fun canInterpolateTo(other: Rect): Boolean {
            // A large jump or scale change is not reliable visual association: snap immediately.
            val scaleX = other.width / width
            val scaleY = other.height / height
            return scaleX in 0.8f..1.25f && scaleY in 0.8f..1.25f &&
                centerDistance(other) <= minOf(width, height, other.width, other.height) * 0.35f
        }

        fun interpolate(other: Rect, fraction: Float): Rect = Rect(
            left = left + (other.left - left) * fraction,
            top = top + (other.top - top) * fraction,
            right = right + (other.right - right) * fraction,
            bottom = bottom + (other.bottom - bottom) * fraction,
        )
    }

    data class Box(
        val rect: Rect,
        val label: String,
        val best: Boolean,
        val className: String? = null,
        val held: Boolean = false,
        val smoothed: Boolean = false,
    ) {
        fun matchesRole(other: Box): Boolean = best == other.best &&
            (className ?: label) == (other.className ?: other.label)
    }

    data class RenderedBox(
        val box: Box,
        val interpolated: Boolean,
        val sourceFrameId: Long,
        val sourceCapturedAtElapsedRealtimeMs: Long,
        val sourceAgeMs: Long,
    )

    data class RenderedFrame(val boxes: List<RenderedBox>, val nextRedrawDelayMs: Long?) {
        companion object {
            val EMPTY = RenderedFrame(emptyList(), null)
        }
    }

    private data class Transition(val from: Rect, val target: Box, val startedAtMs: Long)
    private data class Frame(
        val sourceFrameId: Long,
        val sourceCapturedAtMs: Long,
        val acceptedAtMs: Long,
        val sourceComplete: Boolean,
        val transitions: List<Transition>,
    )
}
