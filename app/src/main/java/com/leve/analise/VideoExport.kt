package com.leve.analise

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Estilo do texto no vídeo, no jeito do CapCut. */
data class TextStyle(
    var size: Int = 52,          // 20..100 (milésimos da largura)
    var color: String = "Branco",
    var bg: String = "Faixa escura",
    var pos: String = "Baixo",
    var font: String = "Negrito",
    var align: String = "Centro",
) {
    fun save(c: Context) = Store.prefs(c).edit().putString("txt_style",
        JSONObject().put("size", size).put("color", color).put("bg", bg).put("pos", pos).put("font", font).put("align", align).toString()).apply()

    companion object {
        val COLORS = linkedMapOf("Branco" to Color.WHITE, "Preto" to Color.BLACK, "Amarelo" to 0xFFFFD43B.toInt(),
            "Rosa" to 0xFFFF5C82.toInt(), "Vermelho" to 0xFFFF3B3B.toInt(), "Azul" to 0xFF4DA3FF.toInt())
        val BGS = listOf("Sem fundo", "Faixa escura", "Faixa clara", "Contorno")
        val POS = listOf("Cima", "Meio", "Baixo")
        val FONTS = listOf("Negrito", "Fina", "Serifa", "Manuscrita", "Condensada")
        val ALIGNS = listOf("Esquerda", "Centro", "Direita")
        fun load(c: Context): TextStyle = try {
            val o = JSONObject(Store.prefs(c).getString("txt_style", "{}") ?: "{}")
            TextStyle(o.optInt("size", 52), o.optString("color", "Branco"), o.optString("bg", "Faixa escura"),
                o.optString("pos", "Baixo"), o.optString("font", "Negrito"), o.optString("align", "Centro"))
        } catch (e: Exception) { TextStyle() }
    }
}

/** Edição e exportação dos SEUS vídeos e fotos: 1080x1920, texto, música de fundo, biblioteca. */
object VideoExport {
    const val INSTAGRAM = "com.instagram.android"
    const val W = 1080
    const val H = 1920
    private var current: Transformer? = null

    // ------------------------------------------------------------------ texto

