package kr.co.hanium.dreamup.walksafe.session

/**
 * Owner decision 3(b) (2026-09-29, sweep/owner-decisions-20260929.md): deferred transfers (RAW
 * segments, queued reports, DEBUG activity originals) start only after the walk was explicitly
 * ended. ENDED (the user ended it) and READY (a fresh walk id after a new walk request or a process
 * restart; FP-035 lifecycle.resume never resumes the earlier walk) are after the walk. Root decision
 * R4 (transfer-F2): SAFE_STOP is a stop the system imposed while the user is usually still on the
 * way, not an explicit end, so nothing is sent until the user ends the walk (-> ENDED) or starts a
 * new one (-> READY); the RAW segment is still sealed at SAFE_STOP ([isAfterWalkSealPoint]).
 * ACTIVE and PAUSED are the walk itself. A walk that becomes ACTIVE again stops every in-flight
 * transfer at its next boundary.
 */
val WalkSessionState.allowsPostWalkTransfer: Boolean
    get() = this == WalkSessionState.ENDED || this == WalkSessionState.READY

/** The walk is over for its RAW segment (sealed and kept): every state except ACTIVE and PAUSED. */
val WalkSessionState.isAfterWalkSealPoint: Boolean
    get() = this != WalkSessionState.ACTIVE && this != WalkSessionState.PAUSED
