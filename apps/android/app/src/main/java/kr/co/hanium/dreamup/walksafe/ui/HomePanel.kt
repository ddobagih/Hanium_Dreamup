package kr.co.hanium.dreamup.walksafe.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_NOTICE_TEXT
import kotlin.math.roundToInt

/** Home views and presentation; feature availability and navigation belong to the caller. */
internal class HomePanel(
    private val context: Context,
    private val style: WalkSafeViewStyle,
    props: Props,
    actions: Actions,
) {
    data class Props(
        val showCameraTest: Boolean,
        val initialRouteStatus: String,
    )

    data class Actions(
        val onCamera: () -> Unit,
        val onVoice: () -> Unit,
        val onDestinationSearch: () -> Unit,
        val onSettings: () -> Unit,
        val onHelp: () -> Unit = {},
    )

    private fun homeCard(title: String, onOpen: () -> Unit): Button = Button(context).apply {
        text = title
        style.applyWsSecondaryButtonStyle(this)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { onOpen() }
    }

    private val cameraCard = homeCard("카메라 테스트", actions.onCamera)
    private val voiceCard = homeCard("음성 명령", actions.onVoice)
    private val destinationCard = homeCard("길라잡이", actions.onDestinationSearch)
    private val settingsCard = homeCard("설정", actions.onSettings)

    /** 저시력 사용자가 한 줄씩 훑을 수 있도록 바로가기를 세로 1열로 둔다. */
    val homeCardGrid = LinearLayout(context).apply {
        val gap = (16f * context.resources.displayMetrics.density).roundToInt()
        val cards = listOfNotNull(
            homeCard("도움말", actions.onHelp),
            cameraCard.takeIf { props.showCameraTest },
            voiceCard,
            destinationCard,
            settingsCard,
        )
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        cards.forEachIndexed { index, card ->
            addView(
                card,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
                ).apply { if (index > 0) topMargin = gap },
            )
        }
    }

    val nativeHomeRestrictionText = TextView(context).apply {
        text = style.nativePanelMessage(
            "앱 내의 기능 사용 제한",
            "기기 장착 상태와 기기 상태를 확인해주세요.",
        )
        visibility = View.GONE
        style.applyNativeNoticeStyle(this, "warning")
    }

    val nativeHomeCompassText = TextView(context).apply {
        text = "나침반 방향을 준비하고 있습니다."
        textSize = 18f
        setTextColor(WS_COLOR_NOTICE_TEXT)
        setPadding(16, 12, 16, 12)
        // Direction changes must not interrupt route speech or TalkBack every half second.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        visibility = View.GONE
    }

    val nativeHomeRouteStatusText = TextView(context).apply {
        text = props.initialRouteStatus
        textSize = 18f
        setTextColor(WS_COLOR_NOTICE_TEXT)
        setPadding(16, 0, 16, 12)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        visibility = View.GONE
    }

    fun renderAvailability(
        runtimeActive: Boolean,
        voiceAvailable: Boolean,
        searchAvailable: Boolean,
        cameraHandoffPending: Boolean = false,
    ) {
        renderCardAvailability(
            cameraCard,
            runtimeActive,
            available = true,
            title = "카메라 테스트",
            subtitle = "안내 시작 없이 객체 인식과 깊이를 확인합니다",
            preparing = cameraHandoffPending,
        )
        renderCardAvailability(
            voiceCard,
            runtimeActive,
            available = voiceAvailable,
            title = "음성 명령",
            subtitle = "음성으로 앱 기능을 사용합니다",
            preparing = cameraHandoffPending,
            lockTitle = "앱 내의 기능 사용 제한",
            lockDetail = "기기 장착 상태와 기기 상태를 확인해주세요.",
        )
        renderCardAvailability(
            destinationCard,
            runtimeActive,
            available = searchAvailable,
            title = "길라잡이",
            subtitle = "목적지를 찾고 경로 안내를 시작합니다",
            preparing = cameraHandoffPending,
            lockTitle = "앱 내의 기능 사용 제한",
            lockDetail = "기기 장착 상태와 기기 상태를 확인해주세요.",
        )
        renderCardAvailability(
            settingsCard,
            runtimeActive,
            available = true,
            title = "설정",
            subtitle = "개인정보 동의와 계정을 관리합니다",
        )
    }

    private fun renderCardAvailability(
        card: Button,
        runtimeActive: Boolean,
        available: Boolean,
        title: String,
        subtitle: String,
        lockTitle: String = "",
        lockDetail: String = "",
        preparing: Boolean = false,
    ) {
        val cardEnabled = !preparing && (!runtimeActive || available)
        card.isEnabled = cardEnabled
        card.contentDescription = if (preparing) {
            "$title. 홈으로 돌아오는 중입니다. 잠시 기다려 주세요."
        } else if (cardEnabled) {
            "$title. $subtitle"
        } else {
            "$title. $subtitle. 잠김. $lockTitle. $lockDetail"
        }
    }
}
