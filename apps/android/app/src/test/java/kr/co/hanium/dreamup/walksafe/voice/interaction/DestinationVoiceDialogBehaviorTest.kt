package kr.co.hanium.dreamup.walksafe.voice.interaction

import kr.co.hanium.dreamup.walksafe.navigation.*
import org.junit.Assert.*
import org.junit.Test

class DestinationVoiceDialogBehaviorTest {
    @Test fun slowDetailRestoresFullAnswerWindowAndOnlyCurrentDoneStartsInput() {
        val f = InteractionFixture(); val page = f.showPage()
        f.controllers.destinations.speakHomeDestinationVoicePrompt(page); f.responses.last().done!!.invoke()
        f.now += 29_000
        f.controllers.destinations.speakVoiceDestinationCandidateDetail(1)
        val detail = f.responses.last()
        f.now += 45_000
        assertSame(page, f.controllers.destinations.currentVoiceDestinationDialogState())
        val starts = f.microphoneStarts
        detail.done!!.invoke(); detail.done!!.invoke()
        assertEquals(starts + 1, f.microphoneStarts)
        f.now += 29_999; assertSame(page, f.controllers.destinations.currentVoiceDestinationDialogState())
        f.now++; assertNull(f.controllers.destinations.currentVoiceDestinationDialogState())
    }
    @Test fun cancelledOrStaleDetailNeverStartsAnotherMicrophone() {
        for (stale in listOf(false, true)) {
            val f = InteractionFixture(); val page = f.showPage()
            f.controllers.destinations.speakHomeDestinationVoicePrompt(page); f.responses.last().done!!.invoke()
            f.controllers.destinations.speakVoiceDestinationCandidateDetail(1)
            val detail = f.responses.last(); val starts = f.microphoneStarts
            if (stale) f.actor = "different-actor"
            detail.cancelled!!.invoke(); detail.done!!.invoke()
            assertEquals(starts, f.microphoneStarts)
        }
    }

