package kr.co.hanium.dreamup.walksafe.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.TextView
import androidx.core.view.doOnPreDraw

/** Android presentation only; ReportCandidateController retains authority and confirmation deadlines. */
internal class AndroidExplicitReportPresentation(
    private val targets: () -> Target?,
    private val isMainThread: () -> Boolean,
    private val dispatch: (() -> Unit) -> Unit,
    private val queueAvailable: () -> Boolean,
    private val disclosureText: String,
    private val summary: String,
    private val refreshHomeCards: () -> Unit,
    private val drawTargets: () -> DrawTarget? = targets,
) {
    private var cancelPendingDraw: (() -> Unit)? = null

    data class Presentation(
        val visible: Boolean,
        val disclosure: String,
        val enabled: Boolean,
        val buttonText: String,
        val buttonDescription: String,
    )
    interface DrawObserver {
        val alive: Boolean
        fun add(listener: () -> Unit)
        fun remove(listener: () -> Unit)
    }
    interface Target : DrawTarget {
        fun render(presentation: Presentation)
    }
    interface DrawTarget {
        val attached: Boolean
        val current: Boolean
        val visible: Boolean
        val observer: DrawObserver
        fun post(action: () -> Unit)
        fun reveal(isCurrent: () -> Boolean): () -> Unit
        fun invalidate()
    }

    fun deliverAfterDraw(onDelivered: () -> Unit): Boolean {
        cancelPendingDraw?.invoke()
        val target = drawTargets() ?: return false
        if (!target.attached || !target.current) return false
        val observer = target.observer
        if (!observer.alive) return false
        var cancelled = false
        var deliveryPosted = false
        var cancelReveal: (() -> Unit)? = null
        lateinit var listener: () -> Unit
        lateinit var cancel: () -> Unit
        cancel = {
            if (!cancelled) {
                cancelled = true
                cancelReveal?.invoke()
                if (observer.alive) observer.remove(listener)
                if (cancelPendingDraw === cancel) cancelPendingDraw = null
            }
        }
        listener = {
            if (!cancelled && !deliveryPosted &&
                (!observer.alive || !target.attached || !target.current || target.visible)) {
                deliveryPosted = true
                target.post {
                    val delivered = !cancelled && observer.alive && target.attached &&
                        target.current && target.visible
                    cancel()
                    if (delivered) onDelivered()
                }
            }
        }
        cancelPendingDraw = cancel
        observer.add(listener)
        cancelReveal = target.reveal { !cancelled && observer.alive && target.attached && target.current }
        target.invalidate()
        return true
    }

    fun renderDisclosure(armed: Boolean) {
        if (!isMainThread()) {
            dispatch { renderDisclosure(armed) }
            return
        }
        cancelPendingDraw?.invoke()
        val target = targets() ?: return
        val action = if (armed) {
            "30초 안에 아래 ‘방금 고정한 신고 보내기’를 누르면 이 건만 저장합니다."
        } else {
            "음성 또는 접근성 안내가 끝나면 별도 보내기 동작이 열립니다."
        }
        target.render(Presentation(
            visible = true,
            disclosure = "$disclosureText $action",
            enabled = armed,
            buttonText = if (armed) "방금 고정한 신고 보내기" else "신고 전송 안내 확인 중",
            buttonDescription = if (armed) "$summary 방금 고정한 신고 보내기"
                else "$summary 안내가 끝날 때까지 기다려 주세요.",
        ))
        refreshHomeCards()
    }

    fun renderIdle(message: String? = null) {
        if (!isMainThread()) {
            dispatch { renderIdle(message) }
            return
        }
        cancelPendingDraw?.invoke()
        val target = targets() ?: return
        val available = queueAvailable()
        target.render(Presentation(
            visible = message != null,
            disclosure = message.orEmpty(),
            enabled = available,
            buttonText = if (available) "손상 점자블록 직접 신고" else "손상 점자블록 신고 저장 기능 준비 중",
            buttonDescription = if (available) {
                "손상 점자블록 직접 신고. 누르면 신고 사진과 정확한 위치 전송 내용을 확인합니다."
            } else {
                "현재 앱 빌드에서는 손상 점자블록 신고 저장 기능을 사용할 수 없습니다."
            },
        ))
        refreshHomeCards()
    }

    class ViewDrawTarget(
        private val text: TextView,
        private val isCurrentScreen: () -> Boolean,
    ) : DrawTarget {
        override val attached get() = text.isAttachedToWindow
        override val current get() = isCurrentScreen() && text.isShown && text.windowVisibility == View.VISIBLE
        override val visible get(): Boolean {
            val visibleBounds = Rect()
            return current && text.getGlobalVisibleRect(visibleBounds) && !visibleBounds.isEmpty
        }
        override val observer: DrawObserver get() {
            val observer = text.viewTreeObserver
            return object : DrawObserver {
                private val listeners = mutableMapOf<() -> Unit, ViewTreeObserver.OnDrawListener>()
                override val alive get() = observer.isAlive
                override fun add(listener: () -> Unit) {
                    val native = ViewTreeObserver.OnDrawListener { listener() }
                    listeners[listener] = native
                    observer.addOnDrawListener(native)
                }
                override fun remove(listener: () -> Unit) {
                    listeners.remove(listener)?.let(observer::removeOnDrawListener)
                }
            }
        }
        override fun post(action: () -> Unit) { text.post { action() } }
        override fun reveal(isCurrent: () -> Boolean): () -> Unit {
            val pending = text.doOnPreDraw { target ->
                if (isCurrent() && current && target.width > 0 && target.height > 0) {
                    target.requestRectangleOnScreen(
                        Rect(0, 0, target.width, text.lineHeight.coerceAtMost(target.height)), true,
                    )
                }
            }
            return pending::removeListener
        }
        override fun invalidate() { text.invalidate() }
    }

    class ViewTarget(
        private val button: Button,
        private val text: TextView,
        isCurrentScreen: () -> Boolean,
    ) : Target, DrawTarget by ViewDrawTarget(text, isCurrentScreen) {
        override fun render(presentation: Presentation) {
            text.visibility = if (presentation.visible) View.VISIBLE else View.GONE
            text.text = presentation.disclosure
            text.contentDescription = presentation.disclosure
            button.isEnabled = presentation.enabled
            button.text = presentation.buttonText
            button.contentDescription = presentation.buttonDescription
        }
    }
}
