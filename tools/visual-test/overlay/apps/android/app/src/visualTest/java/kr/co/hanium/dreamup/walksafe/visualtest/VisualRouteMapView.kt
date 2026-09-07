package kr.co.hanium.dreamup.walksafe.visualtest

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import org.json.JSONArray
import org.json.JSONObject

/** Visual-test-only reader of the existing guidance state; never acquires location or changes it. */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class VisualRouteMapView(context: Context) : FrameLayout(context) {
    private var disposed = false
    private var pageReady = false
    private var pendingPayload: String? = null
    private var sentPayload: String? = null
    private val webView = WebView(context)

    init {
        minimumHeight = (420 * resources.displayMetrics.density).toInt()
        webView.contentDescription = "현재 안내 경로 지도. 출발지, 목적지, 안내 지점과 현재 위치를 표시합니다."
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString WalkSafeVisualTest/0.1 (${context.packageName})"
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val uri = request.url
                if (request.method != "GET" || uri.scheme != "https") return blockedResponse()
                if (uri.host == LOCAL_HOST && uri.query == null) {
                    val asset = ASSETS[uri.path] ?: return blockedResponse()
                    return runCatching {
                        val stream = if (asset.first == "visualtest/map.html") {
                            val key = BuildConfig.WALKSAFE_TMAP_MAP_KEY
                            val sdkTag = if (key.isBlank()) "" else {
                                val sdkUrl = Uri.parse("https://apis.openapi.sk.com/tmap/jsv2")
                                    .buildUpon().appendQueryParameter("version", "1")
                                    .appendQueryParameter("appKey", key).build().toString()
                                "<script src=\"$sdkUrl\"></script>"
                            }
                            val html = context.assets.open(asset.first).bufferedReader().use { it.readText() }
                                .replace("<!-- TMAP_SDK_TAG -->", sdkTag)
                            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                        } else context.assets.open(asset.first)
                        WebResourceResponse(asset.second, "UTF-8", stream)
                    }.getOrElse { blockedResponse() }
                }
                // Official JS bootstrap, SDK resources and HTTPS TMAP road tiles only.
                if ((uri.host == "apis.openapi.sk.com" && uri.path == "/tmap/jsv2" && uri.port == -1) ||
                    (uri.host in TMAP_TILE_HOSTS && uri.port in setOf(-1, 443, 10730))
                ) return null
                return blockedResponse()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (disposed || url != PAGE_URL) return
                pageReady = true
                deliverPayload()
            }
        }
        webView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        webView.loadUrl(PAGE_URL)
    }

    fun update(
        route: WalkingRoute?,
        location: TrustedLocation?,
        destinationName: String?,
        instruction: String,
        active: Boolean,
    ) {
        if (disposed) return
        val payload = JSONObject().apply {
            put("active", active)
            put("destinationName", destinationName.orEmpty())
            put("instruction", instruction)
            put("polyline", JSONArray().apply {
                route?.polyline?.forEach { point -> point.jsonOrNull()?.let(::put) }
            })
            put("guides", JSONArray().apply {
                route?.guidePoints?.forEach { guide -> guide.point.jsonOrNull()?.let(::put) }
            })
            put("location", location?.let {
                RoutePoint(it.latitude, it.longitude).jsonOrNull()?.let { coordinates ->
                    JSONObject().put("point", coordinates).put(
                        "accuracyM", it.accuracyM.takeIf { value -> value.isFinite() && value >= 0f } ?: 0f,
                    )
                }
            } ?: JSONObject.NULL)
            put("distanceM", route?.summary?.distanceM ?: JSONObject.NULL)
        }.toString()
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { if (!disposed) { pendingPayload = payload; deliverPayload() } }
        } else {
            pendingPayload = payload
            deliverPayload()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) post { deliverPayload() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { deliverPayload() }
    }

    private fun deliverPayload() {
        if (disposed || !pageReady || !isShown || windowVisibility != View.VISIBLE) return
        val payload = pendingPayload ?: return
        if (payload == sentPayload) return
        sentPayload = payload
        // Quoting a JSON string avoids executable interpolation, including Unicode separators.
        val quoted = JSONObject.quote(payload).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        webView.evaluateJavascript("window.walksafeMapUpdate(JSON.parse($quoted));", null)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        pendingPayload = null
        sentPayload = null
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        removeView(webView)
        webView.destroy()
    }

    private fun RoutePoint.jsonOrNull(): JSONArray? =
        if (latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
        ) JSONArray().put(latitude).put(longitude) else null

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)),
    )

    private companion object {
        const val LOCAL_HOST = "appassets.androidplatform.net"
        const val PAGE_URL = "https://$LOCAL_HOST/visualtest/map.html"
        val TMAP_TILE_HOSTS = setOf(
            "topopentile1.tmap.co.kr", "topopentile2.tmap.co.kr", "topopentile3.tmap.co.kr",
        )
        val ASSETS = mapOf(
            "/visualtest/map.html" to ("visualtest/map.html" to "text/html"),
            "/visualtest/map.css" to ("visualtest/map.css" to "text/css"),
            "/visualtest/map.js" to ("visualtest/map.js" to "application/javascript"),
        )
    }
}
