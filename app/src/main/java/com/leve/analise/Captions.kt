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
        val best = CaptionDB.top(c, 10).filter { CaptionDB.mean(it) > 0.55 }.map { it.optString("t") }
        val ex = (best + refs + top).distinct().take(20)
        return """Você escreve legendas para posts de foto no Threads, em português do Brasil, no estilo de perfis que viralizam.
Vibe: ${vibe(c)}. Tamanho: ${size(c)}. Contexto: ${ctx(c).ifEmpty { "nenhum" }}.
${if (ex.isNotEmpty()) "Legendas de referência que viralizaram (copie o ritmo e o tom, nunca o texto):\n" + ex.joinToString("\n") { "- $it" } else ""}
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
    // ---------- minhas legendas (escritas na mão, reaproveitadas no sorteio) ----------
    fun mine(c: Context): List<String> = CaptionDB.texts(c, "minha")
    fun addMine(c: Context, t: String) = CaptionDB.add(c, t, "minha")
    fun removeMine(c: Context, t: String) = CaptionDB.remove(c, t)

    /**
     * Sorteio do post: uma foto aleatória (as ainda não postadas primeiro; se todas já foram, reusa)
     * e uma legenda aleatória entre as dela e as que você escreveu.
     */
    fun randomPick(c: Context): Pair<String, String>? {
        val all = PostQueue.load(c).filter { PostQueue.imageFile(c, it.optString("id")).exists() }
        if (all.isEmpty()) return null
        val fresh = all.filter { it.optString("status") != "postada" }
        val o = (if (fresh.isNotEmpty()) fresh else all).random()
        val own = o.optJSONArray("legendas")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val pool = (own + mine(c)).filter { it.isNotBlank() }.distinct()
        val cap = (if (pool.isNotEmpty()) CaptionDB.pick(c, pool) else null) ?: randomCaption(c)
        return o.optString("id") to cap
    }
    /** Legenda sem IA: sorteia das suas (como estão) ou das prontas (com o final de interação). */
    fun randomCaption(c: Context, avoid: Collection<String> = emptyList()): String {
        if (mine(c).isNotEmpty() && Math.random() < 0.7) CaptionDB.pick(c, null, avoid)?.let { return it }
        val base = Ai.OFFLINE.filter { o -> avoid.none { it.startsWith(o) } }.ifEmpty { Ai.OFFLINE }
        return withCta(c, listOf(base.random())).first()
    }

    fun randomCaptions(c: Context, n: Int): List<String> {
        val out = ArrayList<String>()
        repeat(n * 3) { if (out.size < n) { val r = randomCaption(c, out); if (r !in out) out.add(r) } }
        return out
    }
}
