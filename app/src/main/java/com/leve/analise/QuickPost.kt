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
            sm.dynamicShortcuts = listOf(sc)
        } catch (e: Exception) { }
    }

    fun countReady(c: Context) = PostQueue.load(c).count { it.optString("status") == "pronta" }

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
        for (id in ids) {
            val v = RemoteViews(c.packageName, R.layout.widget_post)
            v.setTextViewText(R.id.w_count, if (n > 0) "$n pronta(s) na fila" else "Fila vazia")
            v.setOnClickPendingIntent(R.id.w_post, QuickPost.pending(c))
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
        try { startActivity(QuickPost.intent(this)) } catch (e: Exception) { }
        finish()
    }
}

/** Informa só quantas fotos estão prontas (pro cartão do Leve Launcher). Nada de fotos ou legendas sai daqui. */
class StatusProvider : android.content.ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor {
        val c = context!!
        val l = PostQueue.load(c)
        return android.database.MatrixCursor(arrayOf("prontas", "total")).apply {
            addRow(arrayOf<Any>(l.count { it.optString("status") == "pronta" }, l.size))
        }
    }
    override fun getType(uri: android.net.Uri): String? = null
    override fun insert(uri: android.net.Uri, v: android.content.ContentValues?): android.net.Uri? = null
    override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = 0
}
