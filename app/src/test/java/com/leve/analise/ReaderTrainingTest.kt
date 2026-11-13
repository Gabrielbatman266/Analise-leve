package com.leve.analise

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 📖 TREINO DO LEITOR DE NÚMEROS: gera telas do Threads (insights do post e post no feed) com os números certos escondidos
 * e confere se o ReadCore lê TODOS certinho. Currículo: 🟢 fácil → 🟡 médio → 🟠 difícil → 🔴 impossível (estresse máximo).
 * Um acerto = todas as métricas da tela certas e nenhuma inventada.
 */
class ReaderTrainingTest {
    enum class Lv { FACIL, MEDIO, DIFICIL, IMPOSSIVEL, MAXIMO }
    data class Screen(val texts: List<String>, val truth: Map<String, Int>, val post: Boolean, val capIdx: Int)

    private val KEYS = listOf("views", "likes", "replies", "reposts", "quotes", "shares", "visits", "fol")
    private val EN = mapOf("views" to "Views", "likes" to "Likes", "replies" to "Replies", "reposts" to "Reposts", "quotes" to "Quotes", "shares" to "Shares", "visits" to "Profile visits", "fol" to "Follows")
    private val PT = mapOf("views" to "Visualizações", "likes" to "Curtidas", "replies" to "Respostas", "reposts" to "Reposts", "quotes" to "Citações", "shares" to "Compartilhamentos", "visits" to "Visitas ao perfil", "fol" to "Seguidores")

    /** Formata [v] como o app mostraria; devolve o texto e o valor que ele REPRESENTA (1.2K = 1200). */
    private fun fmt(v: Int, pt: Boolean, lv: Lv, r: java.util.Random): Pair<String, Int> {
        val compact = lv >= Lv.MEDIO && v >= 10_000 && r.nextBoolean()
        if (compact || v >= 1_000_000) {
            return if (v >= 1_000_000) {
                val d = Math.round(v / 100_000.0) / 10.0
                val s = if (d == Math.floor(d)) d.toInt().toString() else d.toString()
                val txt = if (pt) s.replace('.', ',') + listOf(" mi", " mi", "mi", " milhões")[r.nextInt(4)] else s + (if (s.contains('.')) listOf("M", "M", " M", "m") else listOf("M", "M", " M"))[r.nextInt(if (s.contains('.')) 4 else 3)]
                txt to (d * 1_000_000).toInt()
            } else {
                val d = Math.round(v / 100.0) / 10.0
                val s = if (d == Math.floor(d)) d.toInt().toString() else d.toString()
                val txt = if (pt) s.replace('.', ',') + listOf(" mil", "mil", " mil")[r.nextInt(3)] else s + listOf("K", "K", " K", "k")[r.nextInt(4)]
                txt to (d * 1000).toInt()
            }
        }
        if (v >= 1000 && lv >= Lv.MEDIO) {
            val sep = when { lv >= Lv.DIFICIL && r.nextInt(5) == 0 -> listOf(" ", " ", " ")[r.nextInt(3)]; pt -> "."; else -> "," }
            val s = v.toString().reversed().chunked(3).joinToString(sep).reversed()
            return s to v
        }
        return v.toString() to v
    }

    private fun value(lv: Lv, r: java.util.Random): Int = when (lv) {
        Lv.FACIL -> r.nextInt(900)
        Lv.MEDIO -> if (r.nextBoolean()) r.nextInt(1000) else r.nextInt(60_000)
        else -> when (r.nextInt(4)) { 0 -> r.nextInt(10); 1 -> r.nextInt(5000); 2 -> r.nextInt(900_000); else -> r.nextInt(30_000_000) }
    }

    private val JUNK = listOf("ⓘ", "Post insights", "Insights do post", "Interactions", "Interações", "Profile activity", "Atividade do perfil", "Overview", "Visão geral",
        "57m", "2h", "3d", "Nov 13", "13 de nov.", "12%", "+12%", "Last 7 days", "Últimos 7 dias", "@anja.chann1", "anja.chann1", "Learn more", "Saiba mais", "Back", "Voltar", "2026", "Top 10 looks",
        "Summary", "Resumo", "", " ", "·", "Translate", "Traduzir")
    private val VSW = listOf("Higher than typical", "Typical", "Lower than typical", "Maior que o normal", "Típico", "Menor que o normal", "Higher", "Lower", "Acima do normal")

