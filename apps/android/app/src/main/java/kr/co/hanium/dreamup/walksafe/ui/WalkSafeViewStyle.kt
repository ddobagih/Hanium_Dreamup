package kr.co.hanium.dreamup.walksafe.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import kr.co.hanium.dreamup.walksafe.R
import kotlin.math.roundToInt

/** View presentation only. Actions and feature availability remain with the caller. */
internal class WalkSafeViewStyle(private val context: Context) {
    private val resources get() = context.resources
    private val wsEmphasisButtons = mutableListOf<Button>()

    fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    fun clearEmphasisButtons() {
        wsEmphasisButtons.clear()
    }

    /** Reapply primary actions after the caller finishes the recursive default styling. */
    fun applyEmphasisButtonStyles() {
        wsEmphasisButtons.forEach {
            applyWsButtonStyle(it, WS_TOUCH_PRIMARY_DP, primary = true)
        }
    }

    fun accessibilityTargetSizePx(): Int =
        (WS_TOUCH_MIN_DP * resources.displayMetrics.density).roundToInt()

    fun applyAccessibleControlDefaults(root: View) {
        if (root is Button && root !is CompoundButton) {
            applyWsButtonStyle(root, WS_TOUCH_WALK_ACTION_DP)
        }
        if (root is EditText) applyWsFieldStyle(root)
        if (root is LinearLayout && root.orientation == LinearLayout.VERTICAL) {
            applyWsStackSpacing(root)
        }
        if (root is CompoundButton) {
            root.buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(WS_COLOR_PRIMARY_ACTION_FILL, WS_COLOR_BUTTON_BORDER),
            )
            root.setTextColor(WS_COLOR_BUTTON_TEXT)
        }
        if (root is Button || root is EditText || root is CheckBox) {
            val targetSize = accessibilityTargetSizePx()
            root.minimumHeight = maxOf(root.minimumHeight, targetSize)
            root.minimumWidth = maxOf(root.minimumWidth, targetSize)
            val minimumTextSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                MIN_INTERACTIVE_TEXT_SP,
                resources.displayMetrics,
            )
            if (root.textSize < minimumTextSizePx) {
                root.setTextSize(TypedValue.COMPLEX_UNIT_SP, MIN_INTERACTIVE_TEXT_SP)
            }
            root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                applyAccessibleControlDefaults(root.getChildAt(index))
            }
        }
    }

    fun applyWsStackSpacing(stack: LinearLayout) {
        if (stack.childCount < 2) return
        val children = (0 until stack.childCount).map(stack::getChildAt)
        val gap = (WS_GROUP_GAP_DP * resources.displayMetrics.density).roundToInt()
        children.dropLast(1).forEach { child ->
            (child.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                if (params.bottomMargin == 0) params.bottomMargin = gap
            }
        }
    }

    fun applyWsSecondaryButtonStyle(button: Button) {
        applyWsButtonStyle(button, 144f)
    }

    fun applyWsStatusText(view: TextView, message: String) {
        val lines = message.split("\n")
        val causeIndex = lines.indexOfFirst { it.startsWith("원인:") }
        val statusIndex = if (causeIndex > 0) causeIndex - 1 else -1
        val builder = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            val start = builder.length
            builder.append(line)
            val end = builder.length
            val labelEnd = LABEL_PREFIXES.firstOrNull { line.startsWith(it) }?.length
            when {
                index == statusIndex -> {
                    builder.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    builder.setSpan(
                        ForegroundColorSpan(
                            if (line in BLOCKING_STATUS_WORDS) {
                                WS_COLOR_WARNING
                            } else {
                                WS_COLOR_EMPHASIS
                            },
                        ),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    builder.setSpan(
                        RelativeSizeSpan(1.15f),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                labelEnd != null -> {
                    builder.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        start + labelEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    builder.setSpan(
                        ForegroundColorSpan(WS_COLOR_BUTTON_TEXT),
                        start + labelEnd,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                else -> builder.setSpan(
                    RelativeSizeSpan(0.94f),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (index != lines.lastIndex) builder.append("\n")
        }
        view.text = builder
    }

    fun wsHomeCardFace(
        fill: Int,
        density: Float,
        focused: Boolean = false,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = WS_CARD_CORNER_RADIUS_DP * density
        setColor(fill)
        if (focused) {
            setStroke((WS_CARD_FOCUS_RING_DP * density).roundToInt(), WS_COLOR_CARD_FOCUS)
        }
    }

    fun applyWsSafetyBannerStyle(button: Button) {
        val density = resources.displayMetrics.density
        button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        button.typeface = wsTypeface(Typeface.BOLD)
        button.setTextColor(WS_COLOR_SAFETY_BANNER_TEXT)
        button.setPadding(
            (WS_SAFETY_BANNER_PAD_H_DP * density).roundToInt(),
            (WS_SAFETY_BANNER_PAD_V_DP * density).roundToInt(),
            (WS_SAFETY_BANNER_PAD_H_DP * density).roundToInt(),
            (WS_SAFETY_BANNER_PAD_V_DP * density).roundToInt(),
        )
        button.background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                wsSafetyBannerFace(density, focused = true),
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                wsSafetyBannerFace(density, pressed = true),
            )
            addState(intArrayOf(), wsSafetyBannerFace(density))
        }
    }

    fun wsSafetyBannerFace(
        density: Float,
        focused: Boolean = false,
        pressed: Boolean = false,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = WS_CORNER_RADIUS_DP * density
        setColor(if (pressed) WS_COLOR_BUTTON_PRESSED_FILL else WS_COLOR_SAFETY_BANNER_FILL)
        setStroke(
            ((if (focused) WS_FOCUS_BORDER_DP else WS_SAFETY_BANNER_BORDER_DP) * density)
                .roundToInt(),
            if (focused) WS_COLOR_FOCUS else WS_COLOR_SAFETY_BANNER_BORDER,
        )
    }

    fun applyWsSafetyNoticeBody(view: TextView) {
        applyWsStatusCard(view)
        view.setTextColor(WS_COLOR_EMPHASIS)
    }

    fun applyNativePanelStyle(view: View) {
        val density = resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            cornerRadius = 16f * density
            setColor(WS_COLOR_NOTICE_FILL)
            setStroke(density.roundToInt().coerceAtLeast(1), WS_COLOR_LINE)
        }
        view.setPadding(
            (16f * density).roundToInt(), (20f * density).roundToInt(),
            (16f * density).roundToInt(), (20f * density).roundToInt(),
        )
    }

    fun applyNativeNoticeStyle(view: TextView, tone: String = "info") {
        val colors = when (tone) {
            "error" -> 0xfffff0ea.toInt() to 0xff942a16.toInt()
            "warning" -> 0xfffef9ee.toInt() to 0xff704c00.toInt()
            "success" -> 0xffedf4e9.toInt() to 0xff2f5d3a.toInt()
            else -> 0xffedf1fd.toInt() to 0xff173ea9.toInt()
        }
        applyWsStatusCard(view)
        (view.background as GradientDrawable).apply {
            setColor(colors.first)
            setStroke(0, colors.first)
        }
        view.setTextColor(colors.second)
    }

    fun nativePanelMessage(title: String, body: String): CharSequence =
        SpannableStringBuilder("$title\n\n$body").apply {
            setSpan(
                android.text.style.AbsoluteSizeSpan(22, true), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setSpan(
                    android.text.style.TypefaceSpan(wsTypeface(Typeface.NORMAL, medium = true) ?: Typeface.DEFAULT),
                    0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

    fun applyNativeDangerButtonStyle(button: Button) {
        applyWsButtonStyle(button, 144f, primary = true)
        button.backgroundTintList = ColorStateList.valueOf(0xffb3341a.toInt())
    }

    fun spaceNativePreviewStack(group: LinearLayout, sectionGap: Float = 24f) {
        val visible = (0 until group.childCount).map(group::getChildAt)
            .filter { it.visibility != View.GONE }
        visible.forEachIndexed { index, child ->
            val params = child.layoutParams as? LinearLayout.LayoutParams ?: return@forEachIndexed
            val next = visible.getOrNull(index + 1)
            val gap = when {
                next == null -> 0f
                child is Button && next is Button -> 12f
                else -> sectionGap
            }
            val bottom = (gap * resources.displayMetrics.density).roundToInt()
            if (params.bottomMargin != bottom) {
                params.bottomMargin = bottom
                child.layoutParams = params
            }
        }
    }

    fun buildAccountLandingHeader(): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (WS_SECTION_GAP_DP * density).roundToInt() }
            addView(
                TextView(context).apply {
                    text = "WALKSAFE  |  계정"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(WS_COLOR_NOTICE_TEXT)
                    typeface = wsTypeface(Typeface.BOLD)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
            )
            addView(
                TextView(context).apply {
                    text = "안전한 보행을\n준비합니다"
                    contentDescription = "안전한 보행을 준비합니다"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, WS_TEXT_LANDING_TITLE_SP)
                    setTextColor(WS_COLOR_EMPHASIS)
                    typeface = wsTypeface(Typeface.BOLD)
                    setLineSpacing(0f, 1.15f)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    ViewCompat.setAccessibilityHeading(this, true)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (WS_GROUP_GAP_DP * density).roundToInt() }
                },
            )
        }
    }

    fun wsFieldGroupOf(input: EditText): View = input.parent as? View ?: input

    fun wsFieldGroup(
        input: EditText,
        label: String,
        hint: String? = null,
    ): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 0 }
            addView(
                TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, WS_TEXT_FIELD_LABEL_SP)
                    setTextColor(WS_COLOR_EMPHASIS)
                    typeface = wsTypeface(Typeface.NORMAL)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = (WS_FIELD_LABEL_GAP_DP * density).roundToInt() }
                },
            )
            addView(input)
            hint?.let { hintText ->
                addView(
                    TextView(context).apply {
                        text = hintText
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, WS_TEXT_FIELD_HINT_SP)
                        setTextColor(WS_COLOR_NOTICE_TEXT)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = (WS_FIELD_LABEL_GAP_DP * density).roundToInt() }
                    },
                )
            }
        }
    }

    fun applyWsStatusCard(view: TextView) {
        applyNativePanelStyle(view)
        view.textSize = 18f
        view.typeface = wsTypeface(Typeface.NORMAL)
        view.setTextColor(WS_COLOR_NOTICE_TEXT)
        view.setLineSpacing(0f, 1.65f)
    }

    fun applyWsFieldStyle(field: EditText) {
        val density = resources.displayMetrics.density
        field.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = WS_CORNER_RADIUS_DP * density
            setColor(WS_COLOR_NOTICE_FILL)
            setStroke((WS_BUTTON_BORDER_DP * density).roundToInt(), WS_COLOR_BUTTON_BORDER)
        }
        field.setTextColor(WS_COLOR_BUTTON_TEXT)
        field.setHintTextColor(WS_COLOR_NOTICE_TEXT)
        field.textSize = 20f
        field.minimumHeight = maxOf(
            field.minimumHeight,
            (72f * density).roundToInt(),
        )
        field.setPadding(
            (20f * density).roundToInt(),
            (16f * density).roundToInt(),
            (20f * density).roundToInt(),
            (16f * density).roundToInt(),
        )
    }

    fun wsTypeface(style: Int, medium: Boolean = false): Typeface? =
        ResourcesCompat.getFont(
            context,
            when {
                style == Typeface.BOLD -> R.font.pretendard_bold
                medium -> R.font.pretendard_medium
                else -> R.font.pretendard_regular
            },
        )

    fun wsButtonFace(
        fill: Int,
        density: Float,
        outlined: Boolean = false,
        focused: Boolean = false,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = WS_CORNER_RADIUS_DP * density
        setColor(fill)
        when {
            focused -> setStroke((WS_FOCUS_BORDER_DP * density).roundToInt(), WS_COLOR_FOCUS)
            outlined -> setStroke(
                (WS_BUTTON_BORDER_DP * density).roundToInt(),
                WS_COLOR_BUTTON_BORDER,
            )
        }
    }

    fun applyWsButtonStyle(
        button: Button,
        minHeightDp: Float,
        primary: Boolean = false,
    ) {
        val density = resources.displayMetrics.density
        val restingFill = if (primary) WS_COLOR_PRIMARY_ACTION_FILL else WS_COLOR_BUTTON_FILL
        val restingText = if (primary) WS_COLOR_PRIMARY_ACTION_TEXT else WS_COLOR_PRIMARY_ACTION_FILL
        fun face(fill: Int, focused: Boolean = false): GradientDrawable =
            wsButtonFace(fill, density, outlined = !primary, focused = focused).apply {
                if (!primary && !focused) {
                    setStroke((density).roundToInt().coerceAtLeast(1), WS_COLOR_PRIMARY_ACTION_FILL)
                }
            }
        button.isAllCaps = false
        button.setSingleLine(false)
        button.ellipsize = null
        button.minimumHeight = maxOf(button.minimumHeight, (maxOf(minHeightDp, 144f) * density).roundToInt())
        button.textSize = 22f
        button.typeface = wsTypeface(Typeface.NORMAL, medium = true)
        button.gravity = Gravity.CENTER
        button.stateListAnimator = null
        button.elevation = 0f
        button.backgroundTintList = null
        button.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), face(WS_COLOR_BUTTON_DISABLED_FILL))
            addState(
                intArrayOf(android.R.attr.state_pressed),
                face(if (primary) WS_COLOR_PRIMARY_ACTION_PRESSED_FILL else WS_COLOR_BUTTON_PRESSED_FILL),
            )
            addState(intArrayOf(android.R.attr.state_focused), face(restingFill, focused = true))
            addState(intArrayOf(), face(restingFill))
        }
        button.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(
                    WS_COLOR_BUTTON_DISABLED_TEXT,
                    restingText,
                ),
            ),
        )
        button.setPadding(
            (20f * density).roundToInt(), (16f * density).roundToInt(),
            (20f * density).roundToInt(), (16f * density).roundToInt(),
        )
    }

    fun walkDivider(): View {
        val density = resources.displayMetrics.density
        return View(context).apply {
            setBackgroundColor(WS_COLOR_LINE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxOf(1, density.roundToInt()),
            )
        }
    }

    fun walkSection(label: String?, vararg children: View): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 0
                bottomMargin = 0
            }
            label?.let { sectionLabel ->
                addView(
                    TextView(context).apply {
                        text = sectionLabel
                        textSize = 26f
                        setTextColor(WS_COLOR_EMPHASIS)
                        typeface = wsTypeface(Typeface.NORMAL, medium = true)
                        letterSpacing = 0f
                        ViewCompat.setAccessibilityHeading(this, true)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            bottomMargin = (24f * density).roundToInt()
                        }
                    },
                )
            }
            children.forEachIndexed { index, child ->
                val bottomMargin = if (index == children.lastIndex) {
                    0
                } else {
                    (WS_GROUP_GAP_DP * density).roundToInt()
                }
                child.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    this.bottomMargin = bottomMargin
                }
                addView(child)
            }
        }
    }

    fun walkTwoColumnRow(left: View, right: View): LinearLayout {
        val gap = (WS_GROUP_GAP_DP * resources.displayMetrics.density).roundToInt()
        val configuration = resources.configuration
        val stacked = configuration.screenWidthDp < 600 || configuration.fontScale >= 1.3f
        return LinearLayout(context).apply {
            orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(
                left,
                LinearLayout.LayoutParams(
                    if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (stacked) 0f else 1f,
                ).apply {
                    if (stacked) bottomMargin = gap else rightMargin = gap / 2
                },
            )
            addView(
                right,
                LinearLayout.LayoutParams(
                    if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (stacked) 0f else 1f,
                ).apply {
                    if (!stacked) leftMargin = gap / 2
                },
            )
        }
    }

    fun applyWsClauseBox(view: TextView) {
        view.background = null
        view.setPadding(0, 0, 0, 0)
        view.textSize = 18f
        view.typeface = wsTypeface(Typeface.NORMAL)
        view.setTextColor(WS_COLOR_NOTICE_TEXT)
        view.setLineSpacing(0f, 1.65f)
        view.maxHeight = Int.MAX_VALUE
        view.isVerticalScrollBarEnabled = false
        view.movementMethod = null
        view.isClickable = false
        view.isLongClickable = false
        view.setOnTouchListener(null)
    }

    fun accessiblePriorityUserButton(
        label: String,
        emphasis: Boolean = false,
        spokenLabel: String? = null,
        onClick: () -> Unit,
    ) = Button(context).apply {
        id = View.generateViewId()
        text = label
        // 화면 라벨은 짧게 두되 TalkBack 은 맥락을 잃지 않는다.
        contentDescription = spokenLabel ?: label
        isAllCaps = false
        setSingleLine(false)
        ellipsize = null
        minimumHeight = (WS_TOUCH_MIN_DP * resources.displayMetrics.density).roundToInt()
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        val density = resources.displayMetrics.density
        val states = arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(),
        )
        textSize = 18f
        backgroundTintList = ColorStateList(
            states,
            intArrayOf(
                WS_COLOR_BUTTON_DISABLED_FILL,
                WS_COLOR_BUTTON_PRESSED_FILL,
                WS_COLOR_BUTTON_FOCUSED_FILL,
                WS_COLOR_BUTTON_FILL,
            ),
        )
        setTextColor(
            ColorStateList(
                states,
                intArrayOf(
                    WS_COLOR_BUTTON_DISABLED_TEXT,
                    WS_COLOR_BUTTON_TEXT,
                    WS_COLOR_BUTTON_TEXT,
                    WS_COLOR_BUTTON_TEXT,
                ),
            ),
        )
        if (emphasis) {
            // 트리 전체에 기본 마감을 적용한 뒤 주 행동 스타일을 다시 입힌다.
            minimumHeight = (WS_TOUCH_PRIMARY_DP * density).roundToInt()
            wsEmphasisButtons += this
        }
        setPadding(
            (20f * density).roundToInt(),
            (12f * density).roundToInt(),
            (20f * density).roundToInt(),
            (12f * density).roundToInt(),
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = (WS_GROUP_GAP_DP * density).roundToInt()
        }
        setOnClickListener { onClick() }
    }

    companion object {
        const val WS_COLOR_BUTTON_FILL = 0xffffffff.toInt()
        const val WS_COLOR_BUTTON_TEXT = 0xff1b1b1d.toInt()
        const val WS_COLOR_BUTTON_PRESSED_FILL = 0xffeef2ff.toInt()
        const val WS_COLOR_BUTTON_FOCUSED_FILL = 0xff1b4cd8.toInt()
        const val WS_COLOR_BUTTON_DISABLED_FILL = 0xffe6e5e3.toInt()
        const val WS_COLOR_BUTTON_DISABLED_TEXT = 0xff747477.toInt()
        const val WS_COLOR_NOTICE_TEXT = 0xff5c5c64.toInt()
        const val WS_COLOR_NOTICE_FILL = 0xffffffff.toInt()
        const val WS_COLOR_LINE = 0xffdedbd5.toInt()
        const val WS_COLOR_BUTTON_BORDER = 0xff77747a.toInt()
        const val WS_COLOR_GROUND = 0xfffaf9f7.toInt()
        const val WS_COLOR_FOCUS = 0xff1b4cd8.toInt()
        const val WS_BUTTON_BORDER_DP = 1f
        const val WS_FOCUS_BORDER_DP = 3f
        const val SAFETY_NOTICE_HEADING = "안전 고지"
        const val WS_COLOR_EMPHASIS = 0xff1b1b1d.toInt()
        const val WS_COLOR_WARNING = 0xffc0340e.toInt()
        const val WS_COLOR_PRIMARY_ACTION_FILL = 0xff1b4cd8.toInt()
        const val WS_COLOR_PRIMARY_ACTION_TEXT = 0xffffffff.toInt()
        const val WS_COLOR_PRIMARY_ACTION_PRESSED_FILL = 0xff153ba8.toInt()
        const val WS_COLOR_PRIMARY_ACTION_DISABLED_FILL = 0xffd8dded.toInt()
        const val WS_COLOR_PRIMARY_ACTION_DISABLED_TEXT = 0xff656a7a.toInt()
        const val WS_TOUCH_PRIMARY_DP = 88f
        const val WS_TOUCH_WALK_ACTION_DP = 72f
        const val WS_TOUCH_MIN_DP = 64f
        const val WS_TOUCH_WALK_PRIMARY_DP = 88f
        const val WS_CORNER_RADIUS_DP = 16f
        const val WS_CLAUSE_BOX_MAX_HEIGHT_DP = 132f
        const val WS_TEXT_FIELD_LABEL_SP = 20f
        const val WS_TEXT_FIELD_HINT_SP = 18f
        const val WS_FIELD_LABEL_GAP_DP = 8f
        const val WS_TEXT_LANDING_TITLE_SP = 36f
        const val WS_COLOR_SAFETY_BANNER_FILL = 0xfffff0cf.toInt()
        const val WS_COLOR_SAFETY_BANNER_BORDER = 0xff8a5700.toInt()
        const val WS_COLOR_SAFETY_BANNER_TEXT = 0xff5f3b00.toInt()
        const val WS_SAFETY_BANNER_BORDER_DP = 1.5f
        const val WS_SAFETY_BANNER_PAD_H_DP = 16f
        const val WS_SAFETY_BANNER_PAD_V_DP = 14f
        const val WS_COLOR_CARD_NAV = 0xff1b4cd8.toInt()
        const val WS_COLOR_CARD_VOICE = 0xff1b1b1d.toInt()
        const val WS_COLOR_CARD_REPORT = 0xffc0340e.toInt()
        const val WS_COLOR_CARD_TEXT = 0xffffffff.toInt()
        const val WS_COLOR_CARD_FOCUS = 0xffffffff.toInt()
        const val WS_CARD_HEIGHT_DP = 112f
        const val WS_CARD_PADDING_DP = 20f
        const val WS_CARD_CORNER_RADIUS_DP = 16f
        const val WS_CARD_GAP_DP = 12f
        const val WS_CARD_FOCUS_RING_DP = 4f
        const val WS_TEXT_CARD_TITLE_SP = 24f
        const val WS_SECTION_GAP_DP = 24f
        const val WS_GROUP_GAP_DP = 12f
        const val WS_TITLE_GAP_DP = 16f
        const val WS_CONTROL_GAP_DP = 12f
        const val MIN_INTERACTIVE_TEXT_SP = 18f
        val LABEL_PREFIXES = listOf("원인:", "다음 행동:", "확인 완료:", "기기 점검:")
        val BLOCKING_STATUS_WORDS = setOf("사용 불가", "제한", "교정 필요")
        const val WS_COLOR_OVERLAY_FILL = 0xfafaf9f7.toInt()
        const val WS_COLOR_WALK_OVERLAY_FILL = 0xf2faf9f7.toInt()
    }
}
