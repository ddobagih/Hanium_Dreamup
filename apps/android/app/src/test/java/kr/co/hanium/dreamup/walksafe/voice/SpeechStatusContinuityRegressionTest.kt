package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackAction
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import org.junit.Assert.*
import org.junit.Test

/** Executes the production output controller, actuator and terminal registry with native effects controlled. */
class SpeechStatusContinuityRegressionTest {
    @Test fun ordinaryStatusWaitsForTheWholeRouteSentenceAndCompletesOnlyOnItsOwnDone() {
        val f = readyFixture()
        var routeDone = 0
        var routeFailed = 0
        var statusDone = 0
        assertTrue(f.controller.dispatchNavigationSpeech("오른쪽 한 시 방향으로 30미터 이동하세요.",
            onCompleted = { routeDone++ }, onFailed = { routeFailed++ }))
        val route = f.runtime.spoken.single()
        f.runtime.start(route.id)
        assertTrue(f.controller.speakCommandResponse("추가 물체 인식 준비를 마쳤습니다.",
            statusExplanation = true, onCompleted = { statusDone++ }))
        f.clock.advanceBy(2_000L)
        assertEquals(1, f.runtime.spoken.size)
        assertEquals(0, routeFailed)
        assertEquals(0, f.runtime.stops)
        f.runtime.done(route.id)
        f.clock.advanceBy(100L)
        assertEquals(1, routeDone)
        assertEquals(2, f.runtime.spoken.size)
        assertEquals(0, statusDone)
        val status = f.runtime.spoken.last()
        f.runtime.start(status.id)
        f.runtime.done(status.id)
        f.runtime.done(route.id)
        f.runtime.done(status.id)
        assertEquals(1, routeDone)
        assertEquals(1, statusDone)
        assertEquals(0, routeFailed)
        assertFalse(f.controller.hasPendingCommandResponse())
        f.controller.closeActuator()
    }

    @Test fun aLongRouteSentenceDoesNotSpendTheStatusEngineReadinessDeadline() {
        val f = readyFixture()
        assertTrue(f.controller.dispatchNavigationSpeech("진행 방향으로 안전하게 이동하세요. ".repeat(20)))
        val route = f.runtime.spoken.single()
        f.runtime.start(route.id)
        var statusDone = 0
        var statusFailed = 0
        assertTrue(f.controller.speakCommandResponse("연결 상태가 복구되었습니다.", statusExplanation = true,
            onCompleted = { statusDone++ }, onFailed = { statusFailed++ }))
        f.clock.advanceBy(25_000L)
        assertEquals("tts=ready", f.controller.currentActuatorOrNull()!!.statusText())
        assertEquals(1, f.runtime.spoken.size)
        assertTrue(f.controller.hasPendingCommandResponse())
        assertEquals(0, statusFailed)
        f.runtime.done(route.id)
        f.clock.advanceBy(100L)
        val status = f.runtime.spoken.last()
        f.runtime.start(status.id)
        f.runtime.done(status.id)
        assertEquals(1, statusDone)
        assertEquals(0, statusFailed)
        f.controller.closeActuator()
    }

    @Test fun aRepeatedAutomaticStatusKeepsOneWaitingOwnerAndOneNativeSentence() {
        val f = readyFixture()
        f.controller.dispatchNavigationSpeech("현재 경로를 따라 이동하세요.")
        val route = f.runtime.spoken.single()
        f.runtime.start(route.id)
        val text = "연결이 복구되어 안내를 계속합니다."
        assertTrue(f.controller.speakStatusExplanation(text))
        val generation = f.controller.currentCommandGeneration()
        repeat(5) {
            f.clock.advanceBy(100L)
            assertTrue(f.controller.speakStatusExplanation(text))
        }
        assertEquals(generation, f.controller.currentCommandGeneration())
        assertEquals(1, f.runtime.spoken.size)
        f.runtime.done(route.id)
        f.clock.advanceBy(100L)
        assertEquals(listOf(route.message, text), f.runtime.spoken.map { it.message })
        f.controller.closeActuator()
    }