    /** 💀 ESTRESSE MÁXIMO: tudo do impossível + grade (rótulos numa linha, números na outra), emoji/marcas invisíveis,
     *  rótulo+número+comparação num texto só, comentários de outras pessoas com números, feed com vários posts. */
    private fun genMax(r: java.util.Random): Screen {
        val base = gen(Lv.IMPOSSIVEL, r)
        val t = base.texts.toMutableList()
        when (r.nextInt(5)) {
            0 -> if (!base.post) { // grade: os rótulos todos e depois os números na mesma ordem
                val pt = r.nextBoolean(); val L = if (pt) PT else EN
                val keys = base.truth.keys.toList()
                val vals = keys.map { k -> fmt(base.truth[k]!!, pt, Lv.MEDIO, java.util.Random(base.truth[k]!!.toLong())).let { it.first } }
                val truth = keys.mapIndexed { i, k -> k to (ReadCore.numOf(ReadCore.norm(vals[i])) ?: base.truth[k]!!) }.toMap()
                return Screen(listOf("Post insights") + keys.map { L[it]!! } + vals + listOf("Learn more"), truth, false, -1)
            }
            1 -> { // marcas invisíveis e emoji grudados nos números
                for (i in t.indices) if (ReadCore.numOf(ReadCore.norm(t[i])) != null && r.nextBoolean()) t[i] = listOf("\u200E", "\u2066", "\u200B")[r.nextInt(3)] + t[i] + (if (r.nextBoolean()) "\u2069" else "")
            }
            2 -> if (!base.post) { // comentários de OUTRAS pessoas embaixo, com números e palavras de métrica
                t += listOf("Replies", "joao.silva", "12 likes", "que linda 😍", "maria_22", "3 replies", "Ver mais respostas")
            }
            3 -> if (base.post) { // feed com outro post depois (os números dele não são deste)
                t += listOf("outra.conta", "1h", "post de outra pessoa", "Like", "999", "Reply", "88")
                return Screen(t, base.truth, true, base.capIdx).let { it.copy(texts = t) }.also { nextCapOverride = base.texts.size }
            }
            else -> {}
        }
        return base.copy(texts = t)
    }
    private var nextCapOverride = -1

    fun gen(lv: Lv, r: java.util.Random): Screen {
        if (lv == Lv.MAXIMO) return genMax(r)
        val pt = r.nextBoolean()
        val L = if (pt) PT else EN
        val post = lv >= Lv.DIFICIL && r.nextInt(3) == 0
        val truth = LinkedHashMap<String, Int>()
        val out = ArrayList<String>()
        if (post) {
            // 📱 post no feed/perfil: legenda e depois a fileira de botões
            out += "anja.chann1"; if (r.nextBoolean()) out += listOf("2h", "57m", "3d")[r.nextInt(3)]
            val cap = listOf("Top 10 motivos pra me seguir 😍", "bom dia 2 vezes ☀", "vibe do dia: tranquila", "quem chegou agora me acha no insta", "nota 10 pra esse look?")[r.nextInt(5)]
            out += cap; val capIdx = out.size - 1
            if (r.nextInt(3) == 0) out += listOf("Translate", "Traduzir", "…mais")[r.nextInt(3)]
            val style = r.nextInt(if (lv == Lv.IMPOSSIVEL) 4 else 3)
            for (k in listOf("likes", "replies", "reposts", "shares")) {
                val (txt, v) = fmt(value(lv, r) / (if (k == "likes") 1 else 7), pt, lv, r)
                val btn = mapOf("likes" to (if (pt) "Curtir" else "Like"), "replies" to (if (pt) "Responder" else "Reply"), "reposts" to (if (pt) "Repostar" else "Repost"), "shares" to (if (pt) "Compartilhar" else "Share"))[k]!!
                val zero = v == 0 && r.nextBoolean()   // o Threads esconde o 0: só o botão
                when (style) {
                    0 -> { out += btn; if (!zero) { out += txt; truth[k] = v } }
                    1 -> { if (!zero) { out += "$btn, $txt"; truth[k] = v } else out += btn }
                    2 -> { if (!zero) { out += txt + " " + L[k]!!.lowercase(); truth[k] = v } else out += btn }
                    else -> { if (r.nextBoolean()) out += "ⓘ"; out += btn; if (!zero) { out += txt; truth[k] = v } }
                }
            }
            if (lv == Lv.IMPOSSIVEL && r.nextBoolean()) { out += "Mais 3 respostas"; out += "Top 10 motivos" }
            return Screen(out, truth, true, capIdx)
        }
        // 📊 insights do post
        out += listOf("Post insights", "Insights do post", "Insights")[r.nextInt(3)]
        if (lv >= Lv.MEDIO && r.nextBoolean()) out += "@anja.chann1"
        val keys = KEYS.filter { it == "views" || it == "likes" || r.nextInt(if (lv == Lv.FACIL) 3 else 5) > 0 }
        val before = lv >= Lv.MEDIO && r.nextInt(3) == 0       // "57 Curtidas" (número antes do rótulo)
        val oneString = lv >= Lv.MEDIO && r.nextInt(5) == 0    // "57 curtidas" no mesmo texto
        val vs = lv >= Lv.MEDIO && r.nextBoolean()
        val order = if (lv >= Lv.DIFICIL && r.nextInt(3) == 0) keys.shuffled(r) else keys
        if (lv >= Lv.DIFICIL && r.nextBoolean()) out += listOf(L["views"]!!, L["likes"]!!)[r.nextInt(2)].let { if (r.nextBoolean()) it else it.uppercase() }.let { "$it" } .let { if (lv == Lv.IMPOSSIVEL) it else "Interactions" }   // aba/título com nome de rótulo sem número
        for (k in order) {
            val (txt, v) = fmt(value(lv, r) / (if (k == "views") 1 else if (k == "likes") 8 else 40), pt, lv, r)
            truth[k] = v
            var lab = L[k]!!
            if (lv >= Lv.DIFICIL && r.nextInt(4) == 0) lab = if (r.nextBoolean()) lab.uppercase() else lab.lowercase()
            if (lv == Lv.IMPOSSIVEL && r.nextInt(5) == 0) lab = (if (pt) EN else PT)[k]!!   // mistura de idioma
            when {
                oneString -> out += if (before) "$txt ${lab.lowercase()}" else "$lab: $txt"
                before -> { out += txt; if (lv == Lv.IMPOSSIVEL && r.nextInt(4) == 0) out += "ⓘ"; out += lab }
                else -> { out += lab; if (lv >= Lv.DIFICIL && r.nextInt(4) == 0) out += listOf("ⓘ", "", "·")[r.nextInt(3)]; out += txt }
            }
            if (vs && !oneString && r.nextBoolean()) out += VSW[r.nextInt(VSW.size)]
            if (lv >= Lv.DIFICIL && r.nextInt(5) == 0) out += JUNK[r.nextInt(JUNK.size)]
        }
        if (lv >= Lv.DIFICIL && r.nextBoolean()) out += listOf("This post got 16x more views than your typical post", "Esse post teve 3,5 vezes mais visualizações", "16x more views")[r.nextInt(3)]
        if (lv == Lv.IMPOSSIVEL) repeat(r.nextInt(4)) { out.add(r.nextInt(out.size + 1).coerceAtLeast(1), JUNK[r.nextInt(JUNK.size)]) }
        return Screen(out, truth, false, -1)
    }

