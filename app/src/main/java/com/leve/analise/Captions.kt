package com.leve.analise

import android.content.Context
import org.json.JSONArray

/** Estilo das legendas e banco de legendas virais (o que antes ficava só no Post Leve web). */
object Captions {
    val VIBES = listOf("Provocante leve", "Misteriosa", "Engraçada / irônica", "Puxar interação (carinhosa)",
        "Confiante", "Pergunta pra gerar comentário", "Romântica")
    val SIZES = listOf("Curtinha (até 8 palavras)", "Média (1 frase)", "Com gancho + pergunta")
    val CTAS = listOf("gostou, amor?", "e aí, aprova?", "me fala nos comentários 👇", "1 a 10, sem mentir",
        "posto mais assim?", "fala a verdade, ficou bom?", "vem conversar comigo", "me conta o que achou", "gostou ou quer mais?")

    private fun p(c: Context) = Store.prefs(c)
    fun vibe(c: Context) = p(c).getString("vibe", VIBES[3]) ?: VIBES[3]
    fun size(c: Context) = p(c).getString("tam", SIZES[0]) ?: SIZES[0]
    fun ctaMode(c: Context) = p(c).getString("cta_mode", "Variado") ?: "Variado"
    fun ctx(c: Context) = p(c).getString("ctx", "") ?: ""

    fun refs(c: Context): List<String> = CaptionDB.texts(c, "viral")

    fun addRef(c: Context, t: String) = CaptionDB.add(c, t, "viral")
    fun removeRef(c: Context, t: String) = CaptionDB.remove(c, t)

    /** Leva as legendas que mais engajaram nos prints analisados pro banco viral. */
    fun pullFromAnalysis(c: Context): Int {
        val top = Store.sorted(c).map { it.optString("legenda").trim() }.filter { it.length >= 3 }.take(15)
        val cur = refs(c)
        val novos = top.filter { it !in cur }
        novos.forEach { CaptionDB.add(c, it, "viral") }
        return novos.size
    }

    /** Coloca o final de interação conforme o modo escolhido. */
    fun withCta(c: Context, list: List<String>): List<String> = when (ctaMode(c)) {
        "Sem final" -> list
        "Fixo" -> {
            val f = p(c).getString("cta_fixed", "")?.trim().orEmpty().ifEmpty { CTAS[0] }
            list.map { it.trimEnd() + "\n" + f }
        }
        else -> { val pool = CTAS.shuffled(); list.mapIndexed { i, s -> s.trimEnd() + "\n" + pool[i % pool.size] } }
    }

    fun prompt(c: Context): String {
        val refs = refs(c).take(12)
        val top = Store.sorted(c).map { it.optString("legenda") }.filter { it.isNotBlank() && it !in refs }.take(6)
        val pats = Store.loadPatterns(c).let { a -> (0 until a.length()).map { a.optString(it) } }
        val wins = CaptionDB.winners(c, 12)
        val best = wins.map { it.optString("t") }
        val flops = CaptionDB.losers(c, 8).map { it.optString("t") }
        val words = CaptionDB.winningWords(c, 10)
        val ex = (refs + top).filter { it !in best }.distinct().take(14)
        return """Você escreve legendas para posts de foto no Threads, em português do Brasil, no estilo de perfis que viralizam.
Vibe: ${vibe(c)}. Tamanho: ${size(c)}. Contexto: ${ctx(c).ifEmpty { "nenhum" }}.
${learnedText(c)}
${Predictor.insightsText(c, false)}
${ThreadsApi.aiText(c)}
${Growth.aiText(c)}
${if (wins.isNotEmpty()) "🔥 LEGENDAS QUE BOMBARAM NO MEU PERFIL (prioridade máxima): metade das suas legendas deve ser VARIAÇÃO delas — mesmo tema, mesma estrutura e o mesmo tipo de gancho, com outras palavras:\n" + wins.joinToString("\n") { "- ${it.optString("t").replace('\n', ' ')}  (🔥${it.optInt("good")})" } + "\n(as com mais 🔥 viralizaram de verdade: priorize o estilo delas)" else ""}
${if (flops.isNotEmpty()) "👎 FLOPARAM (evite esse tom e esse assunto):\n" + flops.joinToString("\n") { "- ${it.replace('\n', ' ')}" } else ""}
${if (words.isNotEmpty()) "Palavras que aparecem nas que bombam: " + words.joinToString(", ") else ""}
${if (mine(c).isNotEmpty()) "LEGENDAS QUE EU ESCREVI — escreva no mesmo estilo (tamanho, jeito de falar, gírias), sem copiar:\n" + mine(c).take(12).joinToString("\n") { "- $it" } else ""}
${if (ex.isNotEmpty()) "Outras legendas de referência que viralizaram (copie o ritmo e o tom, nunca o texto):\n" + ex.joinToString("\n") { "- $it" } else ""}
${if (pats.isNotEmpty()) "Padrões que mais funcionaram (siga):\n" + pats.joinToString("\n") { "- $it" } else ""}
Use a foto anexada para deixar as legendas específicas.
Não termine com pergunta ou chamada pra comentar: o app adiciona esse final depois.
Regras: naturais, em minúsculas quando combinar, no máximo 1 emoji, sem hashtags, nada explícito, nada que finja ser outra pessoa real.
Responda só com JSON: {"legendas":["...","...","...","...","..."]}"""
    }

