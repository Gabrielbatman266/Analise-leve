package com.leve.analise

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * 🗓 Núcleo da agenda — Kotlin puro (sem Android), testado em AgendaCoreTest.
 * Recorrência, conflitos, status efetivo e leitura do horário que aparece na tela de agendar do Threads.
 * Horários são guardados como instante (ms) + fuso da regra; a recorrência é calculada no relógio de parede do fuso
 * (20:00 continua 20:00 mesmo quando o fuso muda de horário de verão).
 */
object AgendaCore {
    // ---------------------------------------------------------------- status
    const val AGENDADA = "agendada"          // salvo no app, esperando a hora
    const val NO_THREADS = "agendada_threads" // você agendou no próprio Threads (ele publica)
    const val EXECUTANDO = "executando"      // publicando agora pela API
    const val PUBLICADA = "publicada"
    const val PENDENTE = "pendente"          // chegou a hora e falta você postar/confirmar
    const val ATRASADA = "atrasada"
    const val ERRO = "erro"
    const val CANCELADA = "cancelada"

    val ACTIVE = setOf(AGENDADA, NO_THREADS, EXECUTANDO, PENDENTE)

    /** Status que aparece pra você: o que passou da hora sem acontecer vira "atrasada". */
    fun effective(status: String, mode: String, at: Long, now: Long): String = when {
        status == AGENDADA && now >= at + 10 * 60_000L -> ATRASADA
        status == PENDENTE && now >= at + 30 * 60_000L -> ATRASADA
        status == NO_THREADS && now >= at + 3 * 3600_000L -> PENDENTE // Threads não avisou que publicou: confira
        else -> status
    }

    // ---------------------------------------------------------------- recorrência
    /**
     * freq: "nenhuma" | "diaria" (a cada [every] dias) | "semanal" (dias [days] 1=seg..7=dom, a cada [every] semanas)
     *       | "horas" (a cada [every] horas, a partir do 1º horário)
     * [times]: "HH:mm" (vários horários por dia nas regras diária/semanal).
     * Termina em [end] (inclusive) ou depois de [count] ocorrências.
     */
    data class Rule(
        val freq: String,
        val every: Int = 1,
        val days: Set<Int> = emptySet(),
        val times: List<String> = listOf("20:00"),
        val start: LocalDate,
        val end: LocalDate? = null,
        val count: Int? = null,
        val zone: String = "America/Sao_Paulo",
    )

    data class Occ(val key: String, val at: Long)

