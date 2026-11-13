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

    /** Quanto esperar a rodinha parar antes de confiar na leitura (aprende com os erros recentes). */
    fun settleMs(c: Context): Long {
        val recent = all(c).filter { it.optString("etapa") in setOf("rodinhas", "post", "done") }.takeLast(12)
        val wrong = recent.count { !it.optBoolean("ok") }
        return (650L + wrong * 250L).coerceAtMost(2500L)
    }

    fun summary(c: Context): String {
        val l = all(c); if (l.isEmpty()) return "sem exemplos ainda"
        val ok = l.count { it.optBoolean("ok") }; val bad = l.size - ok
        val last = l.takeLast(6).joinToString(" | ") { (if (it.optBoolean("ok")) "✓" else "✗") + " " + it.optString("etapa") + ": " + it.optString("info").take(70) }
        return "✓ $ok certo(s) · ✗ $bad errado(s) · espera a rodinha parar ${settleMs(c)} ms · últimos: $last"
    }
}