    /** IA gera legendas sozinha pra toda foto nova? (desligado = usa o seu banco, sem gastar crédito) */
    fun aiAuto(c: Context) = p(c).getBoolean("cap_ai_auto", false)
    fun setAiAuto(c: Context, on: Boolean) = p(c).edit().putBoolean("cap_ai_auto", on).apply()

    /** Pede legendas da IA pras N fotos prontas mais antigas que ainda não têm legenda de IA. */
    fun requestAi(c: Context, n: Int): Int {
        val ids = PostQueue.load(c).filter { it.optString("status") == "pronta" && !it.optBoolean("ia") }.map { it.optString("id") }.let { if (n > 0) it.take(n) else it }
        ids.forEach { id -> PostQueue.update(c, id) { it.put("status", "gerando"); it.put("pedido", true) } }
        return ids.size
    }

    /** Sem IA automática: fotos esperando legenda recebem legendas do banco na hora. */
    fun fillFromBank(c: Context): Int {
        if (aiAuto(c)) return 0
        val ids = PostQueue.load(c).filter { it.optString("status") == "gerando" && !it.optBoolean("pedido") }.map { it.optString("id") }
        ids.forEach { id -> PostQueue.update(c, id) { it.put("legendas", JSONArray(randomCaptions(c, 5))); it.put("status", "pronta"); it.put("ia", false) } }
        return ids.size
    }

    /** Marca todas as fotos ainda não postadas pra ganhar legendas novas com o estilo atual. */
    fun regenerateAll(c: Context): Int {
        val ids = PostQueue.load(c).filter { it.optString("status") == "pronta" }.map { it.optString("id") }
        ids.forEach { id -> PostQueue.update(c, id) { it.put("status", "gerando") } }
        return ids.size
    }
    /** ✨ IA: variações novas das legendas que bombaram (entram no banco e no sorteio). */
    fun variationsOfWinners(c: Context, n: Int): Int {
        val wins = CaptionDB.winners(c, 10).map { it.optString("t") }
        if (wins.isEmpty()) return -1
        val l = Ai.variations(c, wins, CaptionDB.losers(c, 8).map { it.optString("t") }, mine(c).take(10), n)
        l.forEach { CaptionDB.add(c, it, "var") }
        return l.size
    }

    // ---------- minhas legendas (escritas na mão, reaproveitadas no sorteio) ----------
    fun mine(c: Context): List<String> = CaptionDB.texts(c, "minha")
    fun addMine(c: Context, t: String) = CaptionDB.add(c, t, "minha")
    fun removeMine(c: Context, t: String) = CaptionDB.remove(c, t)

    /**
     * Sorteio do post: uma foto aleatória (as ainda não postadas primeiro; se todas já foram, reusa)
     * e uma legenda aleatória entre as dela e as que você escreveu.
     */
    // ---------- variedade: o que saiu por último não sai de novo logo em seguida
    private fun recent(c: Context, k: String): List<String> = try {
        val a = JSONArray(p(c).getString(k, "[]")); (0 until a.length()).map { a.optString(it) }
    } catch (e: Exception) { emptyList() }
    private fun pushRecent(c: Context, k: String, v: String, max: Int) {
        if (v.isBlank()) return
        val l = (listOf(v) + recent(c, k).filter { it != v }).take(max)
        p(c).edit().putString(k, JSONArray(l).toString()).apply()
    }
    fun recentCaps(c: Context) = recent(c, "rec_caps")
    fun recentPhotos(c: Context) = recent(c, "rec_fotos")
    /** Guarda o que acabou de sair (foto + legenda) pra não repetir em seguida. */
    fun remember(c: Context, photoId: String?, cap: String?) {
        photoId?.let { pushRecent(c, "rec_fotos", it, 40) }
        cap?.let { pushRecent(c, "rec_caps", it.trim(), 40) }
    }

