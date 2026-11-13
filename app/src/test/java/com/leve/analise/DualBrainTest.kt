package com.leve.analise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🧠📱 Treino do CÉREBRO DE ANÁLISE do "Abrir com": milhares de janelas de escolha diferentes, cheias de lixo,
 * e ele tem que tocar SEMPRE no app duplo. A 1ª tela é a de verdade do seu Xiaomi (diagnóstico 13/11 13:45).
 */
class DualBrainTest {
    private val own = setOf("com.instagram.barcelona", "com.instagram.android", "com.leve.analise")
    private fun n(l: String, x: Int, y: Int, w: Int = 138, h: Int = 138, pkg: String = "com.miui.securitycore", tap: Boolean = true) =
        DualBrain.N(l, if (tap) intArrayOf(x, y, x + w, y + h) else null, pkg, y, x)

    @Test fun telaDeVerdadeDoXiaomi() {
        // opções vistas: threads @[338,2061][476,2199] | dual appthreads @[743,2061][881,2199] · pacote com.miui.securitycore
        val tela = listOf(n("threads", 338, 2061), n("dual appthreads", 743, 2061))
        val p = DualBrain.analyze(tela, ownPkgs = own)!!
        assertEquals(1, p.index); assertEquals(743, p.tap[0])
    }

    data class Case(val screen: List<DualBrain.N>, val dualTap: IntArray, val kind: String)

    /** Gera uma janela de escolha (Xiaomi, Android, Instagram…) com o duplo em algum lugar e muito lixo em volta. */
    private fun gen(r: java.util.Random): Case {
        val app = if (r.nextInt(4) == 0) "Instagram" else "Threads"
        val pkg = listOf("com.miui.securitycore", "com.android.intentresolver", "android", "com.miui.securityadd")[r.nextInt(4)]
        val dualNames = listOf("Dual app $app", "$app (Dual)", "App duplo $app", "$app (2)", "$app clone", "Clone de $app", "$app · 2º espaço", "Second space $app", "$app XSpace", "dual app${app.lowercase()}", "$app duplo", "$app 2", "Cópia de $app")
        val vertical = r.nextBoolean()       // lista (Android) ou lado a lado (Xiaomi)
        val keyword = r.nextDouble() < 0.75
        val badgeMode = !keyword && r.nextBoolean()  // sem palavra no nome, mas com selo "Dual" no botão
        val dualFirst = (keyword || badgeMode) && r.nextBoolean()   // sem palavra nem selo, o Xiaomi põe o duplo em 2º
        val out = ArrayList<DualBrain.N>()
        fun pos(i: Int) = if (vertical) (80 to 900 + i * 170) else (300 + i * 400 to 2061)
        val labels = if (keyword) (if (dualFirst) listOf(dualNames[r.nextInt(dualNames.size)], app) else listOf(app, dualNames[r.nextInt(dualNames.size)])) else listOf(app, app)
        val dualIdx = if (dualFirst) 0 else 1
        var dualTap = intArrayOf(); var badge: Triple<Int, Int, Int>? = null
        labels.forEachIndexed { i, l ->
            val (x, y) = pos(i)
            val w = if (vertical) 900 else 138; val h = if (vertical) 150 else 138
            val lab = if (r.nextInt(5) == 0) l.uppercase() else if (r.nextInt(5) == 0) l.lowercase() else l
            when (r.nextInt(if (vertical) 2 else 3)) { // na lista do Android a linha inteira é 1 botão
                0 -> out += n(lab, x, y, w, h, pkg)                                     // botão com o texto
                1 -> { out += n(lab, x, y, w, h, pkg); out += n(lab, x + 10, y + 10, 60, 60, pkg) }      // botão + texto tocável dentro (mesmo item)
                else -> { out += n(lab, x, y, w, 100, pkg); out += n(lab, x, y + 110, w, 40, pkg) }      // ícone e nome tocáveis separados
            }
            if (i == dualIdx) dualTap = intArrayOf(x, y)
            // 🏷 o Xiaomi às vezes põe o "Dual"/"2" como SELO separado dentro do botão (o nome fica só "Threads")
            if (i == dualIdx && badgeMode) badge = Triple(x, y, if (vertical) 900 else 138)
        }
        badge?.let { (x, y, w) -> out += n(listOf("Dual", "Duplo", "2", "Clone", "App duplo")[r.nextInt(5)], x + w - 40, y + 5, 35, 35, pkg).copy(tap = intArrayOf(x, y, x + w, y + (if (vertical) 150 else 138))) }
        // 🗑 lixo
        if (r.nextBoolean()) out += n("Abrir com $app", 60, 1800, 600, 80, pkg, tap = r.nextBoolean())
        if (r.nextBoolean()) { out += n("Sempre", 100, 2300, 300, 100, pkg); out += n("Só uma vez", 600, 2300, 300, 100, pkg) }
        if (r.nextBoolean()) { out += n("Always", 100, 2300, 300, 100, pkg); out += n("Just once", 600, 2300, 300, 100, pkg) }
        if (r.nextBoolean()) out += n("Lembrar minha escolha para $app", 60, 2250, 900, 60, pkg)
        if (r.nextBoolean()) out += n("Chrome", 80, 300, 900, 150, pkg)
        if (r.nextBoolean()) out += n("Cancelar", 500, 2400, 200, 100, pkg)
        if (r.nextBoolean()) out += n("$app", 0, 100, 1080, 2000, if (app == "Threads") "com.instagram.barcelona" else "com.instagram.android")  // o próprio app atrás
        if (r.nextBoolean()) out += n("🧠M", 900, 250, 80, 60, "com.leve.analise")                                                               // nosso painel
        if (r.nextBoolean()) out += n("$app: novo post", 0, 0, 1080, 80, "com.android.systemui", tap = false)                                     // notificação
        if (r.nextBoolean()) out += n(if (app == "Threads") "Instagram" else "Threads", 80, 1500, 900, 150, pkg)                                  // outro app na lista (só 1)
        out.shuffle(r)
        return Case(out, dualTap, (if (keyword) "com palavra" else if (badgeMode) "com selo" else "sem palavra") + (if (vertical) " · lista" else " · lado a lado"))
    }

