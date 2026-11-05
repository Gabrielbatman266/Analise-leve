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

    fun every(c: Context) = Store.prefs(c).getInt("post_every", 0)
    fun nextAt(c: Context) = Store.prefs(c).getLong("next_post_at", 0L)

    private fun alarmPi(c: Context) = PendingIntent.getBroadcast(c, 0, Intent(c, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun setEvery(c: Context, min: Int) {
        Store.prefs(c).edit().putInt("post_every", min).apply()
        schedule(c)
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(alarmPi(c))
        val m = every(c)
        if (m <= 0) { Store.prefs(c).edit().putLong("next_post_at", 0L).apply(); return }
        val at = System.currentTimeMillis() + m * 60_000L
        Store.prefs(c).edit().putLong("next_post_at", at).apply()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(c))
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
        val item = PostQueue.next(c)
        val open = PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Hora de postar")
            .setAutoCancel(true)
            .setContentIntent(open)
        if (item == null) {
            b.setContentText("A fila está vazia. Adicione fotos no Análise Leve.")
        } else {
            val id = item.optString("id")
            val cap = PostQueue.captionOf(item)
            b.setContentText(cap.ifEmpty { "Próxima foto pronta" })
            try {
                val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bmp = BitmapFactory.decodeFile(PostQueue.imageFile(c, id).path, o)
                if (bmp != null) b.setLargeIcon(bmp).setStyle(Notification.BigPictureStyle().bigPicture(bmp).setSummaryText(cap))
            } catch (e: Exception) { }
            b.setContentIntent(postIntent(c, id))
            b.addAction(Notification.Action.Builder(null, "Postar agora", postIntent(c, id)).build())
            b.addAction(Notification.Action.Builder(null, "Abrir fila", open).build())
        }
        try { nm.notify(NID, b.build()) } catch (e: SecurityException) { }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) { Reminder.schedule(c); return }
        Reminder.notifyNow(c)
        Reminder.schedule(c)
    }
}

/** Invisível: copia a legenda, manda foto+legenda pro Threads e marca como postada. */
class PostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")
        val item = id?.let { PostQueue.get(this, it) }
        if (id == null || item == null || !PostQueue.imageFile(this, id).exists()) {
            Toast.makeText(this, "Essa foto não está mais na fila", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val cap = intent.getStringExtra("caption") ?: PostQueue.captionOf(item)
        try {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("legenda", cap))
        } catch (e: Exception) { }
        try {
            startActivity(PostQueue.shareIntent(this, id, cap))
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
