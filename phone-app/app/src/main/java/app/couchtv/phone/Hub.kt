package app.couchtv.phone

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Where the bridge is, and small HTTP helpers. The bridge (couch-tv, port 5400) serves the remote
 * and its API; the stick's app hub (port 5401, same host) serves this app's updates and the
 * household notifications.
 */
object Hub {
    /** The stick's address, from bridge.properties at build time; empty means "search the Wi-Fi". */
    val DEFAULT_HOST: String = BuildConfig.DEFAULT_HOST
    const val BRIDGE_PORT = 5400
    const val HUB_PORT = 5401

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("couchtv", Context.MODE_PRIVATE)
    fun host(c: Context): String = prefs(c).getString("host", DEFAULT_HOST) ?: DEFAULT_HOST
    fun setHost(c: Context, h: String) = prefs(c).edit().putString("host", h.trim()).apply()
    fun bridge(c: Context) = "http://${host(c)}:$BRIDGE_PORT"
    fun hub(c: Context) = "http://${host(c)}:$HUB_PORT"

    fun volumeKeys(c: Context) = prefs(c).getBoolean("volume_keys", true)
    fun notificationsOn(c: Context) = prefs(c).getBoolean("notifications", true)

    /** Light mode: on by default for older phones (under 4 GB RAM, or Android 8.1 and older). */
    fun lightMode(c: Context): Boolean {
        val p = prefs(c)
        if (p.contains("light_mode")) return p.getBoolean("light_mode", false)
        val mi = android.app.ActivityManager.MemoryInfo()
        c.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
        return mi.totalMem < 4L * 1024 * 1024 * 1024 || android.os.Build.VERSION.SDK_INT < 28
    }

    /**
     * What light mode does to the web remote: no blurred photo behind it, no frosted glass, and the
     * remote's own "reduce transparency" and "reduce motion" styles switched on. Old WebViews: ES5.
     */
    const val LIGHT_JS = """(function(){
  if (document.getElementById('ctv-light')) return;
  var css = '.bd-layer{display:none!important}' +
    '*,*::before,*::after{-webkit-backdrop-filter:none!important;backdrop-filter:none!important}';
  var sheets = document.styleSheets;
  for (var i = 0; i < sheets.length; i++) {
    var rules; try { rules = sheets[i].cssRules; } catch (e) { continue; }
    for (var j = 0; j < rules.length; j++) {
      var r = rules[j];
      if (r.media && /prefers-reduced-(transparency|motion)/.test(r.media.mediaText)) {
        for (var k = 0; k < r.cssRules.length; k++) css += r.cssRules[k].cssText;
      }
    }
  }
  var s = document.createElement('style'); s.id = 'ctv-light'; s.textContent = css;
  document.head.appendChild(s);
})();"""

    fun get(url: String, timeoutMs: Int = 4000): String? = try {
        val k = URL(url).openConnection() as HttpURLConnection
        k.connectTimeout = timeoutMs
        k.readTimeout = timeoutMs
        k.useCaches = false
        if (k.responseCode in 200..299) k.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) {
        null
    }

    fun post(url: String, body: String, timeoutMs: Int = 8000): String? = try {
        val k = URL(url).openConnection() as HttpURLConnection
        k.connectTimeout = timeoutMs
        k.readTimeout = timeoutMs
        k.requestMethod = "POST"
        k.doOutput = true
        k.setRequestProperty("Content-Type", "application/json")
        k.outputStream.use { it.write(body.toByteArray()) }
        if (k.responseCode in 200..299) k.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) {
        null
    }

    /** POST to the bridge's API, e.g. api(c, "/api/volume", """{"action":"up"}"""). */
    fun api(c: Context, path: String, json: String) = post(bridge(c) + path, json)

    fun healthy(host: String, timeoutMs: Int = 1500): Boolean {
        val s = get("http://$host:$BRIDGE_PORT/api/health", timeoutMs) ?: return false
        return try { JSONObject(s).optBoolean("ok") } catch (e: Exception) { false }
    }

    /**
     * Finds a bridge: the saved address, the stick's usual address, its names, then every address
     * on this phone's Wi-Fi network (port 5400 answering /api/health). Saves what it finds.
     * Call off the main thread.
     */
    fun discover(c: Context): String? {
        val tried = linkedSetOf(host(c), DEFAULT_HOST).filter { it.isNotBlank() }.toSet()
        for (h in tried) if (healthy(h)) { setHost(c, h); return h }
        val prefix = localPrefix() ?: return null
        val found = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(32)
        for (i in 1..254) {
            val h = "$prefix.$i"
            if (h in tried) continue
            pool.execute {
                if (found.get() != null) return@execute
                val open = try {
                    Socket().use { it.connect(InetSocketAddress(h, BRIDGE_PORT), 350); true }
                } catch (e: Exception) { false }
                if (open && healthy(h, 1500)) found.compareAndSet(null, h)
            }
        }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
        return found.get()?.also { setHost(c, it) }
    }

    /** True when this phone's Wi-Fi address and the stick's address share their first three parts
     *  (both at home). A name instead of an address counts as home. */
    fun atHome(c: Context): Boolean {
        val h = host(c)
        if (!h.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) return localPrefix() != null
        return localPrefix() == h.substringBeforeLast('.')
    }

    /** The first three parts of this phone's private IPv4 address on Wi-Fi; null when it has none. */
    fun localPrefix(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress?.substringBeforeLast('.')
    } catch (e: Exception) {
        null
    }
}
