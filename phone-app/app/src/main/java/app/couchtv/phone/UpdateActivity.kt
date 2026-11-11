package app.couchtv.phone

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the new app from the stick (/app/couch-tv-kumanda.apk), checks its SHA-256 against
 * version.json and hands it to Android's installer, which asks the user to confirm.
 */
class UpdateActivity : Activity() {
    private lateinit var text: TextView
    private lateinit var go: Button
    private lateinit var bar: ProgressBar
    private var version: Notes.Version? = null
    private var waitingForPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        text = TextView(this).apply { textSize = 16f; text = "Sürüm bilgisi alınıyor…" }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = android.view.View.GONE }
        go = Button(this).apply { text = "Güncelle"; isEnabled = false; setOnClickListener { start() } }
        val later = Button(this).apply { text = "Sonra"; setOnClickListener { finish() } }
        val row = LinearLayout(this).apply { gravity = Gravity.END; addView(later); addView(go) }
        box.addView(text); box.addView(bar); box.addView(row)
        setContentView(box)
        setFinishOnTouchOutside(false)

        Thread {
            val v = Notes.latestVersion(this)
            runOnUiThread {
                version = v
                when {
                    v == null -> text.text = "Köprüye ulaşılamadı. Telefon ev Wi-Fi'sinde mi?"
                    v.code <= BuildConfig.VERSION_CODE -> text.text = "Salon TV güncel (sürüm ${BuildConfig.VERSION_NAME})."
                    else -> {
                        text.text = "Yeni sürüm: ${v.name} (şu an ${BuildConfig.VERSION_NAME})" +
                            (if (v.notes.isNotBlank()) "\n\n${v.notes}" else "")
                        go.isEnabled = true
                    }
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForPermission) { waitingForPermission = false; start() }
    }

    private fun start() {
        // Android 8+: this app needs "install unknown apps" once, granted by the user in Settings.
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            text.text = "Bir kez izin gerekiyor: açılan ekranda \"Bu kaynaktan izin ver\"i açıp geri dönün."
            waitingForPermission = true
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val v = version ?: return
        go.isEnabled = false
        bar.visibility = android.view.View.VISIBLE
        text.text = "İndiriliyor…"
        Thread {
            val f = File(cacheDir, "update.apk")
            val ok = download(Hub.hub(this) + "/app/couch-tv-kumanda.apk", f)
            val sumOk = ok && (v.sha256.isBlank() || sha256(f).equals(v.sha256, ignoreCase = true))
            runOnUiThread {
                when {
                    !ok -> { text.text = "İndirilemedi. Tekrar deneyin."; go.isEnabled = true }
                    !sumOk -> { text.text = "İndirilen dosya bozuk. Tekrar deneyin."; go.isEnabled = true }
                    else -> { text.text = "Android yüklemeyi onaylamanızı isteyecek."; install(f) }
                }
            }
        }.start()
    }

    private fun download(url: String, to: File): Boolean = try {
        val k = URL(url).openConnection() as HttpURLConnection
        k.connectTimeout = 8000
        k.readTimeout = 20000
        val total = k.contentLength
        k.inputStream.use { inp ->
            to.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) runOnUiThread { bar.progress = done * 100 / total }
                }
            }
        }
        k.responseCode in 200..299
    } catch (e: Exception) {
        false
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(f: File) {
        try {
            val pi = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                f.inputStream().use { inp -> s.openWrite("app", 0, f.length()).use { out -> inp.copyTo(out); s.fsync(out) } }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val status = PendingIntent.getBroadcast(this, id, Intent(this, InstallReceiver::class.java), flags)
                s.commit(status.intentSender)
            }
        } catch (e: Exception) {
            text.text = "Yükleme başlatılamadı: ${e.message}"
            go.isEnabled = true
        }
    }
}

/** Android's answer from the installer: show its confirm screen, or say what went wrong. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Notes.show(c, Notes.CH_UPDATE, 2, "Güncelleme yüklenemedi",
                i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Tekrar deneyin.",
                Intent(c, UpdateActivity::class.java))
        }
    }
}
