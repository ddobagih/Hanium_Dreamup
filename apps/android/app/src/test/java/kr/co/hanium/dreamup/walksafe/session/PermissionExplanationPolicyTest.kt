package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionExplanationPolicyTest {
    @Test
    fun beforeRequestDoesNotTreatFalseRationaleOrApproximateAccessAsDenial() {
        val explanation = PermissionExplanationPolicy.withoutResult(
            missingPermissions = setOf(PermissionExplanationKind.PRECISE_LOCATION),
            coarseLocationCurrentlyGranted = true,
        )

        assertFalse(explanation.requestResultReceived)
        assertEquals(PermissionLocationState.APPROXIMATE_ONLY, explanation.locationState)
        assertEquals(
            mapOf(PermissionExplanationKind.PRECISE_LOCATION to PermissionReRequestability.NOT_EVALUATED),
            explanation.reRequestabilityByPermission,
        )
    }

    @Test
    fun requestResultDistinguishesApproximateOnlyFromNoLocation() {
        val fineDenied = PermissionRequestAnswer(
            PermissionExplanationKind.PRECISE_LOCATION,
            granted = false,
            rationaleRecommended = false,
        )
        val approximateGranted = PermissionExplanationPolicy.afterRequest(
            answers = listOf(
                fineDenied,
                PermissionRequestAnswer(
                    PermissionExplanationKind.APPROXIMATE_LOCATION,
                    granted = true,
                    rationaleRecommended = false,
                ),
            ),
            coarseLocationCurrentlyGranted = false,
        )
        val noLocation = PermissionExplanationPolicy.afterRequest(
            answers = listOf(
                fineDenied,
                PermissionRequestAnswer(
                    PermissionExplanationKind.APPROXIMATE_LOCATION,
                    granted = false,
                    rationaleRecommended = false,
                ),
            ),
            coarseLocationCurrentlyGranted = false,
        )

        assertTrue(approximateGranted!!.requestResultReceived)
        assertEquals(PermissionLocationState.APPROXIMATE_ONLY, approximateGranted.locationState)
        assertEquals(
            setOf(PermissionExplanationKind.PRECISE_LOCATION),
            approximateGranted.unavailablePermissions,
        )
        assertEquals(PermissionLocationState.NO_LOCATION, noLocation!!.locationState)
        assertEquals(
            setOf(
                PermissionExplanationKind.PRECISE_LOCATION,
                PermissionExplanationKind.APPROXIMATE_LOCATION,
            ),
            noLocation.unavailablePermissions,
        )
    }

    @Test
    fun rationaleTrueAndFalseAreNotConflatedWithPermanentDenial() {
        fun result(rationale: Boolean) = PermissionExplanationPolicy.afterRequest(
            answers = listOf(
                PermissionRequestAnswer(
                    PermissionExplanationKind.CAMERA,
                    granted = false,
                    rationaleRecommended = rationale,
                ),
            ),
            coarseLocationCurrentlyGranted = false,
        )!!

        assertEquals(
            mapOf(PermissionExplanationKind.CAMERA to PermissionReRequestability.RATIONALE_RECOMMENDED),
            result(rationale = true).reRequestabilityByPermission,
        )
        assertEquals(
            mapOf(PermissionExplanationKind.CAMERA to PermissionReRequestability.NO_RATIONALE_SIGNAL),
            result(rationale = false).reRequestabilityByPermission,
        )
    }

    @Test
    fun mixedDenialKeepsRationaleGuidanceAttachedToEachPermission() {
        val explanation = PermissionExplanationPolicy.afterRequest(
            answers = listOf(
                PermissionRequestAnswer(
                    PermissionExplanationKind.CAMERA,
                    granted = false,
                    rationaleRecommended = true,
                ),
                PermissionRequestAnswer(
                    PermissionExplanationKind.PRECISE_LOCATION,
                    granted = false,
                    rationaleRecommended = false,
                ),
                PermissionRequestAnswer(
                    PermissionExplanationKind.APPROXIMATE_LOCATION,
                    granted = true,
                    rationaleRecommended = false,
                ),
            ),
            coarseLocationCurrentlyGranted = false,
        )!!

        assertTrue(explanation.requestResultReceived)
        assertEquals(PermissionLocationState.APPROXIMATE_ONLY, explanation.locationState)
        assertEquals(
            setOf(PermissionExplanationKind.CAMERA, PermissionExplanationKind.PRECISE_LOCATION),
            explanation.unavailablePermissions,
        )
        assertEquals(
            mapOf(
                PermissionExplanationKind.CAMERA to PermissionReRequestability.RATIONALE_RECOMMENDED,
                PermissionExplanationKind.PRECISE_LOCATION to PermissionReRequestability.NO_RATIONALE_SIGNAL,
            ),
            explanation.reRequestabilityByPermission,
        )
    }

    @Test
    fun emptyOrFullyGrantedResultDoesNotInventARefusal() {
        assertNull(PermissionExplanationPolicy.afterRequest(emptyList(), false))
        assertNull(PermissionExplanationPolicy.afterRequest(
            answers = listOf(
                PermissionRequestAnswer(
                    PermissionExplanationKind.PRECISE_LOCATION,
                    granted = true,
                    rationaleRecommended = false,
                ),
                PermissionRequestAnswer(
                    PermissionExplanationKind.APPROXIMATE_LOCATION,
                    granted = false,
                    rationaleRecommended = false,
                ),
            ),
            coarseLocationCurrentlyGranted = false,
        ))
    }
}
