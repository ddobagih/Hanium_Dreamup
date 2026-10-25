package kr.co.hanium.dreamup.walksafe

import android.Manifest
import java.io.File
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Main selection/observation leaves plus real plan/collector; no OS dialog or Activity lifecycle. */
class MainActivityWalkPermissionConsistencyBehaviorTest {
    private val microphoneDenied = ObservedPermissionSnapshot(true, true, false, false)
    private val allRequiredGranted = ObservedPermissionSnapshot(true, true, true, false)

    @Test fun microphoneOnlyDenialReachesTheWalkSessionRequestCallback() {
        val main = MainActivity()
        val requested = mutableListOf<List<String>>()
        val dispatched = main.requestSelectedWalkSessionPermissions(
            observedPermissions = { microphoneDenied },
            missingPermissionsForObservedSnapshot = main::requiredWalkSessionPermissionsForObservedSnapshot,
            requestPermissions = { requested += it.toList(); 7301 },
        )
        assertEquals("The mic-only snapshot must reach the actual selector/callback: $dispatched/$requested",
            listOf(listOf(Manifest.permission.RECORD_AUDIO)), requested)
        assertTrue(dispatched)
        assertEquals(1, requested.size)
    }

    @Test fun startEntryReadinessWithMicrophoneDeniedIsUnavailable() {
        assertEntryDenied(WalkSessionAction.START_WALK)
    }

    @Test fun resumeEntryReadinessWithMicrophoneDeniedIsUnavailable() {
        assertEntryDenied(WalkSessionAction.RESUME_WALK)
    }

    @Test fun operationalReadinessKeepsPermissionsNotRequiredAndItsOldReadyResult() {
        val snapshot = readiness(MainActivity(), WalkSessionAction.START_WALK, false, microphoneDenied)
        assertTrue(snapshot.isReady)
        assertFalse(snapshot.plan.requires(WalkSessionReadinessRequirement.PERMISSIONS))
        assertEquals(WalkSessionReadinessStatus.NOT_REQUIRED,
            snapshot.observations.getValue(WalkSessionReadinessRequirement.PERMISSIONS).status)
    }

