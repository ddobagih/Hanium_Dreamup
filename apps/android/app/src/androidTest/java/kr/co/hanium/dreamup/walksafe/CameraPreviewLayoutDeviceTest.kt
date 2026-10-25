package kr.co.hanium.dreamup.walksafe

import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraPreviewLayoutDeviceTest {
    @Test fun cameraAndBothPixelOverlaysHaveIdenticalFittedBoundsAcrossRotationResizeAndReconnect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val layout = CameraPreviewLayout(instrumentation.targetContext)
            val children = List(3) { View(instrumentation.targetContext).also {
                layout.addView(it, FrameLayout.LayoutParams(-1, -1))
            } }
            val commits = mutableListOf<CameraPreviewGeometry.Viewport>()
            layout.onViewportChanged = { commits += it }
            fun measure(width: Int, height: Int) {
                layout.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                layout.layout(0, 0, width, height)
            }
            fun assertChildren(rect: Rect) {
                for (child in children) {
                    assertEquals(rect, Rect(child.left, child.top, child.right, child.bottom))
                    assertEquals(rect.width(), child.measuredWidth)
                    assertEquals(rect.height(), child.measuredHeight)
                }
            }
            layout.setImageSize(1920, 1080, 1, 1L)
            measure(1080, 2160)
            assertChildren(Rect(0, 120, 1080, 2040))
            assertEquals(CameraPreviewGeometry.Viewport(1080, 1920, 1L), commits.last())
            layout.setImageSize(1920, 1080, 0, 2L)
            measure(2400, 1080)
            assertChildren(Rect(240, 0, 2160, 1080))
            assertEquals(CameraPreviewGeometry.Viewport(1920, 1080, 2L), commits.last())
            layout.setImageSize(1920, 1080, 0, 3L)
            measure(2400, 1080)
            assertEquals(CameraPreviewGeometry.Viewport(1920, 1080, 3L), commits.last())
            val count = commits.size
            measure(2400, 1080)
            assertEquals("Unchanged layout must not keep invalidating frames", count, commits.size)
            layout.setImageSize(0, 1080, 1, 4L)
            measure(2400, 1080)
            assertEquals(CameraPreviewGeometry.Viewport(1920, 1080, 3L), commits.last())
        }
    }
}
