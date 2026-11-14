package com.leve.analise

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 🛫 PILOTO AUTOMÁTICO: a cada ~90 min (8h–23h30) ele, sozinho:
 *  1. vê quantos posts já estão agendados no Threads e no lote, por dia;
 *  2. monta os que faltam pra manter hoje e os próximos dias com a meta (fotos+legendas do sorteio da fila, horários
 *     em que a audiência está ativa), sem passar de 25 agendados;
 *  3. liga o 🤖 robô pra agendar no Threads;
 *  4. com o robô parado, roda a 🤖 Coleta dos números quando tem post precisando de leitura.
 * O cérebro (quantos, que dia, que horário) é o AutoPilotCore, testado 30 dias seguidos no AutoPilotSimTest.
 */
object AutoPilot {
    private fun p(c: Context) = Store.prefs(c)
    fun on(c: Context) = p(c).getBoolean("ap_on", true)
    fun setOn(c: Context, v: Boolean) { p(c).edit().putBoolean("ap_on", v).apply(); schedule(c) }
    fun perDay(c: Context) = p(c).getInt("ap_dia", 8)
    fun setPerDay(c: Context, v: Int) = p(c).edit().putInt("ap_dia", v).apply()
    fun daysAhead(c: Context) = p(c).getInt("ap_dias", 2)
    fun setDaysAhead(c: Context, v: Int) = p(c).edit().putInt("ap_dias", v).apply()
    private fun cfg(c: Context) = AutoPilotCore.Cfg(perDay = perDay(c), daysAhead = daysAhead(c))

    private fun pi(c: Context) = PendingIntent.getBroadcast(c, 7711, Intent(c, AgendaReceiver::class.java).setAction("piloto"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Próxima rodada em ~90 min (de madrugada, pula pras 7h40). */
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (!on(c)) { am.cancel(pi(c)); return }
        var t = System.currentTimeMillis() + 90 * 60_000L
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = t }
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        if (h < 7 || (h == 23 && cal.get(java.util.Calendar.MINUTE) > 40)) {
            if (h >= 7) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 7); cal.set(java.util.Calendar.MINUTE, 40); t = cal.timeInMillis
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c))
        p(c).edit().putLong("ap_next", t).apply()
    }

    /** Uma rodada do piloto. Devolve o que fez (vai pro diagnóstico). */
    fun tick(c: Context): String {
        schedule(c)
        if (!on(c)) return "desligado"
        val now = System.currentTimeMillis()
        try { Batch.refreshTimes(c) } catch (e: Exception) { }
        val lote = Batch.load(c)
        val pending = lote.filter { !it.optBoolean("done") && it.optLong("sent") == 0L }.map { it.optLong("at") }
        val waiting = ScheduleCap.scheduledTimes(c).filter { it > now }
        val published = (lote.filter { it.optBoolean("done") }.map { it.optLong("at") } +
            Agenda.all(c).filter { it.optString("status") == "publicada" || it.optString("status") == AgendaCore.NO_THREADS }.map { it.optLong("at") })
            .filter { it in now - 2 * 86_400_000L..now }
        val needs = AutoPilotCore.plan(now, waiting, pending, cfg(c), published = published)
        var added = 0; var noPhoto = 0
        val busy = ArrayList(waiting + pending)
        val cal = java.util.Calendar.getInstance()
        for (n in needs) {
            val ts = AutoPilotCore.times(n.n, n.from, n.to, busy, score = { t -> cal.timeInMillis = t; (try { Audience.score(c, cal.get(java.util.Calendar.HOUR_OF_DAY)) } catch (e: Exception) { null } ?: 0.5) + Math.random() * 0.15 })
            for (t in ts) {
                val pk = Captions.randomPick(c, promo = false)
                if (pk == null) { noPhoto++; continue }
                Captions.remember(c, pk.first, pk.second)
                lote += org.json.JSONObject().put("id", pk.first).put("cap", pk.second).put("at", t).put("done", false).put("piloto", true)
                busy += t; added++
            }
        }
        if (added > 0) Batch.save(c, lote.sortedBy { it.optLong("at") })
        val left = lote.count { !it.optBoolean("done") && it.optLong("sent") == 0L }
        val r = ScreenReader.instance
        val what = when {
            r == null -> "⚠ leitor de tela desligado: não dá pra agendar sozinho"
            r.autoRunning -> "robô já trabalhando"
            left > 0 && ScheduleCap.plan(c).free > 0 -> { r.runBatchAuto(); "🤖 robô ligado pra $left post(s)" }
            AutoPilotCore.shouldCollect(now, p(c).getLong("ap_coleta", 0L), try { ThreadsApi.needReread(c).size } catch (e: Exception) { 0 }, false) -> {
                p(c).edit().putLong("ap_coleta", now).apply(); "📊 coleta: " + Macro.start(c) }
            else -> "nada a fazer"
        }
        val msg = android.text.format.DateFormat.format("dd/MM HH:mm", now).toString() + " · montei $added" + (if (noPhoto > 0) " (faltou foto pra $noPhoto: adicione fotos na fila)" else "") + " · $what"
        p(c).edit().putString("ap_last", msg).apply()
        return msg
    }

    fun status(c: Context): String = if (!on(c)) "🛫 piloto automático: desligado" else
        "🛫 piloto automático: ${perDay(c)}/dia, ${daysAhead(c)} dia(s) à frente · última rodada: " + (p(c).getString("ap_last", null) ?: "ainda não rodou") +
        " · próxima " + p(c).getLong("ap_next", 0L).let { if (it > 0) AgendaCore.fmt(it) else "?" }
}

/** 📡 De onde vêm os números (por post dos últimos 7 dias) e quanto cobrem. */
object DataSources {
    fun status(c: Context): String {
        val now = System.currentTimeMillis()
        val ps = try { ThreadsApi.posts(c) } catch (e: Exception) { emptyList() }.filter { now - it.optLong("at") < 7 * 86_400_000L }
        if (ps.isEmpty()) return "📡 fontes de dados: nenhum post com números nos últimos 7 dias"
        val bySrc = ps.groupingBy { it.optString("src").ifEmpty { "?" } }.eachCount().entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${it.value}" }
        val withViews = ps.count { it.has("views") }; val withLikes = ps.count { it.has("likes") }
        val sends = try { SendLog.all(c).count { now - it.optLong("at") < 7 * 86_400_000L && it.optLong("at") < now - 3 * 3_600_000L } } catch (e: Exception) { 0 }
        return "📡 fontes de dados (7 dias): $bySrc · com visualizações ${withViews}/${ps.size} · com curtidas ${withLikes}/${ps.size}" +
            (if (sends > 0) " · posts do app com número: ${ps.count { it.optLong("send") > 0 }}/$sends" else "") +
            " · API: " + (if (ThreadsApi.on(c)) "conectada" else "não") + " · leitor: " + (if (ScreenReader.instance != null) "ligado" else "DESLIGADO") +
            " · notificações: " + (if (ThreadsNotif.live(c)) "ligadas" else "DESLIGADAS")
    }
}
