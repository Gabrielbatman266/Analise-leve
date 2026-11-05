package com.leve.analise

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/** Banco local: fila de prints (arquivos) + posts lidos (posts.json). Tudo no armazenamento interno do app. */
object Store {
    private val lock = Any()

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("leve", Context.MODE_PRIVATE)

    fun queueDir(c: Context) = File(c.filesDir, "fila").apply { mkdirs() }
    private fun postsFile(c: Context) = File(c.filesDir, "posts.json")
    private fun patternsFile(c: Context) = File(c.filesDir, "padroes.json")

    fun pending(c: Context): List<File> =
        (queueDir(c).listFiles { f -> f.name.endsWith(".jpg") } ?: emptyArray()).sortedBy { it.name }

    // ---------- prints ----------
    private var lastThumb: IntArray? = null

    /** Encolhe, compara com o último print (pra não salvar repetido) e coloca na fila. */
    fun saveShot(c: Context, src: Bitmap, skipIfSame: Boolean): File? {
        val w = 720
        val bmp = if (src.width > w) Bitmap.createScaledBitmap(src, w, src.height * w / src.width, true) else src
        val thumb = thumbOf(bmp)
        if (skipIfSame) {
            val prev = lastThumb
            if (prev != null && diff(prev, thumb) < 3.0) return null
        }
        lastThumb = thumb
        val f = File(queueDir(c), "p_${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val n = prefs(c).getInt("shots_total", 0)
        prefs(c).edit().putInt("shots_total", n + 1).apply()
        return f
    }

    private fun thumbOf(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 24, 48, true)
        val out = IntArray(24 * 48)
        for (y in 0 until 48) for (x in 0 until 24) {
            val p = s.getPixel(x, y)
            out[y * 24 + x] = (Color.red(p) * 3 + Color.green(p) * 6 + Color.blue(p)) / 10
        }
        return out
    }

    private fun diff(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) s += abs(a[i] - b[i])
        return s.toDouble() / a.size
    }

    // ---------- posts ----------
    fun loadPosts(c: Context): JSONArray = synchronized(lock) {
        val f = postsFile(c)
        if (!f.exists()) JSONArray() else try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
    }

    private fun keyOf(o: JSONObject) =
        o.optString("legenda").trim().lowercase() + "|" + o.optString("foto").trim().lowercase().take(30) + "|" + o.optLong("curtidas")

    /** Junta posts novos sem repetir. Se o mesmo post aparecer com números maiores, fica o maior. Retorna quantos entraram. */
    fun addPosts(c: Context, novos: JSONArray): Int = synchronized(lock) {
        val all = loadPosts(c)
        val byText = HashMap<String, Int>()
        for (i in 0 until all.length()) {
            val o = all.getJSONObject(i)
            val t = o.optString("legenda").trim().lowercase()
            if (t.length >= 6) byText[t] = i
        }
        val keys = HashSet<String>()
        for (i in 0 until all.length()) keys.add(keyOf(all.getJSONObject(i)))
        var added = 0
        for (i in 0 until novos.length()) {
            val o = novos.optJSONObject(i) ?: continue
            o.put("visto", System.currentTimeMillis())
            val t = o.optString("legenda").trim().lowercase()
            val same = if (t.length >= 6) byText[t] else null
            if (same != null) {
                val old = all.getJSONObject(same)
                if (score(o) > score(old)) all.put(same, o)
                continue
            }
            if (keys.add(keyOf(o))) {
                all.put(o); added++
                if (t.length >= 6) byText[t] = all.length() - 1
            }
        }
        postsFile(c).writeText(all.toString())
        added
    }

    fun clearAll(c: Context) = synchronized(lock) {
        postsFile(c).delete(); patternsFile(c).delete()
        pending(c).forEach { it.delete() }
        prefs(c).edit().putInt("shots_total", 0).apply()
    }

    fun savePatterns(c: Context, arr: JSONArray) { patternsFile(c).writeText(arr.toString()) }
    fun loadPatterns(c: Context): JSONArray =
        try { JSONArray(patternsFile(c).readText()) } catch (e: Exception) { JSONArray() }

    fun score(o: JSONObject): Long =
        o.optLong("curtidas") + 3 * o.optLong("comentarios") + 2 * o.optLong("reposts")

    fun sorted(c: Context): List<JSONObject> {
        val a = loadPosts(c)
        return (0 until a.length()).map { a.getJSONObject(it) }.sortedByDescending { score(it) }
    }

    fun fmt(n: Long): String = if (n >= 1000) String.format("%.1f mil", n / 1000.0).replace('.', ',') else n.toString()

    /** Pacote pra colar no Post Leve. */
    fun exportJson(c: Context): String {
        val list = sorted(c)
        val o = JSONObject()
        o.put("fonte", "analise-leve")
        o.put("geradoEm", System.currentTimeMillis())
        o.put("total", list.size)
        o.put("padroes", loadPatterns(c))
        val posts = JSONArray()
        list.take(80).forEach {
            posts.put(JSONObject()
                .put("legenda", it.optString("legenda"))
                .put("foto", it.optString("foto"))
                .put("curtidas", it.optLong("curtidas"))
                .put("comentarios", it.optLong("comentarios"))
                .put("reposts", it.optLong("reposts")))
        }
        o.put("posts", posts)
        return o.toString()
    }
}
