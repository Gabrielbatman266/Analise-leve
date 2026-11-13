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

    @Test fun rodinhaDoThreads() {
        val hoje = now.toLocalDate() // 12/11/2026
        assertEquals(LocalDate.of(2026, 11, 22), AgendaCore.findDate(listOf("Sun, Nov 22"), hoje))
        assertEquals(LocalDate.of(2026, 11, 16), AgendaCore.findDate(listOf("Mon, Nov 16"), hoje))
        assertEquals(LocalDate.of(2027, 1, 3), AgendaCore.findDate(listOf("Sun, Jan 3"), hoje)) // virada do ano
        assertEquals(LocalDate.of(2026, 11, 22), AgendaCore.findDate(listOf("dom., 22 de nov."), hoje))
        assertEquals(hoje, AgendaCore.findDate(listOf("Today"), hoje))
        assertEquals(null, AgendaCore.findTime(listOf("Sun, Nov 22"))) // dia sem hora
        assertEquals(null, AgendaCore.findDate(listOf("7"), hoje)) // número da rodinha não é data
        assertEquals(null, AgendaCore.findDate(listOf("00"), hoje))
    }

    // ---------------------------------------------------------------- simulador da tela "Schedule post" do Threads
    /**
     * Rodinhas como no Threads (medidas do print): 5 linhas visíveis, o escolhido na faixa do meio (y=1940),
     * dia começa em HOJE (nada acima do primeiro), minuto 00–59 sem dar a volta, hora 1–12, AM/PM.
     * Cada comando anda 1 linha; no fim da rodinha o comando falha (como aconteceu no celular).
     */
    private class Sim(val today: LocalDate, var day: Int, var hour: Int, var min: Int, var pm: Boolean, val hourWraps: Boolean = false) {
        val band = 1940; val rowH = 103
        private fun fmtDay(d: LocalDate) = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + ", " +
            d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + d.dayOfMonth
        fun screen(): List<AgendaCore.WItem> {
            val out = ArrayList<AgendaCore.WItem>()
            for (k in -2..2) {
                val y = band + k * rowH
                (day + k).takeIf { it in 0..75 }?.let { out += AgendaCore.WItem(fmtDay(today.plusDays(it.toLong())), 375, y) }
                // hora como no Threads: 12, 1, 2 … 11 (posição 0..11; o 12 em cima)
                val pos = (hour % 12) + k
                (if (hourWraps) ((pos % 12) + 12) % 12 else pos.takeIf { it in 0..11 })?.let { out += AgendaCore.WItem((if (it == 0) 12 else it).toString(), 600, y) }
                (min + k).takeIf { it in 0..59 }?.let { out += AgendaCore.WItem("%02d".format(it), 724, y) }
            }
            out += AgendaCore.WItem(if (pm) "PM" else "AM", 867, band)
            out += AgendaCore.WItem(if (pm) "AM" else "PM", 867, if (pm) band - rowH else band + rowH)
            // coisas do post que ficam atrás da folha, na mesma altura (não podem confundir)
            out += AgendaCore.WItem("Edit", 280, 1919); out += AgendaCore.WItem("Add to thread", 300, 2219)
            return out
        }
        /** Um comando de rolar: true se andou. */
        fun step(col: Int, dir: Int): Boolean = when (col) {
            0 -> (day + dir in 0..75).also { if (it) day += dir }
            1 -> { val p = hour % 12 + dir; if (hourWraps) { val q = ((p % 12) + 12) % 12; hour = if (q == 0) 12 else q; true } else (p in 0..11).also { if (it) hour = if (p == 0) 12 else p } }
            2 -> (min + dir in 0..59).also { if (it) min += dir }
            else -> (if (dir > 0) !pm else pm).also { if (it) pm = !pm }
        }
    }

    private fun drive(sim: Sim, target: ZonedDateTime): Int {
        var steps = 0; val noWrap = HashSet<Int>()
        while (steps < 400) {
            val r = AgendaCore.wheelRead(sim.screen(), sim.band, 124, sim.today) ?: throw AssertionError("não reconheceu as rodinhas")
            val next = AgendaCore.wheelNext(r, target, noWrap) ?: return steps
            // igual o robô: mandou andar e nada mudou → aquela rodinha não dá a volta
            if (!sim.step(next.first, if (next.second > 0) 1 else -1)) noWrap += next.first
            steps++
        }
        throw AssertionError("não chegou no alvo: " + AgendaCore.wheelRead(sim.screen(), sim.band, 124, sim.today))
    }

    @Test fun rodinhasLeituraDaFaixaDoMeio() {
        // exatamente a tela do print: Sun, Nov 22 · 7 · 00 · PM
        val hoje = LocalDate.of(2026, 11, 12)
        val sim = Sim(hoje, day = 10, hour = 7, min = 0, pm = true)
        val r = AgendaCore.wheelRead(sim.screen(), sim.band, 124, hoje)!!
        assertEquals(LocalDate.of(2026, 11, 22), r.date); assertEquals(7, r.hour); assertEquals(0, r.minute); assertEquals(true, r.pm)
        assertEquals(103, r.rowH); assertEquals(listOf(375, 600, 724, 867), r.colX)
    }

    @Test fun rodinhasCasoQueTravouNoCelular() {
        // rodinha em HOJE (nada acima), alvo hoje 18:47: antes ele tentava voltar, falhava e girava o dia pra frente sem parar
        val hoje = LocalDate.of(2026, 11, 12)
        val sim = Sim(hoje, day = 0, hour = 7, min = 0, pm = true)
        val alvo = ZonedDateTime.of(2026, 11, 12, 18, 47, 0, 0, ZoneId.of(sp))
        drive(sim, alvo)
        assertEquals(0, sim.day); assertEquals(6, sim.hour); assertEquals(47, sim.min); assertEquals(true, sim.pm)
        // e saindo de onde ele tinha parado (Sun, Nov 22): volta 10 dias
        val sim2 = Sim(hoje, day = 10, hour = 7, min = 0, pm = true)
        drive(sim2, alvo)
        assertEquals(0, sim2.day); assertEquals(6, sim2.hour); assertEquals(47, sim2.min)
    }

    @Test fun rodinhasChegamEmQualquerHorario() {
        val hoje = LocalDate.of(2026, 11, 12); val rnd = java.util.Random(7)
        repeat(400) { n ->
            val sim = Sim(hoje, rnd.nextInt(20), 1 + rnd.nextInt(12), rnd.nextInt(60), rnd.nextBoolean(), hourWraps = n % 2 == 0)
            val alvo = ZonedDateTime.of(hoje.plusDays(rnd.nextInt(30).toLong()), LocalTime.of(rnd.nextInt(24), rnd.nextInt(60)), ZoneId.of(sp))
            drive(sim, alvo)
            val h24 = (sim.hour % 12) + (if (sim.pm) 12 else 0)
            assertEquals("caso $n", alvo.toLocalDate(), hoje.plusDays(sim.day.toLong()))
            assertEquals("caso $n", alvo.hour, h24); assertEquals("caso $n", alvo.minute, sim.min)
        }
    }

    @Test fun rodinhasCasoDoPrintDas23h() {
        // tela do 2º print: Fri, Nov 13 · 12 · 00 · AM (tudo no topo), alvo sex 13/11 09:40 — antes travava no 12
        val hoje = LocalDate.of(2026, 11, 12)
        val sim = Sim(hoje, day = 1, hour = 12, min = 0, pm = false)
        val r = AgendaCore.wheelRead(sim.screen(), sim.band, 124, hoje)!!
        assertEquals(12, r.hour); assertEquals(false, r.pm); assertEquals(LocalDate.of(2026, 11, 13), r.date)
        drive(sim, ZonedDateTime.of(2026, 11, 13, 9, 40, 0, 0, ZoneId.of(sp)))
        assertEquals(9, sim.hour); assertEquals(40, sim.min); assertEquals(false, sim.pm); assertEquals(1, sim.day)
    }

    @Test fun rodinhasMeiaNoiteEMeioDia() {
        val hoje = LocalDate.of(2026, 11, 12)
        for ((h, wantH, wantPm) in listOf(Triple(0, 12, false), Triple(12, 12, true), Triple(23, 11, true), Triple(1, 1, false))) {
            val sim = Sim(hoje, 3, 7, 30, true)
            drive(sim, ZonedDateTime.of(hoje.plusDays(1), LocalTime.of(h, 5), ZoneId.of(sp)))
            assertEquals(wantH, sim.hour); assertEquals(wantPm, sim.pm); assertEquals(5, sim.min); assertEquals(1, sim.day)
        }
    }

    @Test fun linhaDoPostAgendado() {
        assertEquals(ZonedDateTime.of(2026, 11, 12, 19, 0, 0, 0, ZoneId.of(sp)), AgendaCore.parsePostingRow("Today 7 PM", now))
        assertEquals(ZonedDateTime.of(2026, 11, 12, 18, 47, 0, 0, ZoneId.of(sp)), AgendaCore.parsePostingRow("Today 6:47 PM", now))
        assertEquals(ZonedDateTime.of(2026, 11, 22, 9, 5, 0, 0, ZoneId.of(sp)), AgendaCore.parsePostingRow("Sun, Nov 22 9:05 AM", now))
        assertEquals(ZonedDateTime.of(2026, 11, 13, 0, 0, 0, 0, ZoneId.of(sp)), AgendaCore.parsePostingRow("Tomorrow 12 AM", now))
        assertEquals(null, AgendaCore.parsePostingRow("Posting", now))
        // dentro da semana o Threads mostra só o dia da semana (antes a conferência pulava esses)
        assertEquals(ZonedDateTime.of(2026, 11, 13, 9, 40, 0, 0, ZoneId.of(sp)), AgendaCore.parsePostingRow("Fri 9:40 AM", now))
        assertEquals(ZonedDateTime.of(2026, 11, 16, 21, 0, 0, 0, ZoneId.of(sp)), AgendaCore.parsePostingRow("Mon 9 PM", now))
        assertEquals(ZonedDateTime.of(2026, 11, 14, 20, 15, 0, 0, ZoneId.of(sp)), AgendaCore.parsePostingRow("sáb 20:15", now))
        assertEquals(null, AgendaCore.parsePostingRow("Edit 9 PM", now))
    }

    // ---------------------------------------------------------------- aprendizado por recompensa
    /**
     * Rodinha "escorregadia": mandar muitas linhas de uma vez faz ela andar a mais (passa do ponto) — o que achamos que
     * fazia o 3º post "voltar a data". O aprendizado tem que descobrir sozinho quantas linhas mexer por vez.
     */
    @Test fun recompensaAprendeANaoPassarDoPonto() {
        val rnd = java.util.Random(3)
        val b = AgendaCore.Bandit(intArrayOf(1, 2, 4, 8, 12))
        fun slide(k: Int) = if (k >= 4) k + k / 3 else k // 4 → 5, 8 → 10, 12 → 16 (escorrega)
        var totalSteps = 0; var lastSteps = 0
        repeat(300) { ep ->
            var d = 1 + rnd.nextInt(30); var steps = 0
            while (d != 0 && steps < 80) {
                val key = AgendaCore.wheelKey(0, d)
                val idx = b.choose(key, { it <= Math.abs(d) }, rnd)
                val k = b.arms[idx]; val moved = slide(k)
                val after = if (d > 0) d - moved else d + moved
                b.update(key, idx, AgendaCore.wheelReward(d, after, k))
                d = after; steps++
            }
            assertEquals("episódio $ep não chegou", 0, d)
            if (ep >= 250) lastSteps += steps
            totalSteps += steps
        }
        // longe (9+): aprendeu que 8/12 escorregam e passam; perto (1): mexe 1
        assertEquals(1, b.best(AgendaCore.wheelKey(0, 1)))
        assertTrue("passos médios no fim: ${lastSteps / 50.0}", lastSteps / 50.0 < 8.0)
        // salva e carrega igual
        val b2 = AgendaCore.Bandit.load(b.arms, b.save())
        assertEquals(b.best(AgendaCore.wheelKey(0, 20)), b2.best(AgendaCore.wheelKey(0, 20)))
    }

    @Test fun recompensaDensa() {
        assertTrue(AgendaCore.wheelReward(10, 6, 4) > 0.9)      // aproximou certinho
        assertTrue(AgendaCore.wheelReward(3, -2, 4) < -1.5)     // passou do ponto: castigo
        assertTrue(AgendaCore.wheelReward(5, 5, 2) < 0)         // não mexeu
        assertTrue(AgendaCore.wheelReward(4, 6, 2) < 0)         // voltou
    }
}
