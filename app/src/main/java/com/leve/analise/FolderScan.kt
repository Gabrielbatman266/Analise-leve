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
    fun only2k(c: Context) = p(c).getBoolean("watch_2k", false)
    /** O Flow baixa com nome tipo 247151599_1793933003076088.jpg */
    private val FLOW_NAME = Regex("^\\d{5,}_\\d{12,}\\.(jpe?g|png|webp)$", RegexOption.IGNORE_CASE)
    fun flowNames(c: Context) = p(c).getBoolean("watch_flow_names", true)
    fun setFlowNames(c: Context, on: Boolean) = p(c).edit().putBoolean("watch_flow_names", on).apply()
    fun setOnly2k(c: Context, on: Boolean) = p(c).edit().putBoolean("watch_2k", on).apply()

    val perm: String get() = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"
    val perms: Array<String> get() = if (Build.VERSION.SDK_INT >= 33) arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO") else arrayOf("android.permission.READ_EXTERNAL_STORAGE")
    fun hasPerm(c: Context) = c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    fun hasVideoPerm(c: Context) = c.checkSelfPermission(if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_VIDEO" else "android.permission.READ_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED
    fun videosOn(c: Context) = p(c).getBoolean("watch_videos", true)
    fun setVideosOn(c: Context, on: Boolean) = p(c).edit().putBoolean("watch_videos", on).apply()
    private val FLOW_VID = Regex("^(\\d{5,}_\\d{12,}|.*flow.*)\\.(mp4|mov|webm|m4v)$", RegexOption.IGNORE_CASE)

    /** Vídeos novos do Flow viram esboços (projetos) no editor do Instagram. Retorna quantos. */
    @Synchronized
    fun scanVideos(c: Context, all: Boolean = false): Int {
        val b = bucket(c) ?: return 0
        if (!videosOn(c) || !hasVideoPerm(c) || Build.VERSION.SDK_INT < 29) return 0
        val now = System.currentTimeMillis() / 1000
        val since = if (all) 0L else p(c).getLong("vwatch_since", p(c).getLong("watch_since", now))
        val seen = HashSet(p(c).getStringSet("vwatch_seen", emptySet()) ?: emptySet())
        var n = 0; var maxAdded = since
        try {
            c.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DISPLAY_NAME),
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME + "=? AND " + MediaStore.Video.Media.DATE_ADDED + ">?",
                arrayOf(b, since.toString()), MediaStore.Video.Media.DATE_ADDED + " ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0); val added = cur.getLong(1); val name = cur.getString(2) ?: ""
                    maxAdded = maxOf(maxAdded, added)
                    if (id.toString() in seen) continue
                    if (flowNames(c) && !FLOW_VID.matches(name)) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    val pid = Projects.create(c, false)
                    val f = Projects.srcFile(c, pid, false)
                    val ok = try { c.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }; f.length() > 0 } catch (e: Exception) { false }
                    if (!ok) { Projects.remove(c, pid); continue }
                    Projects.update(c, pid) { it.put("flow", true); it.put("text", ""); it.put("updated", System.currentTimeMillis()) }
                    VideoExport.baseFrame(c, Uri.fromFile(f), false)?.let { Projects.saveThumb(c, pid, it) }
                    seen.add(id.toString()); n++
                    addOriginal(c, id, true)
                }
            }
        } catch (e: Exception) { }
        val keep = if (seen.size > 600) seen.toList().takeLast(600).toSet() else seen
        val e = p(c).edit().putStringSet("vwatch_seen", keep)
        if (!all) e.putLong("vwatch_since", maxAdded)
        e.apply()
        return n
    }

    /** Pastas da galeria com quantas imagens cada uma tem (as com mais imagens recentes primeiro). */
    fun buckets(c: Context): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        try {
            c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME), null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { cur ->
                while (cur.moveToNext()) {
                    val b = cur.getString(0) ?: continue
                    if (b == "PostLeve" || b == "PostLeve Flow") continue
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

    private data class Img(val id: Long, val added: Long, val w: Int, val h: Int, val name: String)

    private fun list(c: Context, sinceSec: Long): List<Img> {
        val b = bucket(c) ?: return emptyList()
        val out = ArrayList<Img>()
        try {
            c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DISPLAY_NAME),
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME + "=? AND " + MediaStore.Images.Media.DATE_ADDED + ">?",
                arrayOf(b, sinceSec.toString()),
                MediaStore.Images.Media.DATE_ADDED + " ASC")?.use { cur ->
                val flow = flowNames(c)
                while (cur.moveToNext()) {
                    val name = cur.getString(4) ?: ""
                    if (flow && !FLOW_NAME.matches(name)) continue
                    out.add(Img(cur.getLong(0), cur.getLong(1), cur.getInt(2), cur.getInt(3), name))
                }
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
                val qid = PostQueue.add(c, bmp)
                PostQueue.update(c, qid) { it.put("nova", true) }
                PostQueue.copyToFolder(c, qid)
                s.add(key); n++
                addOriginal(c, img.id)
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

    // ---------- originais que ficaram no Download ----------
    private fun addOriginal(c: Context, id: Long, video: Boolean = false) {
        val set = HashSet(p(c).getStringSet("orig_pending", emptySet()) ?: emptySet())
        set.add((if (video) "v" else "") + id.toString()); p(c).edit().putStringSet("orig_pending", set).apply()
    }
    fun originals(c: Context): List<Uri> = (p(c).getStringSet("orig_pending", emptySet()) ?: emptySet())
        .mapNotNull { k ->
            val v = k.startsWith("v")
            k.removePrefix("v").toLongOrNull()?.let { ContentUris.withAppendedId(if (v) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
        }
        .filter { u -> try { c.contentResolver.query(u, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.count > 0 } ?: false } catch (e: Exception) { false } }
    fun clearOriginals(c: Context) = p(c).edit().remove("orig_pending").apply()

    /** Pede pro Android acordar o app quando aparecer imagem nova na galeria. */
    fun schedule(c: Context) {
        if (bucket(c) == null || !hasPerm(c)) return
        val js = c.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(JOB_ID, ComponentName(c, FolderJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(4000)
            .setTriggerContentMaxDelay(30000)
            .build()
        try { js.schedule(job) } catch (e: Exception) { }
    }

    fun notifyVideos(c: Context, n: Int) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pasta", "Fotos novas da pasta", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 4, Intent(c, MainActivity::class.java).putExtra("tab", "Instagram"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = Notification.Builder(c, "pasta")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("$n vídeo(s) do Flow viraram esboço")
            .setContentText("Abra na aba Instagram, coloque texto e música e poste.")
            .setContentIntent(open).setAutoCancel(true)
        try { nm.notify(11, nb.build()) } catch (e: SecurityException) { }
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
            val nv = try { FolderScan.scanVideos(this) } catch (e: Exception) { 0 }
            if (nv > 0) FolderScan.notifyVideos(this, nv)
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
