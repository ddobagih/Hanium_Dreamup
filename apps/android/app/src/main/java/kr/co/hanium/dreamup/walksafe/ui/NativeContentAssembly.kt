package kr.co.hanium.dreamup.walksafe.ui

/** The six existing screen layers and the separate accessibility status row. */
internal object NativeContentAssembly {
    data class Layers<T>(
        val surface: T,
        val fallbackPreview: T,
        val debugOverlay: T,
        val controls: T,
        val walkSafety: T,
        val cameraControls: T,
        val recognitionOverlay: T? = null,
        val recognitionControls: T? = null,
    )

    interface Target<T> {
        fun createContentFrame(): T
        fun addFullscreen(parent: T, child: T, topAligned: Boolean)
        fun addCameraControls(parent: T, child: T)
        fun createRootFrame(): T
        fun createStatusColumn(): T
        fun addWeightedContent(column: T, content: T)
        fun addAccessibilityRow(column: T, status: T)
        fun attachStatusColumn(root: T, column: T)
    }

    fun <T> compose(layers: Layers<T>, target: Target<T>, accessibilityStatus: () -> T): T {
        val content = target.createContentFrame()
        target.addFullscreen(content, layers.surface, false)
        target.addFullscreen(content, layers.fallbackPreview, false)
        target.addFullscreen(content, layers.debugOverlay, false)
        layers.recognitionOverlay?.let { target.addFullscreen(content, it, false) }
        target.addFullscreen(content, layers.controls, true)
        target.addFullscreen(content, layers.walkSafety, true)
        target.addCameraControls(content, layers.cameraControls)
        layers.recognitionControls?.let { target.addCameraControls(content, it) }
        val root = target.createRootFrame()
        val column = target.createStatusColumn()
        // A separate row keeps semantic updates from covering camera or controls.
        target.addWeightedContent(column, content)
        target.addAccessibilityRow(column, accessibilityStatus())
        target.attachStatusColumn(root, column)
        return root
    }
}
