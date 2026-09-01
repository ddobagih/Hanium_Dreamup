package kr.co.hanium.dreamup.walksafe.device

import kotlin.math.asin
import kotlin.math.sqrt

object PhoneMountingSensorMath {
    fun cameraPitchFromHorizontalDegrees(
        gravityX: Double,
        gravityY: Double,
        gravityZ: Double,
    ): Double? {
        if (listOf(gravityX, gravityY, gravityZ).any { !it.isFinite() }) return null
        val magnitude = sqrt(
            gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ,
        )
        if (!magnitude.isFinite() || magnitude < 1.0) return null
        return Math.toDegrees(asin((gravityZ / magnitude).coerceIn(-1.0, 1.0)))
    }

    fun angularShakeDegreesPerSecond(
        xRadiansPerSecond: Double,
        yRadiansPerSecond: Double,
        zRadiansPerSecond: Double,
    ): Double? {
        if (
            listOf(xRadiansPerSecond, yRadiansPerSecond, zRadiansPerSecond)
                .any { !it.isFinite() }
        ) return null
        return Math.toDegrees(
            sqrt(
                xRadiansPerSecond * xRadiansPerSecond +
                    yRadiansPerSecond * yRadiansPerSecond +
                    zRadiansPerSecond * zRadiansPerSecond,
            ),
        )
    }
}
