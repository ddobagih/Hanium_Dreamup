package kr.co.hanium.dreamup.walksafe

/** UI owner: cold auxiliary preparation follows the current camera's first live primary attempt. */
internal class CameraAuxiliaryStartupCoordinator {
    private var epoch: Int? = null
    private var primaryLiveCompleted = false

    fun begin(epoch: Int) {
        if (this.epoch == epoch) return
        this.epoch = epoch
        primaryLiveCompleted = false
    }

    fun complete(epoch: Int): Boolean {
        if (this.epoch != epoch || primaryLiveCompleted) return false
        primaryLiveCompleted = true
        return true
    }

    fun mayStart(epoch: Int, primaryBusy: Boolean = false): Boolean =
        this.epoch == epoch && primaryLiveCompleted && !primaryBusy

    fun clear() {
        epoch = null
        primaryLiveCompleted = false
    }
}
