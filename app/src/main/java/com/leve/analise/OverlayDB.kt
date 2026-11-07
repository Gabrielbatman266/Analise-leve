package com.leve.analise

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Banco dos TEXTOS QUE APARECEM NO VÍDEO (não a legenda do post).
 * Cada vídeo postado e avaliado 🔥/😐/👎 ajusta a nota do texto; a produção automática
 * escolhe mais os que bombam, sem parar de testar os novos.
 */
object OverlayDB {
    const val MAX = 5000
    private val lock = Any()
    private var cache: MutableList<JSONObject>? = null

    /** Pra começar quando o banco está vazio e a IA não está disponível. */
    val STARTERS = listOf(
        "pov: você parou de rolar\nsó pra me ver", "ninguém tá pronto\npra essa parte", "espera o final",
        "me diz a verdade\n1 ou 10?", "não era pra ficar tão bom", "salva pra ver depois",
        "eu fingindo que não sei\nque tá bom", "esse é o sinal\nque você queria", "olha até o fim 👀",
        "não reposta isso", "modo: confiante", "o detalhe que ninguém percebe",
        "manda pra quem precisa ver isso", "coloquei e deu certo", "não presta atenção nisso"
    )

    private fun file(c: Context) = File(c.filesDir, "overlay_db.json")

