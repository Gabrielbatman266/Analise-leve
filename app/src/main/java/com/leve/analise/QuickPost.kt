package com.leve.analise

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/** "Postar próximo" fora do app: widget na tela inicial, atalho no ícone e bloco na barra de atalhos. */
object QuickPost {
    fun intent(c: Context) = Intent(c, PostActivity::class.java).putExtra("random", true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    fun pending(c: Context): PendingIntent = PendingIntent.getActivity(c, 77, intent(c),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Segurar o ícone do app mostra "Postar próximo". */
    fun publishShortcut(c: Context) {
        try {
            val sm = c.getSystemService(ShortcutManager::class.java) ?: return
            val sc = ShortcutInfo.Builder(c, "postar_proximo")
                .setShortLabel("Postar próximo")
                .setLongLabel("Postar próximo no Threads")
                .setIcon(Icon.createWithResource(c, android.R.drawable.ic_menu_send))
                .setIntent(Intent(c, PostActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("random", true))
                .build()
            val sv = ShortcutInfo.Builder(c, "postar_video")
                .setShortLabel("Postar vídeo")
                .setLongLabel("Postar vídeo no Instagram")
                .setIcon(Icon.createWithResource(c, android.R.drawable.ic_media_play))
                .setIntent(Intent(c, PostVideoActivity::class.java).setAction(Intent.ACTION_VIEW))
                .build()
            sm.dynamicShortcuts = listOf(sc, sv)
        } catch (e: Exception) { }
    }

    fun countReady(c: Context) = PostQueue.load(c).count { it.optString("status") == "pronta" }
    fun countVideos(c: Context) = AutoProducer.waiting(c)

    fun videoIntent(c: Context) = Intent(c, PostVideoActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    fun videoPending(c: Context): PendingIntent = PendingIntent.getActivity(c, 79, videoIntent(c),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun updateWidgets(c: Context) {
        try {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, PostWidget::class.java))
            if (ids.isNotEmpty()) PostWidget().onUpdate(c, m, ids)
        } catch (e: Exception) { }
    }
}

class PostWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        val n = QuickPost.countReady(c)
        val nv = QuickPost.countVideos(c)
        for (id in ids) {
            val v = RemoteViews(c.packageName, R.layout.widget_post)
            v.setTextViewText(R.id.w_count, "📸 $n foto(s) · 🎬 $nv vídeo(s) prontos" + if (AutoProducer.running) " · 🤖 produzindo" else "")
            v.setOnClickPendingIntent(R.id.w_post, QuickPost.pending(c))
            v.setOnClickPendingIntent(R.id.w_video, QuickPost.videoPending(c))
            v.setOnClickPendingIntent(R.id.w_open, PendingIntent.getActivity(c, 78, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            m.updateAppWidget(id, v)
        }
    }
}

class PostTile : TileService() {
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(QuickPost.pending(this))
        else @Suppress("DEPRECATION") startActivityAndCollapse(QuickPost.intent(this))
    }
}

/** Porta de entrada pro Leve Launcher (e outros atalhos): "Postar próximo". Só abre o fluxo, não lê nada. */
class QuickPostEntry : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val video = intent?.action == "com.leve.analise.POSTAR_VIDEO"
        try { startActivity(if (video) QuickPost.videoIntent(this) else QuickPost.intent(this)) } catch (e: Exception) { }
        finish()
    }
}

/** "Postar vídeo" (widget, launcher, atalho): sorteia um vídeo pronto da rodada + legenda e abre o Reels. */
class PostVideoActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val id = VideoExport.cycleNext(this)
        if (id == null) {
            android.widget.Toast.makeText(this, "Nenhum vídeo pronto. Abra o Post Leve › Instagram › 🤖 Auto pra produzir.", android.widget.Toast.LENGTH_LONG).show()
            AutoProducer.refillIfNeeded(this); finish(); return
        }
        val o = VideoExport.library(this).firstOrNull { it.optString("id") == id }
        val cap = o?.optString("legenda")?.takeIf { it.isNotBlank() && o.optInt("posts") == 0 } ?: Captions.randomCaption(this, listOfNotNull(o?.optString("legenda")))
        try { getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("legenda", cap)) } catch (e: Exception) { }
        VideoExport.updateLib(this, id) { it.put("legenda", cap) }
        val uri = PostQueue.uri(id).buildUpon().path("/$id.mp4").build()
        try {
            startActivity(VideoExport.shareToInstagram(this, uri, "video/mp4"))
            PostConfirm.start(this, "video", id, cap, "Instagram")
            android.widget.Toast.makeText(this, "Legenda copiada: cole e publique", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Não achei o Instagram", android.widget.Toast.LENGTH_LONG).show()
        }
        finish()
    }
}

