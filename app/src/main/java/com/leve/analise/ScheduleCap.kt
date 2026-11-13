package com.leve.analise

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 📅 BOT DO LIMITE: o Threads só aceita 25 posts agendados de uma vez. Antes de cada post do lote ele conta quantos já estão
 * esperando lá; se bateu 25, PARA o lote sozinho (em vez de travar no Threads) e marca pra voltar quando o mais cedo for
 * publicado — aí agenda mais um, e assim vai, sem você mexer.
 */
object ScheduleCapCore {
    const val CAP = 25
    data class Plan(val waiting: Int, val free: Int, val resumeAt: Long?)

    /**
     * [scheduled]: horários de tudo que já foi pro Threads (agenda + lote ✓/enviado). Conta só os que ainda não saíram
     * (mais de 1 min no futuro). Horários repetidos (o mesmo post contado na agenda e no lote) contam 1 vez.
     */
    fun plan(scheduled: List<Long>, now: Long, cap: Int = CAP): Plan {
        val fut = scheduled.filter { it > now + 60_000L }.distinctBy { it / 60_000L }.sorted()
        val free = (cap - fut.size).coerceAtLeast(0)
        // cheio: volta 3 min depois que o mais cedo publicar (abre 1 vaga)
        val resume = if (free == 0) fut.firstOrNull()?.let { it + 3 * 60_000L } else null
        return Plan(fut.size, free, resume)
    }
}

object ScheduleCap {
    /** Tudo que está esperando no Threads: agenda ("agendada no Threads") + lote (✓ ou enviado). */
    fun scheduledTimes(c: Context): List<Long> =
        Agenda.all(c).filter { it.optString("status") == AgendaCore.NO_THREADS }.map { it.optLong("at") } +
        Batch.load(c).filter { it.optBoolean("done") || it.optLong("sent") > 0 }.map { it.optLong("at") }

    fun plan(c: Context, now: Long = System.currentTimeMillis()) = ScheduleCapCore.plan(scheduledTimes(c), now)

    /** Marca o lote pra continuar sozinho em [at]. */
    fun resumeAt(c: Context, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 7702, Intent(c, AgendaReceiver::class.java).setAction("lote"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        Store.prefs(c).edit().putLong("cap_resume", at).apply()
    }

    fun status(c: Context): String {
        val p = plan(c)
        val r = Store.prefs(c).getLong("cap_resume", 0L)
        return "📅 limite do Threads: ${p.waiting}/${ScheduleCapCore.CAP} agendados" + (if (p.free == 0) " (cheio)" else " · cabem mais ${p.free}") +
            (if (r > System.currentTimeMillis()) " · o lote volta sozinho ${AgendaCore.fmt(r)}" else "")
    }
}
