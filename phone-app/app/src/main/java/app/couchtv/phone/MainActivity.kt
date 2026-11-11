package app.couchtv.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * The phone remote: couch-tv's own web remote (served by the bridge) in a full-screen WebView, so
 * every feature of the remote is here and follows the bridge's updates. Native extras: finds the
 * bridge by itself, the phone's volume buttons change the TV's volume, notifications, updates.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var buttons: LinearLayout
    private var loadedHost: String? = null
    private var lastVolume = 0L
    private var askedUpdate = 0
    private var lightApplied: Boolean? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportZoom(false)
            settings.userAgentString = settings.userAgentString + " CouchTVApp/" + BuildConfig.VERSION_NAME
            webViewClient = Client()
            visibility = View.INVISIBLE
        }
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        spinner = ProgressBar(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }
        buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        buttons.addView(Button(this).apply { text = getString(R.string.retry); setOnClickListener { connect(true) } })
        buttons.addView(Button(this).apply {
            text = getString(R.string.settings)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        overlay.addView(spinner)
        overlay.addView(status)
        overlay.addView(buttons)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        connect(false)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        web.saveState(out)
    }

    override fun onResume() {
        super.onResume()
        // A changed address in Settings, or a bridge that went away while we were in the background.
        if (loadedHost != null && loadedHost != Hub.host(this)) connect(false)
        // Light mode switched in Settings: reload so it applies (or goes away).
        val light = Hub.lightMode(this)
        if (lightApplied != null && lightApplied != light && loadedHost != null) web.reload()
        lightApplied = light
        Thread {
            Notes.check(this)
            offerUpdate()
        }.start()
    }

    private fun connect(fromButton: Boolean) {
        showOverlay(getString(R.string.connecting), busy = true)
        Thread {
            val host = if (Hub.healthy(Hub.host(this))) Hub.host(this) else Hub.discover(this)
            runOnUiThread {
                if (host == null) {
                    showOverlay(getString(R.string.not_found), busy = false)
                } else if (fromButton || loadedHost != host || web.url == null) {
                    loadedHost = host
                    web.loadUrl("http://$host:${Hub.BRIDGE_PORT}/")
                } else {
                    hideOverlay()
                }
            }
        }.start()
    }

    private fun showOverlay(text: String, busy: Boolean) {
        status.text = text
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        buttons.visibility = if (busy) View.GONE else View.VISIBLE
        overlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        web.visibility = View.VISIBLE
    }

    /** Asks once per new version, when the app is opened, whether to install it now. */
    private fun offerUpdate() {
        val v = Notes.latestVersion(this) ?: return
        if (v.code <= BuildConfig.VERSION_CODE || askedUpdate == v.code) return
        askedUpdate = v.code
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            AlertDialog.Builder(this)
                .setTitle("Salon TV ${v.name} hazır")
                .setMessage(v.notes.ifBlank { "Uygulamanın yeni sürümü var." })
                .setPositiveButton("Güncelle") { _, _ -> startActivity(Intent(this, UpdateActivity::class.java)) }
                .setNegativeButton("Sonra", null)
                .show()
        }
    }

    // The phone's volume buttons change the TV's volume (Settings can switch this off).
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) && Hub.volumeKeys(this)) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                val now = SystemClock.uptimeMillis()
                if (now - lastVolume > 120) {
                    lastVolume = now
                    val action = if (code == KeyEvent.KEYCODE_VOLUME_UP) "up" else "down"
                    Thread { Hub.api(this, "/api/volume", """{"action":"$action"}""") }.start()
                    web.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Activity back handling for API 23+")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private inner class Client : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            if (Hub.lightMode(this@MainActivity)) view.evaluateJavascript(Hub.LIGHT_JS, null)
            if (overlay.visibility == View.VISIBLE && status.text == getString(R.string.connecting)) hideOverlay()
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (req.isForMainFrame) {
                web.visibility = View.INVISIBLE
                showOverlay(getString(R.string.not_found), busy = false)
            }
        }

        // Links to anything other than the bridge (an app store page, a show's web page) open outside.
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val u = req.url
            if (u.host == loadedHost && u.port == Hub.BRIDGE_PORT) return false
            return try { startActivity(Intent(Intent.ACTION_VIEW, u)); true } catch (e: Exception) { true }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
