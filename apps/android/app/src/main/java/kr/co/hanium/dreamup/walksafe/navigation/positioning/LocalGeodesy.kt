package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * S09-52 (originals/09.txt:133): one local coordinate convention for the positioning pipeline.
 *
 * - Sphere radius [EARTH_RADIUS_M] (the value most modules and every haversine already used).
 * - Longitudes and longitude differences are wrapped to [-180, 180] so the antimeridian is a short
 *   east/west step; in-range values are returned unchanged (exact ordinary arithmetic).
 * - [Frame] is an equirectangular east/north frame for short pedestrian distances around an origin.
 *   It is not valid near the poles; callers only build it from validated fixes (|latitude| < 90).
 */
internal object LocalGeodesy {
    const val EARTH_RADIUS_M = 6_371_000.0
    const val METERS_PER_DEGREE = EARTH_RADIUS_M * PI / 180.0

    fun wrapLongitudeDegrees(degrees: Double): Double {
        if (degrees in -180.0..180.0 || !degrees.isFinite()) return degrees
        val wrapped = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return if (wrapped == -180.0 && degrees > 0.0) 180.0 else wrapped
    }

    /** Metres per degree of longitude at [latitudeDegrees]. */
    fun metersPerLongitudeDegree(latitudeDegrees: Double): Double = METERS_PER_DEGREE * cos(Math.toRadians(latitudeDegrees))

    class Frame(val originLatitude: Double, val originLongitude: Double) {
        private val eastScale: Double
        init {
            require(originLatitude.isFinite() && abs(originLatitude) < 90.0 && originLongitude.isFinite())
            eastScale = metersPerLongitudeDegree(originLatitude)
        }

        fun toLocal(latitude: Double, longitude: Double): LocalEnuPosition = LocalEnuPosition(
            eastM = wrapLongitudeDegrees(longitude - originLongitude) * eastScale,
            northM = (latitude - originLatitude) * METERS_PER_DEGREE,
        )

        fun toCoordinate(eastM: Double, northM: Double): PositionCoordinate = PositionCoordinate(
            latitude = originLatitude + northM / METERS_PER_DEGREE,
            longitude = wrapLongitudeDegrees(originLongitude + eastM / eastScale),
        )
    }
}
