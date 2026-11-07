package com.leve.analise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 🔕 Filtro das notificações do Threads:
 *  - curtidas abaixo do limite (ex.: 50) somem;
 *  - quando um post passa do limite, o Post Leve avisa (com som) e marca o post como 🔥 sozinho.
 * Precisa do "Acesso a notificações" liberado pro Post Leve.
 */
object ThreadsNotif {
    const val THREADS = "com.instagram.barcelona"
    private fun p(c: Context) = Store.prefs(c)
    fun on(c: Context) = p(c).getBoolean("tn_on", false)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("tn_on", v).apply()
    fun min(c: Context) = p(c).getInt("tn_min", 50)
    fun setMin(c: Context, v: Int) = p(c).edit().putInt("tn_min", v).apply()
    /** "likes" = só filtra curtidas; "all" = some tudo do Threads, menos as curtidas acima do limite. */
    fun mode(c: Context) = p(c).getString("tn_mode", "likes") ?: "likes"
    fun setMode(c: Context, v: String) = p(c).edit().putString("tn_mode", v).apply()
    fun autoRate(c: Context) = p(c).getBoolean("tn_rate", true)
    fun setAutoRate(c: Context, v: Boolean) = p(c).edit().putBoolean("tn_rate", v).apply()

    fun hasAccess(c: Context): Boolean {
        val s = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
        return s.split(':').any { ComponentName.unflattenFromString(it)?.packageName == c.packageName }
    }
    fun openAccess(c: Context) = try { c.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    fun openThreadsSettings(c: Context) = try {
        c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, THREADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { }

    private val LIKE = Regex("curt|like|❤|gost", RegexOption.IGNORE_CASE)
    private val OTHERS = Regex("(\\d[\\d.,]*)\\s*(mil|k)?\\s*(outras|outros|others|pessoas|people)", RegexOption.IGNORE_CASE)
    private val NUM = Regex("(\\d[\\d.,]*)\\s*(mil|k)?\\s*(curtidas|likes|curtiram)", RegexOption.IGNORE_CASE)

    private fun num(s: String, mult: String?): Int {
        val m = if (mult.isNullOrEmpty()) 1.0 else 1000.0
        val clean = if (m > 1) s.replace(',', '.') else s.replace(".", "").replace(",", "")
        return ((clean.toDoubleOrNull() ?: 0.0) * m).toInt()
    }

    /** Quantas curtidas a notificação diz (null = não é de curtida). */
    fun likes(text: String): Int? {
        if (!LIKE.containsMatchIn(text)) return null
        OTHERS.find(text)?.let { return num(it.groupValues[1], it.groupValues[2]) + 1 }
        NUM.find(text)?.let { return num(it.groupValues[1], it.groupValues[2]) }
        return 1 // "fulano curtiu sua thread"
    }

    fun countSilenced(c: Context) {
        val today = android.text.format.DateFormat.format("yyyyMMdd", System.currentTimeMillis()).toString()
        val n = if (p(c).getString("tn_day", "") == today) p(c).getInt("tn_n", 0) + 1 else 1
        p(c).edit().putString("tn_day", today).putInt("tn_n", n).apply()
    }
    fun silencedToday(c: Context): Int {
        val today = android.text.format.DateFormat.format("yyyyMMdd", System.currentTimeMillis()).toString()
        return if (p(c).getString("tn_day", "") == today) p(c).getInt("tn_n", 0) else 0
    }

    /** Post passou do limite: avisa com som e marca 🔥 no post que bate com o trecho. */
    fun hit(c: Context, likes: Int, text: String, open: PendingIntent?) {
        val key = "tn_hit_" + text.hashCode()
        if (p(c).getBoolean(key, false)) return
        p(c).edit().putBoolean(key, true).apply()
        val rated = if (autoRate(c)) rateMatching(c, text) else null
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("tn_hit", "Posts que bombaram (Threads)", NotificationManager.IMPORTANCE_HIGH))
        val nb = Notification.Builder(c, "tn_hit").setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("🔥 Post com $likes+ curtidas no Threads")
            .setContentText((if (rated != null) "Marquei como 🔥: " else "") + text.replace('\n', ' ').take(100))
            .setAutoCancel(true)
        open?.let { nb.setContentIntent(it) }
        try { nm.notify(("hit" + text).hashCode(), nb.build()) } catch (e: SecurityException) { }
    }

    /** Acha o post da fila cuja legenda aparece no texto da notificação e dá 🔥 (uma vez por post). */
    private fun rateMatching(c: Context, text: String): String? {
        val t = text.lowercase()
        val o = PostQueue.load(c).filter { it.optString("status") == "postada" }.sortedByDescending { it.optLong("postedAt") }.firstOrNull { o ->
            val cap = PostQueue.captionOf(o).lowercase().replace('\n', ' ').trim()
            cap.length >= 6 && (t.contains(cap.take(40)) || t.contains(cap.take(18)))
        } ?: return null
        val id = o.optString("id")
        if (o.optLong("notaAt") == o.optLong("postedAt") && o.optString("nota") == "good") return null
        RateLater.applyPhoto(c, id, "good")
        return id
    }
}

class ThreadsListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != ThreadsNotif.THREADS || !ThreadsNotif.on(this)) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val text = listOfNotNull(ex.getCharSequence(Notification.EXTRA_TITLE), ex.getCharSequence(Notification.EXTRA_TEXT), ex.getCharSequence(Notification.EXTRA_BIG_TEXT))
            .joinToString(" ").trim()
        if (text.isEmpty()) return
        val likes = ThreadsNotif.likes(text)
        val keep = when {
            likes != null -> likes >= ThreadsNotif.min(this)
            else -> ThreadsNotif.mode(this) != "all" // não é curtida: só some no modo "tudo"
        }
        if (likes != null && keep) ThreadsNotif.hit(this, likes, text, n.contentIntent)
        if (!keep) { try { cancelNotification(sbn.key) } catch (e: Exception) { }; ThreadsNotif.countSilenced(this) }
    }
}