/** No dia seguinte: "Como foi o vídeo?" 🔥 😐 👎 direto na notificação (ensina texto, modelo e legenda). */
object RateLater {
    fun schedule(c: Context, vid: String) {
        val am = c.getSystemService(android.app.AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, ("r" + vid).hashCode(), Intent(c, RateReceiver::class.java).setAction("ask").putExtra("id", vid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 18 * 60 * 60 * 1000L, pi)
    }

    /** Aplica a nota uma vez só (a mesma do botão em Prontos). */
    fun apply(c: Context, vid: String, k: String) {
        val o = VideoExport.library(c).firstOrNull { it.optString("id") == vid } ?: return
        if (o.optString("nota") == k) return
        CaptionDB.rate(c, o.optString("legenda"), k)
        OverlayDB.rate(c, o.optString("texto"), k, o.optString("tctx").ifEmpty { o.optString("ctx") })
        ModelScore.rate(c, o.optString("modelo"), k)
        VideoExport.updateLib(c, vid) { it.put("nota", k) }
    }
}

class RateReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val vid = i.getStringExtra("id") ?: return
        val nm = c.getSystemService(android.app.NotificationManager::class.java)
        val nid = ("n" + vid).hashCode()
        if (i.action == "ask") {
            val o = VideoExport.library(c).firstOrNull { it.optString("id") == vid } ?: return
            if (o.optString("nota").isNotEmpty()) return
            nm.createNotificationChannel(android.app.NotificationChannel("nota", "Como foi o post", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            fun act(k: String, label: String, req: Int) = android.app.Notification.Action.Builder(null, label,
                PendingIntent.getBroadcast(c, req + nid, Intent(c, RateReceiver::class.java).setAction(k).putExtra("id", vid),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
            val th = java.io.File(VideoExport.videoDir(c), "$vid.jpg")
            val b = android.app.Notification.Builder(c, "nota").setSmallIcon(android.R.drawable.star_on)
                .setContentTitle("Como foi esse vídeo?")
                .setContentText("T: " + o.optString("texto").replace('\n', ' ').take(60))
                .addAction(act("good", "🔥 Bombou", 1)).addAction(act("meh", "😐 Normal", 2)).addAction(act("bad", "👎 Flopou", 3))
                .setAutoCancel(true)
            if (th.exists()) android.graphics.BitmapFactory.decodeFile(th.path)?.let { b.setLargeIcon(it) }
            try { nm.notify(nid, b.build()) } catch (e: SecurityException) { }
            return
        }
        RateLater.apply(c, vid, i.action ?: return)
        nm.cancel(nid)
        android.widget.Toast.makeText(c, if (i.action == "good") "Anotado: mais vídeos assim" else if (i.action == "bad") "Anotado: menos vídeos assim" else "Anotado", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Informa só quantas fotos estão prontas (pro cartão do Leve Launcher). Nada de fotos ou legendas sai daqui. */
class StatusProvider : android.content.ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor {
        val c = context!!
        val l = PostQueue.load(c)
        return android.database.MatrixCursor(arrayOf("prontas", "total", "videos", "produzindo")).apply {
            addRow(arrayOf<Any>(l.count { it.optString("status") == "pronta" }, l.size, AutoProducer.waiting(c), if (AutoProducer.running) 1 else 0))
        }
    }
    override fun getType(uri: android.net.Uri): String? = null
    override fun insert(uri: android.net.Uri, v: android.content.ContentValues?): android.net.Uri? = null
    override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = 0
}
