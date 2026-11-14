package com.leve.analise

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 🛫 TESTE EXTREMO do piloto automático: 30 dias seguidos sozinho, rodada a cada 90 min, com caos:
 * celular bloqueado, robô errando, alarme atrasando, fotos acabando, limite de 25 do Threads, horários vencendo.
 * Mede quantos posts saem por dia e confere as regras (nunca >25 esperando, nunca fora de 8h–23h30, nunca no passado).
 */
class AutoPilotSimTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val min = 60_000L

    data class Chaos(val name: String, val pLocked: Double, val pRobotFail: Double, val alarmDelayMin: Int, val pNoPhoto: Double, val pRobotStops: Double)

    data class Out(val perDay: Double, val goodDays: Double, val maxWaiting: Int, val outside: Int, val past: Int, val tight: Int, val note: String)

    /** A mesma regra do Batch.refreshTimes (só os vencidos andam pra frente), com o conserto: não passa das 23h30. */
    private fun refresh(pend: MutableList<Long>, busy: List<Long>, now: Long, cfg: AutoPilotCore.Cfg) {
        if (pend.none { it < now + 15 * min }) return
        pend.sort(); val gap = 25 * min; var last = now + 20 * min - gap
        for (i in pend.indices) {
            var t = pend[i]
            if (t < now + 15 * min || t < last + gap) {
                t = maxOf(now + 20 * min, last + gap); t = (t + 5 * min - 1) / (5 * min) * (5 * min)
                t = BatchTimes.inWindow(t, cfg.startH, cfg.endH, cfg.endM, zone)
                val others = busy + pend.filterIndexed { j, _ -> j != i }
                while (others.any { Math.abs(it - t) < gap }) t = BatchTimes.inWindow(t + 5 * min, cfg.startH, cfg.endH, cfg.endM, zone)
            }
            pend[i] = t; last = t
        }
    }

    private fun run(ch: Chaos, seed: Long, days: Int = 30, cfg: AutoPilotCore.Cfg = AutoPilotCore.Cfg()): Out {
        val r = java.util.Random(seed)
        val start = ZonedDateTime.of(LocalDate.of(2026, 11, 14), LocalTime.of(1, 45), zone).toInstant().toEpochMilli()
        val end = start + days * 86_400_000L
        val waiting = ArrayList<Long>(); val pending = ArrayList<Long>(); val published = ArrayList<Long>()
        var maxW = 0; var outside = 0; var past = 0; var now = start
        while (now < end) {
            now += 90 * min + r.nextInt(ch.alarmDelayMin + 1) * min
            // o Threads publica o que venceu
            waiting.filter { it <= now }.let { published += it; waiting.removeAll(it.toSet()) }
            refresh(pending, waiting, now, cfg)
            // 🛫 o piloto planeja e monta
            for (need in AutoPilotCore.plan(now, waiting, pending, cfg, zone, published)) {
                val ts = AutoPilotCore.times(need.n, need.from, need.to, waiting + pending, cfg.gapMin)
                ts.forEach { if (r.nextDouble() >= ch.pNoPhoto) pending += it }
            }
            pending.sort()
            // 🤖 o robô agenda (se o celular não estiver bloqueado)
            if (r.nextDouble() >= ch.pLocked) {
                var t = now
                val it = pending.iterator()
                while (it.hasNext()) {
                    val at = it.next()
                    if (r.nextDouble() < ch.pRobotStops) break                               // saiu do Threads / travou: para o lote
                    val cap = ScheduleCapCore.plan(waiting, t)
                    if (cap.free == 0) break
                    t += 15_000L
                    if (r.nextDouble() < ch.pRobotFail) continue                             // errou esse: fica pro próximo
                    if (at < t + 5 * min) { past++; continue }                               // o Threads não aceita horário vencido
                    val lt = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
                    if (lt.isBefore(LocalTime.of(cfg.startH, 0)) || lt.isAfter(LocalTime.of(cfg.endH, cfg.endM))) outside++
                    waiting += at; it.remove()
                }
            }
            maxW = maxOf(maxW, waiting.count { it > now })
        }
        // posts por dia (ignora o 1º dia, que começa de madrugada, e o último)
        val perDay = published.groupBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.filterKeys { it > LocalDate.of(2026, 11, 14) && it < LocalDate.of(2026, 11, 14).plusDays(days.toLong() - 1) }
        val counts = (1 until days - 1).map { perDay[LocalDate.of(2026, 11, 14).plusDays(it.toLong())]?.size ?: 0 }
        val tight = published.sorted().zipWithNext().count { (a, b) -> b - a < 25 * min }
        return Out(counts.average(), counts.count { it >= cfg.perDay - 1 } * 100.0 / counts.size, maxW, outside, past, tight, "dias: " + counts.joinToString(","))
    }

    private val chaos = listOf(
        Chaos("😌 tudo certo", 0.0, 0.0, 0, 0.0, 0.0),
        Chaos("📱 celular bloqueado 40% das rodadas", 0.4, 0.0, 5, 0.0, 0.0),
        Chaos("🤖 robô erra 30% dos posts", 0.1, 0.3, 5, 0.0, 0.05),
        Chaos("⏰ alarme atrasa até 60 min", 0.1, 0.1, 60, 0.0, 0.05),
        Chaos("🖼 fotos faltando 25%", 0.1, 0.1, 10, 0.25, 0.05),
        Chaos("💀 tudo junto (bloqueado 50%, robô erra 35%, para 15%, alarme +60, fotos 30%)", 0.5, 0.35, 60, 0.3, 0.15),
    )

    @Test fun trintaDiasSozinho() {
        val sb = StringBuilder()
        var worst = 100.0
        for (c in chaos) {
            val o = (1..20).map { run(c, it * 977L) }
            val pd = o.map { it.perDay }.average(); val gd = o.map { it.goodDays }.average()
            val line = "${c.name}: ${"%.2f".format(pd)} posts/dia (meta 8) · dias com 7+: ${"%.0f".format(gd)}% · máx esperando ${o.maxOf { it.maxWaiting }}/25 · fora de 8h–23h30: ${o.sumOf { it.outside }} · no passado: ${o.sumOf { it.past }} · colados (<25 min): ${o.sumOf { it.tight }}"
            sb.appendLine(line)
            if (c.name.startsWith("😌")) assertTrue(line, pd >= 7.9 && pd <= 8.01)
            assertTrue(line, pd <= 8.2) // um vencido empurrado pro dia seguinte pode somar 1 lá
            assertTrue(line, o.all { it.maxWaiting <= 25 } && o.sumOf { it.outside } == 0 && o.sumOf { it.past } == 0)
            worst = minOf(worst, pd)
        }
        println(sb); java.io.File("build/piloto.txt").writeText(sb.toString())
    }
}
