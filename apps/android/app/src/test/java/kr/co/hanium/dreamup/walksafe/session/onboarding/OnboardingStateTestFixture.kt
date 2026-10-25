package kr.co.hanium.dreamup.walksafe.session.onboarding

import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot

/** Real transition owner with unused side-effect ports for account request behavior tests. */
internal fun onboardingStateForTest(initial: FirstRunOnboardingSnapshot): FirstRunOnboardingController {
    val profiles = PriorityUserProfileController(noEffects(), noEffects(), { "a".repeat(64) },
        PriorityUserProfileController.Config("p", "i", 1, "test"))
    return FirstRunOnboardingController(profiles, noEffects(), noEffects(), noEffects(), { "a".repeat(64) })
        .also { it.restoreSessionSnapshot(initial) }
}

private inline fun <reified T> noEffects(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
    when (method.returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Void.TYPE -> null
        else -> null
    }
} as T
