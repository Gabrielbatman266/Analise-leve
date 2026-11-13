package com.leve.analise

/**
 * 🧠📱 CÉREBRO DE ANÁLISE do "Abrir com" (app duplo). Faz uma coisa só: olha a tela, acha a janela de escolha
 * (Xiaomi "Apps duplos", "Abrir com" do Android…), decide qual botão é o app DUPLO e onde tocar. Não depende do Android
 * (recebe a tela como lista de [N]) — por isso dá pra treinar com milhares de telas no DualBrainTest.
 *
 * Como decide (em ordem):
 *  1. só olha janelas que NÃO são o próprio app (o Threads/Instagram aberto não é a escolha);
 *  2. opções = textos com o nome do app que têm um botão tocável; título ("Abrir com Threads"), "Sempre/Só uma vez",
 *     "Lembrar escolha" e afins NÃO são opções; opções repetidas (mesmo botão) viram uma só;
 *  3. pontua cada opção: palavra de duplo (dual, duplo, clone, 2º espaço, xspace, cópia, "(2)", "2") ganha; a outra perde;
 *  4. sem palavra nenhuma: usa o que APRENDEU nessa mesma janela (qual posição deu certo); sem aprendizado: a 2ª (Xiaomi);
 *  5. o jeito de tocar (toque de verdade × clique de acessibilidade) também é aprendido: começa pelo que funcionou.
 */
object DualBrain {
    /** Um elemento da tela: texto (ou descrição), retângulo do BOTÃO tocável (null = não tocável), pacote da janela. */
    data class N(val label: String, val tap: IntArray?, val pkg: String, val top: Int, val left: Int)

    data class Pick(val tap: IntArray, val index: Int, val options: List<String>, val why: String, val signature: String, val useRealTap: Boolean, val byWord: Boolean = false)

