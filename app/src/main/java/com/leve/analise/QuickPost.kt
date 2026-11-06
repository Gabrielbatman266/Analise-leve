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
