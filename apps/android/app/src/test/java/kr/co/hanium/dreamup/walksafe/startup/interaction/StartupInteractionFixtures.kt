package kr.co.hanium.dreamup.walksafe.startup.interaction

import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*

internal inline fun <reified T> startupPort(crossinline body: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments -> body(method.name, arguments.orEmpty()) } as T
internal fun startupInput(voice: Boolean? = true) = WalkSafeStartupCapabilityInput(true, true, true, true, true, true, voice, true, true, "profile")
internal fun readyStartupLifecycle(): WalkSessionLifecycle = WalkSessionLifecycle({ "walk" }, { 100L }, { 1_000L }).also { lifecycle ->
    lifecycle.handle(WalkSessionEvent.EnteredForeground)
    val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
    val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, plan)
    plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(collector.epoch, it, WalkSessionReadinessStatus.READY)) }
    lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(1_000L)))
}
internal class StartupConfirmationFixture {
    val trace = mutableListOf<String>(); val statuses = mutableListOf<Pair<String,String>>()
    val lifecycle = readyStartupLifecycle()
    var decision: WalkSafeStartupCapabilityDecision? = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL, emptyList(), emptyList(), "actual notice")
    var foreground = true; var generation = 1; var actor = "actor"; var bypass = false; var quick = false
    var voiceAvailable = false; var resourceReady = false; var block: String? = null; var accepted = true; var throwOnWrite = false; var recoverPermission = false
    val ui = mutableListOf<() -> Unit>(); var done: (() -> Unit)? = null; var failed: (() -> Unit)? = null
    var cancelled: (() -> Unit)? = null; var retired: (() -> Unit)? = null; var current: (() -> Boolean)? = null
    val persisted = mutableListOf<WalkSafeStartupCapabilityDecision>()
    val controller = StartupConfirmationInteractionController({ trace += "lifecycle"; lifecycle },
        startupPort<StartupConfirmationFacts> { name, args -> trace += name; when(name) {
            "getDecision" -> decision; "getForeground" -> foreground; "getFeedbackGeneration" -> generation; "getActorId" -> actor
            "getBypass" -> bypass; "getQuickStart" -> quick; "getResourceProbeInitialized" -> resourceReady
            "feedbackCurrent" -> foreground && args[0] == generation; "onboardingComplete" -> true; "currentReporter" -> actor
            "resourceSnapshot" -> WalkSessionDeviceResourceSnapshot(true,true,true)
            "profileMatch" -> ApprovedDeviceProfileMatch(true,"profile","1",ApprovedDeviceProfileMatchReason.EXACT_MATCH)
            else -> error(name)
        } },
        startupPort<StartupConfirmationEffects> { name,args -> trace += name; when(name) {
            "readinessBlock" -> block; "resumePermissionRecovery" -> recoverPermission; "voiceResumeAvailable" -> voiceAvailable
            "status" -> { statuses += (args[0] as String) to (args[1] as String); Unit }
            "onUi" -> { @Suppress("UNCHECKED_CAST") val action=args[0] as () -> Unit; ui += action; Unit }
            "speakNotice" -> { @Suppress("UNCHECKED_CAST") val callbacks=args.drop(1).map { it as () -> Any? }
                done={callbacks[0]()}; failed={callbacks[1]()}; cancelled={callbacks[2]()}; retired={callbacks[3]()}; current={callbacks[4]() as Boolean}; accepted }
            "prepareResumeRetry", "startFreshWalk", "refresh", "logResources", "confirmResumeButton", "requestMissingPermissions" -> Unit
            else -> error(name)
        } },
        StartupDecisionStorage { value, _ -> trace += "write"; persisted += value; if(throwOnWrite) error("storage unavailable") },
    )
    fun drainUi() { val actions=ui.toList(); ui.clear(); actions.forEach { it() } }
}
