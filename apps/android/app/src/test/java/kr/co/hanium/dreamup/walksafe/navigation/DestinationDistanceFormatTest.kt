package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationDistanceFormatTest {
    @Test fun usesKilometersFromOneKilometer() {
        assertEquals("999m", formatDestinationDistance(999))
        assertEquals("1.0km", formatDestinationDistance(1000))
        assertEquals("12.5km", formatDestinationDistance(12500))
        assertEquals("300.0km", formatDestinationDistance(300000))
    }
    @Test fun keepsShortAndUnknownDistances() {
        assertEquals("0m", formatDestinationDistance(0))
        assertEquals("350m", formatDestinationDistance(350))
        assertEquals("거리미상", formatDestinationDistance(null))
        assertEquals("거리미상", formatDestinationDistance(-1))
    }
}
