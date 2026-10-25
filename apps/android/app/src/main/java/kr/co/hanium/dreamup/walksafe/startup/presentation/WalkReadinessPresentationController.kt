package kr.co.hanium.dreamup.walksafe.startup.presentation

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.ui.*

import android.view.View

/** The expanded UI preference is owned here; no readiness or sensor state is copied. */
internal class WalkReadinessPresentationController(
    private val facts: WalkReadinessPresentationFacts,
    private val view: WalkReadinessPresentationView,
) {
    var expanded = true

    fun updateWalkReadinessSection() {
        if (
            !facts.published(NativeUiPublication.Target.WALK_READINESS_CONTROLS) ||
            !facts.published(NativeUiPublication.Target.WALK_READINESS_SUMMARY_TEXT) ||
            !facts.published(NativeUiPublication.Target.WALK_READINESS_TOGGLE_BUTTON)
        ) return
        val walkState = facts.walkStateOrReady()
        if (walkState == WalkSessionState.ACTIVE) {
            view.controlsVisibility = View.GONE
            view.summaryVisibility = View.GONE
            view.toggleVisibility = View.GONE
            return
        }
        if (view.controlsVisibility == View.GONE) {
            view.summaryVisibility = View.GONE
            view.toggleVisibility = View.GONE
            return
        }
        val everythingPassed =
            walkState != WalkSessionState.PAUSED &&
                facts.complete() &&
                facts.devicePassesFeatureGate() &&
                facts.capabilityMayStart() &&
                facts.capabilityConfirmed()
        if (!everythingPassed) {
            view.controlsVisibility = View.VISIBLE
            view.summaryVisibility = View.GONE
            view.toggleVisibility = View.GONE
            return
        }
        view.toggleVisibility = View.VISIBLE
        view.toggleText =
            if (expanded) "기기 준비 상태 접기" else "기기 준비 상태 펼치기"
        view.toggleDescription =
            if (expanded) {
                "기기 준비 상태, 펼침. 두 번 탭하여 접기"
            } else {
                "기기 준비 상태, 접힘. 두 번 탭하여 펼치기"
            }
        view.controlsVisibility =
            if (expanded) View.VISIBLE else View.GONE
        view.summaryVisibility =
            if (expanded) View.GONE else View.VISIBLE
        val summary = if (facts.hasCurrentRestrictions()) {
            val preparation = if (facts.cameraAnalysisEnabled()) {
                "장착과 사용 중인 기능 범위의 환경 점검을 통과했습니다."
            } else {
                "사용 중인 기능 범위의 환경 점검을 통과했고, " +
                    "제한된 카메라 장착 점검은 생략했습니다."
            }
            "기기 준비 완료. $preparation 현재 제한 기능: " +
                "${facts.disabledFeatureText()}. " +
                "나머지 기능은 사용할 수 있습니다."
        } else {
            "기기 준비 완료. 장착, 환경, 기기 점검을 모두 통과했습니다."
        }
        view.summaryText = summary
        view.summaryDescription = summary
    }
}
