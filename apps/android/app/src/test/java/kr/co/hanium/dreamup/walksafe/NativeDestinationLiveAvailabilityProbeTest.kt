package kr.co.hanium.dreamup.walksafe

import android.widget.EditText
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentRuntimeController
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.ui.DestinationGuidancePanel
import kr.co.hanium.dreamup.walksafe.ui.NativeScreenPresenter
import kr.co.hanium.dreamup.walksafe.ui.NativeUiPublication
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Main queries and actual workflow/one-use owner; stable admitted SDK fixture, not native UI. */
class NativeDestinationLiveAvailabilityProbeTest {
    @After fun clearSession() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }

    @Test fun liveRecoveryReopensConfirmationWithoutRefreshingTheHiddenSearchView() {
        val f = Fixture()
        f.gps(false)
        assertFalse(f.model())
        assertFalse(f.available(NativeUiPage.DESTINATION_CONFIRM))
        f.gps(true)
        assertTrue(f.model())
        assertFalse(f.widget.isEnabled)
        assertTrue("Confirmation must read the recovered live model", f.available(NativeUiPage.DESTINATION_CONFIRM))
        assertFalse("Search page keeps its original cached-view behavior", f.available(NativeUiPage.DESTINATION_SEARCH))
    }

    @Test fun actualWorkflowConsumesAndPostsTheOriginalIntentOnceAfterLiveRecovery() {
        val f = Fixture()
        f.gps(false)
        f.workflow.renderMainUi()
        assertEquals(0, f.posts.size)
        assertEquals(NativeUiPage.DESTINATION_CONFIRM, f.workflow.pendingNativeUiPage)
        f.gps(true)
        f.workflow.renderMainUi()
        assertEquals("Actual workflow must not wait for unrelated search rendering", 1, f.posts.size)
        f.drain()
        repeat(2) { f.workflow.renderMainUi(); f.drain() }
        assertEquals(1, f.acceptedStarts)
        assertNull(f.workflow.pendingNativeUiPage)
        assertFalse(f.widget.isEnabled)
    }

    @Test fun staleEnabledViewCannotAdmitConfirmationWhileTheLiveModelIsClosed() {
        val f = Fixture()
        f.widget.setEnabled(true)
        f.gps(false)
        assertFalse(f.model())
        assertFalse(f.available(NativeUiPage.DESTINATION_CONFIRM))
        assertTrue(f.available(NativeUiPage.DESTINATION_SEARCH))
        f.workflow.renderMainUi()
        f.drain()
        assertEquals(0, f.acceptedStarts)
    }

    @Test fun aWalkingGpsRestrictionBlocksSearchAndDestinationConfirmation() {
        val f = Fixture()
        f.gps(false)
        assertFalse(f.navigationAllowed())
        assertFalse(f.model())
        assertFalse(f.available(NativeUiPage.DESTINATION_CONFIRM))
        assertFalse(f.available(NativeUiPage.DESTINATION_SEARCH))
    }

    @Test fun inFlightSearchAndMissingPublicationRemainClosedAfterRecovery() {
        val f = Fixture()
        assertTrue(f.model())
        f.base.set("destinationSearchInFlight", true)
        assertFalse(f.available(NativeUiPage.DESTINATION_CONFIRM))
        f.base.set("destinationSearchInFlight", false)
        val published: BooleanArray = read(f.publication, "published")
        published[NativeUiPublication.Target.DESTINATION_QUERY_INPUT.ordinal] = false
        assertFalse(f.available(NativeUiPage.DESTINATION_CONFIRM))
        f.workflow.renderMainUi()
        assertEquals(0, f.posts.size)
    }

    @Test fun modelRecoveryDoesNotBypassTheActualPostedActorAndEpochChecks() {
        for (retire in listOf<(Fixture) -> Unit>(
            { it.base.set("reporterUserId", "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") },
            { it.base.lifecycle.handle(WalkSessionEvent.EndRequested) },
        )) {
            val f = Fixture()
            f.widget.setEnabled(true)
            f.workflow.renderMainUi()
            assertEquals(1, f.posts.size)
            retire(f)
            f.drain()
            assertEquals(0, f.acceptedStarts)
            assertNull(f.workflow.pendingNativeUiPage)
        }
    }

    private class CachedEditText : EditText(null) {
        private var cachedEnabled = false
        override fun isEnabled() = cachedEnabled
        override fun setEnabled(enabled: Boolean) { cachedEnabled = enabled }
    }

    private class Fixture {
        // Reuses the accepted cold-fixture SDK/clock setup. Actor publication and OS grants
        // remain outside this query/workflow probe; its existing stable-owner assumption stays.
        private val cold = Class.forName(MainActivityColdFirstDirectionFlowIntegrationTest::class.java.name + "\$ColdFixture")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance().also {
                it.javaClass.getDeclaredMethod("acquire", Double::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(it, 5.0)
            }
        val base: MainActivityContinuousLocationStartBehaviorTest.Fixture = read(cold, "base")
        val main = base.main
        val workflow = MainActivityControllerTestFixture.controller(main, "nativeWorkflowController") as NativeWorkflowController
        val publication: NativeUiPublication = direct(main, "nativeUiPublication")
        val widget: CachedEditText = allocate(CachedEditText::class.java)
        val posts = mutableListOf<Runnable>()
        var acceptedStarts = 0
        private val destination = DestinationSearchResult("live-query", "목적지", RoutePoint(37.5027, 127.0), null, null, null, null)
        private val guard = base.field("officialEnvironmentRuntimeGuard") as OfficialEnvironmentRuntimeGuard

        init {
            // Same completed actor-bound profile used by NativeDestinationStartRecovery tests.
            // The cold GPS fixture deliberately did not exercise the native-home admission.
            val actor = base.field("reporterUserId") as String
            base.set("priorityUserOnboardingPolicy", PriorityUserOnboardingPolicy(PriorityUserOnboardingSnapshot(
                ageBand = PriorityUserAgeBand.VERIFIED_14_PLUS, educationReviewed = true,
                safePracticePlaceConfirmed = true, completedPractices = PriorityUserPractice.entries.toSet(),
                phonePostureAcknowledged = true, practiceNecessityReviewed = true,
                educationAccepted = true, usageConditionsAcknowledged = true,
                appUsageReviewed = true, appUsageAccepted = true, hazardResponseConfirmed = true)))
            base.set("priorityUserOnboardingActorId", actor)
            val panel = allocate(DestinationGuidancePanel::class.java)
            put(panel, "destinationQueryInput", widget)
            val presenter = allocate(NativeScreenPresenter::class.java)
            presenter.publishSearchTargets(panel)
            put(main, "nativeScreenPresenter\$delegate", lazyOf(presenter))
            publication.publish(NativeUiPublication.Target.DESTINATION_QUERY_INPUT)
            workflow.pendingUiDestination = destination
            workflow.nativeUiPage = NativeUiPage.GUIDANCE
            workflow.pendingNativeUiPage = NativeUiPage.DESTINATION_CONFIRM
            val pages: NativeWorkflowController.PageUi = read(workflow, "pages")
            put(workflow, "pages", object : NativeWorkflowController.PageUi by pages {
                override fun updateLiveVisibility() = Unit
                override fun readyForNativeRendering() = true
                override fun renderFrame(frame: NativeScreenPresenter.FrameFacts) = Unit
            })
            val preparation: NativeWorkflowController.PreparationActions = read(workflow, "preparation")
            put(workflow, "preparation", object : NativeWorkflowController.PreparationActions by preparation {
                override fun refreshForegroundHomeWakeListening() = Unit // Acoustic service leaf.
            })
            val destinations: NativeWorkflowController.DestinationTransactions = read(workflow, "destinations")
            put(workflow, "destinations", object : NativeWorkflowController.DestinationTransactions by destinations {
                override fun postNativeDestinationStart(destination: DestinationSearchResult) {
                    main.postNativeDestinationStart(destination, post = { posts += it },
                        navigationAdmitted = { boolean("currentNavigationCollectionAllowsWork") }, start = {
                            main.handOffNativeDestinationStart(destination, prepare = {}, select = {
                                acceptedStarts++; true // Downstream transport acceptance, no HTTP/TTS.
                            }, refreshUi = {}, navigationAdmitted = { boolean("currentNavigationCollectionAllowsWork") })
                        })
                }
            })
            assertTrue(boolean("shouldShowNativeHome"))
            assertTrue(boolean("isWalkSessionRuntimeActive"))
            assertTrue(model())
        }

        fun navigationAllowed() = boolean("currentNavigationCollectionAllowsWork")
        fun gps(allowed: Boolean) {
            guard.onAssessment(OfficialEnvironmentAssessment(base.epoch, base.now, base.profile.profileId,
                OfficialEnvironmentFactor.entries.associateWith {
                    if (it == OfficialEnvironmentFactor.GPS_QUALITY && !allowed) EnvironmentEvidenceStatus.FAIL else EnvironmentEvidenceStatus.PASS
                }, usageLimitsAcknowledged = true), base.now)
            // Camera remains available, so CONTINUE and the shared overall output flag survive.
            assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, guard.decision().action)
        }
        fun model() = boolean("currentDestinationSearchAllowsWork")
        // Resolve only the requested method; enumerating all Activity signatures needs GL10
        // classes that are intentionally absent from the Gradle mockable Android runtime.
        fun available(page: NativeUiPage): Boolean = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, "nativeFeatureAvailable", MethodType.methodType(Boolean::class.javaPrimitiveType, NativeUiPage::class.java))
            .bindTo(main).invokeWithArguments(page) as Boolean
        private fun boolean(name: String): Boolean = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, name, MethodType.methodType(Boolean::class.javaPrimitiveType))
            .bindTo(main).invokeWithArguments() as Boolean
        fun drain() { while (posts.isNotEmpty()) posts.removeAt(0).run() }
    }

    companion object {
        private val allocator = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST") private fun <T> allocate(type: Class<T>): T = allocator.javaClass.getMethod("allocateInstance", Class::class.java).invoke(allocator, type) as T
        @Suppress("UNCHECKED_CAST") private fun <T> read(owner: Any, field: String): T = owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(owner) as T
        private fun <T> direct(owner: Any, field: String): T = read(owner, field)
        private fun put(owner: Any, field: String, value: Any?) { owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(owner, value) }
    }
}
