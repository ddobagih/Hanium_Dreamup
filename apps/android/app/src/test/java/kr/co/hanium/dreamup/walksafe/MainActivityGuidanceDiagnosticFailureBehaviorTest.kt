package kr.co.hanium.dreamup.walksafe

import java.lang.reflect.InvocationTargetException
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.fieldlog.*
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteGuidanceController
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The unavailable JVM Android SDK is a deliberately throwing diagnostic leaf, not a route gate. */
class MainActivityGuidanceDiagnosticFailureBehaviorTest {
    @After fun retire() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }

    @Test fun diagnosticSdkFailureCannotLeaveTheRealReservedCueWithoutSpeechSubmission() {
        val type = Class.forName(MovingDirectionGraceReviewTest::class.java.name + "$" + "Fixture")
        val fixture = type.getDeclaredConstructor(Double::class.javaPrimitiveType, java.lang.Double::class.java,
            Double::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(15.0, 12.0, 0.0)
        val main = field(fixture, "main") as MainActivity
        val guide = field(fixture, "guide") as RouteGuidanceController
        val logger = logger()
        put(main, "guidanceDiagnosticRuntime", logger)
        val original = field(guide, "diagnostics") as RouteGuidanceController.Diagnostics
        val record = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup()).findVirtual(
            MainActivity::class.java, "recordGuidanceDiagnosticEvent",
            MethodType.methodType(Void.TYPE, String::class.java, Map::class.java),
        )
        put(guide, "diagnostics", object : RouteGuidanceController.Diagnostics by original {
            override fun record(event: String, values: Map<String, Any?>) {
                original.record(event, values)
                if (event == "route_guidance_generated") {
                    assertNotNull(field(field(fixture, "navigator")!!, "pendingSpeechCue"))
                    GuidanceDiagnosticMetadata.structured(event, values)?.let { record.invokeWithArguments(main, it.name, it.fields) }
                }
            }
        })
        try {
            val action = call(fixture, "startWithNativeIntro")!!
            val native = field(fixture, "native")!!
            assertEquals(2, (field(native, "spoken") as List<*>).size)
            val id = field(action, "id")!!
            val clock = field(fixture, "clock")!!
            call(clock, "advanceBy", 100L); call(fixture, "face")
            call(native, "start", id); call(native, "done", id)
            val navigator = field(fixture, "navigator")!!
            assertNotNull(field(navigator, "completedSpeechCue"))
        } finally { logger.close(); call(field(fixture, "output")!!, "closeActuator") }
    }

    @Test fun unavailableAuthorityObservationClosesPreviouslyAdmittedDiagnosticWork() {
        val type = Class.forName(MovingDirectionGraceReviewTest::class.java.name + "$" + "Fixture")
        val fixture = type.getDeclaredConstructor(Double::class.javaPrimitiveType, java.lang.Double::class.java,
            Double::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(15.0, 12.0, 0.0)
        val main = field(fixture, "main") as MainActivity
        val runtime = logger(approved = true, executor = Executor { /* Keep queued work controlled; no I/O. */ })
        val session = GatewayFieldSession.legacyVerified("http://127.0.0.1", "fixture", "fixture-device",
            "walksafe_field_session=fixture", Long.MAX_VALUE).also { put(it, "backendAccountGeneration", 1L) }
        val authority = GuidanceDiagnosticRuntimeCoordinator.Authority(session, 1, 1L, true, true, true)
        try {
            runtime.publishAuthority(authority)
            assertTrue(runtime.state().enabled)
            assertTrue(runtime.record("tts", mapOf("stage" to "GENERATED")))
            val pending = runtime.state().pending
            put(main, "guidanceDiagnosticRuntime", runtime)
            // The real JVM Looper leaf throws: retaining a previous admitted snapshot is unsafe.
            val refresh = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup()).findVirtual(
                MainActivity::class.java, "refreshGuidanceDiagnosticAuthority", MethodType.methodType(Boolean::class.javaPrimitiveType),
            )
            assertEquals(false, refresh.invokeWithArguments(main))
            assertFalse(runtime.state().enabled)
            assertFalse(runtime.state().authorized)
            assertFalse(runtime.record("tts", mapOf("stage" to "START_CHECKED")))
            assertEquals(pending, runtime.state().pending)
            runtime.publishAuthority(authority)
            assertTrue(runtime.state().enabled)
        } finally { runtime.close(); call(field(fixture, "output")!!, "closeActuator") }
    }

    private fun logger(approved: Boolean = false, executor: Executor = Executor { it.run() }) = GuidanceDiagnosticRuntimeCoordinator(approved, "a".repeat(64),
        queueFactory = { error("No diagnostic store should open") },
        transport = object : GuidanceDiagnosticTransport {
            override fun upload(binding: GuidanceDiagnosticBinding, batch: GuidanceDiagnosticBatch): CancellableNetworkCall<GuidanceDiagnosticAck> = error("No network")
        }, preference = { error("No network") }, authorizeIfAbsent = { error("No network") },
        setPreference = { _, _, _ -> error("No network") }, wallTimeMs = { 1_000L }, elapsedRealtimeMs = { 1_000L },
        worker = executor, ownsWorker = false)
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun put(owner: Any, name: String, value: Any?) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    private fun call(owner: Any, name: String, vararg args: Any): Any? = try {
        owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(owner, *args)
    } catch (failure: InvocationTargetException) { throw failure.targetException }
}
