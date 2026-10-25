package kr.co.hanium.dreamup.walksafe.ui

import org.junit.Assert.*
import org.junit.Test

class NativeContentAssemblyTest {
    @Test fun existingViewsKeepTheirIdentityAndCameraOcclusionOrder() {
        val target = RecordingTarget()
        val layers = layers()
        val status = Node("accessibility")
        val root = NativeContentAssembly.compose(layers, target) { status }
        assertEquals(listOf(layers.surface, layers.fallbackPreview, layers.debugOverlay,
            layers.controls, layers.walkSafety, layers.cameraControls), target.content.children)
        assertEquals(listOf(false, false, false, true, true), target.fullscreenAlignment)
        assertSame(target.column, root.children.single())
        assertEquals(listOf(target.content, status), target.column.children)
        assertFalse(target.content.children.contains(status))
        assertSame(status, target.column.children[1])
    }

    @Test fun accessibilitySdkSupplierWaitsUntilAllLayersAndTheWeightedContentRowExist() {
        val target = RecordingTarget()
        var calls = 0
        NativeContentAssembly.compose(layers(), target) {
            calls++
            assertEquals(6, target.content.children.size)
            assertEquals(listOf(target.content), target.column.children)
            assertTrue(target.root.children.isEmpty())
            target.trace += "statusSupplier"
            Node("accessibility")
        }
        assertEquals(1, calls)
        assertTrue(target.trace.indexOf("weightedContent") < target.trace.indexOf("statusSupplier"))
        assertTrue(target.trace.indexOf("statusSupplier") < target.trace.indexOf("accessibilityRow"))
        assertTrue(target.trace.indexOf("accessibilityRow") < target.trace.indexOf("attachColumn"))
    }

    @Test fun cameraLayerFailureDoesNotCreateTheOuterFrameOrReadTheLateSdkSupplier() {
        val target = RecordingTarget()
        target.failCameraControls = true
        try {
            NativeContentAssembly.compose(layers(), target) { error("Late supplier must remain unread") }
            fail("Expected layer attachment failure")
        } catch (expected: IllegalArgumentException) {
            assertEquals("camera attachment", expected.message)
        }
        assertFalse(target.trace.contains("createRoot"))
        assertFalse(target.trace.contains("weightedContent"))
    }

    @Test fun failedAccessibilitySupplierLeavesTheColumnUnattached() {
        val target = RecordingTarget()
        try {
            NativeContentAssembly.compose(layers(), target) { throw IllegalStateException("status unavailable") }
            fail("Expected supplier failure")
        } catch (expected: IllegalStateException) {
            assertEquals("status unavailable", expected.message)
        }
        assertEquals(listOf(target.content), target.column.children)
        assertTrue(target.root.children.isEmpty())
        assertFalse(target.trace.contains("attachColumn"))
    }

    @Test fun anAlreadyAttachedLayerIsNotSilentlyReparentedBeforeAccessibilityCreation() {
        val target = RecordingTarget()
        val layers = layers()
        val oldParent = Node("old parent")
        oldParent.add(layers.surface)
        try {
            NativeContentAssembly.compose(layers, target) { error("Must not create status after attachment failure") }
            fail("Expected existing-parent rejection")
        } catch (expected: IllegalArgumentException) {
            assertEquals("existing parent", expected.message)
        }
        assertSame(oldParent, layers.surface.parent)
        assertTrue(target.content.children.isEmpty())
    }

    private fun layers() = NativeContentAssembly.Layers(Node("surface"), Node("fallback"), Node("debug"),
        Node("controls"), Node("walk"), Node("camera controls"))

    private class Node(val label: String) {
        var parent: Node? = null
        val children = mutableListOf<Node>()
        fun add(child: Node) {
            require(child.parent == null) { "existing parent" }
            children += child
            child.parent = this
        }
    }

    private class RecordingTarget : NativeContentAssembly.Target<Node> {
        val content = Node("content")
        val root = Node("root")
        val column = Node("status column")
        val trace = mutableListOf<String>()
        val fullscreenAlignment = mutableListOf<Boolean>()
        var failCameraControls = false
        override fun createContentFrame(): Node { trace += "createContent"; return content }
        override fun addFullscreen(parent: Node, child: Node, topAligned: Boolean) {
            parent.add(child); fullscreenAlignment += topAligned; trace += "fullscreen:${child.label}"
        }
        override fun addCameraControls(parent: Node, child: Node) {
            if (failCameraControls) throw IllegalArgumentException("camera attachment")
            parent.add(child); trace += "cameraControls"
        }
        override fun createRootFrame(): Node { trace += "createRoot"; return root }
        override fun createStatusColumn(): Node { trace += "createColumn"; return column }
        override fun addWeightedContent(column: Node, content: Node) { column.add(content); trace += "weightedContent" }
        override fun addAccessibilityRow(column: Node, status: Node) { column.add(status); trace += "accessibilityRow" }
        override fun attachStatusColumn(root: Node, column: Node) { root.add(column); trace += "attachColumn" }
    }
}
