package com.leve.analise

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
import android.os.Build

/**
 * Área de transferência:
 *  - copy(): toda legenda que o app copia leva o rótulo "PostLeve" (o Leve Teclado reconhece e apaga depois)
 *    e some da área de transferência depois de X minutos.
 *  - TextInReceiver: o Leve Teclado manda o que você copia no Threads → banco de legendas (referências).
 */
object ClipSync {
    const val LABEL = "PostLeve"
    fun ttl(c: Context) = Store.prefs(c).getInt("clip_ttl", 10) // minutos; 0 = nunca

    fun copy(c: Context, text: String) {
        if (text.isBlank()) return
        try {
            val cm = c.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText(LABEL, text)
            cm.setPrimaryClip(clip)
        } catch (e: Exception) { return }
        Store.prefs(c).edit().putString("clip_last", text).apply()
        val min = ttl(c)
        if (min <= 0) return
        val am = c.getSystemService(AlarmManager::class.java)
        am.set(AlarmManager.RTC, System.currentTimeMillis() + min * 60_000L, pi(c))
    }

    private fun pi(c: Context) = PendingIntent.getBroadcast(c, 61, Intent(c, ClipCleanReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Apaga a legenda da área de transferência (se você copiou outra coisa depois, não mexe). */
    fun clean(c: Context) {
        val last = Store.prefs(c).getString("clip_last", null) ?: return
        try {
            val cm = c.getSystemService(ClipboardManager::class.java)
            val cur = try { cm.primaryClip } catch (e: Exception) { null }
            if (cur != null && cur.itemCount > 0) {
                val lab = cur.description?.label?.toString()
                val t = cur.getItemAt(0).coerceToText(c)?.toString()
                if (lab != LABEL && t != last) { Store.prefs(c).edit().remove("clip_last").apply(); return } // é outra coisa sua
            }
            // sem poder ler (Android 10+ em segundo plano): só limpa se ainda é a nossa, pelo rótulo quando dá
            if (cur == null || cur.description?.label?.toString() == LABEL) {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) { }
        Store.prefs(c).edit().remove("clip_last").apply()
    }

    // ---------- textos vindos do Leve Teclado
    fun receive(c: Context, text: String, app: String) {
        val t = text.trim()
        if (t.length < 3) return
        val norm = t.lowercase().replace(Regex("\\s+"), " ")
        if (CaptionDB.all(c).any { it.optString("t").lowercase().replace(Regex("\\s+"), " ") == norm }) return // já tem (ou é legenda nossa)
        CaptionDB.add(c, t, "viral")
        val p = Store.prefs(c)
        val today = android.text.format.DateFormat.format("yyyyMMdd", System.currentTimeMillis()).toString()
        val n = if (p.getString("in_day", "") == today) p.getInt("in_n", 0) + 1 else 1
        p.edit().putString("in_day", today).putInt("in_n", n).apply()
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("entrada", "Textos do teclado", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(c, 62, Intent(c, MainActivity::class.java).putExtra("tab", "Legendas").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val from = if (app.contains("barcelona")) "Threads" else if (app.contains("instagram")) "Instagram" else "teclado"
        val nb = Notification.Builder(c, "entrada").setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("📥 $n texto(s) do $from no banco hoje")
            .setContentText(t.replace('\n', ' ').take(90))
            .setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(open).build()
        try { nm.notify(63, nb) } catch (e: SecurityException) { }
    }
}

class ClipCleanReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { ClipSync.clean(c) }
}

class TextInReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != "com.leve.analise.ADD_TEXT") return
        val t = i.getStringExtra("texto") ?: return
        if (t.length > 3000) return
        ClipSync.receive(c, t, i.getStringExtra("app").orEmpty())
    }
}
