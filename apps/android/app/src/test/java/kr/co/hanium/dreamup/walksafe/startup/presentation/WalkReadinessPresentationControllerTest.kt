package kr.co.hanium.dreamup.walksafe.startup.presentation

import android.view.View
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.ui.NativeUiPublication
import org.junit.Assert.*
import org.junit.Test

class WalkReadinessPresentationControllerTest {
    @Test fun publicationGuardDoesNotReadWalkOrViewsAndConstructionIsPure() {
        val f = Fixture(); assertTrue(f.events.isEmpty()); f.published = false
        f.controller.updateWalkReadinessSection()
        assertEquals(listOf("publication"), f.events)
        assertTrue(f.controller.expanded)
    }
    @Test fun activeAndHiddenControlsStopBeforeReadinessQueries() {
        val f = Fixture(); f.state = WalkSessionState.ACTIVE
        f.controller.updateWalkReadinessSection()
        assertEquals(View.GONE, f.controls); assertEquals(View.GONE, f.summary); assertEquals(View.GONE, f.toggle)
        assertFalse(f.events.contains("complete")); assertFalse(f.events.contains("controls-read"))
        f.events.clear(); f.state = WalkSessionState.READY
        f.controller.updateWalkReadinessSection()
        assertTrue(f.events.contains("controls-read")); assertFalse(f.events.contains("complete"))
    }
    @Test fun pausedOrUnavailableEvidenceNeverDisplaysReadySummary() {
        val f = Fixture(); f.state = WalkSessionState.PAUSED
        f.controller.updateWalkReadinessSection()
        assertEquals(View.VISIBLE, f.controls); assertEquals(View.GONE, f.summary); assertEquals(View.GONE, f.toggle)
        assertFalse(f.events.contains("complete"))
        f.events.clear(); f.state = WalkSessionState.READY; f.passes = false
        f.controller.updateWalkReadinessSection()
        assertEquals(listOf("complete", "device"), f.events.filter { it in setOf("complete", "device", "capability", "confirmed") })
        assertEquals("", f.summaryText)
    }
    @Test fun expandedPreferenceAndRestrictedSummaryUseCurrentFlagsWithoutChangingEvidence() {
        val f = Fixture(); f.restricted = true; f.camera = false; f.controller.expanded = false
        f.controller.updateWalkReadinessSection()
        assertEquals(View.GONE, f.controls); assertEquals(View.VISIBLE, f.summary); assertEquals(View.VISIBLE, f.toggle)
        assertEquals("기기 준비 상태 펼치기", f.toggleText)
        assertTrue(f.summaryText.toString().contains("제한된 카메라 장착 점검은 생략"))
        assertTrue(f.summaryText.toString().contains("현재 제한 기능: 카메라"))
        assertEquals(f.summaryText, f.summaryDescription)
        // As in Main, the original preparation renderer publishes controls again before toggling.
        f.controls = View.VISIBLE; f.controller.expanded = true; f.camera = true
        f.controller.updateWalkReadinessSection()
        assertEquals(View.VISIBLE, f.controls); assertEquals(View.GONE, f.summary)
        assertTrue(f.summaryText.toString().contains("장착과 사용 중인 기능 범위"))
        assertEquals("기기 준비 상태, 펼침. 두 번 탭하여 접기", f.toggleDescription)
    }
    @Test fun unrestrictedSummaryDoesNotQueryCameraOrDisabledFeatures() {
        val f = Fixture(); f.controller.updateWalkReadinessSection()
        assertEquals("기기 준비 완료. 장착, 환경, 기기 점검을 모두 통과했습니다.", f.summaryText)
        assertFalse(f.events.contains("camera")); assertFalse(f.events.contains("disabled"))
        assertEquals(listOf("complete", "device", "capability", "confirmed"), f.events.filter { it in setOf("complete", "device", "capability", "confirmed") })
    }
    private class Fixture : WalkReadinessPresentationFacts, WalkReadinessPresentationView {
        val events = mutableListOf<String>(); var published = true; var state = WalkSessionState.READY
        var complete = true; var passes = true; var restricted = false; var camera = true
        var controls = View.VISIBLE; var summary = View.VISIBLE; var toggle = View.VISIBLE
        val controller = WalkReadinessPresentationController(this, this)
        override fun published(target: NativeUiPublication.Target) = published.also { events += "publication" }
        override fun walkStateOrReady() = state.also { events += "walk" }
        override fun complete() = complete.also { events += "complete" }
        override fun devicePassesFeatureGate() = passes.also { events += "device" }
        override fun capabilityMayStart() = true.also { events += "capability" }
        override fun capabilityConfirmed() = true.also { events += "confirmed" }
        override fun hasCurrentRestrictions() = restricted.also { events += "restricted" }
        override fun cameraAnalysisEnabled() = camera.also { events += "camera" }
        override fun disabledFeatureText() = "카메라".also { events += "disabled" }
        override var controlsVisibility: Int get() = controls.also { events += "controls-read" }
            set(v) { controls = v; events += "controls-write" }
        override var summaryVisibility: Int get() = summary
            set(v) { summary = v; events += "summary-write" }
        override var toggleVisibility: Int get() = toggle
            set(v) { toggle = v; events += "toggle-write" }
        override var toggleText: CharSequence = ""
        override var toggleDescription: CharSequence? = null
        override var summaryText: CharSequence = ""
        override var summaryDescription: CharSequence? = null
    }
}
