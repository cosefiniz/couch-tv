package app.couchtv.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Bridge address, volume buttons, notifications, updates. Opened by long-pressing the app icon. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun label(s: String, size: Float = 16f) = TextView(this).apply {
            text = s; textSize = size; setTextColor(Color.WHITE); setPadding(0, dp(14), 0, dp(6))
        }

        box.addView(label("Ayarlar", 24f))
        box.addView(label("Köprünün adresi (stick)"))
        val host = EditText(this).apply {
            setText(Hub.host(this@SettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
        }
        box.addView(host)
        val row = LinearLayout(this)
        row.addView(Button(this).apply {
            text = "Kaydet"
            setOnClickListener { Hub.setHost(this@SettingsActivity, host.text.toString()); toast("Kaydedildi") }
        })
        row.addView(Button(this).apply {
            text = "Ağda bul"
            setOnClickListener {
                toast("Aranıyor…")
                Thread {
                    val h = Hub.discover(this@SettingsActivity)
                    runOnUiThread { if (h != null) { host.setText(h); toast("Bulundu: $h") } else toast("Bulunamadı") }
                }.start()
            }
        })
        box.addView(row)

        box.addView(Switch(this).apply {
            text = "Telefonun ses tuşları TV sesini değiştirsin"
            setTextColor(Color.WHITE); setPadding(0, dp(18), 0, dp(8))
            isChecked = Hub.volumeKeys(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Hub.prefs(this@SettingsActivity).edit().putBoolean("volume_keys", on).apply() }
        })
        box.addView(Switch(this).apply {
            text = "Hafif mod (eski telefonlar için: bulanıklık ve animasyon yok)"
            setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(8))
            isChecked = Hub.lightMode(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Hub.prefs(this@SettingsActivity).edit().putBoolean("light_mode", on).apply() }
        })
        box.addView(Switch(this).apply {
            text = "Bildirimler (elektrik kesintisi, güncellemeler)"
            setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(8))
            isChecked = Hub.notificationsOn(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Hub.prefs(this@SettingsActivity).edit().putBoolean("notifications", on).apply()
                CheckJob.reschedule(this@SettingsActivity)
            }
        })

        box.addView(Button(this).apply {
            text = "Sistem durumu"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, StatusActivity::class.java)) }
        })
        box.addView(label("Sürüm ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
        box.addView(Button(this).apply {
            text = "Güncellemeleri kontrol et"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, UpdateActivity::class.java)) }
        })
        box.addView(label("İpucu: hızlı ayarlar paneline \"TV gücü\" kutucuğunu ekleyerek TV'yi bildirim çekmecesinden açıp kapatabilirsiniz.", 13f))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
