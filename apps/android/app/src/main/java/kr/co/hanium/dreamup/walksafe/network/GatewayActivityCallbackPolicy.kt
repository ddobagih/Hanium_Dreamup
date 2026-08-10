package kr.co.hanium.dreamup.walksafe.network

internal fun runGatewayActivityCallbackIfCurrent(
    activityDestroyed: Boolean,
    expectedLeaseIsCurrent: Boolean,
    callback: () -> Unit,
): Boolean {
    if (activityDestroyed || !expectedLeaseIsCurrent) return false
    callback()
    return true
}
