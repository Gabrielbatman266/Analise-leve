package com.leve.analise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AgendaCoreTest {
    private val sp = "America/Sao_Paulo"
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, z: String = sp) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of(z)).toInstant().toEpochMilli()

    @Test fun naoRepete() {
        val r = AgendaCore.Rule("nenhuma", times = listOf("14:30"), start = LocalDate.of(2026, 11, 12))
        val o = AgendaCore.expand(r, 0, Long.MAX_VALUE)
        assertEquals(1, o.size); assertEquals(ms(2026, 11, 12, 14, 30), o[0].at); assertEquals("2026-11-12T14:30", o[0].key)
    }

    @Test fun diariaComVariosHorariosEFim() {
        val r = AgendaCore.Rule("diaria", times = listOf("20:00", "12:00"), start = LocalDate.of(2026, 11, 12), end = LocalDate.of(2026, 11, 14))
        val o = AgendaCore.expand(r, 0, Long.MAX_VALUE)
        assertEquals(6, o.size)
        assertEquals(listOf("2026-11-12T12:00", "2026-11-12T20:00", "2026-11-13T12:00", "2026-11-13T20:00", "2026-11-14T12:00", "2026-11-14T20:00"), o.map { it.key })
    }

    @Test fun aCadaDoisDiasComContagem() {
        val r = AgendaCore.Rule("diaria", every = 2, times = listOf("09:00"), start = LocalDate.of(2026, 11, 1), count = 3)
        assertEquals(listOf("2026-11-01T09:00", "2026-11-03T09:00", "2026-11-05T09:00"), AgendaCore.expand(r, 0, Long.MAX_VALUE).map { it.key })
    }

    @Test fun semanalDiasEscolhidos() {
        // 12/11/2026 é quinta. Seg(1), qua(3), sex(5) até 22/11
        val r = AgendaCore.Rule("semanal", days = setOf(1, 3, 5), times = listOf("19:00"), start = LocalDate.of(2026, 11, 12), end = LocalDate.of(2026, 11, 22))
        assertEquals(listOf("2026-11-13T19:00", "2026-11-16T19:00", "2026-11-18T19:00", "2026-11-20T19:00"), AgendaCore.expand(r, 0, Long.MAX_VALUE).map { it.key })
    }

    @Test fun semanalACadaDuasSemanas() {
        val r = AgendaCore.Rule("semanal", every = 2, days = setOf(4), times = listOf("10:00"), start = LocalDate.of(2026, 11, 12), count = 3)
        assertEquals(listOf("2026-11-12T10:00", "2026-11-26T10:00", "2026-12-10T10:00"), AgendaCore.expand(r, 0, Long.MAX_VALUE).map { it.key })
    }

    @Test fun aCadaTresHoras() {
        val r = AgendaCore.Rule("horas", every = 3, times = listOf("21:00"), start = LocalDate.of(2026, 11, 12), count = 4)
        assertEquals(listOf("2026-11-12T21:00", "2026-11-13T00:00", "2026-11-13T03:00", "2026-11-13T06:00"), AgendaCore.expand(r, 0, Long.MAX_VALUE).map { it.key })
    }

    @Test fun janelaEExcecoes() {
        val r = AgendaCore.Rule("diaria", times = listOf("20:00"), start = LocalDate.of(2026, 11, 1))
        val o = AgendaCore.expand(r, ms(2026, 11, 10, 0, 0), ms(2026, 11, 13, 23, 59), exceptions = setOf("2026-11-11T20:00"))
        assertEquals(listOf("2026-11-10T20:00", "2026-11-12T20:00", "2026-11-13T20:00"), o.map { it.key })
    }

    @Test fun relogioDeParedeNoHorarioDeVerao() {
        // Nova York muda pro horário de inverno em 01/11/2026: 20:00 continua 20:00 no relógio
        val r = AgendaCore.Rule("diaria", times = listOf("20:00"), start = LocalDate.of(2026, 10, 31), count = 2, zone = "America/New_York")
        val o = AgendaCore.expand(r, 0, Long.MAX_VALUE)
        assertEquals(ms(2026, 10, 31, 20, 0, "America/New_York"), o[0].at)
        assertEquals(ms(2026, 11, 1, 20, 0, "America/New_York"), o[1].at)
        assertEquals(25 * 3600_000L, o[1].at - o[0].at)
    }

    @Test fun conflitos() {
        val c = AgendaCore.conflicts(listOf(AgendaCore.Slot("a", 0), AgendaCore.Slot("b", 10 * 60_000L), AgendaCore.Slot("c", 60 * 60_000L)), 20)
        assertEquals(setOf("a", "b"), c)
    }

    @Test fun statusEfetivo() {
        val at = 1_000_000_000L
        assertEquals(AgendaCore.AGENDADA, AgendaCore.effective(AgendaCore.AGENDADA, "api", at, at + 5 * 60_000L))
        assertEquals(AgendaCore.ATRASADA, AgendaCore.effective(AgendaCore.AGENDADA, "api", at, at + 11 * 60_000L))
        assertEquals(AgendaCore.PENDENTE, AgendaCore.effective(AgendaCore.PENDENTE, "lembrete", at, at + 20 * 60_000L))
        assertEquals(AgendaCore.ATRASADA, AgendaCore.effective(AgendaCore.PENDENTE, "lembrete", at, at + 31 * 60_000L))
        assertEquals(AgendaCore.PUBLICADA, AgendaCore.effective(AgendaCore.PUBLICADA, "api", at, at + 99 * 3600_000L))
    }

    private val now = ZonedDateTime.of(2026, 11, 12, 11, 0, 0, 0, ZoneId.of(sp))

    @Test fun telaEmIngles() {
        val z = AgendaCore.parseSchedule(listOf("Schedule", "Date", "Thu, Nov 12", "Time", "2:30 PM", "Schedule"), now)
        assertEquals(ZonedDateTime.of(2026, 11, 12, 14, 30, 0, 0, ZoneId.of(sp)), z)
    }

    @Test fun telaEmPortugues() {
        val z = AgendaCore.parseSchedule(listOf("Agendar", "Data", "13 de nov.", "Horário", "21:15"), now)
        assertEquals(ZonedDateTime.of(2026, 11, 13, 21, 15, 0, 0, ZoneId.of(sp)), z)
    }

    @Test fun hojeAmanhaEBarras() {
        assertEquals(LocalDate.of(2026, 11, 13), AgendaCore.findDate(listOf("Amanhã"), now.toLocalDate()))
        assertEquals(LocalDate.of(2026, 11, 12), AgendaCore.findDate(listOf("Today"), now.toLocalDate()))
        assertEquals(LocalDate.of(2026, 12, 3), AgendaCore.findDate(listOf("03/12"), now.toLocalDate()))
        assertEquals(LocalDate.of(2027, 1, 5), AgendaCore.findDate(listOf("Jan 5"), now.toLocalDate())) // sem ano e já passou: ano que vem
        assertEquals(LocalTime.of(0, 15), AgendaCore.findTime(listOf("12:15 AM")))
        assertEquals(LocalTime.of(12, 5), AgendaCore.findTime(listOf("12:05 PM")))
        assertEquals(LocalTime.of(14, 30), AgendaCore.findTime(listOf("14h30")))
    }

    @Test fun semDataEHoraJaPassou() {
        val z = AgendaCore.parseSchedule(listOf("Schedule", "9:00 AM"), now)
        assertEquals(ZonedDateTime.of(2026, 11, 13, 9, 0, 0, 0, ZoneId.of(sp)), z)
    }

    @Test fun semHoraNaoETelaDeAgendar() {
        assertNull(AgendaCore.parseSchedule(listOf("New thread", "What's new?", "Add AI label"), now))
        assertNotNull(AgendaCore.parseTime("07:05")); assertNull(AgendaCore.parseTime("25:00"))
        assertTrue(AgendaCore.fmt(ms(2026, 11, 12, 14, 30), sp).startsWith("qui 12/11 às 14:30"))
    }

    @Test fun horaEmPedacosJuntados() {
        // seletor que mostra hora e minuto em textos separados: o leitor junta tudo
        val z = AgendaCore.parseSchedule(listOf("Schedule", "Thu, Nov 12", "2", ":", "30", "PM", "Thu, Nov 12 2 : 30 PM"), now)
        assertEquals(ZonedDateTime.of(2026, 11, 12, 14, 30, 0, 0, ZoneId.of(sp)), z)
    }

    @Test fun avisoDeAgendado() {
        val z = AgendaCore.parseSchedule(listOf("Seu thread foi agendado para 14 de nov. às 09:05"), now)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 9, 5, 0, 0, ZoneId.of(sp)), z)
        val e = AgendaCore.parseSchedule(listOf("Your thread is scheduled for tomorrow at 8:00 AM"), now)
        assertEquals(ZonedDateTime.of(2026, 11, 13, 8, 0, 0, 0, ZoneId.of(sp)), e)
    }

    @Test fun naoConfundeContagemComHora() {
        // "12 h" (tempo do post), "1,2 mil curtidas", "3/4" de carrossel não são horário
        assertEquals(null, AgendaCore.findTime(listOf("12 h", "1,2 mil curtidas", "3/4")))
        assertEquals(null, AgendaCore.findDate(listOf("1/2", "3/4"), now.toLocalDate()))
        assertEquals(LocalDate.of(2026, 12, 5), AgendaCore.findDate(listOf("1/2", "5/12/2026"), now.toLocalDate()))
    }
}