    @Test fun statusChangedWhileWaitingCannotReplayAfterRouteCompletion() {
        for (change in listOf("source", "actor", "epoch", "background", "cancel")) {
            val f = readyFixture()
            f.controller.dispatchNavigationSpeech("현재 경로를 따라 이동하세요.")
            val route = f.runtime.spoken.single()
            f.runtime.start(route.id)
            var sourceCurrent = true
            var statusDone = 0
            f.controller.speakCommandResponse("이전 상태 안내", statusExplanation = true,
                additionalCurrent = { sourceCurrent }, onCompleted = { statusDone++ })
            when (change) {
                "source" -> sourceCurrent = false
                "actor" -> f.context = f.context.copy(actorId = "new actor")
                "epoch" -> f.context = f.context.copy(walkEpoch = f.context.walkEpoch!!.copy(recoveryGeneration = 1L))
                "background" -> f.lifetime = f.lifetime.copy(foreground = false)
                "cancel" -> f.controller.cancelCommandSpeechResponse()
            }
            f.clock.advanceBy(100L)
            assertEquals(change, 0, f.runtime.stops)
            f.runtime.done(route.id)
            f.clock.advanceBy(100L)
            assertEquals(change, 1, f.runtime.spoken.size)
            assertEquals(change, 0, statusDone)
            assertFalse(change, f.controller.hasPendingCommandResponse())
            f.controller.closeActuator()
        }
    }

