package com.leve.analise

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONObject
import java.io.File

/**
 * Fotos e vídeos que você salva no Threads ("Salvar mídia"/baixar) → Referências do Post Leve (sem metadados)
 * e o original é apagado da galeria.
 * Reconhece por: app que criou o arquivo (Threads), pasta com "Threads", OU arquivo novo que apareceu
 * enquanto o Threads estava aberto (o leitor de tela avisa) — cobre Xiaomi, Download e pastas estranhas.
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
    fun deleteOn(c: Context) = p(c).getBoolean("th_del_on", true)
    fun setDeleteOn(c: Context, v: Boolean) = p(c).edit().putBoolean("th_del_on", v).apply()

    /** Acesso parcial (Android 14+: "só fotos selecionadas") não deixa ver o que o Threads salva. */
    fun partialOnly(c: Context) = Build.VERSION.SDK_INT >= 34 && !FolderScan.hasPerm(c) &&
        c.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == android.content.pm.PackageManager.PERMISSION_GRANTED
    /** "Gerenciar mídia": apaga sem perguntar toda vez. */
    fun canManage(c: Context) = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(c)

    // ---------- o leitor de tela avisa quando o Threads está aberto
    @Volatile private var fgAt = 0L
    @Volatile private var lastScan = 0L
    fun threadsActive(c: Context) {
        val now = System.currentTimeMillis()
        if (now - fgAt > 5000) p(c).edit().putLong("th_fg", now).apply()
        fgAt = now
        if (now - lastScan > 6000 && on(c)) { lastScan = now; Thread { try { scan(c) } catch (e: Exception) { } }.start() }
    }
    private fun fgRecent(c: Context, addedSec: Long) = Math.abs(addedSec * 1000 - maxOf(fgAt, p(c).getLong("th_fg", 0))) < 150_000L

    private val SKIP = Regex("screenshot|screenrecord|captura|gravador|postleve|post leve|dcim/camera|whatsapp|telegram", RegexOption.IGNORE_CASE)

    @Synchronized
    fun scan(c: Context): Int {
        if (!on(c) || !FolderScan.hasPerm(c)) return 0
        val pr = p(c)
        val nowSec = System.currentTimeMillis() / 1000
        val since = pr.getLong("th_since", 0L).let { if (it == 0L) { pr.edit().putLong("th_since", nowSec).apply(); nowSec } else it }
        val seen = (pr.getStringSet("th_seen", emptySet()) ?: emptySet()).toMutableSet()
        var n = 0; var maxAdded = since; var keepFrom = Long.MAX_VALUE
        val files = MediaStore.Files.getContentUri("external")
        val cols = mutableListOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= 29) { cols += MediaStore.MediaColumns.OWNER_PACKAGE_NAME; cols += MediaStore.MediaColumns.RELATIVE_PATH }
        else @Suppress("DEPRECATION") cols += MediaStore.MediaColumns.DATA
        val sel = "${MediaStore.Files.FileColumns.DATE_ADDED} > ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3)"
        val log = ArrayList<String>()
        try {
            c.contentResolver.query(files, cols.toTypedArray(), sel, arrayOf((since - 5).toString()), "${MediaStore.Files.FileColumns.DATE_ADDED} ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0); val added = cur.getLong(1); val video = cur.getInt(2) == 3; val name = cur.getString(3).orEmpty()
                    val owner = if (Build.VERSION.SDK_INT >= 29) cur.getString(4).orEmpty() else ""
                    val path = if (Build.VERSION.SDK_INT >= 29) cur.getString(5).orEmpty() else cur.getString(4).orEmpty()
                    maxAdded = maxOf(maxAdded, added)
                    val key = (if (video) "v" else "") + id
                    if (key in seen) continue
                    if (owner == c.packageName || SKIP.containsMatchIn(path + "/" + name)) { seen += key; continue }
                    val why = when {
                        owner == THREADS -> "app"
                        path.contains("threads", true) || name.contains("threads", true) -> "pasta"
                        fgRecent(c, added) -> "aberto"
                        else -> null
                    }
                    log += "${name.take(24)} · ${owner.ifEmpty { "?" }} · ${path.take(28)} → ${why ?: "ignorado"}"
                    if (why == null) { if (nowSec - added > 300) seen += key else keepFrom = minOf(keepFrom, added); continue } // pode ser que o Threads ainda vá ser marcado como aberto
                    seen += key
                    val uri = ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val ok = if (video) addVideo(c, uri) else Refs.addPhoto(c, uri, listOf("Threads")) != null
                    if (ok) { n++; queueDelete(c, key) }
                }
            }
        } catch (e: Exception) { ErrLog.log(c, "Threads → Refs", e) }
        pr.edit().putLong("th_since", minOf(maxAdded, nowSec - 1, keepFrom - 1)).putStringSet("th_seen", seen.toList().takeLast(400).toSet()).putLong("th_scan", System.currentTimeMillis()).apply()
        if (log.isNotEmpty()) pr.edit().putString("th_log", log.takeLast(5).joinToString(" | ")).apply()
        if (n > 0) { pr.edit().putInt("th_tot", pr.getInt("th_tot", 0) + n).apply(); deleteNow(c); notify(c, n) }
        return n
    }

    /** Vídeo salvo: guarda o vídeo nas referências + um quadro do meio como foto (pra ver e mandar pro Flow). */
    private fun addVideo(c: Context, uri: Uri): Boolean = try {
        val r = android.media.MediaMetadataRetriever(); r.setDataSource(c, uri)
        val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val fr: Bitmap? = r.getFrameAtTime(dur * 500, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC); r.release()
        if (fr == null) false else {
            val tmp = File(c.cacheDir, "thv_${System.currentTimeMillis()}.jpg"); tmp.outputStream().use { fr.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val rid = Refs.addPhoto(c, Uri.fromFile(tmp), listOf("Threads", "vídeo")); tmp.delete()
            if (rid != null) {
                val vf = File(File(c.filesDir, "refs_videos").apply { mkdirs() }, "$rid.mp4")
                c.contentResolver.openInputStream(uri)?.use { i -> vf.outputStream().use { i.copyTo(it) } }
                Refs.updatePhoto(c, rid) { it.put("video", vf.path) }
            }
            rid != null
        }
    } catch (e: Exception) { ErrLog.log(c, "Threads → Refs (vídeo)", e); false }

    // ---------- apagar o original da galeria
    private fun queueDelete(c: Context, key: String) {
        if (!deleteOn(c)) return
        val s = (p(c).getStringSet("th_del", emptySet()) ?: emptySet()).toMutableSet(); s += key
        p(c).edit().putStringSet("th_del", s).apply()
    }
    fun pendingDelete(c: Context): List<Uri> = (p(c).getStringSet("th_del", emptySet()) ?: emptySet()).mapNotNull { k ->
        val v = k.startsWith("v"); k.removePrefix("v").toLongOrNull()?.let { ContentUris.withAppendedId(if (v) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
    }.filter { u -> try { c.contentResolver.query(u, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false } catch (e: Exception) { false } }
        .also { if (it.isEmpty()) p(c).edit().remove("th_del").apply() }
    fun clearDeleted(c: Context) { pendingDelete(c) }

    /** Tenta apagar já: com "Gerenciar mídia" liberado o Android não pergunta; senão fica uma notificação de 1 toque. */
    fun deleteNow(c: Context) {
        val l = pendingDelete(c); if (l.isEmpty()) return
        l.forEach { u -> try { c.contentResolver.delete(u, null, null) } catch (e: Exception) { } } // funciona se o arquivo for "nosso"
        val left = pendingDelete(c); if (left.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30 && canManage(c) && Settings.canDrawOverlays(c)) {
            try { MediaStore.createDeleteRequest(c.contentResolver, left).send(); return } catch (e: Exception) { }
        }
        if (Build.VERSION.SDK_INT >= 30) notifyDelete(c, left.size)
    }

    private fun notifyDelete(c: Context, n: Int) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("refs_th", "Fotos do Threads nas referências", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(c, 66, Intent(c, ThreadsDelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { nm.notify(66, Notification.Builder(c, "refs_th").setSmallIcon(android.R.drawable.ic_menu_delete).setContentTitle("🗑 Apagar $n original(is) do Threads da galeria")
            .setContentText("Já estão nas Referências. Toque pra apagar da galeria. (Libere \"Gerenciar mídia\" pra não precisar tocar.)")
            .setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(true).build()) } catch (e: SecurityException) { }
    }

    private fun notify(c: Context, n: Int) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("refs_th", "Fotos do Threads nas referências", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(c, 64, Intent(c, MainActivity::class.java).putExtra("tab", "Refs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = Notification.Builder(c, "refs_th").setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("📌 $n mídia(s) do Threads nas Referências")
            .setContentText(if (deleteOn(c)) "Guardadas sem metadados e apagadas da galeria." else "Guardadas no Post Leve, sem metadados.")
            .setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(open).build()
        try { nm.notify(65, nb) } catch (e: SecurityException) { }
    }

    fun status(c: Context): String {
        val lines = ArrayList<String>()
        lines += when {
            !on(c) -> "Desligado."
            partialOnly(c) -> "⚠ O acesso às fotos está como \"só selecionadas\": assim não dá pra ver o que o Threads salva. Toque em Permitir e escolha \"Permitir tudo\"."
            !FolderScan.hasPerm(c) -> "⚠ Falta o acesso às fotos e vídeos."
            !RateWatch.readerOn(c) -> "Ligado. Dica: com o Leitor de tela ligado ele pega na hora, até de pastas estranhas."
            else -> "✓ Ligado: pega na hora o que você salva no Threads."
        }
        lines += "Já trouxe ${p(c).getInt("th_tot", 0)} · última olhada: " + (p(c).getLong("th_scan", 0).takeIf { it > 0 }?.let { android.text.format.DateFormat.format("dd/MM HH:mm", it) } ?: "nunca")
        if (deleteOn(c)) lines += if (canManage(c)) "🗑 Apaga o original sozinho." else "🗑 Apaga o original com 1 toque na notificação (libere \"Gerenciar mídia\" pra ser automático)."
        return lines.joinToString("\n")
    }
    fun diag(c: Context) = status(c).replace("\n", " · ") + " · pendentes p/ apagar: ${(p(c).getStringSet("th_del", emptySet()) ?: emptySet()).size} · últimos vistos: " + (p(c).getString("th_log", "nenhum") ?: "")

    fun openManageMedia(c: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        try { c.startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { try { c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (x: Exception) { } }
    }
}

/** Tela invisível: pede pro Android apagar os originais do Threads (uma confirmação pra todos). */
class ThreadsDelActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val l = ThreadsRefs.pendingDelete(this)
        if (l.isEmpty() || Build.VERSION.SDK_INT < 30) { finish(); return }
        try { @Suppress("DEPRECATION") startIntentSenderForResult(MediaStore.createDeleteRequest(contentResolver, l).intentSender, 1, null, 0, 0, 0) } catch (e: Exception) { finish() }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        ThreadsRefs.clearDeleted(this)
        android.widget.Toast.makeText(this, if (res == RESULT_OK) "Apagadas da galeria (continuam nas Referências)" else "Mantidas na galeria", android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }
}
