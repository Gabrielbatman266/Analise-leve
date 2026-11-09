package com.leve.analise

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Aba Refs: links de vídeos de referência (com prévia) e estoque de fotos de referência (com "Mandar pro Flow"). */
class RefsTab(private val a: MainActivity) {
    companion object { const val REQ_PHOTOS = 41 }

    private val ui get() = a.ui
    private lateinit var navRow: LinearLayout
    private val navBtns = ArrayList<android.widget.Button>()
    private lateinit var secLinks: LinearLayout
    private lateinit var secPhotos: LinearLayout
    private lateinit var linkBox: LinearLayout
    private lateinit var linkFilter: LinearLayout
    private lateinit var photoGrid: LinearLayout
    private lateinit var photoFilter: LinearLayout
    private lateinit var etLink: EditText
    private lateinit var etTags: EditText
    private lateinit var tvSel: TextView
    private var cur = 0
    private var tagLinks = ""
    private var tagPhotos = ""
    private val selected = LinkedHashSet<String>()

    fun build() {
        val host = a.page
        val ci = a.card("💡 Ideias pro Flow (no estilo do que bombou)")
        val tvSt = a.text("", 13f, a.FG, parent = ci, bold = true)
        val ideasBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        ci.addView(ideasBox, a.lpMatch())
        fun ideasUi() {
            tvSt.text = "📦 Estoque: fotos novas ~${"%.1f".format(Stock.daysPhotos(a))} dia(s) · vídeos ~${"%.1f".format(Stock.daysVideos(a))} dia(s)"
            ideasBox.removeAllViews()
            val l = Stock.ideas(a)
            if (l.isEmpty()) a.text("Toque em ✨ pra a IA olhar seus posts 🔥/💎 e escrever prompts novos pro Flow. Quando o estoque estiver acabando, o app faz isso sozinho e te avisa.", 12f, a.MUTED, parent = ideasBox)
            l.forEach { t ->
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                ideasBox.addView(r, a.lpMatch().apply { topMargin = a.dp(6) })
                r.addView(TextView(a).apply { text = t; textSize = 12f; setTextColor(a.FG); setTextIsSelectable(true) }, LinearLayout.LayoutParams(0, -2, 1f))
                a.button("📋", r) { a.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("ideia", t)); a.toast("Copiado: cole no Flow") }
            }
        }
        val ir = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        ci.addView(ir, a.lpMatch())
        a.button("✨ Gerar ideias", ir, primary = true, weight = true) {
            tvSt.text = "A IA está olhando seus posts que bombaram…"
            Thread { val m = try { Stock.makeIdeas(a); null } catch (e: Exception) { e.message }; a.ui.post { ideasUi(); m?.let { a.toast(it) } } }.start()
        }
        a.button("Abrir o Flow", ir, weight = true) { try { a.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://labs.google/fx/tools/flow"))) } catch (e: Exception) { } }
        ideasUi()
        navRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        host.addView(navRow, a.lpMatch().apply { topMargin = a.dp(12) })
        listOf("🔗 Links de vídeo", "🖼 Fotos").forEachIndexed { i, n -> navBtns += a.button(n, navRow, weight = true) { show(i) } }
        secLinks = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }; host.addView(secLinks, a.lpMatch())
        secPhotos = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }; host.addView(secPhotos, a.lpMatch())

        // ---------- links
        a.page = secLinks
        val cs = a.card("Salvar link de referência")
        a.text("Instagram, TikTok, YouTube, Pinterest, Threads… O app guarda com uma miniatura e o título pra você lembrar o que é. Ou, no próprio Instagram: Compartilhar › Post Leve.", 13f, a.MUTED, parent = cs)
        etLink = a.field("Link", "", cs) { }
        etTags = a.field("Etiquetas (opcional, separadas por vírgula: pose, música, transição…)", "", cs) { }
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        cs.addView(r, a.lpMatch())
        a.button("📋 Colar e salvar", r, primary = true, weight = true) { pasteAndSave() }
        a.button("Salvar", r, weight = true) { saveTyped() }
        val cl = a.card(null)
        linkFilter = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        cl.addView(linkFilter, a.lpMatch())
        linkBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        cl.addView(linkBox, a.lpMatch())