    private fun read(s: Screen): Map<String, Int> { val nc = nextCapOverride; nextCapOverride = -1; return if (s.post) ReadCore.readPost(s.texts, s.capIdx, if (nc > 0) nc else s.texts.size) else ReadCore.readInsights(s.texts) }

    private fun level(lv: Lv, n: Int, seed: Long, show: Int = 0): Pair<Double, String> {
        val r = java.util.Random(seed); var ok = 0; val errs = HashMap<String, Int>(); var shown = 0
        repeat(n) {
            val s = gen(lv, r)
            val got = read(s).filterKeys { it in KEYS }
            val wrong = s.truth.filter { (k, v) -> got[k] != v }.keys + got.keys.filter { it !in s.truth }
            if (wrong.isEmpty()) ok++ else {
                wrong.forEach { k -> errs.merge((if (s.post) "post " else "insights ") + k + (if (k in s.truth) (if (got[k] == null) " não leu" else " errado") else " inventado"), 1, Int::plus) }
                if (shown++ < show) println("✗ $lv ${s.texts} → leu $got · certo ${s.truth}")
            }
        }
        return ok * 100.0 / n to errs.entries.sortedByDescending { it.value }.take(8).joinToString(" | ") { "${it.key}=${it.value}" }
    }

    @Test fun curriculo() {
        val n = (System.getenv("TELAS") ?: "4000").toInt(); val show = (System.getenv("MOSTRA") ?: "0").toInt()
        val sb = StringBuilder()
        val res = Lv.values().map { lv -> val (p, e) = level(lv, n, 99L + lv.ordinal, if (lv == Lv.values().first { System.getenv("NIVEL") == it.name || System.getenv("NIVEL") == null }) show else 0)
            sb.append("${listOf("🟢", "🟡", "🟠", "🔴", "💀")[lv.ordinal]} $lv: ${"%.2f".format(p)}%" + (if (e.isNotEmpty()) " · erros: $e" else "") + "\n"); lv to p }
        println(sb); java.io.File("build/leitor.txt").writeText(sb.toString())
        val m = res.toMap()
        assertTrue(sb.toString(), m[Lv.FACIL]!! >= 99.0 && m[Lv.MEDIO]!! >= 95.0 && m[Lv.DIFICIL]!! >= 85.0 && m[Lv.IMPOSSIVEL]!! >= 90.0 && m[Lv.MAXIMO]!! >= 70.0)
    }
}