    /**
     * Sorteio do post: foto e legenda variadas.
     * - fotos que saíram há pouco ficam de fora (até ~60% da fila, no máximo 20);
     * - foto nova tem mais chance; a que bombou pode voltar, mas só depois de outras;
     * - legenda: nunca uma das últimas (até 12); a que bombou tem mais chance.
     */
    // ---------- combinações foto + legenda
    private fun pairKey(id: String, cap: String) = id + "|" + cap.trim().lowercase().hashCode()
    private fun pairs(c: Context): org.json.JSONObject = try { org.json.JSONObject(p(c).getString("pares", "{}")) } catch (e: Exception) { org.json.JSONObject() }

    /** Nota da combinação (esta foto COM esta legenda). */
    fun ratePair(c: Context, id: String, cap: String, k: String, undo: String = "") {
        if (cap.isBlank()) return
        val all = pairs(c); val key = pairKey(id, cap)
        val e = all.optJSONObject(key) ?: org.json.JSONObject()
        if (undo.isNotEmpty()) e.put(undo, maxOf(0, e.optInt(undo) - 1))
        e.put(k, e.optInt(k) + 1); all.put(key, e)
        p(c).edit().putString("pares", all.toString()).apply()
    }

    /** Multiplicador: combinação que bombou ≈ 4x; que flopou ≈ 0,25x; nunca testada = 1. */
    fun pairBonus(c: Context, id: String): (String) -> Double {
        val all = pairs(c)
        return { cap ->
            val e = all.optJSONObject(pairKey(id, cap))
            if (e == null) 1.0 else {
                val g = e.optInt("good"); val m = e.optInt("meh"); val b = e.optInt("bad")
                Math.exp(4.0 * ((g + 0.5 * m + 1.0) / (g + m + b + 2.0) - 0.5))
            }
        }
    }

    /** Bônus da legenda pra uma foto: combinação já testada + previsão pelas características da legenda. */
    fun capBonus(c: Context, id: String): (String) -> Double {
        val pb = pairBonus(c, id); val ready = Predictor.ready(c)
        return { cap -> pb(cap) * (if (ready) Math.exp(3.0 * (Predictor.caption(c, cap) - 0.5)) else 1.0) *
            (ThreadsApi.captionScore(c, cap)?.let { Math.exp(3.0 * (it - 0.5)) } ?: 1.0) } // legenda que rendeu nos números sai mais
    }

    /** Regras que a IA aprendeu comparando fotos + legendas. */
    fun learned(c: Context): List<String> = try { val a = JSONArray(p(c).getString("combo_regras", "[]")); (0 until a.length()).map { a.optString(it) } } catch (e: Exception) { emptyList() }
    fun learnedText(c: Context) = learned(c).takeIf { it.isNotEmpty() }?.let { "🧠 O QUE JÁ SE APRENDEU NESTE PERFIL (foto + legenda) — siga:\n" + it.joinToString("\n") { r -> "- $r" } } ?: ""