    @Test fun treinoMilJanelas() {
        val r = java.util.Random(2026); val mem = DualBrain.Memory()
        val errs = HashMap<String, Int>(); var ok = 0; val n = 50_000
        repeat(n) {
            val c = gen(r)
            val p = DualBrain.analyze(c.screen, ownPkgs = own, mem = mem)
            val hit = p != null && p.tap[0] - c.dualTap[0] in -15..15 && p.tap[1] - c.dualTap[1] in -15..160
            if (hit) { ok++; DualBrain.learn(mem, p!!, p.useRealTap, true) } else errs.merge(c.kind + " → " + (p?.let { "${it.options} escolheu ${it.index + 1}º (${it.why})" } ?: "não achou"), 1, Int::plus)
        }
        val msg = "acerto ${"%.3f".format(ok * 100.0 / n)}% ($ok/$n) · erros: " + errs.entries.sortedByDescending { it.value }.take(6).joinToString(" | ") { "${it.key}=${it.value}" }
        println(msg); java.io.File("build/dual_brain.txt").writeText(msg)
        assertTrue(msg, ok == n)
    }

    @Test fun naoInventaJanela() {
        // telas sem escolha nenhuma: o Threads aberto, a tela inicial, o nosso painel → não toca em nada
        assertEquals(null, DualBrain.analyze(listOf(n("Threads", 0, 0, 1080, 2400, "com.instagram.barcelona"), n("Threads", 10, 10, 100, 100, "com.instagram.barcelona")), ownPkgs = own))
        assertEquals(null, DualBrain.analyze(listOf(n("Threads", 100, 500, pkg = "com.miui.home")), ownPkgs = own))
        assertEquals(null, DualBrain.analyze(listOf(n("Abrir com Threads", 100, 500), n("Sempre", 100, 900), n("Só uma vez", 500, 900)), ownPkgs = own))
    }

    @Test fun aprendeOJeitoDeTocar() {
        // celular onde o "clique" de acessibilidade é ignorado e o toque de verdade pega (o seu Xiaomi)
        val m = DualBrain.Memory(); val p = DualBrain.analyze(listOf(n("threads", 338, 2061), n("dual appthreads", 743, 2061)), ownPkgs = own)!!
        repeat(5) { DualBrain.learn(m, p, usedRealTap = false, worked = false); DualBrain.learn(m, p, usedRealTap = true, worked = true) }
        assertTrue(m.realTapFirst())
        // celular ao contrário: aprende a começar pelo clique
        val m2 = DualBrain.Memory(); repeat(6) { DualBrain.learn(m2, p, usedRealTap = true, worked = false); DualBrain.learn(m2, p, usedRealTap = false, worked = true) }
        assertTrue(!m2.realTapFirst())
        // salva e carrega
        assertEquals(m2.save(), DualBrain.Memory.load(m2.save()).save())
    }
}
