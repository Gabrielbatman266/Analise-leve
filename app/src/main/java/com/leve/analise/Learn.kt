package com.leve.analise

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Aprendizado do que bomba:
 *  - nota de cada post (foto ou vídeo) soma; o que bombou sai mais e pode voltar depois de 2 outros;
 *  - a IA compara o que bombou com o que flopou e escreve regras (foto/vídeo + texto + legenda…);
 *  - IA automática: a cada 6 notas novas, analisa sozinha (1 pedido).
 */
object Learn {
    private fun p(c: Context) = Store.prefs(c)
    const val EVERY = 6

    fun autoOn(c: Context) = p(c).getBoolean("learn_auto", true)
    fun setAuto(c: Context, v: Boolean) = p(c).edit().putBoolean("learn_auto", v).apply()

    fun mean(o: JSONObject): Double {
        val g = o.optInt("good"); val m = o.optInt("meh"); val b = o.optInt("bad")
        val legacy = o.optString("nota").takeIf { g + m + b == 0 }
        val gg = g + (if (legacy == "good") 1 else if (legacy == "top") 2 else 0); val mm = m + (if (legacy == "meh") 1 else 0); val bb = b + (if (legacy == "bad") 1 else 0)
        return (gg + 0.5 * mm + 1.0) / (gg + mm + bb + 2.0)
    }
    fun rated(o: JSONObject) = o.optInt("good") + o.optInt("meh") + o.optInt("bad") + (if (o.optInt("good") + o.optInt("meh") + o.optInt("bad") == 0 && o.optString("nota").isNotEmpty()) 1 else 0)
    fun verdict(o: JSONObject) = when { rated(o) == 0 -> ""; o.optInt("top") > 0 && mean(o) > 0.7 -> "top"; mean(o) > 0.6 -> "good"; mean(o) < 0.45 -> "bad"; else -> "meh" }
    fun emoji(k: String) = when (k) { "top" -> "💎"; "good" -> "🔥"; "bad" -> "👎"; "meh" -> "😐"; else -> "" }
    fun isWin(o: JSONObject) = verdict(o).let { it == "good" || it == "top" }

    // ---------- histórico de cada post (a mesma foto/vídeo pode ir mal numa vez e bem na outra)
    fun secondChance(c: Context) = p(c).getBoolean("second_chance", true)
    fun setSecondChance(c: Context, v: Boolean) = p(c).edit().putBoolean("second_chance", v).apply()

