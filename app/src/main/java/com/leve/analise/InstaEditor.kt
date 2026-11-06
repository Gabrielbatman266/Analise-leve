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
        const val REQ_MULTI = 34
        const val REQ_IMPORT = 35
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
    private var playing: File? = null
    private lateinit var playerBar: LinearLayout
    private lateinit var tvNow: TextView
    private lateinit var tvTime: TextView
    private lateinit var playSeek: SeekBar
    private lateinit var btnPlay: Button
    private lateinit var tvBatch: TextView
    private val redraws = ArrayList<() -> Unit>()
    private lateinit var seek: SeekBar
    private lateinit var projBox: LinearLayout
    private var projId: String? = null
    private lateinit var editorCard: LinearLayout
    private lateinit var cutRow: LinearLayout
    private lateinit var tvCut: TextView
    private var durMs = 0L
    private var cutStart = 0L
    private var cutEnd = 0L
    private var musicStart = 0L
    private lateinit var tvMusicStart: TextView
    private lateinit var timeline: TimelineView
    private lateinit var tlHint: TextView
    private lateinit var pVideo: LinearLayout
    private lateinit var pMusic: LinearLayout
    private lateinit var pText: LinearLayout
    private lateinit var pExport: LinearLayout
    private val tabBtns = ArrayList<Button>()
    private var textStart = 0L
    private var textEnd = 0L
    private var musicEnd = 0L
    private var musicDurMs = 0L
    private var waveFor: File? = null

    private fun panel(parent: LinearLayout) = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        .also { parent.addView(it, a.lpMatch().apply { topMargin = a.dp(4) }) }

    private fun showPanel(i: Int) {
        Store.prefs(a).edit().putInt("ed_panel", i).apply()
        listOf(pVideo, pMusic, pText, pExport).forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabBtns.forEachIndexed { k, b ->
            val on = k == i
            b.setTextColor(if (on) 0xFF1A0A10.toInt() else a.FG); b.textSize = 13f
            b.background = GradientDrawable().apply { setColor(if (on) a.ACCENT else 0xFF1C1C24.toInt()); cornerRadius = a.dp(10).toFloat() }
        }
        if (i == 1 && music == null && ::timeline.isInitialized && timeline.visibility == View.VISIBLE && pendingMusicAsk) { pendingMusicAsk = false; chooseMusic() }
        if (i == 2 && etText.text.isBlank() && pendingTextFocus) { pendingTextFocus = false; etText.requestFocus() }
    }
    private var pendingMusicAsk = false
    private var pendingTextFocus = false

    private fun outDur() = maxOf(0L, cutEnd - cutStart)
    private fun cursorOut(): Long = ((try { video.currentPosition.toLong() } catch (e: Exception) { cutStart }) - cutStart).coerceIn(0, outDur())
    private fun textVisibleAt(out: Long) = out >= textStart && (textEnd <= 0 || out < textEnd)

    /** Copia o estado do editor pra linha do tempo. */
    private fun syncTimeline() {
        if (!::timeline.isInitialized) return
        timeline.dur = durMs; timeline.cutS = cutStart; timeline.cutE = cutEnd
        timeline.musicOn = music != null; timeline.musicName = music?.let { VideoExport.nice(it) } ?: ""
        timeline.musicDur = musicDurMs; timeline.musicStart = musicStart; timeline.musicEnd = musicEnd
        timeline.text = etText.text.toString(); timeline.textS = textStart; timeline.textE = textEnd
        timeline.invalidate()
    }

    /** Depois de um botão mexer no tempo: atualiza tudo, mostra o ponto e salva. */
    private fun afterEdit(what: String) {
        if (cutEnd <= cutStart) cutEnd = durMs
        syncTimeline(); timeline.clampRel()
        textStart = timeline.textS; textEnd = timeline.textE; musicEnd = timeline.musicEnd
        syncCut(); musicLabel2(); scheduleSave()
        if (editMode) {
            val ms = when (what) { "cut" -> cutStart; "text" -> cutStart + textStart; else -> null }
            if (ms != null) { pauseEdit(); seekPrecise(ms); timeline.playhead = ms; updateTextLayer(ms - cutStart) }
            else if (video.isPlaying) bgmStart(cursorOut())
        }
    }

    private fun onTimeline(what: String, fin: Boolean) {
        cutStart = timeline.cutS; cutEnd = timeline.cutE
        textStart = timeline.textS; textEnd = timeline.textE
        musicStart = timeline.musicStart; musicEnd = timeline.musicEnd
        when (what) {
            "cut", "text", "seek" -> {
                if (video.isPlaying) pauseEdit()
                seekPrecise(timeline.playhead); tvPos.text = t(timeline.playhead)
                updateTextLayer(timeline.playhead - cutStart)
                if (what == "cut") syncCut()
            }
            "music" -> { musicLabel2(); if (fin && video.isPlaying) bgmStart(cursorOut()) }
        }
        if (fin && what != "seek") scheduleSave()
        if (what == "music" && fin && !video.isPlaying && music != null) previewMusicSnippet()
    }

    /** Toca 3 segundos da música a partir do cursor, pra conferir a batida depois de arrastar. */
    private fun previewMusicSnippet() {
        bgmStart(cursorOut())
        ui.postDelayed({ if (!video.isPlaying) bgmStop() }, 3000)
    }

    private fun updateTextLayer(out: Long) {
        if (::textLayer.isInitialized && editMode) textLayer.visibility = if (textVisibleAt(out)) View.VISIBLE else View.INVISIBLE
    }

    private fun slip(d: Long) {
        if (music == null) { a.toast("Escolha uma música primeiro"); return }
        musicStart = (musicStart + d).coerceIn(0, maxOf(0L, musicDurMs - 1000))
        afterEdit("music"); if (!video.isPlaying) previewMusicSnippet()
    }

    private fun cutAtCursor(start: Boolean) {
        if (durMs <= 0) return
        val pos = try { video.currentPosition.toLong() } catch (e: Exception) { return }
        if (start) cutStart = pos.coerceIn(0, cutEnd - 500) else cutEnd = pos.coerceIn(cutStart + 500, durMs)
        afterEdit("cut")
    }

    /** Duração e onda da música escolhida (em segundo plano, com cache). */
    private fun refreshMusicMeta() {
        val f = music
        if (f == null) { musicDurMs = 0; timeline.wave = null; waveFor = null; syncTimeline(); return }
        if (f == waveFor) { syncTimeline(); return }
        waveFor = f; timeline.wave = null
        Thread {
            val d = VideoExport.durationMs(f)
            ui.post { if (waveFor == f) { musicDurMs = d; syncTimeline() } }
            val w = Wave.load(a, f)
            ui.post { if (waveFor == f) { timeline.wave = w; timeline.invalidate() } }
        }.start()
    }

    /** Miniaturas do vídeo pra camada de vídeo. */
    private fun loadThumbs(s: Uri, d: Long) {
        Thread {
            val out = ArrayList<Bitmap>()
            try {
                val r = android.media.MediaMetadataRetriever(); r.setDataSource(a, s)
                val n = 10
                for (i in 0 until n) {
                    val us = d * 1000 * i / n + 1000
                    val b = if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 170)
                            else r.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, 96, 170, true) }
                    if (b != null) out.add(b)
                }
                r.release()
            } catch (e: Exception) { }
            ui.post { if (src == s) { timeline.thumbs = out; timeline.invalidate() } }
        }.start()
    }

    private fun t(ms: Long) = "%d:%02d,%d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)

    private fun syncCut() {
        if (durMs <= 0) return
        tvCut.text = "✂ ${t(cutStart)} → ${t(cutEnd)}  ·  fica com ${t(cutEnd - cutStart)}"
        syncTimeline()
    }

    private fun nudge(start: Boolean, d: Long) {
        if (durMs <= 0) return
        if (start) cutStart = (cutStart + d).coerceIn(0, cutEnd - 500) else cutEnd = (cutEnd + d).coerceIn(cutStart + 500, durMs)
        syncCut(); scheduleSave(); frameAtCut(start)
    }

    private var mp: MediaPlayer? = null
    private var bgm: MediaPlayer? = null
    private var editMode = false
    private lateinit var textLayer: ImageView
    private lateinit var playRow: LinearLayout
    private lateinit var btnTrecho: Button
    private lateinit var tvPos: TextView

    private fun applyVolume() { val v = if (mute || music != null) 0f else 1f; try { mp?.setVolume(v, v) } catch (e: Exception) { } }

    private fun seekPrecise(ms: Long) {
        val p = mp
        if (p != null && android.os.Build.VERSION.SDK_INT >= 26) try { p.seekTo(ms, MediaPlayer.SEEK_CLOSEST); return } catch (e: Exception) { }
        try { video.seekTo(ms.toInt()) } catch (e: Exception) { }
    }

    private fun bgmStart(offset: Long) {
        bgmStop()
        val f = music ?: return
        if (musicEnd > 0 && offset >= musicEnd) return
        bgm = try { MediaPlayer().apply { setDataSource(f.path); prepare(); seekTo((musicStart + offset).toInt()); start() } } catch (e: Exception) { null }
    }
    private fun bgmStop() { try { bgm?.stop(); bgm?.release() } catch (e: Exception) { }; bgm = null }

    private fun pauseEdit() {
        try { if (video.isPlaying) video.pause() } catch (e: Exception) { }
        bgmStop()
        if (::btnTrecho.isInitialized) btnTrecho.text = "▶ Ver trecho"
    }

    /** Toca só o trecho escolhido, em loop, com o texto e a música por cima. */
    private fun togglePlay() {
        if (!editMode) { if (video.isPlaying) video.pause() else video.start(); return }
        if (video.isPlaying) { pauseEdit(); return }
        var pos = try { video.currentPosition.toLong() } catch (e: Exception) { 0L }
        if (pos < cutStart || pos >= cutEnd - 200) { seekPrecise(cutStart); pos = cutStart }
        applyVolume(); video.start(); bgmStart(pos - cutStart)
        btnTrecho.text = "⏸ Pausar"
        ui.removeCallbacks(loopTick); ui.post(loopTick)
    }

    private val loopTick = object : Runnable {
        override fun run() {
            if (!editMode) return
            val pos = try { video.currentPosition.toLong() } catch (e: Exception) { 0L }
            if (video.isPlaying) {
                tvPos.text = t(pos)
                timeline.playhead = pos; timeline.invalidate()
                updateTextLayer(pos - cutStart)
                if (musicEnd > 0 && pos - cutStart >= musicEnd && bgm != null) bgmStop()
            }
            if (video.isPlaying && pos >= cutEnd) { seekPrecise(cutStart); bgmStart(0) }
            ui.postDelayed(this, 50)
        }
    }

    /** Prévia do vídeo de verdade: dá pra tocar o trecho e arrastar o corte vendo o quadro. */
    private fun showEditVideo(s: Uri) {
        editMode = true
        afterBox.visibility = View.GONE
        preview.visibility = View.GONE
        video.visibility = View.VISIBLE; textLayer.visibility = View.VISIBLE; playRow.visibility = View.VISIBLE
        timeline.visibility = View.VISIBLE; tlHint.visibility = View.VISIBLE
        timeline.playhead = cutStart; syncTimeline(); updateTextLayer(0)
        btnTrecho.text = "▶ Ver trecho"
        video.setVideoURI(s)
        ui.removeCallbacks(loopTick); ui.post(loopTick)
        drawPreview()
    }

    private var frameJob = 0
    /** Mostra na prévia o quadro do ponto de corte. */
    private fun frameAtCut(start: Boolean) {
        if (editMode) { pauseEdit(); val ms = if (start) cutStart else maxOf(0, cutEnd - 100); seekPrecise(ms); timeline.playhead = ms; timeline.invalidate(); return }
        val s = src ?: return
        val ms = if (start) cutStart else maxOf(0, cutEnd - 100)
        val my = ++frameJob
        Thread {
            val b = VideoExport.frameAt(a, s, ms)
            ui.post { if (my == frameJob && b != null) { if (start) base = b; preview.setImageBitmap(VideoExport.preview(b, etText.text.toString(), style)) } }
        }.start()
    }
    private lateinit var tvQ: TextView
    private lateinit var modelBox: LinearLayout
    private lateinit var tvCycle: TextView
    private lateinit var tvImport: TextView

    private fun updateCycle() {
        if (!::tvCycle.isInitialized) return
        val (done, total, rounds) = VideoExport.cycleStatus(a)
        tvCycle.text = if (total == 0) "" else "Rodada: $done de $total postados" + (if (rounds > 0) " · $rounds rodada(s) completa(s)" else "") + ". Quando todos saírem, recomeça sozinho."
    }

    /** Sorteia um vídeo que ainda não saiu nesta rodada e uma legenda do banco. */
    private fun postRandomVideo() {
        val id = VideoExport.cycleNext(a) ?: run { a.toast("Nenhum vídeo pronto ainda. Exporte ou importe alguns."); return }
        val old = VideoExport.library(a).firstOrNull { it.optString("id") == id }?.optString("legenda").orEmpty()
        post(id, Captions.randomCaption(a, listOf(old)))
    }

    /** Importa vários vídeos prontos, um depois do outro, cada um vira arquivo novo sem metadados. */
    private fun importMany(list: List<Uri>, i: Int = 0, ok: Int = 0) {
        if (i >= list.size) {
            a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            tvImport.setTextColor(a.OK); tvImport.text = "$ok de ${list.size} vídeos importados, sem metadados."
            renderLibrary(); return
        }
        if (i == 0) a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tvImport.setTextColor(a.MUTED); tvImport.text = "Importando ${i + 1} de ${list.size}…"
        VideoExport.cleanCopy(a, list[i], { p -> tvImport.text = "Importando ${i + 1} de ${list.size}… $p%" }) { f, _ ->
            if (f == null) { importMany(list, i + 1, ok); return@cleanCopy }
            Thread {
                VideoExport.addToLibrary(a, f, "", true)
                ui.post { renderLibrary(); importMany(list, i + 1, ok + 1) }
            }.start()
        }
    }
    private lateinit var tvMany: TextView
    private fun quality() = Store.prefs(a).getString("quality", "1080p") ?: "1080p"
    private fun qNote() {
        if (!::tvQ.isInitialized) return
        tvQ.text = when (quality()) {
            "2K" -> "2K (1440x2560) em HEVC: mais nítido e o arquivo fica quase do tamanho do 1080p."
            "4K" -> "4K (2160x3840) em HEVC: máximo de detalhe; demora mais pra exportar e o arquivo é maior."
            else -> "1080p (1080x1920): o mais rápido e o padrão do Instagram."
        }
    }

    fun refreshLists() { renderProjects(); renderLibrary() }

    private val ui get() = a.ui

    fun build() {
        // ---------- projetos
        val cp = a.card("Esboços")
        a.text("Os originais ficam aqui, sem perder nada. Os vídeos novos do Flow entram sozinhos. Toque em Editar, mude texto e música, exporte e poste. Dá pra repetir com o mesmo original quantas vezes quiser.", 13f, a.MUTED, parent = cp)
        a.button("⚡ Exportar vários de uma vez", cp, primary = true) { exportManyDialog() }
        tvMany = a.text("", 13f, a.OK, parent = cp)
        a.button("＋ Novo esboço (limpar editor)", cp) { newProject() }
        projBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        cp.addView(projBox, a.lpMatch())
        renderProjects()

        // ---------- editor
        val ce = a.card("Editor")
        editorCard = ce
        val pick = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        ce.addView(pick, a.lpMatch())
        a.button("🎬 Meu vídeo", pick, primary = true, weight = true) { chooseMyVideo() }
        a.button("🖼 Foto → vídeo", pick, weight = true) { open("image/*", REQ_PHOTO) }
        tvSrc = a.text("Escolha um vídeo ou uma foto pra começar.", 13f, a.MUTED, parent = ce)
        a.text("Modelos (toque pra aplicar, segure pra apagar)", 12f, a.MUTED, parent = ce)
        modelBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        ce.addView(modelBox, a.lpMatch())
        a.button("☆ Salvar como modelo", ce) { saveModelDialog() }
        renderModels()

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
        video.setOnPreparedListener { p ->
            mp = p; p.isLooping = !editMode; applyVolume()
            if (editMode) seekPrecise(cutStart)
        }
        video.setOnClickListener { togglePlay() }
        textLayer = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        frame.addView(textLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        playRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        ce.addView(playRow, a.lpMatch().apply { topMargin = a.dp(6) })
        btnTrecho = a.button("▶ Ver trecho", playRow, primary = true, weight = true) { togglePlay() }
        tvPos = TextView(a).apply { setTextColor(a.FG); textSize = 15f; gravity = Gravity.CENTER; text = "0:00,0" }
        playRow.addView(tvPos, LinearLayout.LayoutParams(a.dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---------- linha do tempo (camadas) + ferramentas de cada camada
        timeline = TimelineView(a).apply { visibility = View.GONE }
        ce.addView(timeline, a.lpMatch().apply { topMargin = a.dp(10) })
        tlHint = a.text("Toque numa camada pra editar. Bordas cortam; arrastar a música sincroniza; o bloco do texto escolhe quando ele aparece.", 11f, a.MUTED, parent = ce)
        tlHint.visibility = View.GONE
        timeline.onSelect = { tk ->
            if (tk == 1 && music == null) pendingMusicAsk = true
            if (tk == 2) pendingTextFocus = true
            showPanel(tk)
        }
        timeline.onChange = { what, fin -> onTimeline(what, fin) }
        val tabs = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        ce.addView(tabs, a.lpMatch().apply { topMargin = a.dp(8) })
        listOf("🎬 Vídeo", "🎵 Música", "T Texto", "⬆ Exportar").forEachIndexed { i, n ->
            tabBtns += a.button(n, tabs, weight = true) { showPanel(i); if (i < 3) { timeline.selected = i; timeline.invalidate() } }
        }
        pVideo = panel(ce); pMusic = panel(ce); pText = panel(ce); pExport = panel(ce)

        // texto
        a.text("Texto no vídeo (Enter = novo parágrafo)", 12f, a.MUTED, parent = pText)
        etText = EditText(a).apply {
            setTextColor(a.FG); setHintTextColor(a.MUTED); textSize = 16f; hint = "Escreva aqui…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 8; gravity = Gravity.TOP or Gravity.START
            background = GradientDrawable().apply { setColor(a.BG); cornerRadius = a.dp(10).toFloat(); setStroke(a.dp(1), a.LINE) }
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
                override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
                override fun afterTextChanged(s: Editable?) { schedulePreview(); scheduleSave(); syncTimeline() }
            })
        }
        pText.addView(etText, a.lpMatch())

        a.text("Tamanho", 12f, a.MUTED, parent = pText)
        seek = SeekBar(a)
        pText.addView(seek.apply {
            max = 80; progress = style.size - 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { style.size = p + 20; schedulePreview() }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { style.save(a); scheduleSave() }
            })
        }, a.lpMatch())
        a.text("Cor", 12f, a.MUTED, parent = pText)
        redraws += a.chips(pText, TextStyle.COLORS.keys.toList(), { style.color }) { style.color = it; changed() }
        a.text("Fundo do texto", 12f, a.MUTED, parent = pText)
        redraws += a.chips(pText, TextStyle.BGS, { style.bg }) { style.bg = it; changed() }
        a.text("Fonte", 12f, a.MUTED, parent = pText)
        redraws += a.chips(pText, TextStyle.FONTS, { style.font }) { style.font = it; changed() }
        a.text("Posição e alinhamento", 12f, a.MUTED, parent = pText)
        redraws += a.chips(pText, TextStyle.POS, { style.pos }) { style.pos = it; changed() }
        redraws += a.chips(pText, TextStyle.ALIGNS, { style.align }) { style.align = it; changed() }

        a.text("Quando o texto aparece (ou arraste o bloco rosa na linha do tempo)", 12f, a.MUTED, parent = pText)
        val tr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        pText.addView(tr, a.lpMatch())
        a.button("Aparece no cursor", tr, weight = true) { val o = cursorOut(); textStart = o.coerceIn(0, maxOf(0, (if (textEnd > 0) textEnd else outDur()) - 300)); afterEdit("text") }
        a.button("Some no cursor", tr, weight = true) { val o = cursorOut(); textEnd = if (o >= outDur() - 150) 0 else o.coerceAtLeast(textStart + 300); afterEdit("text") }
        a.button("O vídeo todo", pText) { textStart = 0; textEnd = 0; afterEdit("text") }

        // camada Vídeo: corte
        cutRow = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        pVideo.addView(cutRow, a.lpMatch())
        tvCut = a.text("", 14f, a.FG, parent = cutRow, bold = true)
        a.text("Arraste as bordas brancas na linha do tempo, ou corte onde está o cursor:", 12f, a.MUTED, parent = cutRow)
        val cr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        cutRow.addView(cr, a.lpMatch())
        a.button("✂ Início no cursor", cr, weight = true) { cutAtCursor(true) }
        a.button("✂ Fim no cursor", cr, weight = true) { cutAtCursor(false) }
        a.button("Sem corte (vídeo inteiro)", cutRow) { cutStart = 0; cutEnd = durMs; afterEdit("cut") }

        // duração (foto)
        durRow = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        pVideo.addView(durRow, a.lpMatch())
        a.text("Duração da foto em vídeo", 12f, a.MUTED, parent = durRow)
        redraws += a.chips(durRow, listOf("5s", "8s", "12s", "15s"), { "${photoSecs}s" }) { photoSecs = it.removeSuffix("s").toInt(); scheduleSave() }

        // camada Música
        tvMusic = a.text("", 14f, a.FG, parent = pMusic, bold = true)
        a.button("🎵 Escolher música", pMusic, primary = true) { chooseMusic() }
        tvMusicStart = a.text("", 12f, a.MUTED, parent = pMusic)
        a.text("Sincronizar: arraste a onda verde na linha do tempo até a batida bater, ou ajuste fino o ponto da música:", 12f, a.MUTED, parent = pMusic)
        val mr0 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        pMusic.addView(mr0, a.lpMatch())
        a.button("−0,5s", mr0, weight = true) { slip(-500) }
        a.button("−0,1s", mr0, weight = true) { slip(-100) }
        a.button("+0,1s", mr0, weight = true) { slip(100) }
        a.button("+0,5s", mr0, weight = true) { slip(500) }
        val mr1 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        pMusic.addView(mr1, a.lpMatch())
        a.button("✂ Música para no cursor", mr1, weight = true) { val o = cursorOut(); musicEnd = if (o >= outDur() - 150 || o < 500) 0 else o; afterEdit("music") }
        a.button("Até o fim", mr1, weight = true) { musicEnd = 0; afterEdit("music") }
        updateMusicLabel()

        // legenda + exportar
        a.text("Legenda do post", 12f, a.MUTED, parent = pExport)
        etCap = EditText(a).apply {
            setTextColor(a.FG); setHintTextColor(a.MUTED); textSize = 15f; hint = "Legenda que vai no Instagram"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2; maxLines = 6; gravity = Gravity.TOP or Gravity.START
            background = GradientDrawable().apply { setColor(a.BG); cornerRadius = a.dp(10).toFloat(); setStroke(a.dp(1), a.LINE) }
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
        }
        pExport.addView(etCap, a.lpMatch())
        etCap.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun afterTextChanged(s: Editable?) { scheduleSave() }
        })
        a.button("🎲 Legenda aleatória", pExport) { etCap.setText(Captions.randomCaption(a)) }
        a.text("Qualidade", 12f, a.MUTED, parent = pExport)
        redraws += a.chips(pExport, listOf("1080p", "2K", "4K"), { quality() }) {
            Store.prefs(a).edit().putString("quality", it).apply(); btnExport.text = "Exportar $it"; qNote()
        }
        tvQ = a.text("", 12f, a.MUTED, parent = pExport)
        val gal = a.button("", pExport) { }
        fun galT() { gal.text = if (Store.prefs(a).getBoolean("save_gallery", false)) "Também salvar cópia na galeria: ligado" else "Também salvar cópia na galeria: desligado" }
        galT()
        gal.setOnClickListener { Store.prefs(a).edit().putBoolean("save_gallery", !Store.prefs(a).getBoolean("save_gallery", false)).apply(); galT() }
        btnExport = a.button("Exportar ${quality()}", pExport, primary = true) { export() }
        qNote()
        tvProg = a.text("", 13f, a.OK, parent = pExport)
        afterBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        pExport.addView(afterBox, a.lpMatch())
        a.button("Postar no Instagram ↗", afterBox, primary = true) { lastExportId?.let { post(it, etCap.text.toString()) } }
        a.button("Voltar pra edição", afterBox) { val s0 = src; if (s0 != null && !isPhoto) showEditVideo(s0) else { showImagePreview(); drawPreview() } }

        showPanel(Store.prefs(a).getInt("ed_panel", 0))

        // ---------- músicas
        val cm = a.card("Minhas músicas")
        a.text("Ficam salvas no app pra usar em qualquer vídeo. Também vão pra pasta Music/PostLeve.", 13f, a.MUTED, parent = cm)
        val mr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        cm.addView(mr, a.lpMatch())
        a.button("Importar do celular", mr, weight = true) { open("audio/*", REQ_MUSIC) }
        a.button("Extrair do vídeo aberto", mr, weight = true) { extract() }
        a.button("🎵 Extrair de vários vídeos de uma vez", cm, primary = true) {
            open("video/*", REQ_MULTI, true)
        }
        tvBatch = a.text("", 13f, a.OK, parent = cm)
        // player
        val pbar = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xFF3A1824.toInt()); cornerRadius = a.dp(10).toFloat() }
        }
        playerBar = pbar
        cm.addView(pbar, a.lpMatch().apply { topMargin = a.dp(8) })
        tvNow = a.text("", 14f, a.FG, parent = pbar, bold = true)
        playSeek = SeekBar(a).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) try { player?.seekTo(p) } catch (e: Exception) { } }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        pbar.addView(playSeek, a.lpMatch())
        val pr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pbar.addView(pr, a.lpMatch())
        tvTime = TextView(a).apply { setTextColor(a.MUTED); textSize = 12f }
        pr.addView(tvTime, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnPlay = Button(a).apply {
            text = "⏸"; textSize = 15f; setTextColor(a.FG)
            background = GradientDrawable().apply { setColor(0xFF1C1C24.toInt()); cornerRadius = a.dp(8).toFloat() }
            setOnClickListener { val p = player ?: return@setOnClickListener; if (p.isPlaying) { p.pause(); text = "▶" } else { p.start(); text = "⏸" } }
        }
        pr.addView(btnPlay, LinearLayout.LayoutParams(a.dp(56), a.dp(40)))
        small(pr, "Usar no vídeo") { playing?.let { music = it; mute = false; updateMusicLabel(); scheduleSave(); a.toast("Música escolhida pro vídeo") } }
        small(pr, "✕") { stopPlay() }
        musicBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        cm.addView(musicBox, a.lpMatch())
        a.text("Música de Reels dos outros: toque no nome do áudio › Salvar, e escolha em Áudios salvos na hora de postar. Assim ela vem licenciada.", 12f, a.MUTED, parent = cm)
        renderMusics()

        // ---------- biblioteca
        val cl = a.card("Vídeos prontos")
        a.text("Todo vídeo exportado fica guardado aqui no app. Postar ↗ manda direto pro Instagram, sem baixar nada e sem encher a galeria.", 13f, a.MUTED, parent = cl)
        a.button("🎲 Postar vídeo aleatório", cl, primary = true) { postRandomVideo() }
        tvCycle = a.text("", 13f, a.OK, parent = cl)
        a.button("＋ Importar vídeos que já tenho (sem metadados)", cl) {
            open("video/*", REQ_IMPORT, true)
        }
        tvImport = a.text("", 13f, a.OK, parent = cl)
        libBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        cl.addView(libBox, a.lpMatch())
        renderLibrary()
    }

    // ------------------------------------------------------------------ modelos

    private fun styleJson(st: TextStyle) = org.json.JSONObject().put("size", st.size).put("color", st.color).put("bg", st.bg)
        .put("pos", st.pos).put("font", st.font).put("align", st.align)
    private fun styleOf(st: org.json.JSONObject) = TextStyle(st.optInt("size", 52), st.optString("color", "Branco"), st.optString("bg", "Faixa escura"),
        st.optString("pos", "Baixo"), st.optString("font", "Negrito"), st.optString("align", "Centro"))

    private fun models(): MutableList<org.json.JSONObject> = try {
        val a0 = org.json.JSONArray(Store.prefs(a).getString("modelos", "[]")); (0 until a0.length()).map { a0.getJSONObject(it) }.toMutableList()
    } catch (e: Exception) { mutableListOf() }
    private fun saveModels(l: List<org.json.JSONObject>) { val ar = org.json.JSONArray(); l.forEach { ar.put(it) }; Store.prefs(a).edit().putString("modelos", ar.toString()).apply() }

    private fun saveModelDialog() {
        val et = EditText(a).apply { hint = "Nome do modelo (ex.: Rosa embaixo)"; setSingleLine(true) }
        AlertDialog.Builder(a).setTitle("Salvar como modelo").setView(et)
            .setPositiveButton("Com o texto") { _, _ -> saveModel(et.text.toString(), true) }
            .setNeutralButton("Sem o texto") { _, _ -> saveModel(et.text.toString(), false) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun saveModel(name0: String, withText: Boolean) {
        val name = name0.trim().ifEmpty { "Modelo ${models().size + 1}" }
        val m = org.json.JSONObject().put("name", name).put("style", styleJson(style))
            .put("music", music?.path ?: "").put("musicStart", musicStart).put("mute", mute)
            .put("secs", photoSecs).put("quality", quality()).put("text", if (withText) etText.text.toString() else "")
        saveModels(models().filter { it.optString("name") != name } + m)
        renderModels(); a.toast("Modelo \"$name\" salvo")
    }

    private fun applyModel(m: org.json.JSONObject) {
        style = styleOf(m.optJSONObject("style") ?: org.json.JSONObject()); style.save(a)
        music = m.optString("music").takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }
        musicStart = m.optLong("musicStart"); mute = m.optBoolean("mute"); photoSecs = m.optInt("secs", 8)
        Store.prefs(a).edit().putString("quality", m.optString("quality", "1080p")).apply()
        btnExport.text = "Exportar ${quality()}"; qNote()
        m.optString("text").takeIf { it.isNotEmpty() }?.let { etText.setText(it) }
        seek.progress = style.size - 20
        redraws.forEach { it() }
        updateMusicLabel(); drawPreview(); scheduleSave()
        a.toast("Modelo \"${m.optString("name")}\" aplicado")
    }

    private fun renderModels() {
        if (!::modelBox.isInitialized) return
        modelBox.removeAllViews()
        val l = models()
        if (l.isEmpty()) { a.text("Nenhum modelo ainda. Ajuste o vídeo do jeito que gosta e toque em Salvar como modelo.", 12f, a.MUTED, parent = modelBox); return }
        l.chunked(2).forEach { pair ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            modelBox.addView(row, a.lpMatch())
            pair.forEach { m ->
                val b = a.button("★ " + m.optString("name"), row, weight = true) { applyModel(m) }
                b.setOnLongClickListener {
                    AlertDialog.Builder(a).setTitle("Apagar o modelo \"${m.optString("name")}\"?")
                        .setPositiveButton("Apagar") { _, _ -> saveModels(models().filter { it.optString("name") != m.optString("name") }); renderModels() }
                        .setNegativeButton("Cancelar", null).show(); true
                }
            }
            if (pair.size == 1) row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        }
    }

    // ------------------------------------------------------------------ exportar vários

    private fun exportManyDialog() {
        if (VideoExport.busy()) { a.toast("Espere terminar o que está exportando"); return }
        val l = models()
        val names = listOf("Do jeito de cada esboço") + l.map { "★ " + it.optString("name") }
        AlertDialog.Builder(a).setTitle("Qual modelo usar?").setItems(names.toTypedArray()) { _, i ->
            val m = if (i == 0) null else l[i - 1]
            val all = Projects.list(a)
            val novos = all.filter { it.optInt("versoes") == 0 }
            AlertDialog.Builder(a).setTitle("Exportar quais esboços?")
                .setItems(arrayOf("Só os novos (${novos.size})", "Todos (${all.size})")) { _, j ->
                    val alvo = if (j == 0) novos else all
                    if (alvo.isEmpty()) a.toast("Nenhum esboço pra exportar") else { saveNow(); exportMany(alvo.map { it.optString("id") }, m) }
                }.show()
        }.show()
    }

    /** Exporta um esboço atrás do outro, cada um com legenda sorteada, direto pra Vídeos prontos. */
    private fun exportMany(ids: List<String>, m: org.json.JSONObject?, i: Int = 0, ok: Int = 0) {
        if (i >= ids.size) {
            a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            tvMany.setTextColor(a.OK); tvMany.text = "$ok de ${ids.size} vídeos prontos. Estão em Vídeos prontos, com legenda sorteada."
            renderLibrary(); renderProjects(); return
        }
        if (i == 0) { stopPlay(); a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val p = Projects.get(a, ids[i])
        if (p == null) { exportMany(ids, m, i + 1, ok); return }
        val photo = p.optBoolean("photo")
        val f = Projects.srcFile(a, ids[i], photo)
        if (!f.exists()) { exportMany(ids, m, i + 1, ok); return }
        val st = (m?.optJSONObject("style") ?: p.optJSONObject("style"))?.let { styleOf(it) } ?: TextStyle.load(a)
        val txt = m?.optString("text")?.takeIf { it.isNotEmpty() } ?: p.optString("text")
        val mus = (if (m != null) m.optString("music") else p.optString("music")).takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }
        val mt = if (m != null) m.optBoolean("mute") else p.optBoolean("mute")
        val ms = if (m != null) m.optLong("musicStart") else p.optLong("musicStart")
        val secs = if (m != null) m.optInt("secs", 8) else p.optInt("secs", 8)
        val q = m?.optString("quality")?.ifEmpty { null } ?: quality()
        val cs = p.optLong("cutStart"); val ce = p.optLong("cutEnd")
        val dur = if (photo) 0L else VideoExport.durationMs(f)
        val job = VideoExport.Job(Uri.fromFile(f), photo, secs, txt, st, mus, mt, q,
            if (photo) 0 else cs, if (photo || ce <= 0 || ce >= dur) 0 else ce, ms,
            if (photo) 0 else p.optLong("textStart"), if (photo) 0 else p.optLong("textEnd"), if (photo || m != null) 0 else p.optLong("musicEnd"))
        tvMany.setTextColor(a.MUTED); tvMany.text = "Exportando ${i + 1} de ${ids.size}…"
        VideoExport.export(a, job, { pr -> tvMany.text = "Exportando ${i + 1} de ${ids.size}… $pr%" }) { out, _ ->
            if (out == null) { exportMany(ids, m, i + 1, ok); return@export }
            val cap = p.optString("caption").ifBlank { Captions.randomCaption(a) }
            Thread {
                val vid = VideoExport.addToLibrary(a, out, cap)
                VideoExport.updateLib(a, vid) { it.put("proj", ids[i]) }
                Projects.update(a, ids[i]) { it.put("versoes", it.optInt("versoes") + 1) }
                ui.post { renderLibrary(); exportMany(ids, m, i + 1, ok + 1) }
            }.start()
        }
    }

    // ------------------------------------------------------------------ projetos

    private val saveTask = Runnable { saveNow() }
    private fun scheduleSave() { if (projId == null) return; ui.removeCallbacks(saveTask); ui.postDelayed(saveTask, 600) }

    private fun saveNow() {
        val id = projId ?: return
        Projects.update(a, id) {
            it.put("photo", isPhoto); it.put("secs", photoSecs)
            it.put("text", etText.text.toString()); it.put("caption", etCap.text.toString())
            it.put("style", org.json.JSONObject().put("size", style.size).put("color", style.color).put("bg", style.bg)
                .put("pos", style.pos).put("font", style.font).put("align", style.align))
            it.put("music", music?.path ?: ""); it.put("mute", mute); it.put("updated", System.currentTimeMillis())
            it.put("cutStart", cutStart); it.put("cutEnd", cutEnd); it.put("musicStart", musicStart)
            it.put("textStart", textStart); it.put("textEnd", textEnd); it.put("musicEnd", musicEnd)
        }
        base?.let { b -> Projects.saveThumb(a, id, VideoExport.preview(b, etText.text.toString(), style)) }
        ui.removeCallbacks(projRefresh); ui.postDelayed(projRefresh, 800)
    }
    private val projRefresh = Runnable { renderProjects() }

    private fun newProject() {
        saveNow()
        projId = null; src = null; base = null; isPhoto = false; music = null; mute = false; lastExportId = null
        cutStart = 0; cutEnd = 0; durMs = 0; musicStart = 0; cutRow.visibility = View.GONE
        textStart = 0; textEnd = 0; musicEnd = 0; timeline.thumbs = emptyList(); syncTimeline()
        etText.setText(""); etCap.setText(""); durRow.visibility = View.GONE
        tvSrc.setTextColor(a.MUTED); tvSrc.text = "Escolha um vídeo ou uma foto pra começar."
        updateMusicLabel(); showImagePreview(); drawPreview(); renderProjects()
    }

    private fun openProject(id: String) {
        saveNow()
        val o = Projects.get(a, id) ?: return
        val photo = o.optBoolean("photo")
        val f = Projects.srcFile(a, id, photo)
        if (!f.exists()) { a.toast("O arquivo desse projeto sumiu"); return }
        projId = null // evita salvar enquanto carrega
        isPhoto = photo; src = Uri.fromFile(f); photoSecs = o.optInt("secs", 8)
        o.optJSONObject("style")?.let { st ->
            style = TextStyle(st.optInt("size", 52), st.optString("color", "Branco"), st.optString("bg", "Faixa escura"),
                st.optString("pos", "Baixo"), st.optString("font", "Negrito"), st.optString("align", "Centro"))
        }
        etText.setText(o.optString("text")); etCap.setText(o.optString("caption"))
        music = o.optString("music").takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }
        mute = o.optBoolean("mute")
        cutStart = o.optLong("cutStart"); cutEnd = o.optLong("cutEnd"); musicStart = o.optLong("musicStart")
        textStart = o.optLong("textStart"); textEnd = o.optLong("textEnd"); musicEnd = o.optLong("musicEnd")
        timeline.thumbs = emptyList()
        seek.progress = style.size - 20
        redraws.forEach { it() }
        durRow.visibility = if (photo) View.VISIBLE else View.GONE
        updateMusicLabel()
        projId = id
        loadBase(if (photo) "Projeto de foto aberto" else "Projeto de vídeo aberto")
        a.scrollTo(editorCard)
        a.toast("Esboço aberto: mude texto e música e exporte")
    }

    private fun renderProjects() {
        if (!::projBox.isInitialized) return
        projBox.removeAllViews()
        val l = Projects.list(a)
        if (l.isEmpty()) { a.text("Nenhum projeto ainda.", 12f, a.MUTED, parent = projBox); return }
        l.take(30).forEach { o ->
            val id = o.optString("id")
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8)); gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(a.BG); cornerRadius = a.dp(12).toFloat()
                    setStroke(a.dp(if (id == projId) 2 else 1), if (id == projId) a.ACCENT else a.LINE) }
            }
            projBox.addView(box, a.lpMatch().apply { topMargin = a.dp(8) })
            val th = Projects.thumb(a, id)
            box.addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; if (th.exists()) setImageBitmap(BitmapFactory.decodeFile(th.path)) },
                LinearLayout.LayoutParams(a.dp(54), a.dp(96)).apply { rightMargin = a.dp(10) })
            val side = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            box.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val t = o.optString("text").replace('\n', ' ').take(60).ifEmpty { "(sem texto ainda)" }
            val tag = if (o.optBoolean("flow")) " · do Flow" else ""
            val ver = o.optInt("versoes")
            a.text((if (o.optBoolean("photo")) "🖼 " else "🎬 ") + t, 13f, a.FG, parent = side)
            a.text((if (ver > 0) "$ver versão(ões) exportada(s)" else "Nunca exportado") + tag, 11f, a.MUTED, parent = side)
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            side.addView(r, a.lpMatch())
            a.button(if (id == projId) "Aberto" else "✏ Editar", r, primary = id != projId, weight = true) { if (id != projId) openProject(id) else a.scrollTo(editorCard) }
            val del = a.button("Apagar", r, weight = true) { }
            del.setOnClickListener {
                if (del.tag != "armed") { del.tag = "armed"; del.text = "De novo"; ui.postDelayed({ del.tag = null; del.text = "Apagar" }, 2500) }
                else { if (projId == id) newProject(); Projects.remove(a, id); renderProjects() }
            }
        }
    }

    // ------------------------------------------------------------------ prévia

    private fun changed() { style.save(a); schedulePreview(); scheduleSave() }

    private val previewTask = Runnable { drawPreview() }
    private fun schedulePreview() { ui.removeCallbacks(previewTask); ui.postDelayed(previewTask, 120) }

    private fun drawPreview() {
        val txt = etText.text.toString()
        if (editMode) { textLayer.setImageBitmap(VideoExport.textBitmap(txt, style, 540, 960)); return }
        val b = base
        if (b == null) {
            // sem mídia: mostra o texto num fundo escuro pra testar o estilo
            val blank = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF1C1C24.toInt()) }
            preview.setImageBitmap(VideoExport.preview(blank, txt.ifEmpty { "Seu texto aparece aqui" }, style))
            return
        }
        preview.setImageBitmap(VideoExport.preview(b, txt, style))
    }

    private fun showImagePreview() {
        editMode = false; bgmStop(); ui.removeCallbacks(loopTick); mp = null
        if (::textLayer.isInitialized) { textLayer.visibility = View.GONE; playRow.visibility = View.GONE }
        if (::timeline.isInitialized) { timeline.visibility = View.GONE; tlHint.visibility = View.GONE }
        video.stopPlayback(); video.visibility = View.GONE; preview.visibility = View.VISIBLE
        afterBox.visibility = View.GONE
    }

    // ------------------------------------------------------------------ escolher arquivos

    /** Pergunta onde procurar: Galeria (fotos e vídeos do celular) ou Arquivos (pastas). */
    private fun open(mime: String, req: Int, multiple: Boolean = false) {
        if (mime.startsWith("audio")) { openFiles(mime, req, multiple); return }
        AlertDialog.Builder(a).setTitle("Procurar em")
            .setItems(arrayOf("🖼  Galeria", "📁  Arquivos e pastas")) { _, i ->
                if (i == 0) openGallery(mime, req, multiple) else openFiles(mime, req, multiple)
            }.show()
    }

    private fun openFiles(mime: String, req: Int, multiple: Boolean) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = mime; addCategory(Intent.CATEGORY_OPENABLE); if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        a.startActivityForResult(i, req)
    }

    private fun openGallery(mime: String, req: Int, multiple: Boolean) {
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply {
                type = mime
                if (multiple) putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, minOf(50, android.provider.MediaStore.getPickImagesMaxLimit()))
            }
        } else {
            val col = if (mime.startsWith("video")) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Intent(Intent.ACTION_PICK, col).apply { type = mime; if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
        }
        try {
            @Suppress("DEPRECATION")
            a.startActivityForResult(i, req)
        } catch (e: Exception) { openFiles(mime, req, multiple) } // sem galeria compatível: cai pros arquivos
    }

    /** Devolve true se o resultado era deste editor. */
    fun onResult(req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ_VIDEO && req != REQ_PHOTO && req != REQ_MUSIC && req != REQ_MULTI && req != REQ_IMPORT) return false
        if (req == REQ_IMPORT) {
            if (res != android.app.Activity.RESULT_OK || data == null) return true
            val uris = ArrayList<Uri>()
            data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            if (VideoExport.busy()) { a.toast("Espere terminar o que está rodando"); return true }
            if (uris.isNotEmpty()) importMany(uris)
            return true
        }
        if (req == REQ_MULTI) {
            if (res != android.app.Activity.RESULT_OK || data == null) return true
            val uris = ArrayList<Uri>()
            data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            if (VideoExport.busy()) { a.toast("Espere terminar o que está rodando"); return true }
            if (uris.isNotEmpty()) extractMany(uris)
            return true
        }
        val u = data?.data ?: return true
        if (res != android.app.Activity.RESULT_OK) return true
        try { a.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        when (req) {
            REQ_VIDEO -> loadVideoUri(u)
            REQ_PHOTO -> {
                tvSrc.text = "Preparando a foto…"
                Thread {
                    // foto vira um JPEG novo (sem metadados), já na posição certa
                    val pid = Projects.create(a, true)
                    val f = Projects.srcFile(a, pid, true)
                    val ok = try {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        a.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
                        var smp = 1; while (maxOf(o.outWidth, o.outHeight) / (smp * 2) >= 2160) smp *= 2
                        val b = a.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = smp }) }
                        if (b == null) false else { val r = a.rotateByExif(u, b); f.outputStream().use { r.compress(Bitmap.CompressFormat.JPEG, 95, it) }; true }
                    } catch (e: Exception) { false }
                    ui.post {
                        if (!ok) { Projects.remove(a, pid); a.toast("Não abriu essa foto"); return@post }
                        projId = pid; isPhoto = true; src = Uri.fromFile(f); durRow.visibility = View.VISIBLE
                        loadBase("Foto salva no projeto: vira um vídeo de ${photoSecs}s"); saveNow(); renderProjects()
                    }
                }.start()
            }
            REQ_MUSIC -> Thread {
                val f = VideoExport.importMusic(a, u)
                ui.post {
                    if (f == null) a.toast("Não consegui importar") else { music = f; mute = false; updateMusicLabel(); renderMusics(); scheduleSave(); a.toast("Música salva e escolhida") }
                }
            }.start()
        }
        return true
    }

    private fun loadBase(label: String) {
        val s = src ?: return
        showImagePreview()
        tvSrc.text = label; tvSrc.setTextColor(a.OK)
        cutRow.visibility = if (isPhoto) View.GONE else View.VISIBLE
        Thread {
            val b = VideoExport.baseFrame(a, s, isPhoto)
            val d = if (isPhoto) 0L else VideoExport.durationMs(a, s)
            ui.post {
                base = b; durMs = d
                if (!isPhoto && d > 0) {
                    if (cutEnd <= 0 || cutEnd > d) cutEnd = d
                    if (cutStart >= cutEnd) cutStart = 0
                    syncCut()
                    showEditVideo(s)
                    loadThumbs(s, d)
                } else drawPreview()
            }
        }.start()
    }

    /** Copia o vídeo escolhido pro app e abre como esboço novo no editor. */
    private fun loadVideoUri(u: Uri) {
        tvSrc.setTextColor(a.MUTED); tvSrc.text = "Salvando o vídeo no projeto…"
        Thread {
            val pid = Projects.create(a, false)
            val f = Projects.srcFile(a, pid, false)
            val ok = try { a.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { i.copyTo(it) } }; f.length() > 0 } catch (e: Exception) { false }
            if (ok) VideoExport.baseFrame(a, Uri.fromFile(f), false)?.let { Projects.saveThumb(a, pid, it) }
            ui.post {
                if (!ok) { Projects.remove(a, pid); a.toast("Não consegui abrir esse vídeo"); return@post }
                projId = pid; isPhoto = false; src = Uri.fromFile(f); durRow.visibility = View.GONE
                cutStart = 0; cutEnd = 0
                loadBase("Vídeo salvo no projeto"); saveNow(); renderProjects()
            }
        }.start()
    }

    // ------------------------------------------------------------------ vídeos do Flow

    /** "Meu vídeo": Vídeos do Flow (lista com miniaturas), Galeria ou Arquivos. */
    private fun chooseMyVideo() {
        AlertDialog.Builder(a).setTitle("Escolher vídeo")
            .setItems(arrayOf("✨  Vídeos do Flow", "🖼  Galeria", "📁  Arquivos e pastas")) { _, i ->
                when (i) {
                    0 -> flowPicker(false)
                    1 -> openGallery("video/*", REQ_VIDEO, false)
                    else -> openFiles("video/*", REQ_VIDEO, false)
                }
            }.show()
    }

    fun flowPicker(all: Boolean) {
        if (!FolderScan.hasVideoPerm(a)) {
            a.toast("Permita ver os vídeos e toque de novo")
            a.requestPermissions(FolderScan.perms, 23); return
        }
        val dlg = AlertDialog.Builder(a).setTitle(if (all) "Todos os vídeos" else "Vídeos do Flow").setMessage("Carregando…").show()
        Thread {
            val list = FolderScan.flowVideos(a, all)
            val thumbs = list.take(60).map { v ->
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 29) a.contentResolver.loadThumbnail(v.uri, android.util.Size(180, 320), null) else null
                } catch (e: Exception) { null }
            }
            ui.post {
                try { dlg.dismiss() } catch (e: Exception) { }
                showFlowList(list.take(60), thumbs, all)
            }
        }.start()
    }

    private fun showFlowList(list: List<FolderScan.Vid>, thumbs: List<Bitmap?>, all: Boolean) {
        val scroll = android.widget.ScrollView(a)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8)) }
        scroll.addView(box)
        var dialog: AlertDialog? = null
        if (list.isEmpty()) a.text(if (all) "Nenhum vídeo no celular." else "Não achei vídeos do Flow na pasta ${FolderScan.bucket(a) ?: "Download"}.", 14f, a.FG, parent = box)
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR"))
        list.forEachIndexed { i, v ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6))
                background = GradientDrawable().apply { setColor(a.CARD); cornerRadius = a.dp(10).toFloat(); setStroke(a.dp(1), a.LINE) }
                setOnClickListener { dialog?.dismiss(); loadVideoUri(v.uri) }
            }
            box.addView(row, a.lpMatch().apply { topMargin = a.dp(6) })
            row.addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF000000.toInt()); thumbs.getOrNull(i)?.let { setImageBitmap(it) } },
                LinearLayout.LayoutParams(a.dp(54), a.dp(96)).apply { rightMargin = a.dp(10) })
            val side = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            row.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val big = maxOf(v.w, v.h)
            val q = when { big >= 3000 -> "4K"; big >= 1800 -> "2K"; big >= 1200 -> "1080p"; big > 0 -> "${minOf(v.w, v.h)}p"; else -> "" }
            a.text(fmt.format(java.util.Date(v.added * 1000)) + "  ·  " + t(v.durMs) + (if (q.isNotEmpty()) "  ·  $q" else ""), 13f, a.FG, parent = side, bold = true)
            a.text(v.name, 11f, a.MUTED, parent = side)
        }
        val b = AlertDialog.Builder(a).setView(scroll).setNegativeButton("Fechar", null)
        if (!all) b.setNeutralButton("Ver todos os vídeos") { _, _ -> flowPicker(true) }
        dialog = b.show()
    }

    // ------------------------------------------------------------------ música

    private fun musicLabel2() {
        if (!::tvMusicStart.isInitialized) return
        tvMusicStart.text = if (music != null) "Toca a partir de ${t(musicStart)} da música" + (if (musicEnd > 0) " · para em ${t(musicEnd)} do vídeo" else " · até o fim do vídeo") else ""
        if (::timeline.isInitialized) syncTimeline()
    }

    private fun updateMusicLabel() {
        musicLabel2(); applyVolume()
        if (::timeline.isInitialized) refreshMusicMeta()
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
                else -> { music = list[i - 3]; mute = false; musicStart = 0; musicEnd = 0 }
            }
            updateMusicLabel(); scheduleSave()
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
            small(row, if (playing == f) "♪" else "▶") { play(f) }
            small(row, "Usar") { music = f; mute = false; updateMusicLabel(); scheduleSave(); a.toast("Música escolhida pro vídeo") }
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

    private fun fmt(ms: Int) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            try { playSeek.progress = p.currentPosition; tvTime.text = fmt(p.currentPosition) + " / " + fmt(p.duration) } catch (e: Exception) { }
            ui.postDelayed(this, 300)
        }
    }

    private fun play(f: File) {
        stopPlay()
        player = try { MediaPlayer().apply { setDataSource(f.path); prepare(); start(); setOnCompletionListener { btnPlay.text = "▶" } } } catch (e: Exception) { null }
        val p = player ?: run { a.toast("Não consegui tocar"); return }
        playing = f
        tvNow.text = "♪ " + VideoExport.nice(f)
        playSeek.max = p.duration
        btnPlay.text = "⏸"
        playerBar.visibility = View.VISIBLE
        ui.post(ticker)
    }

    fun stopPlay() {
        if (editMode) pauseEdit()
        ui.removeCallbacks(ticker)
        try { player?.stop(); player?.release() } catch (e: Exception) { }
        player = null; playing = null
        if (::playerBar.isInitialized) playerBar.visibility = View.GONE
    }

    /** Extrai o áudio de vários vídeos, um depois do outro, cada um com o nome do vídeo. */
    private fun extractMany(list: List<Uri>, i: Int = 0, ok: Int = 0) {
        if (i >= list.size) {
            tvBatch.setTextColor(a.OK); tvBatch.text = "$ok de ${list.size} músicas extraídas e salvas"
            a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            renderMusics(); return
        }
        if (i == 0) a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val u = list[i]
        val name = VideoExport.displayName(a, u)
        tvBatch.setTextColor(a.MUTED); tvBatch.text = "Extraindo ${i + 1} de ${list.size}…"
        VideoExport.extractAudio(a, u, { p -> tvBatch.text = "Extraindo ${i + 1} de ${list.size}… $p%" }, { f, _ ->
            if (f != null) renderMusics()
            extractMany(list, i + 1, if (f != null) ok + 1 else ok)
        }, name)
    }

    private fun extract() {
        val s = src ?: run { a.toast("Escolha um vídeo seu primeiro"); return }
        if (isPhoto) { a.toast("Foto não tem áudio. Escolha um vídeo."); return }
        if (VideoExport.busy()) { a.toast("Espere terminar o que está rodando"); return }
        tvProg.setTextColor(a.MUTED); tvProg.text = "Extraindo áudio…"
        VideoExport.extractAudio(a, s, { p -> tvProg.text = "Extraindo áudio… $p%" }, { f, err ->
            if (f == null) { tvProg.setTextColor(a.ACCENT); tvProg.text = "Não deu: $err" }
            else { tvProg.setTextColor(a.OK); tvProg.text = "Áudio salvo em Minhas músicas"; renderMusics() }
        }, "audio_" + (etText.text.toString().take(20).ifBlank { "video" }))
    }

    // ------------------------------------------------------------------ exportar e postar

    private fun export() {
        val s = src ?: run { a.toast("Escolha um vídeo ou uma foto primeiro"); return }
        if (VideoExport.busy()) { a.toast("Já está exportando"); return }
        stopPlay(); showImagePreview()
        btnExport.isEnabled = false
        tvProg.setTextColor(a.MUTED); tvProg.text = "Exportando ${quality()}…"
        a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val job = VideoExport.Job(s, isPhoto, photoSecs, etText.text.toString(), style.copy(), music, mute, quality(),
            if (isPhoto) 0 else cutStart, if (isPhoto || cutEnd >= durMs) 0 else cutEnd, musicStart,
            if (isPhoto) 0 else textStart, if (isPhoto) 0 else textEnd, if (isPhoto) 0 else musicEnd)
        val qn = quality()
        VideoExport.export(a, job, { p -> tvProg.text = "Exportando $qn… $p%" }) { f, err ->
            a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnExport.isEnabled = true
            if (f == null) { tvProg.setTextColor(a.ACCENT); tvProg.text = "Não deu: $err"; return@export }
            tvProg.text = "Salvando…"
            Thread {
                val id = VideoExport.addToLibrary(a, f, etCap.text.toString().trim())
                val pid = projId
                if (pid != null) {
                    VideoExport.updateLib(a, id) { it.put("proj", pid) }
                    Projects.update(a, pid) { it.put("versoes", it.optInt("versoes") + 1) }
                }
                ui.post {
                    lastExportId = id
                    tvProg.setTextColor(a.OK); tvProg.text = "Pronto! Toque no vídeo pra pausar."
                    preview.visibility = View.GONE; video.visibility = View.VISIBLE
                    video.setVideoURI(Uri.fromFile(File(VideoExport.videoDir(a), "$id.mp4"))); video.start()
                    afterBox.visibility = View.VISIBLE
                    renderLibrary(); renderProjects()
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
        // posta direto do app: nada é baixado nem copiado pra galeria
        val f = File(VideoExport.videoDir(a), "$id.mp4")
        if (!f.exists()) { a.toast("Esse vídeo não está mais no app"); return }
        VideoExport.updateLib(a, id) { it.put("legenda", cap); it.put("posts", it.optInt("posts") + 1); it.put("lastPost", System.currentTimeMillis()) }
        VideoExport.markPosted(a, id)
        CaptionDB.used(a, cap)
        renderLibrary()
        a.toast(if (cap.isEmpty()) "Abrindo o Instagram" else "Legenda copiada: no Instagram é só colar e postar")
        a.startActivity(VideoExport.shareToInstagram(a, PostQueue.uri(id).buildUpon().path("/$id.mp4").build(), "video/mp4"))
    }

    private fun renderLibrary() {
        updateCycle()
        libBox.removeAllViews()
        val l = VideoExport.library(a)
        if (l.isEmpty()) { a.text("Nenhum vídeo pronto ainda. Exporte um esboço ou importe os que você já tem.", 12f, a.MUTED, parent = libBox); return }
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
            if (posts > 0) {
                val nota = o.optString("nota")
                val rr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                side.addView(rr, a.lpMatch())
                listOf("good" to "🔥", "meh" to "😐", "bad" to "👎").forEach { (k, e) ->
                    a.button(e, rr, primary = nota == k, weight = true) {
                        CaptionDB.rate(a, o.optString("legenda"), k); VideoExport.updateLib(a, id) { it.put("nota", k) }; renderLibrary()
                        a.toast("Anotado: o app vai ${if (k == "good") "usar mais" else if (k == "bad") "evitar" else "manter"} legendas assim")
                    }
                }
            }
            val pj = o.optString("proj")
            if (pj.isNotEmpty() && Projects.get(a, pj) != null) a.button("✏", r1, weight = true) { openProject(pj) }
            a.button("Postar ↗", r1, primary = true, weight = true) { post(id, et.text.toString()) }
            val del = a.button("Apagar", r1, weight = true) { }
            del.setOnClickListener {
                if (del.tag != "armed") { del.tag = "armed"; del.text = "De novo"; ui.postDelayed({ del.tag = null; del.text = "Apagar" }, 2500) }
                else { VideoExport.removeLib(a, id); renderLibrary() }
            }
        }
    }
}
