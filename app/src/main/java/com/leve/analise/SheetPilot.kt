package com.leve.analise

import java.time.ZonedDateTime

/**
 * 🧠 Cérebro da tela "Schedule post" do Threads (Kotlin puro: o mesmo código roda no celular e no simulador de treino).
 *
 * A cada leitura da tela ele decide UMA ação: rolar uma rodinha N linhas, esperar, olhar a cor do Done, tocar no Done ou desistir.
 * Aprende por recompensa:
 *  - rodinhas: quantas linhas mexer por vez em cada situação (aproximou +, passou do ponto −−, voltou/não mexeu −);
 *  - Done: quanto esperar a rodinha parar antes de tocar (Done certo +3, errado −3, cinza −1).
 * Trava: só toca no Done quando LÊ a data/hora certa duas vezes seguidas com a espera escolhida entre elas e o Done não está cinza.
 */
class SheetPilot(
    val target: ZonedDateTime,
    val wb: AgendaCore.Bandit,          // linhas por vez: braços 1, 2, 4, 8, 12
    val db: AgendaCore.Bandit,          // espera antes do Done (ms): braços 300, 600, 1000, 1600
    private val maxRows: (Int) -> Int,  // limite da velocidade escolhida, por coluna
    private val rnd: java.util.Random = java.util.Random(),
    private val explore: Double = 0.05,
) {
    sealed class Act {
        /** Rolar a coluna [col] ([dir] +1 = valor maior) [rows] linhas; [gesture] = arrastar com o dedo em vez do comando. */
        data class Scroll(val col: Int, val dir: Int, val rows: Int, val gesture: Boolean) : Act()
        data class Wait(val ms: Long) : Act()
        object Look : Act()      // tirar print e medir a cor do Done → [lum]
        object TapDone : Act()
        data class Fail(val why: String) : Act()
    }

    private data class Pend(val col: Int, val before: Int, val arm: Int, val key: String)
    val noWrap = HashSet<Int>()
    private val gesture = HashSet<Int>()
    private val stuck = HashMap<Int, Int>()
    private var pend: Pend? = null
    private var lastSig = ""; private var lastWasScroll = false; private var lastScrollCol = -1
    // tela parada: a mesma leitura por [stableMs]; depois de rolar, espera MUDAR e PARAR antes de decidir de novo
    private var curSig = ""; private var curSince = 0L
    private var awaiting = false; private var preSig = ""; private var scrollAt = 0L
    var stableMs = 250L; var motionTimeoutMs = 1800L
    private var okSig = ""; private var okAt = 0L
    private var doneArm = -1; private var judgeArm = -1
    private var gray = 0; private var unread = 0; private var scrolls = 0; private var startAt = 0L
    private var lumSig = ""; private var lum: Int? = null
    var score = 0.0; private set
    val log = ArrayList<String>()
    private fun note(s: String) { log += s; if (log.size > 60) log.removeAt(0) }

    /** Resultado da medição de cor (null = não deu pra medir). */
    fun setLum(sig: String, v: Int?) { lumSig = sig; lum = v }

    /** O comando de rolar não andou (fim da rodinha): vai pelo outro lado. */
    fun scrollRefused(col: Int) { noWrap += col; pend = null; note("coluna $col: fim da rodinha") }

    /** O que aconteceu depois do Done (folha fechou e o post mostra o horário certo = true). */
    fun doneResult(ok: Boolean) {
        if (judgeArm >= 0) { val r = if (ok) 3.0 else -3.0; db.update("done", judgeArm, r); score += r }
        judgeArm = -1; doneArm = -1; okSig = ""
        note(if (ok) "Done certo +3" else "Done errado −3")
    }

    fun step(r: AgendaCore.WRead?, now: Long, doneEnabled: Boolean, canLook: Boolean): Act {
        if (startAt == 0L) startAt = now
        if (now - startAt > 240_000L) return Act.Fail("demorou mais de 4 min")
        if (scrolls > 250) return Act.Fail("passos demais")
        if (r == null) { if (++unread > 12) return Act.Fail("não reconheci as rodinhas"); return Act.Wait(300) }
        unread = 0
        val sig = r.toString()
        if (sig != curSig) { curSig = sig; curSince = now }
        if (awaiting) {
            if (sig == preSig && now - scrollAt < motionTimeoutMs) return Act.Wait(150)   // ainda não começou a mexer
            if (sig != preSig && now - curSince < stableMs) return Act.Wait(150)          // mexendo: espera parar
            // mandou andar N linhas e andou menos? ainda está animando (ou bateu no fim): espera até o tempo máximo
            pend?.let { p -> AgendaCore.wheelDiff(r, target, p.col, noWrap)?.let { d -> if (Math.abs(p.before - d) < wb.arms[p.arm] && now - scrollAt < motionTimeoutMs) return Act.Wait(150) } }
            awaiting = false
        } else if (now - curSince < stableMs) return Act.Wait(150)                         // tela acabou de mudar: espera parar
        // recompensa do passo anterior
        pend?.let { p ->
            AgendaCore.wheelDiff(r, target, p.col, noWrap)?.let { after ->
                val rw = AgendaCore.wheelReward(p.before, after, wb.arms[p.arm]); wb.update(p.key, p.arm, rw); score += rw
                if (after != 0 && (after > 0) != (p.before > 0)) note("passou do ponto na coluna ${p.col}: ${p.before} → $after")
            }
            pend = null
        }
        // mandou rolar e a tela não mudou: conta como travada (3x seguidas → passa a arrastar; 6x → desiste)
        if (lastWasScroll && sig == preSig) {
            val k = (stuck[lastScrollCol] ?: 0) + 1; stuck[lastScrollCol] = k
            if (k == 3) { gesture += lastScrollCol; note("coluna $lastScrollCol não anda com o comando: vou arrastar") }
            if (k >= 6) return Act.Fail("a rodinha $lastScrollCol não anda")
        } else if (lastWasScroll) stuck.remove(lastScrollCol)
        lastSig = sig; lastWasScroll = false

        val next = AgendaCore.wheelNext(r, target, noWrap)
        if (next == null) {
            if (doneArm < 0) doneArm = db.choose("done", { true }, rnd, explore)
            val wait = db.arms[doneArm].toLong()
            if (okSig != sig) { okSig = sig; okAt = now; return Act.Wait(wait) }       // 1ª leitura certa: espera parar
            if (now - okAt < wait) return Act.Wait(wait - (now - okAt))
            if (!doneEnabled) return grayDone("Done desligado")
            if (canLook && lumSig != sig) return Act.Look                              // olha a cor do Done
            if (lumSig == sig && (lum ?: 255) < 200) { lumSig = ""; return grayDone("Done cinza ($lum)") }
            judgeArm = doneArm
            note("certo e parado ($sig) → Done")
            return Act.TapDone
        }
        okSig = ""
        val (col, diff) = next
        val key = AgendaCore.wheelKey(col, diff)
        val arm = wb.choose(key, { it <= Math.abs(diff) && it <= maxRows(col) }, rnd, explore)
        pend = Pend(col, diff, arm, key)
        lastWasScroll = true; lastScrollCol = col
        awaiting = true; preSig = sig; scrollAt = now; scrolls++
        return Act.Scroll(col, if (diff > 0) 1 else -1, wb.arms[arm], col in gesture)
    }

    private fun grayDone(why: String): Act {
        gray++; okSig = ""
        if (doneArm >= 0) { db.update("done", doneArm, -1.0); score -= 1 }
        note("$why: não toquei")
        return if (gray >= 5) Act.Fail("o Threads não aceita esse horário") else Act.Wait(500)
    }
}
