package com.leve.analise

/**
 * 📖 LEITOR DE NÚMEROS das telas do Threads/Instagram (insights do post, post no feed/perfil). Puro, sem Android:
 * recebe os textos da tela (na ordem da acessibilidade) e devolve views, likes, replies, reposts, quotes, shares,
 * visits, follows… Treinado com o simulador do ReaderTrainingTest (fácil → médio → difícil → impossível).
 */
object ReadCore {
    // ⚡ todas as expressões compiladas UMA vez (antes: recompiladas a cada texto lido)
    private val R_THIN = Regex("(?<=\\d)[\\u00A0\\u202F\\u2009 ](?=\\d{3}(?!\\d))")
    private val R_BIGM = Regex("(\\d[\\d.,]*)\\s?M(?![a-zA-Z])")
    private val R_INVIS = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
    private val R_NBSP = Regex("[\\u00A0\\u202F\\u2009]")
    private val R_WS = Regex("\\s+")
    fun norm(s: String): String {
        // ⚡ caminho curto: texto comum (sem espaço especial, sem M de milhão, sem marca invisível)
        var plain = true
        for (ch in s) if (ch == '\u00A0' || ch == '\u202F' || ch == '\u2009' || ch == 'M' || ch in '\u200B'..'\u200F' || ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' || ch == '\uFEFF' || (ch == ' ' && false)) { plain = false; break }
        if (plain && s.none { it.isWhitespace() }) return s.lowercase().trim()
        var t = if (s.any { it.isDigit() }) s.replace(R_THIN, "") else s      // "1 234" (espaço fino) = 1234
        if (!plain) {
            t = t.replace(R_BIGM, "$1 mi")                                     // "1.2M" = milhões ("57m" = minutos)
            t = t.replace(R_INVIS, "")                                         // marcas invisíveis (direção do texto, espaço zero)
            t = t.replace(R_NBSP, " ")
        }
        return t.lowercase().replace(R_WS, " ").trim()
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

    private val R_NUM = Regex("^(\\d[\\d.,]*)\\s*(mil|k|mi|milh[oõ]es|milh[aã]o|mi\\.|m)?\\s*(…|\\.\\.\\.)?$", RegexOption.IGNORE_CASE)
    private val R_DEC = Regex("[.,]\\d")
    fun numOf(t: String): Int? {
        // "57m"/"5h"/"3d" são HORÁRIOS do post (57 minutos atrás), não números: só aceita mil/k/mi/milhões
        // ("1.2m" com casa decimal é milhão; "1.234.567"/"1,234,567" é número inteiro)
        val tt = t.trim()
        if (tt.isEmpty() || !tt[0].isDigit()) return null                    // ⚡ não começa com dígito: nem tenta
        if (tt.length <= 9 && tt.all { it in '0'..'9' }) return tt.toIntOrNull()   // ⚡ "57", "1234"
        val m = R_NUM.find(tt) ?: return null
        val suf = m.groupValues[2].lowercase().trimEnd('.')
        val s = m.groupValues[1].trimEnd('.', ',')
        if (suf == "m" && !R_DEC.containsMatchIn(s)) return null
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
    private val R_HIGH = Regex("high|maior|acima|mais alto|above"); private val R_LOW = Regex("low|menor|abaixo|mais baixo|below")
    private val R_X = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:x|vezes)\\s+(?:more|mais)")
    fun extras(nt: List<String>, m: MutableMap<String, Int>) {
        fun lab(t: String) = if (t.isEmpty() || t.length > 26 || t[0].isDigit()) null else LABELS.entries.firstOrNull { it.value.matches(t) }?.key
        for (i in nt.indices) {
            val k = lab(nt[i]) ?: continue
            val j = (i + 1..minOf(nt.size - 1, i + 3)).firstOrNull { VS.matches(nt[it]) } ?: continue
            if ((i + 1 until j).none { numOf(nt[it]) != null }) continue
            val v = nt[j].lowercase()
            m["vs_" + (if (k == "follows") "fol" else k)] = when { R_HIGH.containsMatchIn(v) -> 1; R_LOW.containsMatchIn(v) -> -1; else -> 0 }
        }
        nt.firstNotNullOfOrNull { if (it.length >= 6) R_X.find(it) else null }?.let { r ->
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
    private val labCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun lab(t: String): String? {
        val c = t.trim().trim('ⓘ', ':', ' ', '·').trim()
        if (c.isEmpty() || c.length > 26 || c[0].isDigit()) return null     // ⚡ rótulo nunca é longo nem começa com número
        labCache[c]?.let { return it.ifEmpty { null } }
        val k = LABELS.entries.firstOrNull { it.value.matches(c) }?.key
        if (labCache.size < 4000) labCache[c] = k ?: ""
        return k
    }
    /** Lixo entre o rótulo e o número: vazio, ⓘ, ·, e a comparação da Meta (Higher/Typical). */
    private val JUNK1 = setOf("ⓘ", "·", "•", "-", "—", "–")
    private fun skip(t: String) = t.isBlank() || t.trim().let { it in JUNK1 || (it.length <= 20 && !it[0].isDigit() && VS.matches(it)) }
    private val ONE_BEFORE = Regex("^(\\d[\\d.,]*\\s*(?:mil|k|mi|milh[oõ]es|milh[aã]o|m)?)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val ONE_AFTER = Regex("^(.+?)[,:]?\\s+(\\d[\\d.,]*\\s*(?:mil|k|mi|milh[oõ]es|milh[aã]o|m)?)$", RegexOption.IGNORE_CASE)

    /** Número de cada rótulo. Descobre se a tela mostra "Curtidas 52" ou "52 Curtidas" e usa só esse lado. */
    fun pairs(nt: List<String>, from: Int, to: Int): Map<String, Int> {
        val out = HashMap<String, Int>()
        // 2) rótulo e número em textos separados. Testa os 2 sentidos ("Curtidas 52" × "52 Curtidas"): cada número vai pra
        //    UM rótulo só, pulando lixo (ⓘ, Higher, títulos) até 3 textos, sem atravessar outro rótulo. Ganha o sentido que dá
        //    número pra mais métricas DIFERENTES (um título "Views" sem número no topo não engana); empate: o das pontas.
        // ⚡ cada texto é classificado UMA vez (rótulo? número?) e as passadas usam a tabela
        val LB = arrayOfNulls<String>(nt.size); val NV = arrayOfNulls<Int>(nt.size)
        for (i in from until to) { LB[i] = lab(nt[i]); if (LB[i] == null) NV[i] = numOf(nt[i]) }
        fun lab(i: Int) = LB[i]
        fun numOf(i: Int) = NV[i]
        val idx = (from until to).filter { LB[it] != null }
        fun assign(afterDir: Boolean): Map<String, Int> {
            val used = HashSet<Int>(); val m = LinkedHashMap<String, Int>()
            val order = if (afterDir) idx else idx.reversed()
            for (i in order) {
                val k = LB[i]!!
                var j = if (afterDir) i + 1 else i - 1; var steps = 0
                while (j in from until to && steps < 4) {
                    if (LB[j] != null) break
                    val v = NV[j]
                    if (v != null) { if (j !in used) { used += j; if (k !in m) m[k] = v }; break }
                    j += if (afterDir) 1 else -1; steps++
                }
            }
            return m
        }
        val a = assign(true); val b = assign(false)
        fun firstAlone(): Boolean { if (idx.isEmpty()) return false // pontas: o 1º rótulo tem número antes e o último não tem depois → "52 Curtidas"
            fun numNear(i: Int, d: Int): Boolean { var j = i + d; var n = 0; while (j in from until to && n < 3) { if (LB[j] != null) return false; if (NV[j] != null) return true; if (!skip(nt[j])) return false; j += d; n++ }; return false }
            return numNear(idx.first(), -1) && !numNear(idx.last(), 1)
        }
        // grade: uma fileira de rótulos e logo depois a fileira de números, na mesma ordem
        run {
            var i = from
            while (i < to) {
                val labs = ArrayList<Int>(); var j = i
                while (j < to && (LB[j] != null || (labs.isNotEmpty() && skip(nt[j])))) { if (LB[j] != null) labs += j; j++ }
                val nums = ArrayList<Int>()
                while (j < to && (NV[j] != null || (nums.isNotEmpty() && skip(nt[j])))) { if (NV[j] != null) nums += j; j++ }
                if (labs.size >= 3 && nums.size == labs.size) { labs.forEachIndexed { q, li -> out.putIfAbsent(LB[li]!!, NV[nums[q]]!!) }; i = j } else i = maxOf(i + 1, if (labs.isEmpty()) i + 1 else labs.last() + 1)
            }
        }
        val pick = when { b.size > a.size -> b; a.size > b.size -> a; firstAlone() -> b; else -> a }
        pick.forEach { (k, v) -> out.putIfAbsent(k, v) }
        // por último: rótulo e número no MESMO texto ("52 curtidas", "Curtidas: 52", "Curtir, 57")
        for (i in from until to) {
            val sp = nt[i].indexOf(' ') > 0
            if (sp && nt[i][0].isDigit()) ONE_BEFORE.find(nt[i])?.let { mm -> lab(mm.groupValues[2])?.let { k -> numOf(mm.groupValues[1])?.let { v -> out.putIfAbsent(k, v) } } }
            if (sp && nt[i].any { it.isDigit() }) ONE_AFTER.find(nt[i])?.let { mm -> lab(mm.groupValues[1])?.let { k -> numOf(mm.groupValues[2])?.let { v -> out.putIfAbsent(k, v) } } }
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
