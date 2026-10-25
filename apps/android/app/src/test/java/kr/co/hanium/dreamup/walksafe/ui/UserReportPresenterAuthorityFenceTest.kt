package kr.co.hanium.dreamup.walksafe.ui

import android.view.View
import android.content.Context
import kr.co.hanium.dreamup.walksafe.report.UserReportUiState
import kr.co.hanium.dreamup.walksafe.report.UserReportUiPhase
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.report.UserReportAuthority
import kr.co.hanium.dreamup.walksafe.report.UserReportContentCategory
import kr.co.hanium.dreamup.walksafe.report.UserReportCorrectionPatch
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

/** Runs the real presenter; the unused Android panels are inert and the clearing target is explicit. */
class UserReportPresenterAuthorityFenceTest {
    @Test fun backgroundAuthorityFenceResetsTheActualDraftOwnerBeforeUiDispatch() {
        val f = Fixture()
        f.seed()
        f.presenter.clearForAuthorityFence()
        assertNull(f.read("renderedUserReportAuthority"))
        assertNull(f.read("renderedUserReportRequestId"))
        assertNull(f.read("renderedUserReportCorrectionId"))
        assertEquals(0L, f.read("renderedUserReportHistoryRefreshSequence"))
        assertEquals(false, f.read("clearUserReportCorrectionDescription"))
        assertSame(UserReportCorrectionPatch.Omitted, f.read("userReportCorrectionCategoryPatch"))
        assertEquals(0, f.clears)
        f.queued.single().run()
        assertEquals(1, f.clears)
    }
    @Test fun equalAuthorityDoesNotClearButChangedSessionReferenceGenerationOrIdentityDoes() {
        for (replacement in listOf<(UserReportAuthority) -> UserReportAuthority>(
            { it.copy(session = session()) }, { it.copy(sessionGeneration = it.sessionGeneration + 1) },
            { it.copy(localIdentityEpoch = it.localIdentityEpoch + 1) },
        )) {
            val f = Fixture().apply { main = true; seed() }
            val authority = f.read("renderedUserReportAuthority") as UserReportAuthority
            f.presenter.revalidateAuthority(authority.copy())
            assertEquals(0, f.clears)
            f.presenter.revalidateAuthority(replacement(authority))
            assertEquals(1, f.clears)
            assertNull(f.read("renderedUserReportAuthority"))
            assertTrue(f.queued.isEmpty())
        }
    }
    @Test fun queuedAuthorityClearCannotEraseTheNextAuthorityRender() {
        val f = Fixture()
        val first = UserReportAuthority(session(), 1L, 1L)
        f.main = true
        f.presenter.render(UserReportUiState(phase = UserReportUiPhase.READY), first)
        f.main = false
        f.presenter.clearForAuthorityFence()
        assertEquals(1, f.queued.size)
        f.main = true
        val replacement = UserReportAuthority(session(), 2L, 2L)
        f.presenter.render(UserReportUiState(phase = UserReportUiPhase.READY), replacement)
        f.queued.single().run()
        assertEquals("Old authority's queued clear must not erase a newer render", 0, f.clears)
        assertSame(replacement, f.read("renderedUserReportAuthority"))
    }

    private class Fixture {
        var main = false
        var clears = 0
        val queued = mutableListOf<Runnable>()
        private val panel = inert(UserReportPanel::class.java).also { panel ->
            UserReportPanel::class.java.declaredFields.filter { View::class.java.isAssignableFrom(it.type) }.forEach { field ->
                field.isAccessible = true
                field.set(panel, field.type.getConstructor(Context::class.java).newInstance(null as Any?))
            }
        }
        val presenter = UserReportPresenter(
            panel, inert(WalkSafeViewStyle::class.java),
            UserReportPresenter.Actions({}, {}),
            object : UserReportPresenter.UiDispatcher {
                override fun isMainThread() = main
                override fun post(action: Runnable) { queued += action }
            }, UserReportPresenter.AuthorityFenceTarget { clears++ },
        )
        fun seed() {
            write("renderedUserReportAuthority", UserReportAuthority(session(), 1L, 1L))
            write("renderedUserReportRequestId", "request")
            write("renderedUserReportCorrectionId", "correction")
            write("renderedUserReportHistoryRefreshSequence", 8L)
            write("clearUserReportCorrectionDescription", true)
            write("userReportCorrectionCategoryPatch", UserReportCorrectionPatch.Value(UserReportContentCategory.SIDEWALK_OBSTRUCTION))
        }
        fun read(name: String): Any? = field(name).get(presenter)
        private fun write(name: String, value: Any?) = field(name).set(presenter, value)
        private fun field(name: String) = UserReportPresenter::class.java.getDeclaredField(name).apply { isAccessible = true }
    }
    companion object {
        private fun session() = GatewayFieldSession.verified("https://gateway.example.test", "report-user", "walksafe_field_session=v2.report.user.session", Long.MAX_VALUE)
        private fun <T> inert(type: Class<T>): T {
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
            return type.cast(unsafe.allocateInstance(type))
        }
    }
}
