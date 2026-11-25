package app.couchtv.phone

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * System status: what the stick guard's hub (/status) says about the stick, its services and the
 * TV, one row each, with what to do when something is wrong. Refreshes every 30 seconds.
 */
class StatusActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var head: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val again = Runnable { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        box.addView(TextView(this).apply {
            text = "Sistem durumu"; textSize = 24f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(6))
        })
        head = TextView(this).apply { textSize = 15f; setTextColor(Color.LTGRAY) }
        box.addView(head)
        box.addView(Button(this).apply { text = "Yenile"; setOnClickListener { load() } })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    override fun onResume() { super.onResume(); load() }
    override fun onPause() { super.onPause(); ui.removeCallbacks(again) }

    private fun load() {
        ui.removeCallbacks(again)
        head.text = "Okunuyor…"
        Thread {
            val s = Hub.get(Hub.hub(this) + "/status", 8000)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (s == null) showUnreachable() else try { show(JSONObject(s)) } catch (e: Exception) { showUnreachable() }
                ui.postDelayed(again, 30_000)
            }
        }.start()
    }

    private fun showUnreachable() {
        list.removeAllViews()
        head.text = "Stick'e ulaşılamıyor (${Hub.host(this)})."
        list.addView(row("bad", "Stick", "Cevap yok",
            "Telefon ev Wi-Fi'sinde mi? Öyleyse stick'in ışıklarına bak: kırmızı = güç, mavi = Wi-Fi. " +
                "Mavi yanmıyorsa birkaç dakika bekle (Wi-Fi bekçisi kendisi dener); 20 dakikadan uzun sürerse çıkarıp tak."))
    }

    private fun show(j: JSONObject) {
        list.removeAllViews()
        head.text = when (j.optString("state")) {
            "ok" -> "Her şey yolunda."
            "warn" -> "Dikkat edilecek bir şey var."
            else -> "Bir sorun var."
        } + " · " + j.optString("updated").substringAfter("T").take(5)
        val items = j.optJSONArray("items") ?: return
        var group = ""
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val g = it.optString("group")
            if (g != group) {
                group = g
                list.addView(TextView(this).apply {
                    text = g; textSize = 14f; setTextColor(Color.GRAY); setPadding(0, dp(18), 0, dp(4))
                    typeface = Typeface.DEFAULT_BOLD
                })
            }
            list.addView(row(it.optString("state"), it.optString("label"), it.optString("detail"), it.optString("hint")))
        }
    }

    private fun row(state: String, label: String, detail: String, hint: String): LinearLayout {
        val (mark, colour) = when (state) {
            "ok" -> "✓" to "#2e9d5b"
            "warn" -> "!" to "#c98a14"
            "bad" -> "✕" to "#d2453b"
            else -> "?" to "#888888"
        }
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        r.addView(TextView(this).apply {
            text = mark; textSize = 18f; setTextColor(Color.parseColor(colour)); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; width = dp(30)
        })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(TextView(this).apply { text = label; textSize = 16f; setTextColor(Color.WHITE) })
        if (detail.isNotBlank()) t.addView(TextView(this).apply { text = detail; textSize = 13f; setTextColor(Color.LTGRAY) })
        if (hint.isNotBlank()) t.addView(TextView(this).apply { text = hint; textSize = 13f; setTextColor(Color.parseColor("#e8b04a")) })
        r.addView(t)
        return r
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
