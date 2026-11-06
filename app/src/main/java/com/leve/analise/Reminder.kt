package com.leve.analise

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast

/** Lembrete de postar a cada X minutos, com a próxima foto e o botão "Postar agora". */
object Reminder {
    private const val CH = "posts"
    private const val NID = 9

    /** Intervalo sorteado entre min e max minutos (0 = desligado). */
    fun every(c: Context) = Store.prefs(c).getInt("post_min", 0)
    fun range(c: Context) = Store.prefs(c).getInt("post_min", 0) to Store.prefs(c).getInt("post_max", 0)
    fun nextAt(c: Context) = Store.prefs(c).getLong("next_post_at", 0L)
    fun dayOnly(c: Context) = Store.prefs(c).getBoolean("post_day_only", true)

    private fun alarmPi(c: Context) = PendingIntent.getBroadcast(c, 0, Intent(c, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun setRange(c: Context, min: Int, max: Int) {
        Store.prefs(c).edit().putInt("post_min", min).putInt("post_max", max).apply()
        schedule(c)
    }

    /** Próximo horário: aleatório dentro da faixa, nunca redondo; de madrugada pula pra manhã. */
    fun pickNext(c: Context, from: Long): Long {
        val (mn, mx) = range(c)
        var at = from + ((mn * 60L)..(maxOf(mn, mx) * 60L)).random() * 1000L
        if (dayOnly(c)) {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            if (h >= 23 || h < 8) {
                if (h >= 23) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 8)
                cal.set(java.util.Calendar.MINUTE, (5..55).random())
                at = cal.timeInMillis
            }
        }
        return at
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(alarmPi(c))
        if (every(c) <= 0) { Store.prefs(c).edit().putLong("next_post_at", 0L).apply(); return }
        val at = pickNext(c, System.currentTimeMillis())
        Store.prefs(c).edit().putLong("next_post_at", at).apply()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(c))
    }

    /** Horários sorteados do resto do dia, pra agendar no próprio Threads. */
    fun planToday(c: Context, count: Int = 8): List<Long> {
        val out = ArrayList<Long>()
        var t = System.currentTimeMillis()
        val end = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
        repeat(count) { t = pickNext(c, t); if (t < end) out.add(t) }
        return out
    }

    fun postIntent(c: Context, id: String, caption: String? = null): PendingIntent {
        val i = Intent(c, PostActivity::class.java).putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (caption != null) i.putExtra("caption", caption)
        return PendingIntent.getActivity(c, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun notifyNow(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Hora de postar", NotificationManager.IMPORTANCE_HIGH))
        val pick = Captions.randomPick(c)
        val open = PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Hora de postar")
            .setAutoCancel(true)
            .setContentIntent(open)
        if (pick == null) {
            b.setContentText("A fila está vazia. Adicione fotos no Post Leve.")
        } else {
            val (id, cap) = pick
            b.setContentText(cap)
            try {
                val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bmp = BitmapFactory.decodeFile(PostQueue.imageFile(c, id).path, o)
                if (bmp != null) b.setLargeIcon(bmp).setStyle(Notification.BigPictureStyle().bigPicture(bmp).setSummaryText(cap))
            } catch (e: Exception) { }
            b.setContentIntent(postIntent(c, id, cap))
            b.addAction(Notification.Action.Builder(null, "Postar", postIntent(c, id, cap)).build())
            b.addAction(Notification.Action.Builder(null, "Sortear outra", PendingIntent.getBroadcast(c, 5,
                Intent(c, ReminderReceiver::class.java).setAction("com.leve.analise.REROLL"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        try { nm.notify(NID, b.build()) } catch (e: SecurityException) { }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) { Reminder.schedule(c); FolderScan.schedule(c); return }
        if (i.action == "com.leve.analise.REROLL") { Reminder.notifyNow(c); return }
        PostQueue.cleanGallery(c, false)
        Reminder.notifyNow(c)
        Reminder.schedule(c)
    }
}

/** Invisível: copia a legenda, manda foto+legenda pro Threads e marca como postada. */
class PostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // "Postar próximo" (widget, atalho, barra): sorteia foto e legenda na hora
        val pick = if (intent.getBooleanExtra("random", false)) Captions.randomPick(this) else null
        if (intent.getBooleanExtra("random", false) && pick == null) {
            Toast.makeText(this, "A fila está vazia. Adicione fotos no Post Leve.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val id = pick?.first ?: intent.getStringExtra("id")
        val item = id?.let { PostQueue.get(this, it) }
        if (id == null || item == null || !PostQueue.imageFile(this, id).exists()) {
            Toast.makeText(this, "Essa foto não está mais na fila", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val cap = pick?.second ?: intent.getStringExtra("caption") ?: PostQueue.captionOf(item)
        try {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("legenda", cap))
        } catch (e: Exception) { }
        PostQueue.putInGallery(this, id) // fica em primeiro na galeria; a cópia anterior é apagada
        try {
            startActivity(PostQueue.shareIntent(this, id, cap))
            CaptionDB.used(this, cap)
            PostQueue.update(this, id) {
                it.put("status", "postada"); it.put("postedAt", System.currentTimeMillis())
                it.put("escolhida", cap); it.put("usos", it.optInt("usos") + 1)
            }
            getSystemService(android.app.NotificationManager::class.java).cancel(9)
            if (Reminder.every(this) > 0) Reminder.schedule(this)
            Toast.makeText(this, "Legenda também copiada, se precisar colar", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Não achei o Threads pra enviar", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
