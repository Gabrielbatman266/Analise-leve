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
        const val REQ_RAW_MANY = 36
    }

    private var src: Uri? = null
    private var isPhoto = false
    private var photoSecs = 8
    private var crop = VideoExport.CropSpec()
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
    private val SEC_NAMES = listOf("✏ Editar", "🗂 Categorias", "🎬 Fila", "🎵 Músicas", "⚙ Auto")
    private val NAV_ORDER = listOf(2, 1, 0, 3, 4) // ordem dos botões no topo
    private lateinit var navRow: LinearLayout
    private lateinit var tvBusy: TextView
    private val navBtns = ArrayList<Button>()
    private var secs: List<LinearLayout> = emptyList()
    private var curSec = 0
    private lateinit var startBox: LinearLayout
    private lateinit var work: LinearLayout

    fun showSection(i: Int, scroll: Boolean = true) {
        if (secs.isEmpty()) return
        if (curSec == 0 && i != 0) stopPlay()
        curSec = i
        Store.prefs(a).edit().putInt("ig_sec", i).apply()
        secs.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        updateNav()
        if (scroll) a.scrollTo(navRow)
    }

    private fun updateNav() {
        if (navBtns.isEmpty()) return
        val counts = listOf(-1, -1, VideoExport.library(a).count { it.optInt("posts") == 0 }, -1, -1)
        navBtns.forEachIndexed { pos, b ->
            val k = NAV_ORDER[pos]
            val on = k == curSec
            b.text = SEC_NAMES[k] + if (counts[k] > 0) " ${counts[k]}" else ""
            b.textSize = 11f; b.setTextColor(if (on) 0xFF1A0A10.toInt() else a.FG)
            b.background = GradientDrawable().apply { setColor(if (on) a.ACCENT else 0xFF1C1C24.toInt()); cornerRadius = a.dp(10).toFloat() }
        }
    }

    /** Sem vídeo aberto: só as opções de começar. Com vídeo: a área de edição. */
    private fun updateEmpty() {
        if (!::startBox.isInitialized) return
        val empty = src == null
        startBox.visibility = if (empty) View.VISIBLE else View.GONE
        work.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private var pendingMusicAsk = false
    private var postAfter = false
    private lateinit var btnExpPost: Button
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
            ui.post { if (my == frameJob && b != null) { if (start) base = b; preview.setImageBitmap(VideoExport.preview(pv(b), etText.text.toString(), style)) } }
        }.start()
    }
    private lateinit var tvQ: TextView
    private lateinit var modelBox: LinearLayout
    private lateinit var tvCycle: TextView
    private lateinit var tvImport: TextView

    private fun updateCycle() {
        if (!::tvCycle.isInitialized) return
        val lib = VideoExport.library(a)
        tvCycle.text = if (lib.isEmpty()) "" else "${lib.size} vídeo(s) · ${lib.count { it.optInt("posts") == 0 }} nunca postado(s) · ${lib.count { Learn.isWin(it) }} 🔥/💎"
    }

    /** Sorteia um vídeo que ainda não saiu nesta rodada e uma legenda do banco. */
    private fun postRandomVideo() {
        val id = VideoExport.cycleNext(a) ?: run { a.toast("Nenhum vídeo pronto ainda. Exporte ou importe alguns."); return }
        post(id, VideoExport.captionFor(a, VideoExport.library(a).firstOrNull { it.optString("id") == id }, true))
    }

    /** Importa vários vídeos prontos, um depois do outro, cada um vira arquivo novo sem metadados. */
    private fun importMany(list: List<Uri>, i: Int = 0, ok: Int = 0) {
        if (i >= list.size) {
            a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            tvImport.setTextColor(a.OK); tvImport.text = "✓ $ok de ${list.size} vídeo(s) prontos na fila, sem metadados."
            QuickPost.updateWidgets(a)
            renderLibrary(); return
        }
        if (i == 0) { a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); showSection(2) }
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

    fun refreshLists() { if (::catBox.isInitialized) { renderProjects(); renderLibrary() } }

    private val ui get() = a.ui

    fun build() {
        // ---------- navegação: uma seção por vez
        val host = a.page
        navRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        host.addView(navRow, a.lpMatch().apply { topMargin = a.dp(12) })
        NAV_ORDER.forEach { i -> navBtns += a.button(SEC_NAMES[i], navRow, weight = true) { showSection(i) } }
        tvBusy = a.text("", 13f, a.MUTED, parent = host).apply {
            visibility = View.GONE; setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
            background = GradientDrawable().apply { setColor(0xFF1C1C24.toInt()); cornerRadius = a.dp(10).toFloat() }
        }
        secs = List(5) { LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }.also { v -> host.addView(v, a.lpMatch()) } }

        // ---------- 🗂 categorias: cada categoria = seus vídeos crus + seus textos
        a.page = secs[1]
        catHead = a.card("🗂 Categorias")
        a.text("Separe os vídeos crus por tipo (ex.: Falando, Academia, Olhar). Cada categoria tem os textos dela; a produção só põe num vídeo textos da categoria dele (ou os gerais).", 13f, a.MUTED, parent = catHead)
        val rr0 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        catHead.addView(rr0, a.lpMatch())
        a.button("＋ Nova categoria", rr0, primary = true, weight = true) { newCtxDialog { openCat = it; renderProjects() } }
        a.button("✨ Separar vídeos com IA", rr0, weight = true) { classifyRaw() }
        a.button("＋＋ Adicionar vários vídeos crus", catHead) { chooseRawMany(openCat) }
        a.button("📥 Já tenho vídeos prontos (editados) › vão direto pra Fila", catHead) { importReady() }
        tvRaw = a.text("", 12f, a.OK, parent = catHead)
        catBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        a.page.addView(catBox, a.lpMatch())
        renderProjects()

        // ---------- editor
        a.page = secs[0]
        val ce = a.card("Editor")
        editorCard = ce
        startBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        ce.addView(startBox, a.lpMatch())
        a.text("O que você quer editar?", 16f, a.FG, parent = startBox, bold = true)
        a.button("✨  Vídeo do Flow", startBox, primary = true) { flowPicker(false) }
        a.button("🖼  Vídeo da galeria", startBox) { openGallery("video/*", REQ_VIDEO, false) }
        a.button("📷  Foto → vídeo", startBox) { open("image/*", REQ_PHOTO) }
        a.button("📁  Abrir um esboço salvo", startBox) { showSection(1) }
        a.text("Os vídeos novos do Flow já viram esboço sozinhos. É só abrir e editar.", 12f, a.MUTED, parent = startBox)
        work = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        ce.addView(work, a.lpMatch())
        val pick = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        work.addView(pick, a.lpMatch())
        tvSrc = a.text("", 13f, a.MUTED, parent = pick)
        (tvSrc.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; gravity = Gravity.CENTER_VERTICAL }
        a.button("Trocar", pick) { chooseMyVideo() }.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        a.button("＋ Novo", pick) { newProject() }.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        a.text("Modelos (toque pra aplicar, segure pra apagar)", 12f, a.MUTED, parent = work)
        modelBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        work.addView(modelBox, a.lpMatch())
        a.button("☆ Salvar como modelo", work) { saveModelDialog() }
        renderModels()

        // prévia 9:16
        val w = a.resources.displayMetrics.widthPixels - a.dp(64)
        val pw = minOf(w, a.dp(260)); val ph = pw * 16 / 9
        val frame = FrameLayout(a).apply {
            background = GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = a.dp(12).toFloat(); setStroke(a.dp(1), a.LINE) }
        }
        work.addView(frame, LinearLayout.LayoutParams(pw, ph).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = a.dp(8) })
        preview = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        run {
            var lx = 0f; var ly = 0f
            preview.setOnTouchListener { v, e ->
                if (!crop.active || base == null) return@setOnTouchListener false
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; v.parent.requestDisallowInterceptTouchEvent(true) }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val k = 2f / maxOf(1f, crop.zoom - 0.9f)
                        crop = crop.copy(px = (crop.px - (e.x - lx) / v.width * k).coerceIn(-1f, 1f), py = (crop.py - (e.y - ly) / v.height * k).coerceIn(-1f, 1f))
                        lx = e.x; ly = e.y; drawPreview()
                    }
                    android.view.MotionEvent.ACTION_UP -> { updateCropUi(); scheduleSave() }
                }
                true
            }
        }
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
        work.addView(playRow, a.lpMatch().apply { topMargin = a.dp(6) })
        btnTrecho = a.button("▶ Ver trecho", playRow, primary = true, weight = true) { togglePlay() }
        tvPos = TextView(a).apply { setTextColor(a.FG); textSize = 15f; gravity = Gravity.CENTER; text = "0:00,0" }
        playRow.addView(tvPos, LinearLayout.LayoutParams(a.dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---------- linha do tempo (camadas) + ferramentas de cada camada
        timeline = TimelineView(a).apply { visibility = View.GONE }
        work.addView(timeline, a.lpMatch().apply { topMargin = a.dp(10) })
        tlHint = a.text("Toque numa camada pra editar. Bordas cortam; arrastar a música sincroniza; o bloco do texto escolhe quando ele aparece.", 11f, a.MUTED, parent = work)
        tlHint.visibility = View.GONE
        timeline.onSelect = { tk ->
            if (tk == 1 && music == null) pendingMusicAsk = true
            if (tk == 2) pendingTextFocus = true
            showPanel(tk)
        }
        timeline.onChange = { what, fin -> onTimeline(what, fin) }
        val tabs = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        work.addView(tabs, a.lpMatch().apply { topMargin = a.dp(8) })
        listOf("🎬 Vídeo", "🎵 Música", "T Texto", "⬆ Exportar").forEachIndexed { i, n ->
            tabBtns += a.button(n, tabs, weight = true) { showPanel(i); if (i < 3) { timeline.selected = i; timeline.invalidate() } }
        }
        pVideo = panel(work); pMusic = panel(work); pText = panel(work); pExport = panel(work)

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

        // ⛶ recortar / zoom
        val cc = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        pVideo.addView(cc, a.lpMatch().apply { topMargin = a.dp(10) })
        a.text("⛶ Recortar / zoom", 14f, a.FG, parent = cc, bold = true)
        a.text("Corta bordas, marca d'água no canto ou algo que você não quer. Com zoom, arraste a prévia parada pra escolher o pedaço.", 12f, a.MUTED, parent = cc)
        redraws += a.chips(cc, listOf("Original", "Preencher 9:16"), { if (crop.fill) "Preencher 9:16" else "Original" }) { crop = crop.copy(fill = it != "Original"); afterCrop() }
        redraws += a.chips(cc, listOf("1x", "1.2x", "1.5x", "2x", "3x"), { listOf(1f, 1.2f, 1.5f, 2f, 3f).minBy { z -> Math.abs(z - crop.zoom) }.let { z -> if (z == 1f) "1x" else "${z}x".replace(".0x", "x") } }) {
            crop = crop.copy(zoom = it.removeSuffix("x").toFloat()); afterCrop()
        }
        val cropRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        cc.addView(cropRow, a.lpMatch())
        listOf("◀" to (-0.2f to 0f), "▲" to (0f to -0.2f), "▼" to (0f to 0.2f), "▶" to (0.2f to 0f)).forEach { (t, d) ->
            a.button(t, cropRow, weight = true) { crop = crop.copy(px = (crop.px + d.first).coerceIn(-1f, 1f), py = (crop.py + d.second).coerceIn(-1f, 1f)); afterCrop() }
        }
        a.button("↺ Sem recorte", cropRow, weight = true) { crop = VideoExport.CropSpec(); afterCrop() }
        tvCrop = a.text("", 11f, a.MUTED, parent = cc)

        // 🧽 remover objeto com IA (fotos)
        val rm = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        pVideo.addView(rm, a.lpMatch().apply { topMargin = a.dp(10) })
        a.text("🧽 Remover objeto com IA", 14f, a.FG, parent = rm, bold = true)
        tvRm = a.text("", 12f, a.MUTED, parent = rm)
        val rr2 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        rm.addView(rr2, a.lpMatch())
        btnRm = a.button("🧽 O que remover?", rr2, primary = true, weight = true) { removeObjectDialog() }
        btnUndoRm = a.button("↺ Voltar a original", rr2, weight = true) { undoRemove() }

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
        // exportar e postar ficam sempre visíveis, embaixo das abas
        val expRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        work.addView(expRow, a.lpMatch().apply { topMargin = a.dp(10) })
        btnExport = a.button("Exportar ${quality()}", expRow, primary = true, weight = true) { postAfter = false; export() }
        btnExpPost = a.button("⬆ Exportar e postar", expRow, weight = true) { postAfter = true; export() }
        qNote()
        tvProg = a.text("", 13f, a.OK, parent = work)
        afterBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        work.addView(afterBox, a.lpMatch())
        a.button("Postar no Instagram ↗", afterBox, primary = true) { lastExportId?.let { post(it, etCap.text.toString()) } }
        a.button("Voltar pra edição", afterBox) { val s0 = src; if (s0 != null && !isPhoto) showEditVideo(s0) else { showImagePreview(); drawPreview() } }

        showPanel(Store.prefs(a).getInt("ed_panel", 0))
        updateEmpty()

        // ---------- músicas
        a.page = secs[3]
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

        // ---------- 🤖 produção automática
        a.page = secs[4]
        val ca = a.card("⚙ Produção automática")
        a.text("Junta um vídeo cru + um texto da categoria dele + um modelo de edição e exporta pra 🎬 Fila. Marque 🔥/😐/👎 depois de postar: o app usa mais o que bomba.", 13f, a.MUTED, parent = ca)
        a.text("Quantos vídeos", 12f, a.MUTED, parent = ca)
        a.chips(ca, listOf("3", "5", "10", "20"), { Store.prefs(a).getInt("auto_n", 5).toString() }) { Store.prefs(a).edit().putInt("auto_n", it.toInt()).apply() }
        a.text("Edição", 12f, a.MUTED, parent = ca)
        a.chips(ca, listOf("Modelo sorteado (aprende)", "Do jeito de cada cru"), { if (Store.prefs(a).getBoolean("auto_model", true)) "Modelo sorteado (aprende)" else "Do jeito de cada cru" }) {
            Store.prefs(a).edit().putBoolean("auto_model", it.startsWith("Modelo")).apply()
        }
        a.text("Produzir de qual categoria", 12f, a.MUTED, parent = ca)
        autoCtxBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        ca.addView(autoCtxBox, a.lpMatch())
        btnAuto = a.button("🤖 Produzir agora", ca, primary = true) { produceNow(Store.prefs(a).getInt("auto_n", 5)) }
        tvAuto = a.text("", 13f, a.OK, parent = ca)
        a.text("🎯 Meta de vídeos por dia", 13f, a.FG, parent = ca, bold = true)
        a.text("De manhã o app deixa os vídeos do dia prontos e, das 8h às 23h, vai lembrando \"Hora do vídeo 3 de 10\" com o botão Postar, em horários espaçados.", 12f, a.MUTED, parent = ca)
        a.chips(ca, listOf("Desligado", "3", "5", "10", "15", "20"), { Daily.goal(a).let { if (it == 0) "Desligado" else it.toString() } }) {
            Daily.setGoal(a, if (it == "Desligado") 0 else it.toInt()); updateDaily()
        }
        tvDaily = a.text("", 13f, a.OK, parent = ca)
        updateDaily()
        a.text("Textos novos no vídeo com IA", 12f, a.MUTED, parent = ca)
        a.chips(ca, listOf("Só quando eu pedir", "Automático"), { if (AutoProducer.textAiAuto(a)) "Automático" else "Só quando eu pedir" }) {
            Store.prefs(a).edit().putBoolean("ov_ai_auto", it == "Automático").apply()
        }
        a.text("Só quando eu pedir: a produção usa os textos que já existem e não gasta crédito. Pra criar mais, abra a categoria em 🗂 e toque em ✨.", 11f, a.MUTED, parent = ca)
        a.text("Manter sempre vídeos prontos: quando faltar, o app produz sozinho em segundo plano (depois de cada post e ao abrir)", 12f, a.MUTED, parent = ca)
        a.chips(ca, listOf("Desligado", "3", "5", "10"), { Store.prefs(a).getInt("auto_keep", 0).let { if (it == 0) "Desligado" else it.toString() } }) {
            Store.prefs(a).edit().putInt("auto_keep", if (it == "Desligado") 0 else it.toInt()).apply()
        }

        val cx2 = a.card("Exportar esboços com um modelo")
        a.text("Sem IA e sem texto novo: aplica um modelo nos vídeos crus e exporta todos.", 12f, a.MUTED, parent = cx2)
        a.button("⚡ Exportar vários de uma vez", cx2) { exportManyDialog() }
        tvMany = a.text("", 13f, a.OK, parent = cx2)

        val cs2 = a.card("O que está bombando")
        tvStats = a.text("", 13f, a.FG, parent = cs2)
        renderCtx(); renderStats()

        // ---------- biblioteca
        a.page = secs[2]
        val cf0 = a.card("🎬 Hoje")
        tvFila = a.text("", 15f, a.FG, parent = cf0, bold = true)
        a.button("🎬 Postar um vídeo agora", cf0, primary = true) { postRandomVideo() }
        val fr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        cf0.addView(fr, a.lpMatch())
        a.button("📥 Adicionar vídeos prontos", fr, weight = true) { importReady() }
        a.button("🤖 Produzir mais ${Store.prefs(a).getInt("auto_n", 5)}", fr, weight = true) { produceNow(Store.prefs(a).getInt("auto_n", 5)) }
        tvImport = a.text("", 13f, a.OK, parent = cf0)
        a.text("📥 = vídeos que você já editou (no CapCut, no Flow…): entram direto na fila, sem metadados, pode marcar vários. Meta diária e IA: em ⚙ Auto.", 11f, a.MUTED, parent = cf0)
        val cl = a.card("Fila de vídeos prontos")
        a.text("Sorteio com peso: o que bombou sai muito mais, vídeo novo tem prioridade, o que flopou quase não volta — e nunca o mesmo vídeo, o mesmo vídeo cru ou o mesmo texto em seguida. 12 h depois de cada post o app pergunta como foi.", 12f, a.MUTED, parent = cl)
        tvCycle = a.text("", 13f, a.OK, parent = cl)
        libBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        cl.addView(libBox, a.lpMatch())
        renderLibrary()
        showSection(Store.prefs(a).getInt("ig_sec", 2), false)
        // (a aba continua com a.page = Prontos: o cartão "Foto pro Instagram" entra ali)
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
        curModel = m.optString("name")
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

    // ------------------------------------------------------------------ 🤖 produção automática

    private lateinit var tvDaily: TextView
    private lateinit var tvFila: TextView
    fun updateDaily() {
        if (::tvFila.isInitialized) {
            val g = Daily.goal(a); val w = AutoProducer.waiting(a)
            tvFila.text = (if (g > 0) Daily.status(a) + "\n" else "") + "$w vídeo(s) pronto(s) na fila" + if (AutoProducer.running) " · 🤖 produzindo…" else ""
        }
        if (!::tvDaily.isInitialized) return
        val g = Daily.goal(a)
        val next = Daily.nextAt(a)
        tvDaily.text = if (g <= 0) "" else Daily.status(a) + " · ${AutoProducer.waiting(a)} prontos" +
            (if (Daily.remaining(a) > 0 && next > System.currentTimeMillis()) " · próximo lembrete " + android.text.format.DateFormat.format("HH:mm", next) else "")
    }

    private lateinit var btnAuto: Button
    private lateinit var tvAuto: TextView
    private lateinit var ovBox: LinearLayout
    private lateinit var tvStats: TextView
    private var curModel = ""


    // ------------------------------------------------------------------ contextos

    private lateinit var tvRaw: TextView
    private lateinit var rawFilter: LinearLayout
    private lateinit var autoCtxBox: LinearLayout
    private lateinit var txtCtxBox: LinearLayout
    private lateinit var ctxCard: LinearLayout
    private lateinit var ctxBox: LinearLayout
    private var rawCtx: String? = null      // filtro dos crus (null = todos)
    private var txtCtx: String? = ""        // contexto do banco de textos ("" = Geral, null = ver todos)

    /** Linha de etiquetas de contexto. includeAll = mostra "Todos" (valor null). */
    private fun ctxChips(parent: LinearLayout, includeAll: Boolean, cur: String?, counts: ((String) -> Int)? = null, pick: (String?) -> Unit) {
        parent.removeAllViews()
        val sc = android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        sc.addView(row); parent.addView(sc, a.lpMatch().apply { topMargin = a.dp(6) })
        val opts: List<String?> = (if (includeAll) listOf<String?>(null) else emptyList()) + listOf<String?>(Contexts.GERAL) + Contexts.names(a)
        opts.forEach { n ->
            val on = n == cur
            val cnt = if (n != null && counts != null) counts(n) else -1
            row.addView(TextView(a).apply {
                text = (if (n == null) "Todos" else Contexts.label(a, n)) + if (cnt > 0) " $cnt" else ""
                textSize = 13f; setTextColor(if (on) 0xFF1A0A10.toInt() else a.FG); setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6))
                background = GradientDrawable().apply { setColor(if (on) a.ACCENT else 0xFF1C1C24.toInt()); cornerRadius = a.dp(16).toFloat() }
                setOnClickListener { pick(n) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = a.dp(6) })
        }
        row.addView(TextView(a).apply {
            text = "＋"; textSize = 15f; setTextColor(a.FG); setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(4))
            background = GradientDrawable().apply { setColor(0xFF1C1C24.toInt()); cornerRadius = a.dp(16).toFloat() }
            setOnClickListener { newCtxDialog { n -> renderCtx(); renderProjects(); pick(n) } }
        })
    }

    private fun newCtxDialog(done: (String) -> Unit) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(20), a.dp(8), a.dp(20), 0) }
        val en = EditText(a).apply { hint = "Nome (ex.: Falando, Olhar, Academia)"; setSingleLine(true) }
        val ed = EditText(a).apply { hint = "Como são esses vídeos (ajuda a IA)" }
        val cb = android.widget.CheckBox(a).apply { text = "🎬 Sem texto: o vídeo já está pronto, o app só edita"; setTextColor(a.FG) }
        en.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { if (Contexts.isGeneric(e.toString())) cb.isChecked = true }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        box.addView(en); box.addView(ed); box.addView(cb)
        AlertDialog.Builder(a).setTitle("Nova categoria").setView(box)
            .setPositiveButton("Criar") { _, _ -> val n = Contexts.add(a, en.text.toString(), ed.text.toString().trim(), cb.isChecked); if (n.isNotEmpty()) done(n) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun chooseCtx(cur: String, done: (String) -> Unit) {
        val names = listOf(Contexts.GERAL) + Contexts.names(a)
        val labels = names.map { (if (it == cur) "✓ " else "") + if (it.isEmpty()) "📥 Sem categoria (geral)" else Contexts.label(a, it) } + "＋ Nova categoria…"
        AlertDialog.Builder(a).setTitle("Mover pra qual categoria?").setItems(labels.toTypedArray()) { _, i ->
            if (i < names.size) done(names[i]) else newCtxDialog { done(it) }
        }.show()
    }

    /** Atualiza todas as linhas de contexto (Auto, textos, cartão de contextos). */
    private fun renderCtx() {
        if (!::autoCtxBox.isInitialized) return
        val ac = Store.prefs(a).getString("auto_ctx", null)
        ctxChips(autoCtxBox, true, ac, counts = { n -> Projects.list(a).count { it.optString("ctx") == n } }) { n ->
            Store.prefs(a).edit().apply { if (n == null) remove("auto_ctx") else putString("auto_ctx", n) }.apply(); renderCtx()
        }
    }

    /** Textos só deste vídeo: descrição do que acontece + lista + gerar com IA olhando o vídeo. */
    private fun videoTexts(pid: String) {
        val p = Projects.get(a, pid) ?: return
        val key = AutoProducer.vidKey(pid)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(8)) }
        val sc = android.widget.ScrollView(a).apply { addView(box) }
        a.text("O que acontece neste vídeo?", 13f, a.FG, parent = box, bold = true)
        val ed = EditText(a).apply {
            setText(p.optString("desc")); hint = "Ex.: ela falando sobre o dia dela, rindo pra câmera"
            setTextColor(a.FG); setHintTextColor(a.MUTED); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addView(ed, a.lpMatch())
        a.text("Com a descrição, a IA cria textos que só servem pra este vídeo, e a produção usa eles primeiro.", 11f, a.MUTED, parent = box)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(a).apply { setTextColor(a.OK); textSize = 12f }
        fun draw() {
            list.removeAllViews()
            val l = OverlayDB.all(a, key)
            if (l.isEmpty()) a.text("Ainda sem textos próprios.", 12f, a.MUTED, parent = list)
            l.forEach { o ->
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                list.addView(row, a.lpMatch().apply { topMargin = a.dp(4) })
                val n = OverlayDB.rated(o)
                row.addView(TextView(a).apply { text = "T  " + o.optString("t") + (if (n > 0) "\n${(OverlayDB.mean(o) * 100).toInt()}% · 🔥${o.optInt("good")} 👎${o.optInt("bad")}" else ""); textSize = 13f; setTextColor(a.FG) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                small(row, "✕") { OverlayDB.remove(a, o.optString("t")); draw() }
            }
        }
        val add = EditText(a).apply {
            hint = "Escrever um texto pra este vídeo (Enter = nova linha)"; setTextColor(a.FG); setHintTextColor(a.MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addView(add, a.lpMatch().apply { topMargin = a.dp(8) })
        val rr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(rr, a.lpMatch())
        a.button("Salvar texto", rr, weight = true) {
            val t = add.text.toString().trim(); if (t.isNotEmpty()) { OverlayDB.add(a, t, "meu", key); add.setText(""); draw() }
        }
        a.button("✨ Gerar 8 com IA", rr, primary = true, weight = true) {
            Projects.update(a, pid) { it.put("desc", ed.text.toString().trim()) }
            status.setTextColor(a.MUTED); status.text = "A IA está olhando o vídeo…"
            Thread { val r = AutoProducer.genTexts(a, 8, key); ui.post { status.setTextColor(a.OK); status.text = r; draw() } }.start()
        }
        box.addView(status)
        box.addView(list, a.lpMatch())
        draw()
        AlertDialog.Builder(a).setView(sc)
            .setPositiveButton("Pronto") { _, _ -> Projects.update(a, pid) { it.put("desc", ed.text.toString().trim()) }; renderProjects() }
            .setOnDismissListener { Projects.update(a, pid) { it.put("desc", ed.text.toString().trim()) }; renderProjects() }
            .show()
    }

    /** A IA olha um quadro de cada cru sem contexto e separa (cria contextos novos se precisar). */
    private fun classifyRaw() {
        if (Ai.key(a).isEmpty()) { a.toast("Coloque a chave da IA em Ajustes"); return }
        val alvo = Projects.list(a).filter { it.optString("ctx").isEmpty() }
        if (alvo.isEmpty()) { a.toast("Todos os crus já têm contexto. Toque na etiqueta de um pra trocar."); return }
        tvRaw.setTextColor(a.MUTED); tvRaw.text = "Separando ${alvo.size} crus…"
        Thread {
            var done = 0; var err = ""
            alvo.chunked(6).forEach { lote ->
                val frames = lote.mapIndexedNotNull { k, p ->
                    val photo = p.optBoolean("photo"); val f = Projects.srcFile(a, p.optString("id"), photo)
                    val b = if (photo) BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 }) else VideoExport.frameAt(a, Uri.fromFile(f), 1500)
                    if (b == null) null else File(a.cacheDir, "cls_$k.jpg").also { o -> o.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } }.let { p to it }
                }
                if (frames.isEmpty()) return@forEach
                try {
                    val ctxs = Contexts.list(a).map { it.optString("name") to it.optString("desc") }
                    val r = Ai.classify(a, frames.map { it.second }, ctxs)
                    frames.forEachIndexed { k, (p, _) ->
                        val name = r.getOrNull(k).orEmpty()
                        if (name.isNotEmpty()) { val n = Contexts.add(a, name); Projects.update(a, p.optString("id")) { it.put("ctx", n) }; done++ }
                    }
                } catch (e: Exception) { err = e.message ?: "erro" }
                ui.post { tvRaw.text = "Separando… $done de ${alvo.size}"; renderProjects() }
            }
            ui.post {
                tvRaw.setTextColor(if (err.isEmpty()) a.OK else a.ACCENT)
                tvRaw.text = "$done de ${alvo.size} crus separados." + if (err.isNotEmpty()) " A IA parou: ${err.take(60)}" else " Toque na etiqueta de um cru pra corrigir."
                renderProjects(); renderCtx()
            }
        }.start()
    }

    private fun renderOverlays() {
        if (!::ovBox.isInitialized) return
        ovBox.removeAllViews()
        renderCtxQuiet()
        val l = OverlayDB.all(a, txtCtx)
        if (l.isEmpty()) a.text("Nenhum texto ainda. Escreva alguns ou toque em ✨ Gerar com IA. Sem nada aqui, a produção usa frases prontas.", 12f, a.MUTED, parent = ovBox)
        if (l.size > 40) a.text("Mostrando 40 de ${l.size}", 12f, a.MUTED, parent = ovBox)
        l.sortedByDescending { OverlayDB.mean(it) * 10 + (if (OverlayDB.rated(it) > 0) 1 else 0) }.take(40).forEach { o ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            ovBox.addView(row, a.lpMatch().apply { topMargin = a.dp(6) })
            val n = OverlayDB.rated(o)
            val badge = if (n > 0) "${(OverlayDB.mean(o) * 100).toInt()}% · " else ""
            val oc = o.optString("ctx")
            row.addView(TextView(a).apply {
                setOnLongClickListener { chooseCtx(oc) { n -> OverlayDB.setCtx(a, o.optString("t"), n); renderOverlays() }; true }
                text = "T  " + o.optString("t") + "\n" + (if (txtCtx == null) (if (oc.startsWith("v:")) "🎬 de um vídeo" else Contexts.label(a, oc)) + " · " else "") + badge + "usado ${o.optInt("uses")}x" + (if (o.optInt("good") > 0) " · 🔥${o.optInt("good")}" else "") + (if (o.optInt("bad") > 0) " · 👎${o.optInt("bad")}" else "")
                textSize = 13f; setTextColor(a.FG)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            small(row, "✕") { OverlayDB.remove(a, o.optString("t")); renderOverlays() }
        }
        renderStats()
    }

    private fun renderCtxQuiet() {
        if (::txtCtxBox.isInitialized) ctxChips(txtCtxBox, true, txtCtx, counts = { n -> OverlayDB.count(a, n) }) { txtCtx = it; renderOverlays() }
    }

    private fun renderStats() {
        if (!::tvStats.isInitialized) return
        val tt = OverlayDB.top(a, 5)
        val mm = models().mapNotNull { m -> ModelScore.pct(m)?.let { m.optString("name") to it } }.sortedByDescending { it.second }.take(5)
        tvStats.text = buildString {
            append("Textos no vídeo:")
            if (tt.isEmpty()) append(" ainda sem avaliação") else tt.forEach { append("\n• ").append(it.optString("t").replace('\n', ' ').take(50)).append("  ${(OverlayDB.mean(it) * 100).toInt()}%") }
            Contexts.names(a).forEach { n ->
                OverlayDB.top(a, 1, n).firstOrNull()?.let { append("\n${Contexts.label(a, n)}: ").append(it.optString("t").replace('\n', ' ').take(40)).append("  ${(OverlayDB.mean(it) * 100).toInt()}%") }
            }
            append("\n\nModelos de edição:")
            if (mm.isEmpty()) append(" ainda sem avaliação") else mm.forEach { append("\n★ ${it.first}  ${it.second}%") }
            append("\n\nMarque 🔥/😐/👎 nos vídeos postados (Prontos). Cada nota ensina o texto, o modelo e a legenda.")
        }
    }

    private fun genOverlays(n: Int, ctx: String = "", then: (() -> Unit)? = null) {
        if (::tvAuto.isInitialized) { tvAuto.setTextColor(a.MUTED); tvAuto.text = "Criando textos com a IA" + (if (ctx.isNotEmpty() && !ctx.startsWith("v:")) " pra $ctx" else "") + "…" }
        Thread {
            val r = AutoProducer.genTexts(a, n, ctx)
            ui.post {
                if (::tvAuto.isInitialized) { tvAuto.setTextColor(a.OK); tvAuto.text = r }
                renderOverlays(); renderProjects(); then?.invoke()
            }
        }.start()
    }

    /** Monta o plano: cada vídeo cru recebe textos diferentes; modelo e legenda sorteados pelo que bomba. */

    private fun produceNow(n: Int, only0: String? = Store.prefs(a).getString("auto_ctx", null)) {
        if (VideoExport.busy() || AutoProducer.running) { a.toast("Já tem uma produção ou exportação rodando"); return }
        if (Projects.list(a).isEmpty()) { a.toast("Primeiro adicione vídeos crus em 🗂 Categorias (os do Flow entram sozinhos)"); showSection(1); return }
        val only = only0
        if (only != null && Projects.list(a).none { it.optString("ctx") == only }) { a.toast("Nenhum vídeo cru em ${Contexts.label(a, only)}"); return }
        val cats = Projects.list(a).map { it.optString("ctx") }.distinct().filter { (only == null || it == only) && MusicPick.mode(a, it) == MusicPick.ASK }
        askMusics(cats) { ans -> AutoProducer.asked = ans; produceGo(n, only) }
    }

    private fun busy(t: String, color: Int) {
        tvAuto.setTextColor(color); tvAuto.text = t
        tvBusy.setTextColor(color); tvBusy.text = t; tvBusy.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun produceGo(n: Int, only: String?) {
        stopPlay(); btnAuto.isEnabled = false
        a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        busy("🤖 Preparando…", a.MUTED)
        AutoProducer.run(a, n, only, { t -> busy("🤖 $t", a.MUTED) }) { ok ->
            a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnAuto.isEnabled = true
            busy(if (ok < 0) "Já tinha uma produção rodando" else if (ok == 0) "Nada pra produzir agora (tudo já editado, ou faltam textos)" else "✓ $ok vídeo(s) novo(s) na 🎬 Fila", a.OK)
            ui.postDelayed({ if (!AutoProducer.running) { tvBusy.visibility = View.GONE } }, 8000)
            renderLibrary(); renderProjects(); renderOverlays()
            QuickPost.updateWidgets(a)
            if (ok > 0) { a.toast("🤖 $ok vídeos prontos"); showSection(2) }
        }
    }


    /** "Manter sempre N prontos": ao abrir o app, repõe o que falta. */
    /** "Manter sempre N prontos": repõe em segundo plano. */
    fun maybeRefill() { AutoProducer.refillIfNeeded(a) }

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
            tvMany.setTextColor(a.OK); tvMany.text = "$ok de ${ids.size} vídeos prontos, com legenda sorteada."
            renderLibrary(); renderProjects(); showSection(2)
            a.toast("$ok vídeos prontos pra postar"); return
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
            if (photo) 0 else p.optLong("textStart"), if (photo) 0 else p.optLong("textEnd"), if (photo || m != null) 0 else p.optLong("musicEnd"), VideoExport.cropOf(p))
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
            VideoExport.putCrop(it, crop)
        }
        base?.let { b -> Projects.saveThumb(a, id, VideoExport.preview(pv(b), etText.text.toString(), style)) }
        ui.removeCallbacks(projRefresh); ui.postDelayed(projRefresh, 800)
    }
    private val projRefresh = Runnable { renderProjects() }

    private fun newProject() {
        saveNow()
        projId = null; src = null; base = null; isPhoto = false; music = null; mute = false; lastExportId = null; curModel = ""
        cutStart = 0; cutEnd = 0; durMs = 0; musicStart = 0; cutRow.visibility = View.GONE; crop = VideoExport.CropSpec()
        textStart = 0; textEnd = 0; musicEnd = 0; timeline.thumbs = emptyList(); syncTimeline()
        etText.setText(""); etCap.setText(""); durRow.visibility = View.GONE
        tvSrc.setTextColor(a.MUTED); tvSrc.text = "Escolha um vídeo ou uma foto pra começar."
        updateMusicLabel(); showImagePreview(); drawPreview(); renderProjects(); updateEmpty()
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
        crop = VideoExport.cropOf(o)
        timeline.thumbs = emptyList()
        seek.progress = style.size - 20
        redraws.forEach { it() }
        durRow.visibility = if (photo) View.VISIBLE else View.GONE
        updateMusicLabel()
        projId = id
        loadBase(if (photo) "Projeto de foto aberto" else "Projeto de vídeo aberto")
        showSection(0)
        a.toast("Esboço aberto: mude texto e música e exporte")
    }

    // ------------------------------------------------------------------ 🗂 categorias

    private lateinit var catHead: LinearLayout
    private lateinit var catBox: LinearLayout
    private var openCat: String? = null       // null = lista; "" = sem categoria; nome = categoria aberta
    private var pendingCat: String? = null    // vídeo adicionado de dentro de uma categoria já entra nela

    private fun thumbOf(pid: String) = Projects.thumb(a, pid).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    private fun renderProjects() {
        if (!::catBox.isInitialized) return
        updateNav()
        catBox.removeAllViews()
        if (openCat == null) renderCatList() else renderCatDetail(openCat!!)
    }

    private fun catCard(): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
        background = GradientDrawable().apply { setColor(a.CARD); cornerRadius = a.dp(14).toFloat(); setStroke(a.dp(1), a.LINE) }
    }.also { catBox.addView(it, a.lpMatch().apply { topMargin = a.dp(12) }) }

    private val expanded = mutableSetOf<String>()

    /** Quantos vídeos um "Produzir" desta categoria faz (sem texto: só o que falta editar). */
    private fun catTodo(n: String): Int {
        val crus = Projects.list(a).filter { it.optString("ctx") == n }
        if (crus.isEmpty()) return 0
        if (!Contexts.noText(a, n)) return Store.prefs(a).getInt("auto_n", 5)
        val ids = crus.map { it.optString("id") }.toSet()
        return maxOf(0, crus.size * Contexts.versions(a, n) - VideoExport.library(a).count { it.optString("proj") in ids })
    }

    private fun catSummary(n: String): String {
        if (n.isEmpty()) return "Textos gerais servem pra qualquer vídeo"
        val parts = ArrayList<String>()
        parts += if (Contexts.noText(a, n)) "🎬 sem texto" else "T ${OverlayDB.count(a, n)} textos"
        Contexts.seconds(a, n).takeIf { it > 0 }?.let { parts += "⏱ $it s" }
        parts += "♪ " + MusicPick.label(MusicPick.mode(a, n)).substringAfter(' ').lowercase()
        if (Contexts.noCaption(a, n)) parts += "sem legenda"
        return parts.joinToString("  ·  ")
    }

    private fun renderCatList() {
        val projs = Projects.list(a)
        val lib = VideoExport.library(a)
        // 👉 o que fazer agora
        val todo = catCard()
        a.text("👉 O que fazer agora", 15f, a.FG, parent = todo, bold = true)
        var tips = 0
        val loose = projs.count { it.optString("ctx").isEmpty() }
        if (loose > 0 && Contexts.list(a).isNotEmpty()) {
            tips++; a.text("📥 $loose vídeo(s) cru(s) sem categoria", 13f, a.FG, parent = todo)
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }; todo.addView(r, a.lpMatch())
            a.button("✨ Separar com IA", r, weight = true) { classifyRaw() }
            a.button("Abrir e mover", r, weight = true) { openCat = ""; renderProjects() }
        }
        Contexts.names(a).forEach { n ->
            val nc = projs.count { it.optString("ctx") == n }
            if (nc == 0) return@forEach
            if (Contexts.noText(a, n)) {
                val left = catTodo(n)
                if (left > 0) { tips++; a.text("${Contexts.label(a, n)}: $left vídeo(s) esperando edição", 13f, a.FG, parent = todo)
                    a.button("🎬 Editar e pôr na fila", todo, primary = true) { produceNow(left, n) } }
            } else if (OverlayDB.count(a, n) + OverlayDB.count(a, "") < 3) {
                tips++; a.text("${Contexts.label(a, n)}: poucos textos (${OverlayDB.count(a, n)}). Escreva alguns do seu jeito.", 13f, a.FG, parent = todo)
                a.button("Escrever textos", todo) { openCat = n; renderProjects() }
            }
        }
        val waiting = lib.count { it.optInt("posts") == 0 }
        val need = Daily.remaining(a) - waiting
        if (need > 0 && projs.isNotEmpty()) {
            tips++; a.text("🎯 Faltam $need vídeo(s) na fila pra meta de hoje", 13f, a.FG, parent = todo)
            a.button("🤖 Produzir $need", todo) { produceNow(need, null) }
        }
        if (projs.isEmpty()) { tips++; a.text("Comece adicionando vídeos crus: toque em ＋＋ Adicionar vários vídeos crus.", 13f, a.FG, parent = todo) }
        if (tips == 0) a.text("✓ Tudo em dia. A fila tem $waiting vídeo(s).", 13f, a.OK, parent = todo)

        // categorias
        val names = Contexts.names(a) + listOf("")
        names.forEach { n ->
            val crus = projs.filter { it.optString("ctx") == n }
            if (n.isEmpty() && crus.isEmpty() && OverlayDB.count(a, "") == 0) return@forEach
            val ids = crus.map { it.optString("id") }.toSet()
            val inQueue = lib.count { it.optString("proj") in ids && it.optInt("posts") == 0 }
            val card = catCard()
            card.setOnClickListener { openCat = n; renderProjects(); a.scrollTo(catHead) }
            a.text(if (n.isEmpty()) "📥  Sem categoria" else Contexts.label(a, n), 17f, a.FG, parent = card, bold = true)
            a.text("${crus.size} cru(s)  ·  $inQueue na fila", 13f, a.FG, parent = card)
            a.text(catSummary(n), 12f, a.MUTED, parent = card)
            if (crus.isNotEmpty()) {
                val strip = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                card.addView(strip, a.lpMatch().apply { topMargin = a.dp(8) })
                crus.take(6).forEach { p ->
                    strip.addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1C1C24.toInt()); thumbOf(p.optString("id"))?.let { setImageBitmap(it) } },
                        LinearLayout.LayoutParams(a.dp(40), a.dp(70)).apply { rightMargin = a.dp(4) })
                }
                if (crus.size > 6) strip.addView(TextView(a).apply { text = "+${crus.size - 6}"; setTextColor(a.MUTED); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(a.dp(40), a.dp(70)))
            }
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            card.addView(r, a.lpMatch())
            a.button("Abrir ›", r, weight = true) { openCat = n; renderProjects(); a.scrollTo(catHead) }
            if (n.isNotEmpty() && crus.isNotEmpty()) {
                val k = catTodo(n)
                if (k > 0) a.button(if (Contexts.noText(a, n)) "🎬 Editar $k" else "🤖 Produzir $k", r, primary = true, weight = true) { produceNow(k, n) }
            }
        }
        if (Contexts.list(a).isEmpty()) a.text("Nenhuma categoria ainda. Crie uma (ex.: \"Genérico\" pra vídeos sem texto, \"Falando\" pra vídeos com texto) ou toque em ✨ Separar vídeos com IA.", 13f, a.MUTED, parent = catBox)
    }

    /** Cartão de passo numerado: "② Música   ✓ Automático". */
    private fun stepCard(num: String, title: String, status: String, ok: Boolean): LinearLayout {
        val c = catCard()
        val h = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        c.addView(h, a.lpMatch())
        h.addView(TextView(a).apply { text = "$num  $title"; textSize = 16f; setTextColor(a.FG); typeface = android.graphics.Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        h.addView(TextView(a).apply { text = (if (ok) "✓ " else "• ") + status; textSize = 12f; setTextColor(if (ok) a.OK else a.ACCENT) })
        return c
    }

    private fun moreBtn(parent: LinearLayout, key: String, total: Int, shown: Int) {
        if (total <= shown && key !in expanded) return
        a.button(if (key in expanded) "Mostrar menos" else "Ver todos ($total)", parent) {
            if (!expanded.add(key)) expanded.remove(key); renderProjects()
        }
    }

    private fun renderCatDetail(n: String) {
        val key = n
        // topo
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        catBox.addView(top, a.lpMatch().apply { topMargin = a.dp(12) })
        top.addView(android.widget.Button(a).apply {
            text = "◀"; textSize = 16f; setTextColor(a.FG)
            background = GradientDrawable().apply { setColor(0xFF1C1C24.toInt()); cornerRadius = a.dp(10).toFloat() }
            setOnClickListener { openCat = null; renderProjects() }
        }, LinearLayout.LayoutParams(a.dp(52), a.dp(44)).apply { rightMargin = a.dp(10) })
        top.addView(TextView(a).apply { text = if (n.isEmpty()) "📥 Sem categoria" else Contexts.label(a, n); textSize = 20f; setTextColor(a.FG); typeface = android.graphics.Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (n.isNotEmpty()) {
            small(top, "✏") {
                val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(20), a.dp(8), a.dp(20), 0) }
                val en = EditText(a).apply { setText(n); setSingleLine(true) }
                val ed = EditText(a).apply { setText(Contexts.desc(a, n)); hint = "Como são esses vídeos (ajuda a IA)" }
                box.addView(en); box.addView(ed)
                AlertDialog.Builder(a).setTitle("Editar categoria").setView(box)
                    .setPositiveButton("Salvar") { _, _ ->
                        Contexts.update(a, n) { it.put("desc", ed.text.toString().trim()) }
                        val nn = en.text.toString().trim()
                        if (nn.isNotEmpty() && nn != n) { Contexts.rename(a, n, nn)?.let { openCat = it } }
                        renderProjects(); renderCtx()
                    }.setNegativeButton("Cancelar", null).show()
            }
            small(top, "🗑") {
                AlertDialog.Builder(a).setTitle("Apagar a categoria $n?").setMessage("Os vídeos e textos dela vão pra Sem categoria. Nada é apagado.")
                    .setPositiveButton("Apagar") { _, _ -> Contexts.remove(a, n); openCat = null; renderProjects(); renderCtx() }
                    .setNegativeButton("Cancelar", null).show()
            }
            Contexts.desc(a, n).takeIf { it.isNotBlank() }?.let { a.text(it, 12f, a.MUTED, parent = catBox) }
        } else a.text("Vídeos que ainda não estão em nenhuma categoria e os textos gerais (servem pra qualquer vídeo com texto).", 12f, a.MUTED, parent = catBox)

        val noTxt = Contexts.noText(a, n)
        val crus = Projects.list(a).filter { it.optString("ctx") == n }

        // ① vídeos crus
        val cv = stepCard("①", "Vídeos crus", "${crus.size}", crus.isNotEmpty())
        val ar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        cv.addView(ar, a.lpMatch())
        a.button("＋＋ Vários de uma vez", ar, primary = true, weight = true) { chooseRawMany(n) }
        a.button("＋ 1 vídeo", ar, weight = true) { pendingCat = n; chooseMyVideo() }
        if (crus.isEmpty()) a.text("Nenhum vídeo aqui ainda.", 12f, a.MUTED, parent = cv)
        val ids = crus.map { it.optString("id") }
        val libAll = VideoExport.library(a)
        val showN = if ("crus:$n" in expanded) crus.size else 4
        crus.take(showN).forEach { o ->
            val id = o.optString("id")
            val made = libAll.count { it.optString("proj") == id }
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            cv.addView(row, a.lpMatch().apply { topMargin = a.dp(8) })
            row.addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1C1C24.toInt()); thumbOf(id)?.let { setImageBitmap(it) }; setOnClickListener { openProject(id) } },
                LinearLayout.LayoutParams(a.dp(46), a.dp(82)).apply { rightMargin = a.dp(10) })
            val side = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            row.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val nt = OverlayDB.count(a, AutoProducer.vidKey(id))
            a.text(o.optString("desc").ifBlank { if (o.optBoolean("flow")) "Vídeo do Flow" else "Vídeo cru" }.take(50), 13f, a.FG, parent = side)
            a.text((if (made == 0) "ainda não editado" else "$made versão(ões) feitas") + (if (nt > 0) " · $nt texto(s) só dele" else ""), 11f, if (made == 0) a.ACCENT else a.MUTED, parent = side)
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            side.addView(r, a.lpMatch())
            a.button("✏", r, weight = true) { openProject(id) }
            if (!noTxt) a.button("T", r, weight = true) { videoTexts(id) }
            a.button("⇄", r, weight = true) { chooseCtx(n) { nn -> Projects.update(a, id) { it.put("ctx", nn) }; renderProjects(); renderCtx() } }
            val del = a.button("🗑", r, weight = true) { }
            del.setOnClickListener {
                if (del.tag != "armed") { del.tag = "armed"; del.text = "?"; ui.postDelayed({ del.tag = null; del.text = "🗑" }, 2500) }
                else { if (projId == id) newProject(); Projects.remove(a, id); renderProjects() }
            }
        }
        moreBtn(cv, "crus:$n", crus.size, 4)

        // ② texto no vídeo
        val ct = stepCard("②", "Texto no vídeo", if (noTxt) "sem texto" else "${OverlayDB.count(a, key)} textos", noTxt || OverlayDB.count(a, key) + OverlayDB.count(a, "") >= 3)
        if (n.isNotEmpty()) a.chips(ct, listOf("T Com texto", "🎬 Sem texto (só edita)"), { if (Contexts.noText(a, n)) "🎬 Sem texto (só edita)" else "T Com texto" }) { o ->
            Contexts.update(a, n) { it.put("semtexto", o.startsWith("🎬")) }; renderProjects()
        }
        if (noTxt) a.text("O vídeo já está pronto: sem texto e sem gastar IA. O app só edita: música, qualidade, tira os metadados e, em cada versão, usa um trecho diferente.", 12f, a.MUTED, parent = ct)
        else catTexts(ct, n, key)

        if (n.isNotEmpty()) {
            // ③ música
            val mode = MusicPick.mode(a, n)
            val all = VideoExport.musics(a)
            val needsMusic = mode == MusicPick.AUTO || mode == MusicPick.ASK || mode == MusicPick.FIXED
            val cmu = stepCard("③", "Música", MusicPick.label(mode).substringAfter(' '), !needsMusic || all.isNotEmpty())
            a.chips(cmu, MusicPick.MODES.map { it.second }, { MusicPick.label(MusicPick.mode(a, n)) }) { o ->
                val m = MusicPick.MODES.first { it.second == o }.first
                Contexts.update(a, n) { it.put("musica_modo", m) }
                if (m == MusicPick.FIXED && MusicPick.fixed(a, n) == null) pickFixedMusic(n) else renderProjects()
            }
            when (mode) {
                MusicPick.AUTO -> {
                    val ch = MusicPick.chosen(a, n)
                    a.text("Sorteia uma por vídeo, sem repetir em seguida, e usa mais as que bombam (🔥 na fila). Entre: " +
                        (if (ch.isEmpty()) "todas as suas músicas (${all.size})" else "${ch.size} escolhida(s)"), 12f, a.MUTED, parent = cmu)
                    a.button("Escolher quais músicas…", cmu) { chooseMusics(n) }
                    musicList(cmu, if (ch.isEmpty()) all.take(5) else ch.map { File(it) })
                }
                MusicPick.ASK -> a.text("Quando você tocar em Produzir, eu pergunto qual música usar (ou sorteio). Se o app produzir sozinho em segundo plano, ele sorteia.", 12f, a.MUTED, parent = cmu)
                MusicPick.FIXED -> {
                    val f = MusicPick.fixed(a, n)
                    a.button("♪ " + (f?.let { VideoExport.nice(it) } ?: "Escolher a música…"), cmu) { pickFixedMusic(n) }
                }
                MusicPick.ORIG -> a.text("Usa o som do próprio vídeo.", 12f, a.MUTED, parent = cmu)
                else -> a.text("Usa a música salva no modelo de edição sorteado (★ em Editar).", 12f, a.MUTED, parent = cmu)
            }
            if (needsMusic && all.isEmpty()) {
                a.text("⚠ Você ainda não tem músicas salvas.", 12f, a.ACCENT, parent = cmu)
                a.button("Ir pra 🎵 Músicas", cmu) { showSection(3) }
            }

            // ④ postagem
            val secN = Contexts.seconds(a, n)
            val cp = stepCard("④", "Tempo e postagem", (if (secN > 0) "$secN s · " else "") + if (Contexts.noCaption(a, n)) "sem legenda" else "legenda sorteada", true)
            a.text("⏱ Tempo de cada vídeo", 12f, a.MUTED, parent = cp)
            val opts = listOf("Original", "5 s", "7 s", "10 s", "15 s", "Outro…")
            a.chips(cp, opts, { val v = Contexts.seconds(a, n); when { v == 0 -> "Original"; "$v s" in opts -> "$v s"; else -> "Outro…" } }) { o ->
                when (o) {
                    "Original" -> { Contexts.update(a, n) { it.put("duracao", 0) }; renderProjects() }
                    "Outro…" -> {
                        val et = EditText(a).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "segundos (ex.: 6)"; Contexts.seconds(a, n).takeIf { it > 0 }?.let { setText(it.toString()) } }
                        AlertDialog.Builder(a).setTitle("Quantos segundos?").setView(et)
                            .setPositiveButton("OK") { _, _ -> et.text.toString().toIntOrNull()?.let { v -> Contexts.update(a, n) { it.put("duracao", v.coerceIn(1, 90)) } }; renderProjects() }
                            .setNegativeButton("Cancelar") { _, _ -> renderProjects() }.show()
                    }
                    else -> { Contexts.update(a, n) { it.put("duracao", o.substringBefore(' ').toInt()) }; renderProjects() }
                }
            }
            if (secN > 0) a.text("Vídeos maiores são cortados em $secN s (a 1ª versão pega o começo, as outras outro trecho). Vídeos menores ficam como estão.", 11f, a.MUTED, parent = cp)
            if (noTxt) {
                a.text("Versões de cada vídeo cru (cada uma com um trecho diferente)", 12f, a.MUTED, parent = cp)
                if (secN == 0 && Contexts.versions(a, n) > 1) a.text("Dica: com um tempo curto (ex.: 5 s) as versões ficam bem diferentes uma da outra.", 11f, a.MUTED, parent = cp)
                a.chips(cp, listOf("1", "2", "3", "5"), { Contexts.versions(a, n).toString() }) { v -> Contexts.update(a, n) { it.put("versoes", v.toInt()) }; renderProjects() }
            }
            a.text("Legenda do post", 12f, a.MUTED, parent = cp)
            a.chips(cp, listOf("Sorteada do banco", "Sem legenda"), { if (Contexts.noCaption(a, n)) "Sem legenda" else "Sorteada do banco" }) { o ->
                Contexts.update(a, n) { it.put("semlegenda", o == "Sem legenda") }; renderProjects()
            }
        }

        // ⑤ produzir
        if (n.isNotEmpty() && crus.isNotEmpty()) {
            val k = catTodo(n)
            val cpr = stepCard("⑤", "Produzir", "${libAll.count { it.optString("proj") in ids && it.optInt("posts") == 0 }} na fila", true)
            if (k <= 0) a.text("✓ Todos os vídeos já foram editados (${Contexts.versions(a, n)} versão(ões) cada). Adicione mais crus ou aumente as versões.", 12f, a.OK, parent = cpr)
            else {
                a.text("Vai fazer $k vídeo(s): " + catSummary(n), 12f, a.MUTED, parent = cpr)
                a.button(if (noTxt) "🎬 Editar $k e pôr na fila" else "🤖 Produzir $k vídeos", cpr, primary = true) { produceNow(k, n) }
            }
        }
    }

    private fun musicList(parent: LinearLayout, files: List<File>) {
        files.forEach { f ->
            val p = MusicPick.pct(a, f.path)
            a.text("♪ " + VideoExport.nice(f) + (p?.let { "  ·  🔥 $it%" } ?: ""), 12f, a.FG, parent = parent)
        }
    }

    private fun chooseMusics(n: String) {
        val all = VideoExport.musics(a)
        if (all.isEmpty()) { a.toast("Salve músicas em 🎵 Músicas primeiro"); showSection(3); return }
        val cur = MusicPick.chosen(a, n).toSet()
        val sel = BooleanArray(all.size) { all[it].path in cur }
        AlertDialog.Builder(a).setTitle("Músicas de $n (nenhuma marcada = todas)")
            .setMultiChoiceItems(all.map { f -> VideoExport.nice(f) + (MusicPick.pct(a, f.path)?.let { " · $it%" } ?: "") }.toTypedArray(), sel) { _, i, on -> sel[i] = on }
            .setPositiveButton("Salvar") { _, _ -> MusicPick.setChosen(a, n, all.filterIndexed { i, _ -> sel[i] }.map { it.path }); renderProjects() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun pickFixedMusic(n: String) {
        val all = VideoExport.musics(a)
        if (all.isEmpty()) { a.toast("Salve músicas em 🎵 Músicas primeiro"); renderProjects(); return }
        AlertDialog.Builder(a).setTitle("Sempre esta música em $n").setItems(all.map { VideoExport.nice(it) }.toTypedArray()) { _, i ->
            Contexts.update(a, n) { it.put("musica_fixa", all[i].path) }; renderProjects()
        }.setOnCancelListener { renderProjects() }.show()
    }

    /** "Me perguntar": pergunta a música de cada categoria envolvida, depois produz. */
    private fun askMusics(cats: List<String>, acc: Map<String, String> = emptyMap(), then: (Map<String, String>) -> Unit) {
        if (cats.isEmpty()) { then(acc); return }
        val n = cats.first()
        val all = VideoExport.musics(a)
        val labels = listOf("🎲 Sortear das minhas músicas", "🔊 Som original do vídeo") + all.map { f -> "♪ " + VideoExport.nice(f) + (MusicPick.pct(a, f.path)?.let { " · 🔥 $it%" } ?: "") }
        AlertDialog.Builder(a).setTitle("Qual música pra ${Contexts.label(a, n)}?").setItems(labels.toTypedArray()) { _, i ->
            val v = when (i) { 0 -> "*"; 1 -> ""; else -> all[i - 2].path }
            askMusics(cats.drop(1), acc + (n to v), then)
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun catTexts(ct: LinearLayout, n: String, key: String) {
        a.text("Escreva do seu jeito: a IA aprende com os seus textos e gera parecidos. Enter = nova linha.", 12f, a.MUTED, parent = ct)
        val et = EditText(a).apply {
            hint = "Escreva um texto pro vídeo…"; setTextColor(a.FG); setHintTextColor(a.MUTED); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2; gravity = Gravity.TOP or Gravity.START
            background = GradientDrawable().apply { setColor(a.BG); cornerRadius = a.dp(10).toFloat(); setStroke(a.dp(1), a.LINE) }
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
        }
        ct.addView(et, a.lpMatch())
        val tr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        ct.addView(tr, a.lpMatch())
        val st = TextView(a).apply { textSize = 12f; setTextColor(a.OK) }
        a.button("Salvar", tr, weight = true) {
            val t = et.text.toString().trim(); if (t.isNotEmpty()) { OverlayDB.add(a, t, "meu", key); renderProjects() }
        }
        a.button("✨ Gerar 10 parecidos com os meus", tr, primary = true, weight = true) {
            st.setTextColor(a.MUTED); st.text = "A IA está aprendendo com os seus textos…"
            Thread { val r = AutoProducer.genTexts(a, 10, key); ui.post { st.setTextColor(a.OK); st.text = r; renderProjects() } }.start()
        }
        ct.addView(st)
        val l = OverlayDB.all(a, key).sortedWith(compareByDescending<org.json.JSONObject> { if (it.optString("src") == "meu") 1 else 0 }.thenByDescending { OverlayDB.mean(it) })
        if (l.isEmpty()) a.text("Nenhum texto ainda.", 12f, a.MUTED, parent = ct)
        l.take(if ("txt:$key" in expanded) l.size else 6).forEach { o ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            ct.addView(row, a.lpMatch().apply { topMargin = a.dp(6) })
            val nr = OverlayDB.rated(o)
            val who = if (o.optString("src") == "meu") "✍ meu" else "✨ IA"
            row.addView(TextView(a).apply {
                text = o.optString("t") + "\n" + who + (if (nr > 0) " · ${(OverlayDB.mean(o) * 100).toInt()}% · 🔥${o.optInt("good")} 👎${o.optInt("bad")}" else "") + " · usado ${o.optInt("uses")}x"
                textSize = 13f; setTextColor(a.FG)
                setOnLongClickListener { chooseCtx(key) { nn -> OverlayDB.setCtx(a, o.optString("t"), nn); renderProjects() }; true }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            small(row, "✕") { OverlayDB.remove(a, o.optString("t")); renderProjects() }
        }
        moreBtn(ct, "txt:$key", l.size, 6)
        a.text("Segure um texto pra mudar de categoria.", 11f, a.MUTED, parent = ct)
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
        preview.setImageBitmap(VideoExport.preview(pv(b), txt, style))
    }

    private lateinit var tvCrop: TextView
    private lateinit var tvRm: TextView
    private lateinit var btnRm: Button
    private lateinit var btnUndoRm: Button

    private fun afterCrop() {
        redraws.forEach { it() }
        if (editMode) showImagePreview() else drawPreview()
        updateCropUi(); scheduleSave()
    }

    private fun updateCropUi() {
        if (!::tvCrop.isInitialized) return
        tvCrop.text = if (!crop.active) "Sem recorte." else "Recorte ativo" + (if (crop.zoom > 1.01f) " · zoom ${"%.1f".format(crop.zoom)}x" else "") + (if (crop.fill) " · preenchendo 9:16" else "") +
            ". A prévia parada mostra o resultado; tocando ▶ o vídeo aparece inteiro."
        val orig = projId?.let { File(Projects.srcFile(a, it, true).path + ".orig") }
        tvRm.text = if (isPhoto) "Escreva o que tirar da foto (ex.: \"a garrafa na mesa\", \"a pessoa no fundo\", \"o texto na parede\"). A IA apaga e preenche o fundo; o resto fica igual." +
            (if (Ai.provider(a) != "gemini") "\n⚠ Precisa da IA do Google (Gemini) nos Ajustes: a do Claude não edita imagem." else "")
            else "Em vídeo a IA não consegue apagar quadro a quadro aqui no celular. Use ⛶ Recortar pra tirar o que está na borda, ou refaça o vídeo no Flow pedindo sem o objeto. Em fotos funciona."
        btnRm.isEnabled = isPhoto; btnUndoRm.visibility = if (isPhoto && orig?.exists() == true) View.VISIBLE else View.GONE
    }

    private fun removeObjectDialog() {
        val id = projId ?: run { a.toast("Abra uma foto primeiro"); return }
        if (!isPhoto) return
        val et = EditText(a).apply { hint = "Ex.: a garrafa em cima da mesa" }
        AlertDialog.Builder(a).setTitle("O que remover da foto?").setView(et)
            .setPositiveButton("🧽 Remover") { _, _ ->
                val what = et.text.toString().trim(); if (what.isEmpty()) return@setPositiveButton
                val f = Projects.srcFile(a, id, true)
                btnRm.isEnabled = false; tvRm.setTextColor(a.MUTED); tvRm.text = "A IA está removendo \"$what\"…"
                Thread {
                    val msg = try {
                        val bytes = Ai.editImage(a, f, "Remove $what from this photo. Fill the area naturally so it looks like it was never there. Keep everything else exactly the same: the person, face, body, pose, clothes, lighting, colors, framing and resolution.")
                        val orig = File(f.path + ".orig"); if (!orig.exists()) f.copyTo(orig)
                        f.writeBytes(bytes); null
                    } catch (e: Exception) { e.message ?: "erro" }
                    ui.post {
                        btnRm.isEnabled = true
                        if (msg == null) { tvRm.setTextColor(a.OK); loadBase("Foto sem \"$what\""); ui.postDelayed({ updateCropUi(); tvRm.setTextColor(a.OK); tvRm.text = "✓ Removido. Não gostou? ↺ Voltar a original." }, 600) }
                        else { tvRm.setTextColor(a.ACCENT); tvRm.text = "⚠ $msg" }
                    }
                }.start()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun undoRemove() {
        val id = projId ?: return
        val f = Projects.srcFile(a, id, true); val orig = File(f.path + ".orig")
        if (!orig.exists()) return
        orig.copyTo(f, true); orig.delete()
        loadBase("Foto original de volta"); ui.postDelayed({ updateCropUi() }, 300)
    }

    /** Quadro da prévia já com recorte/zoom, numa tela 9:16 igual ao exportado. */
    private fun pv(b: Bitmap) = VideoExport.fit916(VideoExport.cropBitmap(b, crop))

    private fun showImagePreview() {
        editMode = false; bgmStop(); ui.removeCallbacks(loopTick); mp = null
        if (::textLayer.isInitialized) { textLayer.visibility = View.GONE; playRow.visibility = View.GONE }
        if (::timeline.isInitialized) { timeline.visibility = View.GONE; tlHint.visibility = View.GONE }
        video.stopPlayback(); video.visibility = View.GONE; preview.visibility = View.VISIBLE
        afterBox.visibility = View.GONE
    }

    // ------------------------------------------------------------------ escolher arquivos

    /** Pergunta onde procurar: Galeria (fotos e vídeos do celular) ou Arquivos (pastas). */
    /** Vídeos já editados: Flow, galeria ou arquivos (vários) → direto na fila, sem metadados. */
    private fun importReady() {
        if (VideoExport.busy() || AutoProducer.running) { a.toast("Espere terminar o que está rodando"); return }
        AlertDialog.Builder(a).setTitle("Adicionar vídeos prontos")
            .setItems(arrayOf("🖼  Galeria (marque vários)", "📁  Arquivos (segure pra marcar vários)")) { _, i ->
                if (i == 0) openGallery("video/*", REQ_IMPORT, true) else openFiles("video/*", REQ_IMPORT, true)
            }.show()
    }

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
        if (req != REQ_VIDEO && req != REQ_PHOTO && req != REQ_MUSIC && req != REQ_MULTI && req != REQ_IMPORT && req != REQ_RAW_MANY) return false
        if (req == REQ_RAW_MANY) {
            val cat = pendingCat; pendingCat = null
            if (res != android.app.Activity.RESULT_OK || data == null) return true
            val uris = ArrayList<Uri>()
            data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) addRawMany(uris, cat)
            return true
        }
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
                        pendingCat?.let { cx -> Projects.update(a, pid) { it.put("ctx", cx) } }; pendingCat = null
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
        updateEmpty(); if (curSec != 0) showSection(0)
        showImagePreview()
        tvSrc.text = label; tvSrc.setTextColor(a.OK)
        cutRow.visibility = if (isPhoto) View.GONE else View.VISIBLE
        Thread {
            val b = VideoExport.baseFrame(a, s, isPhoto)
            val d = if (isPhoto) 0L else VideoExport.durationMs(a, s)
            ui.post {
                base = b; durMs = d
                updateCropUi()
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
                cutStart = 0; cutEnd = 0; crop = VideoExport.CropSpec()
                pendingCat?.let { cx -> Projects.update(a, pid) { it.put("ctx", cx) } }; pendingCat = null
                loadBase("Vídeo salvo no projeto"); saveNow(); renderProjects()
            }
        }.start()
    }

    /** Vários vídeos crus de uma vez: cada um vira um vídeo cru (na categoria escolhida), sem abrir o editor. */
    private fun chooseRawMany(cat: String?) {
        AlertDialog.Builder(a).setTitle("Adicionar vários vídeos crus")
            .setItems(arrayOf("🖼  Galeria (marque vários)", "📁  Arquivos (segure pra marcar vários)")) { _, i ->
                pendingCat = cat
                if (i == 0) openGallery("video/*", REQ_RAW_MANY, true) else openFiles("video/*", REQ_RAW_MANY, true)
            }.show()
    }

    private fun addRawMany(uris: List<Uri>, cat: String?) {
        tvRaw.setTextColor(a.MUTED); tvRaw.text = "Adicionando ${uris.size} vídeo(s)…"
        a.scrollTo(catHead)
        Thread {
            var ok = 0
            uris.forEachIndexed { i, u ->
                ui.post { tvRaw.text = "Adicionando ${i + 1} de ${uris.size}…" }
                try { Thread.sleep(5) } catch (e: Exception) { } // ids diferentes
                val pid = Projects.create(a, false)
                val f = Projects.srcFile(a, pid, false)
                val good = try { a.contentResolver.openInputStream(u)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }; f.length() > 0 } catch (e: Exception) { false }
                if (!good) { Projects.remove(a, pid); return@forEachIndexed }
                VideoExport.baseFrame(a, Uri.fromFile(f), false)?.let { Projects.saveThumb(a, pid, it) }
                if (cat != null) Projects.update(a, pid) { it.put("ctx", cat) }
                ok++
            }
            ui.post {
                tvRaw.setTextColor(a.OK)
                tvRaw.text = "✓ $ok de ${uris.size} vídeo(s) cru(s) adicionados" + (cat?.let { " em " + (if (it.isEmpty()) "Sem categoria" else Contexts.label(a, it)) } ?: "") +
                    if (cat == null && Contexts.list(a).isNotEmpty()) ". Toque em ✨ Separar vídeos com IA ou mova com ⇄." else ""
                renderProjects()
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
        updateNav()
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
        btnExport.isEnabled = false; btnExpPost.isEnabled = false
        tvProg.setTextColor(a.MUTED); tvProg.text = "Exportando ${quality()}…"
        a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val job = VideoExport.Job(s, isPhoto, photoSecs, etText.text.toString(), style.copy(), music, mute, quality(),
            if (isPhoto) 0 else cutStart, if (isPhoto || cutEnd >= durMs) 0 else cutEnd, musicStart,
            if (isPhoto) 0 else textStart, if (isPhoto) 0 else textEnd, if (isPhoto) 0 else musicEnd, crop)
        val qn = quality()
        VideoExport.export(a, job, { p -> tvProg.text = "Exportando $qn… $p%" }) { f, err ->
            a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnExport.isEnabled = true; btnExpPost.isEnabled = true
            if (f == null) { tvProg.setTextColor(a.ACCENT); tvProg.text = "Não deu: $err"; return@export }
            tvProg.text = "Salvando…"
            Thread {
                val id = VideoExport.addToLibrary(a, f, etCap.text.toString().trim())
                val txt0 = etText.text.toString().trim()
                val cx0 = projId?.let { Projects.get(a, it)?.optString("ctx") }.orEmpty()
                VideoExport.updateLib(a, id) { it.put("texto", txt0); it.put("modelo", curModel); it.put("ctx", cx0) }
                if (txt0.isNotEmpty()) { OverlayDB.add(a, txt0, "meu", cx0); OverlayDB.used(a, txt0, cx0) }
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
                    if (postAfter) { postAfter = false; post(id, etCap.text.toString()) }
                }
            }.start()
        }
    }

    private fun post(id: String, caption: String) {
        val cap = caption.trim()
        if (cap.isNotEmpty()) {
            ClipSync.copy(a, cap)
            Captions.addMine(a, cap)
        }
        // posta direto do app: nada é baixado nem copiado pra galeria
        val f = File(VideoExport.videoDir(a), "$id.mp4")
        if (!f.exists()) { a.toast("Esse vídeo não está mais no app"); return }
        VideoExport.updateLib(a, id) { it.put("legenda", cap) }
        PostConfirm.start(a, "video", id, cap, "Instagram") // só conta como postado quando você confirmar
        renderLibrary()
        a.toast(if (cap.isEmpty()) "Abrindo o Instagram" else "Legenda copiada: no Instagram é só colar e postar")
        a.startActivity(VideoExport.shareToInstagram(a, PostQueue.uri(id).buildUpon().path("/$id.mp4").build(), "video/mp4"))
    }

    private fun sub(title: String): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
        background = GradientDrawable().apply { setColor(a.BG); cornerRadius = a.dp(12).toFloat(); setStroke(a.dp(1), a.LINE) }
    }.also { libBox.addView(it, a.lpMatch().apply { topMargin = a.dp(10) }); a.text(title, 15f, a.FG, parent = it, bold = true) }

    private fun thumbV(id: String, w: Int, h: Int) = ImageView(a).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1C1C24.toInt())
        File(VideoExport.videoDir(a), "$id.jpg").takeIf { it.exists() }?.let { setImageBitmap(BitmapFactory.decodeFile(it.path)) }
    }.also { it.layoutParams = LinearLayout.LayoutParams(a.dp(w), a.dp(h)).apply { rightMargin = a.dp(8) } }

    private fun renderLibrary() {
        updateCycle(); updateNav(); updateDaily()
        libBox.removeAllViews()
        val all = VideoExport.library(a)
        if (all.isEmpty()) { a.text("Nenhum vídeo pronto ainda. Toque em 🤖 Produzir mais ou 📥 Adicionar vídeos prontos.", 12f, a.MUTED, parent = libBox); return }
        val tag = groupTags(all)
        val posted = all.filter { it.optInt("posts") > 0 }
        val rated = posted.filter { Learn.rated(it) > 0 }
        val waitNota = posted.filter { it.optLong("notaAt") != it.optLong("lastPost") || it.optString("nota").isEmpty() }

        // 📊 resultados
        val rs = sub("📊 Resultados dos vídeos")
        a.text("💎 ${rated.count { Learn.verdict(it) == "top" }} viralizaram  ·  🔥 ${rated.count { Learn.verdict(it) == "good" }} bombaram  ·  😐 ${rated.count { Learn.verdict(it) == "meh" }}  ·  👎 ${rated.count { Learn.verdict(it) == "bad" }} floparam  ·  ⏳ ${waitNota.size} sem nota", 13f, a.FG, parent = rs)
        if (waitNota.isNotEmpty()) {
            a.text("⭐ Como foram? (1 toque ensina texto, música, edição, legenda e o vídeo cru)", 12f, a.MUTED, parent = rs)
            waitNota.sortedByDescending { it.optLong("lastPost") }.take(4).forEach { o ->
                val id = o.optString("id")
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                rs.addView(row, a.lpMatch().apply { topMargin = a.dp(6) })
                row.addView(thumbV(id, 34, 60))
                val side = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                row.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                a.text("${tag[VideoExport.origin(o)] ?: ""} · " + o.optString("texto").replace('\n', ' ').ifBlank { "sem texto" }.take(40), 12f, a.FG, parent = side)
                val rr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                side.addView(rr, a.lpMatch())
                listOf("top" to "💎", "good" to "🔥", "meh" to "😐", "bad" to "👎").forEach { (k, e) ->
                    a.button(e, rr, weight = true) { RateLater.apply(a, id, k); renderLibrary(); renderStats() }
                }
            }
        }
        // 🏆 o que deu certo / 👎 o que não deu
        if (rated.isNotEmpty()) {
            fun <K> best(key: (org.json.JSONObject) -> K?, worst: Boolean): Pair<K, Int>? = rated.groupBy(key).filterKeys { it != null && it.toString().isNotBlank() }
                .mapValues { (_, v) -> (v.map { Learn.mean(it) }.average() * 100).toInt() }.entries
                .let { if (worst) it.minByOrNull { e -> e.value }?.takeIf { e -> e.value < 45 } else it.maxByOrNull { e -> e.value }?.takeIf { e -> e.value > 55 } }
                ?.let { it.key!! to it.value }
            val good = buildString {
                best({ it.optString("ctx") }, false)?.let { append("\n• Categoria: ${Contexts.label(a, it.first)} (${it.second}%)") }
                best({ VideoExport.origin(it) }, false)?.let { append("\n• Vídeo cru: ${tag[it.first]} (${it.second}%)") }
                best({ it.optString("texto").replace('\n', ' ').take(40).ifBlank { null } }, false)?.let { append("\n• Texto: ${it.first} (${it.second}%)") }
                best({ it.optString("musica").takeIf { m -> m.isNotBlank() }?.let { m -> File(m).nameWithoutExtension.take(30) } }, false)?.let { append("\n• Música: ${it.first} (${it.second}%)") }
                best({ it.optString("modelo").ifBlank { null } }, false)?.let { append("\n• Edição: ★ ${it.first} (${it.second}%)") }
            }
            val bad = buildString {
                best({ it.optString("ctx") }, true)?.let { append("\n• Categoria: ${Contexts.label(a, it.first)} (${it.second}%)") }
                best({ it.optString("texto").replace('\n', ' ').take(40).ifBlank { null } }, true)?.let { append("\n• Texto: ${it.first} (${it.second}%)") }
                best({ it.optString("musica").takeIf { m -> m.isNotBlank() }?.let { m -> File(m).nameWithoutExtension.take(30) } }, true)?.let { append("\n• Música: ${it.first} (${it.second}%)") }
            }
            if (good.isNotEmpty()) a.text("🏆 O que deu certo$good", 12f, a.OK, parent = rs)
            if (bad.isNotEmpty()) a.text("👎 O que não deu$bad", 12f, a.ACCENT, parent = rs)
        }

        // 🔁 2ª chance + horários
        val sc = sub("🔁 2ª chance e horários")
        a.text("O mesmo vídeo pode ir mal numa vez e bem na outra (horário, dia, algoritmo).", 12f, a.MUTED, parent = sc)
        a.chips(sc, listOf("🔁 Dar 2ª chance", "Flopou, sai menos"), { if (Learn.secondChance(a)) "🔁 Dar 2ª chance" else "Flopou, sai menos" }) { Learn.setSecondChance(a, it.startsWith("🔁")); renderLibrary() }
        a.text(if (Learn.secondChance(a)) "Um flop só não conta: o vídeo volta normal pro sorteio. Se flopar de novo, aí sai bem menos." else "Um flop já faz o vídeo sair bem menos.", 11f, a.MUTED, parent = sc)
        a.text(Learn.repostStats(posted), 12f, a.FG, parent = sc)
        a.text(Learn.hourStats(posted), 12f, a.FG, parent = sc)

        // 🧠 IA
        val ia = sub("🧠 IA que aprende com os vídeos")
        val rules = Learn.videoRules(a)
        a.text(if (rules.isEmpty()) "Ainda sem análise. Avalie alguns vídeos e toque em Analisar." else rules.joinToString("\n") { "• $it" }, 12f, if (rules.isEmpty()) a.MUTED else a.FG, parent = ia)
        a.chips(ia, listOf("🤖 Analisa sozinha", "Só quando eu pedir"), { if (Learn.autoOn(a)) "🤖 Analisa sozinha" else "Só quando eu pedir" }) { Learn.setAuto(a, it.startsWith("🤖")) }
        a.text(if (Learn.autoOn(a)) "A cada ${Learn.EVERY} notas novas a IA compara tudo de novo (1 pedido). Faltam ${Learn.EVERY - Learn.pending(a, "v")}." else "A IA só analisa quando você tocar no botão.", 11f, a.MUTED, parent = ia)
        val st = TextView(a).apply { textSize = 12f; setTextColor(a.OK) }
        val ir = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        ia.addView(ir, a.lpMatch())
        a.button("🧠 Analisar agora", ir, primary = true, weight = true) {
            st.setTextColor(a.MUTED); st.text = "A IA está comparando os vídeos…"
            Thread { val m = try { "✓ ${Learn.learnVideos(a).size} regras. Os próximos textos da IA já seguem elas." } catch (e: Exception) { "⚠ " + (e.message ?: "erro") }
                ui.post { renderLibrary(); a.toast(m) } }.start()
        }
        a.button("✨ Textos parecidos com os 🔥", ir, weight = true) {
            val topCtx = rated.filter { Learn.isWin(it) }.groupBy { it.optString("tctx").ifEmpty { it.optString("ctx") } }.maxByOrNull { it.value.size }?.key ?: ""
            st.setTextColor(a.MUTED); st.text = "Criando textos no estilo dos que bombaram" + (if (topCtx.isNotEmpty()) " ($topCtx)" else "") + "…"
            Thread { val m = AutoProducer.genTexts(a, 10, topCtx); ui.post { st.setTextColor(a.OK); st.text = m } }.start()
        }
        ia.addView(st)

        // próximos a sair
        val next = VideoExport.upcoming(a, 6)
        if (next.isNotEmpty()) {
            a.text("Próximos a sair (o que bombou volta mais, nunca em seguida)", 12f, a.MUTED, parent = libBox, bold = true)
            val hs = android.widget.HorizontalScrollView(a)
            val strip = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            hs.addView(strip); libBox.addView(hs, a.lpMatch().apply { topMargin = a.dp(4) })
            next.forEachIndexed { k, o ->
                val cell = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                strip.addView(cell, LinearLayout.LayoutParams(a.dp(60), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = a.dp(6) })
                cell.addView(thumbV(o.optString("id"), 54, 96).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = 0 })
                val v = Learn.verdict(o).let { if (it == "meh") "" else Learn.emoji(it) }
                cell.addView(TextView(a).apply { text = "${k + 1}º ${tag[VideoExport.origin(o)] ?: ""} $v"; textSize = 10f; setTextColor(a.MUTED); gravity = Gravity.CENTER })
            }
        }

        // lista
        val byRes = Store.prefs(a).getString("lib_group", "res") == "res"
        val gr = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        libBox.addView(gr, a.lpMatch().apply { topMargin = a.dp(12) })
        a.chips(gr, listOf("Por resultado", "Por vídeo cru"), { if (Store.prefs(a).getString("lib_group", "res") == "res") "Por resultado" else "Por vídeo cru" }) {
            Store.prefs(a).edit().putString("lib_group", if (it == "Por resultado") "res" else "cru").apply(); renderLibrary()
        }
        fun header(t: String) { libBox.addView(TextView(a).apply { text = t; textSize = 14f; setTextColor(a.FG); typeface = android.graphics.Typeface.DEFAULT_BOLD }, a.lpMatch().apply { topMargin = a.dp(16) }) }
        if (byRes) {
            val sec = listOf(
                "💎 Viralizaram (prioridade máxima)" to all.filter { Learn.verdict(it) == "top" },
                "🔥 Bombaram (voltam mais vezes)" to all.filter { Learn.verdict(it) == "good" },
                "🆕 Na fila, nunca postados" to all.filter { it.optInt("posts") == 0 },
                "😐 Normais / esperando nota" to all.filter { it.optInt("posts") > 0 && Learn.verdict(it).let { v -> v == "meh" || v == "" } },
                "🔁 2ª chance (flopou 1 vez, vai tentar de novo)" to all.filter { Learn.onSecondChance(a, it) },
                "👎 Floparam (saem bem menos)" to all.filter { Learn.verdict(it) == "bad" && !Learn.onSecondChance(a, it) })
            sec.forEach { (t, l) ->
                if (l.isEmpty()) return@forEach
                header("$t · ${l.size}")
                val key = "lib:$t"; val shown = if (key in expanded) l.size else 5
                l.take(shown).forEach { libItem(it, tag) }
                if (l.size > 5) a.button(if (key in expanded) "Mostrar menos" else "Ver todos (${l.size})", libBox) { if (!expanded.add(key)) expanded.remove(key); renderLibrary() }
            }
        } else {
            val groups = all.groupBy { VideoExport.origin(it) }.entries.sortedByDescending { e -> e.value.count { it.optInt("posts") == 0 } }
            groups.forEach { (orig, vids) ->
                val p = if (orig.startsWith("solo:")) null else Projects.list(a).firstOrNull { it.optString("id") == orig }
                val waiting = vids.count { it.optInt("posts") == 0 }
                val pr = p?.let { pj -> if (Learn.rated(pj) > 0) " · ${(Learn.mean(pj) * 100).toInt()}%" else "" } ?: ""
                header("${tag[orig]} · " + (p?.optString("desc")?.ifBlank { null } ?: if (p != null) "Vídeo cru" else "Vídeo importado").take(36) + "\n${vids.size} versão(ões) · $waiting na fila$pr")
                vids.forEach { libItem(it, tag) }
            }
        }
    }

    /** Letra de cada vídeo cru (A, B, C…) pra ver de onde cada pronto saiu. */
    private fun groupTags(all: List<org.json.JSONObject>): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        all.map { VideoExport.origin(it) }.distinct().sorted().forEachIndexed { i, o ->
            m[o] = "🎞 " + (if (i < 26) ('A' + i).toString() else (i + 1).toString())
        }
        return m
    }

    private fun libItem(o: org.json.JSONObject, tag: Map<String, String> = emptyMap()) {
            run {
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
            val sc = if (Learn.rated(o) > 0) "  ·  " + Learn.hist(o).joinToString(" → ") { Learn.emoji(it.second) } else ""
            a.text((tag[VideoExport.origin(o)]?.let { "$it  " } ?: "") + (if (posts > 0) "Postado ${posts}x" else "Ainda não postado") + sc, 12f, if (posts > 0) a.OK else a.ACCENT, parent = side, bold = true)
            o.optString("texto").takeIf { it.isNotBlank() }?.let { a.text("T  " + it.replace('\n', ' ').take(60), 12f, a.FG, parent = side) }
                ?: if (o.optBoolean("auto")) a.text("🎬 sem texto", 12f, a.MUTED, parent = side) else null
            o.optString("modelo").takeIf { it.isNotBlank() }?.let { a.text("★ $it", 12f, a.MUTED, parent = side) }
            o.optString("ctx").takeIf { it.isNotBlank() }?.let { a.text(Contexts.label(a, it), 12f, a.MUTED, parent = side) }
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
                val nota = if (o.optLong("notaAt") == o.optLong("lastPost")) o.optString("nota") else ""
                val rr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                side.addView(rr, a.lpMatch())
                a.text("Como foi o último post?", 11f, a.MUTED, parent = side).also { side.removeView(it); side.addView(it, side.indexOfChild(rr)) }
                listOf("top" to "💎", "good" to "🔥", "meh" to "😐", "bad" to "👎").forEach { (k, e) ->
                    a.button(e, rr, primary = nota == k, weight = true) {
                        RateLater.apply(a, id, k)
                        renderLibrary(); renderOverlays()
                        a.toast("Anotado: o app vai ${if (k == "top") "usar MUITO mais" else if (k == "good") "usar mais" else if (k == "bad") "evitar" else "manter"} texto, modelo e legenda assim")
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
