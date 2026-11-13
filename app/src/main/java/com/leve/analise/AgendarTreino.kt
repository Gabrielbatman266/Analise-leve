package com.leve.analise

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 🎯 Treino do piloto de agendamento: cada post vira um exemplo marcado ✓ CERTO ou ✗ ERRADO (com o porquê).
 * O piloto usa isso pra se ajustar: se anda errando, espera mais a rodinha parar antes do Done; se anda acertando, volta a ser rápido.
 */
object AgendarTreino {
    private fun f(c: Context) = File(c.filesDir, "agendar_treino.json")
    fun all(c: Context): List<JSONObject> = JsonFile.peek(f(c))

    /** ok = deu certo; etapa = onde (rodinhas, done, post, lista…); info = o que leu/fez. */
    fun add(c: Context, ok: Boolean, etapa: String, alvo: Long, info: String) {
        JsonFile.edit(f(c)) { l ->
            l.add(JSONObject().put("at", System.currentTimeMillis()).put("ok", ok).put("etapa", etapa).put("alvo", alvo).put("info", info.take(300))
                .put("espera", settleMs(c)))
            while (l.size > 300) l.removeAt(0)
        }
    }

    // ⚡ velocidade das rodinhas (você escolhe e vota se deu certo)
    val SPEEDS = listOf("Devagar", "Normal", "Rápido", "Turbo")
    fun speed(c: Context) = Store.prefs(c).getInt("ag_speed", 2).coerceIn(0, 3)
    fun setSpeed(c: Context, v: Int) = Store.prefs(c).edit().putInt("ag_speed", v.coerceIn(0, 3)).apply()
    /** Linhas por vez na rodinha (dia/minuto, hora). */
    fun rowsAtOnce(c: Context, col: Int) = when (col) { 3 -> 1; 1 -> listOf(2, 3, 6, 11)[speed(c)]; else -> listOf(3, 6, 12, 20)[speed(c)] }
    /** Espera entre um passo e a releitura. */
    fun stepMs(c: Context) = listOf(700L, 450L, 350L, 250L)[speed(c)]

    /** Quanto esperar a rodinha parar antes de confiar na leitura (base da velocidade + aprende com os erros recentes). */
    fun settleMs(c: Context): Long {
        val recent = all(c).filter { it.optString("etapa") in setOf("rodinhas", "post", "done") }.takeLast(12)
        val wrong = recent.count { !it.optBoolean("ok") }
        return (listOf(1200L, 850L, 650L, 450L)[speed(c)] + wrong * 250L).coerceAtMost(2500L)
    }

    /** 👍/👎 do lote com a velocidade usada. */
    fun vote(c: Context, ok: Boolean, note: String = "") = add(c, ok, "voto", 0, "velocidade ${SPEEDS[speed(c)]}" + (if (note.isNotEmpty()) ": $note" else ""))

    fun votes(c: Context): String = all(c).filter { it.optString("etapa") == "voto" }.groupBy { it.optString("info").substringAfter("velocidade ").substringBefore(":") }
        .entries.joinToString(" · ") { (k, l) -> "$k 👍${l.count { it.optBoolean("ok") }} 👎${l.count { !it.optBoolean("ok") }}" }.ifEmpty { "sem votos" }

    fun summary(c: Context): String {
        val l = all(c); if (l.isEmpty()) return "sem exemplos ainda"
        val ok = l.count { it.optBoolean("ok") }; val bad = l.size - ok
        val last = l.takeLast(6).joinToString(" | ") { (if (it.optBoolean("ok")) "✓" else "✗") + " " + it.optString("etapa") + ": " + it.optString("info").take(70) }
        val bd = Store.prefs(c).getString("ag_bandit_done", "") ?: ""
        return "aprendido: ${Store.prefs(c).getString("ag_bandit", "")?.take(400)} · Done: ${bd.take(120)} · velocidade ${SPEEDS[speed(c)]} · votos: ${votes(c)} · ✓ $ok certo(s) · ✗ $bad errado(s) · espera a rodinha parar ${settleMs(c)} ms · últimos: $last"
    }
}
