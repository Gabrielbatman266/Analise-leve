package com.leve.analise

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * 🎯 Modelo de previsão: aprende com TODAS as notas quais características bombam
 * (categoria, cena, música, edição, tipo de legenda/texto, palavras, horário, dia, "cara" da foto)
 * e dá uma chance pra cada post — inclusive os que nunca saíram (antes começavam do zero).
 * Regressão linear com regularização, treinada no celular em milissegundos. Sem IA, sem crédito.
 */
object Predictor {
    @Volatile private var w: Map<String, Double> = emptyMap()
    @Volatile private var bias = 0.5
    @Volatile private var sig = ""
    @Volatile private var nTrain = 0
    private val featCount = HashMap<String, Int>()

    private fun score(k: String) = when (k) { "top" -> 1.0; "good" -> 0.8; "meh" -> 0.45; "bad" -> 0.0; else -> 0.5 }

    // ---------- características
    private fun noAcc(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    private val STOP = setOf("voce", "voces", "para", "como", "mais", "isso", "esse", "essa", "esta", "esse", "pra", "que", "uma", "com", "nao", "sim", "meu", "minha", "seu", "sua", "tudo", "muito")

    fun textFeats(s: String, pre: String): List<String> {
        val t = s.trim(); if (t.isEmpty()) return listOf("${pre}vazio")
        val f = ArrayList<String>()
        f += pre + "tam:" + when { t.length <= 35 -> "curto"; t.length <= 90 -> "medio"; else -> "longo" }
        if (Regex("[\\p{So}\\p{Cn}]").containsMatchIn(t) || t.any { Character.isSurrogate(it) }) f += pre + "emoji"
        if (t.contains("?")) f += pre + "pergunta"
        if (t.contains("...") || t.contains("…")) f += pre + "reticencias"
        if (t == t.lowercase()) f += pre + "minusculas"
        if (t.contains("\n")) f += pre + "quebra"
        if (t.contains("\"") || t.contains("“")) f += pre + "fala"
        noAcc(t).split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in STOP }.distinct().take(8).forEach { f += pre + "w:" + it }
        return f
    }

