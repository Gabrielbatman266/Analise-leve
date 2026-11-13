package com.leve.analise

import java.time.ZonedDateTime

/**
 * 🧠 Cérebro da tela "Schedule post" do Threads (Kotlin puro: o mesmo código roda no celular e no simulador de treino).
 *
 * A cada leitura da tela ele decide UMA ação. Tem 3 jeitos de mexer cada rodinha e aprende qual funciona em cada coluna:
 *   0 = comando de rolar (acessibilidade), 1 = TOCAR no número que quer (se está visível), 2 = arrastar com o dedo.
 * Recompensa: aproximou do alvo (+), passou do ponto (−−), não mexeu (−1), cada espera à toa (−pouco).
 * Done: só toca com a data/hora certa lida duas vezes (rodinha parada) e o botão branco; aprende quanto esperar.
 */
class SheetPilot(
    val target: ZonedDateTime,
    val wb: AgendaCore.Bandit,          // linhas por vez (comando): 1, 2, 4, 8, 12
    val db: AgendaCore.Bandit,          // espera antes do Done (ms): 300, 600, 1000, 1600
    private val maxRows: (Int) -> Int,
    private val rnd: java.util.Random = java.util.Random(),
    private val explore: Double = 0.05,
    val hb: AgendaCore.Bandit = AgendaCore.Bandit(intArrayOf(0, 1, 2)), // jeito: 0 comando, 1 tocar no número, 2 arrastar
) {
    sealed class Act {
        /** Mexer a coluna [col] ([dir] +1 = valor maior) [rows] linhas pelo jeito [how] (0 comando, 1 tocar, 2 arrastar). */
        data class Scroll(val col: Int, val dir: Int, val rows: Int, val how: Int) : Act()
        data class Wait(val ms: Long) : Act()
        object Look : Act()
        object TapDone : Act()
        data class Fail(val why: String) : Act()
    }
    companion object { val HOW = listOf("comando", "tocar no número", "arrastar") }

    private data class Pend(val col: Int, val before: Int, val arm: Int, val key: String, val how: Int, val rows: Int)
    val noWrap = HashSet<Int>()
    private val bad = HashMap<Int, MutableSet<Int>>()      // jeitos que não funcionaram nesta coluna (neste post)
    private val fails = HashMap<String, Int>()             // "col:jeito" → falhas seguidas
    private var pend: Pend? = null
    private var curSig = ""; private var curSince = 0L
    private var awaiting = false; private var preSig = ""; private var scrollAt = 0L
    var stableMs = 250L; var motionTimeoutMs = 1800L
    private var okSig = ""; private var okAt = 0L
    private var doneArm = -1; private var judgeArm = -1
    private var gray = 0; private var unread = 0; private var scrolls = 0; private var startAt = 0L
    private var lumSig = ""; private var lum: Int? = null
    var score = 0.0; private set
    val log = ArrayList<String>()
    private fun note(s: String) { log += s; if (log.size > 80) log.removeAt(0) }

    fun setLum(sig: String, v: Int?) { lumSig = sig; lum = v }

    /** O comando/gesto foi recusado (fim da rodinha). */
    fun scrollRefused(col: Int) { noWrap += col; pend = null; awaiting = false; note("coluna $col: fim da rodinha") }

    fun doneResult(ok: Boolean) {
        if (judgeArm >= 0) { val r = if (ok) 3.0 else -3.0; db.update("done", judgeArm, r); score += r }
        judgeArm = -1; doneArm = -1; okSig = ""
        note(if (ok) "Done certo +3" else "Done errado −3")
    }

    private fun idle(ms: Long): Act { score -= 0.02; return Act.Wait(ms) } // espera custa um pouquinho

    fun step(r: AgendaCore.WRead?, now: Long, doneEnabled: Boolean, canLook: Boolean): Act {
        if (startAt == 0L) startAt = now
        if (now - startAt > 240_000L) return Act.Fail("demorou mais de 4 min")
        if (scrolls > 250) return Act.Fail("passos demais")
        if (r == null) { if (++unread > 12) return Act.Fail("não reconheci as rodinhas"); return idle(300) }
        unread = 0
        val sig = r.toString()
        if (sig != curSig) { curSig = sig; curSince = now }
        if (awaiting) {
            if (sig == preSig && now - scrollAt < motionTimeoutMs) return idle(150)        // ainda não mexeu
            if (sig != preSig && now - curSince < stableMs) return idle(150)               // mexendo: espera parar
            pend?.let { p -> AgendaCore.wheelDiff(r, target, p.col, noWrap)?.let { d -> if (Math.abs(p.before - d) < p.rows && now - scrollAt < motionTimeoutMs) return idle(150) } }
            awaiting = false
        } else if (now - curSince < stableMs) return idle(150)

        // 🎯 nota do último passo (no jeito usado e nas linhas por vez)
        pend?.let { p ->
            val after = AgendaCore.wheelDiff(r, target, p.col, noWrap)
            if (after != null) {
                val rw = AgendaCore.wheelReward(p.before, after, p.rows)
                // a nota das "linhas por vez" só conta quando mexeu (comando ignorado é culpa do jeito, não do tamanho do passo)
                hb.update("c${p.col}", p.how, rw); if (p.how == 0 && after != p.before) wb.update(p.key, p.arm, rw); score += rw
                val k = "${p.col}:${p.how}"
                if (after == p.before) {
                    val f = (fails[k] ?: 0) + 1; fails[k] = f
                    note("coluna ${p.col}: ${HOW[p.how]} não mexeu (${f}x)")
                    if (f >= 2) { bad.getOrPut(p.col) { HashSet() } += p.how; note("coluna ${p.col}: troco o jeito (${HOW[p.how]} não funciona aqui)") }
                } else fails.remove(k)
                if (after != 0 && (after > 0) != (p.before > 0)) note("passou do ponto na coluna ${p.col}: ${p.before} → $after")
            }
            pend = null
        }

        val next = AgendaCore.wheelNext(r, target, noWrap)
        if (next == null) {
            if (doneArm < 0) doneArm = db.choose("done", { true }, rnd, explore)
            val wait = db.arms[doneArm].toLong()
            if (okSig != sig) { okSig = sig; okAt = now; return idle(wait) }
            if (now - okAt < wait) return idle(wait - (now - okAt))
            if (!doneEnabled) return grayDone("Done desligado")
            if (canLook && lumSig != sig) return Act.Look
            if (lumSig == sig && (lum ?: 255) < 200) { lumSig = ""; return grayDone("Done cinza ($lum)") }
            judgeArm = doneArm
            note("certo e parado ($sig) → Done")
            return Act.TapDone
        }
        okSig = ""
        val (col, diff) = next
        val badHere = bad[col] ?: emptySet<Int>()
        if (badHere.size >= 3) return Act.Fail("a rodinha $col não anda de jeito nenhum")
        // jeito: o de melhor nota nesta coluna (tocar = no número 1 ou 2 linhas pra lá, que sempre está visível)
        val how = hb.choose("c$col", { h -> h !in badHere }, rnd, explore)
        val rows: Int; val arm: Int; val key = AgendaCore.wheelKey(col, diff)
        when (how) {
            0 -> { arm = wb.choose(key, { it <= Math.abs(diff) && it <= maxRows(col) }, rnd, explore); rows = wb.arms[arm] }
            1 -> { arm = 0; rows = minOf(Math.abs(diff), 2) }
            else -> { arm = 0; rows = minOf(Math.abs(diff), 3) }
        }
        pend = Pend(col, diff, arm, key, how, rows)
        awaiting = true; preSig = sig; scrollAt = now; scrolls++
        return Act.Scroll(col, if (diff > 0) 1 else -1, rows, how)
    }

    private fun grayDone(why: String): Act {
        gray++; okSig = ""
        if (doneArm >= 0) { db.update("done", doneArm, -1.0); score -= 1 }
        note("$why: não toquei")
        return if (gray >= 5) Act.Fail("o Threads não aceita esse horário") else Act.Wait(500)
    }
}
