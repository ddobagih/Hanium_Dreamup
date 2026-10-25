package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.session.gateway.ActorSessionLifecycleCoordinator
import kr.co.hanium.dreamup.walksafe.voice.VoiceInputOutputJourneyBehaviorTest
import org.junit.Test

/** Actual Main-created actor-session Runtime adapter plus the real input/output/actuator fixture.
 * Does not execute onCreate or OS microphone. SDK/fact inputs stay within the voice fixture.
 */
class MainVoiceProcessTransitionWiringTest {
    @Test fun mainActorSessionNotificationRetiresListeningInputBeforeAnyOldSdkCallback() {
        VoiceInputOutputJourneyBehaviorTest().verifyMainProcessTransition { input, snapshot ->
            val main = MainActivity()
            MainActivity::class.java.getDeclaredField("voiceInputController\$delegate")
                .apply { isAccessible = true }.set(main, lazyOf(input))
            val ownerLazy = MainActivity::class.java.getDeclaredField("actorSessionLifecycleCoordinator\$delegate")
                .apply { isAccessible = true }.get(main) as Lazy<*>
            val owner = ownerLazy.value as ActorSessionLifecycleCoordinator
            val runtime = ActorSessionLifecycleCoordinator::class.java.getDeclaredField("runtime")
                .apply { isAccessible = true }.get(owner) as ActorSessionLifecycleCoordinator.Runtime
            runtime.voiceGatewayChanged(snapshot)
        }
    }
}
