package com.leve.analise

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.File

/** Exporta o SEU vídeo em 1080x1920, arquivo novo, com texto embaixo; ou extrai só o áudio dele. */
object VideoExport {
    const val INSTAGRAM = "com.instagram.android"
    private var current: Transformer? = null

    /** Texto branco com fundo escuro arredondado, na parte de baixo do quadro. */
    fun textBitmap(text: String, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = w * 0.052f; typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
        }
        val maxW = (w * 0.84f).toInt()
        @Suppress("DEPRECATION")
        val sl = StaticLayout(text, tp, maxW, Layout.Alignment.ALIGN_CENTER, 1.1f, 0f, false)
        val padX = w * 0.04f; val padY = w * 0.025f
        val top = h * 0.80f - sl.height
        val box = RectF((w - maxW) / 2f - padX, top - padY, (w + maxW) / 2f + padX, top + sl.height + padY)
        cv.drawRoundRect(box, w * 0.03f, w * 0.03f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() })
        cv.save(); cv.translate((w - maxW) / 2f, top); sl.draw(cv); cv.restore()
        return bmp
    }

    /**
     * Roda na thread principal (o Transformer exige um Looper).
     * onProgress recebe 0..100; onDone recebe o arquivo salvo na galeria, ou o erro.
     */
    fun export(c: Context, src: Uri, overlay: String, audioOnly: Boolean,
               onProgress: (Int) -> Unit, onDone: (Uri?, String?) -> Unit) {
        val out = File(c.cacheDir, "exp_${System.currentTimeMillis()}" + if (audioOnly) ".m4a" else ".mp4")
        val builder = EditedMediaItem.Builder(MediaItem.fromUri(src))
        if (audioOnly) {
            builder.setRemoveVideo(true)
        } else {
            val fx = ArrayList<androidx.media3.common.Effect>()
            fx.add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT))
            if (overlay.isNotBlank()) {
                fx.add(OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(textBitmap(overlay.trim(), 1080, 1920)))))
            }
            builder.setEffects(Effects(emptyList(), fx))
        }
        val enc = DefaultEncoderFactory.Builder(c)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(16_000_000).build())
            .build()
        val t = Transformer.Builder(c)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(enc)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    current = null
                    val u = saveToGallery(c, out, audioOnly)
                    out.delete()
                    onDone(u, if (u == null) "Não consegui salvar na galeria" else null)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    current = null; out.delete()
                    onDone(null, exportException.message ?: "Erro ao exportar")
                }
            })
            .build()
        current = t
        t.start(builder.build(), out.path)
        val ph = ProgressHolder()
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.post(object : Runnable {
            override fun run() {
                val tr = current ?: return
                if (tr.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(ph.progress)
                h.postDelayed(this, 500)
            }
        })
    }

    fun cancel() { try { current?.cancel() } catch (e: Exception) { }; current = null }

    private fun saveToGallery(c: Context, f: File, audio: Boolean): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val cr = c.contentResolver
            val col = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, (if (audio) "audio_" else "video_") + System.currentTimeMillis() + if (audio) ".m4a" else ".mp4")
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

    /** Áudios que você já extraiu (pasta Music/PostLeve). */
    fun savedAudios(c: Context): List<String> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val out = ArrayList<String>()
        try {
            c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", arrayOf("Music/PostLeve%"),
                MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { cur -> while (cur.moveToNext()) out.add(cur.getString(0)) }
        } catch (e: Exception) { }
        return out
    }

    /** Abre o Instagram com o vídeo ou a foto; a legenda vai pra área de transferência pra colar. */
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
