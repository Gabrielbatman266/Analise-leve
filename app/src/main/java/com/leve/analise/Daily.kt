package com.leve.analise

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Meta diária de vídeos: de manhã o app deixa os vídeos do dia prontos,
 * e ao longo do dia (8h–23h) vai lembrando "Hora do vídeo 3 de 10" com o botão Postar.
 */
object Daily {
    private const val NID = 51
    const val START_H = 8
    const val END_H = 23

    private fun p(c: Context) = Store.prefs(c)
    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun goal(c: Context) = p(c).getInt("day_goal", 0)
    fun setGoal(c: Context, n: Int) { p(c).edit().putInt("day_goal", n).apply(); schedule(c); AutoProducer.refillIfNeeded(c) }

    fun postedToday(c: Context) = if (p(c).getString("day_date", "") == today()) p(c).getInt("day_posted", 0) else 0
    fun remaining(c: Context) = maxOf(0, goal(c) - postedToday(c))

    fun countPost(c: Context) {
        val n = postedToday(c) + 1
        p(c).edit().putString("day_date", today()).putInt("day_posted", n).apply()
        c.getSystemService(NotificationManager::class.java).cancel(NID)
        scheduleReminder(c)
    }

    fun status(c: Context): String {
        val g = goal(c); if (g <= 0) return ""
        val done = postedToday(c)
        return if (done >= g) "Meta de hoje batida: $done de $g ✓" else "Hoje: $done de $g vídeos postados"
    }

    private fun pi(c: Context, action: String, req: Int) = PendingIntent.getBroadcast(c, req,
        Intent(c, DailyReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Agenda a preparação da manhã (7:40) e o próximo lembrete do dia. */
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (goal(c) <= 0) { am.cancel(pi(c, "manha", 52)); am.cancel(pi(c, "lembrar", 53)); return }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 40); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi(c, "manha", 52)) } catch (e: Exception) { }
        scheduleReminder(c)
    }

    /** Espalha o que falta no resto do dia, com uma variação pra não ficar redondo. */
    fun scheduleReminder(c: Context, minDelayMs: Long = 0) {
        val am = c.getSystemService(AlarmManager::class.java)
        val left = remaining(c)
        if (goal(c) <= 0 || left <= 0) { am.cancel(pi(c, "lembrar", 53)); return }
        val now = Calendar.getInstance()
        val h = now.get(Calendar.HOUR_OF_DAY)
        val at: Long = if (h < START_H || h >= END_H) {
            Calendar.getInstance().apply {
                if (h >= END_H) add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, START_H); set(Calendar.MINUTE, (5..40).random()); set(Calendar.SECOND, 0)
            }.timeInMillis
        } else {
            val end = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, END_H); set(Calendar.MINUTE, 0) }.timeInMillis
            val gap = ((end - now.timeInMillis) / left).coerceIn(20 * 60_000L, 3 * 60 * 60_000L)
            now.timeInMillis + maxOf(minDelayMs, (gap * (0.8 + Math.random() * 0.4)).toLong())
        }
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, "lembrar", 53)) } catch (e: Exception) { }
        p(c).edit().putLong("day_next", at).apply()
    }

    fun nextAt(c: Context) = p(c).getLong("day_next", 0L)

    fun notifyTime(c: Context) {
        if (remaining(c) <= 0) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("meta", "Meta diária de vídeos", NotificationManager.IMPORTANCE_HIGH))
        val ready = AutoProducer.waiting(c)
        val n = Notification.Builder(c, "meta").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("🎬 Hora do vídeo ${postedToday(c) + 1} de ${goal(c)}")
            .setContentText(if (ready > 0) "Tem $ready vídeo(s) pronto(s). Toque em Postar." else "Sem vídeo pronto: produzindo agora…")
            .setAutoCancel(true)
            .setContentIntent(QuickPost.videoPending(c))
        if (ready > 0) n.addAction(Notification.Action.Builder(null, "🎬 Postar", QuickPost.videoPending(c)).build())
        n.addAction(Notification.Action.Builder(null, "Daqui 30 min", pi(c, "adiar", 54)).build())
        try { nm.notify(NID, n.build()) } catch (e: SecurityException) { }
        if (ready == 0) AutoProducer.refillIfNeeded(c)
        PostBar.alert(c, true)
    }
}

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            "manha" -> { AutoProducer.refillIfNeeded(c); Daily.schedule(c) }               // prepara os vídeos do dia
            "lembrar" -> { Daily.notifyTime(c); Daily.scheduleReminder(c, 25 * 60_000L) }  // se não postar, lembra de novo mais tarde
            "adiar" -> { c.getSystemService(NotificationManager::class.java).cancel(51); Daily.scheduleReminder(c, 30 * 60_000L) }
        }
        QuickPost.updateWidgets(c)
    }
}
