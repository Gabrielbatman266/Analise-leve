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
${MetaKnow.THREADS}
${Lab.aiText(c)}
${Study.aiText(c)}
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
    /** ✨ Enche o banco ativo com legendas novas no estilo DELE (as que já tem; vencedoras primeiro). */
    fun fillBank(c: Context, n: Int): String {
        val all = CaptionDB.all(c).filter { it.optString("src") != "viral" }
        val ex = (CaptionDB.winners(c, 6).map { it.optString("t") } + all.shuffled().take(10).map { it.optString("t") }).distinct().take(12)
            .ifEmpty { mine(c).take(10) }
        if (ex.isEmpty()) return "Banco vazio: cole algumas legendas de exemplo primeiro (📋 Colar lista) pra IA pegar o estilo"
        val l = Ai.variations(c, ex, CaptionDB.losers(c, 6).map { it.optString("t") }, emptyList(), n)
        var k = 0; l.forEach { if (CaptionDB.find(c, it) == null) { CaptionDB.add(c, it, "ia"); k++ } }
        return "✓ $k legenda(s) novas no banco ${Banks.activeName(c)}"
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
    /** Marca o que SAIU de verdade (chamado quando o post é mandado pro app). */
    fun remember(c: Context, photoId: String?, cap: String?) {
        photoId?.let { pushRecent(c, "rec_fotos", it, 40) }
        cap?.let { pushRecent(c, "rec_caps", it.trim(), 40) }
        if (photoId != null) Store.prefs(c).edit().remove("pick_now").apply()
    }

    /**
     * O sorteio fica guardado até ser postado (até 3 h): o lembrete mostra uma foto e o "Postar" abre ESSA mesma.
     * Antes o sorteio já marcava a foto como "saiu", então lembrete ignorado fazia a fila andar sem postar.
     */
    private fun hold(c: Context, id: String, cap: String): Pair<String, String> {
        Store.prefs(c).edit().putString("pick_now", org.json.JSONObject().put("id", id).put("cap", cap).put("at", System.currentTimeMillis()).toString()).apply()
        return id to cap
    }
    private fun held(c: Context, ok: (String) -> Boolean): Pair<String, String>? {
        val o = try { Store.prefs(c).getString("pick_now", null)?.let { org.json.JSONObject(it) } } catch (e: Exception) { null } ?: return null
        if (System.currentTimeMillis() - o.optLong("at") > 3 * 3600_000L || !ok(o.optString("id"))) { Store.prefs(c).edit().remove("pick_now").apply(); return null }
        return o.optString("id") to o.optString("cap")
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
        val pb = pairBonus(c, id); val ready = Predictor.ready(c); val law = Lab.bonus(c)
        return { cap -> pb(cap) * law(cap) * (if (ready) Math.exp(3.0 * (Predictor.caption(c, cap) - 0.5)) else 1.0) *
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

    /** Camada da foto e a qualidade (0..1) que vale dentro dela. */
    fun tierOf(c: Context, o: org.json.JSONObject): Pair<String, Double> {
        val rated = Learn.rated(o); val mean = Learn.chanceMean(c, o)
        val real = ThreadsApi.photoScore(c, o.optString("id"))
        val m0 = Predictor.blend(mean, rated, Predictor.photo(c, o))
        val m = real?.let { (m0 + 2 * it) / 3 } ?: m0
        val posted = o.optString("status") == "postada" || rated > 0
        val t = when {
            (rated >= 2 && mean >= 0.66) || (real != null && real >= 0.72 && (rated == 0 || mean >= 0.5)) -> "provada"
            (rated >= 1 && mean >= 0.58) || (real != null && real >= 0.55) -> "promessa"
            !posted -> "nova"
            else -> "resto"
        }
        return t to m
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
     * Sorteio do post (seleção em camadas: 🏆 provadas, 🌱 promessas, ✨ novas, resto), nunca repetindo em seguida:
     * - foto que bombou pode voltar depois de 2 outras; as normais esperam ~60% da fila;
     * - peso da foto: bombou ≈ 3x a 8x; nova = 2x; 👎 bem menos;
     * - legenda: vencedoras entram no sorteio de qualquer foto e têm peso alto.
     */
    fun randomPick(c: Context, promo: Boolean = true): Pair<String, String>? {
        if (promo) Promo.maybe(c)?.let { return it } // 📣 é a vez da divulgação do Instagram
        if (promo) TextPosts.maybe(c)?.let { return it } // ✍ é a vez de um post só de texto
        val all = PostQueue.load(c).filter { PostQueue.imageFile(c, it.optString("id")).exists() && it.optString("status") != "arquivada" && Saturation.photoOk(it) }
            .ifEmpty { PostQueue.load(c).filter { PostQueue.imageFile(c, it.optString("id")).exists() && it.optString("status") != "arquivada" } }
            .ifEmpty { PostQueue.load(c).filter { PostQueue.imageFile(c, it.optString("id")).exists() } }
        if (all.isEmpty()) return null
        val rec = recentPhotos(c)
        held(c) { id -> all.any { it.optString("id") == id } && id !in rec.take(1) }?.let { return it }
        val block = Burst.currentBlock(c).takeIf { Burst.inBlock(c) }.orEmpty()
        val blockLooks = block.mapNotNull { e -> all.firstOrNull { it.optString("id") == e.optString("id") }?.let { Predictor.photoLook(c, it) } }.toSet()
        val blockTypes = block.map { Burst.capType(it.optString("cap")) }.toSet()
        if (block.isEmpty()) AB.pick(c, all, rec)?.let { (aid, acap) -> return hold(c, aid, acap) } // 🧪 teste: mesma foto, legenda nova
        val byId = all.associateBy { it.optString("id") }
        Health.takeForced(c, "force_fotos") { id -> id in byId && id !in rec.take(2) }?.let { fid ->
            val o = byId.getValue(fid)
            val own = o.optJSONArray("legendas")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val pool = Banks.pool(c, own) ?: (own + mine(c) + CaptionDB.winners(c, 20).map { it.optString("t") }).filter { it.isNotBlank() }.distinct()
            val cap = (if (pool.isNotEmpty()) CaptionDB.pick(c, pool, capAvoid(c), capBonus(c, fid)) else null) ?: randomCaption(c, capAvoid(c))
            return hold(c, fid, cap)
        }
        val k = minOf(all.size - 1, maxOf(minOf(3, all.size - 1), (all.size * 0.6).toInt()), 20)
        val winGap = minOf(maxOf(4, (all.size * 0.4).toInt()), k)
        val cand0 = all.filter { o ->
            val idx = rec.indexOf(o.optString("id"))
            idx < 0 || idx >= (if (photoMean(o) > 0.6) winGap else k)
        }
        // 👯 no meio da dupla/trio: foto com outra "cara" (cenário/pose) que as do bloco
        val cand = if (blockLooks.isEmpty()) cand0 else cand0.filter { Predictor.photoLook(c, it) !in blockLooks }.ifEmpty { cand0 }.ifEmpty { all.filter { it.optString("id") != rec.firstOrNull() }.ifEmpty { all } }
        // 🏆 seleção em camadas: primeiro sorteia a CAMADA pela cota (as que deram certo têm a maior fatia garantida),
        // depois a foto dentro dela pela qualidade. Assim as vencedoras voltam sempre, mas sem sufocar as novas.
        val tiers = cand.map { o -> tierOf(c, o) }
        val quota = Selection.quota(c)
        val present = tiers.map { it.first }.toSet()
        val qSum = quota.filterKeys { it in present }.values.sum().takeIf { it > 0 } ?: 1.0
        val inTier = cand.indices.groupBy { tiers[it].first }
        val w = cand.indices.map { i ->
            val (t, m) = tiers[i]
            val idx = rec.indexOf(cand[i].optString("id"))
            val q = minOf(4.0, Math.exp(5.0 * (m - 0.5))) * (if (idx < 0) 1.3 else 1.0 + idx / 10.0)
            val tierTotal = inTier.getValue(t).sumOf { j -> val (_, mj) = tiers[j]; val ij = rec.indexOf(cand[j].optString("id")); minOf(4.0, Math.exp(5.0 * (mj - 0.5))) * (if (ij < 0) 1.3 else 1.0 + ij / 10.0) }
            (quota[t] ?: 0.0) / qSum * q / tierTotal
        }
        var r = Math.random() * w.sum(); var o = cand.last()
        for (i in cand.indices) { r -= w[i]; if (r <= 0) { o = cand[i]; break } }
        val own = o.optJSONArray("legendas")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val avoid = capAvoid(c)
        val wins = CaptionDB.winners(c, 20).map { it.optString("t") }
        val pool1 = Banks.pool(c, own) ?: (own + mine(c) + wins + CaptionDB.texts(c, "var").take(30)).filter { it.isNotBlank() }.distinct()
        // 👯 e outro tipo de legenda (tamanho/pergunta/emoji) que as do bloco
        val pool = if (blockTypes.isEmpty()) pool1 else pool1.filter { Burst.capType(it) !in blockTypes }.ifEmpty { pool1 }
        // 🧪 laboratório: a foto já foi escolhida; agora sorteia de que lado do teste este post cai
        val labPool = if (block.isEmpty()) Lab.arm(c, pool.filter { it.trim() !in avoid }) else null
        val cap = (if (labPool != null) CaptionDB.pick(c, labPool, avoid, pairBonus(c, o.optString("id"))) else null)
            ?: (if (pool.isNotEmpty()) CaptionDB.pick(c, pool, avoid, capBonus(c, o.optString("id"))) else null) ?: randomCaption(c, avoid)
        if (labPool != null && cap in labPool) Lab.picked(c, cap)
        return hold(c, o.optString("id"), cap)
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

/** 🏆 Quanto valorizar as que deram certo (fatia de cada camada no sorteio). */
object Selection {
    val MODES = linkedMapOf(
        "equilibrado" to ("⚖ Equilibrado" to mapOf("provada" to 0.45, "promessa" to 0.22, "nova" to 0.23, "resto" to 0.10)),
        "forte" to ("🏆 Forte" to mapOf("provada" to 0.62, "promessa" to 0.18, "nova" to 0.15, "resto" to 0.05)),
        "maximo" to ("💎 Máximo" to mapOf("provada" to 0.78, "promessa" to 0.12, "nova" to 0.08, "resto" to 0.02)))
    fun mode(c: Context) = Store.prefs(c).getString("sel_mode", "forte").takeIf { it in MODES } ?: "forte"
    fun setMode(c: Context, v: String) = Store.prefs(c).edit().putString("sel_mode", v).apply()
    fun quota(c: Context) = MODES.getValue(mode(c)).second

    fun summary(c: Context): String {
        val l = PostQueue.load(c).filter { it.optString("status") != "arquivada" }
        val t = l.groupingBy { Captions.tierOf(c, it).first }.eachCount()
        val q = quota(c)
        fun pc(k: String) = "${((q[k] ?: 0.0) * 100).toInt()}%"
        return "🏆 Provadas: ${t["provada"] ?: 0} (saem em ~${pc("provada")} dos posts) · 🌱 Promessas: ${t["promessa"] ?: 0} (${pc("promessa")}) · ✨ Novas: ${t["nova"] ?: 0} (${pc("nova")}) · resto: ${t["resto"] ?: 0} (${pc("resto")})\n" +
            "Provada = 2+ notas boas ou números reais altos. Mesmo provada, ela espera outras fotos antes de voltar."
    }
}

/**
 * 🗂 Bancos de legendas: além do banco principal, você monta outros (do zero, copiando, de outra modelo, colando uma lista
 * ou pedindo pra IA) e escolhe qual o sorteio usa. Cada banco aprende separado (notas, vencedoras).
 * Trocar de banco só troca o arquivo de legendas: fotos, vídeos e números continuam.
 */
object Banks {
    const val MAIN = "principal"
    private fun p(c: Context) = Store.prefs(c)
    private fun dir(c: Context) = java.io.File(c.filesDir, "bancos").apply { mkdirs() }
    private fun live(c: Context) = java.io.File(c.filesDir, "legendas_db.json")
    private fun fileOf(c: Context, id: String) = java.io.File(dir(c), "$id.json")

    fun list(c: Context): List<org.json.JSONObject> {
        val a = try { org.json.JSONArray(p(c).getString("bancos", "[]")) } catch (e: Exception) { org.json.JSONArray() }
        val l = (0 until a.length()).map { a.getJSONObject(it) }
        return if (l.any { it.optString("id") == MAIN }) l else listOf(org.json.JSONObject().put("id", MAIN).put("name", "Principal")) + l
    }
    private fun save(c: Context, l: List<org.json.JSONObject>) = p(c).edit().putString("bancos", org.json.JSONArray().apply { l.forEach { put(it) } }.toString()).apply()
    fun active(c: Context) = p(c).getString("banco_ativo", MAIN) ?: MAIN
    fun activeName(c: Context) = list(c).firstOrNull { it.optString("id") == active(c) }?.optString("name") ?: "Principal"

    /** "so" = só o banco ativo; "mais" = banco ativo + legendas presas às fotos. */
    fun mode(c: Context) = p(c).getString("banco_modo", "so") ?: "so"
    fun setMode(c: Context, v: String) = p(c).edit().putString("banco_modo", v).apply()
    /** Mistura as vencedoras do Principal num banco alternativo. */
    fun mixMain(c: Context) = p(c).getBoolean("banco_mix", false)
    fun setMixMain(c: Context, v: Boolean) = p(c).edit().putBoolean("banco_mix", v).apply()

    /** Legendas pro sorteio quando um banco alternativo está ativo (null = Principal: sorteio normal). */
    fun pool(c: Context, own: List<String>): List<String>? {
        if (active(c) == MAIN) return null
        val bank = CaptionDB.all(c).filter { it.optString("src") != "viral" }.map { it.optString("t") }.filter { it.isNotBlank() }
        if (bank.size < 3) return null // banco quase vazio: sorteio normal até ele encher
        val extra = if (mixMain(c)) mainWinners(c) else emptyList()
        return ((if (mode(c) == "mais") own else emptyList()) + bank + extra).distinct()
    }

    private fun mainWinners(c: Context): List<String> = try {
        val a = org.json.JSONArray(fileOf(c, MAIN).readText())
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optInt("good") > 0 && CaptionDB.mean(it) > 0.6 }.sortedByDescending { CaptionDB.mean(it) }.take(20).map { it.optString("t") }
    } catch (e: Exception) { emptyList() }

    /** Troca o banco ativo (guarda o atual na pasta dos bancos). */
    @Synchronized fun switchTo(c: Context, id: String) {
        val cur = active(c); if (cur == id) return
        CaptionDB.flush(c)
        val lv = live(c)
        if (lv.exists()) { val d = fileOf(c, cur); d.delete(); if (!lv.renameTo(d)) { lv.copyTo(d, true); lv.delete() } }
        val src = fileOf(c, id)
        if (src.exists()) { if (!src.renameTo(lv)) { src.copyTo(lv, true); src.delete() } } else lv.writeText("[]")
        p(c).edit().putString("banco_ativo", id).apply()
        CaptionDB.reload()
    }

    /**
     * Cria um banco. [from]: "vazio", "copia" (do ativo), "banco:<id>" (de outro banco) ou "modelo:<id>" (de outra modelo, nome trocado).
     * Não muda o ativo.
     */
    fun create(c: Context, name: String, from: String): String {
        val id = "b" + System.currentTimeMillis().toString(36)
        CaptionDB.flush(c)
        val f = fileOf(c, id)
        fun clean(a: org.json.JSONArray): org.json.JSONArray { // começa sem as notas (aprende do zero neste banco)
            val out = org.json.JSONArray()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> out.put(org.json.JSONObject().put("t", o.optString("t")).put("src", o.optString("src").ifEmpty { "ia" }).put("at", System.currentTimeMillis()).put("uses", 0).put("good", 0).put("bad", 0).put("meh", 0)) }
            return out
        }
        when {
            from == "copia" -> f.writeText(clean(org.json.JSONArray(live(c).takeIf { it.exists() }?.readText() ?: "[]")).toString())
            from.startsWith("banco:") -> { val b = from.removePrefix("banco:"); val sf = if (b == active(c)) live(c) else fileOf(c, b)
                f.writeText(clean(try { org.json.JSONArray(sf.readText()) } catch (e: Exception) { org.json.JSONArray() }).toString()) }
            from.startsWith("modelo:") -> {
                val mid = from.removePrefix("modelo:")
                val a = try { org.json.JSONArray(java.io.File(java.io.File(java.io.File(java.io.File(c.filesDir, "perfis"), mid), "files"), "legendas_db.json").readText()) } catch (e: Exception) { org.json.JSONArray() }
                val fromN = Profiles.modelName(c, mid); val toN = Profiles.modelName(c)
                val out = clean(a)
                if (fromN.isNotBlank() && toN.isNotBlank()) for (i in 0 until out.length()) out.getJSONObject(i).let { it.put("t", Names.swap(it.optString("t"), fromN, toN)) }
                f.writeText(out.toString())
            }
            else -> f.writeText("[]")
        }
        save(c, list(c) + org.json.JSONObject().put("id", id).put("name", name.ifBlank { "Banco ${list(c).size + 1}" }))
        return id
    }

    fun delete(c: Context, id: String): Boolean {
        if (id == MAIN || id == active(c)) return false
        fileOf(c, id).delete(); save(c, list(c).filter { it.optString("id") != id }); return true
    }

    fun size(c: Context, id: String): Int = if (id == active(c)) CaptionDB.size(c) else try { org.json.JSONArray(fileOf(c, id).readText()).length() } catch (e: Exception) { 0 }

    /** Cola uma lista (uma legenda por linha; linha em branco separa legendas de várias linhas) no banco ativo. */
    fun paste(c: Context, text: String): Int {
        val blocks = if (text.contains("\n\n")) text.split(Regex("\n\\s*\n")) else text.lines()
        var n = 0
        blocks.map { it.trim().replace(Regex("^\\s*(\\d+[).:-]|[-•*])\\s*"), "") }.filter { it.length >= 3 }.forEach { t ->
            if (CaptionDB.find(c, t) == null) { CaptionDB.add(c, t, "minha"); n++ }
        }
        return n
    }
}

/**
 * ✍ Posts só de texto no Threads (liga/desliga). Entram no meio dos posts com foto, a cada N posts.
 * A legenda vem do banco ativo (as criadas pra texto têm preferência) e cada uma aprende pela nota/números
 * que teve COMO TEXTO (separado de como foi com foto).
 */
object TextPosts {
    private fun p(c: Context) = Store.prefs(c)
    fun on(c: Context) = p(c).getBoolean("txt_on", false)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("txt_on", v).apply()
    fun every(c: Context) = p(c).getInt("txt_every", 4)
    fun setEvery(c: Context, v: Int) = p(c).edit().putInt("txt_every", v).apply()
    fun perDay(c: Context) = p(c).getInt("txt_day", 4)
    fun setPerDay(c: Context, v: Int) = p(c).edit().putInt("txt_day", v).apply()
    fun isText(id: String?) = id?.startsWith("texto_") == true

    private fun sends(c: Context) = SendLog.all(c).filter { it.optString("kind") == "fila" }
    fun sinceLast(c: Context): Int { val l = sends(c); val i = l.indexOfFirst { isText(it.optString("id")) }; return if (i < 0) l.size else i }
    fun today(c: Context): Int {
        val d0 = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
        return sends(c).count { isText(it.optString("id")) && it.optLong("at") >= d0 }
    }
    fun due(c: Context) = on(c) && !Burst.inBlock(c) && today(c) < perDay(c) && sinceLast(c) >= every(c)

    // ---- aprendizado: como cada legenda foi COMO TEXTO
    private fun stats(c: Context): org.json.JSONObject = try { org.json.JSONObject(p(c).getString("txt_stats", "{}")) } catch (e: Exception) { org.json.JSONObject() }
    private fun k(t: String) = t.trim().lowercase().hashCode().toString()
    fun mean(c: Context, t: String): Double? = stats(c).optJSONObject(k(t))?.let { o -> if (o.optInt("n") == 0) null else (o.optDouble("s") + 0.5) / (o.optInt("n") + 1.0) }

    /** De hora em hora: notas dos posts de texto → aprendizado. */
    fun tick(c: Context) {
        val st = stats(c); var ch = false
        SendLog.all(c).filter { isText(it.optString("id")) && !it.optBoolean("tx") }.forEach { e ->
            val y = ThreadsApi.targetFor(c, e.optLong("at")) ?: when (e.optString("nota")) { "top" -> 1.0; "good" -> 0.8; "meh" -> 0.45; "bad" -> 0.0; else -> null } ?: return@forEach
            if (System.currentTimeMillis() - e.optLong("at") < 12 * 3600_000L && e.optString("nota").isEmpty()) return@forEach
            val o = st.optJSONObject(k(e.optString("cap"))) ?: org.json.JSONObject()
            o.put("n", o.optInt("n") + 1).put("s", o.optDouble("s", 0.0) + y); st.put(k(e.optString("cap")), o); ch = true
            SendLog.mark(c, e.optLong("at"), "tx")
        }
        if (ch) p(c).edit().putString("txt_stats", st.toString()).apply()
    }

    /** Legenda pro post de texto: as feitas pra texto primeiro; as que foram bem como texto voltam mais; nunca as últimas. */
    private fun pickCap(c: Context): String? {
        val avoid = (Captions.capAvoid(c) + sends(c).filter { isText(it.optString("id")) }.take(20).map { it.optString("cap").trim() }).toSet()
        val made = CaptionDB.all(c).filter { it.optString("src") == "texto" }.map { it.optString("t") }
        val pool = (if (made.count { it.trim() !in avoid } >= 5) made else CaptionDB.all(c).filter { it.optString("src") != "viral" }.map { it.optString("t") })
            .filter { it.isNotBlank() && it.trim() !in avoid }.ifEmpty { return null }
        return CaptionDB.pick(c, pool, avoid) { t -> mean(c, t)?.let { Math.exp(4.0 * (it - 0.5)) } ?: 1.2 }
    }

    private fun pending(c: Context): Pair<String, String>? = try { p(c).getString("txt_pending", null)?.let { val o = org.json.JSONObject(it); o.optString("id") to o.optString("cap") } } catch (e: Exception) { null }

    /** No sorteio: se é a vez, devolve o post de texto (o mesmo até sair). */
    fun maybe(c: Context): Pair<String, String>? {
        if (!due(c)) return null
        pending(c)?.takeIf { (id, _) -> SendLog.all(c).none { it.optString("id") == id } }?.let { return it }
        return now(c)
    }

    fun now(c: Context): Pair<String, String>? {
        val cap = pickCap(c) ?: return null
        val id = "texto_" + System.currentTimeMillis().toString(36)
        p(c).edit().putString("txt_pending", org.json.JSONObject().put("id", id).put("cap", cap).toString()).apply()
        return id to cap
    }

    /** ✨ Cria posts feitos pra sair SÓ como texto (sem foto). */
    fun generate(c: Context, n: Int): Int {
        val wins = (all(c).sortedByDescending { mean(c, it) ?: 0.0 }.take(6) + CaptionDB.winners(c, 6).map { it.optString("t") }).distinct().take(10)
        val l = Ai.textPosts(c, wins, Captions.mine(c).take(8), n)
        var k = 0; l.forEach { if (CaptionDB.find(c, it) == null) { CaptionDB.add(c, it, "texto"); k++ } }
        return k
    }
    private fun all(c: Context) = CaptionDB.all(c).filter { it.optString("src") == "texto" }.map { it.optString("t") }

    fun summary(c: Context): String {
        if (!on(c)) return "Desligado."
        val left = maxOf(0, every(c) - sinceLast(c))
        val st = stats(c)
        val best = CaptionDB.all(c).map { it.optString("t") }.mapNotNull { t -> mean(c, t)?.let { t to it } }.sortedByDescending { it.second }.take(3)
        return "Ligado · 1 a cada ${every(c)} posts · " + (if (today(c) >= perDay(c)) "hoje já foram ${today(c)}/${perDay(c)}" else if (left == 0) "o próximo é AGORA" else "próximo daqui a $left post(s)") +
            " · ${all(c).size} textos feitos pra texto · ${st.length()} já testados" +
            (if (best.isNotEmpty()) "\nMelhores como texto: " + best.joinToString("  |  ") { "\"" + it.first.replace('\n', ' ').take(40) + "\" ${(it.second * 100).toInt()}pts" } else "")
    }
}
