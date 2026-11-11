package app.couchtv.browser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Bookmarks, history and per-site settings, kept in SharedPreferences. */
class Store(ctx: Context) {

    data class Item(val title: String, val url: String, val time: Long = 0L)

    private val p = ctx.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private var historyCache: MutableList<Item>? = null

    // --- bookmarks ---
    fun bookmarks(): MutableList<Item> {
        val raw = p.getString("bookmarks", null) ?: return DEFAULT_BOOKMARKS.toMutableList()
        return parse(raw)
    }

    fun saveBookmarks(list: List<Item>) {
        p.edit().putString("bookmarks", toJson(list)).apply()
    }

    fun isBookmarked(url: String) = bookmarks().any { same(it.url, url) }

    /** Returns true when the page is now bookmarked. */
    fun toggleBookmark(title: String, url: String): Boolean {
        val list = bookmarks()
        val i = list.indexOfFirst { same(it.url, url) }
        return if (i >= 0) {
            list.removeAt(i); saveBookmarks(list); false
        } else {
            list.add(Item(title.ifBlank { url }, url)); saveBookmarks(list); true
        }
    }

    // --- history ---
    fun history(): MutableList<Item> =
        historyCache ?: parse(p.getString("history", "[]") ?: "[]").also { historyCache = it }

    fun addHistory(title: String, url: String) {
        val list = history()
        list.removeAll { it.url == url }
        list.add(0, Item(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        saveHistory()
    }

    fun updateHistoryTitle(url: String, title: String) {
        if (title.isBlank()) return
        val list = history()
        val i = list.indexOfFirst { it.url == url }
        if (i >= 0 && list[i].title != title) {
            list[i] = list[i].copy(title = title)
            saveHistory()
        }
    }

    fun clearHistory() {
        history().clear(); saveHistory()
    }

    private fun saveHistory() {
        p.edit().putString("history", toJson(history())).apply()
    }

    // --- per-site settings (key = host without "www.") ---
    fun isDesktop(host: String) = !p.getBoolean("mobile:$host", false)
    fun setDesktop(host: String, desktop: Boolean) {
        p.edit().putBoolean("mobile:$host", !desktop).apply()
    }

    fun zoom(host: String) = p.getFloat("zoom:$host", 1f)
    fun setZoom(host: String, z: Float) {
        p.edit().putFloat("zoom:$host", z).apply()
    }

    fun textZoom(host: String) = p.getInt("text:$host", 100)
    fun setTextZoom(host: String, z: Int) {
        p.edit().putInt("text:$host", z).apply()
    }

    var cursorMode: Boolean
        get() = p.getBoolean("cursor", true)
        set(v) {
            p.edit().putBoolean("cursor", v).apply()
        }

    private fun parse(raw: String): MutableList<Item> {
        val out = mutableListOf<Item>()
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Item(o.optString("t"), o.optString("u"), o.optLong("w")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun toJson(list: List<Item>): String {
        val a = JSONArray()
        for (it in list) a.put(JSONObject().put("t", it.title).put("u", it.url).put("w", it.time))
        return a.toString()
    }

    companion object {
        private const val MAX_HISTORY = 300

        val DEFAULT_BOOKMARKS = listOf(
            Item("Google", "https://www.google.com.tr/"),
            Item("Vikipedi", "https://tr.wikipedia.org/"),
            Item("Hava Durumu (MGM)", "https://www.mgm.gov.tr/"),
            Item("TRT Haber", "https://www.trthaber.com/"),
        )

        fun hostKey(host: String?): String = (host ?: "").lowercase().removePrefix("www.")

        fun hostOf(url: String): String = hostKey(Uri.parse(url).host)

        private fun norm(u: String) = u.trim().lowercase()
            .removePrefix("http://").removePrefix("https://").removePrefix("www.").trimEnd('/')

        fun same(a: String, b: String) = norm(a) == norm(b)

        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private val HOSTLIKE = Regex("^([\\p{L}\\p{N}-]+\\.)+[\\p{L}]{2,}(:\\d+)?(/.*)?$|^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?(/.*)?$|^localhost(:\\d+)?(/.*)?$")

        /** Turns what the user typed into a URL; anything that is not an address becomes a Google search. */
        fun toUrl(input: String): String {
            val s = input.trim()
            if (s.isEmpty()) return ""
            if (s.startsWith("http://", true) || s.startsWith("https://", true) ||
                s.startsWith("about:", true) || s.startsWith("file:", true)
            ) return s
            if (!s.contains(' ') && HOSTLIKE.matches(s)) return "http://$s"
            if (!s.contains(' ') && SCHEME.containsMatchIn(s) && s.contains("://")) return s
            return "https://www.google.com.tr/search?q=" + URLEncoder.encode(s, "UTF-8")
        }
    }
}
