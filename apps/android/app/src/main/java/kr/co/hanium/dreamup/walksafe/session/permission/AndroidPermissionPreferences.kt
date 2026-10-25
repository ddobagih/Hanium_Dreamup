package kr.co.hanium.dreamup.walksafe.session.permission

import android.content.SharedPreferences
import kr.co.hanium.dreamup.walksafe.session.ObservedPermission
import kr.co.hanium.dreamup.walksafe.session.PermissionRecoveryGate
import kr.co.hanium.dreamup.walksafe.session.PermissionRecoveryGateState

internal class AndroidPermissionPreferences(
    private val preferences: () -> SharedPreferences,
    private val initialized: () -> Boolean,
    private val recoveryStateKey: String,
    private val recoveryItemsKey: String,
    private val initialStartedKey: String,
    private val initialCompletedKey: String,
) : PermissionFlowPreferences {
    override fun ready() = initialized()
    override fun restoreRecovery(): PermissionRecoveryGate {
        val persisted = preferences().getString(recoveryStateKey, null)?.let {
            runCatching { PermissionRecoveryGateState.valueOf(it) }.getOrNull()
        }
        if (persisted == null || persisted == PermissionRecoveryGateState.CLEAR) return PermissionRecoveryGate()
        val affected = preferences().getString(recoveryItemsKey, "").orEmpty().split(',').mapNotNull {
            runCatching { ObservedPermission.valueOf(it) }.getOrNull()
        }.toSet()
        return PermissionRecoveryGate.restoredBlocked(affected)
    }
    override fun persistRecovery(gate: PermissionRecoveryGate) {
        val editor = preferences().edit()
        if (gate.state == PermissionRecoveryGateState.CLEAR) {
            editor.remove(recoveryStateKey).remove(recoveryItemsKey)
        } else {
            editor.putString(recoveryStateKey, gate.state.name).putString(recoveryItemsKey,
                gate.affectedPermissions.sortedBy(ObservedPermission::ordinal).joinToString(",") { it.name })
        }
        editor.commit()
    }
    override fun initialStarted() = preferences().getBoolean(initialStartedKey, false)
    override fun initialCompleted() = preferences().getBoolean(initialCompletedKey, false)
    override fun markInitialStarted() = preferences().edit().putBoolean(initialStartedKey, true).commit()
    override fun clearInitialStarted() { preferences().edit().remove(initialStartedKey).commit() }
    override fun markInitialCompleted() = preferences().edit().putBoolean(initialCompletedKey, true).remove(initialStartedKey).commit()
}