    @Test fun approvedRequiredMappingIncludesMicrophoneAndKeepsActivityOptional() {
        val main = MainActivity()
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO),
            main.requiredWalkSessionPermissionsForObservedSnapshot(microphoneDenied))
        assertTrue(main.requiredWalkSessionPermissionsForObservedSnapshot(allRequiredGranted).isEmpty())
    }

    @Test fun allRequiredPermissionsGrantedDoesNotAttemptOrDispatchAnotherRequest() {
        val main = MainActivity()
        var calls = 0
        assertFalse(main.requestSelectedWalkSessionPermissions(
            observedPermissions = { allRequiredGranted },
            missingPermissionsForObservedSnapshot = main::requiredWalkSessionPermissionsForObservedSnapshot,
            requestPermissions = { calls++; 7301 },
        ))
        assertEquals(0, calls)
    }

    @Test fun missingCameraLocationAndMicrophoneUseOneApprovedRequestBatchWithoutActivity() {
        val main = MainActivity()
        val missing = ObservedPermissionSnapshot(false, false, false, false)
        val requested = mutableListOf<List<String>>()
        assertTrue(main.requestSelectedWalkSessionPermissions(
            observedPermissions = { missing },
            missingPermissionsForObservedSnapshot = main::requiredWalkSessionPermissionsForObservedSnapshot,
            requestPermissions = { requested += it.toList(); 7302 },
        ))
        assertEquals(listOf(listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO)), requested)
        assertEquals(7302, requestField(main, "walkSessionPermissionRequestCode"))
    }

    @Test fun eachRequiredPermissionBlocksEntryAndActivityOnlyDenialKeepsItReady() {
        val main = MainActivity()
        for (observed in listOf(ObservedPermissionSnapshot(false, true, true, true),
            ObservedPermissionSnapshot(true, false, true, true), microphoneDenied)) {
            for (action in listOf(WalkSessionAction.START_WALK, WalkSessionAction.RESUME_WALK)) {
                assertFalse(readiness(main, action, true, observed).isReady)
            }
        }
        val ready = readiness(main, WalkSessionAction.START_WALK, true, allRequiredGranted)
        assertTrue(ready.isReady)
        assertEquals(WalkSessionReadinessStatus.READY,
            ready.observations.getValue(WalkSessionReadinessRequirement.PERMISSIONS).status)
    }

    @Test fun repeatedRequestCannotReobserveOrDispatchWhileTheSameAttemptIsPending() {
        val main = MainActivity()
        var snapshots = 0
        var requests = 0
        val observed = { snapshots++; microphoneDenied }
        val callback: (Array<String>) -> Int? = { requests++; 7303 }
        assertTrue(main.requestSelectedWalkSessionPermissions(observed,
            main::requiredWalkSessionPermissionsForObservedSnapshot, callback))
        assertFalse(main.requestSelectedWalkSessionPermissions(observed,
            main::requiredWalkSessionPermissionsForObservedSnapshot, callback))
        assertEquals(1, snapshots)
        assertEquals(1, requests)
        assertEquals(true, requestField(main, "walkSessionPermissionRequestAttempted"))
        assertEquals(true, requestField(main, "walkSessionPermissionRequestInFlight"))
    }

    @Test fun anExistingInFlightRequestPreventsSnapshotAndCallbackBeforeMarkingAttempted() {
        val main = MainActivity()
        MainActivityControllerTestFixture.field("walkSessionPermissionRequestInFlight").apply {
            isAccessible = true; setBoolean(main, true)
        }
        var snapshots = 0
        var requests = 0
        assertFalse(main.requestSelectedWalkSessionPermissions(
            observedPermissions = { snapshots++; microphoneDenied },
            missingPermissionsForObservedSnapshot = main::requiredWalkSessionPermissionsForObservedSnapshot,
            requestPermissions = { requests++; 7304 },
        ))
        assertEquals(0, snapshots)
        assertEquals(0, requests)
        assertEquals(false, requestField(main, "walkSessionPermissionRequestAttempted"))
    }

    @Test fun requestExceptionKeepsTheOriginalCauseAndAttemptButClearsInFlight() {
        val main = MainActivity()
        val failure = IllegalStateException("OS request failed")
        try {
            main.requestSelectedWalkSessionPermissions(
                observedPermissions = { microphoneDenied },
                missingPermissionsForObservedSnapshot = main::requiredWalkSessionPermissionsForObservedSnapshot,
                requestPermissions = {
                    assertEquals(true, requestField(main, "walkSessionPermissionRequestAttempted"))
                    assertEquals(true, requestField(main, "walkSessionPermissionRequestInFlight"))
                    throw failure
                },
            )
            fail("The original request exception must propagate")
        } catch (observed: IllegalStateException) { assertSame(failure, observed) }
        assertEquals(true, requestField(main, "walkSessionPermissionRequestAttempted"))
        assertEquals(false, requestField(main, "walkSessionPermissionRequestInFlight"))
        assertNull(requestField(main, "walkSessionPermissionRequestCode"))
        assertFalse(main.requestSelectedWalkSessionPermissions(
            observedPermissions = { fail("Attempted request must not reobserve"); microphoneDenied },
            missingPermissionsForObservedSnapshot = main::requiredWalkSessionPermissionsForObservedSnapshot,
            requestPermissions = { fail("Attempted request must not redispatch"); null },
        ))
    }

    @Test fun otherActionsAndDefaultPlanRetainTheOldPermissionAndVoiceInputRequirements() {
        val main = MainActivity()
        for (action in WalkSessionAction.entries) {
            for (mode in WalkSessionMode.entries) {
                val original = WalkSessionReadinessPlan(action, mode)
                val operational = main.walkSessionReadinessPlanForPurpose(action, mode, false)
                assertEquals(original.requiredRequirements, operational.requiredRequirements)
                assertFalse(operational.requires(WalkSessionReadinessRequirement.PERMISSIONS))
                assertFalse(operational.requires(WalkSessionReadinessRequirement.VOICE_INPUT))
                if (action !in listOf(WalkSessionAction.START_WALK, WalkSessionAction.RESUME_WALK)) {
                    assertEquals(original.requiredRequirements,
                        main.walkSessionReadinessPlanForPurpose(action, mode, true).requiredRequirements)
                }
            }
        }
    }

    @Test fun activeMainStillUsesEntryChecksForFinalStartAndResumePurpose() {
        val main = MainActivity()
        val lifecycle = WalkSessionLifecycle(clock = { 1_000L }, elapsedRealtimeClock = { 1_000L })
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val epoch = lifecycle.snapshot().epoch
        val initial = readiness(main, WalkSessionAction.START_WALK, true, allRequiredGranted, epoch)
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(initial))
        lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
        MainActivity::class.java.getDeclaredField("walkSessionLifecycle").apply {
            isAccessible = true; set(main, lifecycle)
        }
        assertFalse(readiness(main, WalkSessionAction.START_WALK, true, microphoneDenied, epoch).isReady)
        assertFalse(readiness(main, WalkSessionAction.RESUME_WALK, true, microphoneDenied, epoch).isReady)
        val operational = readiness(main, WalkSessionAction.START_WALK, false, microphoneDenied, epoch)
        assertTrue(operational.isReady)
        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(operational))
        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
    }

    @Test fun sourceConnectsTheFreshRequiredSnapshotAndPurposeWithoutChangingTheLeaseCallback() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        fun block(signature: String) = MainActivityRefactorSource.function(
            source, signature.substringBefore('('),
        )
        val request = block("private fun maybeRequestMissingWalkSessionPermissions(")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(request,
            "!authority.firstRunComplete()", "!decision.mayConfirmAndStart", "authority.currentActorId() == null",
            "requestSelectedWalkSessionPermissions(", "observedPermissions = ::currentObservedPermissionSnapshot",
            "missingPermissionsForObservedSnapshot = requiredWalkPermissions",
            "requestPermissionsWithLease(permissions, PermissionRequestPurpose.WALK_SESSION)"))
        assertTrue(MainActivityRefactorSource.composition(source, "permissionFlowController").contains("requiredWalkPermissions = ::requiredWalkSessionPermissionsForObservedSnapshot"))
        val selected = block("internal fun requestSelectedWalkSessionPermissions(")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(selected,
            "walkSessionPermissionRequestInFlight || walkSessionPermissionRequestAttempted",
            "observedPermissions()", "if (requestable.isEmpty()) return false",
            "walkSessionPermissionRequestAttempted = true", "walkSessionPermissionRequestInFlight = true",
            "requestPermissions(requestable.toTypedArray())", "catch (error: RuntimeException)",
            "walkSessionPermissionRequestInFlight = false", "throw error"))
        assertFalse(selected.contains("filterNot"))
        val capture = block("private fun captureWalkSessionReadiness(")
        val captureStart = source.indexOf("private fun captureWalkSessionReadiness(")
        val captureDeclaration = source.substring(captureStart, source.indexOf('{', captureStart))
        assertTrue(captureDeclaration.contains("checkEntryPermissions: Boolean = true"))
        assertTrue(capture.contains("checkEntryPermissions = checkEntryPermissions"))
        assertTrue(capture.contains("producers.evaluate(requirement,"))
        val composition = MainActivityRefactorSource.composition(source, "walkReadinessCoordinator")
        assertTrue(composition.contains("PermissionReadinessProducer(::currentObservedPermissionSnapshot, ::hasRecordAudioPermission)"))
        val producers = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/readiness/WalkReadinessProducers.kt").readText()
        assertTrue(producers.contains("WalkSessionReadinessRequirement.PERMISSIONS -> permissions.evaluate()"))
        val permissions = producers.substringAfter("internal class PermissionReadinessProducer").substringBefore("internal class FeatureReadinessProducer")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(permissions, "val observed = observe()",
            "WalkStartPermissionPolicy.required(activityRecognitionRequired = false).filterNot(observed::isGranted)",
            "if (missing.isEmpty()) WalkSessionReadinessStatus.READY"))
        assertEquals(1, Regex("checkEntryPermissions = false").findAll(source).count())
        assertTrue(block("private fun applyRuntimeReadinessIfActive(").contains("val readiness = runtime.capture(decision)"))
        val activeComposition = MainActivityRefactorSource.composition(source, "startupCapabilityCoordinator")
        val activeReadiness = activeComposition.substringAfter("object : ActiveReadinessPort {")
            .substringBefore("override fun transition")
        assertTrue(activeReadiness.contains("override fun capture(decision: WalkSafeStartupCapabilityDecision) = captureWalkSessionReadiness("))
        assertTrue(activeReadiness.contains("action = WalkSessionAction.START_WALK, checkEntryPermissions = false"))
        assertFalse(block("private fun currentGatewayWalkStartReadinessOrNull(").contains("checkEntryPermissions = false"))
        assertFalse(block("private fun completePendingWalkSessionResumeActivation(").contains("checkEntryPermissions = false"))
    }

    private fun requestField(main: MainActivity, field: String): Any? =
        MainActivityControllerTestFixture.field(field).apply { isAccessible = true }.get(main)

    private fun assertEntryDenied(action: WalkSessionAction) {
        val snapshot = readiness(MainActivity(), action, true, microphoneDenied)
        assertFalse("The entry-purpose snapshot must include the required mic denial", snapshot.isReady)
        assertTrue(snapshot.plan.requires(WalkSessionReadinessRequirement.PERMISSIONS))
        val observation = snapshot.observations.getValue(WalkSessionReadinessRequirement.PERMISSIONS)
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, observation.status)
        assertTrue(observation.reason.contains("microphone"))
        assertFalse(snapshot.plan.requires(WalkSessionReadinessRequirement.VOICE_INPUT))
    }

    private fun readiness(main: MainActivity, action: WalkSessionAction, entry: Boolean,
        observed: ObservedPermissionSnapshot,
        epoch: WalkRuntimeEpoch = WalkRuntimeEpoch("permission-consistency", 3L)): WalkSessionReadinessSnapshot {
        val plan = main.walkSessionReadinessPlanForPurpose(action, WalkSessionMode.FULL, entry)
        val collector = WalkSessionReadinessCollector(epoch, plan)
        plan.requiredRequirements.forEach { requirement ->
            val (status, reason) = if (requirement == WalkSessionReadinessRequirement.PERMISSIONS)
                main.walkSessionPermissionReadinessForObservedSnapshot(observed)
            else WalkSessionReadinessStatus.READY to ""
            collector.record(WalkSessionReadinessObservation(epoch, requirement, status, reason))
        }
        return collector.build(1_000L)
    }
}
