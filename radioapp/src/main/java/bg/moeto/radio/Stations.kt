package bg.moeto.radio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Station(val name: String, val urls: List<String>, val cat: String, val sub: String)

/** Радиата, които сайтът изпраща към приложението (за Android Auto). */
object Stations {
    @Volatile
    var all: List<Station> = emptyList()

    @Volatile
    var fav: List<String> = emptyList()

    @Volatile
    var rec: List<String> = emptyList()

    /** Категории в същия ред като в сайта: име -> имена на радиа. */
    @Volatile
    var groups: List<Pair<String, List<String>>> = emptyList()

    @Volatile
    var onChanged: (() -> Unit)? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "stations.json")

    fun load(ctx: Context) {
        if (all.isNotEmpty()) return
        try {
            val f = file(ctx)
            if (f.exists()) parse(f.readText())
        } catch (e: Exception) {
            // няма записани радиа
        }
    }

    fun update(ctx: Context, json: String) {
        if (!parse(json)) return
        try {
            file(ctx).writeText(json)
        } catch (e: Exception) {
            // записът е само кеш
        }
        onChanged?.invoke()
    }

    fun byName(name: String): Station? = all.firstOrNull { it.name == name }

    private fun names(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun parse(json: String): Boolean {
        return try {
            val o = JSONObject(json)
            val st = o.getJSONArray("st")
            val list = ArrayList<Station>(st.length())
            for (i in 0 until st.length()) {
                val s = st.getJSONObject(i)
                val urls = names(s.getJSONArray("u"))
                if (urls.isEmpty()) continue
                list.add(Station(s.getString("n"), urls, s.optString("c"), s.optString("s")))
            }
            val g = ArrayList<Pair<String, List<String>>>()
            val ga = o.optJSONArray("g")
            if (ga != null) {
                for (i in 0 until ga.length()) {
                    val x = ga.getJSONObject(i)
                    g.add(Pair(x.getString("k"), names(x.optJSONArray("v"))))
                }
            }
            all = list
            fav = names(o.optJSONArray("fav"))
            rec = names(o.optJSONArray("rec"))
            groups = g
            true
        } catch (e: Exception) {
            false
        }
    }
}
