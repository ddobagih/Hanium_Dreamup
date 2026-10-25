package kr.co.hanium.dreamup.walksafe.navigation

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatcher
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * S07-24 / S09-51: a repeatable public-API host JVM workload, not an Android latency assertion.
 * Run this same probe before and after a change. Allocation is the executing JVM thread's measured
 * bytes when its ThreadMXBean supports them; no heap-size estimate or pass/fail speed threshold.
 */
class RouteGeometryPerformanceProbeTest {
    @Test
    fun measuresMaximumContractRouteInstallationAndRepeatedPositions() {
        val route = route()
        val allocation = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.takeIf {
            it.isThreadAllocatedMemorySupported
        }?.also { if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true }
        val threadId = Thread.currentThread().id
        fun allocated(): Long? = allocation?.getThreadAllocatedBytes(threadId)?.takeIf { it >= 0L }
        val sampleCount = 240
        val warmupCount = 40
        var checksum = 0L

        fun measure(label: String, count: Int, action: (Int) -> Unit) {
            val durations = LongArray(count)
            val allocations = LongArray(count)
            var hasAllocations = allocation != null
            repeat(count) { iteration ->
                val beforeBytes = allocated()
                val start = System.nanoTime()
                action(iteration)
                durations[iteration] = System.nanoTime() - start
                val afterBytes = allocated()
                if (beforeBytes == null || afterBytes == null) hasAllocations = false
                else allocations[iteration] = afterBytes - beforeBytes
            }
            durations.sort()
            fun percentile(fraction: Double) = durations[((count - 1) * fraction).toInt()] / 1_000_000.0
            println("ROUTE_GEOMETRY_PROBE phase=$label points=${route.polyline.size} guides=${route.guidePoints.size} " +
                "samples=$count p50_ms=${percentile(0.50)} p95_ms=${percentile(0.95)} p99_ms=${percentile(0.99)} " +
                "mean_ms=${durations.average() / 1_000_000.0} " +
                "mean_allocated_bytes=${if (hasAllocations) allocations.average().toString() else "unavailable"}")
        }

        // Warm the public install path separately, without charging route fixture construction.
        RouteNavigator().setRoute(route)
        val navigator = RouteNavigator()
        measure("setRoute", 3) { navigator.setRoute(route) }
        fun update(index: Int) {
            val time = (index + 1) * 1_000L
            val point = route.polyline[100 + index]
            val result = navigator.update(TrustedLocation(point.latitude, point.longitude, 3f, time), time, false)
            checksum += result.reason.length
        }
        repeat(warmupCount, ::update)
        measure("navigatorUpdate", sampleCount) { update(it + warmupCount) }
        assertNotNull(navigator.currentRouteMatch()?.matchedPoint)

        val matcher = RouteCorridorMatcher()
        fun match(index: Int) {
            val result = matcher.match("performance-line", route.polyline,
                FilteredRoutePosition(route.polyline[100 + index], 3.0, (index + 1) * 1_000L))
            checksum += requireNotNull(result.segmentIndex)
        }
        repeat(warmupCount, ::match)
        measure("matcherMatch", sampleCount) { match(it + warmupCount) }
        println("ROUTE_GEOMETRY_PROBE checksum=$checksum java=${System.getProperty("java.version")}")
    }

    private fun route(): WalkingRoute {
        val points = List(8_192) { RoutePoint(37.0 + it / 111_194.92664455873, 127.0) }
        val guides = List(1_024) { index ->
            val pointIndex = index * (points.lastIndex.toDouble() / 1_023).toInt()
            WalkingRouteGuidePoint(index, points[pointIndex], null, pointIndex, 8_191 - pointIndex,
                bearingDeg = 0f, turnType = if (index == 0) 200 else null)
        }
        return WalkingRoute("STAIR_AVOID", WalkingRouteSummary(8_191, 5_461), points, guides,
            providerRouteId = "performance-line")
    }
}
