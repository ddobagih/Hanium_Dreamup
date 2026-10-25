package kr.co.hanium.dreamup.walksafe.voice.input

import kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult

/** Input arbitration and command ownership remain with VoiceInputSessionController. */
internal interface HandsFreeRuntimeInput {
    fun refreshWakeAndHasProbe(): Boolean
    fun otherInputBusy(): Boolean
    fun outputActive(): Boolean
    fun recognitionBlocked(): Boolean
    fun modelPrepared()
    fun recognizedCommand(text: String, confidence: Float?)
    fun commandListeningStarted()
    fun clearCommandCapture()
    fun commandWorkPending(commandId: Long): Boolean
    fun commandSpeechRevision(commandId: Long): Long = 0L
    fun commandSpeechProgress(commandId: Long): kr.co.hanium.dreamup.walksafe.voice.CommandSpeechProgress = kr.co.hanium.dreamup.walksafe.voice.CommandSpeechProgress.NOT_TRACKED
    fun lowConfidenceReprompt(onFinished: (WakeAcknowledgementResult) -> Unit)
    /** Called only after model completion has been retired, before service stop/detach. */
    fun runtimeRetired()
}
