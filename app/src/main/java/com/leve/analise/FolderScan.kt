package com.leve.analise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Vigia uma pasta da galeria (ex.: onde o Flow salva as imagens).
 * Cada imagem nova entra sozinha na fila de posts, sem metadados.
 */
object FolderScan {
    private const val JOB_ID = 42
    const val MIN_2K = 1800 // lado maior mínimo pra contar como 2K (upscale)

    private fun p(c: Context) = Store.prefs(c)
    fun bucket(c: Context): String? = p(c).getString("watch_bucket", null)
    fun only2k(c: Context) = p(c).getBoolean("watch_2k", true)
    fun setOnly2k(c: Context, on: Boolean) = p(c).edit().putBoolean("watch_2k", on).apply()

    val perm: String get() = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"
    fun hasPerm(c: Context) = c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** Pastas da galeria com quantas imagens cada uma tem (as com mais imagens recentes primeiro). */
    fun buckets(c: Context): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        try {
            c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME), null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { cur ->
                while (cur.moveToNext()) {
                    val b = cur.getString(0) ?: continue
                    if (b == "PostLeve") continue
                    counts[b] = (counts[b] ?: 0) + 1
                }
            }
        } catch (e: Exception) { }
        return counts.entries.map { it.key to it.value }
    }

    /** Escolhe a pasta. Daqui pra frente só entram imagens novas. */
    fun choose(c: Context, name: String) {
        p(c).edit().putString("watch_bucket", name)
            .putLong("watch_since", System.currentTimeMillis() / 1000).apply()
        schedule(c)
    }

    fun stop(c: Context) {
        p(c).edit().remove("watch_bucket").apply()
        c.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }

    private data class Img(val id: Long, val added: Long, val w: Int, val h: Int)

    private fun list(c: Context, sinceSec: Long): List<Img> {
        val b = bucket(c) ?: return emptyList()
        val out = ArrayList<Img>()
        try {
            c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT),
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME + "=? AND " + MediaStore.Images.Media.DATE_ADDED + ">?",
                arrayOf(b, sinceSec.toString()),
                MediaStore.Images.Media.DATE_ADDED + " ASC")?.use { cur ->
                while (cur.moveToNext()) out.add(Img(cur.getLong(0), cur.getLong(1), cur.getInt(2), cur.getInt(3)))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun seen(c: Context): MutableSet<String> = HashSet(p(c).getStringSet("watch_seen", emptySet()) ?: emptySet())

    private fun isBig(c: Context, img: Img, uri: Uri): Boolean {
        var w = img.w; var h = img.h
        if (w <= 0 || h <= 0) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try { c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } } catch (e: Exception) { }
            w = o.outWidth; h = o.outHeight
        }
        return maxOf(w, h) >= MIN_2K
    }

    /** Quantas imagens da pasta ainda não entraram na fila (respeitando o filtro 2K). */
    fun countExisting(c: Context): Int {
        val s = seen(c)
        return list(c, 0).count { img ->
            img.id.toString() !in s && (!only2k(c) || isBig(c, img, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, img.id)))
        }
    }

    /** Importa as novas (ou todas, com all=true). Retorna quantas entraram na fila. */
    @Synchronized
    fun scan(c: Context, all: Boolean = false): Int {
        if (!hasPerm(c) || bucket(c) == null) return 0
        val since = if (all) 0L else p(c).getLong("watch_since", System.currentTimeMillis() / 1000)
        val s = seen(c)
        var n = 0
        var maxAdded = since
        for (img in list(c, since)) {
            maxAdded = maxOf(maxAdded, img.added)
            val key = img.id.toString()
            if (key in s) continue
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, img.id)
            if (only2k(c) && !isBig(c, img, uri)) continue
            try {
                val bmp = decode(c, uri) ?: continue
                PostQueue.add(c, bmp)
                s.add(key); n++
            } catch (e: Exception) { }
        }
        val keep = if (s.size > 600) s.toList().takeLast(600).toSet() else s
        val e = p(c).edit().putStringSet("watch_seen", keep)
        if (!all) e.putLong("watch_since", maxAdded)
        e.apply()
        return n
    }

    private fun decode(c: Context, uri: Uri): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var smp = 1
        while (maxOf(o.outWidth, o.outHeight) / (smp * 2) >= 2560) smp *= 2
        val b = c.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = smp })
        } ?: return null
        val deg = try {
            c.contentResolver.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) { 0f }
        return if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
    }

    /** Pede pro Android acordar o app quando aparecer imagem nova na galeria. */
    fun schedule(c: Context) {
        if (bucket(c) == null || !hasPerm(c)) return
        val js = c.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(JOB_ID, ComponentName(c, FolderJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(4000)
            .setTriggerContentMaxDelay(30000)
            .build()
        try { js.schedule(job) } catch (e: Exception) { }
    }

    fun notifyNew(c: Context, n: Int) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pasta", "Fotos novas da pasta", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = Notification.Builder(c, "pasta")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("$n foto(s) nova(s) na fila")
            .setContentText("Vieram da pasta ${bucket(c)}, já sem metadados. Gerando legendas…")
            .setContentIntent(open).setAutoCancel(true)
        try { nm.notify(10, nb.build()) } catch (e: SecurityException) { }
    }
}

class FolderJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val n = try { FolderScan.scan(this) } catch (e: Exception) { 0 }
            if (n > 0) {
                FolderScan.notifyNew(this, n)
                try { LeveService.send(this, LeveService.ACT_ANALYZE) } catch (e: Exception) { }
            }
            FolderScan.schedule(this) // gatilho de conteúdo vale uma vez: reagenda
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
