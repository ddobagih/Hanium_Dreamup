package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocationPolicyTest {
    @Test
    fun rejectsNullOrPoorAccuracyForRouteDecisions() {
        assertNull(LocationTrustPolicy.trustedOrNull(37.0, 127.0, null, 1_000L))
        assertNull(LocationTrustPolicy.trustedOrNull(37.0, 127.0, 60f, 1_000L))
    }

    @Test
    fun rejectsLargeGpsJump() {
        val previous = LocationTrustPolicy.trustedOrNull(37.0, 127.0, 5f, 1_000L)

        val jumped = LocationTrustPolicy.trustedOrNull(37.01, 127.01, 5f, 2_000L, previous = previous)

        assertNull(jumped)
    }

    @Test
    fun acceptsTrustedFix() {
        val trusted = LocationTrustPolicy.trustedOrNull(37.0, 127.0, 8f, 1_000L)

        assertNotNull(trusted)
    }
}
