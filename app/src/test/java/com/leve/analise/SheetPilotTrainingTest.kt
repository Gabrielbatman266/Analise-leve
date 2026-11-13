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
 *  - a folha abre com o horário do post anterior (o "3º post" que voltava a data).
 * O Done vale o que está DE VERDADE na rodinha (não o que a tela mostrava).
 */
class SheetPilotTrainingTest {
    private val sp = "America/Sao_Paulo"; private val zone = ZoneId.of(sp)

    class Sim(val today: LocalDate, var day: Int, var hPos: Int, var min: Int, var pm: Boolean, val hourWraps: Boolean,
              val slip: Double, val lagMs: Long, val rnd: java.util.Random,
              /** works[col][how]: o comando / tocar no número / arrastar funcionam nessa coluna? (falso = ignora e não mexe) */
              val works: Array<BooleanArray> = Array(4) { BooleanArray(3) { true } }) {
        val band = 1940; val rowH = 103
        var now = 0L
        // o que a tela mostra (fica pra trás enquanto anima)
        private var shown = Triple(day, hPos, min); private var shownPm = pm; private var animUntil = 0L
        private var from = Triple(day, hPos, min); private var animStart = 0L
        private fun fmtDay(d: LocalDate) = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + ", " +
            d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + d.dayOfMonth
        fun hour12(pos: Int) = if (pos == 0) 12 else pos
        fun at(): ZonedDateTime { val h24 = hPos + (if (pm) 12 else 0); return ZonedDateTime.of(today.plusDays(day.toLong()), LocalTime.of(h24, min), ZoneId.of("America/Sao_Paulo")) }
        fun screen(): List<AgendaCore.WItem> {
            if (now >= animUntil) { shown = Triple(day, hPos, min); shownPm = pm }
            else { // no meio da animação a tela mostra valores INTERMEDIÁRIOS (ou ainda o antigo, no começo)
                val f = ((now - animStart).toDouble() / maxOf(1L, animUntil - animStart)).coerceIn(0.0, 1.0)
                fun mid(a: Int, b: Int) = Math.round(a + (b - a) * f).toInt()
                shown = Triple(mid(from.first, day), mid(from.second, hPos), mid(from.third, min))
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
            return scroll(col, dir, if (how == 1) minOf(rows, 2) else rows)
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
            1 -> { val p = hPos + dir; if (hourWraps) { hPos = ((p % 12) + 12) % 12; true } else (p in 0..11).also { if (it) hPos = p } }
            2 -> (min + dir in 0..59).also { if (it) min += dir }
            else -> (if (dir > 0) !pm else pm).also { if (it) pm = !pm }
        }
        fun doneEnabled(clock: ZonedDateTime) = at().isAfter(clock.plusMinutes(5))
    }

    data class Result(val ok: Boolean, val ms: Long, val why: String)

    /** Um post: abre a folha (com o horário anterior), o piloto acerta, toca no Done, confere; corrige se o post mostrar errado. */
    private fun episode(sim: Sim, target: ZonedDateTime, clock: ZonedDateTime, wb: AgendaCore.Bandit, db: AgendaCore.Bandit, rnd: java.util.Random, explore: Double,
                        hb: AgendaCore.Bandit = AgendaCore.Bandit(intArrayOf(0, 1, 2))): Result {
        val pilot = SheetPilot(target, wb, db, { c -> when (c) { 3 -> 1; 1 -> 11; else -> 20 } }, rnd, explore, hb)
        var reopen = 0
        while (true) {
            val r = AgendaCore.wheelRead(sim.screen(), sim.band, 124, sim.today)
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
}
