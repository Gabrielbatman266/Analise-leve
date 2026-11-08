package com.leve.analise

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 🤖 Tudo automático: um interruptor que liga todas as automações e controla o gasto da IA.
 *  - vídeos novos do Flow → entram na categoria padrão e já são produzidos;
 *  - legendas: quando surgem novas 🔥/💎, a IA cria variações sozinha;
 *  - textos no vídeo: a IA completa quando faltam;
 *  - análise das notas, verificador das filas, piloto da manhã, melhores horários.
 * A IA automática tem um limite por dia (o grátis do Gemini é ~20 pedidos).
 */
object Auto {
    private fun p(c: Context) = Store.prefs(c)
    private fun today() = android.text.format.DateFormat.format("yyyyMMdd", System.currentTimeMillis()).toString()

    fun on(c: Context) = p(c).getBoolean("auto_all", true)
    fun setOn(c: Context, v: Boolean) {
        p(c).edit().putBoolean("auto_all", v).putBoolean("ov_ai_auto", v).putBoolean("learn_auto", v).putBoolean("health_auto", v)
            .putBoolean("autopilot", v).putBoolean("best_hours", v).putBoolean("auto_new_produce", v).apply()
        Daily.schedule(c)
    }
    /** Aplica os padrões do "tudo automático" uma vez (pra quem já usava o app). */
    fun migrate(c: Context) { if (!p(c).getBoolean("auto_migrated", false)) { setOn(c, true); p(c).edit().putBoolean("auto_migrated", true).apply() } }

    // ---------- orçamento da IA
    fun aiMax(c: Context) = p(c).getInt("ai_day_max", 12)
    fun setAiMax(c: Context, v: Int) = p(c).edit().putInt("ai_day_max", v).apply()
    fun aiUsedToday(c: Context) = if (p(c).getString("ai_day", "") == today()) p(c).getInt("ai_used", 0) else 0
    fun countAi(c: Context) { p(c).edit().putString("ai_day", today()).putInt("ai_used", aiUsedToday(c) + 1).apply() }
    /** Automação pode gastar mais N pedidos hoje? (o que você pede na mão não tem limite) */
    fun canSpend(c: Context, n: Int = 1) = on(c) && Ai.key(c).isNotEmpty() && aiUsedToday(c) + n <= aiMax(c)

    // ---------- vídeos novos do Flow
    fun newCat(c: Context): String = p(c).getString("cat_flow", null) ?: Contexts.names(c).firstOrNull { Contexts.isGeneric(it) } ?: ""
    fun setNewCat(c: Context, v: String) = p(c).edit().putString("cat_flow", v).apply()
    fun produceNew(c: Context) = on(c) && p(c).getBoolean("auto_new_produce", true)

    /** Chegaram [pids] novos: põe na categoria padrão e produz. */
    fun onNewRaw(c: Context, pids: List<String>) {
        if (pids.isEmpty()) return
        val cat = newCat(c)
        if (cat.isNotEmpty()) pids.forEach { id -> Projects.update(c, id) { if (it.optString("ctx").isEmpty()) it.put("ctx", cat) } }
        if (!produceNew(c) || AutoProducer.running) return
        val n = (if (cat.isNotEmpty() && Contexts.noText(c, cat)) pids.size * Contexts.versions(c, cat) else pids.size).coerceIn(1, 10)
        try {
            val i = Intent(c, ProduceService::class.java).putExtra("n", n).putExtra("only", cat.ifEmpty { null })
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        } catch (e: Exception) { }
    }

    // ---------- legendas novas parecidas com as que bombam
    /** Se surgiram vencedoras novas ou o estoque de variações acabou, a IA cria mais (1 pedido). */
    fun captionsTick(c: Context): String? {
        if (!on(c)) return null
        val wins = CaptionDB.winners(c, 50).size
        val vars = CaptionDB.texts(c, "var").size
        val lastWins = p(c).getInt("auto_wins", 0)
        val need = wins > 0 && (wins > lastWins || vars < 15)
        if (!need || !canSpend(c)) return null
        if (System.currentTimeMillis() - p(c).getLong("auto_var_at", 0) < 6 * 3600_000L) return null // no máx. a cada 6 h
        return try {
            val n = Captions.variationsOfWinners(c, 10)
            p(c).edit().putInt("auto_wins", wins).putLong("auto_var_at", System.currentTimeMillis()).apply()
            if (n > 0) "$n legendas novas no estilo das que bombaram" else null
        } catch (e: Exception) { null }
    }

    /** Resumo do que está automático (pra tela). */
    fun summary(c: Context): String {
        if (!on(c)) return "Desligado: o app só faz o que você pedir."
        val cat = newCat(c)
        return listOf(
            "🎬 Flow → vídeos novos vão pra " + (if (cat.isEmpty()) "Sem categoria" else Contexts.label(c, cat)) + " e já são produzidos",
            "💬 Legendas parecidas com as 🔥 criadas sozinhas",
            "T Textos no vídeo completados pela IA quando faltam",
            "⭐ Notas: notificações + leitor de tela + sua média + vigia",
            "🩺 Filas giradas · ☀️ plano às 7:40 · ⏰ melhores horários",
            "🧠 IA automática hoje: ${aiUsedToday(c)} de ${aiMax(c)} pedidos"
        ).joinToString("\n")
    }
}
