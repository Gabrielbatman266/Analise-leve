package com.leve.analise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Postado" só quando você confirma. O app não enxerga dentro do Instagram/Threads,
 * então cada envio fica pendente até você tocar em "Publiquei" (na volta pro app ou na notificação).
 */
object PostConfirm {
    private const val KEY = "pend_posts"
    private const val NID = 31
    private const val DAY = 24 * 60 * 60 * 1000L

    fun list(c: Context): MutableList<JSONObject> = try {
        val a = JSONArray(Store.prefs(c).getString(KEY, "[]")); (0 until a.length()).map { a.getJSONObject(it) }
            .filter { System.currentTimeMillis() - it.optLong("at") < DAY }.toMutableList()
    } catch (e: Exception) { mutableListOf() }

    private fun save(c: Context, l: List<JSONObject>) { val a = JSONArray(); l.takeLast(10).forEach { a.put(it) }; Store.prefs(c).edit().putString(KEY, a.toString()).apply() }

    /** kind: "fila" (foto da fila → Threads), "video" (vídeo pronto → Instagram), "fotoIg" (foto → Instagram) */
    fun start(c: Context, kind: String, id: String, caption: String, app: String) {
        val l = list(c).filter { !(it.optString("kind") == kind && it.optString("id") == id) }.toMutableList()
        l.add(JSONObject().put("kind", kind).put("id", id).put("cap", caption).put("app", app).put("at", System.currentTimeMillis()))
        save(c, l); notifyAsk(c, l.last())
    }

    fun first(c: Context): JSONObject? = list(c).firstOrNull { System.currentTimeMillis() - it.optLong("at") > 4000 }

    /** Aplica o "Publiquei" (yes) ou descarta (no). */
    fun answer(c: Context, kind: String, id: String, yes: Boolean) {
        val l = list(c)
        val p = l.firstOrNull { it.optString("kind") == kind && it.optString("id") == id } ?: return
        l.remove(p); save(c, l)
        c.getSystemService(NotificationManager::class.java).cancel(NID)
        l.lastOrNull()?.let { notifyAsk(c, it) }
        if (!yes) return
        val cap = p.optString("cap")
        val now = System.currentTimeMillis()
        when (kind) {
            "fila" -> {
                PostQueue.update(c, id) { it.put("status", "postada"); it.put("postedAt", now); it.put("escolhida", cap); it.put("usos", it.optInt("usos") + 1) }
                if (Reminder.every(c) > 0) Reminder.schedule(c)
            }
            "video" -> {
                VideoExport.updateLib(c, id) { it.put("legenda", cap); it.put("posts", it.optInt("posts") + 1); it.put("lastPost", now) }
                VideoExport.markPosted(c, id)
                RateLater.schedule(c, id)
                Daily.countPost(c)
                AutoProducer.refillIfNeeded(c)
            }
            "fotoIg" -> PostQueue.update(c, id) { it.put("ig", it.optInt("ig") + 1) }
        }
        if (cap.isNotBlank()) CaptionDB.used(c, cap)
        Store.prefs(c).edit().putBoolean("bubble_alert", false).apply()
        QuickPost.updateWidgets(c)
    }

    fun label(p: JSONObject) = when (p.optString("kind")) {
        "video" -> "o vídeo no ${p.optString("app")}"; else -> "a foto no ${p.optString("app")}"
    }

    private fun pi(c: Context, p: JSONObject, yes: Boolean, req: Int) = PendingIntent.getBroadcast(c, req,
        Intent(c, PostConfirmReceiver::class.java).setAction(if (yes) "sim" else "nao")
            .setData(Uri.parse("post://${p.optString("kind")}/${p.optString("id")}"))
            .putExtra("kind", p.optString("kind")).putExtra("id", p.optString("id")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notifyAsk(c: Context, p: JSONObject) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("confirma", "Confirmar post", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(c, 33, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, "confirma")
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle("Publicou ${label(p)}?")
            .setContentText("Só marco como postado se você confirmar.")
            .setContentIntent(open).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "✓ Publiquei", pi(c, p, true, 34)).build())
            .addAction(Notification.Action.Builder(null, "Não publiquei", pi(c, p, false, 35)).build())
            .build()
        try { nm.notify(NID, n) } catch (e: SecurityException) { }
    }
}

class PostConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        PostConfirm.answer(c, i.getStringExtra("kind") ?: return, i.getStringExtra("id") ?: return, i.action == "sim")
        QuickPost.updateWidgets(c)
    }
}
