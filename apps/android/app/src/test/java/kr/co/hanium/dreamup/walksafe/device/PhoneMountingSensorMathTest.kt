package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneMountingSensorMathTest {
    @Test
    fun `upright forward camera is level with the horizon`() {
        assertEquals(
            0.0,
            PhoneMountingSensorMath.cameraPitchFromHorizontalDegrees(0.0, 9.81, 0.0)!!,
            0.000_001,
        )
    }

    @Test
    fun `flat camera reports ninety degree pitch`() {
        assertEquals(
            90.0,
            PhoneMountingSensorMath.cameraPitchFromHorizontalDegrees(0.0, 0.0, 9.81)!!,
            0.000_001,
        )
        assertEquals(
            -90.0,
            PhoneMountingSensorMath.cameraPitchFromHorizontalDegrees(0.0, 0.0, -9.81)!!,
            0.000_001,
        )
    }

    @Test
    fun `gyro magnitude is converted from radians to degrees`() {
        assertEquals(
            180.0,
            PhoneMountingSensorMath.angularShakeDegreesPerSecond(Math.PI, 0.0, 0.0)!!,
            0.000_001,
        )
    }

    @Test
    fun `invalid or missing gravity magnitude is rejected`() {
        assertNull(
            PhoneMountingSensorMath.cameraPitchFromHorizontalDegrees(0.0, 0.0, 0.0),
        )
        assertNull(
            PhoneMountingSensorMath.angularShakeDegreesPerSecond(Double.NaN, 0.0, 0.0),
        )
    }
}
