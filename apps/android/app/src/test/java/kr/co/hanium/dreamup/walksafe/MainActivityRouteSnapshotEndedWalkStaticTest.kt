package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 07.txt #40: only the current walk lease may replace another walk's leftover snapshot. */
class MainActivityRouteSnapshotEndedWalkStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun firstRouteSaveReplacesOnlyAfterTheCurrentWalkLeaseIsRechecked() {
        MainActivityRefactorSource.composition(source, "routeAcquisitionController")
        MainActivityRefactorSource.composition(source, "routeArchiveController")
        val acquisition = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt").readText()
        val archive = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteArchiveController.kt").readText()
        val request = MainActivityRefactorSource.member(acquisition, "requestRoute", 1)
        val admission = ReportStaticSourceInspector.blockAfter(request, "fun storageAdmissionCurrent()")
        assertTrue(request.contains("RouteSnapshotScope(owner, expectedWalkEpoch.walkSessionId)"))
        assertTrue(request.contains("archive.walkScope != scope"))
        assertTrue(admission.contains("lifecycleOwner.isRuntimeEpochCurrent(expectedWalkEpoch)"))
        assertTrue(admission.contains("routeRequestGeneration == requestId"))
        assertTrue(admission.contains("isCurrentRouteRequest()"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(request,
            "isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId)", "routeCall.execute()",
            "postToUi {", "routeResponseInstallLeaseCurrent(requestId, ::isCurrentRouteRequest)",
            "archive.submitRouteSnapshotResponseStorage(lane, lease,", "::storageAdmissionCurrent, scope, route, destination"))
        val submission = MainActivityRefactorSource.member(archive, "submitRouteSnapshotResponseStorage")
        assertTrue(submission.contains("lane.submit<StoredRouteSnapshot?>(lease, null, current)"))
        assertTrue(submission.contains("routeSnapshotResponseArchive(store, scope, captured, destination, provenance)"))
        val save = MainActivityRefactorSource.member(archive, "routeSnapshotResponseArchive")
        assertTrue(save.contains("store.saveFirstRoute(scope = scope, route = route, destination = destination"))
        assertTrue(save.contains("if (!saved) null else store.load(scope, nowEpochMs = nowEpochMs)"))
        assertTrue(save.contains("it.walkSessionId == scope.walkSessionId"))
    }
}