    @Test fun acceptedPromptDoesNotAuthorizeSelectionBeforeActualDone() {
        val f=InteractionFixture();val page=f.showPage()
        assertTrue(f.controllers.destinations.speakHomeDestinationVoicePrompt(page))
        assertFalse(page.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
        assertEquals(0,f.microphoneStarts)
        f.responses.last().done!!.invoke()
        assertTrue(page.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
        assertEquals(1,f.microphoneStarts)
        f.controllers.destinations.selectVoiceDestinationCandidate(1)
        assertEquals("c1",f.pendingDestination?.id)
    }

    @Test fun fullAnswerWindowStartsAfterSlowPromptRatherThanAtDispatch() {
        val f=InteractionFixture();val page=f.showPage()
        f.controllers.destinations.speakHomeDestinationVoicePrompt(page)
        f.now+=45_000
        assertSame(page,f.controllers.destinations.currentVoiceDestinationDialogState())
        f.responses.last().done!!.invoke()
        f.now+=29_999;assertSame(page,f.controllers.destinations.currentVoiceDestinationDialogState())
        f.now++;assertNull(f.controllers.destinations.currentVoiceDestinationDialogState())
        assertFalse(page.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
    }

    @Test fun failureKeepsRetryWindowButNeverGrantsPageSelectionAuthority() {
        val f=InteractionFixture();val page=f.showPage()
        f.controllers.destinations.speakHomeDestinationVoicePrompt(page)
        f.responses.last().failed!!.invoke()
        assertSame(page,f.controllers.destinations.currentVoiceDestinationDialogState())
        assertFalse(page.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
        assertEquals(0,f.microphoneStarts)
        assertNull(f.range)
        assertTrue(f.controllers.destinations.retryHomeDestinationVoiceDialog("다시 안내합니다."))
        f.responses.last().done!!.invoke()
        assertTrue(page.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
    }

    @Test fun priorPageAndActorCallbacksCannotAuthorizeNewPageOrClearItsRange() {
        val f=InteractionFixture();val first=f.showPage()
        f.controllers.destinations.speakHomeDestinationVoicePrompt(first);val old=f.responses.last()
        val second=f.showPage();f.controllers.destinations.speakHomeDestinationVoicePrompt(second)
        old.cancelled!!.invoke();old.done!!.invoke()
        assertSame(second,f.range)
        assertFalse(first.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
        assertFalse(second.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
        f.actor="replacement";f.responses.last().done!!.invoke()
        assertFalse(second.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
        assertEquals(0,f.microphoneStarts)
    }

    @Test fun ageNoticeIsConsumedOnlyByActualSuccessfulCompletion() {
        val f=InteractionFixture();f.now=200_000
        val origin=DestinationSearchOrigin(RoutePoint(37.0,127.0),10f,0)
        val page=f.showPage(origin);val notice=destinationOriginAgeNoticeKo(origin,f.now)!!
        f.controllers.destinations.speakHomeDestinationVoicePrompt(page)
        assertTrue(f.responses.last().message.contains(notice));f.responses.last().failed!!.invoke()
        f.controllers.destinations.speakHomeDestinationVoicePrompt(page)
        assertTrue(f.responses.last().message.contains(notice));f.responses.last().done!!.invoke()
        f.controllers.destinations.speakHomeDestinationVoicePrompt(page)
        assertFalse(f.responses.last().message.contains(notice))
    }

    @Test fun nextAndPreviousUseSameSearchOriginAndOrderWithoutNetwork() {
        val f=InteractionFixture();val page=f.showPage(DestinationSearchOrigin(RoutePoint(37.0,127.0),10f,0))
        f.controllers.destinations.speakHomeDestinationVoicePrompt(page);f.responses.last().done!!.invoke()
        f.controllers.destinations.hearMoreVoiceDestinationCandidates();val next=f.search.state.voicePage!!
        assertEquals(1,next.pageIndex);assertSame(page.origin,next.origin);assertEquals(page.results,next.results)
        assertFalse(next.onCommand(DestinationSearchVoiceCommand.SelectCandidate(4)).accepted)
        f.responses.last().done!!.invoke()
        f.controllers.destinations.previousVoiceDestinationCandidates()
        assertEquals(0,f.search.state.voicePage!!.pageIndex)
        assertEquals(0,f.search.state.generation)
    }

    @Test fun walkingSelectionWaitsForSeparateStartAndRepeatDoesNotExtendWindow() {
        val f=InteractionFixture();f.home=false;f.routeRun.markRequestActive();val page=f.showPage()
        f.controllers.destinations.speakHomeDestinationVoicePrompt(page,listenAfterPrompt=false);f.responses.last().done!!.invoke()
        f.controllers.destinations.selectVoiceDestinationCandidate(1)
        assertTrue(f.controllers.destinations.hasWalkingSelection())
        assertFalse(f.events.any{it.startsWith("select:")})
        f.now+=29_000;assertTrue(f.controllers.destinations.repeatPendingWalkingDestinationChange())
        f.now+=1_001;assertTrue(f.controllers.destinations.startPendingWalkingDestinationChange())
        assertFalse(f.events.any{it.startsWith("select:")})
        assertTrue(f.routeRun.active)
        assertTrue(f.spoken.last().contains("확인 시간이 지났습니다"))
    }

    @Test fun currentWalkingSelectionIsConsumedOnceOnSeparateStart() {
        val f=InteractionFixture();f.home=false;val page=f.showPage()
        f.controllers.destinations.speakHomeDestinationVoicePrompt(page,listenAfterPrompt=false);f.responses.last().done!!.invoke()
        f.controllers.destinations.selectVoiceDestinationCandidate(2)
        assertTrue(f.controllers.destinations.startPendingWalkingDestinationChange())
        assertFalse(f.controllers.destinations.startPendingWalkingDestinationChange())
        assertEquals(listOf("select:c2"),f.events.filter{it.startsWith("select:")})
    }
}
