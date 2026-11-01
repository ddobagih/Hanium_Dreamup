package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import android.content.Context
import android.content.ContextWrapper
import kr.co.hanium.dreamup.walksafe.device.AndroidDeviceCheckAuthority
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthority
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckOwnerRef

/** Carries an owner ID to worker factories without retaining an Activity or using thread locals. */
class VisionRuntimeContext private constructor(base: Context, val visionOwnerId: String,
    val visionOwnerRef: DeviceCheckOwnerRef?, val visionDiagnosticAuthority: DeviceCheckAuthority?) : ContextWrapper(base.applicationContext) {
    constructor(base: Context, owner: DeviceCheckOwnerRef) : this(base, owner.ownerId, owner, null)
    constructor(base: Context, owner: DeviceCheckOwnerRef, diagnostics: DeviceCheckAuthority) :
        this(base, owner.ownerId, owner, diagnostics) {
        require(AndroidDeviceCheckAuthority.isDiagnostic(diagnostics) && diagnostics.ownerRef(owner.ownerId) === owner)
    }
    /** An ID alone is a non-authoritative context; it cannot acquire production native permission. */
    constructor(base: Context, ownerId: String) : this(base, ownerId, null, null)
    init { require(VisionRuntimeProtocol.KEY.matches(visionOwnerId)) }

    companion object {
        /** Resolve before applicationContext normalization; unscoped diagnostic hosts stay explicit. */
        @JvmStatic fun ownerId(context: Context): String {
            return scoped(context)?.visionOwnerId ?: "foreground"
        }
        @JvmStatic fun ownerRef(context: Context): DeviceCheckOwnerRef =
            scoped(context)?.visionOwnerRef ?: throw VisionStaleAuthorityException()
        @JvmStatic fun diagnosticAuthority(context: Context): DeviceCheckAuthority? = scoped(context)?.visionDiagnosticAuthority

        private fun scoped(context: Context): VisionRuntimeContext? {
            val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
            var current: Context? = context
            while (current != null && visited.add(current)) {
                if (current is VisionRuntimeContext) return current
                current = (current as? ContextWrapper)?.baseContext
            }
            return null
        }
    }
}
