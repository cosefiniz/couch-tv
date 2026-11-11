package app.couchtv.phone

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject

/**
 * Checks the stick's app hub: new household notifications (/events) and a newer app version
 * (/app/version.json). Run by CheckJob every 15 minutes on Wi-Fi, and when the app comes forward.
 */
object Notes {
    const val CH_TV = "tv"
    const val CH_UPDATE = "update"
    private const val UPDATE_ID = 1

    data class Version(val code: Int, val name: String, val notes: String, val sha256: String)

    fun latestVersion(c: Context): Version? {
        val s = Hub.get(Hub.hub(c) + "/app/version.json") ?: return null
        return try {
            val j = JSONObject(s)
            Version(j.getInt("versionCode"), j.optString("versionName"), j.optString("notes"), j.optString("sha256"))
        } catch (e: Exception) {
            null
        }
    }

    fun check(c: Context) {
        if (!Hub.notificationsOn(c)) return
        checkEvents(c)
        val v = latestVersion(c) ?: return
        val p = Hub.prefs(c)
        if (v.code > BuildConfig.VERSION_CODE && p.getInt("notified_version", 0) < v.code) {
            show(c, CH_UPDATE, UPDATE_ID, "Salon TV ${v.name} hazır",
                v.notes.ifBlank { "Yeni sürümü yüklemek için dokunun." },
                Intent(c, UpdateActivity::class.java))
            p.edit().putInt("notified_version", v.code).apply()
        }
    }

    private fun checkEvents(c: Context) {
        val p = Hub.prefs(c)
        val since = p.getLong("last_event", -1L)
        val s = Hub.get(Hub.hub(c) + "/events?since=" + maxOf(since, 0L)) ?: return
        try {
            val j = JSONObject(s)
            val latest = j.optLong("latest", 0L)
            if (since >= 0) {   // the very first check only remembers where the list is
                val evs = j.getJSONArray("events")
                for (i in 0 until evs.length()) {
                    val e = evs.getJSONObject(i)
                    show(c, CH_TV, (e.optLong("id") % 100000).toInt() + 10, e.optString("title"),
                        e.optString("message"), Intent(c, MainActivity::class.java))
                }
            }
            p.edit().putLong("last_event", maxOf(latest, 0L)).apply()
        } catch (e: Exception) {
        }
    }

    fun show(c: Context, channel: String, id: Int, title: String, text: String, open: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(c, id, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, channel) else Notification.Builder(c)
        val n = b.setSmallIcon(R.drawable.ic_stat_tv)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
