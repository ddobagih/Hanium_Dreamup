package kr.co.hanium.dreamup.walksafe.session.permission

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kr.co.hanium.dreamup.walksafe.ui.FirstRunOnboardingPresenter
import kr.co.hanium.dreamup.walksafe.ui.NativeScreenPresenter
import kr.co.hanium.dreamup.walksafe.ui.NativeUiPublication
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle

/** Uses the existing published views and the dialog's original captured permission set. */
internal class AndroidPermissionUi(
    private val activity: Activity,
    private val onboarding: () -> FirstRunOnboardingPresenter,
    private val screen: () -> NativeScreenPresenter,
    private val publication: () -> NativeUiPublication,
    private val style: () -> WalkSafeViewStyle,
    private val openSettings: () -> Unit,
    private val finishTask: () -> Unit,
) : PermissionFlowUi {
    var requiredExitDialog: AlertDialog? = null
    override fun panelReady() = publication().isPublished(NativeUiPublication.Target.PERMISSION_DENIAL_PANEL)
    override fun hidePanel() { onboarding().permissionBinding().permissionDenialPanel.visibility = View.GONE }
    override fun renderProgress(awaitingResume: Boolean) { onboarding().renderPermissionProgress(awaitingResume) }
    override fun renderDenial(message: String) { onboarding().renderPermissionDenial(message) }
    override fun renderResumeControl() {
        onboarding().readinessBinding().startupCapabilityConfirmButton.visibility = View.VISIBLE
        onboarding().readinessBinding().startupCapabilityConfirmButton.isEnabled = true
        onboarding().readinessBinding().startupCapabilityConfirmButton.text = "보행 안내 다시 시작"
        screen().searchBinding().runtimeControls.visibility = View.GONE
    }
    override fun postResumeFocus(stillAwaiting: () -> Boolean) {
        onboarding().readinessBinding().startupCapabilityConfirmButton.post {
            if (stillAwaiting() &&
                onboarding().readinessBinding().startupCapabilityConfirmButton.visibility == View.VISIBLE &&
                onboarding().readinessBinding().startupCapabilityConfirmButton.isEnabled
            ) {
                onboarding().readinessBinding().startupCapabilityConfirmButton.requestFocus()
                onboarding().readinessBinding().startupCapabilityConfirmButton.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        }
    }
    override fun requiredExitShowing() = requiredExitDialog?.isShowing == true
    override fun dismissRequiredExit() {
        requiredExitDialog?.dismiss()
        requiredExitDialog = null
    }
    override fun showRequiredExit(missing: Set<String>) {
        val density = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 24 * density
            }
        }
        content.addView(TextView(activity).apply {
            text = "앱 실행 불가"
            textSize = 26f
            setTextColor(0xff1b1b1d.toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                typeface = android.graphics.Typeface.create(typeface, 500, false)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (24 * density).toInt() }
        })
        content.addView(TextView(activity).apply {
            // Restarting is what revoked a one-time grant, so it is the one instruction that
            // cannot work here. Name the permissions and the place they are granted instead.
            text = "필수 권한이 없어 보행을 시작할 수 없습니다. 설정에서 " +
                "${missing.joinToString { PermissionExplanationText.permissionLabelKo(it) }} 권한을 허용해 주세요."
            textSize = 18f
            setTextColor(0xff1b1b1d.toInt())
            setLineSpacing(0f, 1.65f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (24 * density).toInt() }
        })
        // A walker who cannot read the screen will not find Android settings on their own, so
        // the dialog that demands them has to open them.
        content.addView(Button(activity).apply {
            text = "설정 열기"
            style().applyWsButtonStyle(this, 144f, primary = true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (12 * density).toInt() }
            setOnClickListener { openSettings() }
        })
        content.addView(Button(activity).apply {
            text = "종료"
            style().applyNativeDangerButtonStyle(this)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { finishTask() }
        })
        requiredExitDialog = AlertDialog.Builder(activity)
            .setView(content)
            .setCancelable(false)
            .create().also { dialog ->
                dialog.setOnDismissListener { requiredExitDialog = null }
                dialog.show()
                dialog.window?.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                )
            }
        }
}
