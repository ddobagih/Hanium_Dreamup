package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-10: the activity shows causes added to a stop it already owns, without repeating cleanup. */
class MainActivityRuntimeSafetyCauseUpdateStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    private fun function(name: String) = MainActivityRefactorSource.function(source, "private fun $name(")
    private fun member(text: String, name: String) = MainActivityRefactorSource.member(text, name)
    private fun ui(name: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/$name.kt").readText()

    @Test
    fun coordinatorCauseChangesReachTheOwnedStopStatusOnly() {
        val construction = source.substringAfter("private val walkRuntimeSafetyCoordinator = WalkRuntimeSafetyCoordinator(")
            .substringBefore("\n    )")
        assertTrue(construction.contains("onStopCausesChanged = ::onWalkRuntimeSafetyStopCausesChanged"))

        val wiring = MainActivityRefactorSource.composition(source, "walkStopDelivery")
        assertTrue(wiring.contains("override fun enqueueOnMain(action: () -> Unit) = reportCleanupCallbackHandler.post { action() }"))
        assertTrue(wiring.contains("override fun showCauseStatus(navigationStatus: String) { updateNavigationStatus(navigationStatus); updateWalkSafetySummary() }"))
        val handler = function("onWalkRuntimeSafetyStopCausesChanged")
        // Posted (never run inline) so a stop delivery queued earlier sets the cleanup epoch first.
        assertTrue(handler.contains("scheduler.enqueueOnMain {"))
        assertFalse(handler.contains("runOnUiThread"))
        assertFalse(handler.contains("scheduler.dispatchOnMain"))
        assertFalse(handler.contains("cleanup.run("))
        assertTrue(handler.contains("safety.latchedStopStatus() ?: return@enqueueOnMain"))
        assertTrue(handler.contains("val stop = current.stop"))
        assertTrue(handler.contains("if (walkRuntimeSafetyStopCleanupEpoch != stop.epoch) return@enqueueOnMain"))
        assertTrue(handler.contains("\"navigation=runtime_safety_stopped reason=runtime_safety_coordinator:\$causes\""))
        assertFalse(handler.contains("performWalkSessionSafetyStopCleanup("))
        assertFalse(handler.contains("transitionWalkSession("))
        // The stop delivery re-reads the latch after owning the epoch, so a cause added
        // between the coordinator snapshot and this runnable is still shown.
        val delivery = function("onWalkRuntimeSafetyStop")
        val epochSet = delivery.indexOf("walkRuntimeSafetyStopCleanupEpoch = stop.epoch")
        val reread = delivery.indexOf("safety.latchedStop()")
        assertTrue(epochSet >= 0 && reread > epochSet)
        assertTrue(delivery.contains("it.epoch == stop.epoch && it.causes != stop.causes"))
        assertTrue(delivery.contains("?.let(::onWalkRuntimeSafetyStopCausesChanged)"))
        val acknowledge = delivery.indexOf("completion(synchronousComplete && cameraReleased == true && locationReleased == true)")
        val owned = delivery.indexOf("if (currentOwnedWalkRuntimeSafetyStopStatus()?.stop?.epoch == stop.epoch)")
        val refresh = delivery.indexOf("effects.refreshOwnedStop()", owned)
        assertTrue(acknowledge >= 0 && owned > acknowledge && refresh > owned)
        assertTrue(wiring.contains("override fun refreshOwnedStop() { updateWalkSafetySummary(); if (nativeUiPage == NativeUiPage.GUIDANCE) renderMainUi() }"))
    }

    @Test
    fun causeUpdateStatusIsNotReadAsARouteFailure() {
        assertTrue(member(source, "currentRouteGuidanceDisplayInput").contains("NativeRouteGuidancePresentation.backendFailureReason(routeStatus)"))
        val presentation = member(ui("NativeRouteGuidancePresentation"), "backendFailureReason")
        assertTrue(presentation.contains("\"navigation=safety_stopped\""))
        assertFalse(presentation.contains("navigation=runtime_safety_stopped"))
    }

    @Test
    fun laterCausesKeepExhaustedRecoveryAndTheCurrentOwnedEpoch() {
        val exhausted = function("onWalkRuntimeSafetyStopExhausted")
        assertTrue(exhausted.contains("latchedStopStatus() ?: return@dispatchOnMain"))
        assertTrue(exhausted.contains("current.stop.epoch != stop.epoch || !current.cleanupExhausted"))
        assertTrue(exhausted.contains("walkRuntimeSafetyStopCleanupEpoch != current.stop.epoch"))
        val cause = function("onWalkRuntimeSafetyStopCausesChanged")
        val ownerCheck = cause.indexOf("if (walkRuntimeSafetyStopCleanupEpoch != stop.epoch) return@enqueueOnMain")
        val restart = cause.indexOf("if (current.cleanupExhausted)")
        val ordinary = cause.indexOf("navigation=runtime_safety_stopped reason=runtime_safety_coordinator")
        assertTrue(ownerCheck >= 0 && restart > ownerCheck && ordinary > restart)
        assertTrue(cause.substring(restart, ordinary).contains("effects.showRestartNotice()"))
        assertTrue(cause.substring(restart, ordinary).contains("return@enqueueOnMain"))
        val wiring = MainActivityRefactorSource.composition(source, "walkStopDelivery")
        assertTrue(wiring.contains("override fun showRestartNotice() { showWalkRuntimeSafetyRestartNotice() }"))
        val notice = member(source, "showWalkRuntimeSafetyRestartNotice")
        assertTrue(notice.contains("latchedStopStatus()?.userFacingSummary()"))
        assertTrue(notice.contains("navigation=runtime_safety_stopped reason=runtime_cleanup_exhausted"))
        val summary = member(source, "updateWalkSafetySummary")
        assertTrue(summary.contains("WalkSessionState.SAFE_STOP -> currentOwnedWalkRuntimeSafetyStopStatus()"))
        assertTrue(summary.contains("it.userFacingSummary()"))
    }

    @Test
    fun theVisibleNativeGuidanceStatusUsesOnlyItsOwnedStoppedSession() {
        val render = function("renderMainUi")
        assertTrue(render.contains("pages.renderFrame("))
        val workflow = MainActivityRefactorSource.composition(source, "nativeWorkflowController")
        assertTrue(workflow.contains("nativePagePresentationController.renderFrame(frame)"))
        val pages = MainActivityRefactorSource.composition(source, "nativePagePresentationController")
        assertTrue(pages.contains("updateNativeGuidanceStatus(presentation, measurements)"))
        assertTrue(member(ui("NativePagePresentationController"), "renderFrame").contains(
            "phases.renderGuidanceStatus(presentation, queries.walkingCalibrationStatusMessage())"))
        val writer = member(source, "updateNativeGuidanceStatus")
        assertTrue(writer.contains("destinationSearchPresenter.renderGuidanceStatus(nativeGuidanceStatusMessage(presentation, false),"))
        assertTrue(member(ui("DestinationSearchPresenter"), "renderGuidanceStatus").contains("requireNotNull(target).renderGuidanceStatus("))
        val build = member(source, "buildContentView")
        assertTrue(build.contains("destinationSearchPresenter.bindPanel(destinationPanel)"))
        assertTrue(member(ui("DestinationGuidancePanel"), "renderGuidanceStatus").contains("nativeGuidanceStatusText.text = message"))
        val visible = member(source, "nativeGuidanceStatusMessage")
        assertTrue(visible.contains("NativeGuidanceTextPresentation.guidanceStatus("))
        assertTrue(visible.contains("currentSafetyStopSummary = { currentOwnedWalkRuntimeSafetyStopStatus()?.userFacingSummary() }"))
        val presentation = member(ui("NativeGuidanceTextPresentation"), "guidanceStatus")
        val stopped = presentation.indexOf("currentSafetyStopSummary()?.let")
        val ordinary = presentation.indexOf("return listOfNotNull(")
        assertTrue(stopped >= 0 && ordinary > stopped)
        val owner = delegatedOwner("currentOwnedWalkRuntimeSafetyStopStatus", "walkStopDelivery", "session/safety/WalkRuntimeStopDeliveryController.kt", "currentOwnedWalkRuntimeSafetyStopStatus", "{ return walkStopDelivery.currentOwnedWalkRuntimeSafetyStopStatus() }")
        assertTrue(owner.contains("walk.state != WalkSessionState.SAFE_STOP"))
        assertTrue(owner.contains("walkRuntimeSafetyStopCleanupEpoch != status.stop.epoch"))
        assertTrue(owner.contains("ownsStoppedSession(it.stop.epoch, walk) == true"))
    }

    // Structural wiring and owner policy are checked separately; this does not execute Main.
    private fun delegatedOwner(name: String, owner: String, path: String, target: String, expectedBody: String): String {
        val caller = MainActivityRefactorSource.factory(source, name)
        // Match the whole caller, including additional effects; a guarded call or lambda is different.
        val declaration = caller.filterNot(Char::isWhitespace)
        val body = Regex.escape(expectedBody.filterNot(Char::isWhitespace))
        val expected = Regex("""^(?:private|internal|public|override)*fun$name\([^{}]*\)(?::[\w?<>]+)?$body$""")
        assertTrue("Main caller changed for $owner.$target: $caller", expected.matches(declaration))
        assertTrue(expectedBody.contains("$owner.$target("))
        MainActivityRefactorSource.composition(source, owner)
        return MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), target,
        )
    }
}