    /** [(quando postou, nota)] em ordem. */
    fun hist(o: JSONObject): List<Pair<Long, String>> {
        val a = o.optJSONArray("hist")
        if (a != null && a.length() > 0) return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optLong("at") to it.optString("k") }.sortedBy { it.first }
        val k = o.optString("nota")
        return if (k.isNotEmpty()) listOf(o.optLong("notaAt").takeIf { it > 0 }.let { it ?: o.optLong("lastPost", o.optLong("postedAt")) } to k) else emptyList()
    }

    /** Grava a nota de um post no histórico (troca se já tinha nota pra esse post). */
    fun addHist(o: JSONObject, at: Long, k: String) {
        val l = hist(o).filter { it.first != at } + (at to k)
        o.put("hist", JSONArray().apply { l.sortedBy { it.first }.takeLast(30).forEach { (t, n) -> put(JSONObject().put("at", t).put("k", n)) } })
    }

    /** Média usada no sorteio: com a 2ª chance ligada, um flop só não derruba (volta a ser neutro). */
    fun chanceMean(c: Context, o: JSONObject): Double {
        val m = mean(o)
        return if (secondChance(c) && rated(o) <= 1 && m < 0.5) 0.5 else m
    }
    fun onSecondChance(c: Context, o: JSONObject) = secondChance(c) && rated(o) == 1 && verdict(o) == "bad"

    private fun score(k: String) = when (k) { "top", "good" -> 1.0; "meh" -> 0.5; else -> 0.0 }

    /** Repostagens: quantos flopparam e depois bombaram, e o contrário. */
    fun repostStats(items: List<JSONObject>): String {
        val multi = items.map { hist(it) }.filter { it.size >= 2 }
        if (multi.isEmpty()) return "🔁 Repostagens: ainda nenhum post avaliado 2 vezes."
        var up = 0; var down = 0; var same = 0
        multi.forEach { h ->
            val ks = h.map { it.second }
            val win = { k: String -> k == "good" || k == "top" }
            val fu = ks.indices.any { i -> ks[i] == "bad" && ks.drop(i + 1).any(win) }
            val fd = ks.indices.any { i -> win(ks[i]) && ks.drop(i + 1).contains("bad") }
            when { fu -> up++; fd -> down++; else -> same++ }
        }
        val pct = (up * 100) / multi.size
        return "🔁 Repostagens (${multi.size} com 2+ notas): $up flopparam e depois bombaram · $down bombaram e depois flopparam · $same iguais." +
            if (up > 0) "\n→ $pct% dos flops viraram 🔥 na repostagem: vale dar 2ª chance." else ""
    }

    private val BUCKETS = listOf(0..5 to "madrugada", 6..10 to "manhã", 11..13 to "almoço", 14..17 to "tarde", 18..20 to "começo da noite", 21..23 to "noite")
    private fun bucket(at: Long): String {
        val h = java.util.Calendar.getInstance().apply { timeInMillis = at }.get(java.util.Calendar.HOUR_OF_DAY)
        return BUCKETS.first { h in it.first }.second
    }

    /** Em que horário os posts vão melhor (pelo histórico de notas). */
    fun hourStats(items: List<JSONObject>): String {
        val all = items.flatMap { hist(it) }.filter { it.first > 0 }
        if (all.size < 3) return "⏰ Horários: precisa de mais notas pra comparar."
        val g = all.groupBy { bucket(it.first) }.mapValues { (_, v) -> Triple((v.map { score(it.second) }.average() * 100).toInt(), v.size, v.count { it.second == "good" }) }
            .filter { it.value.second >= 2 }
        if (g.isEmpty()) return "⏰ Horários: precisa de mais notas pra comparar."
        val best = g.maxBy { it.value.first }; val worst = g.minBy { it.value.first }
        return "⏰ Melhor horário: ${best.key} (${best.value.first}%, ${best.value.second} posts)" +
            if (worst.key != best.key) " · pior: ${worst.key} (${worst.value.first}%)" else ""
    }

    /** Histórico de um post em texto (pra IA). */
    fun histText(o: JSONObject): String {
        val h = hist(o)
        if (h.isEmpty()) return ""
        val fmt = java.text.SimpleDateFormat("EEE HH'h'", java.util.Locale("pt", "BR"))
        return "histórico: " + h.mapIndexed { i, (at, k) -> "${i + 1}ª " + emoji(k) + (if (at > 0) " " + fmt.format(java.util.Date(at)) else "") }.joinToString(", ")
    }

    // ---------- regras dos vídeos
    fun videoRules(c: Context): List<String> = try { val a = JSONArray(p(c).getString("combo_regras_v", "[]")); (0 until a.length()).map { a.optString(it) } } catch (e: Exception) { emptyList() }
    fun videoRulesText(c: Context) = videoRules(c).takeIf { it.isNotEmpty() }?.let { "🧠 O QUE JÁ SE APRENDEU NOS VÍDEOS DESTE PERFIL — siga:\n" + it.joinToString("\n") { r -> "- $r" } } ?: ""

    /** 🧠 IA compara os vídeos avaliados (quadro + texto + legenda + categoria + música + tempo). */
    fun learnVideos(c: Context): List<String> {
        val lib = VideoExport.library(c).filter { rated(it) > 0 && File(VideoExport.videoDir(c), "${it.optString("id")}.jpg").exists() }
        if (lib.size < 3) throw AiException(-3, "Avalie pelo menos 3 vídeos postados (🔥/😐/👎) pra IA comparar. Agora tem ${lib.size}.")
        val sorted = lib.sortedByDescending { mean(it) }
        val pick = (sorted.take(6) + sorted.takeLast(4)).distinct().take(10)
        val items = pick.map { o ->
            val desc = buildString {
                append("texto no vídeo: ").append(o.optString("texto").replace('\n', ' ').ifBlank { "(sem texto)" })
                o.optString("ctx").takeIf { it.isNotBlank() }?.let { append(" · categoria: ").append(it) }
                o.optString("musica").takeIf { it.isNotBlank() }?.let { append(" · música: ").append(File(it).nameWithoutExtension.take(30)) }
                o.optString("modelo").takeIf { it.isNotBlank() }?.let { append(" · edição: ").append(it) }
                o.optString("legenda").takeIf { it.isNotBlank() }?.let { append(" · legenda: ").append(it.replace('\n', ' ').take(80)) }
                histText(o).takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
            }
            Triple(File(VideoExport.videoDir(c), "${o.optString("id")}.jpg"), desc, verdict(o))
        }
        val r = Ai.learnVideos(c, items, repostStats(lib) + "\n" + hourStats(lib) + "\n💎 = VIRALIZOU (muito acima do normal): dê peso máximo ao que esses têm em comum.")
        if (r.isNotEmpty()) p(c).edit().putString("combo_regras_v", JSONArray(r).toString()).putLong("combo_v_at", System.currentTimeMillis()).apply()
        return r
    }

    /** Chame depois de cada nota nova. A cada 6, a IA analisa sozinha (se ligada e com chave). */
    fun noteRated(c: Context, kind: String) {
        val k = "learn_new_$kind"
        val n = p(c).getInt(k, 0) + 1
        p(c).edit().putInt(k, n).apply()
        if (n < EVERY || !autoOn(c) || Ai.key(c).isEmpty()) return
        p(c).edit().putInt(k, 0).apply()
        Thread {
            try { if (kind == "v") learnVideos(c) else Captions.learnCombos(c) } catch (e: Exception) { }
        }.start()
    }
    fun pending(c: Context, kind: String) = p(c).getInt("learn_new_$kind", 0)
}
