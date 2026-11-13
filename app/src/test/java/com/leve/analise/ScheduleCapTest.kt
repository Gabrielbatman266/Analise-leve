package com.leve.analise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 📅 Bot do limite de 25 agendados do Threads: conta, para no 25 e sabe quando volta. Simula dias de lote. */
class ScheduleCapTest {
    private val min = 60_000L
    @Test fun contaSoOsQueAindaVaoSair() {
        val now = 1_000_000_000_000L
        val p = ScheduleCapCore.plan(listOf(now - 10 * min, now + 30 * min, now + 30 * min, now + 90 * min), now)   // 1 já saiu, 1 repetido
        assertEquals(2, p.waiting); assertEquals(23, p.free); assertEquals(null, p.resumeAt)
    }
    @Test fun cheioVoltaQuandoOMaisCedoSai() {
        val now = 1_000_000_000_000L
        val l = (1..25).map { now + it * 40 * min }
        val p = ScheduleCapCore.plan(l, now)
        assertEquals(0, p.free); assertEquals(now + 40 * min + 3 * min, p.resumeAt)
        // depois que ele sai, abre 1 vaga
        assertEquals(1, ScheduleCapCore.plan(l, p.resumeAt!!).free)
    }
    /** Simulação: lote de 60 posts (3 dias), o robô só agenda quando cabe, nunca passa de 25 e termina tudo. */
    @Test fun loteGrandeNuncaPassaDe25() {
        val r = java.util.Random(25); var now = 1_000_000_000_000L
        val want = (1..60).map { now + 30 * min + it * (25 + r.nextInt(50)) * min }.toMutableList()
        val sent = ArrayList<Long>(); var maxWaiting = 0; var wakeups = 0
        while (want.isNotEmpty()) {
            val p = ScheduleCapCore.plan(sent, now)
            if (p.free > 0) { sent += want.removeAt(0); now += 15_000L }               // agenda 1 (15 s)
            else { now = p.resumeAt!!; wakeups++ }                                       // dorme até abrir vaga
            maxWaiting = maxOf(maxWaiting, ScheduleCapCore.plan(sent, now).waiting)
            assertTrue("passou de 25: $maxWaiting", maxWaiting <= 25)
        }
        println("📅 60 posts agendados · no máximo $maxWaiting esperando ao mesmo tempo · voltou sozinho $wakeups vez(es)")
    }
}
