package com.leve.analise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ============================================================ 2) categoria automática dos vídeos novos
object AutoCat {
    fun on(c: Context) = Store.prefs(c).getBoolean("auto_classify", true)
    fun setOn(c: Context, v: Boolean) = Store.prefs(c).edit().putBoolean("auto_classify", v).apply()

    /** A IA olha cada vídeo novo e põe na categoria que já existe e combina (senão, fica na padrão). */
    fun classify(c: Context, pids: List<String>): Int {
        val cats = Contexts.list(c)
        if (!on(c) || cats.size < 2 || pids.isEmpty()) return 0
        var n = 0
        pids.chunked(6).forEach { lote ->
            if (!Auto.canSpend(c)) return n
            val projs = lote.mapNotNull { id -> Projects.list(c).firstOrNull { it.optString("id") == id } }
            val frames = projs.mapNotNull { p -> AutoProducer.frameOf(c, p)?.let { p to it } }
            if (frames.isEmpty()) return@forEach
            val r = try { Ai.classify(c, frames.map { it.second }, cats.map { it.optString("name") to it.optString("desc") }) } catch (e: Exception) { return n }
            frames.forEachIndexed { k, (p, _) ->
                val name = r.getOrNull(k).orEmpty().trim()
                val hit = cats.firstOrNull { it.optString("name").equals(name, true) }?.optString("name") ?: return@forEachIndexed
                Projects.update(c, p.optString("id")) { it.put("ctx", hit); it.put("autoCat", true) }; n++
            }
        }
        return n
    }
}

// ============================================================ 3) estoque + ideias pro Flow
object Stock {
    private fun p(c: Context) = Store.prefs(c)

    /** Posts por dia nos últimos 7 dias (mín. 1). */
    private fun rate(c: Context, video: Boolean): Double {
        val week = System.currentTimeMillis() - 7 * 86400_000L
        val n = SendLog.all(c).count { it.optLong("at") > week && (it.optString("kind") == "video") == video }
        return maxOf(1.0, n / 7.0)
    }

    /** Dias até acabar o que é novo (nunca postado). */
    fun daysPhotos(c: Context) = PostQueue.load(c).count { it.optString("status") != "postada" && it.optString("status") != "arquivada" && PostQueue.imageFile(c, it.optString("id")).exists() } / rate(c, false)
    fun daysVideos(c: Context): Double {
        val waiting = VideoExport.library(c).count { it.optInt("posts") == 0 && !it.optBoolean("arquivado") }
        val raws = Projects.list(c).count { pj -> VideoExport.library(c).none { it.optString("proj") == pj.optString("id") } }
        return (waiting + raws) / rate(c, true)
    }

    fun ideas(c: Context): List<String> = try { val a = JSONArray(p(c).getString("flow_ideas", "[]")); (0 until a.length()).map { a.optString(it) } } catch (e: Exception) { emptyList() }

    /** Pede à IA prompts de Flow parecidos com o que bombou (fotos e vídeos), sem descrever rosto/identidade. */
    fun makeIdeas(c: Context): List<String> {
        val bestPhotos = PostQueue.load(c).filter { Learn.isWin(it) && PostQueue.imageFile(c, it.optString("id")).exists() }.sortedByDescending { Learn.mean(it) }.take(4)
        val bestVids = VideoExport.library(c).filter { Learn.isWin(it) && File(VideoExport.videoDir(c), "${it.optString("id")}.jpg").exists() }.sortedByDescending { Learn.mean(it) }.take(3)
        val files = bestPhotos.map { PostQueue.imageFile(c, it.optString("id")) } + bestVids.map { File(VideoExport.videoDir(c), "${it.optString("id")}.jpg") }
        if (files.isEmpty()) throw AiException(-3, "Ainda não tem post com 🔥/💎 pra eu copiar o estilo. Avalie alguns posts.")
        val r = Ai.flowIdeas(c, files, bestPhotos.size, Captions.learned(c) + Learn.videoRules(c))
        if (r.isNotEmpty()) p(c).edit().putString("flow_ideas", JSONArray(r).toString()).putLong("flow_ideas_at", System.currentTimeMillis()).apply()
        return r
    }

