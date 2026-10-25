package kr.co.hanium.dreamup.walksafe.voice

internal enum class CommandSpeechProgress { NOT_TRACKED, NO_OUTPUT, PENDING, COMPLETED, FAILED, RETIRED }

/** Observation only: native output's existing owner remains the authority for every terminal. */
internal class CommandSpeechTracker {
    internal data class Response(val commandId: Long, val id: String, val partCount: Int)
    private var commandId: Long? = null
    private val parts = mutableMapOf<String, Boolean?>()
    private var failed = false
    private var revision = 0L

    @Synchronized fun begin(command: Long) { commandId = command; parts.clear(); failed = false; revision++ }
    @Synchronized fun submit(responseId: String, partCount: Int): Response? {
        val command = commandId ?: return null
        require(partCount > 0)
        repeat(partCount) { parts["$responseId:$it"] = null }
        revision++
        return Response(command, responseId, partCount)
    }
    @Synchronized fun terminalPart(response: Response?, index: Int, completed: Boolean) {
        if (response == null || response.commandId != commandId || index !in 0 until response.partCount) return
        val key = "${response.id}:$index"
        if (!parts.containsKey(key) || parts[key] != null) return
        parts[key] = completed
        revision++
        if (!completed) failed = true
    }
    @Synchronized fun terminal(response: Response?, completed: Boolean) {
        if (response == null || response.commandId != commandId) return
        repeat(response.partCount) { terminalPart(response, it, completed) }
    }
    @Synchronized fun retireResponse(responseId: String) {
        parts.keys.toList().filter { it.startsWith("$responseId:") }.forEach {
            if (parts[it] == null) { parts[it] = false; failed = true; revision++ }
        }
    }
    @Synchronized fun retirePending() {
        parts.keys.toList().forEach { if (parts[it] == null) { parts[it] = false; failed = true; revision++ } }
    }
    @Synchronized fun revision(command: Long): Long = if (command == commandId) revision else -1L
    @Synchronized fun progress(command: Long): CommandSpeechProgress = when {
        commandId == null -> CommandSpeechProgress.NOT_TRACKED
        command != commandId -> CommandSpeechProgress.RETIRED
        parts.isEmpty() -> CommandSpeechProgress.NO_OUTPUT
        parts.values.any { it == null } -> CommandSpeechProgress.PENDING
        failed -> CommandSpeechProgress.FAILED
        else -> CommandSpeechProgress.COMPLETED
    }
}
