package kr.co.hanium.dreamup.walksafe.voice

internal enum class CommandSpeechState { NONE, PENDING, COMPLETED, FAILED, CANCELLED }
internal data class CommandSpeechProgress(val state: CommandSpeechState, val revision: Long)

/** One command's latest response, including its asynchronous search/route result. Main-thread owned. */
internal class CommandSpeechTracker {
    internal class Owner(val commandId: Long) {
        var progress = CommandSpeechProgress(CommandSpeechState.NONE, 0)
        var attempt: Attempt? = null
    }
    internal class Attempt(val owner: Owner)
    private var current: Owner? = null

    fun begin(commandId: Long): Owner = Owner(commandId).also { current = it }
    fun start(owner: Owner?): Attempt? {
        if (owner == null || owner !== current) return null
        return Attempt(owner).also {
            owner.attempt = it
            owner.progress = CommandSpeechProgress(CommandSpeechState.PENDING, owner.progress.revision + 1)
        }
    }
    fun partCompleted(attempt: Attempt?) {
        if (attempt == null || current !== attempt.owner || attempt.owner.attempt !== attempt) return
        attempt.owner.progress = attempt.owner.progress.copy(revision = attempt.owner.progress.revision + 1)
    }
    fun finish(attempt: Attempt?, state: CommandSpeechState) {
        require(state in setOf(CommandSpeechState.COMPLETED, CommandSpeechState.FAILED, CommandSpeechState.CANCELLED))
        if (attempt == null || current !== attempt.owner || attempt.owner.attempt !== attempt) return
        attempt.owner.attempt = null
        attempt.owner.progress = CommandSpeechProgress(state, attempt.owner.progress.revision + 1)
    }
    fun progress(commandId: Long): CommandSpeechProgress = current?.takeIf { it.commandId == commandId }?.progress
        ?: CommandSpeechProgress(CommandSpeechState.CANCELLED, 0)
}
