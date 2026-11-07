package com.leve.analise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore

/**
 * Fotos que você salva no Threads ("Salvar mídia" / baixar) caem na galeria;
 * o Post Leve percebe na hora e copia pro estoque de Referências (sem metadados).
 * Reconhece pelo app que criou o arquivo (Threads) ou pela pasta "Threads".
 */
object ThreadsRefs {
    const val THREADS = "com.instagram.barcelona"
    private fun p(c: Context) = Store.prefs(c)
    fun on(c: Context) = p(c).getBoolean("th_refs", true)
    fun setOn(c: Context, v: Boolean) {
        p(c).edit().putBoolean("th_refs", v).apply()
        if (v) p(c).edit().putLong("th_since", System.currentTimeMillis() / 1000).apply()
        FolderScan.schedule(c)
    }

    fun scan(c: Context): Int {
        if (!on(c) || !FolderScan.hasPerm(c)) return 0
        val pr = p(c)
        val since = pr.getLong("th_since", 0L).let { if (it == 0L) { val now = System.currentTimeMillis() / 1000; pr.edit().putLong("th_since", now).apply(); now } else it }
        val seen = (pr.getStringSet("th_seen", emptySet()) ?: emptySet()).toMutableSet()
        val proj = mutableListOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val sel = StringBuilder("${MediaStore.Images.Media.DATE_ADDED} > ?")
        val args = mutableListOf((since - 5).toString())
        if (Build.VERSION.SDK_INT >= 29) {
            sel.append(" AND (${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)")
            args += THREADS; args += "%Threads%"
        } else {
            @Suppress("DEPRECATION")
            sel.append(" AND ${MediaStore.Images.Media.DATA} LIKE ?"); args += "%/Threads/%"
        }
        var n = 0; var maxAdded = since
        try {
            c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj.toTypedArray(), sel.toString(), args.toTypedArray(),
                "${MediaStore.Images.Media.DATE_ADDED} ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0); val added = cur.getLong(1)
                    maxAdded = maxOf(maxAdded, added)
                    if (id.toString() in seen) continue
                    seen += id.toString()
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    if (Refs.addPhoto(c, uri, listOf("Threads")) != null) n++
                }
            }
        } catch (e: Exception) { }
        pr.edit().putLong("th_since", maxAdded).putStringSet("th_seen", seen.toList().takeLast(300).toSet()).apply()
        if (n > 0) notify(c, n)
        return n
    }

    private fun notify(c: Context, n: Int) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("refs_th", "Fotos do Threads nas referências", NotificationManager.IMPORTANCE_LOW))
        val p = p(c); val tot = p.getInt("th_tot", 0) + n; p.edit().putInt("th_tot", tot).apply()
        val open = PendingIntent.getActivity(c, 64, Intent(c, MainActivity::class.java).putExtra("tab", "Refs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = Notification.Builder(c, "refs_th").setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("📌 $n foto(s) do Threads nas Referências")
            .setContentText("Salvas no estoque do Post Leve, sem metadados. Toque pra ver.")
            .setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(open).build()
        try { nm.notify(65, nb) } catch (e: SecurityException) { }
    }
}
