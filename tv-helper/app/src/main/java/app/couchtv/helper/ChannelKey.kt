package app.couchtv.helper

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.net.HttpURLConnection
import java.net.URL

/**
 * The red button flips between the last two channels while Live TV is on screen.
 *
 * The TV's own Live TV app already understands KEYCODE_LAST_CHANNEL, but the remote has no such
 * button. This accessibility service sees which app is in front (window changes only, nothing on
 * screen is read) and, only while that is Live TV, takes the red button and asks the stick's hub
 * to send LAST_CHANNEL (an app cannot press keys itself). Everywhere else the red button is left
 * alone.
 */
class ChannelKey : AccessibilityService() {
    @Volatile private var front: String? = null

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p in PASSING) return   // volume bars and other overlays do not change what is in front
        if (p != front) Log.d(TAG, "in front: $p")
        front = p
    }

    override fun onServiceConnected() {
        Log.i(TAG, "connected")
        getSharedPreferences("helper", 0).edit().putBoolean("channel_key_on", true).apply()
        // Android binds this service early at every start, before most boot broadcasts: the
        // surest moment to get Wireless debugging back on (Keeper.kt).
        Keeper.kick(this, "accessibility")
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode !in RED) return false
        if (!isLiveTv(front)) return false
        val hub = hub(this) ?: return false   // no stick has told us where it is yet: leave red alone
        if (e.action == KeyEvent.ACTION_UP) {
            Log.i(TAG, "red button on Live TV: last channel")
            Thread { send(hub) }.start()
        }
        return true   // both the press and the release are ours, so Live TV never sees red
    }

    override fun onInterrupt() {}

    private fun send(hub: String) {
        try {
            val k = URL("http://$hub/key").openConnection() as HttpURLConnection
            k.connectTimeout = 3000
            k.readTimeout = 5000
            k.requestMethod = "POST"
            k.doOutput = true
            k.setRequestProperty("Content-Type", "application/json")
            k.outputStream.use { it.write("""{"key":"LAST_CHANNEL"}""".toByteArray()) }
            Log.i(TAG, "hub answered ${k.responseCode}")
        } catch (ex: Exception) {
            Log.w(TAG, "hub not reachable: $ex")
        }
    }

    companion object {
        private const val TAG = "couchtv.channelkey"
        /** The stick's hub, host:port. The stick sends it (Cmd CONFIG) each time it reaches the TV. */
        fun hub(c: android.content.Context): String? =
            c.getSharedPreferences("helper", 0).getString("hub", null)?.takeIf { it.isNotBlank() }
        const val LIVE_TV = "com.mediatek.tv.oneworld.tvcenter"

        /**
         * Live TV, or the tuner and input services that draw over it: right after Live TV opens
         * (and on each channel change) the satellite tuner's own window comes forward
         * (com.mediatek.dtv.tvinput.dvbtuner), so that counts as Live TV too.
         */
        fun isLiveTv(p: String?): Boolean = p != null && (p == LIVE_TV ||
            p.startsWith("com.mediatek.dtv.tvinput.") || p == "com.mediatek.tis" ||
            p.startsWith("com.android.tv.inputplayer"))
        // This remote sends the red button as F6; other remotes use the standard red key.
        val RED = setOf(KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_F6)
        val PASSING = setOf("com.android.systemui", "app.couchtv.helper", "android")
    }
}
