package com.leve.analise

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Banco de legendas (até 20 mil) com aprendizado simples:
 * cada post marcado como 🔥 / 😐 / 👎 ajusta a nota da legenda, e o sorteio
 * prefere as que deram certo sem parar de testar as novas (estilo "bandido de vários braços").
 */
object CaptionDB {
    const val MAX = 20_000
    private val lock = Any()
    private var cache: MutableList<JSONObject>? = null

    private fun file(c: Context) = File(c.filesDir, "legendas_db.json")

    private fun load(c: Context): MutableList<JSONObject> = synchronized(lock) {
        cache?.let { return it }
        val l = try { val a = JSONArray(file(c).readText()); (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
                catch (e: Exception) { mutableListOf() }
        if (l.isEmpty()) migrate(c, l)
        cache = l; l
    }

    /** Traz as legendas que estavam no formato antigo (limitado). */
    private fun migrate(c: Context, l: MutableList<JSONObject>) {
        val p = Store.prefs(c)
        fun arr(k: String) = try { val a = JSONArray(p.getString(k, "[]")); (0 until a.length()).map { a.optString(it) } } catch (e: Exception) { emptyList() }
        arr("mine").forEach { l.add(entry(it, "minha")) }
        arr("refs").forEach { l.add(entry(it, "viral")) }
        if (l.isNotEmpty()) write(c, l)
    }

    private fun entry(t: String, src: String) = JSONObject().put("t", t).put("src", src).put("at", System.currentTimeMillis())
        .put("uses", 0).put("good", 0).put("bad", 0).put("meh", 0)

    private fun write(c: Context, l: List<JSONObject>) {
        val a = JSONArray(); l.forEach { a.put(it) }
        val tmp = File(c.filesDir, "legendas_db.tmp"); tmp.writeText(a.toString()); tmp.renameTo(file(c))
    }

    private fun save(c: Context) = synchronized(lock) { cache?.let { write(c, it) } }

    /** Esquece o que está na memória (o arquivo foi trocado: outro banco ativo). */
    fun reload() = synchronized(lock) { cache = null }
    /** Grava o que está na memória antes de trocar o arquivo. */
    fun flush(c: Context) = save(c)

    private fun norm(t: String) = t.trim().lowercase()

    fun all(c: Context, src: String? = null): List<JSONObject> = load(c).filter { src == null || it.optString("src") == src }
    fun texts(c: Context, src: String) = all(c, src).map { it.optString("t") }
    fun size(c: Context) = load(c).size

    fun add(c: Context, t: String, src: String) {
        synchronized(lock) {
        val txt = t.trim(); if (txt.isEmpty()) return
        val l = load(c)
        val i = l.indexOfFirst { norm(it.optString("t")) == norm(txt) }
        if (i >= 0) { val o = l.removeAt(i); if (src == "minha") o.put("src", "minha"); l.add(0, o) }
        else l.add(0, entry(txt, src))
        while (l.size > MAX) {
            // corta primeiro as nunca usadas/avaliadas mais antigas
            val k = l.indexOfLast { it.optInt("uses") == 0 && it.optInt("good") == 0 }
            l.removeAt(if (k >= 0) k else l.size - 1)
        }
        save(c)
    }
    }

    fun remove(c: Context, t: String) = synchronized(lock) { load(c).removeAll { norm(it.optString("t")) == norm(t) }; save(c) }

    fun find(c: Context, t: String) = load(c).firstOrNull { norm(it.optString("t")) == norm(t) }

    /** Troca o texto das legendas (nome trocado, edição do ChatGPT). Junta se ficar igual a outra. */
    fun mapTexts(c: Context, f: (String) -> String) = synchronized(lock) {
        val l = load(c); var ch = false
        l.forEach { o -> val t = o.optString("t"); val n = f(t); if (n != t) { o.put("t", n); ch = true } }
        if (ch) { val seen = HashSet<String>(); l.retainAll { seen.add(norm(it.optString("t"))) }; save(c) }
    }

    /** Conta que a legenda foi usada num post. */
    fun used(c: Context, t: String, src: String = "ia") {
        synchronized(lock) {
        if (t.isBlank()) return
        if (find(c, t) == null) add(c, t, src)
        find(c, t)?.let { it.put("uses", it.optInt("uses") + 1); it.put("last", System.currentTimeMillis()) }
        save(c)
    }
    }

    /** Resultado do post: "good" (bombou), "meh" (normal) ou "bad" (flopou). */
    fun rate(c: Context, t: String, r: String) {
        synchronized(lock) {
        if (t.isBlank()) return
        if (find(c, t) == null) add(c, t, "ia")
        find(c, t)?.let { it.put(r, it.optInt(r) + 1) }
        save(c)
    }
    }

    /** Nota média (0..1): bombou = 1, normal = 0,5, flopou = 0. */
    fun mean(o: JSONObject): Double {
        val g = o.optInt("good"); val m = o.optInt("meh"); val b = o.optInt("bad")
        return (g + 0.5 * m + 1.0) / (g + m + b + 2.0)
    }

    /** Nota sorteada: média + incerteza (quem tem pouco teste ainda ganha chance). */
    private fun sample(o: JSONObject): Double {
        val n = o.optInt("good") + o.optInt("meh") + o.optInt("bad")
        return mean(o) + (Math.random() - 0.5) * 0.9 / sqrt(n + 1.0) - 0.04 * ln(1.0 + o.optInt("uses"))
    }

    /** Escolhe a melhor legenda do momento entre as candidatas (ou do banco todo). */
    fun pick(c: Context, candidates: List<String>? = null, avoid: Collection<String> = emptyList(), bonus: ((String) -> Double)? = null): String? {
        val av = avoid.map { norm(it) }.toSet()
        val pool0 = if (candidates != null) candidates.filter { norm(it) !in av }.map { find(c, it) ?: entry(it, "ia") }
                   else load(c).filter { it.optString("src") != "viral" && norm(it.optString("t")) !in av }
        val pool = pool0.filter { Captions.fitsToday(it.optString("t")) }.ifEmpty { pool0 }
        if (pool.isEmpty()) return null
        // sorteio ponderado: legenda que bombou tem mais chance, mas todas podem sair
        // o que bombou vale muito mais: 1 🔥 ≈ 3x, 3 🔥 ≈ 6x; 👎 cai pra ~1/3
        val w = pool.map { o -> Math.exp(6.0 * (mean(o) - 0.5)) / (1.0 + 0.1 * ln(1.0 + o.optInt("uses"))) * (bonus?.invoke(o.optString("t")) ?: 1.0) }
        var r = Math.random() * w.sum()
        for (i in pool.indices) { r -= w[i]; if (r <= 0) return pool[i].optString("t") }
        return pool.last().optString("t")
    }

    /** Legendas que bombaram (nota boa). */
    fun winners(c: Context, n: Int = 30) = load(c).filter { it.optInt("good") > 0 && mean(it) > 0.6 }.sortedByDescending { mean(it) * (1 + it.optInt("good")) }.take(n)
    fun losers(c: Context, n: Int = 15) = load(c).filter { it.optInt("bad") > it.optInt("good") }.sortedByDescending { it.optInt("bad") }.take(n)
    fun isWinner(c: Context, t: String) = find(c, t)?.let { it.optInt("good") > 0 && mean(it) > 0.6 } == true

    fun top(c: Context, n: Int) = load(c).filter { it.optInt("good") + it.optInt("meh") + it.optInt("bad") > 0 }
        .sortedByDescending { mean(it) }.take(n)

    /** Palavras que aparecem mais nas que bombaram do que nas que floparam. */
    fun winningWords(c: Context, n: Int = 12): List<String> {
        val stop = setOf("de", "a", "o", "e", "que", "do", "da", "em", "um", "uma", "pra", "pro", "me", "eu", "é", "no", "na", "com", "se", "só", "mais", "os", "as", "por", "você", "vc", "isso", "esse", "essa", "ao", "tá")
        val good = HashMap<String, Int>(); val bad = HashMap<String, Int>()
        load(c).forEach { o ->
            val ws = Regex("[\\p{L}\\p{N}]+").findAll(o.optString("t").lowercase()).map { it.value }.filter { it.length > 2 && it !in stop }.toSet()
            ws.forEach { w -> good[w] = (good[w] ?: 0) + o.optInt("good"); bad[w] = (bad[w] ?: 0) + o.optInt("bad") }
        }
        return good.filter { it.value >= 1 }.entries.sortedByDescending { it.value - (bad[it.key] ?: 0) * 0.7 }.take(n).map { it.key }
    }

    fun export(c: Context): String { val a = JSONArray(); load(c).forEach { a.put(it) }; return a.toString() }

    /** Junta um banco colado (backup ou de outro celular). Retorna quantas entraram. */
    fun import(c: Context, json: String): Int = synchronized(lock) {
        val a = try { JSONArray(json.substring(json.indexOf('['), json.lastIndexOf(']') + 1)) } catch (e: Exception) { return 0 }
        val l = load(c); var n = 0
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val t = o.optString("t"); if (t.isBlank()) continue
            val ex = l.firstOrNull { norm(it.optString("t")) == norm(t) }
            if (ex == null) { l.add(o); n++ } else for (k in listOf("uses", "good", "meh", "bad")) ex.put(k, maxOf(ex.optInt(k), o.optInt(k)))
        }
        while (l.size > MAX) l.removeAt(l.size - 1)
        save(c); n
    }
}
