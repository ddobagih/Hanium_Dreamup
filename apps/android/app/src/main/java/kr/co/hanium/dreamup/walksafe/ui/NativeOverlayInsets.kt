package kr.co.hanium.dreamup.walksafe.ui

import kotlin.math.roundToInt

/** Density reads remain bottom, horizontal, top; bottom deliberately truncates. */
internal data class NativeOverlayInsets(val bottom: Int, val horizontal: Int, val top: Int) {
    companion object {
        fun read(bottomDp: Float, horizontalDp: Float, topDp: Float, density: () -> Float): NativeOverlayInsets {
            val bottom = (bottomDp * density()).toInt()
            val horizontal = (horizontalDp * density()).roundToInt()
            val top = (topDp * density()).roundToInt()
            return NativeOverlayInsets(bottom, horizontal, top)
        }
    }
}