    fun parseTime(s: String): LocalTime? {
        val m = Regex("^\\s*(\\d{1,2})[:hH.](\\d{2})\\s*$").find(s) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) LocalTime.of(h, mi) else null
    }

    private fun key(ldt: LocalDateTime) = ldt.toString().take(16) // 2026-11-12T20:00

    /** Todas as ocorrências da regra entre [from] e [to] (ms), menos as [exceptions] (chaves apagadas/editadas à parte). */
    fun expand(r: Rule, from: Long, to: Long, exceptions: Set<String> = emptySet()): List<Occ> {
        val zone = ZoneId.of(r.zone)
        val times = r.times.mapNotNull { parseTime(it) }.distinct().sorted().ifEmpty { listOf(LocalTime.of(20, 0)) }
        val every = maxOf(1, r.every)
        val out = ArrayList<Occ>()
        var n = 0
        fun emit(ldt: LocalDateTime): Boolean { // false = parar
            if (r.end != null && ldt.toLocalDate().isAfter(r.end)) return false
            if (r.count != null && n >= r.count) return false
            val at = ZonedDateTime.of(ldt, zone).toInstant().toEpochMilli()
            if (at > to) return false
            n++
            val k = key(ldt)
            if (at >= from && k !in exceptions) out += Occ(k, at)
            return true
        }
        when (r.freq) {
            "nenhuma" -> emit(LocalDateTime.of(r.start, times.first()))
            "diaria", "dias" -> {
                var d = r.start; var guard = 0
                loop@ while (guard++ < 4000) { for (t in times) if (!emit(LocalDateTime.of(d, t))) break@loop; d = d.plusDays(every.toLong()) }
            }
            "semanal" -> {
                val days = r.days.ifEmpty { setOf(r.start.dayOfWeek.value) }
                val week0 = r.start.with(DayOfWeek.MONDAY)
                var d = r.start; var guard = 0
                loop@ while (guard++ < 4000) {
                    val wk = ChronoUnit.WEEKS.between(week0, d.with(DayOfWeek.MONDAY))
                    if (wk % every == 0L && d.dayOfWeek.value in days) for (t in times) if (!emit(LocalDateTime.of(d, t))) break@loop
                    d = d.plusDays(1)
                    if (r.end == null && r.count == null && ZonedDateTime.of(d.atStartOfDay(), zone).toInstant().toEpochMilli() > to) break
                }
            }
            "horas" -> {
                var ldt = LocalDateTime.of(r.start, times.first()); var guard = 0
                while (guard++ < 20000 && emit(ldt)) ldt = ldt.plusHours(every.toLong())
            }
        }
        return out
    }

    // ---------------------------------------------------------------- conflitos
    data class Slot(val id: String, val at: Long)

    /** Ids que ficam a menos de [gapMin] minutos de outro post ativo. */
    fun conflicts(slots: List<Slot>, gapMin: Int): Set<String> {
        val s = slots.sortedBy { it.at }; val out = HashSet<String>()
        for (i in 1 until s.size) if (s[i].at - s[i - 1].at < gapMin * 60_000L) { out += s[i].id; out += s[i - 1].id }
        return out
    }

    // ---------------------------------------------------------------- horário na tela de agendar do Threads
    private val MONTHS = mapOf(
        "jan" to 1, "january" to 1, "janeiro" to 1, "feb" to 2, "february" to 2, "fev" to 2, "fevereiro" to 2,
        "mar" to 3, "march" to 3, "março" to 3, "marco" to 3, "apr" to 4, "april" to 4, "abr" to 4, "abril" to 4,
        "may" to 5, "mai" to 5, "maio" to 5, "jun" to 6, "june" to 6, "junho" to 6, "jul" to 7, "july" to 7, "julho" to 7,
        "aug" to 8, "august" to 8, "ago" to 8, "agosto" to 8, "sep" to 9, "sept" to 9, "september" to 9, "set" to 9, "setembro" to 9,
        "oct" to 10, "october" to 10, "out" to 10, "outubro" to 10, "nov" to 11, "november" to 11, "novembro" to 11,
        "dec" to 12, "december" to 12, "dez" to 12, "dezembro" to 12)

    /** Hora escrita na tela (14:30, 2:30 PM, 14h30). */
    fun findTime(texts: List<String>): LocalTime? {
        val re = Regex("(?<![\\d:])(\\d{1,2})(?:\\s?:\\s?|h)(\\d{2})(?:\\s*([ap])\\.?\\s*m\\.?)?(?![\\d:])", RegexOption.IGNORE_CASE)
        for (t in texts) {
            val m = re.find(t) ?: continue
            var h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
            val ap = m.groupValues[3].lowercase()
            if (ap == "p" && h < 12) h += 12
            if (ap == "a" && h == 12) h = 0
            if (h in 0..23 && mi in 0..59) return LocalTime.of(h, mi)
        }
        return null
    }

    /** Data escrita na tela (hoje/amanhã, 12/11, Nov 12, 12 de nov). */
    fun findDate(texts: List<String>, today: LocalDate): LocalDate? {
        for (t0 in texts) {
            val t = t0.lowercase().trim()
            if (Regex("(?<!\\p{L})(today|hoje)(?!\\p{L})").containsMatchIn(t)) return today
            if (Regex("(?<!\\p{L})(tomorrow|amanhã|amanha)(?!\\p{L})").containsMatchIn(t)) return today.plusDays(1)
            Regex("(?<![\\d:/])(?:(\\d{1,2})/(\\d{1,2})/(\\d{2,4})|(\\d{2})/(\\d{2}))(?![\\d:/])").find(t)?.let { m ->
                // dd/mm/aa(aa) ou dd/mm com 2 dígitos (o "1/2" do carrossel não é data)
                val g = m.groupValues; val full = g[1].isNotEmpty()
                val d = (if (full) g[1] else g[4]).toInt(); val mo = (if (full) g[2] else g[5]).toInt()
                val y = if (full) g[3].toInt().let { if (it < 100) 2000 + it else it } else today.year
                if (mo in 1..12 && d in 1..31) return fixYear(LocalDate.of(y, mo, minOf(d, LocalDate.of(y, mo, 1).lengthOfMonth())), today, !full)
            }
            // "Nov 12", "November 12, 2026", "Thu, Nov 12"
            Regex("(?<!\\p{L})([a-zç]{3,9})\\.?\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?(?!\\d)").findAll(t).forEach { m ->
                val mo = MONTHS[m.groupValues[1]] ?: return@forEach
                val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: today.year
                val d = m.groupValues[2].toInt(); if (d !in 1..31) return@forEach
                return fixYear(LocalDate.of(y, mo, minOf(d, LocalDate.of(y, mo, 1).lengthOfMonth())), today, m.groupValues[3].isEmpty())
            }
            // "12 de nov", "12 de novembro de 2026", "12 nov"
            Regex("(?<![\\d:])(\\d{1,2})\\s+(?:de\\s+)?([a-zç]{3,9})(?!\\p{L})\\.?(?:\\s+(?:de\\s+)?(\\d{4}))?").findAll(t).forEach { m ->
                val mo = MONTHS[m.groupValues[2]] ?: return@forEach
                val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: today.year
                val d = m.groupValues[1].toInt(); if (d !in 1..31) return@forEach
                return fixYear(LocalDate.of(y, mo, minOf(d, LocalDate.of(y, mo, 1).lengthOfMonth())), today, m.groupValues[3].isEmpty())
            }
        }
        return null
    }

    /** Sem ano escrito e a data já passou há mais de 2 dias: é do ano que vem. */
    private fun fixYear(d: LocalDate, today: LocalDate, noYear: Boolean) = if (noYear && d.isBefore(today.minusDays(2))) d.plusYears(1) else d

    /**
     * Data e hora escolhidas na tela de agendar do Threads. Sem data na tela: hoje (ou amanhã, se a hora já passou).
     * null = não tem hora na tela (não é a tela de agendar).
     */
    fun parseSchedule(texts: List<String>, now: ZonedDateTime): ZonedDateTime? {
        val time = findTime(texts) ?: return null
        var date = findDate(texts, now.toLocalDate()) ?: now.toLocalDate()
        var z = ZonedDateTime.of(date, time, now.zone)
        if (findDate(texts, now.toLocalDate()) == null && z.isBefore(now.minusMinutes(5))) { date = date.plusDays(1); z = ZonedDateTime.of(date, time, now.zone) }
        return z
    }

    fun fmt(at: Long, zone: String = ZoneId.systemDefault().id): String {
        val z = Instant.ofEpochMilli(at).atZone(ZoneId.of(zone))
        val dias = listOf("seg", "ter", "qua", "qui", "sex", "sáb", "dom")
        return dias[z.dayOfWeek.value - 1] + " " + "%02d/%02d às %02d:%02d".format(z.dayOfMonth, z.monthValue, z.hour, z.minute)
    }
}
