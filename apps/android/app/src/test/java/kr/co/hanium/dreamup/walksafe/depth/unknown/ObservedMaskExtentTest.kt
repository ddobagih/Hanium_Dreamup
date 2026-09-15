package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.BinaryImageMask
import org.junit.Assert.*
import org.junit.Test

class ObservedMaskExtentTest {
    private val width = 640
    private val height = 480
    private val identity = MaskDepthEstimator.FrameIdentity("test", "one", 1_000_000_000L, "camera")
    private fun calibration(fx: Double = 600.0, fy: Double = 300.0) = MaskDepthEstimator.Calibration.affine(
        identity, width, height, width, height, doubleArrayOf(1.0 / width, 0.0, 0.0, 0.0, 1.0 / height, 0.0),
        "synthetic_calibration", MaskDepthEstimator.Intrinsics(width, height, fx, fy, width / 2.0, height / 2.0))
    private fun mask(left: Int = 300, top: Int = 220, w: Int = 12, h: Int = 16) =
        BinaryImageMask.fromPackedRoi(width, height, left, top, w, h, ByteArray((w * h + 7) / 8) { -1 })
    private fun depth(mask: BinaryImageMask, full: Boolean = false, confidence: Int = 255,
                      sparse: Boolean = false): MaskDepthEstimator.Result {
        val adapter = object : MaskDepthEstimator.ImageMask {
            override fun imageWidth() = width
            override fun imageHeight() = height
            override fun minX() = mask.left
            override fun minY() = mask.top
            override fun maxXExclusive() = mask.left + mask.width
            override fun maxYExclusive() = mask.top + mask.height
            override fun contains(x: Int, y: Int) = mask.contains(x, y)
        }
        val pixels = IntArray(width * height) { i -> if (sparse && i % width % 3 != 0) 0 else 2500 }
        val frame = if (full) MaskDepthEstimator.DepthFrame.full(identity, identity.cameraTimestampNs(), "camera", pixels, calibration())
            else MaskDepthEstimator.DepthFrame.raw(identity, identity.cameraTimestampNs(), "camera", pixels,
                ByteArray(width * height) { confidence.toByte() }, calibration(), identity)
        return MaskDepthEstimator().associate(listOf(MaskDepthEstimator.Detection("mask", "unknown", identity, adapter)), frame).single()
    }

    @Test fun projectsWholeRgbMaskUsingAxialDepthAndSwapsPortraitAxes() {
        val mask = mask(); val result = depth(mask)
        val extent = requireNotNull(ObservedMaskExtentEstimator.estimate(mask, result, calibration(), true, 0))
        assertEquals(.05f, extent.widthM, .00001f)
        assertEquals(16 * 2.5f / 300, extent.heightM, .00001f)
        assertTrue(extent.conservativeWidthM > extent.widthM)
        assertTrue(extent.conservativeHeightM > extent.heightM)
        assertTrue(extent.canRejectSmall)
        for (turn in listOf(1, 3)) {
            val portrait = requireNotNull(ObservedMaskExtentEstimator.estimate(mask, result, calibration(), true, turn))
            assertEquals(extent.widthM, portrait.heightM)
            assertEquals(extent.heightM, portrait.widthM)
            assertEquals(extent.conservativeWidthM, portrait.conservativeHeightM)
        }
    }

    @Test fun fullDepthAndWeakRawCannotDeclareASmallObjectHarmless() {
        val mask = mask()
        val full = requireNotNull(ObservedMaskExtentEstimator.estimate(mask, depth(mask, full = true), calibration(), true, 0))
        assertFalse(full.canRejectSmall)
        assertEquals("smoothed_depth_extent_is_diagnostic", full.reason)
        val weak = requireNotNull(ObservedMaskExtentEstimator.estimate(mask, depth(mask, confidence = 150), calibration(), true, 0))
        assertFalse(weak.canRejectSmall)
        val sparse = depth(mask, sparse = true)
        assertEquals(MaskDepthEstimator.Status.KNOWN, sparse.status)
        val visible = requireNotNull(ObservedMaskExtentEstimator.estimate(mask, sparse, calibration(), true, 0))
        assertEquals(.05f, visible.widthM, .00001f)
        assertFalse(visible.canRejectSmall)
    }

    @Test fun missingIntrinsicsPartialCoverageAndImageClippingDoNotReject() {
        val mask = mask(); val result = depth(mask)
        assertNull(ObservedMaskExtentEstimator.estimate(mask, result, null, true, 0))
        assertNull(ObservedMaskExtentEstimator.estimate(mask, result, calibration(Double.NaN), true, 0))
        assertNull(ObservedMaskExtentEstimator.estimate(mask, result, calibration(), true, 4))
        assertNull(ObservedMaskExtentEstimator.estimate(mask, result, calibration(), false, 0))
        val clipped = mask(left = 0)
        val extent = requireNotNull(ObservedMaskExtentEstimator.estimate(clipped, depth(clipped), calibration(), true, 0))
        assertFalse(extent.canRejectSmall)
        assertEquals("image_edge_may_clip_object", extent.reason)
    }

    @Test fun tinyRgbMaskOnLowResolutionDepthIsUnmeasuredNotProvenHarmless() {
        // Representative dimensions, not a measured device fixture. At 2.5m this mask projects to
        // about 3.3cm, but occupies only ~3x3 raw pixels. It cannot meet the robust support contract.
        val w = 1280; val h = 720; val dw = 320; val dh = 180
        val mask = BinaryImageMask.fromPackedRoi(w, h, 640, 352, 12, 12, ByteArray(18) { -1 })
        val c = MaskDepthEstimator.Calibration.affine(identity, w, h, dw, dh,
            doubleArrayOf(1.0 / w, 0.0, 0.0, 0.0, 1.0 / h, 0.0), "synthetic_calibration",
            MaskDepthEstimator.Intrinsics(w, h, 900.0, 900.0, 640.0, 360.0))
        val adapter = object : MaskDepthEstimator.ImageMask {
            override fun imageWidth() = w
            override fun imageHeight() = h
            override fun minX() = mask.left
            override fun minY() = mask.top
            override fun maxXExclusive() = mask.left + mask.width
            override fun maxYExclusive() = mask.top + mask.height
            override fun contains(x: Int, y: Int) = mask.contains(x, y)
        }
        val frame = MaskDepthEstimator.DepthFrame.raw(identity, identity.cameraTimestampNs(), "camera",
            IntArray(dw * dh) { 2500 }, ByteArray(dw * dh) { -1 }, c, identity)
        val result = MaskDepthEstimator().associate(listOf(MaskDepthEstimator.Detection("tiny", "unknown", identity, adapter)), frame).single()
        assertNotEquals(MaskDepthEstimator.Status.KNOWN, result.status)
        assertNull(ObservedMaskExtentEstimator.estimate(mask, result, c, true, 0))
    }
}
