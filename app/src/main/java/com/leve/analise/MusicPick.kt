package com.leve.analise

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Música de cada categoria na produção automática.
 *  auto      → sorteia entre as suas músicas salvas (ou só as escolhidas pra categoria), aprendendo com 🔥/👎
 *  perguntar → o app pergunta na hora de produzir (em segundo plano, cai no automático)
 *  fixa      → sempre a mesma música
 *  original  → som original do vídeo
 *  modelo    → a música do modelo de edição sorteado (como era antes)
 */
object MusicPick {
    const val AUTO = "auto"; const val ASK = "perguntar"; const val FIXED = "fixa"; const val ORIG = "original"; const val MODEL = "modelo"
    val MODES = listOf(AUTO to "🎲 Automático", ASK to "❓ Me perguntar", FIXED to "📌 Sempre a mesma", ORIG to "🔊 Som original", MODEL to "★ A do modelo")

    fun label(mode: String) = MODES.firstOrNull { it.first == mode }?.second ?: MODES[0].second

    fun mode(c: Context, cat: String): String {
        val o = Contexts.get(c, cat)
        val m = o?.optString("musica_modo").orEmpty()
        if (m.isNotEmpty()) return m
        return if (Contexts.noText(c, cat)) AUTO else MODEL
    }

    /** Músicas escolhidas pra categoria (vazio = todas as salvas). */
    fun chosen(c: Context, cat: String): List<String> {
        val a = Contexts.get(c, cat)?.optJSONArray("musicas") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { File(it).exists() }
    }

    fun setChosen(c: Context, cat: String, paths: List<String>) =
        Contexts.update(c, cat) { o -> o.put("musicas", JSONArray().apply { paths.forEach { put(it) } }) }

    fun pool(c: Context, cat: String): List<File> = chosen(c, cat).map { File(it) }.ifEmpty { VideoExport.musics(c) }

    fun fixed(c: Context, cat: String): File? = Contexts.get(c, cat)?.optString("musica_fixa")?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }

    // ---------- aprendizado: nota de cada música
    private fun scores(c: Context): JSONObject = try { JSONObject(Store.prefs(c).getString("musica_score", "{}")) } catch (e: Exception) { JSONObject() }
    private fun saveScores(c: Context, o: JSONObject) = Store.prefs(c).edit().putString("musica_score", o.toString()).apply()
    private fun keyOf(path: String) = File(path).name

    fun rate(c: Context, path: String, k: String) {
        if (path.isBlank()) return
        val s = scores(c); val e = s.optJSONObject(keyOf(path)) ?: JSONObject()
        e.put(k, e.optInt(k) + 1); s.put(keyOf(path), e); saveScores(c, s)
    }

    fun used(c: Context, path: String) {
        if (path.isBlank()) return
        val s = scores(c); val e = s.optJSONObject(keyOf(path)) ?: JSONObject()
        e.put("uses", e.optInt("uses") + 1); s.put(keyOf(path), e); saveScores(c, s)
    }

    fun pct(c: Context, path: String): Int? {
        val e = scores(c).optJSONObject(keyOf(path)) ?: return null
        val n = e.optInt("good") + e.optInt("meh") + e.optInt("bad")
        return if (n == 0) null else (((e.optInt("good") + 0.5 * e.optInt("meh") + 1.0) / (n + 2.0)) * 100).toInt()
    }

    /** Sorteio que aprende: o que bomba sai mais, mas sem repetir a mesma música em seguida. */
    fun pick(c: Context, cat: String, avoid: Collection<String>): File? {
        val pool = pool(c, cat)
        if (pool.isEmpty()) return null
        val s = scores(c)
        val cand = pool.filter { it.path !in avoid }.ifEmpty { pool }
        return cand.maxByOrNull { f ->
            val e = s.optJSONObject(keyOf(f.path)) ?: JSONObject()
            val n = e.optInt("good") + e.optInt("meh") + e.optInt("bad")
            (e.optInt("good") + 0.5 * e.optInt("meh") + 1.0) / (n + 2.0) + (Math.random() - 0.5) * 0.9 / sqrt(n + 1.0) - 0.03 * ln(1.0 + e.optInt("uses"))
        }
    }

    /** Músicas dos últimos vídeos produzidos (pra variar). */
    fun recent(c: Context, n: Int = 2 + Health.musicExtra(c)): List<String> = VideoExport.library(c).take(n).map { it.optString("musica") }.filter { it.isNotEmpty() }

    /**
     * Decide a música de um vídeo. Devolve: null = usar a do modelo/projeto; "" = som original; caminho = essa música.
     * [asked]: respostas do "Me perguntar" desta produção (categoria → caminho, "" ou "*" = sortear).
     */
    fun resolve(c: Context, cat: String, avoid: Collection<String>, asked: Map<String, String>): String? = when (mode(c, cat)) {
        ORIG -> ""
        MODEL -> null
        FIXED -> fixed(c, cat)?.path ?: pick(c, cat, avoid)?.path
        ASK -> asked[cat]?.let { a -> if (a == "*") pick(c, cat, avoid)?.path else a } ?: pick(c, cat, avoid)?.path
        else -> pick(c, cat, avoid)?.path
    }
}
