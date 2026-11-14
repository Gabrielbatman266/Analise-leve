package com.leve.analise

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 🛫 PILOTO AUTOMÁTICO (cérebro puro, sem Android — treinado no AutoPilotSimTest).
 * A cada rodada decide QUANTOS posts novos montar e PRA QUAL DIA, pra manter os próximos dias cheios sem passar do
 * limite de 25 agendados do Threads. Também decide se é hora de coletar números (posts que precisam de leitura).
 */
object AutoPilotCore {
    data class Cfg(val perDay: Int = 8, val daysAhead: Int = 2, val cap: Int = ScheduleCapCore.CAP,
                   val startH: Int = 8, val endH: Int = 23, val endM: Int = 30, val leadMin: Long = 40, val gapMin: Long = 25)
    data class Need(val day: LocalDate, val from: Long, val to: Long, val n: Int)

    /**
     * [waiting]: horários já agendados no Threads (ainda não saíram). [pending]: horários no lote esperando o robô.
     * Devolve, por dia, a janela livre e quantos posts faltam. Nunca deixa (agendados + lote) passar do limite.
     */
    fun plan(now: Long, waiting: List<Long>, pending: List<Long>, cfg: Cfg = Cfg(), zone: ZoneId = ZoneId.systemDefault(), published: List<Long> = emptyList()): List<Need> {
        val future = (waiting + pending).filter { it > now }.distinctBy { it / 60_000L }
        // o que JÁ saiu hoje também conta pra meta do dia (sem isso ele enchia o dia de novo a cada rodada: 38 posts/dia no teste)
        val known = (future + published.filter { it <= now }).distinctBy { it / 60_000L }
        var room = cfg.cap - (waiting.count { it > now + 60_000L } + pending.count { it > now })
        if (room <= 0) return emptyList()
        val out = ArrayList<Need>()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (d in 0 until cfg.daysAhead) {
            val day = today.plusDays(d.toLong())
            val start = ZonedDateTime.of(day, LocalTime.of(cfg.startH, 0), zone).toInstant().toEpochMilli()
            val end = ZonedDateTime.of(day, LocalTime.of(cfg.endH, cfg.endM), zone).toInstant().toEpochMilli()
            val from = maxOf(start, now + cfg.leadMin * 60_000L)
            if (end - from < cfg.gapMin * 60_000L) continue
            val have = known.count { it in start..end }
            // quanto cabe na janela que sobrou do dia (hoje à noite já não cabe 8)
            val fits = ((end - from) / (cfg.gapMin * 60_000L)).toInt() + 1
            val dayTarget = cfg.perDay
            val n = minOf(dayTarget - have, fits - future.count { it in from..end }, room)
            if (n > 0) { out += Need(day, from, end, n); room -= n }
            if (room <= 0) break
        }
        return out
    }

    /** Horários pra [n] posts entre [from] e [to], com [gapMin] entre todos (inclusive os que já existem em [busy]). */
    fun times(n: Int, from: Long, to: Long, busy: List<Long>, gapMin: Long = 25, score: (Long) -> Double = { 0.5 }): List<Long> {
        val gap = gapMin * 60_000L; val step = 5 * 60_000L
        val first = (from + step - 1) / step * step
        val cand = generateSequence(first) { it + step }.takeWhile { it <= to }.filter { t -> busy.none { Math.abs(it - t) < gap } }.toList()
        val out = ArrayList<Long>()
        cand.sortedByDescending { score(it) }.forEach { t -> if (out.size < n && out.all { Math.abs(it - t) >= gap }) out += t }
        // a pontuação deixou buraco? completa pela ordem
        if (out.size < n) cand.forEach { t -> if (out.size < n && out.all { Math.abs(it - t) >= gap }) out += t }
        return out.sorted()
    }

    /** Coletar números agora? Só com o robô parado, tem post precisando de leitura e a última coleta foi há mais de [everyH] h. */
    fun shouldCollect(now: Long, lastCollect: Long, needRead: Int, robotBusy: Boolean, everyH: Int = 6) =
        !robotBusy && needRead > 0 && now - lastCollect > everyH * 3_600_000L
}

/** Horário dentro da janela de postar (8h–23h30): passou do fim → dia seguinte no começo; antes do começo → o começo. */
object BatchTimes {
    fun inWindow(t: Long, startH: Int = 8, endH: Int = 23, endM: Int = 30, zone: ZoneId = ZoneId.systemDefault()): Long {
        val z = Instant.ofEpochMilli(t).atZone(zone)
        val lt = z.toLocalTime()
        return when {
            lt.isAfter(LocalTime.of(endH, endM)) -> ZonedDateTime.of(z.toLocalDate().plusDays(1), LocalTime.of(startH, 0), zone).toInstant().toEpochMilli()
            lt.isBefore(LocalTime.of(startH, 0)) -> ZonedDateTime.of(z.toLocalDate(), LocalTime.of(startH, 0), zone).toInstant().toEpochMilli()
            else -> t
        }
    }
}
