package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S07-12 (FP-022-DETAIL-03 interim): stairs avoided; other accessibility information not confirmed. */
class RouteScopeDisclosureTest {
    @Test fun stairAvoidRouteStatesItsScopeWithoutASafetyClaim() {
        val disclosure = requireNotNull(routeScopeDisclosureKo("STAIR_AVOID"))
        assertEquals("계단을 피하는 경로입니다. 보도 상태나 공사 같은 다른 접근성 정보는 확인되지 않았습니다.", disclosure)
        listOf("안전", "적합", "보장", "확인했습니다").forEach { claim -> assertFalse(claim, disclosure.contains(claim)) }
        assertNull(routeScopeDisclosureKo("RECOMMEND"))
    }

    @Test fun mainActivitySaysItOnceAsItsOwnUtteranceForANewDestinationRoute() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val ready = source.substringAfter("routeNavigator.setRoute(\n                            route,")
            .substringBefore("} catch (_: CancellationException) {")
        assertTrue(ready.contains(".routeScopeDisclosureKo(route.priority).takeIf { reason != \"off_route\" }"))
        // With a location: the scope utterance first; the first direction is held and retried after it.
        val located = ready.substringAfter("if (currentRouteLocationOrNull() != null) {")
            .substringBefore("} else {")
        val scope = located.indexOf("scopeDisclosure?.let(::speakInteraction)")
        assertTrue(scope >= 0 && scope < located.indexOf("updateRouteGuidance(\n                                connectedInstallLocation,"))
        // Without a location: "경로를 찾았습니다" follows the scope utterance, only for the same route.
        val waiting = ready.substringAfter("var routeFoundAnnounced = false")
        assertTrue(waiting.contains("routeRequestGeneration != scopeRouteGeneration"))
        assertTrue(waiting.indexOf("onCompleted = { announceRouteFound() }") > waiting.indexOf("scopeDisclosure,"))
        assertTrue(waiting.contains("onFailed = { announceRouteFound() }"))
        // The route summary sentences never absorb the scope sentence (S08-30: one utterance, one purpose).
        assertFalse(source.contains("전체 경로는 약 \${currentRoute.summary.distanceM}m입니다. 계단"))
    }
}
