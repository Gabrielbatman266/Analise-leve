package com.leve.analise

/**
 * 📖 LEITOR DE NÚMEROS das telas do Threads/Instagram (insights do post, post no feed/perfil). Puro, sem Android:
 * recebe os textos da tela (na ordem da acessibilidade) e devolve views, likes, replies, reposts, quotes, shares,
 * visits, follows… Treinado com o simulador do ReaderTrainingTest (fácil → médio → difícil → impossível).
 */
object ReadCore {
    fun norm(s: String): String {
        var t = s.replace(Regex("(?<=\\d)[\\u00A0\\u202F\\u2009 ](?=\\d{3}(?!\\d))"), "")   // "1 234" (espaço fino) = 1234
        t = t.replace(Regex("(\\d[\\d.,]*)\\s?M(?![a-zA-Z])"), "$1 mi")                              // "1.2M" = milhões ("57m" = minutos)
        t = t.replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), "")                       // marcas invisíveis (direção do texto, espaço zero)
        return t.replace(Regex("[\\u00A0\\u202F\\u2009]"), " ").lowercase().replace(Regex("\\s+"), " ").trim()
    }

    val LABELS = mapOf(
        "views" to Regex("^(visualiza[cç][oõ]es|views|visualizações|reprodu[cç][oõ]es|plays|contas alcan[cç]adas|accounts reached)$", RegexOption.IGNORE_CASE),
        "likes" to Regex("^(curtidas?|likes?|curtir|like)$", RegexOption.IGNORE_CASE),
        "replies" to Regex("^(respostas?|replies|reply|responder|coment[aá]rios?|comments?|comentar|comment)$", RegexOption.IGNORE_CASE),
        "saves" to Regex("^(salvamentos|salvos|saves?|salvar)$", RegexOption.IGNORE_CASE),
        "reposts" to Regex("^(reposts?|repostar|republica[cç][oõ]es)$", RegexOption.IGNORE_CASE),
        "quotes" to Regex("^(cita[cç][oõ]es|quotes?|citar)$", RegexOption.IGNORE_CASE),
        "shares" to Regex("^(compartilhamentos|shares?|compartilhar|enviar|envios|sends?)$", RegexOption.IGNORE_CASE),
        "viewers" to Regex("^(viewers|espectadores|pessoas alcan[cç]adas|contas que viram|alcance)$", RegexOption.IGNORE_CASE),
        "visits" to Regex("^(profile visits|visitas ao perfil|visitas no perfil)$", RegexOption.IGNORE_CASE),
        "follows" to Regex("^(seguidores|follows|novos seguidores|seguiram|seguimentos|follows gained)$", RegexOption.IGNORE_CASE))

    fun numOf(t: String): Int? {
        // "57m"/"5h"/"3d" são HORÁRIOS do post (57 minutos atrás), não números: só aceita mil/k/mi/milhões
        // ("1.2m" com casa decimal é milhão; "1.234.567"/"1,234,567" é número inteiro)
        val m = Regex("^(\\d[\\d.,]*)\\s*(mil|k|mi|milh[oõ]es|milh[aã]o|mi\\.|m)?\\s*(…|\\.\\.\\.)?$", RegexOption.IGNORE_CASE).find(t.trim()) ?: return null
        val suf = m.groupValues[2].lowercase().trimEnd('.')
        val s = m.groupValues[1].trimEnd('.', ',')
        if (suf == "m" && !Regex("[.,]\\d").containsMatchIn(s)) return null
        val mult = when (suf) { "mil", "k" -> 1000.0; "mi", "m", "milhões", "milhoes", "milhão", "milhao" -> 1_000_000.0; else -> 1.0 }
        val clean = if (mult > 1) s.replace(',', '.') else s.replace(".", "").replace(",", "")
        return ((clean.toDoubleOrNull() ?: return null) * mult).toInt()
    }

    val VS = Regex("^(higher|lower|typical|maior|menor|t[ií]pico|t[ií]pica|acima( do normal)?|abaixo( do normal)?|normal|mais alto|mais baixo|above|below)$", RegexOption.IGNORE_CASE)

    /**
     * Tela de insights do post: o que vai além dos rótulos.
     * - a comparação da própria Meta embaixo de cada número (Higher/Typical/Lower) → vs_views, vs_fol...
     * - o resumo da Meta AI ("16x more views") → xviews (x10)
     * - a fileira de ícones do post (❤ 57 · 💬 24 · 🔁 0 · ↗ 0), que não tem rótulo
     */
    fun extras(nt: List<String>, m: MutableMap<String, Int>) {
        fun lab(t: String) = LABELS.entries.firstOrNull { it.value.matches(t) }?.key
        for (i in nt.indices) {
            val k = lab(nt[i]) ?: continue
            val j = (i + 1..minOf(nt.size - 1, i + 3)).firstOrNull { VS.matches(nt[it]) } ?: continue
            if ((i + 1 until j).none { numOf(nt[it]) != null }) continue
            val v = nt[j].lowercase()
            m["vs_" + (if (k == "follows") "fol" else k)] = when { Regex("high|maior|acima|mais alto|above").containsMatchIn(v) -> 1; Regex("low|menor|abaixo|mais baixo|below").containsMatchIn(v) -> -1; else -> 0 }
        }
        nt.firstNotNullOfOrNull { Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:x|vezes)\\s+(?:more|mais)").find(it) }?.let { r ->
            r.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { if (it in 0.1..500.0) m["xviews"] = (it * 10).toInt() }
        }
        if (m["likes"] == null) {
            val stop = nt.indexOfFirst { it == "summary" || it == "resumo" || lab(it) != null }.let { if (it < 0) nt.size else it }
            var run = ArrayList<Int>()
            for (i in 0 until stop) { val v = numOf(nt[i]); if (v != null) run.add(v) else if (run.size in 1..2) run = ArrayList() else if (run.size >= 3) break }
            if (run.size >= 3) listOf("likes", "replies", "reposts", "shares").forEachIndexed { k, key -> run.getOrNull(k)?.let { m.putIfAbsent(key, it) } }
        }
    }

    /** Rótulo da métrica (aceita "Curtidas:", "ⓘ Views", "VIEWS"). */
    fun lab(t: String): String? { val c = t.trim().trim('ⓘ', ':', ' ', '·').trim(); return LABELS.entries.firstOrNull { it.value.matches(c) }?.key }
    /** Lixo entre o rótulo e o número: vazio, ⓘ, ·, e a comparação da Meta (Higher/Typical). */
    private fun skip(t: String) = t.isBlank() || t.trim() in setOf("ⓘ", "·", "•", "-", "—", "–") || VS.matches(t.trim())
    private val ONE_BEFORE = Regex("^(\\d[\\d.,]*\\s*(?:mil|k|mi|milh[oõ]es|milh[aã]o|m)?)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val ONE_AFTER = Regex("^(.+?)[,:]?\\s+(\\d[\\d.,]*\\s*(?:mil|k|mi|milh[oõ]es|milh[aã]o|m)?)$", RegexOption.IGNORE_CASE)

    /** Número de cada rótulo. Descobre se a tela mostra "Curtidas 52" ou "52 Curtidas" e usa só esse lado. */
    fun pairs(nt: List<String>, from: Int, to: Int): Map<String, Int> {
        val out = HashMap<String, Int>()
        // 2) rótulo e número em textos separados. Testa os 2 sentidos ("Curtidas 52" × "52 Curtidas"): cada número vai pra
        //    UM rótulo só, pulando lixo (ⓘ, Higher, títulos) até 3 textos, sem atravessar outro rótulo. Ganha o sentido que dá
        //    número pra mais métricas DIFERENTES (um título "Views" sem número no topo não engana); empate: o das pontas.
        val idx = (from until to).filter { lab(nt[it]) != null }
        fun assign(afterDir: Boolean): Map<String, Int> {
            val used = HashSet<Int>(); val m = LinkedHashMap<String, Int>()
            val order = if (afterDir) idx else idx.reversed()
            for (i in order) {
                val k = lab(nt[i])!!
                var j = if (afterDir) i + 1 else i - 1; var steps = 0
                while (j in from until to && steps < 4) {
                    if (lab(nt[j]) != null) break
                    val v = numOf(nt[j])
                    if (v != null) { if (j !in used) { used += j; if (k !in m) m[k] = v }; break }
                    j += if (afterDir) 1 else -1; steps++
                }
            }
            return m
        }
        val a = assign(true); val b = assign(false)
        fun firstAlone(): Boolean { if (idx.isEmpty()) return false // pontas: o 1º rótulo tem número antes e o último não tem depois → "52 Curtidas"
            fun numNear(i: Int, d: Int): Boolean { var j = i + d; var n = 0; while (j in from until to && n < 3) { if (lab(nt[j]) != null) return false; if (numOf(nt[j]) != null) return true; if (!skip(nt[j])) return false; j += d; n++ }; return false }
            return numNear(idx.first(), -1) && !numNear(idx.last(), 1)
        }
        // grade: uma fileira de rótulos e logo depois a fileira de números, na mesma ordem
        run {
            var i = from
            while (i < to) {
                val labs = ArrayList<Int>(); var j = i
                while (j < to && (lab(nt[j]) != null || (labs.isNotEmpty() && skip(nt[j])))) { if (lab(nt[j]) != null) labs += j; j++ }
                val nums = ArrayList<Int>()
                while (j < to && (numOf(nt[j]) != null || (nums.isNotEmpty() && skip(nt[j])))) { if (numOf(nt[j]) != null) nums += j; j++ }
                if (labs.size >= 3 && nums.size == labs.size) { labs.forEachIndexed { q, li -> out.putIfAbsent(lab(nt[li])!!, numOf(nt[nums[q]])!!) }; i = j } else i = maxOf(i + 1, if (labs.isEmpty()) i + 1 else labs.last() + 1)
            }
        }
        val pick = when { b.size > a.size -> b; a.size > b.size -> a; firstAlone() -> b; else -> a }
        pick.forEach { (k, v) -> out.putIfAbsent(k, v) }
        // por último: rótulo e número no MESMO texto ("52 curtidas", "Curtidas: 52", "Curtir, 57")
        for (i in from until to) {
            ONE_BEFORE.find(nt[i])?.let { mm -> lab(mm.groupValues[2])?.let { k -> numOf(mm.groupValues[1])?.let { v -> out.putIfAbsent(k, v) } } }
            ONE_AFTER.find(nt[i])?.let { mm -> lab(mm.groupValues[1])?.let { k -> numOf(mm.groupValues[2])?.let { v -> out.putIfAbsent(k, v) } } }
        }
        return out
    }

    /** Tela de insights do post (todos os rótulos da tela + extras). */
    fun readInsights(texts: List<String>): Map<String, Int> {
        val nt = texts.map { norm(it) }
        val m = pairs(nt, 0, nt.size).toMutableMap(); extras(nt, m)
        m["follows"]?.let { m["fol"] = it; m.remove("follows") }
        return m
    }

    /** Post no feed/perfil: os botões logo depois da legenda (índice [capIdx]); sem rótulo, 1º número = curtidas. */
    fun readPost(texts: List<String>, capIdx: Int, nextCap: Int = texts.size): Map<String, Int> {
        val nt = texts.map { norm(it) }
        val mm = pairs(nt, capIdx + 1, minOf(nt.size, capIdx + 16, nextCap)).toMutableMap()
        // sem rótulo nenhum (só a fileira de números): o 1º número é a curtida. Com rótulos, não chuta.
        val anyLabel = (capIdx + 1 until minOf(nt.size, capIdx + 16, nextCap)).any { lab(nt[it]) != null || ONE_AFTER.find(nt[it])?.let { m -> lab(m.groupValues[1]) } != null }
        if (mm["likes"] == null && !anyLabel) for (j in capIdx + 1..minOf(nt.size - 1, capIdx + 8, nextCap - 1)) { numOf(nt[j])?.let { mm["likes"] = it }; if (mm["likes"] != null) break }
        mm["follows"]?.let { mm["fol"] = it; mm.remove("follows") }
        return mm
    }
}
