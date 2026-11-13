package com.leve.analise

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Telas DE VERDADE do Agendar no Xiaomi do usuário (árvore salva pelo 📋 diagnóstico). A árvore guarda o canto esquerdo de
 * cada texto; o centro é estimado pela largura (dígito ~25 px, letra da data ~18 px).
 */
class TelaRealTest {
    private fun cx(left: Int, t: String) = left + t.length * (if (t.all { it.isDigit() } || t.length <= 2) 25 else 18) / 2
    private fun items(raw: List<Triple<String, Int, Int>>) = raw.map { (t, x, y) -> AgendaCore.WItem(t, cx(x, t), y + 27) }

    /** 13/11 13:45: "rodinhas: não reconheci as colunas" — hora "1..5" (1 dígito) a ~130 px do minuto: com 45 dp as colunas se juntavam. */
    private val tela1345 = items(listOf(
        Triple("Sun, Nov 15", 287, 1802), Triple("Mon, Nov 16", 275, 1910), Triple("Tue, Nov 17", 290, 2018), Triple("Wed, Nov 18", 277, 2126), Triple("Thu, Nov 19", 287, 2234),
        Triple("1", 621, 1802), Triple("2", 621, 1910), Triple("3", 621, 2018), Triple("4", 621, 2126), Triple("5", 621, 2234),
        Triple("00", 740, 2018), Triple("01", 740, 2126), Triple("02", 740, 2234), Triple("AM", 882, 1910), Triple("PM", 883, 2018)))
    private val rodinhas1345 = listOf(227..550, 550..692, 692..834, 834..1000)
    private val hoje = LocalDate.of(2026, 11, 13)

    @Test fun lePelasRodinhas() {
        for (density in listOf(2.75, 3.0, 3.5)) {
            val r = AgendaCore.wheelRead(tela1345, 2045, (45 * density).toInt(), hoje, rodinhas1345)!!
            assertEquals("dia 2026-11-17 hora 3 min 0 PM", r.toString())
        }
    }

    @Test fun leSemAsRodinhas() {
        for (density in listOf(2.75, 3.0, 3.5)) {
            val r = AgendaCore.wheelRead(tela1345, 2045, (45 * density).toInt(), hoje)
            assertEquals("densidade $density", "dia 2026-11-17 hora 3 min 0 PM", r.toString())
        }
    }
}
