package com.leve.analise

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 📱 O LOTE INTEIRO como acontece no celular, com o 🧠 cérebro ANTIGO.
 *
 * Diferente do SheetPilotTrainingTest (que chama o piloto num laço), aqui o robô roda do jeito do Android:
 *  - o composeStep só roda quando chega um EVENTO de acessibilidade ou quando um postDelayed agendado vence;
 *  - eventos só chegam enquanto a rodinha gira (notificationTimeout 800 ms) — rodinha parada = silêncio;
 *  - o freio de 600 ms (cLast) do composeStep;
 *  - o Done pode ignorar o "clique" de acessibilidade (o clique devolve true e nada acontece);
 *  - a folha de cada post abre com o horário do post ANTERIOR (o Threads lembra);
 *  - lote de 13 posts seguidos, cada um com seu atraso, leitura falhando, número lido errado, escorregão.
 *
 * [fixed]=false reproduz a v1.0.174 (o "não toca no Done" / 3º post travado); true = a versão corrigida.
 */
class LoteRealTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    data class Cfg(val speed: Int, val pClick: Double, val pTap: Double, val lagMin: Long, val lagMax: Long,
                   val evMin: Long, val evMax: Long, val pTail: Double, val readFail: Double, val misread: Double, val slip: Double)

    class Phone(val rnd: java.util.Random, val cfg: Cfg, val fixed: Boolean) {
        private val zone = ZoneId.of("America/Sao_Paulo")
        var t = 0L
        private var seq = 0L
        private val q = java.util.PriorityQueue<Triple<Long, Long, () -> Unit>>(compareBy({ it.first }, { it.second }))
        fun post(ms: Long, f: () -> Unit) { q.add(Triple(t + ms, seq++, f)) }
        fun runUntil(stop: () -> Boolean, limit: Long) {
            while (!stop() && q.isNotEmpty() && t < limit) { val (at, _, f) = q.poll(); t = maxOf(t, at); f() }
        }

        // ---- a tela do Threads
        lateinit var sim: SheetPilotTrainingTest.Sim
        var sheetOpen = true
        lateinit var clock: ZonedDateTime
        private var evChain = false
        /** eventos de acessibilidade: só enquanto a rodinha gira; às vezes 1 a mais logo depois que ela para */
        fun kickEvents() {
            if (evChain) return; evChain = true
            fun next(d: Long): Unit = post(d) {
                sim.now = t
                val anim = sim.animating()
                onEvent()
                if (anim || sim.animating()) next(cfg.evMin + rnd.nextInt((cfg.evMax - cfg.evMin + 1).toInt()))
                else if (rnd.nextDouble() < cfg.pTail) { evChain = false; post(50L + rnd.nextInt(250)) { onEvent() } } else evChain = false
            }
            next(30L + rnd.nextInt(200))
        }
        fun onEvent() { if (composeUntil > t) composeStep() }

        // ---- o robô (porta fiel do RateWatch: composeStep + oldWheelSheet + passo 7)
        var cTarget = 0L; var composeUntil = 0L; var cStep = 6; var cTries = 0; var cTapAt = 0L; var cLast = -10_000L; var cRetry = false
        var wSettle = ""; var wOkSig = ""; var wLast: Triple<Int, String, String>? = null; var wLastSig = ""
        val wStuck = HashMap<Int, Int>(); val wGesture = HashSet<Int>(); val wNoWrap = HashSet<Int>(); val cWheel = HashMap<String, Int>()
        var cDoneAt = 0L; var cDoneProblems = 0; var doneHow = 0; var lastHow = 0
        val trace = ArrayList<String>(); fun tr(m: String) { if (trace.size > 400) trace.removeAt(0); trace += "${t} $m" }
        var result: String? = null      // null = ainda rodando; "ok" ou o motivo da falha
        val stepMs get() = listOf(700L, 450L, 350L, 250L)[cfg.speed]

        fun arm(target: Long) {
            cTarget = target; cStep = 6; cTries = 0; cTapAt = 0; wSettle = ""; wOkSig = ""; wLast = null; wLastSig = ""; wStuck.clear(); wGesture.clear(); wNoWrap.clear(); cWheel.clear()
            cDoneProblems = 0; result = null; composeUntil = t + 180_000L
            post(1800) { if (composeUntil > t) composeStep() }
        }

        private fun again() {
            if (++cTries > (if (cStep == 6) 250 else 60)) { result = "travou no passo $cStep"; composeUntil = 0; return }
            post(if (cStep == 6) stepMs + 30 else 700) { if (composeUntil > t) composeStep() }
        }

        private fun read(): AgendaCore.WRead? { sim.now = t; return sim.screenOrNull()?.let { AgendaCore.wheelRead(it, sim.band, 124, sim.today) } }

        fun composeStep() {
            val now = t
            if (now - cLast < 600) {
                if (fixed && !cRetry) { cRetry = true; post(620 - (now - cLast)) { cRetry = false; if (composeUntil > t) composeStep() } }
                return
            }
            cLast = now
            val wait = if (cStep == 6) 350L else 1500L
            if (now - cTapAt < wait) { post(wait + 50) { composeStep() }; return }
            when (cStep) {
                6 -> { if (!sheetOpen) { again(); return }; if (!oldWheelSheet(now)) again() }
                7 -> {
                    if (sheetOpen) {
                        if (now - cDoneAt < 1500) { again(); return }
                        val r2 = read()
                        val ok2 = r2 != null && AgendaCore.wheelNext(r2, java.time.Instant.ofEpochMilli(cTarget).atZone(zone), wNoWrap) == null
                        val retry = cWheel["doneRetry"] ?: 0
                        if (fixed && ok2 && retry < 10) { cWheel["doneRetry"] = retry + 1; cStep = 6; cTries = 0; wOkSig = r2.toString(); wLast = null }
                        else if (ok2) doneProblem() else { cStep = 6; cTries = 0; wOkSig = "" }
                        again(); return
                    }
                    // folha fechou: o post mostra o horário escolhido · aprende o jeito do Done que funcionou
                    if (fixed) doneHow = lastHow
                    result = if (sim.at().toInstant().toEpochMilli() == cTarget) "ok" else "post ficou com ${sim.at()} (alvo ${java.time.Instant.ofEpochMilli(cTarget).atZone(zone)})"
                    composeUntil = 0
                }
            }
        }

        private fun doneProblem() {
            cDoneProblems++
            if (cDoneProblems > 3) { result = "Threads não aceita o horário"; composeUntil = 0; return }
            cTarget = maxOf(cTarget, clock.toInstant().toEpochMilli() + t + 15 * 60_000L) + 10 * 60_000L * (cDoneProblems - 1)
            wOkSig = ""; cStep = 6; cTries = 0
            movedTime = true
        }
        var movedTime = false

        /** toque no Done: o clique de acessibilidade pode ser ignorado; o toque de verdade quase sempre pega */
        private fun pressDone(real: Boolean): Boolean {
            val takes = rnd.nextDouble() < (if (real) cfg.pTap else cfg.pClick)
            sim.now = t
            if (takes && sim.doneEnabled(clock.plusNanos(t * 1_000_000))) post(250L + rnd.nextInt(200)) { sheetOpen = false }
            return true
        }

        private fun oldWheelSheet(now: Long): Boolean {
            val z = java.time.Instant.ofEpochMilli(cTarget).atZone(zone)
            val r = read() ?: return false
            val sig = r.toString()
            tr("leu $sig alvo ${z.toLocalDateTime()} real ${sim.at().toLocalDateTime()} stuck=$wStuck gest=$wGesture nowrap=$wNoWrap")
            // mexeu: só decide o próximo passo quando a leitura repetir (rodinha parada) — senão corrige em cima de um número no meio do giro
            if (fixed && wLast != null && wSettle != sig) { wSettle = sig; cTapAt = now; again(); return true }
            wLast?.let { (col, before, how) -> if (before == sig) { wStuck[col] = (wStuck[col] ?: 0) + 1; if (how == "acao") wGesture.add(col); if (col == 1) wNoWrap.add(1) } else { wStuck.remove(col) }; Unit }
            if (wLast?.second != sig) composeUntil = maxOf(composeUntil, t + 90_000L)
            val next = AgendaCore.wheelNext(r, z, wNoWrap)
            if (next == null) {
                wLast = null
                if (wOkSig != sig) { wOkSig = sig; cTapAt = now; again(); return true }
                if (fixed) { val k = cWheel["doneTaps"] ?: 0; cWheel["doneTaps"] = k + 1; lastHow = (k + doneHow) % 2; pressDone(real = lastHow == 0) }
                else pressDone(real = false)                      // v1.0.174: click(done) devolve true → o toque de verdade nunca era tentado
                cStep = 7; cTries = 0; cTapAt = now; cDoneAt = now
                again(); return true
            }
            wOkSig = ""
            val (col, diff) = next
            if ((wStuck[col] ?: 0) >= 4) { result = "rodinha $col não anda"; composeUntil = 0; return true }
            val dir = if (diff > 0) 1 else -1
            val n = minOf(Math.abs(diff), when (col) { 3 -> 1; 1 -> 6; else -> 12 })
            sim.now = t
            var how = "gesto"
            if (col !in wGesture) {
                var ok = true
                repeat(n) { if (ok) ok = sim.act(col, dir, 1, 0) }
                if (ok) how = "acao" else { wNoWrap.add(col); wLast = Triple(col, sig, "fim"); wSettle = ""; cTapAt = now - 600; kickEvents(); again(); return true }
            }
            if (how == "gesto") { val g = minOf(n, 3); sim.act(col, dir, g, 2); cTapAt = now + g * 600L } else cTapAt = now
            wLast = Triple(col, sig, how); wSettle = ""
            kickEvents()
            again(); return true
        }
    }

    /** Um lote de [n] posts seguidos; a folha de cada post abre no horário do anterior. */
    private var dumps = 0
    private fun lote(rnd: java.util.Random, cfg: Cfg, fixed: Boolean, n: Int, stats: HashMap<String, Int>): Int {
        val today = LocalDate.of(2026, 11, 13)
        val clock0 = ZonedDateTime.of(today, LocalTime.of(8 + rnd.nextInt(10), rnd.nextInt(60)), zone)
        val ph = Phone(rnd, cfg, fixed)
        var at = clock0.plusMinutes(20L + rnd.nextInt(40)).withSecond(0).withNano(0)
        var open = clock0.plusMinutes(1).withSecond(0).withNano(0)   // 1º post: a folha abre perto de "agora"
        var ok = 0
        repeat(n) { k ->
            val clk = clock0.plusNanos(ph.t * 1_000_000)
            val dayIdx = (open.toLocalDate().toEpochDay() - today.toEpochDay()).toInt()
            ph.sim = SheetPilotTrainingTest.Sim(today, dayIdx, open.hour % 12, open.minute, open.hour >= 12, hourWraps = rnd.nextBoolean(), slip = cfg.slip,
                lagMs = cfg.lagMin + rnd.nextInt((cfg.lagMax - cfg.lagMin + 1).toInt()), rnd = rnd, readFail = cfg.readFail, misread = cfg.misread)
            ph.clock = clock0; ph.sheetOpen = true; ph.movedTime = false
            ph.arm(at.toInstant().toEpochMilli())
            val t0 = ph.t
            ph.runUntil({ ph.result != null }, t0 + 400_000L)
            val res = ph.result ?: (if (ph.sheetOpen && ph.sim.at().toInstant().toEpochMilli() == ph.cTarget) "TRAVOU com as rodinhas certas (Done nunca tocado)" else "travou (tempo)")
            val good = res == "ok" && !ph.movedTime
            stats.merge("#ms", ((ph.t - t0) / 1000).toInt(), Int::plus)
            if (!good && fixed && dumps++ < 3 && System.getenv("TRACE") != null) println("=== FALHA ${res}\n" + ph.trace.takeLast(40).joinToString("\n"))
            ph.trace.clear()
            if (good) ok++ else stats.merge("post ${k + 1}: " + (if (res == "ok") "horário mudado (Done não pegou)" else res), 1, Int::plus)
            // próximo post: a folha abre onde ficou este
            open = ph.sim.at(); at = at.plusMinutes(25L + rnd.nextInt(50)); if (at.hour >= 23) at = at.plusDays(1).withHour(9 + rnd.nextInt(3))
            ph.composeUntil = 0; ph.t += 3500 + 1500 + 1800  // nextAuto + abrir o Threads + anexar/legenda/menu
            ph.runUntil({ false }, ph.t)                     // descarta eventos velhos
        }
        return ok
    }

    private val cfgs = listOf(
        "Xiaomi (clique no Done pega)" to Cfg(2, 0.97, 0.98, 200, 700, 300, 800, 0.5, 0.03, 0.01, 0.05),
        "Xiaomi (clique no Done ignorado)" to Cfg(2, 0.0, 0.98, 200, 700, 300, 800, 0.5, 0.03, 0.01, 0.05),
        "Xiaomi lento + leitura ruim" to Cfg(2, 0.5, 0.95, 400, 1400, 400, 800, 0.2, 0.10, 0.05, 0.30),
        "Xiaomi sem evento no fim" to Cfg(2, 0.5, 0.98, 200, 700, 300, 800, 0.0, 0.03, 0.01, 0.05),
        "velocidade Muito rápido" to Cfg(3, 0.5, 0.98, 200, 900, 300, 800, 0.3, 0.05, 0.02, 0.10),
        "velocidade Normal" to Cfg(1, 0.5, 0.98, 200, 900, 300, 800, 0.3, 0.05, 0.02, 0.10),
        "velocidade Devagar" to Cfg(0, 0.5, 0.98, 200, 900, 300, 800, 0.3, 0.05, 0.02, 0.10),
        // 💀 tortura: celular travando, eventos raros, leitura péssima, toque que falha
        "💀 rodinha muito lenta (2 s)" to Cfg(2, 0.3, 0.9, 1200, 2200, 400, 800, 0.2, 0.05, 0.02, 0.20),
        "💀 leitura falha 25% e erra 15%" to Cfg(2, 0.5, 0.95, 200, 900, 300, 800, 0.3, 0.25, 0.15, 0.10),
        "💀 escorrega muito (50%)" to Cfg(2, 0.5, 0.95, 200, 900, 300, 800, 0.3, 0.05, 0.02, 0.50),
        "💀 toque no Done falha 30%" to Cfg(2, 0.0, 0.70, 200, 900, 300, 800, 0.3, 0.05, 0.02, 0.10),
        "💀 quase sem eventos" to Cfg(2, 0.5, 0.95, 200, 900, 1500, 3000, 0.0, 0.05, 0.02, 0.10),
        "💀 tudo junto" to Cfg(3, 0.2, 0.8, 600, 2000, 800, 2500, 0.1, 0.20, 0.10, 0.40),
    )

    private fun run(fixed: Boolean, lotes: Int, seed: Long): Pair<String, Double> {
        val sb = StringBuilder(); var tot = 0; var good = 0
        cfgs.forEach { (name, c) ->
            val rnd = java.util.Random(seed); val st = HashMap<String, Int>(); var ok = 0; var n = 0
            repeat(lotes) { ok += lote(rnd, c, fixed, 13, st); n += 13 }
            tot += n; good += ok
            val secs = st.remove("#ms") ?: 0
            val worst = st.entries.sortedByDescending { it.value }.take(4).joinToString { "${it.key}=${it.value}" }
            sb.append("  $name: ${"%.2f".format(ok * 100.0 / n)}% ($ok/$n) · ${"%.1f".format(secs.toDouble() / n)} s/post" + (if (worst.isNotEmpty()) " · falhas: $worst" else "") + "\n")
        }
        return sb.toString() to good * 100.0 / tot
    }

    @Test fun antesEDepois() {
        val lotes = (System.getenv("LOTES") ?: "60").toInt()
        val (a, pa) = run(false, lotes, 11L)
        val (b, pb) = run(true, lotes, (System.getenv("SEED") ?: "11").toLong())
        val out = "v1.0.174 (antes): ${"%.2f".format(pa)}%\n$a\ncorrigido: ${"%.2f".format(pb)}%\n$b"
        println(out); java.io.File("build/lote_real.txt").writeText(out)
        assertTrue(out, pb >= 99.5)
    }
}