    /** Uma vez por dia: se fotos ou vídeos acabam em menos de 2 dias, avisa com ideias pro Flow. */
    fun check(c: Context) {
        val now = System.currentTimeMillis()
        if (now - p(c).getLong("stock_ask", 0) < 20 * 3600_000L) return
        val df = daysPhotos(c); val dv = daysVideos(c)
        if (df >= 2 && dv >= 2) return
        if (ideas(c).isEmpty() || now - p(c).getLong("flow_ideas_at", 0) > 3 * 86400_000L) { if (Auto.canSpend(c)) try { makeIdeas(c) } catch (e: Exception) { } }
        p(c).edit().putLong("stock_ask", now).apply()
        val lines = ArrayList<String>()
        if (df < 2) lines += "📸 Fotos novas acabam em ~${"%.1f".format(df)} dia(s)"
        if (dv < 2) lines += "🎬 Vídeos novos acabam em ~${"%.1f".format(dv)} dia(s)"
        val id = ideas(c)
        if (id.isNotEmpty()) lines += "💡 Ideia pro Flow (no estilo do que bombou):\n" + id.first()
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("estoque", "Estoque", NotificationManager.IMPORTANCE_DEFAULT))
        val copy = PendingIntent.getBroadcast(c, 97, Intent(c, AdminReceiver::class.java).setAction("copiar_ideia"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val flow = PendingIntent.getActivity(c, 98, Intent(Intent.ACTION_VIEW, Uri.parse("https://labs.google/fx/tools/flow")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, "estoque").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("📦 Estoque acabando").setContentText(lines.first())
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n"))).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "📋 Copiar ideia", copy).build())
            .addAction(Notification.Action.Builder(null, "Abrir o Flow", flow).build())
        try { nm.notify(96, b.build()) } catch (e: SecurityException) { }
    }
}

// ============================================================ 4) arquivo: tira da fila o que flopou (com reteste)
object Archive {
    private fun p(c: Context) = Store.prefs(c)
    fun on(c: Context) = p(c).getBoolean("archive_on", true)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("archive_on", v).apply()
    private const val RETEST = 14 * 86400_000L

    private fun flop(o: JSONObject) = Learn.rated(o) >= 2 && Learn.mean(o) < 0.4 && o.optInt("top") == 0

    /** Arquiva o que flopou 2+ vezes; depois de 14 dias dá UMA última chance (reteste). Devolve o que fez. */
    fun tick(c: Context): String? {
        if (!on(c)) return null
        val now = System.currentTimeMillis()
        var fa = 0; var va = 0; var ft = 0; var vt = 0
        PostQueue.load(c).forEach { o ->
            val id = o.optString("id")
            if (o.optString("status") == "arquivada") {
                if (!o.optBoolean("retestado") && now - o.optLong("arqAt") > RETEST) { PostQueue.update(c, id) { it.put("status", "postada"); it.put("retestado", true) }; ft++ }
            } else if (flop(o) && !(o.optBoolean("retestado") && Learn.hist(o).lastOrNull()?.second != "bad")) {
                if (!o.optBoolean("retestado") || Learn.hist(o).lastOrNull()?.second == "bad") { PostQueue.update(c, id) { it.put("status", "arquivada"); it.put("arqAt", now) }; fa++ }
            }
        }
        VideoExport.library(c).forEach { o ->
            val id = o.optString("id")
            if (o.optBoolean("arquivado")) {
                if (!o.optBoolean("retestado") && now - o.optLong("arqAt") > RETEST) { VideoExport.updateLib(c, id) { it.put("arquivado", false); it.put("retestado", true) }; vt++ }
            } else if (flop(o) && (!o.optBoolean("retestado") || Learn.hist(o).lastOrNull()?.second == "bad")) {
                VideoExport.updateLib(c, id) { it.put("arquivado", true); it.put("arqAt", now) }; va++
            }
        }
        val parts = ArrayList<String>()
        if (fa + va > 0) parts += "🗄 arquivei ${fa + va} que flopparam 2+ vezes"
        if (ft + vt > 0) parts += "🔁 ${ft + vt} voltaram pra um último teste"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun photos(c: Context) = PostQueue.load(c).filter { it.optString("status") == "arquivada" }
    fun videos(c: Context) = VideoExport.library(c).filter { it.optBoolean("arquivado") }
    fun restorePhoto(c: Context, id: String) = PostQueue.update(c, id) { it.put("status", "postada"); it.put("retestado", true) }
    fun restoreVideo(c: Context, id: String) = VideoExport.updateLib(c, id) { it.put("arquivado", false); it.put("retestado", true) }
    fun deleteAll(c: Context): Int {
        val ps = photos(c); val vs = videos(c)
        ps.forEach { PostQueue.remove(c, it.optString("id")) }
        vs.forEach { VideoExport.removeLib(c, it.optString("id")) }
        return ps.size + vs.size
    }
}

// ============================================================ 6) backup semanal
object Backup {
    private fun p(c: Context) = Store.prefs(c)
    private val FILES = listOf("posts.json", "fila.json", "projetos.json", "videos.json", "musicas.json", "legendas.json", "overlay_db.json",
        "envios.json", "curtidas.json", "refs_links.json", "refs_fotos.json", "caption_db.json")
    fun last(c: Context) = p(c).getLong("bk_at", 0)

    /** Copia o aprendizado (bancos, notas, filas, histórico, configurações) pra Download/PostLeve Backup. Sem as fotos/vídeos (ficam no app). */
    fun run(c: Context): String {
        val name = "postleve-" + android.text.format.DateFormat.format("yyyy-MM-dd-HHmm", System.currentTimeMillis()) + ".zip"
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            c.filesDir.walkTopDown().filter { it.isFile && it.extension == "json" && it.length() < 20_000_000 }.forEach { f ->
                z.putNextEntry(ZipEntry("files/" + f.relativeTo(c.filesDir).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
            val pf = File(c.applicationInfo.dataDir, "shared_prefs/leve.xml")
            if (pf.exists()) { z.putNextEntry(ZipEntry("prefs/leve.xml")); pf.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/PostLeve Backup")
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw Exception("Não consegui criar o arquivo")
            c.contentResolver.openOutputStream(uri)?.use { it.write(bytes.toByteArray()) }
        } else {
            @Suppress("DEPRECATION") val d = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "PostLeve Backup").apply { mkdirs() }
            File(d, name).writeBytes(bytes.toByteArray())
        }
        File(c.filesDir, "backup_ultimo.zip").writeBytes(bytes.toByteArray()) // cópia interna também
        p(c).edit().putLong("bk_at", System.currentTimeMillis()).apply()
        return "Backup salvo em Download/PostLeve Backup/$name (${bytes.size() / 1024} KB)"
    }

    fun weekly(c: Context): String? = if (System.currentTimeMillis() - last(c) > 7 * 86400_000L) try { run(c) } catch (e: Exception) { null } else null

    /** Restaura um backup (.zip) e reinicia o app. */
    fun restore(c: Context, uri: Uri): String {
        var n = 0
        c.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val target = when {
                        e.name.startsWith("files/") -> File(c.filesDir, e.name.removePrefix("files/"))
                        e.name == "prefs/leve.xml" -> File(c.applicationInfo.dataDir, "shared_prefs/leve.xml")
                        else -> null
                    }
                    if (target != null && !e.isDirectory && target.canonicalPath.startsWith(File(c.applicationInfo.dataDir).canonicalPath)) {
                        target.parentFile?.mkdirs(); target.outputStream().use { z.copyTo(it) }; n++
                    }
                    e = z.nextEntry
                }
            }
        }
        return "$n arquivo(s) restaurados"
    }
}

// ============================================================ 7) teste A/B de legenda
object AB {
    fun on(c: Context) = Store.prefs(c).getBoolean("ab_on", true)
    fun setOn(c: Context, v: Boolean) = Store.prefs(c).edit().putBoolean("ab_on", v).apply()

    /** 1 em cada 4 posts: pega uma foto que já tem nota e posta com uma legenda NOVA pra ela (do banco das que bombam). */
    fun pick(c: Context, all: List<JSONObject>, rec: List<String>): Pair<String, String>? {
        if (!on(c) || Math.random() > 0.25) return null
        val cand = all.filter { Learn.rated(it) >= 1 && it.optString("status") != "arquivada" && it.optString("id") !in rec.take(maxOf(4, (all.size * 0.4).toInt())) }
        val o = cand.randomOrNull() ?: return null
        val tried = SendLog.all(c).filter { it.optString("id") == o.optString("id") }.map { it.optString("cap").trim().lowercase() }.toSet() + PostQueue.captionOf(o).trim().lowercase()
        val pool = (CaptionDB.winners(c, 30).map { it.optString("t") } + CaptionDB.texts(c, "var").take(30) + Captions.mine(c))
            .filter { it.isNotBlank() && it.trim().lowercase() !in tried && it.trim() !in Captions.capAvoid(c) }
        val cap = pool.randomOrNull() ?: return null
        Store.prefs(c).edit().putString("ab_last", o.optString("id")).apply()
        return o.optString("id") to cap
    }

    /** Resultado: fotos postadas com 2+ legendas diferentes e notas. */
    fun report(c: Context): String {
        val byPhoto = SendLog.all(c).filter { it.optString("kind") == "fila" && it.optString("nota").isNotEmpty() }.groupBy { it.optString("id") }
            .filterValues { l -> l.map { it.optString("cap").trim().lowercase() }.distinct().size >= 2 }
        if (byPhoto.isEmpty()) return "🧪 Testes A/B: ainda nenhuma foto testada com 2 legendas (1 em cada 4 posts é teste)."
        val sc = { k: String -> when (k) { "top" -> 2.0; "good" -> 1.0; "meh" -> 0.5; else -> 0.0 } }
        var diff = 0
        byPhoto.values.forEach { l -> val s = l.map { sc(it.optString("nota")) }; if ((s.maxOrNull() ?: 0.0) - (s.minOrNull() ?: 0.0) >= 1.0) diff++ }
        val pct = diff * 100 / byPhoto.size
        return "🧪 Testes A/B: ${byPhoto.size} foto(s) testadas com legendas diferentes. A legenda mudou o resultado em $pct% delas" +
            (if (pct >= 50) " → a LEGENDA pesa muito: o app prioriza as legendas vencedoras." else " → a FOTO pesa mais que a legenda.")
    }
}

class AdminReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == "copiar_ideia") {
            Stock.ideas(c).firstOrNull()?.let { c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ideia", it)) }
            android.widget.Toast.makeText(c, "Ideia copiada: cole no Flow", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