    private val DUAL = Regex("dual|duplo|clone|clonad|segund|2º|2o espa|second space|xspace|copia|cópia|\\(2\\)|\\b2\\b|parallel|paralel|gemeo|gêmeo")
    private val NOT_OPTION = Regex("abrir com|open with|complete action|concluir a[cç][aã]o|usar sempre|sempre|always|s[oó] uma vez|just once|only once|lembrar|remember|escolha|choose|cancel|cancelar|selecionar app|select app|qual app|which app")

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase().trim(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Estado aprendido (texto, vai pras preferências): "assinatura=posição;…|toque=ok,falha|clique=ok,falha". */
    class Memory(var byScreen: MutableMap<String, Int> = HashMap(), var tapOk: Int = 0, var tapFail: Int = 0, var clickOk: Int = 0, var clickFail: Int = 0) {
        fun save() = byScreen.entries.joinToString(";") { "${it.key}=${it.value}" } + "|$tapOk,$tapFail|$clickOk,$clickFail"
        fun realTapFirst(): Boolean {
            // nota de cada jeito (com um empurrão pro toque de verdade, que no Xiaomi é o que pega)
            val t = (tapOk + 1.5) / (tapOk + tapFail + 2.0); val c = (clickOk + 1.0) / (clickOk + clickFail + 2.0)
            return t >= c
        }
        companion object {
            fun load(s: String?): Memory {
                if (s.isNullOrBlank()) return Memory()
                return try {
                    val p = s.split("|")
                    val m = p[0].split(";").filter { it.contains("=") }.associate { it.substringBefore("=") to it.substringAfter("=").toInt() }.toMutableMap()
                    val t = p.getOrNull(1)?.split(",")?.map { it.toInt() } ?: listOf(0, 0); val c = p.getOrNull(2)?.split(",")?.map { it.toInt() } ?: listOf(0, 0)
                    Memory(m, t[0], t[1], c[0], c[1])
                } catch (e: Exception) { Memory() }
            }
        }
    }

    /**
     * Analisa a tela. [appWords]: nomes do app que está sendo aberto (ex.: "threads"). [ownPkgs]: pacotes que NÃO são a janela
     * de escolha (o próprio app, o Post Leve). Devolve onde tocar, ou null se não há janela de escolha na tela.
     */
    fun analyze(screen: List<N>, appWords: List<String> = listOf("threads", "instagram"), ownPkgs: Set<String> = emptySet(), mem: Memory = Memory(), wantDual: Boolean = true): Pick? {
        val cand = screen.filter { it.pkg !in ownPkgs && it.tap != null }.mapNotNull { n ->
            val l = norm(n.label)
            if (l.isEmpty() || l.length > 60 || NOT_OPTION.containsMatchIn(l)) return@mapNotNull null
            val app = appWords.firstOrNull { l.contains(it) } ?: return@mapNotNull null
            Triple(n, l, app)
        }
        // o app com mais opções na tela é o que está sendo escolhido (Threads OU Instagram, não mistura)
        val app = cand.groupingBy { it.third }.eachCount().maxByOrNull { it.value }?.key ?: return null
        // mesmo botão (ou ícone e texto tocáveis separados, um em cima do outro) = 1 opção; junta os textos
        fun cx(t: IntArray) = (t[0] + t[2]) / 2
        fun cy(t: IntArray) = (t[1] + t[3]) / 2
        val groups = ArrayList<MutableList<Triple<N, String, String>>>()
        for (c in cand.filter { it.third == app }) {
            val t = c.first.tap!!
            // mesmo item = os botões se sobrepõem, ou é ícone em cima e nome embaixo (estreitos, colados, mesma coluna)
            fun same(u: IntArray): Boolean {
                val overlap = u[0] < t[2] && t[0] < u[2] && u[1] < t[3] && t[1] < u[3]
                val narrow = (u[2] - u[0]) < 400 && (t[2] - t[0]) < 400
                val gapY = maxOf(u[1], t[1]) - minOf(u[3], t[3])
                return overlap || (narrow && Math.abs(cx(u) - cx(t)) < 50 && gapY in -5..25)
            }
            val g = groups.firstOrNull { gr -> gr.any { o -> same(o.first.tap!!) } }
            if (g != null) g += c else groups += mutableListOf(c)
        }
        // selos sem o nome do app dentro do mesmo botão ("Dual", "2", ícone com descrição "Clone") também contam pra opção
        val extras = screen.filter { it.pkg !in ownPkgs && it.tap != null && norm(it.label).let { l -> l.isNotEmpty() && l.length <= 30 && appWords.none { w -> l.contains(w) } && !NOT_OPTION.containsMatchIn(l) } }
        val opts = groups.map { g ->
            val big = g.maxByOrNull { val t = it.first.tap!!; (t[2] - t[0]) * (t[3] - t[1]) }!!
            // o selo é do item se o botão dele cai em cima de alguma parte do item (e o centro dele está dentro)
            val badge = extras.filter { e -> val u = e.tap!!; val ex = cx(u); val ey = cy(u); g.any { o -> val b = o.first.tap!!; ex in b[0]..b[2] && ey in b[1]..b[3] } }.joinToString(" ") { norm(it.label) }
            Triple(big.first, (g.joinToString(" ") { it.second } + " " + badge).trim(), big.third)
        }.sortedWith(compareBy({ cy(it.first.tap!!) / 120 }, { cx(it.first.tap!!) }))   // leitura: linha, depois coluna
        if (opts.size < 2) return null
        val sig = app + ":" + opts.size + ":" + opts.first().first.pkg.substringAfterLast('.')
        val labels = opts.map { it.second }
        val scores = opts.map { (_, l, _) ->
            var s = 0.0
            if (DUAL.containsMatchIn(l.replace(app, " "))) s += 10.0
            if (l.split(" ").all { it == app }) s -= 1.0                              // "threads" puro costuma ser o original
            s
        }
        val best = scores.maxOrNull()!!
        val idx: Int; val why: String
        if (best >= 10.0 && scores.count { it == best } == 1) {
            idx = if (wantDual) scores.indexOf(best) else scores.indexOfFirst { it < 10.0 }.let { if (it < 0) 0 else it }
            why = if (wantDual) "palavra de duplo em \"${labels[scores.indexOf(best)]}\"" else "o que NÃO tem palavra de duplo"
        } else {
            val learned = mem.byScreen[sig]
            val dualPos = learned ?: 1
            idx = (if (wantDual) dualPos else 1 - dualPos).coerceIn(0, opts.size - 1)
            why = if (learned != null) "aprendido nessa janela: duplo é o ${learned + 1}º" else "sem palavra de duplo: o 2º (padrão do Xiaomi)"
        }
        return Pick(opts[idx].first.tap!!, idx, labels, why, sig, mem.realTapFirst(), byWord = best >= 10.0 && scores.count { it == best } == 1)
    }

    /** Depois do toque: a janela sumiu e o app abriu → aprende a posição e o jeito; senão anota a falha do jeito. */
    fun learn(mem: Memory, p: Pick, usedRealTap: Boolean, worked: Boolean) {
        // a posição só se aprende nas janelas SEM palavra de duplo (com palavra ele já sabe; misturar ensinaria errado)
        if (worked) { if (!p.byWord) mem.byScreen[p.signature] = p.index; if (usedRealTap) mem.tapOk++ else mem.clickOk++ }
        else { if (usedRealTap) mem.tapFail++ else mem.clickFail++ }
        // não deixa um jeito "morrer" pra sempre: as contas vão encolhendo
        if (mem.tapOk + mem.tapFail + mem.clickOk + mem.clickFail > 60) { mem.tapOk /= 2; mem.tapFail /= 2; mem.clickOk /= 2; mem.clickFail /= 2 }
    }
}