        // ---------- fotos
        a.page = secPhotos
        val cp = a.card("Estoque de fotos de referência")
        a.text("Poses, looks, cenários. As fotos ficam guardadas no app, sem metadados. Toque pra marcar; segure pra ver, anotar e gerar o prompt.", 13f, a.MUTED, parent = cp)
        a.button("＋ Adicionar fotos", cp, primary = true) { a.pickImages(REQ_PHOTOS, true) }
        a.text("📌 Fotos e vídeos que você salva no Threads (Salvar/baixar mídia) entram aqui sozinhos e o original sai da galeria", 12f, a.MUTED, parent = cp)
        val tvTh = a.text("", 12f, a.OK, parent = cp)
        fun thT() { tvTh.text = ThreadsRefs.status(a); tvTh.setTextColor(if (ThreadsRefs.status(a).startsWith("⚠")) a.ACCENT else a.OK) }
        a.chips(cp, listOf("Ligado", "Desligado"), { if (ThreadsRefs.on(a)) "Ligado" else "Desligado" }) { o ->
            ThreadsRefs.setOn(a, o == "Ligado")
            if (o == "Ligado" && !FolderScan.hasPerm(a)) { a.requestPermissions(FolderScan.perms, 42); a.toast("Escolha \"Permitir tudo\" pra eu ver o que o Threads salva") }
            thT()
        }
        a.chips(cp, listOf("🗑 Apagar o original", "Manter na galeria"), { if (ThreadsRefs.deleteOn(a)) "🗑 Apagar o original" else "Manter na galeria" }) { ThreadsRefs.setDeleteOn(a, it.startsWith("🗑")); thT() }
        val rth = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }; cp.addView(rth, a.lpMatch())
        if (!FolderScan.hasPerm(a)) a.button("Permitir tudo", rth, weight = true) { a.requestPermissions(FolderScan.perms, 42) }
        if (android.os.Build.VERSION.SDK_INT >= 31) a.button("Apagar sem perguntar", rth, weight = true) { ThreadsRefs.openManageMedia(a); a.toast("Ligue \"Gerenciar mídia\" pro Post Leve") }
        a.button("↻ Buscar agora", rth, weight = true) { Thread { val n = ThreadsRefs.scan(a); ThreadsRefs.deleteNow(a); a.runOnUiThread { a.toast(if (n > 0) "$n nova(s) nas Referências" else "Nada novo do Threads"); thT(); renderPhotos() } }.start() }
        thT()
        tvSel = a.text("", 13f, a.OK, parent = cp)
        val sr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        cp.addView(sr, a.lpMatch())
        a.button("🚀 Mandar pro Flow", sr, primary = true, weight = true) { sendToFlow(selected.toList()) }
        a.button("Limpar seleção", sr, weight = true) { selected.clear(); renderPhotos() }
        a.text("Mandar pro Flow: as fotos marcadas vão pro topo da galeria (pasta PostLeve Ref), o prompt delas fica copiado e o Flow abre. Lá é só tocar em adicionar imagem e colar o prompt.", 12f, a.MUTED, parent = cp)
        a.text("Apagar da galeria depois de", 12f, a.MUTED, parent = cp)
        a.chips(cp, listOf("15 min", "30 min", "1 h", "3 h"), { GalleryClean.flowTtl(a).let { if (it >= 60) "${it / 60} h" else "$it min" } }) {
            val m = if (it.endsWith("h")) it.removeSuffix(" h").toInt() * 60 else it.removeSuffix(" min").toInt()
            Store.prefs(a).edit().putInt("flow_ttl", m).apply()
        }
        a.text("As fotos continuam no estoque do app. Só a cópia da galeria some.", 11f, a.MUTED, parent = cp)
        val cg = a.card(null)
        photoFilter = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        cg.addView(photoFilter, a.lpMatch())
        photoGrid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        cg.addView(photoGrid, a.lpMatch())

        renderLinks(); renderPhotos()
        show(Store.prefs(a).getInt("refs_sec", 0), false)
    }

    fun show(i: Int, scroll: Boolean = true) {
        cur = i; Store.prefs(a).edit().putInt("refs_sec", i).apply()
        secLinks.visibility = if (i == 0) View.VISIBLE else View.GONE
        secPhotos.visibility = if (i == 1) View.VISIBLE else View.GONE
        updateNav()
        if (scroll) a.scrollTo(navRow)
    }

    private fun updateNav() {
        val counts = listOf(Refs.links(a).size, Refs.photos(a).size)
        navBtns.forEachIndexed { k, b ->
            val on = k == cur
            b.text = listOf("🔗 Links de vídeo", "🖼 Fotos")[k] + if (counts[k] > 0) " ${counts[k]}" else ""
            b.setTextColor(if (on) 0xFF1A0A10.toInt() else a.FG)
            b.background = GradientDrawable().apply { setColor(if (on) a.ACCENT else 0xFF1C1C24.toInt()); cornerRadius = a.dp(10).toFloat() }
        }
    }

    private fun tags(t: String) = t.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    // ------------------------------------------------------------------ links

    private fun pasteAndSave() {
        val t = try { a.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(a)?.toString() } catch (e: Exception) { null }
        val url = t?.let { Refs.findUrl(it) }
        if (url == null) { a.toast("Não achei link copiado. Copie o link do vídeo e toque de novo."); return }
        saveUrl(url)
    }

    private fun saveTyped() {
        val url = Refs.findUrl(etLink.text.toString()) ?: run { a.toast("Cole um link que comece com http"); return }
        saveUrl(url)
    }

    /** Também usado quando você compartilha um link pro app. */
    fun saveUrl(url: String, extraTags: List<String> = emptyList()) {
        val id = Refs.addLink(a, url, tags(etTags.text.toString()) + extraTags)
        etLink.setText(""); a.toast("Link salvo. Buscando a prévia…")
        show(0); renderLinks()
        Thread { Refs.fetchPreview(a, id); ui.post { renderLinks() } }.start()
    }

    private fun chipsRow(parent: LinearLayout, all: List<String>, cur: String, pick: (String) -> Unit) {
        parent.removeAllViews()
        if (all.isEmpty()) return
        val sc = android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        sc.addView(row); parent.addView(sc, a.lpMatch())
        (listOf("") + all).forEach { t ->
            val on = t == cur
            row.addView(TextView(a).apply {
                text = if (t.isEmpty()) "Todas" else "#$t"; textSize = 13f
                setTextColor(if (on) 0xFF1A0A10.toInt() else a.FG); setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6))
                background = GradientDrawable().apply { setColor(if (on) a.ACCENT else 0xFF1C1C24.toInt()); cornerRadius = a.dp(16).toFloat() }
                setOnClickListener { pick(t) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = a.dp(6) })
        }
    }

    private fun tagList(o: JSONObject) = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty()

    fun renderLinks() {
        if (!::linkBox.isInitialized) return
        updateNav()
        val all = Refs.links(a)
        val ts = all.flatMap { tagList(it) }.distinct()
        chipsRow(linkFilter, ts, tagLinks) { tagLinks = it; renderLinks() }
        linkBox.removeAllViews()
        val l = if (tagLinks.isEmpty()) all else all.filter { tagLinks in tagList(it) }
        if (l.isEmpty()) { a.text("Nenhum link salvo ainda. Copie o link de um vídeo e toque em 📋 Colar e salvar.", 13f, a.MUTED, parent = linkBox); return }
        val fmt = java.text.SimpleDateFormat("dd/MM", java.util.Locale("pt", "BR"))
        l.forEach { o ->
            val id = o.optString("id"); val url = o.optString("url"); val site = o.optString("site")
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
                background = GradientDrawable().apply { setColor(a.BG); cornerRadius = a.dp(12).toFloat(); setStroke(a.dp(1), a.LINE) }
                setOnClickListener { openLink(url) }
                setOnLongClickListener { linkMenu(o); true }
            }
            linkBox.addView(box, a.lpMatch().apply { topMargin = a.dp(8) })
            // miniatura
            val fr = FrameLayout(a)
            box.addView(fr, LinearLayout.LayoutParams(a.dp(84), a.dp(112)).apply { rightMargin = a.dp(10) })
            val th = java.io.File(Refs.thumbDir(a), "$id.jpg")
            fr.addView(ImageView(a).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { setColor(0xFF1C1C24.toInt()); cornerRadius = a.dp(8).toFloat() }
                clipToOutline = true
                if (th.exists()) setImageBitmap(BitmapFactory.decodeFile(th.path))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (!th.exists()) fr.addView(TextView(a).apply {
                text = Refs.siteIcon(site) + "\n" + if (o.optBoolean("fetched")) "sem prévia" else "carregando…"
                gravity = Gravity.CENTER; setTextColor(a.MUTED); textSize = 12f
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fr.addView(TextView(a).apply {
                text = "▶"; textSize = 18f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x88000000.toInt()) }
            }, FrameLayout.LayoutParams(a.dp(34), a.dp(34), Gravity.CENTER))
            // textos
            val side = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            box.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val title = o.optString("title").ifBlank { url }
            side.addView(TextView(a).apply { text = title; textSize = 14f; setTextColor(a.FG); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
            val who = o.optString("author").let { if (it.isNotBlank()) " · $it" else "" }
            a.text("${Refs.siteIcon(site)} $site$who · ${fmt.format(java.util.Date(o.optLong("at")))}" + if (o.optBoolean("usado")) " · ✓ usado" else "", 12f, if (o.optBoolean("usado")) a.OK else a.MUTED, parent = side)
            o.optString("note").takeIf { it.isNotBlank() }?.let { a.text("📝 $it", 12f, a.FG, parent = side) }
            tagList(o).takeIf { it.isNotEmpty() }?.let { a.text(it.joinToString("  ") { t -> "#$t" }, 12f, a.ACCENT, parent = side) }
        }
        a.text("Toque pra abrir · segure pra nota, etiquetas, marcar como usado ou apagar", 11f, a.MUTED, parent = linkBox)
    }

    private fun openLink(url: String) {
        try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { a.toast("Não consegui abrir esse link") }
    }

    private fun editDialog(title: String, note: String, tg: List<String>, save: (String, List<String>) -> Unit) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(20), a.dp(8), a.dp(20), 0) }
        val en = EditText(a).apply { hint = "Nota (ex.: usar essa pose no próximo post)"; setText(note); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        val et = EditText(a).apply { hint = "Etiquetas separadas por vírgula"; setText(tg.joinToString(", ")) }
        box.addView(en); box.addView(et)
        AlertDialog.Builder(a).setTitle(title).setView(box)
            .setPositiveButton("Salvar") { _, _ -> save(en.text.toString().trim(), tags(et.text.toString())) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun linkMenu(o: JSONObject) {
        val id = o.optString("id"); val url = o.optString("url")
        val usado = o.optBoolean("usado")
        AlertDialog.Builder(a).setTitle(o.optString("title").ifBlank { Refs.site(url) }.take(60))
            .setItems(arrayOf("▶ Abrir", "📝 Nota e etiquetas", if (usado) "Marcar como não usado" else "✓ Marcar como usado", "📋 Copiar link", "🔄 Buscar prévia de novo", "🗑 Apagar")) { _, w ->
                when (w) {
                    0 -> openLink(url)
                    1 -> editDialog("Nota e etiquetas", o.optString("note"), tagList(o)) { n, t -> Refs.updateLink(a, id) { it.put("note", n); it.put("tags", JSONArray(t)) }; renderLinks() }
                    2 -> { Refs.updateLink(a, id) { it.put("usado", !usado) }; renderLinks() }
                    3 -> { copy(url); a.toast("Link copiado") }
                    4 -> { a.toast("Buscando…"); Thread { Refs.fetchPreview(a, id); ui.post { renderLinks() } }.start() }
                    5 -> AlertDialog.Builder(a).setTitle("Apagar esse link?").setPositiveButton("Apagar") { _, _ -> Refs.removeLink(a, id); renderLinks() }.setNegativeButton("Cancelar", null).show()
                }
            }.show()
    }

    private fun copy(t: String) { try { a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ref", t)) } catch (e: Exception) { } }

    // ------------------------------------------------------------------ fotos

    fun addPhotos(uris: List<Uri>, extraTags: List<String> = emptyList()) {
        a.toast("Guardando ${uris.size} foto(s)…")
        Thread {
            var n = 0
            uris.forEach { if (Refs.addPhoto(a, it, extraTags) != null) n++ }
            ui.post { a.toast("$n foto(s) no estoque, sem metadados"); show(1); renderPhotos() }
        }.start()
    }

    fun renderPhotos() {
        if (!::photoGrid.isInitialized) return
        updateNav()
        val all = Refs.photos(a)
        selected.retainAll(all.map { it.optString("id") }.toSet())
        chipsRow(photoFilter, all.flatMap { tagList(it) }.distinct(), tagPhotos) { tagPhotos = it; renderPhotos() }
        tvSel.text = if (selected.isEmpty()) "" else "${selected.size} marcada(s)"
        photoGrid.removeAllViews()
        val l = if (tagPhotos.isEmpty()) all else all.filter { tagPhotos in tagList(it) }
        if (l.isEmpty()) { a.text("Nenhuma foto ainda. Toque em ＋ Adicionar fotos, ou compartilhe fotos da galeria pro Post Leve.", 13f, a.MUTED, parent = photoGrid); return }
        val size = (a.resources.displayMetrics.widthPixels - a.dp(64)) / 3
        l.chunked(3).forEach { row3 ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            photoGrid.addView(row, a.lpMatch().apply { topMargin = a.dp(4) })
            row3.forEach { o ->
                val id = o.optString("id")
                val fr = FrameLayout(a).apply {
                    setOnClickListener { if (!selected.remove(id)) selected.add(id); renderPhotos() }
                    setOnLongClickListener { photoDetail(o); true }
                }
                row.addView(fr, LinearLayout.LayoutParams(size, size * 4 / 3).apply { rightMargin = a.dp(4) })
                val th = Refs.photoThumb(a, id)
                fr.addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1C1C24.toInt()); if (th.exists()) setImageBitmap(BitmapFactory.decodeFile(th.path)) },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val k = selected.indexOf(id)
                if (k >= 0) {
                    fr.foreground = GradientDrawable().apply { setColor(0x00000000); setStroke(a.dp(3), a.ACCENT) }
                    fr.addView(TextView(a).apply {
                        text = "${k + 1}"; textSize = 13f; setTextColor(0xFF1A0A10.toInt()); gravity = Gravity.CENTER
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(a.ACCENT) }
                    }, FrameLayout.LayoutParams(a.dp(26), a.dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, a.dp(4), a.dp(4), 0) })
                }
                val badges = (if (o.optString("prompt").isNotBlank()) "✍ " else "") + (if (o.optInt("usos") > 0) "🚀${o.optInt("usos")}" else "")
                if (badges.isNotBlank()) fr.addView(TextView(a).apply {
                    text = badges; textSize = 11f; setTextColor(0xFFFFFFFF.toInt()); setPadding(a.dp(5), a.dp(2), a.dp(5), a.dp(2))
                    background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = a.dp(6).toFloat() }
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(a.dp(4), 0, 0, a.dp(4)) })
            }
        }
    }

    private fun photoDetail(o: JSONObject) {
        val id = o.optString("id")
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(8), a.dp(16), 0) }
        val sc = android.widget.ScrollView(a).apply { addView(box) }
        box.addView(ImageView(a).apply { adjustViewBounds = true; setImageBitmap(BitmapFactory.decodeFile(Refs.photoFile(a, id).path)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(320)))
        val tvP = TextView(a).apply { textSize = 13f; setTextColor(a.FG); setTextIsSelectable(true); setPadding(0, a.dp(8), 0, 0)
            text = o.optString("prompt").ifBlank { "Ainda sem prompt. Toque em ✍ Gerar prompt." } }
        box.addView(tvP)
        o.optString("note").takeIf { it.isNotBlank() }?.let { box.addView(TextView(a).apply { text = "📝 $it"; setTextColor(a.MUTED); textSize = 13f }) }
        val dlg = AlertDialog.Builder(a).setView(sc)
            .setPositiveButton("🚀 Mandar pro Flow") { _, _ -> sendToFlow(listOf(id)) }
            .setNeutralButton("Mais…") { _, _ -> photoMenu(o) }
            .setNegativeButton("Fechar", null).show()
        // gerar prompt direto daqui
        a.button("✍ Gerar prompt com IA", box) {
            tvP.text = "Gerando…"
            Thread {
                val r = try { Ai.flowPrompt(a, Refs.photoFile(a, id)).also { p -> Refs.updatePhoto(a, id) { it.put("prompt", p) } } }
                        catch (e: Exception) { "Não deu: ${e.message}" }
                ui.post { tvP.text = r; renderPhotos() }
            }.start()
        }
        a.button("📋 Copiar prompt", box) {
            val p = Refs.photos(a).firstOrNull { it.optString("id") == id }?.optString("prompt").orEmpty()
            if (p.isBlank()) a.toast("Gere o prompt primeiro") else { copy(p); a.toast("Prompt copiado") }
        }
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun photoMenu(o: JSONObject) {
        val id = o.optString("id")
        AlertDialog.Builder(a).setTitle("Foto de referência")
            .setItems(arrayOf("📝 Nota e etiquetas", "✏ Escrever o prompt eu mesmo", "🗑 Apagar")) { _, w ->
                when (w) {
                    0 -> editDialog("Nota e etiquetas", o.optString("note"), tagList(o)) { n, t -> Refs.updatePhoto(a, id) { it.put("note", n); it.put("tags", JSONArray(t)) }; renderPhotos() }
                    1 -> {
                        val et = EditText(a).apply { setText(o.optString("prompt")); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3 }
                        AlertDialog.Builder(a).setTitle("Prompt pro Flow").setView(et)
                            .setPositiveButton("Salvar") { _, _ -> Refs.updatePhoto(a, id) { it.put("prompt", et.text.toString().trim()) }; renderPhotos() }
                            .setNegativeButton("Cancelar", null).show()
                    }
                    2 -> AlertDialog.Builder(a).setTitle("Apagar essa foto do estoque?").setPositiveButton("Apagar") { _, _ -> Refs.removePhoto(a, id); selected.remove(id); renderPhotos() }.setNegativeButton("Cancelar", null).show()
                }
            }.show()
    }

    /** Fotos no topo da galeria + prompt copiado + Flow aberto. */
    private fun sendToFlow(ids: List<String>) {
        if (ids.isEmpty()) { a.toast("Marque as fotos primeiro (toque nelas)"); return }
        Thread {
            val n = Refs.sendToGallery(a, ids)
            val ps = Refs.photos(a).filter { it.optString("id") in ids }.map { it.optString("prompt") }.filter { it.isNotBlank() }
            ui.post {
                if (ps.isNotEmpty()) copy(ps.joinToString("\n\n"))
                val ttl = GalleryClean.flowTtl(a).let { if (it >= 60) "${it / 60} h" else "$it min" }
                a.toast("$n foto(s) no topo da galeria por $ttl" + if (ps.isNotEmpty()) " e prompt copiado" else "")
                selected.clear(); renderPhotos()
                try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Refs.FLOW_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
            }
        }.start()
    }

    fun onResult(req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ_PHOTOS) return false
        if (res != android.app.Activity.RESULT_OK || data == null) return true
        val uris = ArrayList<Uri>()
        data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        if (uris.isNotEmpty()) addPhotos(uris)
        return true
    }
}