    /** 🧠 Manda os posts avaliados pra IA comparar as combinações. Devolve as regras (ou erro). */
    fun learnCombos(c: Context): List<String> {
        val rated = PostQueue.load(c).filter { o ->
            PostQueue.imageFile(c, o.optString("id")).exists() && PostQueue.captionOf(o).isNotBlank() &&
                (o.optInt("good") + o.optInt("meh") + o.optInt("bad") > 0 || o.optString("nota").isNotEmpty())
        }
        if (rated.size < 3) throw AiException(-3, "Avalie pelo menos 3 posts (🔥/😐/👎) pra IA comparar. Agora tem ${rated.size}.")
        // mistura vencedoras e perdedoras (até 10 fotos)
        val sorted = rated.sortedByDescending { photoMean(it) }
        val pick = (sorted.take(6) + sorted.takeLast(4)).distinct().take(10)
        val list = pick.map { o ->
            val k = Learn.verdict(o).ifEmpty { "meh" }
            val h = Learn.histText(o) + (ThreadsApi.posts(c).firstOrNull { it.optString("photo") == o.optString("id") }?.let { m ->
                " · números: ${m.optInt("views")} visualizações, ${m.optInt("likes")} curtidas, ${m.optInt("replies")} respostas, +${"%.1f".format(m.optDouble("fol", 0.0))} seguidores" } ?: "")
            Triple(PostQueue.imageFile(c, o.optString("id")), PostQueue.captionOf(o) + (if (h.isNotEmpty()) "  [$h]" else ""), k)
        }
        val regras = Ai.learnCombos(c, list, ThreadsApi.aiText(c) + "\n" + Learn.repostStats(rated) + "\n" + Learn.hourStats(rated) + "\n" + Predictor.insightsText(c, false) + "\n💎 = VIRALIZOU (muito acima do normal): dê peso máximo ao que essas têm em comum.")
        if (regras.isNotEmpty()) p(c).edit().putString("combo_regras", JSONArray(regras).toString()).putLong("combo_at", System.currentTimeMillis()).apply()
        return regras
    }

    fun photoMean(o: org.json.JSONObject): Double {
        val g = o.optInt("good"); val m = o.optInt("meh"); val b = o.optInt("bad")
        val legacy = o.optString("nota").takeIf { g + m + b == 0 } // notas antigas (antes de acumular)
        val gg = g + (if (legacy == "good") 1 else if (legacy == "top") 2 else 0); val mm = m + (if (legacy == "meh") 1 else 0); val bb = b + (if (legacy == "bad") 1 else 0)
        return (gg + 0.5 * mm + 1.0) / (gg + mm + bb + 2.0)
    }

    /** Legendas que podem sair agora: as que bombaram só esperam 3 posts; as outras, 12. */
    fun capAvoid(c: Context): List<String> {
        val rec = recentCaps(c)
        return rec.take(12 + Health.capExtra(c)).filterIndexed { i, t -> i < 3 || !CaptionDB.isWinner(c, t) }
    }

