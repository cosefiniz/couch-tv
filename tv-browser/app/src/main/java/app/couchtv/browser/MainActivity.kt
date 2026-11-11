package app.couchtv.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private companion object {
        const val UP = 0
        const val DOWN = 1
        const val LEFT = 2
        const val RIGHT = 3

        const val LONG_PRESS_MS = 500L
        const val ZOOM_STEP = 1.15f

        /**
         * Desktop mode: responsive pages that ask for "width=device-width" would get the TV's
         * ~960px CSS viewport (a tablet layout). Give them a 1200px desktop viewport instead.
         * Old pages without a viewport tag are left alone (WebView already lays them out wide).
         */
        val VIEWPORT_JS = """
            (function(){try{
              if (window.top !== window || window.__tvVp) return;
              window.__tvVp = 1;
              if (navigator.userAgent.indexOf('Windows NT') < 0) return;
              var W = 'width=1200';
              function fix(){
                var m = document.getElementsByTagName('meta');
                for (var i = 0; i < m.length; i++) {
                  if ((m[i].getAttribute('name') || '').toLowerCase() !== 'viewport') continue;
                  var c = (m[i].getAttribute('content') || '').toLowerCase();
                  if (c !== W && (c.indexOf('device-width') >= 0 || c.indexOf('initial-scale') >= 0)) m[i].setAttribute('content', W);
                }
              }
              fix();
              var mo = new MutationObserver(fix);
              mo.observe(document, {childList: true, subtree: true});
              var stop = function(){ fix(); setTimeout(function(){ mo.disconnect(); }, 4000); };
              if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', stop); else stop();
            }catch(e){}})();
        """.trimIndent()

        const val MEDIA_TOGGLE_JS =
            "(function(){var v=document.querySelector('video');if(!v)return;if(v.paused)v.play();else v.pause();})()"
        const val MEDIA_PAUSE_JS =
            "(function(){var m=document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++)m[i].pause();})()"

        val TILE_COLORS = intArrayOf(
            0xFF34507A.toInt(), 0xFF5A3F7A.toInt(), 0xFF2F6352.toInt(), 0xFF7A3F3F.toInt(),
            0xFF6E5A2C.toInt(), 0xFF2C5A6E.toInt(), 0xFF6A2F66.toInt(), 0xFF3F6A2F.toInt(),
        )
    }

    private lateinit var store: Store
    private lateinit var root: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var web: WebView
    private lateinit var startPage: ScrollView
    private lateinit var startContent: LinearLayout
    private lateinit var fullscreen: FrameLayout
    private lateinit var cursor: CursorView
    private lateinit var hint: TextView
    private lateinit var address: TextView
    private lateinit var btnStar: ImageButton
    private lateinit var btnMode: ImageButton
    private lateinit var btnCursor: ImageButton

    private val ui = Handler(Looper.getMainLooper())
    private var dp = 1f
    private var scrollFactor = 64f

    private var webDead = false
    private var lastUrl: String? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private var cursorMode = true
    private var scrollMode = false
    private var desktopActive = true
    private var desktopUa = ""
    private var mobileUa = ""
    private var defaultUaMeta: UserAgentMetadata? = null
    private var docStartScript = false
    private var zoomAppliedFor: String? = null
    private val sslAllowed = HashSet<String>()
    private var sslDialogOpen = false

    // D-pad pointer movement
    private val held = LinkedHashSet<Int>()
    private var holdStart = 0L
    private var lastDirKey = 0L
    private var okLongDone = false

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        dp = resources.displayMetrics.density
        scrollFactor = ViewConfiguration.get(this).scaledVerticalScrollFactor.takeIf { it > 0f } ?: (64f * dp)

        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        web = findViewById(R.id.web)
        startPage = findViewById(R.id.startPage)
        startContent = findViewById(R.id.startContent)
        fullscreen = findViewById(R.id.fullscreen)
        cursor = findViewById(R.id.cursor)
        hint = findViewById(R.id.hint)
        address = findViewById(R.id.address)
        btnStar = findViewById(R.id.btnStar)
        btnMode = findViewById(R.id.btnMode)
        btnCursor = findViewById(R.id.btnCursor)

        setupWeb()
        setupToolbar()
        cursorMode = store.cursorMode
        updateCursorButton()
        registerDownloadReceiver()
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> updateCursorVisibility() }
        // the page area is only measured after the first layout; place the pointer then
        web.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateCursorVisibility() }

        val viewUrl = viewIntentUrl(intent)
        val restore = savedInstanceState?.getString("url")
        when {
            viewUrl != null -> openUrl(viewUrl)
            restore != null -> openUrl(restore)
            else -> showStartPage()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewIntentUrl(intent)?.let { openUrl(it) }
    }

    private fun viewIntentUrl(i: Intent?): String? =
        if (i?.action == Intent.ACTION_VIEW) i.dataString?.takeIf { it.startsWith("http") } else null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!isStartShown()) lastUrl?.let { outState.putString("url", it) }
    }

    override fun onPause() {
        super.onPause()
        if (!webDead) web.onPause()
        CookieManager.getInstance().flush()
        releaseAllDirs()
    }

    override fun onResume() {
        super.onResume()
        if (!webDead) web.onResume()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (_: Exception) {
        }
        ui.removeCallbacksAndMessages(null)
        if (!webDead) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ WebView setup

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        WebView.setWebContentsDebuggingEnabled(true)
        val s = web.settings
        val chrome = Regex("Chrome/(\\d+)").find(s.userAgentString)?.groupValues?.get(1) ?: "157"
        desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chrome.0.0.0 Safari/537.36"
        mobileUa = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chrome.0.0.0 Mobile Safari/537.36"

        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false) // popups and target=_blank open in the same view
        s.mediaPlaybackRequiresUserGesture = false
        s.loadsImagesAutomatically = true
        s.blockNetworkImage = false
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.setGeolocationEnabled(false)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.setBackgroundColor(Color.WHITE)
        web.overScrollMode = View.OVER_SCROLL_NEVER

        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                defaultUaMeta = WebSettingsCompat.getUserAgentMetadata(s)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, VIEWPORT_JS, setOf("*"))
                docStartScript = true
            }
        } catch (_: Throwable) {
        }

        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, ua, cd, mime, length -> askDownload(url, ua, cd, mime, length) }
    }

    private fun applyMode(desktop: Boolean) {
        desktopActive = desktop
        val s = web.settings
        s.userAgentString = if (desktop) desktopUa else mobileUa
        try {
            val base = defaultUaMeta
            if (base != null && WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                val meta = UserAgentMetadata.Builder(base)
                    .setMobile(!desktop)
                    .setPlatform(if (desktop) "Windows" else "Android")
                    .setPlatformVersion(if (desktop) "10.0.0" else "14.0.0")
                    .build()
                WebSettingsCompat.setUserAgentMetadata(s, meta)
            }
        } catch (_: Throwable) {
        }
        btnMode.setImageResource(if (desktop) R.drawable.ic_desktop else R.drawable.ic_phone)
    }

    private fun applySiteSettings(url: String) {
        val host = Store.hostOf(url)
        if (host.isEmpty()) return
        val want = store.isDesktop(host)
        if (want != desktopActive) applyMode(want)
        val tz = store.textZoom(host)
        if (web.settings.textZoom != tz) web.settings.textZoom = tz
    }

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    if (request.isForMainFrame && !request.isRedirect && request.method.equals("GET", true)) {
                        val want = store.isDesktop(Store.hostKey(uri.host))
                        if (want != desktopActive) {
                            // the other user agent only applies to a fresh load
                            applyMode(want)
                            view.loadUrl(uri.toString())
                            return true
                        }
                    }
                    return false
                }
                "about", "data", "blob", "javascript" -> return false
            }
            openExternal(uri.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            lastUrl = url
            progress.visibility = View.VISIBLE
            setAddressText(url)
            applySiteSettings(url)
            updateStar()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            if (!docStartScript) view.evaluateJavascript(VIEWPORT_JS, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            progress.visibility = View.INVISIBLE
            if (!docStartScript) view.evaluateJavascript(VIEWPORT_JS, null)
            if (url.startsWith("http")) {
                store.addHistory(view.title ?: url, url)
                applySavedZoom(url)
            }
            updateStar()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            lastUrl = url
            setAddressText(url)
            updateStar()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val host = Store.hostOf(error.url)
            if (host in sslAllowed) {
                handler.proceed(); return
            }
            if (sslDialogOpen || isFinishing) {
                handler.cancel(); return
            }
            sslDialogOpen = true
            dialog()
                .setTitle(R.string.ssl_title)
                .setMessage(getString(R.string.ssl_msg, host))
                .setPositiveButton(R.string.ssl_continue) { _, _ -> sslAllowed.add(host); handler.proceed() }
                .setNegativeButton(R.string.cancel) { _, _ -> handler.cancel() }
                .setOnCancelListener { handler.cancel() }
                .setOnDismissListener { sslDialogOpen = false }
                .show()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page process died (often low memory). Rebuild the screen instead of crashing.
            webDead = true
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            showHint(getString(R.string.crashed))
            ui.postDelayed({ recreate() }, 600)
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            val u = view.url ?: return
            if (title != null) store.updateHistoryTitle(u, title)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden(); return
            }
            customView = view
            customCallback = callback
            fullscreen.addView(view, FrameLayout.LayoutParams(-1, -1))
            fullscreen.visibility = View.VISIBLE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ui.post {
                cursor.moveTo(fullscreen.width / 2f, fullscreen.height / 2f)
                updateCursorVisibility()
                cursorActivity()
            }
        }

        override fun onHideCustomView() {
            closeFullscreenViews()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    }

    private fun exitFullscreen() {
        val cb = customCallback
        closeFullscreenViews()
        try {
            cb?.onCustomViewHidden()
        } catch (_: Exception) {
        }
    }

    private fun closeFullscreenViews() {
        val v = customView ?: return
        customView = null
        customCallback = null
        fullscreen.removeView(v)
        fullscreen.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.removeCallbacks(hideCursorRunnable)
        cursor.alpha = 1f
        web.requestFocus()
        updateCursorVisibility()
    }

    private fun openExternal(url: String) {
        try {
            val i = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.component = null
            i.selector = null
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {
            val fallback = try {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            } catch (_: Exception) {
                null
            }
            if (fallback != null && fallback.startsWith("http")) web.loadUrl(fallback)
            else showHint(getString(R.string.no_app))
        }
    }

    // ------------------------------------------------------------------ navigation

    private fun isStartShown() = startPage.visibility == View.VISIBLE

    fun openUrl(input: String) {
        val url = Store.toUrl(input)
        if (url.isEmpty()) return
        applySiteSettings(url)
        showPage()
        web.loadUrl(url)
    }

    private fun showPage() {
        startPage.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.requestFocus()
        lastUrl?.let { setAddressText(it) }
        updateStar()
        updateCursorVisibility()
    }

    private fun showStartPage() {
        if (customView != null) exitFullscreen()
        if (!webDead && web.visibility == View.VISIBLE) web.evaluateJavascript(MEDIA_PAUSE_JS, null)
        buildStartPage()
        web.visibility = View.INVISIBLE
        startPage.visibility = View.VISIBLE
        startPage.scrollTo(0, 0)
        address.text = ""
        progress.visibility = View.INVISIBLE
        updateStar()
        updateCursorVisibility()
        startContent.post { startContent.findViewWithTag<View>("first")?.requestFocus() }
    }

    private fun goBack() {
        when {
            isStartShown() -> if (lastUrl != null) showPage()
            web.canGoBack() -> web.goBack()
            else -> showStartPage()
        }
    }

    private fun onBackKey() {
        when {
            customView != null -> exitFullscreen()
            scrollMode -> setScrollMode(false)
            isStartShown() -> finish()
            web.canGoBack() -> web.goBack()
            else -> showStartPage()
        }
    }

    private fun setAddressText(url: String) {
        address.text = url.removePrefix("https://").removePrefix("http://").removeSuffix("/")
    }

    private fun currentHost(): String = Store.hostOf(lastUrl ?: "")

    // ------------------------------------------------------------------ toolbar

    private fun setupToolbar() {
        findViewById<View>(R.id.btnBack).setOnClickListener { goBack() }
        findViewById<View>(R.id.btnForward).setOnClickListener {
            if (web.canGoForward()) {
                showPage(); web.goForward()
            }
        }
        findViewById<View>(R.id.btnReload).setOnClickListener { if (!isStartShown()) web.reload() }
        findViewById<View>(R.id.btnHome).setOnClickListener { showStartPage() }
        address.setOnClickListener { showAddressDialog(if (isStartShown()) "" else (lastUrl ?: "")) }
        btnStar.setOnClickListener { toggleBookmark() }
        btnMode.setOnClickListener { toggleDesktop() }
        findViewById<View>(R.id.btnZoomOut).setOnClickListener { zoom(false) }
        findViewById<View>(R.id.btnZoomIn).setOnClickListener { zoom(true) }
        btnCursor.setOnClickListener { toggleCursorMode() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu() }

        for (i in 0 until toolbar.childCount) {
            val v = toolbar.getChildAt(i)
            if (v is ImageButton) {
                v.setOnFocusChangeListener { b, has -> if (has) showHint(b.contentDescription, 1200) }
            }
        }
    }

    private fun updateStar() {
        val on = !isStartShown() && lastUrl != null && store.isBookmarked(lastUrl!!)
        btnStar.setImageResource(if (on) R.drawable.ic_star else R.drawable.ic_star_border)
    }

    private fun updateCursorButton() {
        btnCursor.alpha = if (cursorMode) 1f else 0.4f
    }

    private fun toggleBookmark() {
        if (isStartShown()) return
        val url = lastUrl ?: return
        val added = store.toggleBookmark(web.title ?: url, url)
        showHint(getString(if (added) R.string.bookmark_added else R.string.bookmark_removed))
        updateStar()
    }

    private fun toggleDesktop() {
        if (isStartShown()) return
        val host = currentHost()
        if (host.isEmpty()) return
        val desktop = !store.isDesktop(host)
        store.setDesktop(host, desktop)
        applyMode(desktop)
        showHint(getString(if (desktop) R.string.mode_desktop else R.string.mode_mobile))
        zoomAppliedFor = null
        web.reload()
    }

    private fun toggleCursorMode() {
        cursorMode = !cursorMode
        store.cursorMode = cursorMode
        updateCursorButton()
        showHint(getString(if (cursorMode) R.string.cursor_on else R.string.cursor_off))
        if (!isStartShown()) web.requestFocus()
        updateCursorVisibility()
    }

    // ------------------------------------------------------------------ zoom

    private fun zoom(zoomIn: Boolean) {
        if (isStartShown() || webDead) return
        @Suppress("DEPRECATION")
        if (!zoomIn && !web.canZoomOut()) {
            showHint(getString(R.string.zoom_limit)); return
        }
        val f = if (zoomIn) ZOOM_STEP else 1f / ZOOM_STEP
        web.zoomBy(f)
        val host = currentHost()
        var z = (store.zoom(host) * f).coerceIn(0.25f, 6f)
        if (abs(z - 1f) < 0.03f) z = 1f
        store.setZoom(host, z)
        showHint(getString(R.string.zoom_level, (z * 100).roundToInt()))
    }

    private fun zoomReset() {
        if (isStartShown()) return
        val host = currentHost()
        val z = store.zoom(host)
        if (z != 1f) web.zoomBy(1f / z)
        store.setZoom(host, 1f)
        showHint(getString(R.string.zoom_level, 100))
    }

    /** Re-applies the zoom the user chose for this site, once per page load. */
    private fun applySavedZoom(url: String) {
        if (zoomAppliedFor == url) return
        zoomAppliedFor = url
        val z = store.zoom(Store.hostOf(url))
        if (abs(z - 1f) > 0.02f) ui.postDelayed({ if (!webDead && lastUrl == url) web.zoomBy(z) }, 400)
    }

    private fun textZoom(bigger: Boolean) {
        if (isStartShown()) return
        val host = currentHost()
        val z = (store.textZoom(host) + if (bigger) 15 else -15).coerceIn(50, 300)
        store.setTextZoom(host, z)
        web.settings.textZoom = z
        showHint(getString(R.string.text_size, z))
    }

    // ------------------------------------------------------------------ keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) onBackKey()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (first) showMenu(); return true
            }
            KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_PROG_YELLOW -> {
                if (down) zoom(true); return true
            }
            KeyEvent.KEYCODE_ZOOM_OUT, KeyEvent.KEYCODE_PROG_GREEN -> {
                if (down) zoom(false); return true
            }
            KeyEvent.KEYCODE_PROG_RED -> {
                if (first) toggleCursorMode(); return true
            }
            KeyEvent.KEYCODE_PROG_BLUE -> {
                if (first) toggleDesktop(); return true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                if (first) showAddressDialog(""); return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> if (!isStartShown()) {
                if (down) pageScroll(-1); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> if (!isStartShown()) {
                if (down) pageScroll(1); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> if (!isStartShown()) {
                if (first) web.evaluateJavascript(MEDIA_TOGGLE_JS, null); return true
            }
        }
        if (pageActive() && handlePointerKey(e)) return true
        return super.dispatchKeyEvent(e)
    }

    /** True when arrow keys should drive the on-screen pointer. */
    private fun pageActive(): Boolean {
        if (webDead || isStartShown()) return false
        if (customView != null) return true
        return cursorMode && web.visibility == View.VISIBLE && web.hasFocus()
    }

    private fun dirOf(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        else -> -1
    }

    private fun handlePointerKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        cursorActivity()
        val dir = dirOf(e.keyCode)
        if (dir >= 0) {
            if (scrollMode) {
                if (down) scrollStep(dir)
                return true
            }
            if (down) {
                lastDirKey = SystemClock.uptimeMillis()
                if (e.repeatCount == 0) {
                    val r = targetRect()
                    if (dir == UP && customView == null && cursor.cy <= r.top + 1 && !web.canScrollVertically(-1)) {
                        focusToolbar()
                        return true
                    }
                    pressDir(dir)
                }
            } else {
                releaseDir(dir)
            }
            return true
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (down) {
                    if (e.repeatCount == 0) {
                        okLongDone = false
                    } else if (!okLongDone && (e.isLongPress || e.eventTime - e.downTime >= LONG_PRESS_MS)) {
                        okLongDone = true
                        setScrollMode(!scrollMode)
                    }
                } else {
                    if (!okLongDone) {
                        when {
                            // some remotes send no repeats while held: decide on release
                            e.eventTime - e.downTime >= LONG_PRESS_MS -> setScrollMode(!scrollMode)
                            scrollMode -> setScrollMode(false)
                            else -> click()
                        }
                    }
                    okLongDone = false
                }
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ pointer

    /** The view the pointer acts on (page, or fullscreen video), in cursor-view coordinates. */
    private fun target(): View = if (customView != null) fullscreen else web

    private fun targetRect(): RectF {
        val t = target()
        val a = IntArray(2)
        val b = IntArray(2)
        t.getLocationInWindow(a)
        cursor.getLocationInWindow(b)
        val l = (a[0] - b[0]).toFloat()
        val tp = (a[1] - b[1]).toFloat()
        return RectF(l, tp, l + t.width, tp + t.height)
    }

    private fun updateCursorVisibility() {
        if (!::cursor.isInitialized) return
        val show = pageActive()
        if (show) cursor.keepInside(targetRect())
        else {
            releaseAllDirs()
            if (scrollMode) {
                scrollMode = false; cursor.scrollMode = false
            }
        }
        cursor.shown = show
    }

    private val hideCursorRunnable = Runnable {
        if (customView != null && !scrollMode) cursor.animate().alpha(0f).setDuration(300).start()
    }

    private fun cursorActivity() {
        cursor.animate().cancel()
        cursor.alpha = 1f
        ui.removeCallbacks(hideCursorRunnable)
        if (customView != null) ui.postDelayed(hideCursorRunnable, 3000)
    }

    private val mover = object : Runnable {
        override fun run() {
            if (held.isEmpty()) return
            val now = SystemClock.uptimeMillis()
            if (now - lastDirKey > 700) {
                // lost the key-up (e.g. a dialog took focus): stop instead of drifting
                releaseAllDirs(); return
            }
            val t = (now - holdStart).toFloat()
            val speed = min(16f, 3f + t * 0.013f) * dp
            var dx = 0f
            var dy = 0f
            for (d in held) when (d) {
                UP -> dy -= speed
                DOWN -> dy += speed
                LEFT -> dx -= speed
                RIGHT -> dx += speed
            }
            moveBy(dx, dy)
            ui.postDelayed(this, 16)
        }
    }

    private fun pressDir(dir: Int) {
        if (!held.add(dir)) return
        if (held.size == 1) holdStart = SystemClock.uptimeMillis()
        val step = 9f * dp
        when (dir) {
            UP -> moveBy(0f, -step)
            DOWN -> moveBy(0f, step)
            LEFT -> moveBy(-step, 0f)
            RIGHT -> moveBy(step, 0f)
        }
        ui.removeCallbacks(mover)
        ui.postDelayed(mover, 150)
    }

    private fun releaseDir(dir: Int) {
        held.remove(dir)
        if (held.isEmpty()) ui.removeCallbacks(mover)
    }

    private fun releaseAllDirs() {
        held.clear()
        ui.removeCallbacks(mover)
    }

    /** Moves the pointer; pushing against an edge scrolls the page under it. */
    private fun moveBy(dx: Float, dy: Float) {
        val r = targetRect()
        if (r.isEmpty) return
        var nx = cursor.cx + dx
        var ny = cursor.cy + dy
        var overX = 0f
        var overY = 0f
        if (nx < r.left) {
            overX = nx - r.left; nx = r.left
        } else if (nx > r.right - 1) {
            overX = nx - (r.right - 1); nx = r.right - 1
        }
        if (ny < r.top) {
            overY = ny - r.top; ny = r.top
        } else if (ny > r.bottom - 1) {
            overY = ny - (r.bottom - 1); ny = r.bottom - 1
        }
        cursor.moveTo(nx, ny)
        if (overX != 0f || overY != 0f) wheel(overX * 1.5f, overY * 1.5f)
        hover()
    }

    private fun pointerInTarget(): Pair<Float, Float> {
        val r = targetRect()
        return Pair(cursor.cx - r.left, cursor.cy - r.top)
    }

    private fun click() {
        val t = target()
        val (x, y) = pointerInTarget()
        cursor.click()
        val downTime = SystemClock.uptimeMillis()
        t.dispatchTouchEvent(touch(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        ui.postDelayed({
            t.dispatchTouchEvent(touch(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y))
        }, 45)
    }

    private fun touch(downTime: Long, time: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        return MotionEvent.obtain(
            downTime, time, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    private fun mouseEvent(action: Int, x: Float, y: Float, v: Float = 0f, h: Float = 0f): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, v)
            setAxisValue(MotionEvent.AXIS_HSCROLL, h)
        })
        return MotionEvent.obtain(now, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    /** Mouse hover, so menus that open on mouse-over work. */
    private fun hover() {
        if (customView != null) return
        val (x, y) = pointerInTarget()
        val ev = mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y)
        web.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** Scrolls whatever is under the pointer (page or inner box) with mouse-wheel events. */
    private fun wheel(dxPx: Float, dyPx: Float) {
        if (customView != null) return
        val (x, y) = pointerInTarget()
        val ev = mouseEvent(MotionEvent.ACTION_SCROLL, x, y, -dyPx / scrollFactor, dxPx / scrollFactor)
        web.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun scrollStep(dir: Int) {
        val r = targetRect()
        when (dir) {
            UP -> wheel(0f, -r.height() * 0.35f)
            DOWN -> wheel(0f, r.height() * 0.35f)
            LEFT -> wheel(-r.width() * 0.35f, 0f)
            RIGHT -> wheel(r.width() * 0.35f, 0f)
        }
    }

    private fun pageScroll(sign: Int) {
        if (cursor.shown) {
            wheel(0f, sign * targetRect().height() * 0.85f)
        } else if (sign > 0) {
            web.pageDown(false)
        } else {
            web.pageUp(false)
        }
    }

    private fun setScrollMode(on: Boolean) {
        scrollMode = on
        cursor.scrollMode = on
        releaseAllDirs()
        showHint(getString(if (on) R.string.scroll_on else R.string.scroll_off), if (on) 2500 else 1200)
    }

    private fun focusToolbar() {
        releaseAllDirs()
        val a = IntArray(2)
        val b = IntArray(2)
        cursor.getLocationInWindow(b)
        var best: View = address
        var bestD = Float.MAX_VALUE
        for (i in 0 until toolbar.childCount) {
            val v = toolbar.getChildAt(i)
            if (!v.isShown || !v.isFocusable) continue
            v.getLocationInWindow(a)
            val d = abs(a[0] - b[0] + v.width / 2f - cursor.cx)
            if (d < bestD) {
                bestD = d; best = v
            }
        }
        best.requestFocus()
    }

    // ------------------------------------------------------------------ start page

    private fun buildStartPage() {
        val c = startContent
        c.removeAllViews()

        c.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(px(8), 0, 0, px(12))
        })

        c.clipToPadding = false
        c.clipChildren = false
        val search = tile(getString(R.string.search_tile), getString(R.string.search_tile_sub), 0xFF2F6FDB.toInt(), grow = false)
        search.tag = "first"
        search.setOnClickListener { showAddressDialog("") }
        c.addView(search, LinearLayout.LayoutParams(-1, px(76)).apply { setMargins(px(8), px(4), px(8), px(8)) })

        val bookmarks = store.bookmarks()
        c.addView(section(getString(R.string.bookmarks)))
        if (bookmarks.isEmpty()) {
            c.addView(note(getString(R.string.bookmarks_empty)))
        } else {
            val grid = grid()
            for (b in bookmarks) {
                val t = tile(b.title, Store.hostOf(b.url), colorFor(b.url))
                t.setOnClickListener { openUrl(b.url) }
                t.setOnLongClickListener { bookmarkOptions(b); true }
                grid.addView(t, cell())
            }
            c.addView(grid)
        }

        val seen = HashSet<String>()
        bookmarks.forEach { seen.add(Store.hostOf(it.url)) }
        val recent = store.history().filter { seen.add(Store.hostOf(it.url)) }.take(8)
        if (recent.isNotEmpty()) {
            c.addView(section(getString(R.string.recent)))
            val grid = grid()
            for (h in recent) {
                val t = tile(h.title, Store.hostOf(h.url), colorFor(h.url))
                t.setOnClickListener { openUrl(h.url) }
                grid.addView(t, cell())
            }
            c.addView(grid)
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in listOf<Pair<Int, () -> Unit>>(
            R.string.history to { showHistory() },
            R.string.downloads to { showDownloads() },
            R.string.help to { showHelp() },
        )) {
            val t = tile(getString(label), "", 0xFF2A323D.toInt())
            t.setOnClickListener { action() }
            row.addView(t, LinearLayout.LayoutParams(px(200), px(64)).apply { setMargins(px(8), px(8), px(8), px(8)) })
        }
        c.addView(section(""))
        c.addView(row)
        c.addView(note(getString(R.string.start_footer)))
    }

    private fun grid() = GridLayout(this).apply { columnCount = 4; clipChildren = false; clipToPadding = false }

    private fun cell() = GridLayout.LayoutParams().apply {
        width = px(200); height = px(104)
        setMargins(px(8), px(8), px(8), px(8))
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(0xFFB8C4D4.toInt())
        setPadding(px(8), px(18), 0, px(2))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xFF8A96A6.toInt())
        setPadding(px(8), px(8), 0, px(8))
    }

    private fun colorFor(url: String) = TILE_COLORS[abs(Store.hostOf(url).hashCode()) % TILE_COLORS.size]

    private fun tile(title: String, sub: String, color: Int, grow: Boolean = true): LinearLayout {
        val normal = GradientDrawable().apply { setColor(color); cornerRadius = px(14).toFloat() }
        val focused = GradientDrawable().apply {
            setColor(lighten(color)); cornerRadius = px(14).toFloat(); setStroke(px(4), Color.WHITE)
        }
        val bg = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            isFocusable = true
            isClickable = true
            setPadding(px(16), px(8), px(16), px(8))
            addView(TextView(context).apply {
                text = title
                textSize = 19f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (sub.isNotEmpty()) addView(TextView(context).apply {
                text = sub
                textSize = 14f
                setTextColor(0xCCFFFFFF.toInt())
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
            setOnFocusChangeListener { v, has ->
                val s = if (has && grow) 1.06f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(120).start()
                v.elevation = if (has) px(8).toFloat() else 0f
            }
        }
    }

    private fun lighten(c: Int): Int {
        fun ch(v: Int) = min(255, v + (255 - v) / 4)
        return Color.rgb(ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    private fun bookmarkOptions(b: Store.Item) {
        val labels = arrayOf(getString(R.string.open), getString(R.string.move_first), getString(R.string.remove))
        dialog().setTitle(b.title).setItems(labels) { _, which ->
            val list = store.bookmarks()
            val i = list.indexOfFirst { Store.same(it.url, b.url) }
            when (which) {
                0 -> openUrl(b.url)
                1 -> if (i > 0) {
                    list.add(0, list.removeAt(i)); store.saveBookmarks(list); showStartPage()
                }
                2 -> if (i >= 0) {
                    list.removeAt(i); store.saveBookmarks(list)
                    showHint(getString(R.string.bookmark_removed)); showStartPage()
                }
            }
        }.show()
    }

    // ------------------------------------------------------------------ dialogs

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun showAddressDialog(prefill: String) {
        releaseAllDirs()
        val edit = EditText(this).apply {
            setText(prefill)
            setSelectAllOnFocus(true)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            textSize = 22f
            hint = getString(R.string.address_hint)
        }
        val box = FrameLayout(this).apply {
            setPadding(px(24), px(8), px(24), 0)
            addView(edit)
        }
        val dlg = dialog()
            .setTitle(R.string.address_title)
            .setView(box)
            .setPositiveButton(R.string.go) { _, _ -> openUrl(edit.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        edit.setOnEditorActionListener { _, id, ev ->
            val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (id != EditorInfo.IME_ACTION_NONE || enter) {
                dlg.dismiss(); openUrl(edit.text.toString()); true
            } else false
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dlg.window?.setGravity(Gravity.TOP)
        dlg.show()
        edit.requestFocus()
    }

    private fun showMenu() {
        releaseAllDirs()
        val onPage = !isStartShown()
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += getString(R.string.address_menu) to { showAddressDialog(if (onPage) lastUrl ?: "" else "") }
        if (onPage) {
            val marked = lastUrl?.let { store.isBookmarked(it) } == true
            items += getString(if (marked) R.string.bookmark_remove else R.string.bookmark_add) to { toggleBookmark() }
            items += getString(if (desktopActive) R.string.to_mobile else R.string.to_desktop) to { toggleDesktop() }
            items += getString(R.string.zoom_in) to { zoom(true) }
            items += getString(R.string.zoom_out) to { zoom(false) }
            items += getString(R.string.zoom_reset) to { zoomReset() }
            items += getString(R.string.text_bigger) to { textZoom(true) }
            items += getString(R.string.text_smaller) to { textZoom(false) }
            items += getString(R.string.reload) to { web.reload() }
        }
        items += getString(if (cursorMode) R.string.cursor_toggle_off else R.string.cursor_toggle_on) to { toggleCursorMode() }
        items += getString(R.string.history) to { showHistory() }
        items += getString(R.string.downloads) to { showDownloads() }
        items += getString(R.string.home) to { showStartPage() }
        items += getString(R.string.help) to { showHelp() }
        items += getString(R.string.exit) to { finish() }
        dialog().setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun showHistory() {
        val list = store.history().take(150)
        if (list.isEmpty()) {
            showHint(getString(R.string.history_empty)); return
        }
        val labels = list.map { "${it.title}\n${Store.hostOf(it.url)}" }.toTypedArray()
        dialog().setTitle(R.string.history)
            .setItems(labels) { _, i -> openUrl(list[i].url) }
            .setNeutralButton(R.string.clear_history) { _, _ ->
                store.clearHistory()
                showHint(getString(R.string.history_cleared))
                if (isStartShown()) showStartPage()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHelp() {
        dialog().setTitle(R.string.help).setMessage(R.string.help_text).setPositiveButton(android.R.string.ok, null).show()
    }

    // ------------------------------------------------------------------ downloads

    private fun askDownload(url: String, ua: String?, cd: String?, mime: String?, length: Long) {
        if (!url.startsWith("http")) {
            showHint(getString(R.string.download_unsupported)); return
        }
        val name = URLUtil.guessFileName(url, cd, mime)
        val size = if (length > 0) Formatter.formatShortFileSize(this, length) else "?"
        dialog().setTitle(R.string.download_title)
            .setMessage("$name\n$size")
            .setPositiveButton(R.string.download) { _, _ -> startDownload(url, ua, mime, name) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startDownload(url: String, ua: String?, mime: String?, name: String) {
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (!mime.isNullOrBlank()) req.setMimeType(mime)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            req.addRequestHeader("User-Agent", ua ?: web.settings.userAgentString)
            lastUrl?.let { req.addRequestHeader("Referer", it) }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            showHint(getString(R.string.download_started, name), 2500)
        } catch (e: Exception) {
            showHint(getString(R.string.download_failed, name), 3000)
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            try {
                dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                    if (cur.moveToFirst()) {
                        val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val title = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: ""
                        val msg = if (st == DownloadManager.STATUS_SUCCESSFUL) R.string.download_done else R.string.download_failed
                        showHint(getString(msg, title), 4000)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun registerDownloadReceiver() {
        val f = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, f, RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, f)
        }
    }

    private fun showDownloads() {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val ids = mutableListOf<Long>()
        val labels = mutableListOf<String>()
        try {
            dm.query(DownloadManager.Query())?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    val title = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "?"
                    val st = when (cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> getString(R.string.status_done)
                        DownloadManager.STATUS_FAILED -> getString(R.string.status_failed)
                        else -> getString(R.string.status_running)
                    }
                    ids.add(id); labels.add("$title — $st")
                }
            }
        } catch (_: Exception) {
        }
        if (ids.isEmpty()) {
            showHint(getString(R.string.downloads_empty)); return
        }
        dialog().setTitle(R.string.downloads).setItems(labels.toTypedArray()) { _, i ->
            try {
                val uri = dm.getUriForDownloadedFile(ids[i]) ?: throw IllegalStateException()
                val type = dm.getMimeTypeForDownloadedFile(ids[i]) ?: "*/*"
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                showHint(getString(R.string.no_app))
            }
        }.setNegativeButton(R.string.cancel, null).show()
    }

    // ------------------------------------------------------------------ helpers

    private val hideHint = Runnable { hint.visibility = View.GONE }

    private fun showHint(text: CharSequence?, ms: Long = 1800) {
        if (text.isNullOrEmpty()) return
        hint.text = text
        hint.visibility = View.VISIBLE
        ui.removeCallbacks(hideHint)
        ui.postDelayed(hideHint, ms)
    }

    private fun px(v: Int) = (v * dp).roundToInt()
}
