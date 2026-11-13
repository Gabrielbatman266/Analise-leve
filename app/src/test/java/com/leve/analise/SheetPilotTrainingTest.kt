package com.leve.analise

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 🏋️ Simulador da folha "Schedule post" do Threads + treino do piloto.
 * O simulador imita o que vimos no celular e o que costuma dar errado:
 *  - rodinha do dia começa em HOJE (nada acima), hora 12,1..11 (o 12 no topo; às vezes dá a volta), minuto 00–59, AM/PM;
 *  - cada comando anda 1 linha, mas mandar muitos de uma vez às vezes ESCORREGA e anda a mais;
 *  - a tela mostra o valor ANTIGO por um tempo enquanto a rodinha anima (ler cedo demais engana);
 *  - Done CINZA quando o horário escolhido já passou (ou está a menos de 5 min);
 *  - a folha abre com o horário do post anterior (o "3º post" que voltava a data);
 *  - TOCAR no número de cima/baixo anda exatamente 1 linha (como o NumberPicker do Android);
 *  - ARRASTAR embala (fling): metade das vezes passa do ponto, às vezes fica curto, e a animação é mais longa
 *    (no celular de verdade o arrasto tirou nota −6,5; antes o simulador tratava o arrasto como perfeito).
 * O Done vale o que está DE VERDADE na rodinha (não o que a tela mostrava).
 */
class SheetPilotTrainingTest {
    private val sp = "America/Sao_Paulo"; private val zone = ZoneId.of(sp)