    /**
     * Sorteio do post, valorizando MUITO o que bombou, mas nunca repetindo em seguida:
     * - foto que bombou pode voltar depois de 2 outras; as normais esperam ~60% da fila;
     * - peso da foto: bombou ≈ 3x a 8x; nova = 2x; 👎 bem menos;
     * - legenda: vencedoras entram no sorteio de qualquer foto e têm peso alto.
     */
    fun randomPick(c: Context, promo: Boolean = true): Pair<String, String>? {
        if (promo) Promo.maybe(c)?.let { return it } // 📣 é a vez da divulgação do Instagram
        val all = PostQueue.load(c).filter { PostQueue.imageFile(c, it.optString("id")).exists() && it.optString("status") != "arquivada" && Saturation.photoOk(it) }
            .ifEmpty { PostQueue.load(c).filter { PostQueue.imageFile(c, it.optString("id")).exists() && it.optString("status") != "arquivada" } }
            .ifEmpty { PostQueue.load(c).filter { PostQueue.imageFile(c, it.optString("id")).exists() } }
        if (all.isEmpty()) return null
        val rec = recentPhotos(c)
        val block = Burst.currentBlock(c).takeIf { Burst.inBlock(c) }.orEmpty()
        val blockLooks = block.mapNotNull { e -> all.firstOrNull { it.optString("id") == e.optString("id") }?.let { Predictor.photoLook(c, it) } }.toSet()
        val blockTypes = block.map { Burst.capType(it.optString("cap")) }.toSet()
        if (block.isEmpty()) AB.pick(c, all, rec)?.let { (aid, acap) -> remember(c, aid, acap); return aid to acap } // 🧪 teste: mesma foto, legenda nova
        val byId = all.associateBy { it.optString("id") }
        Health.takeForced(c, "force_fotos") { id -> id in byId && id !in rec.take(2) }?.let { fid ->
            val o = byId.getValue(fid)
            val own = o.optJSONArray("legendas")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val pool = (own + mine(c) + CaptionDB.winners(c, 20).map { it.optString("t") }).filter { it.isNotBlank() }.distinct()
            val cap = (if (pool.isNotEmpty()) CaptionDB.pick(c, pool, capAvoid(c), capBonus(c, fid)) else null) ?: randomCaption(c, capAvoid(c))
            remember(c, fid, cap); return fid to cap
        }
        val k = minOf(all.size - 1, maxOf(minOf(3, all.size - 1), (all.size * 0.6).toInt()), 20)
        val winGap = minOf(maxOf(4, (all.size * 0.4).toInt()), k)
        val cand0 = all.filter { o ->
            val idx = rec.indexOf(o.optString("id"))
            idx < 0 || idx >= (if (photoMean(o) > 0.6) winGap else k)
        }
        // 👯 no meio da dupla/trio: foto com outra "cara" (cenário/pose) que as do bloco
        val cand = if (blockLooks.isEmpty()) cand0 else cand0.filter { Predictor.photoLook(c, it) !in blockLooks }.ifEmpty { cand0 }.ifEmpty { all.filter { it.optString("id") != rec.firstOrNull() }.ifEmpty { all } }
        val w = cand.map { o ->
            val posted = o.optString("status") == "postada"
            val idx = rec.indexOf(o.optString("id"))
            val m0 = Predictor.blend(Learn.chanceMean(c, o), Learn.rated(o), Predictor.photo(c, o))
            // 📚 números captados (tela/insights/print/API): a foto que rende de verdade sai mais
            val m = ThreadsApi.photoScore(c, o.optString("id"))?.let { real -> (m0 + 2 * real) / 3 } ?: m0
            val score = minOf(3.0, Math.exp(5.0 * (m - 0.5)) * (1.0 + 0.6 * o.optInt("top")))
            score * (if (!posted) 2.0 else 1.0) * (if (idx < 0) 1.5 else 1.0 + idx / 10.0)
        }
        var r = Math.random() * w.sum(); var o = cand.last()
        for (i in cand.indices) { r -= w[i]; if (r <= 0) { o = cand[i]; break } }
        val own = o.optJSONArray("legendas")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val avoid = capAvoid(c)
        val wins = CaptionDB.winners(c, 20).map { it.optString("t") }
        val pool1 = (own + mine(c) + wins + CaptionDB.texts(c, "var").take(30)).filter { it.isNotBlank() }.distinct()
        // 👯 e outro tipo de legenda (tamanho/pergunta/emoji) que as do bloco
        val pool = if (blockTypes.isEmpty()) pool1 else pool1.filter { Burst.capType(it) !in blockTypes }.ifEmpty { pool1 }
        val cap = (if (pool.isNotEmpty()) CaptionDB.pick(c, pool, avoid, capBonus(c, o.optString("id"))) else null) ?: randomCaption(c, avoid)
        remember(c, o.optString("id"), cap)
        return o.optString("id") to cap
    }
    private val DAYS = listOf("domingo", "segunda", "terça", "quarta", "quinta", "sexta", "sábado")
    /** Legenda que fala de um dia da semana ("sextou", "segunda-feira"…) só sai nesse dia. */
    fun fitsToday(t: String): Boolean {
        val s = t.lowercase()
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
        val words = mapOf(0 to listOf("domingo", "domingou"), 1 to listOf("segunda", "segundou"), 2 to listOf("terça", "terca", "terçou"), 3 to listOf("quarta", "quartou"),
            4 to listOf("quinta", "quintou"), 5 to listOf("sexta", "sextou", "sextô"), 6 to listOf("sábado", "sabado", "sabadou"))
        val mentioned = words.filter { (_, ws) -> ws.any { Regex("\\b" + it + "\\b").containsMatchIn(s) } }.keys
        return mentioned.isEmpty() || today in mentioned
    }

    /** Legenda sem IA: sorteia das suas (como estão) ou das prontas (com o final de interação), fugindo das últimas. */
    fun randomCaption(c: Context, avoid0: Collection<String> = emptyList()): String {
        val avoid = avoid0 + capAvoid(c)
        if (mine(c).isNotEmpty() && Math.random() < 0.7) CaptionDB.pick(c, null, avoid)?.let { return it }
        val base = Ai.OFFLINE.filter { o -> avoid.none { it.startsWith(o) } && fitsToday(o) }.ifEmpty { Ai.OFFLINE.filter { fitsToday(it) } }.ifEmpty { Ai.OFFLINE }
        return withCta(c, listOf(base.random())).first()
    }

    fun randomCaptions(c: Context, n: Int): List<String> {
        val out = ArrayList<String>()
        repeat(n * 3) { if (out.size < n) { val r = randomCaption(c, out); if (r !in out) out.add(r) } }
        return out
    }
}
