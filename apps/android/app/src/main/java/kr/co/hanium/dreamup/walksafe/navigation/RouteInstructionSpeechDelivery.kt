package kr.co.hanium.dreamup.walksafe.navigation

import java.util.concurrent.atomic.AtomicBoolean

/** Main and callback tests share this action-completion boundary; INFO never retries the action. */
internal fun dispatchRouteInstructionSpeech(
    update: RouteNavigatorUpdate,
    isActionCurrent: () -> Boolean,
    isInformationCurrent: (RouteInformationUtterance) -> Boolean,
    reserveInformation: (RouteInformationUtterance) -> Boolean,
    finishInformation: (RouteInformationUtterance) -> Unit,
    onActionCompleted: () -> Unit,
    onActionFailed: () -> Unit,
    speak: (String, () -> Boolean, () -> Unit, () -> Unit) -> Boolean,
): Boolean {
    val action = update.instruction ?: return false
    val actionTerminal = AtomicBoolean(false)
    val actionCurrent = { !actionTerminal.get() && runCatching(isActionCurrent).getOrDefault(false) }
    val failAction = {
        if (actionTerminal.compareAndSet(false, true)) onActionFailed()
        Unit
    }
    val completeAction = completion@{
        if (!actionTerminal.compareAndSet(false, true)) return@completion
        onActionCompleted()
        val information = update.infoUtterance ?: return@completion
        // Reserve first so a duplicate owner cannot finish somebody else's in-flight INFO.
        // The producer also checks that this exact parent action was successfully acknowledged.
        if (!reserveInformation(information)) return@completion
        val informationTerminal = AtomicBoolean(false)
        val informationCurrent = {
            !informationTerminal.get() && runCatching { isInformationCurrent(information) }.getOrDefault(false)
        }
        val finish = {
            if (informationTerminal.compareAndSet(false, true)) finishInformation(information)
            Unit
        }
        if (!informationCurrent()) {
            finish()
            return@completion
        }
        if (!speak(information.message, informationCurrent, finish, finish)) finish()
    }
    if (!actionCurrent()) {
        failAction()
        return false
    }
    val accepted = speak(action, actionCurrent, completeAction, failAction)
    if (!accepted) failAction()
    return accepted
}