    fun typeface(f: String): Typeface = when (f) {
        "Fina" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        "Serifa" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        "Manuscrita" -> Typeface.create("cursive", Typeface.BOLD)
        "Condensada" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    /** Camada transparente do tamanho do quadro com o texto (aceita parágrafos). */
    fun textBitmap(text: String, st: TextStyle, w: Int = W, h: Int = H): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (text.isBlank()) return bmp
        val cv = Canvas(bmp)
        val color = TextStyle.COLORS[st.color] ?: Color.WHITE
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = w * st.size / 1000f; typeface = typeface(st.font)
            if (st.bg == "Sem fundo") setShadowLayer(w * 0.008f, 0f, w * 0.003f, 0xB0000000.toInt())
        }
        val align = when (st.align) { "Esquerda" -> Layout.Alignment.ALIGN_NORMAL; "Direita" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER }
        val maxW = (w * 0.84f).toInt()
        @Suppress("DEPRECATION")
        val sl = StaticLayout(text.trimEnd(), tp, maxW, align, 1.12f, 0f, false)
        val left = (w - maxW) / 2f
        val top = when (st.pos) { "Cima" -> h * 0.12f; "Meio" -> (h - sl.height) / 2f; else -> h * 0.80f - sl.height }
        if (st.bg == "Faixa escura" || st.bg == "Faixa clara") {
            var minL = maxW.toFloat(); var maxR = 0f
            for (i in 0 until sl.lineCount) { minL = minOf(minL, sl.getLineLeft(i)); maxR = maxOf(maxR, sl.getLineRight(i)) }
            val padX = w * 0.035f; val padY = w * 0.022f
            val box = RectF(left + minL - padX, top - padY, left + maxR + padX, top + sl.height + padY)
            val bgc = if (st.bg == "Faixa escura") 0x99000000.toInt() else 0xE6FFFFFF.toInt()
            cv.drawRoundRect(box, w * 0.03f, w * 0.03f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = bgc })
        }
        cv.save(); cv.translate(left, top)
        if (st.bg == "Contorno") {
            val stroke = TextPaint(tp).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.006f; this.color = if (color == Color.BLACK) Color.WHITE else Color.BLACK }
            @Suppress("DEPRECATION")
            StaticLayout(text.trimEnd(), stroke, maxW, align, 1.12f, 0f, false).draw(cv)
        }
        sl.draw(cv); cv.restore()
        return bmp
    }

    // ------------------------------------------------------------------ prévia

    /** Primeiro quadro (vídeo) ou a foto, encaixado em 9:16 com fundo preto. */
    fun baseFrame(c: Context, src: Uri, isPhoto: Boolean): Bitmap? = try {
        val raw: Bitmap? = if (isPhoto) {
            val o = BitmapFactory.Options().apply { inSampleSize = 2 }
            c.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, o) }
        } else {
            val r = MediaMetadataRetriever()
            r.setDataSource(c, src)
            val b = r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            r.release(); b
        }
        raw?.let { fit(it, W / 2, H / 2) }
    } catch (e: Exception) { null }

    private fun fit(b: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out); cv.drawColor(Color.BLACK)
        val k = minOf(w.toFloat() / b.width, h.toFloat() / b.height)
        val dw = (b.width * k).toInt(); val dh = (b.height * k).toInt()
        cv.drawBitmap(b, null, Rect((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun preview(base: Bitmap, text: String, st: TextStyle): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(textBitmap(text, st, out.width, out.height), 0f, 0f, null)
        return out
    }

    // ------------------------------------------------------------------ exportar

    class Job(val src: Uri, val isPhoto: Boolean, val photoSeconds: Int, val text: String, val style: TextStyle,
              val music: File?, val mute: Boolean, val quality: String = "1080p")

    /** Tamanho (9:16), bitrate e codec por qualidade. 2K/4K usam HEVC: mesma qualidade com arquivo bem menor. */
    data class Q(val w: Int, val h: Int, val bitrate: Int, val mime: String)
    fun q(name: String) = when (name) {
        "2K" -> Q(1440, 2560, 22_000_000, MimeTypes.VIDEO_H265)
        "4K" -> Q(2160, 3840, 40_000_000, MimeTypes.VIDEO_H265)
        else -> Q(1080, 1920, 16_000_000, MimeTypes.VIDEO_H264)
    }

    /**
     * Roda na thread principal (o Transformer precisa de Looper).
     * Vídeo pronto vai pra biblioteca do app e pra galeria (Movies/PostLeve).
     */
    fun export(c: Context, job: Job, onProgress: (Int) -> Unit, onDone: (File?, String?) -> Unit) {
        val out = File(c.cacheDir, "exp_${System.currentTimeMillis()}.mp4")
        val qq = q(job.quality)
        try {
            val fx = ArrayList<androidx.media3.common.Effect>()
            fx.add(Presentation.createForWidthAndHeight(qq.w, qq.h, Presentation.LAYOUT_SCALE_TO_FIT))
            if (job.text.isNotBlank()) fx.add(OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(textBitmap(job.text, job.style, qq.w, qq.h)))))
            val media = if (job.isPhoto) MediaItem.Builder().setUri(job.src).setMimeType(MimeTypes.IMAGE_JPEG).build() else MediaItem.fromUri(job.src)
            val vb = EditedMediaItem.Builder(media).setEffects(Effects(emptyList(), fx))
            if (job.isPhoto) {
                @Suppress("DEPRECATION")
                vb.setDurationUs(job.photoSeconds * 1_000_000L)
                vb.setFrameRate(30)
            }
            if (job.music != null || job.mute) vb.setRemoveAudio(true)
            val seqs = ArrayList<EditedMediaItemSequence>()
            seqs.add(EditedMediaItemSequence(vb.build()))
            if (job.music != null) {
                seqs.add(EditedMediaItemSequence(listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(job.music))).build()), true))
            }
            val comp = Composition.Builder(seqs).build()
            val enc = DefaultEncoderFactory.Builder(c)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(qq.bitrate).build())
                .setEnableFallback(true).build()
            val t = Transformer.Builder(c)
                .setVideoMimeType(qq.mime).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(enc)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { current = null; onDone(out, null) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        current = null; out.delete(); onDone(null, exportException.message ?: "Erro ao exportar")
                    }
                }).build()
            current = t
            t.start(comp, out.path)
            pollProgress(onProgress)
        } catch (e: Exception) { onDone(null, e.message) }
    }

    /** Só o áudio de um vídeo seu, guardado na biblioteca de músicas. */
    fun extractAudio(c: Context, src: Uri, onProgress: (Int) -> Unit, onDone: (File?, String?) -> Unit, name: String? = null) {
        val base = name?.replace(Regex("[^A-Za-z0-9._ -]"), "_")?.substringBeforeLast('.')?.take(50)?.ifBlank { null } ?: "audio_${System.currentTimeMillis()}"
        var out = File(musicDir(c), "$base.m4a")
        var n = 2
        while (out.exists()) { out = File(musicDir(c), "$base ($n).m4a"); n++ }
        try {
            val t = Transformer.Builder(c).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        current = null; saveToGallery(c, out, true); onDone(out, null)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        current = null; out.delete(); onDone(null, exportException.message ?: "Erro ao extrair")
                    }
                }).build()
            current = t
            t.start(EditedMediaItem.Builder(MediaItem.fromUri(src)).setRemoveVideo(true).build(), out.path)
            pollProgress(onProgress)
        } catch (e: Exception) { onDone(null, e.message) }
    }

    private fun pollProgress(onProgress: (Int) -> Unit) {
        val ph = ProgressHolder()
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.post(object : Runnable {
            override fun run() {
                val tr = current ?: return
                if (tr.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(ph.progress)
                h.postDelayed(this, 400)
            }
        })
    }

    fun busy() = current != null
    fun cancel() { try { current?.cancel() } catch (e: Exception) { }; current = null }

    // ------------------------------------------------------------------ músicas

    fun musicDir(c: Context) = File(c.filesDir, "musicas").apply { mkdirs() }
    fun musics(c: Context): List<File> = (musicDir(c).listFiles() ?: emptyArray()).filter { it.length() > 0 }.sortedByDescending { it.lastModified() }

    fun importMusic(c: Context, uri: Uri): File? = try {
        var name = "musica_${System.currentTimeMillis()}"
        c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0)?.let { n -> name = n } }
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(60)
        val f = File(musicDir(c), safe)
        c.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
        f
    } catch (e: Exception) { null }

    fun nice(f: File) = f.nameWithoutExtension.replace('_', ' ')

    fun displayName(c: Context, u: Uri): String? = try {
        c.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ biblioteca de vídeos

    fun videoDir(c: Context) = File(c.filesDir, "videos").apply { mkdirs() }
    private fun libFile(c: Context) = File(c.filesDir, "videos.json")

    fun library(c: Context): MutableList<JSONObject> = try {
        val a = JSONArray(libFile(c).readText()); (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
    } catch (e: Exception) { mutableListOf() }

    private fun saveLib(c: Context, l: List<JSONObject>) { val a = JSONArray(); l.forEach { a.put(it) }; libFile(c).writeText(a.toString()) }

    /** Guarda o vídeo pronto no app (com miniatura e legenda) e uma cópia na galeria. */
    fun addToLibrary(c: Context, tmp: File, caption: String): String {
        val id = "v" + System.currentTimeMillis().toString(36)
        val f = File(videoDir(c), "$id.mp4")
        tmp.copyTo(f, true); tmp.delete()
        try {
            val r = MediaMetadataRetriever(); r.setDataSource(f.path)
            r.getFrameAtTime(500_000)?.let { b ->
                val t = Bitmap.createScaledBitmap(b, 216, 384, true)
                File(videoDir(c), "$id.jpg").outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            }
            r.release()
        } catch (e: Exception) { }
        saveToGallery(c, f, false)
        val l = library(c)
        l.add(0, JSONObject().put("id", id).put("legenda", caption).put("at", System.currentTimeMillis()).put("posts", 0))
        saveLib(c, l)
        return id
    }

    fun updateLib(c: Context, id: String, f: (JSONObject) -> Unit) { val l = library(c); l.firstOrNull { it.optString("id") == id }?.let(f); saveLib(c, l) }
    fun removeLib(c: Context, id: String) {
        saveLib(c, library(c).filter { it.optString("id") != id })
        File(videoDir(c), "$id.mp4").delete(); File(videoDir(c), "$id.jpg").delete()
    }

    /** Cópia nova (arquivo novo, sem dados antigos) só pra postar; a anterior é apagada. */
    fun freshCopyForPost(c: Context, id: String): Uri? {
        val p = Store.prefs(c)
        p.getString("ig_last_copy", null)?.let { try { c.contentResolver.delete(Uri.parse(it), null, null) } catch (e: Exception) { } }
        val u = saveToGallery(c, File(videoDir(c), "$id.mp4"), false, "post_") ?: return null
        p.edit().putString("ig_last_copy", u.toString()).apply()
        return u
    }

    fun saveToGallery(c: Context, f: File, audio: Boolean, prefix: String = ""): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val cr = c.contentResolver
            val col = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, prefix + (if (audio) "audio_" else "video_") + System.currentTimeMillis() + if (audio) ".m4a" else ".mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, if (audio) "audio/mp4" else "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/PostLeve")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(col, cv) ?: return null
            cr.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) { null }
    }

    /** Abre o Instagram com o arquivo; a legenda vai pra área de transferência pra colar. */
    fun shareToInstagram(c: Context, uri: Uri, mime: String): Intent {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(c.contentResolver, "post", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val direct = Intent(i).setPackage(INSTAGRAM)
        return if (direct.resolveActivity(c.packageManager) != null) direct
               else Intent.createChooser(i, "Postar em…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
