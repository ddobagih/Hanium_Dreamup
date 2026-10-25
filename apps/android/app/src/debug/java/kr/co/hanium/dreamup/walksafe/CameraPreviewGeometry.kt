package kr.co.hanium.dreamup.walksafe

/** Fit the displayed GPU image without adding a crop for the phone's screen aspect ratio. */
internal object CameraPreviewGeometry {
    data class ImageSize(val width: Int, val height: Int)
    data class Bounds(val left: Int, val top: Int, val width: Int, val height: Int)
    data class Viewport(val width: Int, val height: Int, val revision: Long)

    data class Request(val epoch: Int, val rotation: Int, val revision: Long)

    /** One pending GL -> UI request; a rejected current request remains eligible for retry. */
    class Requests {
        private var current: Request? = null
        private var rejected = false
        private var revision = 0L

        @Synchronized fun shouldRequest(epoch: Int, rotation: Int): Boolean =
            current?.let { it.epoch != epoch || it.rotation != rotation || rejected } ?: true

        @Synchronized fun request(epoch: Int, rotation: Int): Request? {
            if (!shouldRequest(epoch, rotation)) return null
            return Request(epoch, rotation, ++revision).also { current = it; rejected = false }
        }

        @Synchronized fun current(epoch: Int, rotation: Int): Request? =
            current?.takeIf { !rejected && it.epoch == epoch && it.rotation == rotation }

        @Synchronized fun isCurrent(request: Request): Boolean = !rejected && current == request

        @Synchronized fun reject(request: Request) {
            if (current == request) rejected = true
        }

        @Synchronized fun reset() {
            current = null
            rejected = false
            // Keep revisions unique when a Surface is recreated in the same camera epoch.
        }
    }

    fun orientedSize(width: Int, height: Int, quarterTurns: Int): ImageSize? {
        if (width <= 0 || height <= 0 || quarterTurns !in 0..3) return null
        return if (quarterTurns % 2 == 0) ImageSize(width, height) else ImageSize(height, width)
    }

    fun fit(width: Int, height: Int, image: ImageSize?): Bounds {
        if (width <= 0 || height <= 0) return Bounds(0, 0, 0, 0)
        if (image == null) return Bounds(0, 0, width, height)
        require(image.width > 0 && image.height > 0)
        val fittedWidth: Int
        val fittedHeight: Int
        if (width.toLong() * image.height <= height.toLong() * image.width) {
            fittedWidth = width
            fittedHeight = (width.toLong() * image.height / image.width).toInt().coerceAtLeast(1)
        } else {
            fittedWidth = (height.toLong() * image.width / image.height).toInt().coerceAtLeast(1)
            fittedHeight = height
        }
        return Bounds((width - fittedWidth) / 2, (height - fittedHeight) / 2, fittedWidth, fittedHeight)
    }

    fun matches(viewport: Viewport?, width: Int, height: Int, revision: Long): Boolean =
        viewport != null && width > 0 && height > 0 && viewport.width == width &&
            viewport.height == height && viewport.revision == revision
}