    private fun load(c: Context): MutableList<JSONObject> = synchronized(lock) {
        cache?.let { return it }
        val l = try { val a = JSONArray(file(c).readText()); (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() } catch (e: Exception) { mutableListOf() }
        cache = l; l
    }
    private fun save(c: Context) = synchronized(lock) {
        val a = JSONArray(); cache?.forEach { a.put(it) }
        val tmp = File(c.filesDir, "overlay_db.tmp"); tmp.writeText(a.toString()); tmp.renameTo(file(c))
    }
    private fun norm(t: String) = t.trim().lowercase().replace(Regex("\\s+"), " ")

    /** ctx null = todos; "" = só Geral; nome = só daquele contexto */
    fun all(c: Context, ctx: String? = null): List<JSONObject> = load(c).filter { ctx == null || it.optString("ctx") == ctx }
    fun size(c: Context) = load(c).size
    fun count(c: Context, ctx: String) = load(c).count { it.optString("ctx") == ctx }
    private fun find(c: Context, t: String, ctx: String? = null) =
        load(c).firstOrNull { norm(it.optString("t")) == norm(t) && (ctx == null || it.optString("ctx") == ctx) }
            ?: load(c).firstOrNull { norm(it.optString("t")) == norm(t) }

    fun moveCtx(c: Context, from: String, to: String) { synchronized(lock) { load(c).filter { it.optString("ctx") == from }.forEach { it.put("ctx", to) }; save(c) } }
    fun setCtx(c: Context, t: String, ctx: String) { synchronized(lock) { find(c, t)?.put("ctx", ctx); save(c) } }

    fun add(c: Context, t: String, src: String, ctx: String = "") {
        synchronized(lock) {
            val txt = t.trim(); if (txt.isEmpty()) return
            val l = load(c)
            if (l.any { norm(it.optString("t")) == norm(txt) && it.optString("ctx") == ctx }) return
            l.add(0, JSONObject().put("t", txt).put("src", src).put("ctx", ctx).put("at", System.currentTimeMillis()).put("uses", 0).put("good", 0).put("meh", 0).put("bad", 0))
            while (l.size > MAX) { val k = l.indexOfLast { it.optInt("uses") == 0 }; l.removeAt(if (k >= 0) k else l.size - 1) }
            save(c)
        }
    }

    fun remove(c: Context, t: String) { synchronized(lock) { load(c).removeAll { norm(it.optString("t")) == norm(t) }; save(c) } }

    fun used(c: Context, t: String, ctx: String = "") {
        synchronized(lock) {
            if (t.isBlank()) return
            if (find(c, t, ctx) == null) add(c, t, "meu", ctx)
            find(c, t, ctx)?.let { it.put("uses", it.optInt("uses") + 1) }; save(c)
        }
    }

    fun rate(c: Context, t: String, r: String, ctx: String = "") {
        synchronized(lock) {
            if (t.isBlank()) return
            if (find(c, t, ctx) == null) add(c, t, "meu", ctx)
            find(c, t, ctx)?.let { it.put(r, it.optInt(r) + 1) }; save(c)
        }
    }

    fun mean(o: JSONObject): Double {
        val g = o.optInt("good"); val m = o.optInt("meh"); val b = o.optInt("bad")
        return (g + 0.5 * m + 1.0) / (g + m + b + 2.0)
    }
    fun rated(o: JSONObject) = o.optInt("good") + o.optInt("meh") + o.optInt("bad")

    /** Escolhe o texto do momento: boa nota + incerteza (novos ganham chance) − um pouco por já ter sido muito usado. */
    /**
     * Texto pro vídeo de um contexto: só textos daquele contexto ou do Geral,
     * com preferência pros do próprio contexto (eles foram feitos pra ele).
     */
    fun pick(c: Context, avoid: Collection<String> = emptyList(), ctx: String = ""): String? {
        val av = avoid.map { norm(it) }.toSet()
        return load(c).filter { (it.optString("ctx") == ctx || it.optString("ctx") == "") && norm(it.optString("t")) !in av && it.optInt("bad") < 3 + it.optInt("good") * 2 }
            .maxByOrNull {
                mean(it) + (Math.random() - 0.5) * 0.9 / sqrt(rated(it) + 1.0) - 0.05 * ln(1.0 + it.optInt("uses")) +
                    (if (ctx.isNotEmpty() && it.optString("ctx") == ctx) 0.12 else 0.0)
            }?.optString("t")
    }

    /**
     * Texto pra um vídeo cru: primeiro os escritos SÓ pra ele ("v:id"), depois os do contexto dele, depois o Geral.
     * Devolve o texto e a chave onde ele mora (pra nota cair no lugar certo).
     */
    fun pickFor(c: Context, avoid: Collection<String>, vidKey: String, ctx: String): Pair<String, String>? {
        val av = avoid.map { norm(it) }.toSet()
        val keys = setOf(vidKey, ctx, "")
        return load(c).filter { it.optString("ctx") in keys && norm(it.optString("t")) !in av && it.optInt("bad") < 3 + it.optInt("good") * 2 }
            .maxByOrNull {
                val k = it.optString("ctx")
                mean(it) + (Math.random() - 0.5) * 0.9 / sqrt(rated(it) + 1.0) - 0.05 * ln(1.0 + it.optInt("uses")) +
                    (if (k == vidKey) 0.3 else if (ctx.isNotEmpty() && k == ctx) 0.12 else 0.0)
            }?.let { it.optString("t") to it.optString("ctx") }
    }

    fun top(c: Context, n: Int, ctx: String? = null) = all(c, ctx).filter { rated(it) > 0 }.sortedByDescending { mean(it) }.take(n)
    fun worst(c: Context, n: Int, ctx: String? = null) = all(c, ctx).filter { rated(it) > 0 }.sortedBy { mean(it) }.take(n)
}

/** Nota dos modelos de edição (mesma ideia: bombou/flopou). Fica junto dos modelos salvos. */
object ModelScore {
    fun rate(c: Context, name: String, r: String) {
        if (name.isBlank()) return
        val p = Store.prefs(c)
        val a = try { JSONArray(p.getString("modelos", "[]")) } catch (e: Exception) { return }
        for (i in 0 until a.length()) {
            val m = a.optJSONObject(i) ?: continue
            if (m.optString("name") == name) m.put(r, m.optInt(r) + 1)
        }
        p.edit().putString("modelos", a.toString()).apply()
    }

    /** Escolhe um modelo pelo que vem bombando (ou null se não houver modelos). */
    fun pick(models: List<JSONObject>): JSONObject? = models.maxByOrNull {
        val n = it.optInt("good") + it.optInt("meh") + it.optInt("bad")
        (it.optInt("good") + 0.5 * it.optInt("meh") + 1.0) / (n + 2.0) + (Math.random() - 0.5) * 0.9 / sqrt(n + 1.0)
    }

    fun pct(m: JSONObject): Int? {
        val n = m.optInt("good") + m.optInt("meh") + m.optInt("bad")
        return if (n == 0) null else (((m.optInt("good") + 0.5 * m.optInt("meh") + 1.0) / (n + 2.0)) * 100).toInt()
    }
}