    private fun hourB(at: Long): String {
        val h = java.util.Calendar.getInstance().apply { timeInMillis = at }.get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) { in 0..5 -> "madrugada"; in 6..10 -> "manha"; in 11..13 -> "almoco"; in 14..17 -> "tarde"; in 18..20 -> "inicio_noite"; else -> "noite" }
    }
    private fun dow(at: Long) = java.util.Calendar.getInstance().apply { timeInMillis = at }.get(java.util.Calendar.DAY_OF_WEEK).let { if (it == 1 || it == 7) "fds" else "semana" }

    /** "Cara" da foto: agrupa fotos parecidas (mesmo cenário/pose) pelo mesmo jeito das cenas dos vídeos. */
    fun photoLook(c: Context, o: JSONObject): String? {
        val id = o.optString("id")
        val h = o.optString("sh").split(",").mapNotNull { it.toLongOrNull() }.takeIf { it.size == 3 }?.toLongArray() ?: run {
            val b = try { BitmapFactory.decodeFile(PostQueue.imageFile(c, id).path, BitmapFactory.Options().apply { inSampleSize = 8 }) } catch (e: Exception) { null } ?: return null
            val hh = Scenes.hash(b); PostQueue.update(c, id) { it.put("sh", hh.joinToString(",")) }; hh
        }
        val p = Store.prefs(c)
        val reps = try { JSONArray(p.getString("looks", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until reps.length()) {
            val r = reps.getJSONObject(i); val rh = r.optString("h").split(",").mapNotNull { it.toLongOrNull() }.toLongArray()
            if (rh.size == 3 && Scenes.same(h, rh)) return "look:" + r.optString("k")
        }
        val k = "L" + reps.length()
        reps.put(JSONObject().put("k", k).put("h", h.joinToString(",")))
        p.edit().putString("looks", reps.toString()).apply()
        return "look:$k"
    }

    private fun videoFeats(c: Context, o: JSONObject, cap: String, at: Long): List<String> {
        val f = ArrayList<String>()
        o.optString("ctx").takeIf { it.isNotBlank() }?.let { f += "cat:$it" }
        f += "cena:" + VideoExport.origin(o)
        o.optString("musica").takeIf { it.isNotBlank() }?.let { f += "mus:" + java.io.File(it).nameWithoutExtension.take(40) } ?: run { f += "mus:original" }
        o.optString("modelo").takeIf { it.isNotBlank() }?.let { f += "mod:$it" }
        f += textFeats(o.optString("texto"), "vt.")
        f += textFeats(cap, "leg.")
        f += "h:" + hourB(at); f += "d:" + dow(at)
        return f.map { "v." + it }
    }

    private fun photoFeats(c: Context, o: JSONObject, cap: String?, at: Long): List<String> {
        val f = ArrayList<String>()
        photoLook(c, o)?.let { f += it }
        if (cap != null) f += textFeats(cap, "leg.")
        f += "h:" + hourB(at); f += "d:" + dow(at)
        return f.map { "f." + it }
    }

    // ---------- treino
    private fun samples(c: Context): List<Pair<List<String>, Double>> {
        val out = ArrayList<Pair<List<String>, Double>>()
        val lib = VideoExport.library(c).associateBy { it.optString("id") }
        val q = PostQueue.load(c).associateBy { it.optString("id") }
        val seen = HashSet<String>()
        SendLog.all(c).filter { it.optString("nota").isNotEmpty() }.forEach { e ->
            val y = score(e.optString("nota")); val at = e.optLong("at"); seen += e.optString("id") + "@" + (at / 3600_000L)
            if (e.optString("kind") == "video") lib[e.optString("id")]?.let { out += videoFeats(c, it, e.optString("cap"), at) to y }
            else q[e.optString("id")]?.let { out += photoFeats(c, it, e.optString("cap"), at) to y }
        }
        // notas antigas (antes do histórico de envios)
        lib.values.forEach { o -> Learn.hist(o).forEach { (at, k) -> if (seen.add(o.optString("id") + "@" + (at / 3600_000L))) out += videoFeats(c, o, o.optString("legenda"), at) to score(k) } }
        q.values.forEach { o -> Learn.hist(o).forEach { (at, k) -> if (seen.add(o.optString("id") + "@" + (at / 3600_000L))) out += photoFeats(c, o, PostQueue.captionOf(o), at) to score(k) } }
        return out
    }

    /** Re-treina quando surgem notas novas (rápido). */
    @Synchronized fun train(c: Context, force: Boolean = false) {
        val s = SendLog.all(c).count { it.optString("nota").isNotEmpty() }.toString() + ":" + VideoExport.library(c).sumOf { Learn.rated(it) } + ":" + PostQueue.load(c).sumOf { Learn.rated(it) }
        if (!force && s == sig) return
        val data = samples(c)
        sig = s; nTrain = data.size
        if (data.size < 4) { w = emptyMap(); bias = 0.5; return }
        val ww = HashMap<String, Double>()
        featCount.clear(); data.forEach { (f, _) -> f.forEach { featCount[it] = (featCount[it] ?: 0) + 1 } }
        var b = data.map { it.second }.average()
        val lr = 0.05; val l2 = 0.02
        repeat(60) {
            data.shuffled().forEach { (f, y) ->
                val pred = b + f.sumOf { ww[it] ?: 0.0 }
                val err = (y - pred).coerceIn(-1.0, 1.0)
                b += lr * 0.2 * err
                f.forEach { k -> val cur = ww[k] ?: 0.0; ww[k] = cur + lr * (err - l2 * cur) }
            }
        }
        w = ww; bias = b
        Store.prefs(c).edit().putInt("pred_n", data.size).apply()
    }

    private fun predict(f: List<String>) = (bias + f.sumOf { w[it] ?: 0.0 }).coerceIn(0.02, 0.98)

    /** Chance (0..1) do vídeo ir bem se postar agora. */
    fun video(c: Context, o: JSONObject): Double { train(c); return if (w.isEmpty()) 0.5 else predict(videoFeats(c, o, o.optString("legenda"), System.currentTimeMillis())) }
    /** Chance da foto (com a legenda, se der) ir bem se postar agora. */
    fun photo(c: Context, o: JSONObject, cap: String? = null): Double { train(c); return if (w.isEmpty()) 0.5 else predict(photoFeats(c, o, cap, System.currentTimeMillis())) }
    /** Só a parte da legenda (pra escolher legenda). */
    fun caption(c: Context, cap: String): Double { train(c); return if (w.isEmpty()) 0.5 else predict(textFeats(cap, "leg.").map { "f.$it" }) }

    fun ready(c: Context): Boolean { train(c); return w.isNotEmpty() }
    fun trainedOn() = nTrain

    /** Junta o histórico do próprio item com a previsão: item com poucas notas confia mais na previsão. */
    fun blend(itemMean: Double, rated: Int, pred: Double, k: Double = 3.0) = (itemMean * rated + pred * k) / (rated + k)

    // ---------- o que aprendeu, em palavras
    private fun human(f: String): String? {
        val k = f.substringAfter('.')
        return when {
            k.startsWith("cat:") -> "categoria " + k.removePrefix("cat:")
            k.startsWith("mus:") -> if (k == "mus:original") "som original" else "música \"" + k.removePrefix("mus:") + "\""
            k.startsWith("mod:") -> "edição ★ " + k.removePrefix("mod:")
            k.startsWith("h:") -> "postar " + mapOf("madrugada" to "de madrugada", "manha" to "de manhã", "almoco" to "no almoço", "tarde" to "à tarde", "inicio_noite" to "no começo da noite", "noite" to "à noite")[k.removePrefix("h:")]
            k == "d:fds" -> "fim de semana"; k == "d:semana" -> "dia de semana"
            k.startsWith("look:") -> null
            k.startsWith("cena:") -> null
            else -> {
                val who = if (k.startsWith("vt.")) "texto no vídeo" else "legenda"
                when (val t = k.substringAfter('.')) {
                    "tam:curto" -> "$who curto"; "tam:medio" -> "$who médio"; "tam:longo" -> "$who longo"
                    "emoji" -> "$who com emoji"; "pergunta" -> "$who com pergunta"; "reticencias" -> "$who com reticências"
                    "minusculas" -> "$who em minúsculas"; "quebra" -> "$who com quebra de linha"; "fala" -> "$who em formato de fala (\"…\")"; "vazio" -> "sem $who"
                    else -> if (t.startsWith("w:")) "palavra \"" + t.removePrefix("w:") + "\" na $who" else null
                }
            }
        }
    }

    /** As características que mais ajudam e mais atrapalham (só as vistas em 2+ posts). */
    fun insights(c: Context, video: Boolean?, n: Int = 5): Pair<List<String>, List<String>> {
        train(c)
        val pre = when (video) { true -> "v."; false -> "f."; null -> "" }
        val e = w.entries.filter { it.key.startsWith(pre) && (featCount[it.key] ?: 0) >= 2 }.mapNotNull { en -> human(en.key)?.let { it to en.value } }
            .groupBy { it.first }.map { (k, v) -> k to v.sumOf { it.second } }
        val pos = e.filter { it.second > 0.03 }.sortedByDescending { it.second }.take(n).map { "${it.first} (+${(it.second * 100).toInt()}%)" }
        val neg = e.filter { it.second < -0.03 }.sortedBy { it.second }.take(n).map { "${it.first} (${(it.second * 100).toInt()}%)" }
        return pos to neg
    }

    fun insightsText(c: Context, video: Boolean?): String {
        val (p, n) = insights(c, video)
        if (p.isEmpty() && n.isEmpty()) return ""
        return "📊 O QUE OS NÚMEROS MOSTRAM (modelo treinado em ${trainedOn()} posts):\n" +
            (if (p.isNotEmpty()) "Ajuda: " + p.joinToString("; ") + "\n" else "") + (if (n.isNotEmpty()) "Atrapalha: " + n.joinToString("; ") else "")
    }
}
