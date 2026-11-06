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

    fun refs(c: Context): List<String> = try {
        val a = JSONArray(p(c).getString("refs", "[]"))
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    } catch (e: Exception) { emptyList() }

    private fun saveRefs(c: Context, l: List<String>) {
        val a = JSONArray(); l.take(60).forEach { a.put(it) }
        p(c).edit().putString("refs", a.toString()).apply()
    }

    fun addRef(c: Context, t: String) = saveRefs(c, listOf(t) + refs(c).filter { it != t })
    fun removeRef(c: Context, t: String) = saveRefs(c, refs(c).filter { it != t })

    /** Leva as legendas que mais engajaram nos prints analisados pro banco viral. */
    fun pullFromAnalysis(c: Context): Int {
        val top = Store.sorted(c).map { it.optString("legenda").trim() }.filter { it.length >= 3 }.take(15)
        val cur = refs(c)
        val novos = top.filter { it !in cur }
        saveRefs(c, novos + cur)
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
        val ex = refs + top
        return """Você escreve legendas para posts de foto no Threads, em português do Brasil, no estilo de perfis que viralizam.
Vibe: ${vibe(c)}. Tamanho: ${size(c)}. Contexto: ${ctx(c).ifEmpty { "nenhum" }}.
${if (ex.isNotEmpty()) "Legendas de referência que viralizaram (copie o ritmo e o tom, nunca o texto):\n" + ex.joinToString("\n") { "- $it" } else ""}
${if (pats.isNotEmpty()) "Padrões que mais funcionaram (siga):\n" + pats.joinToString("\n") { "- $it" } else ""}
Use a foto anexada para deixar as legendas específicas.
Não termine com pergunta ou chamada pra comentar: o app adiciona esse final depois.
Regras: naturais, em minúsculas quando combinar, no máximo 1 emoji, sem hashtags, nada explícito, nada que finja ser outra pessoa real.
Responda só com JSON: {"legendas":["...","...","...","...","..."]}"""
    }

    /** Marca todas as fotos ainda não postadas pra ganhar legendas novas com o estilo atual. */
    fun regenerateAll(c: Context): Int {
        val ids = PostQueue.load(c).filter { it.optString("status") == "pronta" }.map { it.optString("id") }
        ids.forEach { id -> PostQueue.update(c, id) { it.put("status", "gerando") } }
        return ids.size
    }
    // ---------- minhas legendas (escritas na mão, reaproveitadas no sorteio) ----------
    fun mine(c: Context): List<String> = try {
        val a = JSONArray(p(c).getString("mine", "[]"))
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    } catch (e: Exception) { emptyList() }

    private fun saveMine(c: Context, l: List<String>) {
        val a = JSONArray(); l.take(200).forEach { a.put(it) }
        p(c).edit().putString("mine", a.toString()).apply()
    }
    fun addMine(c: Context, t: String) { if (t.isNotBlank()) saveMine(c, listOf(t.trim()) + mine(c).filter { it != t.trim() }) }
    fun removeMine(c: Context, t: String) = saveMine(c, mine(c).filter { it != t })

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
        val cap = pool.randomOrNull() ?: withCta(c, Ai.OFFLINE.shuffled().take(1)).first()
        return o.optString("id") to cap
    }
}
