package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import android.content.Context
import android.content.ContextWrapper

/** Carries an owner ID to worker factories without retaining an Activity or using thread locals. */
class VisionRuntimeContext(base: Context, val visionOwnerId: String) : ContextWrapper(base.applicationContext) {
    init { require(VisionRuntimeProtocol.KEY.matches(visionOwnerId)) }

    companion object {
        /** Resolve before applicationContext normalization; unscoped diagnostic hosts stay explicit. */
        @JvmStatic fun ownerId(context: Context): String {
            val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
            var current: Context? = context
            while (current != null && visited.add(current)) {
                if (current is VisionRuntimeContext) return current.visionOwnerId
                current = (current as? ContextWrapper)?.baseContext
            }
            return "foreground"
        }
    }
}
