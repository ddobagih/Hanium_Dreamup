package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import org.junit.Assert.*
import org.junit.Test

/**
 * Actual Main pure PhoneMountingPolicy capture/mapping and memory retirement checks.
 * Positive OS collection admission and confirmPhoneMounting's UI/preflight caller are not JVM doubles.
 * Exact MethodHandles lookup does not load unrelated GL/SDK method signatures.
 */
class MainActivityExplicitMountConsumptionTest {
    @Test fun explicitCurrentChestAndNecklaceUseTheRealNoCheckRequestPolicy() {
        for (method in listOf(PhoneMountingMethod.CHEST_FORWARD, PhoneMountingMethod.NECKLACE_FORWARD)) {
            val f = Fixture()
            f.confirm(method)
            val observation = f.capture(1_000L)
            assertEquals(PhoneMountingReason.PASSED, assessment(observation).reason)
            assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT, f.relation(observation))
            assertFalse(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
            assertEquals(900L, f.confirmation().confirmedAtElapsedRealtimeMs)
            assertEquals(1_000L, field(observation, "observedAtElapsedRealtimeMs"))
        }
    }

    @Test fun handAndPocketAreUnsupportedButUnknownAndMissingRemainUnknown() {
        for (method in listOf(PhoneMountingMethod.HANDHELD, PhoneMountingMethod.POCKET)) {
            val f = Fixture()
            f.confirm(method)
            val observation = f.capture(1_000L)
            assertEquals(PhoneMountingReason.PROHIBITED_MOUNTING_METHOD, assessment(observation).reason)
            assertEquals(UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, f.relation(observation))
        }
        val f = Fixture()
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, f.relation(f.capture(1_000L)))
        f.confirm(PhoneMountingMethod.UNKNOWN)
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, f.relation(f.capture(1_000L)))
    }

    @Test fun equalAssumedDtoOrAnOldIdentityCannotBecomeAnExplicitDeclaration() {
        val f = Fixture()
        val manual = f.confirm(PhoneMountingMethod.CHEST_FORWARD)
        assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT, f.relation(f.capture(1_000L)))
        val assumption = manual.copy() // Identical values are insufficient provenance.
        assertEquals(manual, assumption)
        assertNotSame(manual, assumption)
        set(f.main, "phoneMountingUserConfirmation", assumption)
        set(f.main, "explicitPositioningMountConfirmation", null)
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, f.relation(f.capture(1_000L)))
        set(f.main, "explicitPositioningMountConfirmation", manual)
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, f.relation(f.capture(1_000L)))
    }

    @Test fun sensorCheckPassCannotSkipTheOriginalConfirmationAgeEpochOrClockChecks() {
        val f = Fixture()
        f.confirm(PhoneMountingMethod.CHEST_FORWARD, at = 0L)
        val now = PROFILE.maximumUserConfirmationAgeMs + 1L
        val camera = camera(f.epoch, now)
        set(f.main, "latestPhoneMountingCameraAssessment", camera)
        val sensorCheck = PhoneMountingPolicy.assess(PhoneMountingAssessmentPhase.PREFLIGHT, f.epoch, now,
            f.confirmation(), camera, PhoneMountingPolicy.initialState(f.epoch, PROFILE),
            checkRequest = PhoneMountingCheckRequest(f.epoch, 1L, now - 1L))
        assertEquals(PhoneMountingReason.SENSOR_CHECK_PASSED, sensorCheck.reason)
        val original = f.capture(now)
        assertEquals(PhoneMountingReason.USER_CONFIRMATION_STALE, assessment(original).reason)
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, f.relation(original))
        f.confirm(PhoneMountingMethod.CHEST_FORWARD, epoch = f.epoch.copy(recoveryGeneration = f.epoch.recoveryGeneration + 1L))
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, f.relation(f.capture(1_000L)))
        f.confirm(PhoneMountingMethod.CHEST_FORWARD, at = 1_001L)
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, f.relation(f.capture(1_000L)))
        assertEquals(1_001L, f.confirmation().confirmedAtElapsedRealtimeMs)
    }

    @Test fun retiredBindingOrActorWalkOwnerCannotCommitAQueuedUnknownInput() {
        val f = Fixture()
        val old = f.capture(1_000L)
        val next = f.owner.copy(actorGeneration = 2L, walkEpoch = f.epoch.copy(recoveryGeneration = f.epoch.recoveryGeneration + 1L))
        assertTrue(f.coordinator.beginUserMotionEpoch(next, 1_000L))
        assertFalse(f.consume(old, 1_000L))
        val current = f.coordinator.peekUserMotion(1_000L)
        assertEquals(next, current.owner)
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, current.mount)
        val currentInput = f.capture(1_000L)
        set(f.main, "positioningCollectionBinding", null)
        assertFalse(f.consume(currentInput, 1_000L))
        assertEquals(current, f.coordinator.peekUserMotion(1_000L))
    }

    @Test fun sourceFourProducersCaptureTheOriginalInputAndOnlyTheExplicitCallerIssuesProvenance() {
        val source = source()
        val mountingSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/mounting/PhoneMountingSessionController.kt").readText()
        val positioningSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt").readText()
        val mountingBinding = MainActivityRefactorSource.property(source, "phoneMountingSession")
        assertTrue(mountingBinding.contains("PhoneMountingSessionController("))
        assertTrue(mountingBinding.contains("override fun captureTarget() = capturePositioningMountTarget()"))
        assertTrue(mountingBinding.contains("override fun consume(receipt: PositioningMountObservation?, receivedAtMs: Long) { observePositioningMount(receipt, receivedAtMs) }"))
        assertTrue(mountingBinding.contains("SystemClock::elapsedRealtime"))
        val confirm = MainActivityRefactorSource.function(source, "private fun confirmPhoneMounting(")
        val explicit = MainActivityRefactorSource.member(mountingSource, "confirmExplicit")
        assertTrue(confirm.contains("session.confirmExplicit(snapshot.epoch, method, activeCorrection)"))
        assertTrue(explicit.contains("val target = positioning.captureTarget()"))
        assertTrue(explicit.contains("explicitPositioningMountConfirmation = confirmation"))
        assertTrue(confirm.contains("session.consumeConfirmation(mountObservation)"))
        assertTrue(MainActivityRefactorSource.member(mountingSource, "consumeConfirmation").contains("positioning.consume(receipt, clock())"))
        val quick = MainActivityRefactorSource.function(source, "private fun confirmOfficialEnvironmentConditions(")
        assertTrue(quick.contains("effects.assumeInitialManualMountConfirmation(lifecycle.epoch)"))
        assertTrue(MainActivityRefactorSource.composition(source, "officialEnvironmentController").contains("phoneMountingSession.assumeInitialConfirmation(epoch)"))
        val assume = MainActivityRefactorSource.member(mountingSource, "assumeInitialConfirmation")
        assertTrue(assume.contains("explicitPositioningMountConfirmation = null"))
        assertFalse(assume.contains("explicitPositioningMountConfirmation = confirmation"))
        val activate = MainActivityRefactorSource.member(mountingSource, "activatePhoneMountingRuntime")
        val current = MainActivityRefactorSource.member(mountingSource, "applyCurrentPhoneMountingRuntimeAssessment")
        for (producer in listOf(activate, current)) {
            assertTrue(producer.indexOf("positioning.captureTarget()") < producer.indexOf("currentPhoneMountingAssessment("))
            assertTrue(producer.contains("assessedAtElapsedRealtimeMs"))
            assertTrue(producer.contains("capturePositioningObservationLocked("))
        }
        val runtime = MainActivityRefactorSource.member(mountingSource, "applyPhoneMountingRuntimeAssessment")
        assertTrue(runtime.indexOf("positioning.consume(") < runtime.indexOf("when (assessment.status)"))
        val bind = MainActivityRefactorSource.member(positioningSource, "bindPositioningUserMotionEpoch")
        assertTrue(bind.indexOf("beginUserMotionEpoch(") < bind.indexOf("capturePositioningMountObservation("))
        assertTrue(bind.contains("if (!ownerChanged) return false"))
        assertTrue(bind.contains("mounting.retireExplicitForChangedMotionOwner()"))
        assertTrue(MainActivityRefactorSource.member(mountingSource, "retireExplicitForChangedMotionOwner").contains("explicitPositioningMountConfirmation = null"))
        val actualPositioning = MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(actualPositioning.contains("val scope = positions.scope()"))
        assertTrue(actualPositioning.contains("val sources = positions.sources()"))
        assertTrue(actualPositioning.contains("val mounting = positions.mounting()"))
        assertTrue(actualPositioning.contains("PositioningSessionController(run, scope, sources, mounting,"))
        val navigationFactory = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertEquals(1, Regex(Regex.escape("positions = PositioningAssemblyBindings(")).findAll(navigationFactory).count())
        val positioningPorts = ReportStaticSourceInspector.blockAfter(navigationFactory, "positions = PositioningAssemblyBindings(")
        assertEquals(1, Regex(Regex.escape("mounting = { object : PositioningSessionController.Mounting")).findAll(navigationFactory).count())
        val mountingPorts = ReportStaticSourceInspector.blockAfter(navigationFactory, "mounting = { object : PositioningSessionController.Mounting")
        assertTrue(mountingPorts.contains("override val observationLock get() = phoneMountingObservationLock"))
    }

    @Test fun sourceCommitHasOnlyMemoryWritesAndKeepsHistoricalHeadingAndOriginalStepFacts() {
        val source = source()
        val capture = MainActivityRefactorSource.function(source, "private fun capturePositioningMountObservation(")
        assertTrue(capture.contains("PhoneMountingPolicy.assess("))
        assertTrue(capture.contains("nowElapsedRealtimeMs = observedAtElapsedRealtimeMs"))
        assertTrue(capture.contains("checkRequest = null"))
        val consume = MainActivityRefactorSource.function(source, "private fun observePositioningMount(")
        assertTrue(consume.indexOf("isPositioningCollectionCurrent(binding)") < consume.lastIndexOf("synchronized(mounting.observationLock)"))
        val commit = consume.substringAfter("val accepted = synchronized(mounting.observationLock)").substringBefore("if (accepted)")
        assertTrue(commit.contains("positioningMountObservationIsOwned(observation)"))
        assertTrue(commit.contains("binding.coordinator.observeUserMount(relation, observation.observedAtElapsedRealtimeMs,"))
        assertTrue(commit.contains("receivedAtElapsedRealtimeMs, binding.inputGeneration, target.motionOwner"))
        assertFalse(commit.contains("SystemClock"))
        assertFalse(commit.contains("isPositioningCollectionCurrent"))
        assertFalse(commit.contains("applyUserMotionState"))
        assertFalse(commit.contains("feedbackActuator"))
        val step = MainActivityRefactorSource.function(source, "private fun handlePositioningStepEvent(")
        assertTrue(step.contains("chestMountedHeadingForStep(event.timestampMs)"))
        assertTrue(step.contains("maximumAgeMs = 100L"))
        assertTrue(step.contains("phoneForwardMounted = chestHeading != null"))
        val positioningSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt").readText()
        val heading = MainActivityRefactorSource.member(positioningSource, "chestMountedHeadingForStep", occurrence = 1)
        val actualPositioning = MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(actualPositioning.contains("val scope = positions.scope()"))
        assertTrue(actualPositioning.contains("val sources = positions.sources()"))
        assertTrue(actualPositioning.contains("val mounting = positions.mounting()"))
        assertTrue(actualPositioning.contains("PositioningSessionController(run, scope, sources, mounting,"))
        val navigationFactory = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertEquals(1, Regex(Regex.escape("positions = PositioningAssemblyBindings(")).findAll(navigationFactory).count())
        val positioningPorts = ReportStaticSourceInspector.blockAfter(navigationFactory, "positions = PositioningAssemblyBindings(")
        assertEquals(1, Regex(Regex.escape("mounting = { object : PositioningSessionController.Mounting")).findAll(navigationFactory).count())
        val mountingPorts = ReportStaticSourceInspector.blockAfter(navigationFactory, "mounting = { object : PositioningSessionController.Mounting")
        assertTrue(mountingPorts.contains("override val observationLock get() = phoneMountingObservationLock"))
        assertTrue(heading.contains("confirmedAtElapsedRealtimeMs <= timestampMs"))
        assertTrue(heading.contains("PhoneMountingMethod.CHEST_FORWARD"))
    }

    private class Fixture {
        val main = MainActivity()
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "explicit-mount-main" }, clock = { 0L })
        val epoch = lifecycle.snapshot().epoch
        val coordinator = field(main, "positioningCoordinator") as PositioningCoordinator
        val owner = UserMotionStateOwner(1L, epoch, "test-boot", "test-process")
        init {
            set(main, "walkSessionLifecycle", lifecycle)
            assertTrue(coordinator.beginUserMotionEpoch(owner, 0L))
            val locationOwner = ownerClass.getDeclaredConstructor(String::class.java, GatewayFieldSession::class.java,
                java.lang.Long.TYPE, WalkRuntimeEpoch::class.java, String::class.java).apply { isAccessible = true }
                .newInstance(null, null, 1L, null, null)
            val binding = bindingClass.getDeclaredConstructor(PositioningCoordinator::class.java, java.lang.Long.TYPE,
                ownerClass, Integer.TYPE, java.lang.Long.TYPE).apply { isAccessible = true }
                .newInstance(coordinator, coordinator.currentInputGeneration(), locationOwner, 0, 0L)
            set(main, "positioningCollectionBinding", binding)
            set(main, "latestPhoneMountingCameraAssessment", camera(epoch, 1_000L))
        }
        fun confirm(method: PhoneMountingMethod, at: Long = 900L, epoch: WalkRuntimeEpoch = this.epoch): PhoneMountingUserConfirmation =
            PhoneMountingUserConfirmation(epoch, at, method, false).also {
                // Provenance is a controlled input here. Only production confirm's SOURCE issues it.
                set(main, "phoneMountingUserConfirmation", it)
                set(main, "explicitPositioningMountConfirmation", it)
            }
        fun confirmation() = field(main, "phoneMountingUserConfirmation") as PhoneMountingUserConfirmation
        fun capture(at: Long): Any {
            val target = requireNotNull(invoke(main, "capturePositioningMountTarget", targetClass))
            return requireNotNull(invoke(main, "capturePositioningMountObservation", observationClass,
                arrayOf(targetClass, WalkRuntimeEpoch::class.java, java.lang.Long.TYPE,
                    PhoneMountingAssessmentPhase::class.java, PhoneMountingRuntimeState::class.java,
                    CameraFrameQualityAssessment::class.java, java.lang.Boolean.TYPE),
                target, epoch, at, PhoneMountingAssessmentPhase.PREFLIGHT,
                PhoneMountingPolicy.initialState(epoch, PROFILE), null, false))
        }
        fun relation(observation: Any) = invoke(main, "positioningMountRelation", UserDeviceBodyRelationState::class.java,
            arrayOf(observationClass), observation) as UserDeviceBodyRelationState
        fun consume(observation: Any, at: Long) = invoke(main, "observePositioningMount", java.lang.Boolean.TYPE,
            arrayOf(observationClass, java.lang.Long.TYPE), observation, at) as Boolean
    }

    private companion object {
        val PROFILE = WalkSafeEnvironmentProfiles.testCandidate.phoneMounting
        val ownerClass by lazy { Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.LocationCollectionOwner") }
        val bindingClass by lazy { Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding") }
        val targetClass by lazy { Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.PositioningMountTarget") }
        val observationClass by lazy { Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.PositioningMountObservation") }
        fun camera(epoch: WalkRuntimeEpoch, at: Long) = CameraFrameQualityAssessment(epoch, at,
            PROFILE.cameraFrameQualityProfileId, PROFILE.maximumEvidenceAgeMs,
            EnvironmentEvidenceStatus.PASS, CameraFrameQualityReason.PASSED)
        fun assessment(observation: Any) = field(observation, "assessment") as PhoneMountingAssessment
        fun field(owner: Any, name: String): Any? = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(owner)
        fun set(owner: Any, name: String, value: Any?) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(owner, value)
        fun invoke(main: MainActivity, name: String, returns: Class<*>, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? =
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, name, MethodType.methodType(returns, types.toList()))
                .invokeWithArguments(listOf(main) + args.toList())
        fun source() = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        fun body(source: String, from: String, until: String): String {
            assertTrue("Missing exact source boundary: $from", source.contains(from))
            val rest = source.substringAfter(from)
            assertTrue("Missing exact next source boundary: $until", rest.contains(until))
            return rest.substringBefore(until)
        }
    }
}
