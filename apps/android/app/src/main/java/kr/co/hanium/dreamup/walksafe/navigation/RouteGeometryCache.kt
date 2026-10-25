package kr.co.hanium.dreamup.walksafe.navigation

/** One owned route snapshot's fixed measurements, shared by progress and bearing selection. */
internal class RouteGeometryCache(polyline: List<RoutePoint>) {
    val points = polyline.toList()
    val segments: List<RouteGeometrySegment>
    val totalM: Double

    init {
        var cumulativeM = 0.0
        segments = List((points.size - 1).coerceAtLeast(0)) { index ->
            val start = points[index]
            val end = points[index + 1]
            val lengthM = haversineMeters(start.latitude, start.longitude, end.latitude, end.longitude)
            RouteGeometrySegment(index, start, end, lengthM, cumulativeM,
                bearingDegrees(start.latitude, start.longitude, end.latitude, end.longitude))
                .also { cumulativeM += lengthM }
        }
        totalM = cumulativeM
    }

    fun matchesPoints(polyline: List<RoutePoint>): Boolean = points == polyline
}

internal data class RouteGeometrySegment(
    val index: Int,
    val start: RoutePoint,
    val end: RoutePoint,
    val lengthM: Double,
    val cumulativeM: Double,
    val bearingDeg: Float,
)