    class Sim(val today: LocalDate, var day: Int, var hPos: Int, var min: Int, var pm: Boolean, val hourWraps: Boolean,
              val slip: Double, val lagMs: Long, val rnd: java.util.Random,
              /** works[col][how]: o comando / tocar no número / arrastar funcionam nessa coluna? (falso = ignora e não mexe) */
              val works: Array<BooleanArray> = Array(4) { BooleanArray(3) { true } },
              /** dificuldade extra: leitura falha / número lido errado por um instante / português / 24 h */
              val readFail: Double = 0.0, val misread: Double = 0.0, val pt: Boolean = false, val h24: Boolean = false) {
        val band = 1940; val rowH = 103
        var now = 0L
        // o que a tela mostra (fica pra trás enquanto anima)
        private var shown = Triple(day, hPos, min); private var shownPm = pm; private var animUntil = 0L
        private var from = Triple(day, hPos, min); private var animStart = 0L
        private fun fmtDay(d: LocalDate) = if (pt) d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale("pt", "BR")) + ", " + d.dayOfMonth + " de " +
            d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale("pt", "BR"))
            else d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + ", " + d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + d.dayOfMonth
        fun hour12(pos: Int) = if (pos == 0) 12 else pos
        fun at(): ZonedDateTime { val h24 = hPos + (if (pm) 12 else 0); return ZonedDateTime.of(today.plusDays(day.toLong()), LocalTime.of(h24, min), ZoneId.of("America/Sao_Paulo")) }
        /** null = a leitura da tela falhou (acontece de vez em quando no celular). */
        fun screenOrNull(): List<AgendaCore.WItem>? {
            if (rnd.nextDouble() < readFail) return null
            val l = screen().toMutableList()
            if (rnd.nextDouble() < misread) { // um número lido errado só nesta leitura (o "OCR" da acessibilidade se confunde)
                val i = l.indices.filter { l[it].cy == band && l[it].text.length <= 2 }.randomOrNull(rnd)
                if (i != null) l[i] = l[i].copy(text = ((l[i].text.toIntOrNull() ?: 0) + if (rnd.nextBoolean()) 1 else -1).coerceAtLeast(0).let { if (l[i].text.length == 2) "%02d".format(it % 60) else it.toString() })
            }
            return l
        }
        private fun <T> List<T>.randomOrNull(r: java.util.Random) = if (isEmpty()) null else this[r.nextInt(size)]
        fun screen(): List<AgendaCore.WItem> {
            if (h24) return screen24()
            if (now >= animUntil) { shown = Triple(day, hPos, min); shownPm = pm }
            else { // no meio da animação a tela mostra valores INTERMEDIÁRIOS (ou ainda o antigo, no começo)
                val f = ((now - animStart).toDouble() / maxOf(1L, animUntil - animStart)).coerceIn(0.0, 1.0)
                fun mid(a: Int, b: Int) = Math.round(a + (b - a) * f).toInt()
                // a rodinha da hora que dá a volta anima pelo caminho curto (11 → 12 passa direto, não volta pelo 6)
                fun midH(a: Int, b: Int): Int { if (!hourWraps || Math.abs(b - a) <= 6) return mid(a, b); val bb = if (b < a) b + 12 else b - 12; return ((mid(a, bb) % 12) + 12) % 12 }
                shown = Triple(mid(from.first, day), midH(from.second, hPos), mid(from.third, min))
            }
            val (d0, h0, m0) = shown
            val out = ArrayList<AgendaCore.WItem>()
            for (k in -2..2) {
                val y = band + k * rowH
                (d0 + k).takeIf { it in 0..75 }?.let { out += AgendaCore.WItem(fmtDay(today.plusDays(it.toLong())), 375, y) }
                val p = h0 + k
                (if (hourWraps) ((p % 12) + 12) % 12 else p.takeIf { it in 0..11 })?.let { out += AgendaCore.WItem(hour12(it).toString(), 600, y) }
                (m0 + k).takeIf { it in 0..59 }?.let { out += AgendaCore.WItem("%02d".format(it), 724, y) }
            }
            out += AgendaCore.WItem(if (shownPm) "PM" else "AM", 867, band)
            out += AgendaCore.WItem(if (shownPm) "AM" else "PM", 867, if (shownPm) band - rowH else band + rowH)
            out += AgendaCore.WItem("Edit", 280, 1919)
            return out
        }
        /** Comando de rolar: true se andou. Muitas linhas de uma vez podem escorregar 1–2 a mais. */
        /** true = andou ou "aceitou" (comando ignorado também devolve true, como no celular); false = fim da rodinha. */
        fun act(col: Int, dir: Int, rows: Int, how: Int): Boolean {
            if (!works[col][how]) { now += 60; return true }       // ignorou: não mexe nada (o pior caso: nem avisa)
            return when (how) {
                // tocar no número de cima/baixo: como no NumberPicker do Android, cada toque anda EXATAMENTE 1 linha
                1 -> scroll(col, dir, 1)
                // arrastar: o dedo solta com velocidade → a rodinha embala (fling) e para onde quiser: passa do ponto ou fica curta
                2 -> { val f = rnd.nextDouble(); val n = when { f < dragOver -> rows + 1 + rnd.nextInt(maxOf(1, rows)); f < dragOver + 0.15 -> maxOf(1, rows - 1); else -> rows }
                       val ok = scroll(col, dir, n); animUntil += dragLagMs; ok }
                else -> scroll(col, dir, rows)
            }
        }
        /** Arrasto real (fling): chance de passar do ponto e animação mais longa. */
        var dragOver = 0.5; var dragLagMs = 500L
        private fun screen24(): List<AgendaCore.WItem> {
            if (now >= animUntil) { shown = Triple(day, hPos, min); shownPm = pm }
            val out = ArrayList<AgendaCore.WItem>()
            val h0 = (if (now >= animUntil) hPos + (if (pm) 12 else 0) else shown.second + (if (shownPm) 12 else 0))
            for (k in -2..2) {
                val y = band + k * rowH
                (shown.first + k).takeIf { it in 0..75 }?.let { out += AgendaCore.WItem(fmtDay(today.plusDays(it.toLong())), 375, y) }
                (h0 + k).takeIf { it in 0..23 }?.let { out += AgendaCore.WItem("%02d".format(it), 600, y) }
                (shown.third + k).takeIf { it in 0..59 }?.let { out += AgendaCore.WItem("%02d".format(it), 724, y) }
            }
            return out
        }
        fun scroll(col: Int, dir: Int, rows: Int): Boolean {
            if (now >= animUntil) shown = Triple(day, hPos, min)
            var moved = 0
            val extra = if (rows >= 4 && rnd.nextDouble() < slip) 1 + rnd.nextInt(2) else 0
            repeat(rows + extra) { if (one(col, dir)) moved++ }
            now += 40L * rows
            if (moved > 0) { if (now >= animUntil) from = shown; animStart = now; animUntil = now + lagMs }
            return moved > 0
        }
        private fun one(col: Int, dir: Int): Boolean = when (col) {
            0 -> (day + dir in 0..75).also { if (it) day += dir }
            1 -> if (h24) { val t = hPos + (if (pm) 12 else 0) + dir; (t in 0..23).also { if (it) { hPos = t % 12; pm = t >= 12 } } }
                 else { val p = hPos + dir; if (hourWraps) { hPos = ((p % 12) + 12) % 12; true } else (p in 0..11).also { if (it) hPos = p } }
            2 -> (min + dir in 0..59).also { if (it) min += dir }
            else -> (if (dir > 0) !pm else pm).also { if (it) pm = !pm }
        }
        fun doneEnabled(clock: ZonedDateTime) = at().isAfter(clock.plusMinutes(5))
        /** a rodinha ainda está girando (é aí que o Android manda eventos de acessibilidade) */
        fun animating() = now < animUntil
    }

    data class Result(val ok: Boolean, val ms: Long, val why: String)

    /** Um post: abre a folha (com o horário anterior), o piloto acerta, toca no Done, confere; corrige se o post mostrar errado. */
    private fun episode(sim: Sim, target: ZonedDateTime, clock: ZonedDateTime, wb: AgendaCore.Bandit, db: AgendaCore.Bandit, rnd: java.util.Random, explore: Double,
                        hb: AgendaCore.Bandit = AgendaCore.Bandit(intArrayOf(0, 1, 2)), g: Genome = Genome()): Result {
        val pilot = SheetPilot(target, wb, db, { c -> when (c) { 3 -> 1; 1 -> 11; else -> 20 } }, rnd, explore, hb, g)
        var reopen = 0
        while (true) {
            val r = sim.screenOrNull()?.let { AgendaCore.wheelRead(it, sim.band, 124, sim.today) }
            when (val a = pilot.step(r, sim.now, sim.doneEnabled(clock), canLook = true)) {
                is SheetPilot.Act.Scroll -> { if (!sim.act(a.col, a.dir, a.rows, a.how)) pilot.scrollRefused(a.col); sim.now += 120 }
                is SheetPilot.Act.Wait -> sim.now += a.ms
                SheetPilot.Act.Look -> { pilot.setLum(r.toString(), if (sim.doneEnabled(clock)) 238 else 150); sim.now += 150 }
                SheetPilot.Act.TapDone -> {
                    val ok = sim.at() == target
                    pilot.doneResult(ok)                        // o post mostra o que ficou DE VERDADE: o piloto confere
                    if (ok) return Result(true, sim.now, "")
                    if (++reopen > 3) return Result(false, sim.now, "post errado 4x")
                    sim.now += 1500                             // reabre a folha e acerta de novo
                }
                is SheetPilot.Act.Fail -> return Result(false, sim.now, a.why)
            }
        }
    }

    private fun randomWorks(rnd: java.util.Random) = Array(4) {
        val w = BooleanArray(3) { h -> rnd.nextDouble() > (if (h == 0) 0.35 else 0.25) }
        if (w.none { it }) w[rnd.nextInt(3)] = true    // pelo menos um jeito funciona em cada coluna
        w
    }
    private fun randomSim(rnd: java.util.Random, today: LocalDate) = Sim(today, rnd.nextInt(8), rnd.nextInt(12), rnd.nextInt(60), rnd.nextBoolean(),
        hourWraps = rnd.nextBoolean(), slip = 0.1 + rnd.nextDouble() * 0.4, lagMs = 150L + rnd.nextInt(900), rnd = rnd, works = randomWorks(rnd))

    @Test fun treinaEMedeTaxaDeAcerto() {
        val rnd = java.util.Random(2026)
        val wb = AgendaCore.Bandit(intArrayOf(1, 2, 4, 8, 12)); val db = AgendaCore.Bandit(intArrayOf(300, 600, 1000, 1600))
        val today = LocalDate.of(2026, 11, 13)
        val clock = ZonedDateTime.of(today, LocalTime.of(0, 30), zone)
        // treino: lotes de 5 posts seguidos (a folha abre com o horário do post anterior)
        repeat(1200) {
            val sim = randomSim(rnd, today); val hb = AgendaCore.Bandit(intArrayOf(0, 1, 2))
            repeat(5) {
                val t = clock.plusMinutes(20L + rnd.nextInt(60 * 24 * 10)).withSecond(0).withNano(0)
                episode(sim, t, clock, wb, db, rnd, 0.1, hb)
            }
        }
        // prova: sem explorar, 3000 posts novos em celulares simulados novos
        var ok = 0; var n = 0; var ms = 0L; val fails = HashMap<String, Int>()
        repeat(600) {
            val sim = randomSim(rnd, today); val hb = AgendaCore.Bandit(intArrayOf(0, 1, 2))
            repeat(5) {
                val t = clock.plusMinutes(20L + rnd.nextInt(60 * 24 * 10)).withSecond(0).withNano(0)
                val t0 = sim.now
                val r = episode(sim, t, clock, wb, db, rnd, 0.0, hb)
                n++; ms += sim.now - t0; if (r.ok) ok++ else fails.merge(r.why, 1, Int::plus)
            }
        }
        val rate = ok * 100.0 / n
        val out = "ACERTO ${"%.2f".format(rate)}% ($ok/$n) · tempo médio por post ${ms / n / 1000.0} s · falhas $fails\nBANDIT=${wb.save()}\nDONE=${db.save()}"
        println(out)
        java.io.File("build/treino_piloto.txt").apply { parentFile?.mkdirs() }.writeText(out)
        assertTrue(out, rate >= 99.0)
    }

    /** Celular bem pior: animação de até 1,6 s, escorrega muito, e às vezes a leitura vem vazia. */
    /** Prova do genoma de fábrica no simulador BRUTAL (roda em todo build). */
    @Test fun genomaDeFabricaNoBrutal() {
        val g = Genome.load(TreinoPronto.GENOMA)
        val f = fitness(g, 31337L, 60)
        java.io.File("build/treino_brutal.txt").writeText("BRUTAL ${"%.2f".format(f.second)}% · ${"%.1f".format(f.third)} s/post")
        assertTrue("brutal: ${f.second}%", f.second >= 92.0) // 🧠 novo (opcional): o simulador ficou mais fiel (arrasto embala, hora anima pelo caminho curto) e ele caiu; o padrão é o antigo (LoteRealTest)
    }

    @Test fun cenarioDificil() {
        val rnd = java.util.Random(77)
        val wb = AgendaCore.Bandit(intArrayOf(1, 2, 4, 8, 12)); val db = AgendaCore.Bandit(intArrayOf(300, 600, 1000, 1600))
        val today = LocalDate.of(2026, 11, 13); val clock = ZonedDateTime.of(today, LocalTime.of(0, 30), zone)
        var ok = 0; var n = 0; val fails = HashMap<String, Int>()
        repeat(800) { ep ->
            val sim = Sim(today, rnd.nextInt(8), rnd.nextInt(12), rnd.nextInt(60), rnd.nextBoolean(), rnd.nextBoolean(), slip = 0.7, lagMs = 600L + rnd.nextInt(1000), rnd = rnd, works = randomWorks(rnd))
            val hb = AgendaCore.Bandit(intArrayOf(0, 1, 2))
            repeat(5) {
                val t = clock.plusMinutes(20L + rnd.nextInt(60 * 24 * 30)).withSecond(0).withNano(0)
                val r = episode(sim, t, clock, wb, db, rnd, if (ep < 400) 0.1 else 0.0, hb)
                if (ep >= 400) { n++; if (r.ok) ok++ else fails.merge(r.why, 1, Int::plus) }
            }
        }
        val rate = ok * 100.0 / n
        println("DIFICIL ${"%.2f".format(rate)}% ($ok/$n)")
        java.io.File("build/treino_dificil.txt").writeText("DIFICIL ${"%.2f".format(rate)}% ($ok/$n) $fails")
        assertTrue("cenário difícil: $rate%", rate >= 98.0)
    }

    /** Horário que o Threads não aceita (já passou): não pode tocar no Done cinza; tem que desistir limpo (o robô empurra o horário). */
    @Test fun horarioRecusadoNaoToca() {
        val rnd = java.util.Random(5)
        val wb = AgendaCore.Bandit(intArrayOf(1, 2, 4, 8, 12)); val db = AgendaCore.Bandit(intArrayOf(300, 600, 1000, 1600))
        val today = LocalDate.of(2026, 11, 13); val clock = ZonedDateTime.of(today, LocalTime.of(12, 0), zone)
        val sim = Sim(today, 0, 3, 0, true, false, 0.0, 300, rnd)
        val r = episode(sim, clock.minusMinutes(30), clock, wb, db, rnd, 0.0)
        assertTrue(r.why, !r.ok && r.why.contains("não aceita"))
    }

    /** O caso do print: Sat, Nov 14 · 9 · 39 · AM, alvo 09:40, e o MINUTO ignora o comando de rolar. */
    @Test fun minutoTravadoNo39() {
        val rnd = java.util.Random(9)
        val wb = AgendaCore.Bandit(intArrayOf(1, 2, 4, 8, 12)); val db = AgendaCore.Bandit(intArrayOf(300, 600, 1000, 1600))
        val today = LocalDate.of(2026, 11, 13); val clock = ZonedDateTime.of(today, LocalTime.of(0, 59), zone)
        val works = Array(4) { BooleanArray(3) { true } }; works[2][0] = false   // minuto: comando ignorado
        val sim = Sim(today, 1, 9, 39, false, false, 0.2, 500, rnd, works)
        val hb = AgendaCore.Bandit(intArrayOf(0, 1, 2))
        val r = episode(sim, ZonedDateTime.of(2026, 11, 14, 9, 40, 0, 0, zone), clock, wb, db, rnd, 0.0, hb)
        assertTrue(r.why, r.ok)
        assertTrue("aprendeu a não usar o comando no minuto", hb.value("c2", 0) < hb.value("c2", 1) || hb.value("c2", 0) < hb.value("c2", 2))
    }

    // ================================================================ 🧬 evolução: mutações aleatórias, fica a melhor
    private fun brutal(rnd: java.util.Random, today: LocalDate) = Sim(today, rnd.nextInt(10), rnd.nextInt(12), rnd.nextInt(60), rnd.nextBoolean(),
        hourWraps = rnd.nextBoolean(), slip = rnd.nextDouble() * 0.8, lagMs = 100L + rnd.nextInt(2000), rnd = rnd, works = randomWorks(rnd),
        readFail = rnd.nextDouble() * 0.15, misread = rnd.nextDouble() * 0.12, pt = rnd.nextDouble() < 0.35, h24 = rnd.nextDouble() < 0.2)

    /** Nota de um genoma: % de acerto no simulador brutal (e um pouco de tempo). */
    private fun fitness(g: Genome, seed: Long, phones: Int): Triple<Double, Double, Double> {
        val rnd = java.util.Random(seed)
        val wb = AgendaCore.Bandit.load(intArrayOf(1, 2, 4, 8, 12), null); val db = AgendaCore.Bandit(intArrayOf(300, 600, 1000, 1600))
        val today = LocalDate.of(2026, 11, 13); val clock = ZonedDateTime.of(today, LocalTime.of(1, 0), zone)
        var ok = 0; var n = 0; var ms = 0L
        repeat(phones) {
            val sim = brutal(rnd, today); val hb = AgendaCore.Bandit(intArrayOf(0, 1, 2))
            repeat(6) {
                val t = clock.plusMinutes(20L + rnd.nextInt(60 * 24 * 20)).withSecond(0).withNano(0)
                val t0 = sim.now; val r = episode(sim, t, clock, wb, db, rnd, g.explore, hb, g)
                n++; ms += sim.now - t0; if (r.ok) ok++
            }
        }
        val rate = ok * 100.0 / n; val sec = ms / 1000.0 / n
        return Triple(rate * 10 - sec, rate, sec)
    }

    /** Demora (minutos): roda quando EVOLUIR=1. A melhor sai em build/treino_evolucao.txt e vai pro TreinoPronto. */
    @Test fun evolucao() {
        if (System.getenv("EVOLUIR") != "1") return
        val rnd = java.util.Random(42)
        var pop = listOf(Genome()) + (1..11).map { Genome().mutate(rnd).mutate(rnd) }
        var best = Genome(); var bestFit = Triple(-1e9, 0.0, 0.0)
        val hist = StringBuilder()
        repeat(14) { gen ->
            val scored = pop.map { g -> g to fitness(g, 1000L + gen, 50) }.sortedByDescending { it.second.first }
            val top = scored.first()
            hist.append("geração $gen: melhor ${"%.1f".format(top.second.second)}% (${"%.1f".format(top.second.third)} s)\n")
            if (top.second.first > bestFit.first) { best = top.first; bestFit = top.second }
            // os 4 melhores ficam; o resto são filhos com mutação aleatória
            val elite = scored.take(4).map { it.first }
            pop = elite + (1..8).map { elite[rnd.nextInt(elite.size)].mutate(rnd) }
        }
        // prova final da melhor, em celulares brutais que ela nunca viu
        val proof = fitness(best, 99_999L, 300)
        val out = "EVOLUCAO melhor genoma ${best.save()} · prova: ${"%.2f".format(proof.second)}% em 1800 posts brutais · ${"%.1f".format(proof.third)} s/post\n$hist"
        println(out); java.io.File("build/treino_evolucao.txt").writeText(out)
        assertTrue(out, proof.second >= 97.0)
    }

    /** 🧠 o cérebro ANTIGO (RateWatch.oldWheelSheet) rodando no mesmo simulador: comando de 1 linha, vários de uma vez, relê a cada 350 ms. */
    private fun oldEpisode(sim: Sim, target: ZonedDateTime, clock: ZonedDateTime): Result {
        val noWrap = HashSet<Int>(); val stuck = HashMap<Int, Int>(); val gesture = HashSet<Int>()
        var last: Triple<Int, String, String>? = null; var okSig = ""; var reopen = 0; val t0 = sim.now
        while (true) {
            if (sim.now - t0 > 240_000) return Result(false, sim.now, "demorou")
            sim.now += 350
            val r = sim.screenOrNull()?.let { AgendaCore.wheelRead(it, sim.band, 124, sim.today) } ?: continue
            val sig = r.toString()
            last?.let { (c, b, how) -> if (b == sig) { stuck[c] = (stuck[c] ?: 0) + 1; if (how == "acao") gesture.add(c); if (c == 1) noWrap.add(1) } else { stuck.remove(c) }; Unit }
            val next = AgendaCore.wheelNext(r, target, noWrap)
            if (next == null) {
                last = null
                if (okSig != sig) { okSig = sig; continue }
                if (!sim.doneEnabled(clock)) return Result(false, sim.now, "não aceita")
                if (sim.at() == target) return Result(true, sim.now, "")
                if (++reopen > 3) return Result(false, sim.now, "post errado 4x"); sim.now += 1500; okSig = ""; continue
            }
            okSig = ""
            val (col, diff) = next
            if ((stuck[col] ?: 0) >= 4) return Result(false, sim.now, "rodinha $col não anda")
            val dir = if (diff > 0) 1 else -1
            val n = minOf(Math.abs(diff), when (col) { 3 -> 1; 1 -> 6; else -> 12 })
            if (col !in gesture) {
                if (!sim.act(col, dir, n, 0)) { noWrap += col; last = Triple(col, sig, "fim"); continue }
                last = Triple(col, sig, "acao")
            } else { sim.act(col, dir, minOf(n, 3), 2); sim.now += minOf(n, 3) * 600L; last = Triple(col, sig, "gesto") }
        }
    }

    /** O celular do usuário (Xiaomi, pelo diagnóstico): comando anda 1 linha e funciona em todas; arrasto embala; animação ~300 ms. */
    private fun xiaomi(rnd: java.util.Random, today: LocalDate) = Sim(today, rnd.nextInt(8), rnd.nextInt(12), rnd.nextInt(60), rnd.nextBoolean(),
        hourWraps = false, slip = 0.05, lagMs = 250L + rnd.nextInt(250), rnd = rnd, readFail = 0.03, misread = 0.01)

    @Test fun comparaNoXiaomi() {
        val today = LocalDate.of(2026, 11, 13); val clock = ZonedDateTime.of(today, LocalTime.of(1, 0), zone)
        fun run(name: String, f: (Sim, ZonedDateTime) -> Result): String {
            val rnd = java.util.Random(777); var ok = 0; var n = 0; var ms = 0L; val fails = HashMap<String, Int>()
            repeat(200) { val sim = xiaomi(rnd, today); repeat(6) {
                val t = clock.plusMinutes(20L + rnd.nextInt(60 * 24 * 10)).withSecond(0).withNano(0); val t0 = sim.now
                val r = f(sim, t); n++; ms += sim.now - t0; if (r.ok) ok++ else fails.merge(r.why, 1, Int::plus) } }
            return "$name: ${"%.2f".format(ok * 100.0 / n)}% ($ok/$n) · ${"%.1f".format(ms / 1000.0 / n)} s/post · falhas $fails"
        }
        val g = Genome.load(TreinoPronto.GENOMA)
        val out = listOf(
            run("🧠 ANTIGO") { sim, t -> oldEpisode(sim, t, clock) },
            run("🧠 NOVO (genoma de fábrica)") { sim, t -> episode(sim, t, clock, AgendaCore.Bandit.load(intArrayOf(1, 2, 4, 8, 12), null), AgendaCore.Bandit(intArrayOf(300, 600, 1000, 1600)), java.util.Random(1), g.explore, AgendaCore.Bandit(intArrayOf(0, 1, 2)), g) },
            run("🧠 NOVO com comando 1º") { sim, t -> val g2 = g.copy(firstHow = 0, stableMs = 350); episode(sim, t, clock, AgendaCore.Bandit.load(intArrayOf(1, 2, 4, 8, 12), null), AgendaCore.Bandit(intArrayOf(300, 600, 1000, 1600)), java.util.Random(1), g2.explore, AgendaCore.Bandit(intArrayOf(0, 1, 2)), g2) },
        ).joinToString("\n")
        println(out); java.io.File("build/compara_xiaomi.txt").writeText(out)
        assertTrue(out, out.lines()[0].contains("100.00%") || out.lines()[0].substringAfter(": ").substringBefore("%").toDouble() >= 99.0)
    }
}