    @Test fun urgentRiskPreemptsRouteButWaitingStatusWaitsForTheUrgentTerminalCallback() {
        val f = readyFixture()
        var routeDone = 0
        var routeFailed = 0
        f.controller.dispatchNavigationSpeech("경로를 따라 이동하세요.",
            onCompleted = { routeDone++ }, onFailed = { routeFailed++ })
        val route = f.runtime.spoken.single()
        f.runtime.start(route.id)
        f.controller.speakStatusExplanation("현재 연결 상태가 복구되었습니다.")
        val actor = f.controller.currentActuatorOrNull()!!
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
            actor.emit(risk(MessageLevel.STOP), null, null, { true }).speech)
        val urgent = f.runtime.spoken.last()
        assertEquals(1, routeFailed)
        assertEquals(0, routeDone)
        f.runtime.start(urgent.id)
        f.runtime.done(route.id)
        f.clock.advanceBy(100L)
        assertEquals(2, f.runtime.spoken.size)
        assertEquals(0, routeDone)
        f.runtime.done(urgent.id)
        f.clock.advanceBy(100L)
        assertEquals(3, f.runtime.spoken.size)
        val status = f.runtime.spoken.last()
        f.runtime.start(status.id)
        f.runtime.done(status.id)
        assertEquals(0, routeDone)
        assertEquals(1, routeFailed)
        assertFalse(f.controller.hasPendingCommandResponse())
        f.controller.closeActuator()
    }

    @Test fun nonurgentCautionCannotInterruptRouteAndCanRetryOnceItFinishes() {
        val f = readyFixture()
        var routeDone = 0
        var routeFailed = 0
        f.controller.dispatchNavigationSpeech("오른쪽 한 시 방향으로 이동하세요.",
            onCompleted = { routeDone++ }, onFailed = { routeFailed++ })
        val route = f.runtime.spoken.single()
        f.runtime.start(route.id)
        val actor = f.controller.currentActuatorOrNull()!!
        val caution = risk(MessageLevel.CAUTION)
        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, actor.emit(caution, null, null, { true }).speech)
        assertEquals(1, f.runtime.spoken.size)
        assertEquals(0, f.runtime.stops)
        assertEquals(0, routeFailed)
        f.runtime.done(route.id)
        assertEquals(1, routeDone)
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actor.emit(caution, null, null, { true }).speech)
        f.controller.closeActuator()
    }

    @Test fun actuatorStatusAdmissionCannotFlushAnOwnerThatAppearedAfterTheIdleCheck() {
        val f = readyFixture()
        val actor = f.controller.currentActuatorOrNull()!!
        actor.speakNavigation("진행 방향 안내")
        assertEquals(NavigationSpeechDispatchResult.SUPPRESSED,
            actor.speakGuardedHomeCommandInteraction("일반 상태", { true }, {}, deferToCurrentSpeech = true))
        assertEquals(1, f.runtime.spoken.size)
        assertEquals(0, f.runtime.stops)
        f.controller.closeActuator()
    }

    @Test fun fiveArgumentJvmGuardedEntryPointsStillRejectAStaleSourceBeforeNativeAdmission() {
        val f = readyFixture()
        val actor = f.controller.currentActuatorOrNull()!!
        val current: () -> Boolean = { false }
        val callback: () -> Unit = {}
        for (name in listOf("speakGuardedHomeCommandInteraction", "speakGuardedInteraction")) {
            val method = actor.javaClass.methods.singleOrNull { it.name == name && it.parameterCount == 5 }
            assertNotNull("Existing JVM callers must retain the guarded five-argument entry point: $name", method)
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED,
                method!!.invoke(actor, "폐기된 소유자의 안내", current, callback, callback, callback))
        }
        assertTrue(f.runtime.spoken.isEmpty())
        f.controller.closeActuator()
    }

    @Test fun automaticStatusCannotRetireAnInstalledStartOrMultipartCommandResponse() {
        val f = readyFixture()
        var commandDone = 0
        assertTrue(f.controller.speakCommandResponse("시작 안내", speechParts = listOf("시작 안내 첫 문장", "시작 안내 둘째 문장"),
            onCompleted = { commandDone++ }))
        val first = f.runtime.spoken.single()
        f.runtime.start(first.id)
        val generation = f.controller.currentCommandGeneration()
        assertTrue(f.controller.speakStatusExplanation("준비를 마쳤습니다."))
        assertEquals(generation, f.controller.currentCommandGeneration())
        f.clock.advanceBy(100L)
        assertEquals(1, f.runtime.spoken.size)
        f.runtime.done(first.id)
        assertEquals(2, f.runtime.spoken.size)
        val second = f.runtime.spoken.last()
        f.runtime.start(second.id)
        f.clock.advanceBy(100L)
        assertEquals(0, commandDone)
        assertEquals(2, f.runtime.spoken.size)
        f.runtime.done(second.id)
        assertEquals(1, commandDone)
        f.clock.advanceBy(100L)
        assertEquals(listOf("시작 안내 첫 문장", "시작 안내 둘째 문장", "준비를 마쳤습니다."), f.runtime.spoken.map { it.message })
        f.controller.closeActuator()
    }

    @Test fun onlyTheLatestDeferredStatusSurvivesAndANewCommandOrOwnerRetiresIt() {
        for (change in listOf("none", "command", "actor", "epoch", "page", "background", "cancel", "close")) {
            val f = readyFixture()
            f.controller.speakCommandResponse("사용자 명령 결과")
            val command = f.runtime.spoken.single()
            f.runtime.start(command.id)
            f.controller.speakStatusExplanation("이전 상태")
            f.controller.speakStatusExplanation("최신 상태")
            when (change) {
                "command" -> f.controller.speakCommandResponse("새 사용자 명령")
                "actor" -> f.context = f.context.copy(actorId = "new")
                "epoch" -> f.context = f.context.copy(walkEpoch = f.context.walkEpoch!!.copy(recoveryGeneration = 1L))
                "page" -> f.context = f.context.copy(page = SpeechOutputControllerBehaviorTest.Page.SETTINGS, voiceInteractionPage = false)
                "background" -> f.lifetime = f.lifetime.copy(foreground = false)
                "cancel" -> f.controller.cancelCommandSpeechResponse()
                "close" -> f.controller.closeActuator()
            }
            f.runtime.done(command.id)
            f.clock.advanceBy(100L)
            assertFalse(change, f.runtime.spoken.any { it.message == "이전 상태" })
            assertEquals(change, if (change == "none") 1 else 0,
                f.runtime.spoken.count { it.message == "최신 상태" })
            f.controller.closeActuator()
        }
    }

    @Test fun aBusyRaceBeforeTheSecondStatusPartResumesThatPartWithoutRepeatingTheFirst() {
        val f = readyFixture()
        var injectRace = false
        var raced = false
        var statusDone = 0
        f.controller.speakCommandResponse("전체 상태", statusExplanation = true,
            speechParts = listOf("상태 첫 문장", "상태 둘째 문장"), onCompleted = { statusDone++ },
            additionalCurrent = {
                if (injectRace && !raced) {
                    raced = true
                    f.controller.dispatchNavigationSpeech("경합한 경로 문장")
                }
                true
            })
        val first = f.runtime.spoken.single()
        f.runtime.start(first.id)
        injectRace = true
        f.runtime.done(first.id)
        assertTrue(raced)
        assertEquals(listOf("상태 첫 문장", "경합한 경로 문장"), f.runtime.spoken.map { it.message })
        val route = f.runtime.spoken.last()
        f.runtime.start(route.id)
        f.clock.advanceBy(100L)
        assertEquals(2, f.runtime.spoken.size)
        f.runtime.done(route.id)
        f.clock.advanceBy(100L)
        assertEquals(listOf("상태 첫 문장", "경합한 경로 문장", "상태 둘째 문장"), f.runtime.spoken.map { it.message })
        val second = f.runtime.spoken.last()
        f.runtime.start(second.id)
        f.runtime.done(second.id)
        assertEquals(1, statusDone)
        f.controller.closeActuator()
    }

    @Test fun aNewCurrentStatusSurvivesLateCancellationOfAStaleCommandOwner() {
        for (change in listOf("actor", "epoch", "source")) {
            val f = readyFixture()
            var sourceCurrent = true
            var oldDone = 0
            var currentDone = 0
            f.controller.speakCommandResponse("이전 명령 응답", onCompleted = { oldDone++ },
                additionalCurrent = { sourceCurrent })
            val old = f.runtime.spoken.single()
            when (change) {
                "actor" -> f.context = f.context.copy(actorId = "replacement")
                "epoch" -> f.context = f.context.copy(walkEpoch = f.context.walkEpoch!!.copy(recoveryGeneration = 1L))
                "source" -> sourceCurrent = false
            }
            assertTrue(f.controller.speakCommandResponse("새 현재 상태", statusExplanation = true,
                onCompleted = { currentDone++ }))
            f.runtime.start(old.id)
            f.runtime.done(old.id)
            f.clock.advanceBy(100L)
            assertEquals(change, 2, f.runtime.spoken.size)
            val current = f.runtime.spoken.last()
            assertEquals("새 현재 상태", current.message)
            f.runtime.start(current.id)
            f.runtime.done(old.id)
            assertEquals(change, 0, oldDone)
            assertEquals(change, 0, currentDone)
            f.runtime.done(current.id)
            assertEquals(change, 1, currentDone)
            assertFalse(change, f.controller.hasPendingCommandResponse())
            f.controller.closeActuator()
        }
    }

    @Test fun aDeferredCurrentStatusSurvivesItsPredecessorSourceBeingRevokedLater() {
        val f = readyFixture()
        var oldSourceCurrent = true
        var oldDone = 0
        var statusDone = 0
        f.controller.speakCommandResponse("원래 명령 응답", additionalCurrent = { oldSourceCurrent },
            onCompleted = { oldDone++ })
        val old = f.runtime.spoken.single()
        f.controller.speakCommandResponse("현재 상태 안내", statusExplanation = true,
            onCompleted = { statusDone++ })
        assertEquals(1, f.runtime.spoken.size)
        oldSourceCurrent = false
        f.runtime.start(old.id)
        f.runtime.done(old.id)
        f.clock.advanceBy(100L)
        assertEquals(2, f.runtime.spoken.size)
        val current = f.runtime.spoken.last()
        assertEquals("현재 상태 안내", current.message)
        f.runtime.start(current.id)
        f.runtime.done(old.id)
        assertEquals(0, oldDone)
        assertEquals(0, statusDone)
        f.runtime.done(current.id)
        f.runtime.done(old.id)
        assertEquals(0, oldDone)
        assertEquals(1, statusDone)
        assertFalse(f.controller.hasPendingCommandResponse())
        f.controller.closeActuator()
    }

    @Test fun aDeferredStatusWaitsForTheRiskThatCancelledItsPredecessorAndKeepsItsOwnDone() {
        val f = readyFixture()
        var oldDone = 0
        var statusDone = 0
        var riskDone = 0
        f.controller.speakCommandResponse("사용자 명령 응답", onCompleted = { oldDone++ })
        val old = f.runtime.spoken.single()
        f.runtime.start(old.id)
        f.controller.speakCommandResponse("현재 상태 안내", statusExplanation = true,
            onCompleted = { statusDone++ })
        val actor = f.controller.currentActuatorOrNull()!!
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
            actor.emit(risk(MessageLevel.STOP), { riskDone++ }, null, { true }).speech)
        val urgent = f.runtime.spoken.last()
        f.runtime.start(urgent.id)
        f.runtime.done(old.id)
        f.clock.advanceBy(100L)
        assertEquals(2, f.runtime.spoken.size)
        assertEquals(0, oldDone)
        assertEquals(0, statusDone)
        f.runtime.done(urgent.id)
        assertEquals(1, riskDone)
        f.clock.advanceBy(100L)
        assertEquals(3, f.runtime.spoken.size)
        val current = f.runtime.spoken.last()
        assertEquals("현재 상태 안내", current.message)
        f.runtime.start(current.id)
        f.runtime.done(old.id)
        f.runtime.done(urgent.id)
        assertEquals(0, statusDone)
        f.runtime.done(current.id)
        assertEquals(0, oldDone)
        assertEquals(1, statusDone)
        assertEquals(1, riskDone)
        assertFalse(f.controller.hasPendingCommandResponse())
        f.controller.closeActuator()
    }

    @Test fun aReentrantStatusPredicateCannotDiscardTheNewerDeferredStatusItPublished() {
        val f = readyFixture()
        var allowReentry = false
        var replaced = false
        var obsoleteDone = 0
        var latestDone = 0
        f.controller.speakCommandResponse("진행 중인 사용자 명령")
        val command = f.runtime.spoken.single()
        f.runtime.start(command.id)
        assertTrue(f.controller.speakCommandResponse("이전 상태", statusExplanation = true,
            onCompleted = { obsoleteDone++ }, additionalCurrent = {
                if (allowReentry && !replaced) {
                    replaced = true
                    assertTrue(f.controller.speakCommandResponse("더 최신 상태", statusExplanation = true,
                        onCompleted = { latestDone++ }))
                }
                !replaced
            }))
        allowReentry = true
        f.clock.advanceBy(100L)
        assertTrue(replaced)
        assertEquals(1, f.runtime.spoken.size)
        assertEquals(0, f.runtime.stops)
        f.runtime.done(command.id)
        f.clock.advanceBy(100L)
        assertEquals("A superseded predicate must leave the latest deferred registration intact", 2, f.runtime.spoken.size)
        val latest = f.runtime.spoken.last()
        assertEquals("더 최신 상태", latest.message)
        f.runtime.start(latest.id)
        f.runtime.done(command.id)
        assertEquals(0, obsoleteDone)
        assertEquals(0, latestDone)
        f.runtime.done(latest.id)
        f.runtime.done(latest.id)
        assertEquals(0, obsoleteDone)
        assertEquals(1, latestDone)
        assertFalse(f.controller.hasPendingCommandResponse())
        assertFalse(f.runtime.spoken.any { it.message == "이전 상태" })
        f.controller.closeActuator()
    }

    private fun readyFixture() = SpeechOutputControllerBehaviorTest.Fixture().also {
        it.controller.ensureFeedbackActuator()
        it.runtime.ready()
    }

    private fun risk(level: MessageLevel) = FeedbackAction(
        trackId = "obstacle", message = if (level == MessageLevel.STOP) "전방 장애물. 즉시 멈추세요." else "전방 장애물을 주의하세요.",
        level = level, vibrationPatternMs = null, reason = "continuity_test", validUntilMs = Long.MAX_VALUE,
    )
}
