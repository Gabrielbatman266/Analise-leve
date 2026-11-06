package com.leve.analise

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import java.io.File

/** Aba Instagram: editor estilo CapCut (texto, música, foto→vídeo, prévia) + bibliotecas. */
class InstaEditor(private val a: MainActivity) {
    companion object {
        const val REQ_VIDEO = 31
        const val REQ_PHOTO = 32
        const val REQ_MUSIC = 33
    }

    private var src: Uri? = null
    private var isPhoto = false
    private var photoSecs = 8
    private var music: File? = null
    private var mute = false
    private var style = TextStyle.load(a)
    private var base: Bitmap? = null
    private var lastExportId: String? = null

    private lateinit var preview: ImageView
    private lateinit var video: VideoView
    private lateinit var tvSrc: TextView
    private lateinit var etText: EditText
    private lateinit var etCap: EditText
    private lateinit var tvMusic: TextView
    private lateinit var tvProg: TextView
    private lateinit var btnExport: Button
    private lateinit var afterBox: LinearLayout
    private lateinit var durRow: LinearLayout
    private lateinit var musicBox: LinearLayout
    private lateinit var libBox: LinearLayout
    private var player: MediaPlayer? = null

    private val ui get() = a.ui

    fun build() {
        // ---------- editor
        val ce = a.card("Editor")
        val pick = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        ce.addView(pick, a.lpMatch())
        a.button("🎬 Meu vídeo", pick, primary = true, weight = true) { open("video/*", REQ_VIDEO) }
        a.button("🖼 Foto → vídeo", pick, weight = true) { open("image/*", REQ_PHOTO) }
        tvSrc = a.text("Escolha um vídeo ou uma foto pra começar.", 13f, a.MUTED, parent = ce)

        // prévia 9:16
        val w = a.resources.displayMetrics.widthPixels - a.dp(64)
        val pw = minOf(w, a.dp(260)); val ph = pw * 16 / 9
        val frame = FrameLayout(a).apply {
            background = GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = a.dp(12).toFloat(); setStroke(a.dp(1), a.LINE) }
        }
        ce.addView(frame, LinearLayout.LayoutParams(pw, ph).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = a.dp(8) })
        preview = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        video = VideoView(a).apply { visibility = View.GONE }
        frame.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        video.setOnPreparedListener { it.isLooping = true }
        video.setOnClickListener { if (video.isPlaying) video.pause() else video.start() }

        // texto
        a.text("Texto no vídeo (Enter = novo parágrafo)", 12f, a.MUTED, parent = ce)
        etText = EditText(a).apply {
            setTextColor(a.FG); setHintTextColor(a.MUTED); textSize = 16f; hint = "Escreva aqui…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 8; gravity = Gravity.TOP or Gravity.START
            background = GradientDrawable().apply { setColor(a.BG); cornerRadius = a.dp(10).toFloat(); setStroke(a.dp(1), a.LINE) }
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
                override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
                override fun afterTextChanged(s: Editable?) { schedulePreview() }
            })
        }
        ce.addView(etText, a.lpMatch())

        a.text("Tamanho", 12f, a.MUTED, parent = ce)
        ce.addView(SeekBar(a).apply {
            max = 80; progress = style.size - 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { style.size = p + 20; schedulePreview() }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { style.save(a) }
            })
        }, a.lpMatch())
        a.text("Cor", 12f, a.MUTED, parent = ce)
        a.chips(ce, TextStyle.COLORS.keys.toList(), { style.color }) { style.color = it; changed() }
        a.text("Fundo do texto", 12f, a.MUTED, parent = ce)
        a.chips(ce, TextStyle.BGS, { style.bg }) { style.bg = it; changed() }
        a.text("Fonte", 12f, a.MUTED, parent = ce)
        a.chips(ce, TextStyle.FONTS, { style.font }) { style.font = it; changed() }
        a.text("Posição e alinhamento", 12f, a.MUTED, parent = ce)
        a.chips(ce, TextStyle.POS, { style.pos }) { style.pos = it; changed() }
        a.chips(ce, TextStyle.ALIGNS, { style.align }) { style.align = it; changed() }

        // duração (foto)
        durRow = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        ce.addView(durRow, a.lpMatch())
        a.text("Duração da foto em vídeo", 12f, a.MUTED, parent = durRow)
        a.chips(durRow, listOf("5s", "8s", "12s", "15s"), { "${photoSecs}s" }) { photoSecs = it.removeSuffix("s").toInt() }

        // música
        a.text("Música de fundo", 12f, a.MUTED, parent = ce)
        tvMusic = a.text("", 14f, a.FG, parent = ce, bold = true)
        a.button("🎵 Escolher música", ce) { chooseMusic() }
        updateMusicLabel()

        // legenda + exportar
        a.text("Legenda do post", 12f, a.MUTED, parent = ce)
        etCap = EditText(a).apply {
            setTextColor(a.FG); setHintTextColor(a.MUTED); textSize = 15f; hint = "Legenda que vai no Instagram"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2; maxLines = 6; gravity = Gravity.TOP or Gravity.START
            background = GradientDrawable().apply { setColor(a.BG); cornerRadius = a.dp(10).toFloat(); setStroke(a.dp(1), a.LINE) }
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
        }
        ce.addView(etCap, a.lpMatch())
        a.button("🎲 Legenda aleatória", ce) { etCap.setText(Captions.randomCaption(a)) }
        btnExport = a.button("Exportar 1080p", ce, primary = true) { export() }
        tvProg = a.text("", 13f, a.OK, parent = ce)
        afterBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        ce.addView(afterBox, a.lpMatch())
        a.button("Postar no Instagram ↗", afterBox, primary = true) { lastExportId?.let { post(it, etCap.text.toString()) } }
        a.button("Voltar pra prévia", afterBox) { showImagePreview() }

        // ---------- músicas
        val cm = a.card("Minhas músicas")
        a.text("Ficam salvas no app pra usar em qualquer vídeo. Também vão pra pasta Music/PostLeve.", 13f, a.MUTED, parent = cm)
        val mr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        cm.addView(mr, a.lpMatch())
        a.button("Importar do celular", mr, weight = true) { open("audio/*", REQ_MUSIC) }
        a.button("Extrair do meu vídeo", mr, weight = true) { extract() }
        musicBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        cm.addView(musicBox, a.lpMatch())
        a.text("Música de Reels dos outros: toque no nome do áudio › Salvar, e escolha em Áudios salvos na hora de postar. Assim ela vem licenciada.", 12f, a.MUTED, parent = cm)
        renderMusics()

        // ---------- biblioteca
        val cl = a.card("Vídeos prontos")
        a.text("Todo vídeo exportado fica aqui. Troque a legenda e poste de novo: cada vez sai um arquivo novo.", 13f, a.MUTED, parent = cl)
        libBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        cl.addView(libBox, a.lpMatch())
        renderLibrary()
    }

    // ------------------------------------------------------------------ prévia

    private fun changed() { style.save(a); schedulePreview() }

    private val previewTask = Runnable { drawPreview() }
    private fun schedulePreview() { ui.removeCallbacks(previewTask); ui.postDelayed(previewTask, 120) }

    private fun drawPreview() {
        val b = base
        val txt = etText.text.toString()
        if (b == null) {
            // sem mídia: mostra o texto num fundo escuro pra testar o estilo
            val blank = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF1C1C24.toInt()) }
            preview.setImageBitmap(VideoExport.preview(blank, txt.ifEmpty { "Seu texto aparece aqui" }, style))
            return
        }
        preview.setImageBitmap(VideoExport.preview(b, txt, style))
    }

    private fun showImagePreview() {
        video.stopPlayback(); video.visibility = View.GONE; preview.visibility = View.VISIBLE
        afterBox.visibility = View.GONE
    }

    // ------------------------------------------------------------------ escolher arquivos

    private fun open(mime: String, req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = mime; addCategory(Intent.CATEGORY_OPENABLE) }
        @Suppress("DEPRECATION")
        a.startActivityForResult(i, req)
    }

    /** Devolve true se o resultado era deste editor. */
    fun onResult(req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ_VIDEO && req != REQ_PHOTO && req != REQ_MUSIC) return false
        val u = data?.data ?: return true
        if (res != android.app.Activity.RESULT_OK) return true
        try { a.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        when (req) {
            REQ_VIDEO -> { isPhoto = false; src = u; durRow.visibility = View.GONE; loadBase("Vídeo escolhido") }
            REQ_PHOTO -> {
                tvSrc.text = "Preparando a foto…"
                Thread {
                    // foto vira um JPEG novo (sem metadados), já na posição certa
                    val f = File(a.cacheDir, "foto_video.jpg")
                    val ok = try {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        a.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
                        var smp = 1; while (maxOf(o.outWidth, o.outHeight) / (smp * 2) >= 2160) smp *= 2
                        val b = a.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = smp }) }
                        if (b == null) false else { val r = a.rotateByExif(u, b); f.outputStream().use { r.compress(Bitmap.CompressFormat.JPEG, 95, it) }; true }
                    } catch (e: Exception) { false }
                    ui.post {
                        if (!ok) { a.toast("Não abriu essa foto"); return@post }
                        isPhoto = true; src = Uri.fromFile(f); durRow.visibility = View.VISIBLE
                        loadBase("Foto escolhida: vira um vídeo de ${photoSecs}s")
                    }
                }.start()
            }
            REQ_MUSIC -> Thread {
                val f = VideoExport.importMusic(a, u)
                ui.post {
                    if (f == null) a.toast("Não consegui importar") else { music = f; mute = false; updateMusicLabel(); renderMusics(); a.toast("Música salva e escolhida") }
                }
            }.start()
        }
        return true
    }

    private fun loadBase(label: String) {
        val s = src ?: return
        showImagePreview()
        tvSrc.text = label; tvSrc.setTextColor(a.OK)
        Thread {
            val b = VideoExport.baseFrame(a, s, isPhoto)
            ui.post { base = b; drawPreview() }
        }.start()
    }

    // ------------------------------------------------------------------ música

    private fun updateMusicLabel() {
        tvMusic.text = when {
            music != null -> "♪ " + VideoExport.nice(music!!)
            mute -> "Sem som"
            else -> "Som original do vídeo"
        }
    }

    private fun chooseMusic() {
        val list = VideoExport.musics(a)
        val names = mutableListOf("Som original do vídeo", "Sem som", "Importar música do celular…")
        names.addAll(list.map { "♪ " + VideoExport.nice(it) })
        AlertDialog.Builder(a).setTitle("Música de fundo").setItems(names.toTypedArray()) { _, i ->
            when (i) {
                0 -> { music = null; mute = false }
                1 -> { music = null; mute = true }
                2 -> open("audio/*", REQ_MUSIC)
                else -> { music = list[i - 3]; mute = false }
            }
            updateMusicLabel()
        }.show()
    }

    private fun renderMusics() {
        musicBox.removeAllViews()
        val list = VideoExport.musics(a)
        if (list.isEmpty()) { a.text("Nenhuma música ainda.", 12f, a.MUTED, parent = musicBox); return }
        list.forEach { f ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            musicBox.addView(row, a.lpMatch().apply { topMargin = a.dp(6) })
            row.addView(TextView(a).apply { text = "♪ " + VideoExport.nice(f); textSize = 14f; setTextColor(a.FG) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            small(row, "▶") { play(f) }
            small(row, "Usar") { music = f; mute = false; updateMusicLabel(); a.toast("Música escolhida pro vídeo") }
            small(row, "✕") { stopPlay(); f.delete(); if (music == f) { music = null; updateMusicLabel() }; renderMusics() }
        }
    }

    private fun small(row: LinearLayout, t: String, click: () -> Unit) {
        row.addView(Button(a).apply {
            text = t; isAllCaps = false; textSize = 13f; setTextColor(a.FG)
            background = GradientDrawable().apply { setColor(0xFF1C1C24.toInt()); cornerRadius = a.dp(8).toFloat() }
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dp(40)).apply { leftMargin = a.dp(4) })
    }

    private fun play(f: File) {
        stopPlay()
        player = try { MediaPlayer().apply { setDataSource(f.path); prepare(); start(); setOnCompletionListener { stopPlay() } } } catch (e: Exception) { null }
        ui.postDelayed({ stopPlay() }, 15000) // amostra de 15s
    }

    fun stopPlay() { try { player?.stop(); player?.release() } catch (e: Exception) { }; player = null }

    private fun extract() {
        val s = src ?: run { a.toast("Escolha um vídeo seu primeiro"); return }
        if (isPhoto) { a.toast("Foto não tem áudio. Escolha um vídeo."); return }
        if (VideoExport.busy()) { a.toast("Espere terminar o que está rodando"); return }
        tvProg.setTextColor(a.MUTED); tvProg.text = "Extraindo áudio…"
        VideoExport.extractAudio(a, s, { p -> tvProg.text = "Extraindo áudio… $p%" }) { f, err ->
            if (f == null) { tvProg.setTextColor(a.ACCENT); tvProg.text = "Não deu: $err" }
            else { tvProg.setTextColor(a.OK); tvProg.text = "Áudio salvo em Minhas músicas"; renderMusics() }
        }
    }

    // ------------------------------------------------------------------ exportar e postar

    private fun export() {
        val s = src ?: run { a.toast("Escolha um vídeo ou uma foto primeiro"); return }
        if (VideoExport.busy()) { a.toast("Já está exportando"); return }
        stopPlay(); showImagePreview()
        btnExport.isEnabled = false
        tvProg.setTextColor(a.MUTED); tvProg.text = "Exportando 1080p…"
        a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val job = VideoExport.Job(s, isPhoto, photoSecs, etText.text.toString(), style.copy(), music, mute)
        VideoExport.export(a, job, { p -> tvProg.text = "Exportando 1080p… $p%" }) { f, err ->
            a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnExport.isEnabled = true
            if (f == null) { tvProg.setTextColor(a.ACCENT); tvProg.text = "Não deu: $err"; return@export }
            tvProg.text = "Salvando…"
            Thread {
                val id = VideoExport.addToLibrary(a, f, etCap.text.toString().trim())
                ui.post {
                    lastExportId = id
                    tvProg.setTextColor(a.OK); tvProg.text = "Pronto! Toque no vídeo pra pausar."
                    preview.visibility = View.GONE; video.visibility = View.VISIBLE
                    video.setVideoURI(Uri.fromFile(File(VideoExport.videoDir(a), "$id.mp4"))); video.start()
                    afterBox.visibility = View.VISIBLE
                    renderLibrary()
                }
            }.start()
        }
    }

    private fun post(id: String, caption: String) {
        val cap = caption.trim()
        if (cap.isNotEmpty()) {
            try { a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("legenda", cap)) } catch (e: Exception) { }
            Captions.addMine(a, cap)
        }
        Thread {
            val u = VideoExport.freshCopyForPost(a, id)
            ui.post {
                if (u == null) { a.toast("Não consegui preparar o vídeo"); return@post }
                VideoExport.updateLib(a, id) { it.put("legenda", cap); it.put("posts", it.optInt("posts") + 1); it.put("lastPost", System.currentTimeMillis()) }
                renderLibrary()
                a.toast(if (cap.isEmpty()) "Abrindo o Instagram" else "Legenda copiada: no Instagram é só colar e postar")
                a.startActivity(VideoExport.shareToInstagram(a, u, "video/mp4"))
            }
        }.start()
    }

    private fun renderLibrary() {
        libBox.removeAllViews()
        val l = VideoExport.library(a)
        if (l.isEmpty()) { a.text("Nenhum vídeo exportado ainda.", 12f, a.MUTED, parent = libBox); return }
        l.take(30).forEach { o ->
            val id = o.optString("id")
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
                background = GradientDrawable().apply { setColor(a.BG); cornerRadius = a.dp(12).toFloat(); setStroke(a.dp(1), a.LINE) }
            }
            libBox.addView(box, a.lpMatch().apply { topMargin = a.dp(8) })
            val th = File(VideoExport.videoDir(a), "$id.jpg")
            box.addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; if (th.exists()) setImageBitmap(BitmapFactory.decodeFile(th.path)) },
                LinearLayout.LayoutParams(a.dp(72), a.dp(128)).apply { rightMargin = a.dp(10) })
            val side = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            box.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val posts = o.optInt("posts")
            a.text(if (posts > 0) "Postado ${posts}x" else "Ainda não postado", 12f, if (posts > 0) a.OK else a.ACCENT, parent = side, bold = true)
            val et = EditText(a).apply {
                setText(o.optString("legenda")); hint = "Legenda"; setTextColor(a.FG); setHintTextColor(a.MUTED); textSize = 13f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                maxLines = 4
                background = GradientDrawable().apply { setColor(a.CARD); cornerRadius = a.dp(8).toFloat(); setStroke(a.dp(1), a.LINE) }
                setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6))
            }
            side.addView(et, a.lpMatch())
            val r1 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            side.addView(r1, a.lpMatch())
            a.button("🎲", r1, weight = true) { et.setText(Captions.randomCaption(a, listOf(et.text.toString()))) }
            a.button("Postar ↗", r1, primary = true, weight = true) { post(id, et.text.toString()) }
            val del = a.button("Apagar", r1, weight = true) { }
            del.setOnClickListener {
                if (del.tag != "armed") { del.tag = "armed"; del.text = "De novo"; ui.postDelayed({ del.tag = null; del.text = "Apagar" }, 2500) }
                else { VideoExport.removeLib(a, id); renderLibrary() }
            }
        }
    }
}
