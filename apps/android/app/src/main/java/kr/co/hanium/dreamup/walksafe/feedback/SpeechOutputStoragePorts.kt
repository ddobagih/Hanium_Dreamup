package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinator
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.fieldlog.FieldSessionLog
import kr.co.hanium.dreamup.walksafe.fieldlog.PersistentFieldSessionLog
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*

internal fun interface SpeechStorageCallbacks { fun post(callback: () -> Unit): Boolean }
internal interface SpeechStorageQueries {
    fun startupDestroyed(): Boolean
    fun foreground(): Boolean
    fun lifecycleInitialized(): Boolean
    fun currentActuator(): AndroidFeedbackActuator?
    fun feedbackGeneration(): Int
    fun observedPermissions(): ObservedPermissionSnapshot
    fun deviceCheck(): PostLoginDeviceCheckSnapshot
    fun fieldParticipantOrNull(): FieldSessionLog?
    fun filesDirectory(): File
    fun accountBlockedAtStartup(): Boolean
    fun rawBlockedAtStartup(): Boolean
    fun requiredObservedPermissions(): Set<ObservedPermission>
}
