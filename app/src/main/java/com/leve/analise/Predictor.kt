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
        o.optInt("dur").takeIf { it > 0 }?.let { f += "dur:" + when { it <= 7 -> "curto"; it <= 11 -> "medio"; else -> "longo" } }
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
    private class Sample(val f: List<String>, val y: Double, val w: Double)

    private fun gapB(ms: Long) = when { ms < 20 * 60_000L -> "colado"; ms < 60 * 60_000L -> "curto"; ms < 3 * 3600_000L -> "medio"; else -> "longo" }

    private fun samples(c: Context): List<Sample> {
        val out = ArrayList<Sample>()
        val lib = VideoExport.library(c).associateBy { it.optString("id") }
        val q = PostQueue.load(c).associateBy { it.optString("id") }
        val seen = HashSet<String>()
        val now = System.currentTimeMillis()
        // posts recentes pesam mais (o que funciona muda com o tempo): metade do peso a cada 60 dias
        fun wt(at: Long) = Math.pow(0.5, (now - at) / (60.0 * 86400_000L)).coerceIn(0.25, 1.0)
        val sends = SendLog.all(c)
        val byKind = sends.groupBy { it.optString("kind") == "video" }.mapValues { (_, v) -> v.sortedBy { it.optLong("at") } }
        sends.forEach { e ->
            val at = e.optLong("at")
            val y = ThreadsApi.targetFor(c, at) ?: e.optString("nota").takeIf { it.isNotEmpty() }?.let { score(it) } ?: return@forEach
            seen += e.optString("id") + "@" + (at / 3600_000L)
            val video = e.optString("kind") == "video"
            val extra = ArrayList<String>()
            // ritmo: quanto tempo depois do post anterior do mesmo tipo, e a posição na dupla/trio
            byKind[video]?.let { l -> val i = l.indexOfFirst { it.optLong("at") == at }; if (i > 0) extra += "g.gap:" + gapB(at - l[i - 1].optLong("at")) }
            if (!video && e.optInt("rajada", 1) > 1) extra += "g.pos:" + e.optInt("pos", 1)
            if (video) lib[e.optString("id")]?.let { out += Sample(videoFeats(c, it, e.optString("cap"), at) + extra, y, wt(at)) }
            else q[e.optString("id")]?.let { out += Sample(photoFeats(c, it, e.optString("cap"), at) + extra, y, wt(at)) }
        }
        // notas antigas (antes do histórico de envios)
        lib.values.forEach { o -> Learn.hist(o).forEach { (at, k) -> if (seen.add(o.optString("id") + "@" + (at / 3600_000L))) out += Sample(videoFeats(c, o, o.optString("legenda"), at), score(k), wt(at)) } }
        q.values.forEach { o -> Learn.hist(o).forEach { (at, k) -> if (seen.add(o.optString("id") + "@" + (at / 3600_000L))) out += Sample(photoFeats(c, o, PostQueue.captionOf(o), at), score(k), wt(at)) } }
        return out
    }

    /**
     * Modelo linear (cada característica soma ou tira pontos). Com [prior], começa do que foi aprendido com
     * OUTRA modelo e só se afasta disso quando os números desta mostram diferente (aprendizado transferido).
     */
    private fun fit(data: List<Sample>, epochs: Int = 60, prior: Map<String, Double> = emptyMap()): Pair<Map<String, Double>, Double> {
        val ww = HashMap<String, Double>(prior)
        val tw = data.sumOf { it.w }
        var b = if (tw > 0) data.sumOf { it.y * it.w } / tw else 0.5
        val lr = 0.05; val l2 = 0.02
        val rnd = java.util.Random(7)
        repeat(epochs) {
            data.shuffled(rnd).forEach { s ->
                val pred = b + s.f.sumOf { ww[it] ?: 0.0 }
                val err = (s.y - pred).coerceIn(-1.0, 1.0) * s.w
                b += lr * 0.2 * err
                s.f.forEach { k -> val cur = ww[k] ?: 0.0; ww[k] = cur + lr * (err - l2 * (cur - (prior[k] ?: 0.0))) }
            }
        }
        return ww to b
    }

    /**
     * 🧠 Rede neural pequena (1 camada escondida, 8 neurônios): pega COMBINAÇÕES que o linear não vê
     * (ex.: "pergunta funciona à noite mas não de manhã"). Com poucos posts ela costuma decorar em vez de aprender —
     * por isso ela só é usada se ganhar do linear no teste com posts que nenhum dos dois viu.
     */
    private class Mlp(val idx: Map<String, Int>, val w1: Array<DoubleArray>, val b1: DoubleArray, val w2: DoubleArray, var b2: Double) {
        fun hidden(f: List<String>): DoubleArray {
            val h = b1.copyOf()
            f.forEach { k -> idx[k]?.let { i -> for (j in h.indices) h[j] += w1[i][j] } }
            for (j in h.indices) h[j] = Math.tanh(h[j])
            return h
        }
        fun predict(f: List<String>): Double { val h = hidden(f); var o = b2; for (j in h.indices) o += w2[j] * h[j]; return o.coerceIn(0.02, 0.98) }
    }

    private fun fitMlp(data: List<Sample>, epochs: Int = 80, hiddenN: Int = 8): Mlp {
        val keys = data.flatMap { it.f }.distinct()
        val idx = keys.withIndex().associate { it.value to it.index }
        val rnd = java.util.Random(5)
        val w1 = Array(keys.size) { DoubleArray(hiddenN) { (rnd.nextDouble() - 0.5) * 0.2 } }
        val b1 = DoubleArray(hiddenN); val w2 = DoubleArray(hiddenN) { (rnd.nextDouble() - 0.5) * 0.2 }
        val tw = data.sumOf { it.w }
        val net = Mlp(idx, w1, b1, w2, if (tw > 0) data.sumOf { it.y * it.w } / tw else 0.5)
        val lr = 0.03; val l2 = 0.01
        repeat(epochs) {
            data.shuffled(rnd).forEach { s ->
                val h = net.hidden(s.f)
                var o = net.b2; for (j in h.indices) o += w2[j] * h[j]
                val err = (s.y - o).coerceIn(-1.0, 1.0) * s.w
                net.b2 += lr * 0.2 * err
                for (j in h.indices) {
                    val g = err * w2[j] * (1 - h[j] * h[j])
                    w2[j] += lr * (err * h[j] - l2 * w2[j])
                    b1[j] += lr * g
                    s.f.forEach { k -> idx[k]?.let { i -> w1[i][j] += lr * (g - l2 * w1[i][j]) } }
                }
            }
        }
        return net
    }

    @Volatile private var lastCheck = 0L
    @Volatile private var pairAcc = -1.0   // % de comparações que o modelo acerta em posts que ele NÃO viu no treino
    @Volatile private var skill = 0.0      // 0 = não ajuda; 1 = perfeito
    @Volatile private var trust = 1.5      // quanto a previsão pesa contra o histórico do próprio item
    @Volatile private var mode = "linear"  // vencedor do torneio: linear, rede ou os dois juntos
    @Volatile private var net: Mlp? = null
    @Volatile private var scores: Map<String, Double> = emptyMap()
    @Volatile private var priorFrom = ""

    private fun pairAccOf(data: List<Sample>, preds: DoubleArray): Double {
        var ok = 0; var tot = 0
        val smp = data.indices.shuffled(java.util.Random(3)).take(120)
        for (a in smp) for (b2 in smp) if (a < b2 && Math.abs(data[a].y - data[b2].y) > 0.15) { tot++; if ((preds[a] - preds[b2]) * (data[a].y - data[b2].y) > 0) ok++ }
        return if (tot >= 10) ok.toDouble() / tot else -1.0
    }

    /** Re-treina quando surgem notas novas. Faz um TORNEIO: linear × rede neural × os dois juntos, no teste com posts que nenhum viu. */
    @Synchronized fun train(c: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && sig.isNotEmpty() && now - lastCheck < 30_000L) return
        lastCheck = now
        val s = SendLog.all(c).count { it.optString("nota").isNotEmpty() }.toString() + ":" + VideoExport.library(c).sumOf { Learn.rated(it) } + ":" + PostQueue.load(c).sumOf { Learn.rated(it) } + ":" + ThreadsApi.posts(c).size + ":" + Profiles.priorSig(c)
        if (!force && s == sig) return
        val raw = samples(c)
        sig = s; nTrain = raw.size
        // 🔁 aprendizado de outra modelo: começa daí (pesa menos conforme esta junta dados próprios)
        val pr = Profiles.prior(c); priorFrom = Profiles.priorName(c)
        val shrink = 1.0 / (1.0 + raw.size / 40.0)
        val prior = pr.mapValues { it.value * shrink }
        if (raw.size < 4) { w = prior; bias = 0.5; pairAcc = -1.0; skill = 0.0; trust = if (prior.isEmpty()) 1.0 else 0.8; mode = "linear"; net = null; return }
        featCount.clear(); raw.forEach { it.f.forEach { k -> featCount[k] = (featCount[k] ?: 0) + 1 } }
        val data = raw.map { s0 -> Sample(s0.f.filter { !(it.contains("w:") && (featCount[it] ?: 0) < 2) }, s0.y, s0.w) }
        if (data.size >= 12) {
            val k = 5; val idx = data.indices.shuffled(java.util.Random(11))
            val pL = DoubleArray(data.size); val pN = DoubleArray(data.size); val base = DoubleArray(data.size)
            val tryNet = data.size >= 25
            for (fold in 0 until k) {
                val test = idx.filterIndexed { i, _ -> i % k == fold }.toSet()
                val tr = data.filterIndexed { i, _ -> i !in test }
                val (fw, fb) = fit(tr, 40, prior)
                val nn = if (tryNet) fitMlp(tr, 50) else null
                val mean = tr.map { it.y }.average()
                test.forEach { i -> pL[i] = (fb + data[i].f.sumOf { fw[it] ?: 0.0 }).coerceIn(0.02, 0.98); pN[i] = nn?.predict(data[i].f) ?: pL[i]; base[i] = mean }
            }
            val pE = DoubleArray(data.size) { (pL[it] + pN[it]) / 2 }
            val accL = pairAccOf(data, pL); val accN = if (tryNet) pairAccOf(data, pN) else -1.0; val accE = if (tryNet) pairAccOf(data, pE) else -1.0
            scores = mapOf("linear" to accL, "rede" to accN, "juntos" to accE)
            // a rede só ganha se for MELHOR de verdade (≥2 pontos); empate fica com o mais simples
            mode = when { accN > accL + 0.02 && accN >= accE -> "rede"; accE > accL + 0.02 -> "juntos"; else -> "linear" }
            val chosen = when (mode) { "rede" -> pN; "juntos" -> pE; else -> pL }
            pairAcc = maxOf(accL, if (mode == "rede") accN else -1.0, if (mode == "juntos") accE else -1.0)
            val mae = data.indices.sumOf { Math.abs(chosen[it] - data[it].y) } / data.size
            val mae0 = data.indices.sumOf { Math.abs(base[it] - data[it].y) } / data.size
            skill = if (mae0 > 0) (1 - mae / mae0).coerceIn(-0.5, 1.0) else 0.0
            trust = when { pairAcc < 0 -> 1.5; pairAcc <= 0.52 -> 0.4; else -> (0.4 + (pairAcc - 0.5) * 16).coerceAtMost(4.5) }
        } else { pairAcc = -1.0; skill = 0.0; trust = 1.5; mode = "linear"; scores = emptyMap() }
        val (ww, b) = fit(data, 60, prior)
        w = ww; bias = b
        net = if (mode != "linear") fitMlp(data) else null
        Store.prefs(c).edit().putInt("pred_n", data.size).putFloat("pred_acc", pairAcc.toFloat()).putString("pred_mode", mode).apply()
        Profiles.saveWeights(c, ww)
    }

    private fun predict(f: List<String>): Double {
        val lin = (bias + f.sumOf { w[it] ?: 0.0 }).coerceIn(0.02, 0.98)
        val n = net ?: return lin
        return when (mode) { "rede" -> n.predict(f); "juntos" -> (lin + n.predict(f)) / 2; else -> lin }
    }

    /** Chance (0..1) do vídeo ir bem se postar agora. */
    fun video(c: Context, o: JSONObject): Double { train(c); return if (w.isEmpty()) 0.5 else predict(videoFeats(c, o, o.optString("legenda"), System.currentTimeMillis())) }
    /** Chance da foto (com a legenda, se der) ir bem se postar agora. */
    fun photo(c: Context, o: JSONObject, cap: String? = null): Double { train(c); return if (w.isEmpty()) 0.5 else predict(photoFeats(c, o, cap, System.currentTimeMillis())) }
    /** Só a parte da legenda (pra escolher legenda). */
    fun caption(c: Context, cap: String): Double { train(c); return if (w.isEmpty()) 0.5 else predict(textFeats(cap, "leg.").map { "f.$it" }) }

    fun ready(c: Context): Boolean { train(c); return w.isNotEmpty() }
    /** Peso que o modelo dá a uma característica (0 = não sabe). */
    fun weight(c: Context, key: String): Double { train(c); return w[key] ?: 0.0 }
    fun trainedOn() = nTrain

    /** Junta o histórico do próprio item com a previsão. O peso da previsão vem da prova: modelo que acerta pouco quase não influencia. */
    fun blend(itemMean: Double, rated: Int, pred: Double, k: Double = trust) = (0.5 + itemMean * rated + pred * k) / (1.0 + rated + k)

    /** Quanto dá pra confiar no modelo, em palavras. */
    fun accuracyText(c: Context): String {
        train(c)
        if (nTrain < 4) return "🎯 Modelo de previsão: ainda aprendendo ($nTrain posts com resultado; precisa de 12 pra se testar)." + (if (priorFrom.isNotEmpty()) " Enquanto isso usa o que aprendeu com $priorFrom." else "")
        if (pairAcc < 0) return "🎯 Modelo de previsão: treinado em $nTrain posts (precisa de 12 com resultados diferentes pra se testar)."
        val lvl = when { pairAcc >= 0.70 -> "alta"; pairAcc >= 0.60 -> "média"; pairAcc > 0.52 -> "baixa"; else -> "nenhuma por enquanto (está no chute: pesa quase nada no sorteio)" }
        val tor = scores.filterValues { it >= 0 }.takeIf { it.size >= 2 }?.let { m -> " · 🏁 torneio: " + m.entries.joinToString(", ") { "${mapOf("linear" to "linear", "rede" to "rede neural", "juntos" to "os dois juntos")[it.key]} ${(it.value * 100).toInt()}%" } + " → venceu " + mapOf("linear" to "o linear", "rede" to "a rede neural", "juntos" to "os dois juntos")[mode] } ?: ""
        val pf = if (priorFrom.isNotEmpty()) " · começou com o que aprendeu com $priorFrom" else ""
        return "🎯 Modelo de previsão: entre dois posts que ele nunca viu, acerta qual vai melhor em ${(pairAcc * 100).toInt()}% das vezes ($nTrain posts) · confiança $lvl$tor$pf"
    }

    // ---------- o que aprendeu, em palavras
    private fun human(f: String): String? {
        val k = f.substringAfter('.')
        return when {
            k.startsWith("cat:") -> "categoria " + k.removePrefix("cat:")
            k.startsWith("mus:") -> if (k == "mus:original") "som original" else "música \"" + k.removePrefix("mus:") + "\""
            k.startsWith("mod:") -> "edição ★ " + k.removePrefix("mod:")
            k.startsWith("dur:") -> "vídeo " + mapOf("curto" to "curto (até 7 s)", "medio" to "médio (8–11 s)", "longo" to "longo (12 s+)")[k.removePrefix("dur:")]
            k.startsWith("h:") -> "postar " + mapOf("madrugada" to "de madrugada", "manha" to "de manhã", "almoco" to "no almoço", "tarde" to "à tarde", "inicio_noite" to "no começo da noite", "noite" to "à noite")[k.removePrefix("h:")]
            k == "d:fds" -> "fim de semana"; k == "d:semana" -> "dia de semana"
            k.startsWith("gap:") -> "postar " + mapOf("colado" to "colado no anterior (menos de 20 min)", "curto" to "20–60 min depois do anterior", "medio" to "1–3 h depois do anterior", "longo" to "mais de 3 h depois do anterior")[k.removePrefix("gap:")]
            k.startsWith("pos:") -> k.removePrefix("pos:") + "º post da dupla/trio"
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
        val e = w.entries.filter { (it.key.startsWith(pre) || it.key.startsWith("g.")) && (featCount[it.key] ?: 0) >= 2 }.mapNotNull { en -> human(en.key)?.let { it to en.value } }
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
