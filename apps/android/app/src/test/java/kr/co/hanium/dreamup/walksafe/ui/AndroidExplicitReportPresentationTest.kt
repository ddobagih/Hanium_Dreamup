package kr.co.hanium.dreamup.walksafe.ui

import org.junit.Assert.*
import org.junit.Test

/** Tests the actual presentation adapter; native ViewTreeObserver delivery remains an Android check. */
class AndroidExplicitReportPresentationTest {
    @Test fun missingDetachedAndDeadTargetsRefuseDeliveryWithoutRegisteringADraw() {
        val f = Fixture()
        f.present = false
        assertFalse(f.adapter.deliverAfterDraw { error("delivered") })
        f.present = true
        f.target.attached = false
        assertFalse(f.adapter.deliverAfterDraw { error("delivered") })
        f.target.attached = true
        f.target.current = false
        assertFalse(f.adapter.deliverAfterDraw { error("delivered") })
        f.target.current = true
        f.target.observer.alive = false
        assertFalse(f.adapter.deliverAfterDraw { error("delivered") })
        assertTrue(f.target.observer.listeners.isEmpty())
    }
    @Test fun repeatedDrawPostsOneTerminalAndRemovesListenerBeforeDelivery() {
        val f = Fixture()
        assertTrue(f.adapter.deliverAfterDraw { f.target.events += "delivered" })
        assertEquals(listOf("add", "reveal", "invalidate"), f.target.events)
        f.target.draw()
        f.target.draw()
        assertEquals(1, f.target.posts.size)
        assertFalse(f.target.events.contains("delivered"))
        f.target.posts.removeAt(0).invoke()
        assertEquals(listOf("remove", "delivered"), f.target.events.takeLast(2))
        assertTrue(f.target.observer.listeners.isEmpty())
    }
    @Test fun observerDeathAfterDrawCannotDeliverThePostedReceipt() {
        val f = Fixture()
        f.adapter.deliverAfterDraw { f.target.events += "delivered" }
        f.target.draw()
        f.target.observer.alive = false
        f.target.posts.removeAt(0).invoke()
        assertFalse(f.target.events.contains("delivered"))
        assertFalse(f.target.events.contains("remove"))
    }
    @Test fun clippedNoticeWaitsForAnActualVisibleDrawAfterReveal() {
        val f = Fixture()
        f.target.visible = false
        assertTrue(f.adapter.deliverAfterDraw { f.target.events += "delivered" })
        assertTrue(requireNotNull(f.target.revealGuard).invoke())
        f.target.draw()
        assertTrue(f.target.posts.isEmpty())
        assertFalse(f.target.events.contains("delivered"))
        f.target.visible = true
        f.target.draw()
        assertFalse(f.target.events.contains("delivered"))
        f.target.posts.removeAt(0).invoke()
        assertEquals(1, f.target.events.count { it == "delivered" })
        assertTrue(f.target.observer.listeners.isEmpty())
    }
    @Test fun changedScreenOrWindowFocusBeforeDrawRetiresWithoutDelivery() {
        val f = Fixture()
        f.adapter.deliverAfterDraw { f.target.events += "delivered" }
        f.target.current = false
        f.target.draw()
        f.target.posts.removeAt(0).invoke()
        assertFalse(f.target.events.contains("delivered"))
        assertFalse(requireNotNull(f.target.revealGuard).invoke())
        assertTrue(f.target.observer.listeners.isEmpty())
    }
    @Test fun detachScreenChangeAndClippingBetweenDrawAndPostCannotDeliver() {
        for (change in listOf<(FakeTarget) -> Unit>(
            { it.attached = false }, { it.current = false }, { it.visible = false },
        )) {
            val f = Fixture()
            f.adapter.deliverAfterDraw { f.target.events += "delivered" }
            f.target.draw()
            change(f.target)
            f.target.posts.removeAt(0).invoke()
            assertFalse(f.target.events.contains("delivered"))
            assertTrue(f.target.observer.listeners.isEmpty())
        }
    }
    @Test fun visibleIdleMessageCancelsBothPendingDrawAndRevealWithoutAnotherDraw() {
        val f = Fixture()
        f.adapter.deliverAfterDraw { f.target.events += "delivered" }
        f.target.draw()
        f.adapter.renderIdle("신고 전송 안내 시간이 지났습니다.")
        assertTrue(requireNotNull(f.target.presentation).visible)
        assertTrue(f.target.observer.listeners.isEmpty())
        assertFalse(requireNotNull(f.target.revealGuard).invoke())
        assertEquals(1, f.target.events.count { it == "cancel_reveal" })
        f.target.posts.removeAt(0).invoke()
        assertFalse(f.target.events.contains("delivered"))
    }
    @Test fun newDisclosureAndMissingTargetsRetireOldNoticeImmediately() {
        val f = Fixture()
        f.adapter.deliverAfterDraw { error("old notice delivered") }
        f.adapter.renderDisclosure(false)
        assertTrue(f.target.observer.listeners.isEmpty())
        assertFalse(requireNotNull(f.target.revealGuard).invoke())
        f.adapter.deliverAfterDraw { error("missing target delivered") }
        f.present = false
        f.adapter.renderIdle("취소했습니다.")
        assertTrue(f.target.observer.listeners.isEmpty())
        assertEquals(2, f.target.events.count { it == "cancel_reveal" })
    }
    @Test fun stalePostedReceiptCannotDeliverOrRetireANewNotice() {
        val f = Fixture()
        f.adapter.deliverAfterDraw { error("old notice delivered") }
        f.target.draw()
        val oldPost = f.target.posts.removeAt(0)
        f.adapter.deliverAfterDraw { f.target.events += "new_delivered" }
        oldPost()
        assertEquals(1, f.target.observer.listeners.size)
        f.target.draw()
        f.target.posts.removeAt(0).invoke()
        assertEquals(1, f.target.events.count { it == "new_delivered" })
        assertTrue(f.target.observer.listeners.isEmpty())
    }
    @Test fun offThreadRenderingReacquiresTargetsOnMainAndKeepsButtonLockedUntilArmed() {
        val f = Fixture()
        f.main = false
        f.adapter.renderDisclosure(false)
        assertNull(f.target.presentation)
        f.main = true
        f.dispatches.removeAt(0).invoke()
        val locked = requireNotNull(f.target.presentation)
        assertTrue(locked.visible)
        assertFalse(locked.enabled)
        assertTrue(locked.disclosure.contains("음성 또는 접근성 안내가 끝나면"))
        assertEquals("요약 안내가 끝날 때까지 기다려 주세요.", locked.buttonDescription)
        f.adapter.renderDisclosure(true)
        assertTrue(requireNotNull(f.target.presentation).enabled)
        assertEquals("방금 고정한 신고 보내기", f.target.presentation?.buttonText)
        assertEquals(2, f.refreshes)
    }
    @Test fun idleRetainsUnavailableQueueWordingAndOptionalVisibleMessage() {
        val f = Fixture()
        f.queue = false
        f.adapter.renderIdle()
        assertFalse(requireNotNull(f.target.presentation).enabled)
        assertFalse(requireNotNull(f.target.presentation).visible)
        assertEquals("손상 점자블록 신고 저장 기능 준비 중", f.target.presentation?.buttonText)
        f.queue = true
        f.adapter.renderIdle("취소했습니다.")
        assertTrue(requireNotNull(f.target.presentation).enabled)
        assertEquals("취소했습니다.", f.target.presentation?.disclosure)
    }
    private class Fixture {
        val target = FakeTarget()
        var present = true
        var main = true
        var queue = true
        var refreshes = 0
        val dispatches = mutableListOf<() -> Unit>()
        val adapter = AndroidExplicitReportPresentation(
            targets = { target.takeIf { present } }, isMainThread = { main }, dispatch = { dispatches += it },
            queueAvailable = { queue }, disclosureText = "전송 공개", summary = "요약", refreshHomeCards = { refreshes++ },
        )
    }
    private class FakeTarget : AndroidExplicitReportPresentation.Target {
        val events = mutableListOf<String>()
        val posts = mutableListOf<() -> Unit>()
        var presentation: AndroidExplicitReportPresentation.Presentation? = null
        override var attached = true
        override var current = true
        override var visible = true
        override val observer = FakeObserver(events)
        var revealGuard: (() -> Boolean)? = null
        override fun post(action: () -> Unit) { posts += action }
        override fun reveal(isCurrent: () -> Boolean): () -> Unit {
            events += "reveal"
            revealGuard = isCurrent
            return { events += "cancel_reveal" }
        }
        override fun invalidate() { events += "invalidate" }
        override fun render(presentation: AndroidExplicitReportPresentation.Presentation) { this.presentation = presentation }
        fun draw() { observer.listeners.toList().forEach { it() } }
    }
    private class FakeObserver(val events: MutableList<String>) : AndroidExplicitReportPresentation.DrawObserver {
        override var alive = true
        val listeners = mutableListOf<() -> Unit>()
        override fun add(listener: () -> Unit) { listeners += listener; events += "add" }
        override fun remove(listener: () -> Unit) { listeners -= listener; events += "remove" }
    }
}
