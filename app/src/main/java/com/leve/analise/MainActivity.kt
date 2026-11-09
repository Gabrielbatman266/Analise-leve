package com.leve.analise

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val REQ_PROJ = 11
    private val REQ_GALLERY = 12
    private val REQ_POSTS = 13
    private val REQ_DEL = 14
    private val REQ_RESTORE = 47
    private val REQ_IGPHOTO = 16
    private val REQ_PROMO = 48

    // paleta AMOLED
    private lateinit var meta: MetaPanel
    internal val BG = 0xFF000000.toInt()
    internal val CARD = 0xFF121218.toInt()
    internal val LINE = 0xFF262630.toInt()
    internal val FG = 0xFFECEEF3.toInt()
    internal val MUTED = 0xFF9A9EAB.toInt()
    internal val ACCENT = 0xFFFF5C82.toInt()
    internal val OK = 0xFF4CC68E.toInt()

    internal val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    internal lateinit var page: LinearLayout
    private val pages = LinkedHashMap<String, LinearLayout>()
    private lateinit var tabRow: LinearLayout
    private lateinit var refsBox: LinearLayout
    private lateinit var mineBox: LinearLayout
    private lateinit var tvLearn: TextView
    private lateinit var insta: InstaEditor
    private lateinit var refs: RefsTab
    internal lateinit var scroller: ScrollView

    internal fun scrollTo(v: View) {
        v.post {
            val r = android.graphics.Rect(); v.getDrawingRect(r)
            try { scroller.offsetDescendantRectToMyCoords(v, r); scroller.smoothScrollTo(0, maxOf(0, r.top - dp(8))) } catch (e: Exception) { }
        }
    }
    private lateinit var tvStatus: TextView
    private lateinit var tvErr: TextView
    private lateinit var tvTop: TextView
    private lateinit var tvPat: TextView
    private lateinit var postList: LinearLayout
    private lateinit var tvPostInfo: TextView
    private lateinit var everyRow: LinearLayout
    private var postStamp = -1L
    private lateinit var btnFolder: Button
    private lateinit var btn2k: Button
    private lateinit var btnExisting: Button
    private lateinit var btnFlowNames: Button
    private lateinit var btnVideos: Button
    private lateinit var btnCleanDl: Button
    private val thumbs = HashMap<String, Bitmap>()
    private lateinit var btnBubble: Button
    private lateinit var btnClaude: Button
    private lateinit var btnGemini: Button
    private lateinit var etModel: EditText
    private var clearArmed = false

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildUi()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        if (savedInstanceState == null) handleShare(intent)
        intent.getStringExtra("tab")?.let { showTab(it) }
        if (intent.getBooleanExtra("update", false)) { intent.removeExtra("update"); Updater.download(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("tab")?.let { showTab(it) }
        if (intent.getBooleanExtra("update", false)) { intent.removeExtra("update"); Updater.download(this) }
        handleShare(intent)
    }

    private var keysRedraw: (() -> Unit)? = null
    private var tnRedraw: (() -> Unit)? = null
    private var healthRedraw: (() -> Unit)? = null
    private var histRedraw: (() -> Unit)? = null
    private var panelRedraw: (() -> Unit)? = null
    private var setupRedraw: (() -> Unit)? = null

    private fun fixSetup(name: String) {
        when (name) {
            "Chave da IA" -> { showTab("Ajustes"); toast("Cole a chave no cartão da IA, em Ajustes") }
            "API do Threads" -> { showTab("Ajustes"); toast("Abra o cartão 🔗 API do Threads, em Ajustes") }
            "Notificações do app" -> if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
            "Acesso às fotos e vídeos" -> requestPermissions(FolderScan.perms, 42)
            "Pasta do Flow" -> askFolder()
            "Leitor de notificações" -> if (ThreadsNotif.hasAccess(this)) { ThreadsNotif.rebind(this); toast("Reconectando…") } else { ThreadsNotif.setOn(this, true); toast("Ative o Post Leve. Se bloquear: Informações do app › ⋮ › Permitir configurações restritas"); ThreadsNotif.openAccess(this) }
            "Backup recente" -> Thread { val m = try { Backup.run(this) } catch (e: Exception) { "Erro: ${e.message}" }; ui.post { toast(m); setupRedraw?.invoke() } }.start()
            "Leitor de tela" -> { toast("Ative \"Post Leve: ler curtidas\". Se bloquear: ⋮ › Permitir configurações restritas"); Setup.open(this, name) }
            else -> if (!Setup.open(this, name)) toast("Não consegui abrir essa configuração")
        }
        ui.postDelayed({ setupRedraw?.invoke() }, 1500)
    }
    private var healthDrawFn: ((LinearLayout, List<Health.Issue>) -> Unit)? = null
    private fun healthDraw(box: LinearLayout, issues: List<Health.Issue>) { healthDrawFn?.invoke(box, issues) }

    /** 📝 Nota manual: escolhe a foto (ou vídeo), a legenda que usou e a nota. */
    private fun manualRate() {
        AlertDialog.Builder(this).setTitle("Dar nota em…").setItems(arrayOf("📸 Uma foto do Threads", "🎬 Um vídeo do Instagram")) { _, w ->
            if (w == 0) pickItem(PostQueue.load(this).filter { PostQueue.imageFile(this, it.optString("id")).exists() }.sortedByDescending { maxOf(it.optLong("sentAt"), it.optLong("postedAt"), it.optLong("at")) }, false)
            else pickItem(VideoExport.library(this).filter { java.io.File(VideoExport.videoDir(this), "${it.optString("id")}.mp4").exists() }.sortedByDescending { maxOf(it.optLong("lastPost"), it.optLong("at")) }, true)
        }.show()
    }

    private fun pickItem(items: List<org.json.JSONObject>, video: Boolean) {
        if (items.isEmpty()) { toast("Nada pra escolher"); return }
        val sv = ScrollView(this)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        sv.addView(grid)
        var dlg: AlertDialog? = null
        items.take(60).chunked(3).forEach { rowItems ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(r, lpMatch().apply { topMargin = dp(6) })
            rowItems.forEach { o ->
                val id = o.optString("id")
                r.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1C1C24.toInt())
                    SendLog.thumb(this@MainActivity, if (video) "video" else "foto", id)?.let { setImageBitmap(it) }
                    setOnClickListener { dlg?.dismiss(); if (video) rateDialog(o, true, o.optString("legenda")) else pickCaption(o) }
                }, LinearLayout.LayoutParams(0, dp(150), 1f).apply { rightMargin = dp(6) })
            }
            repeat(3 - rowItems.size) { r.addView(View(this), LinearLayout.LayoutParams(0, dp(150), 1f)) }
        }
        dlg = AlertDialog.Builder(this).setTitle(if (video) "Qual vídeo?" else "Qual foto?").setView(sv).setNegativeButton("Cancelar", null).show()
    }

    private fun pickCaption(o: org.json.JSONObject) {
        val own = o.optJSONArray("legendas")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val opts = (listOfNotNull(o.optString("escolhida").takeIf { it.isNotBlank() }) + Captions.recentCaps(this).take(12) + own)
            .map { it.trim() }.filter { it.isNotBlank() }.distinct().take(25)
        val labels = opts.map { it.replace('\n', ' ').take(70) } + "✏ Escrever a legenda que usei"
        AlertDialog.Builder(this).setTitle("Qual legenda você usou?").setItems(labels.toTypedArray()) { _, i ->
            if (i < opts.size) rateDialog(o, false, opts[i])
            else {
                val et = EditText(this).apply { hint = "Cole ou escreva a legenda" }
                AlertDialog.Builder(this).setTitle("Legenda usada").setView(et)
                    .setPositiveButton("OK") { _, _ -> val t = et.text.toString().trim(); if (t.isNotEmpty()) { CaptionDB.add(this, t, "minha"); rateDialog(o, false, t) } }
                    .setNegativeButton("Cancelar", null).show()
            }
        }.show()
    }

    private fun rateDialog(o: org.json.JSONObject, video: Boolean, cap: String) {
        val id = o.optString("id")
        val ks = listOf("top", "good", "meh", "bad")
        AlertDialog.Builder(this).setTitle("Como foi?" + if (cap.isNotBlank()) "\n" + cap.replace('\n', ' ').take(60) else "")
            .setItems(arrayOf("💎 Viralizou", "🔥 Bombou", "😐 Normal", "👎 Flopou")) { _, i ->
                val k = ks[i]
                if (video) {
                    if (o.optInt("posts") == 0) VideoExport.updateLib(this, id) { it.put("posts", 1); it.put("lastPost", System.currentTimeMillis()) }
                    RateLater.apply(this, id, k)
                } else {
                    PostQueue.update(this, id) { it.put("sentAt", System.currentTimeMillis()) } // conta como um post novo
                    RateLater.applyPhoto(this, id, k, cap)
                }
                SendLog.add(this, if (video) "video" else "fila", id, cap, if (video) "Instagram" else "Threads", manual = true)
                SendLog.noted(this, if (video) "video" else "fila", id, k)
                toast("${Learn.emoji(k)} anotado: foto/vídeo e legenda")
                histRedraw?.invoke(); refresh(); if (::insta.isInitialized) insta.refreshLists()
            }.setNegativeButton("Cancelar", null).show()
    }

    override fun onResume() {
        super.onResume()
        Auto.migrate(this)
        PostBar.update(this); keysRedraw?.invoke(); tnRedraw?.invoke(); healthRedraw?.invoke(); histRedraw?.invoke(); panelRedraw?.invoke(); setupRedraw?.invoke(); rankRedraw?.invoke()
        if (ThreadsNotif.on(this) && ThreadsNotif.hasAccess(this) && !ThreadsNotif.live(this)) ThreadsNotif.rebind(this)
        ui.postDelayed({ askPending() }, 600)
        ui.postDelayed({ if (::insta.isInitialized) insta.maybeRefill() }, 2500)
        Updater.schedule(this)
        Daily.schedule(this)
        Updater.checkOnOpen(this)
        QuickPost.publishShortcut(this)
        QuickPost.updateWidgets(this)
        Thread {
            PostQueue.cleanGallery(this, false)
            GalleryClean.sweep(this)
            try { Predictor.train(this); PostQueue.load(this).take(150).forEach { Predictor.photo(this, it) } } catch (e: Exception) { ErrLog.log(this, "modelo", e) }
            RateWatch.schedule(this)
            try { RateWatch.run(this, notify = false) } catch (e: Exception) { }
            ui.post { histRedraw?.invoke() }
            val rec = Recover.run(this)
            val hf = Health.autoFix(this)
            if (hf.isNotEmpty()) ui.post { toast("🩺 " + hf.first()); healthRedraw?.invoke() }
            if (rec.sum() > 0) ui.post {
                toast("Recuperei: " + listOf(rec[0] to "foto(s) da fila", rec[1] to "vídeo(s) prontos", rec[2] to "vídeo(s) crus", rec[3] to "referência(s)").filter { it.first > 0 }.joinToString(", ") { "${it.first} ${it.second}" })
                postStamp = -1L; refresh(); if (::insta.isInitialized) insta.refreshLists(); if (::refs.isInitialized) refs.renderPhotos()
            }
            val nr = ThreadsRefs.scan(this)
            if (nr > 0) ui.post { toast("📌 $nr foto(s) do Threads nas Referências"); if (::refs.isInitialized) refs.renderPhotos() }
            val n = FolderScan.scan(this)
            val nv = FolderScan.scanVideos(this)
            if (nv > 0) ui.post { toast("$nv vídeo(s) do Flow viraram esboço na aba Instagram"); if (::insta.isInitialized) insta.refreshLists() }
            FolderScan.schedule(this)
            Captions.fillFromBank(this) // fotos paradas em "gerando" recebem legendas do banco, sem gastar crédito
            if (n > 0 || PostQueue.pendingCaptions(this).isNotEmpty()) ui.post {
                if (n > 0) toast("$n foto(s) nova(s) da pasta ${FolderScan.bucket(this)} na fila")
                LeveService.send(this, LeveService.ACT_ANALYZE)
            }
        }.start()
        ui.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        if (::insta.isInitialized) insta.stopPlay()
        ui.removeCallbacks(refresher)
    }

    private val refresher = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1500) }
    }

    // ------------------------------------------------------------------ tela

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        scroller = scroll
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(40))
        }
        scroll.addView(root)
        setContentView(scroll)

        text("Post Leve", 30f, FG, bold = true)
        text("Fotos do Flow sem metadados, legendas que viralizam, postagem em um toque.", 14f, MUTED)

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabRow, lpMatch().apply { topMargin = dp(12) })
        for (name in listOf("Postar", "Legendas", "Instagram", "Refs", "Limpar", "Analisar", "Ajustes")) {
            if (name == "Analisar") { pages[name] = LinearLayout(this); continue } // desativada
            val pg = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            pages[name] = pg
            val lab = mapOf("Postar" to "📸\nPostar", "Legendas" to "💬\nLegendas", "Instagram" to "🎬\nVídeos", "Refs" to "💡\nRefs", "Limpar" to "🧽\nLimpar", "Ajustes" to "⚙\nAjustes")[name] ?: name
            button(lab, tabRow, weight = true) { showTab(name) }.apply { tag = name; textSize = 11.5f; maxLines = 2; setPadding(dp(2), dp(6), dp(2), dp(6)) }
        }

        page = root
        // status
        val st = card()
        tvStatus = text("", 16f, FG, parent = st, bold = true)
        tvErr = text("", 13f, ACCENT, parent = st)

        pages.values.forEach { root.addView(it, lpMatch()) }

        // ===== Postar
        page = pages["Postar"]!!
        // ✅ checklist de configuração (some quando está tudo ok)
        val cset = card("✅ Configuração")
        val setBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cset.addView(setBox, lpMatch())
        var setOpen = false
        fun setupUi() {
            setBox.removeAllViews()
            val items = Setup.items(this)
            val (ok, tot) = items.count { it.ok } to items.size
            val missingImportant = items.count { !it.ok && it.important }
            text("$ok de $tot prontos" + if (missingImportant > 0) " · ⚠ $missingImportant importante(s) faltando" else " ✓", 14f, if (missingImportant > 0) ACCENT else OK, parent = setBox, bold = true)
            val show = setOpen || missingImportant > 0
            if (show) items.forEach { i ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                setBox.addView(r, lpMatch().apply { topMargin = dp(6) })
                r.addView(TextView(this).apply {
                    text = (if (i.ok) "✓ " else if (i.important) "✗ " else "○ ") + i.name + (if (!i.ok) "\n   " + i.why else "")
                    textSize = 13f; setTextColor(if (i.ok) OK else if (i.important) ACCENT else MUTED)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (!i.ok && i.fixLabel != null) button(i.fixLabel, r) { fixSetup(i.name) }
            }
            button(if (show && missingImportant == 0) "Esconder" else if (!show) "Ver tudo" else "↻ Conferir de novo", setBox) { if (missingImportant == 0) setOpen = !setOpen; setupUi() }
        }
        setupUi(); setupRedraw = { setupUi() }

        // 🏠 painel: o essencial em cima
        val cpn = card("🏠 Painel")
        val tvPanel = text("", 14f, FG, parent = cpn, bold = true)
        val rp = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cpn.addView(rp, lpMatch())
        button("📸 Postar foto", rp, primary = true, weight = true) { try { startActivity(QuickPost.intent(this)) } catch (e: Exception) { } }
        button("🎬 Postar vídeo", rp, primary = true, weight = true) { try { startActivity(QuickPost.videoIntent(this)) } catch (e: Exception) { } }
        val tvAuto = text("", 12f, MUTED, parent = cpn)
        chips(cpn, listOf("🤖 Tudo automático", "Manual"), { if (Auto.on(this)) "🤖 Tudo automático" else "Manual" }) { Auto.setOn(this, it.startsWith("🤖")); panelRedraw?.invoke() }
        fun panelUi() {
            val nf = QuickPost.countReady(this); val nv = QuickPost.countVideos(this)
            val g = Daily.goal(this); val sn = SendLog.pending(this).size + SendLog.all(this).count { it.optBoolean("ok") && it.optString("nota").isEmpty() && System.currentTimeMillis() - it.optLong("at") < 3 * 86400_000L }
            tvPanel.text = "📸 $nf fotos · 🎬 $nv vídeos prontos" + (if (g > 0) " · hoje ${Daily.postedToday(this)}/$g" else "") + (if (sn > 0) "\n⭐ $sn post(s) esperando nota (veja 🕘 abaixo)" else "")
            tvAuto.text = Auto.summary(this)
        }
        panelUi(); panelRedraw = { panelUi() }

        // 🏆 ranking + ✨ postagem perfeita
        val crk = card("🏆 Ranking e postagem perfeita")
        button("✨ Montar a postagem perfeita", crk, primary = true) { showPerfect(false) }
        val rmc = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; crk.addView(rmc, lpMatch())
        val etH = EditText(this).apply { hint = "seu @ no Threads"; setText(Macro.handle(this@MainActivity)); setSingleLine(true); setTextColor(FG); setHintTextColor(MUTED); textSize = 13f }
        rmc.addView(etH, LinearLayout.LayoutParams(0, -2, 1f))
        button("🤖 Coletar números", rmc) {
            Macro.setHandle(this, etH.text.toString())
            AlertDialog.Builder(this).setTitle("🤖 Coleta automática")
                .setMessage("O app vai abrir seu perfil no Threads, descer devagar, abrir só os SEUS posts (pela legenda), abrir a atividade/insights, ler os números e voltar. Não curte, não comenta, não clica em mais nada.\n\nLimite: ${Macro.maxPosts(this)} posts ou 5 minutos. Não mexa no celular enquanto roda; pra parar, use ⏹ na notificação.\n\nA Meta não permite coleta automática: vai devagar pra ser discreto, mas use com moderação (1x por dia).")
                .setPositiveButton("Começar") { _, _ -> toast(Macro.start(this)) }.setNegativeButton("Cancelar", null).show()
        }
        val rmi = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; crk.addView(rmi, lpMatch())
        val etI = EditText(this).apply { hint = "seu @ no Instagram"; setText(Macro.igHandle(this@MainActivity)); setSingleLine(true); setTextColor(FG); setHintTextColor(MUTED); textSize = 13f }
        rmi.addView(etI, LinearLayout.LayoutParams(0, -2, 1f))
        button("🤖 Coletar Reels", rmi) {
            Macro.setIgHandle(this, etI.text.toString())
            AlertDialog.Builder(this).setTitle("🤖 Coleta dos Reels")
                .setMessage("Abre a aba Reels do seu perfil, abre cada Reel em ordem, confere pela legenda se é um vídeo do app, abre \"Ver insights\" se tiver, lê os números e volta. Só lê.\n\nLimite: ${Macro.maxPosts(this)} Reels ou 5 minutos. Pra parar: ⏹ na notificação.")
                .setPositiveButton("Começar") { _, _ -> toast(Macro.startInstagram(this)) }.setNegativeButton("Cancelar", null).show()
        }
        chips(crk, listOf("Ler todos os meus posts", "Só os do app"), { if (Macro.readAll(this)) "Ler todos os meus posts" else "Só os do app" }) { Macro.setReadAll(this, it.startsWith("Ler")) }
        text("✋ Modo manual: role e abra os posts você mesmo; quando o leitor captar, aparece um aviso verde.", 12f, MUTED, parent = crk)
        text("📖 Forçar leitura: no Threads/Instagram aparece um botãozinho 📖 na borda. Abra o post ou o \"Ver insights\" e toque nele: lê, espera carregar, lê de novo, desce pra ver o resto e guarda (até post feito fora do app). Arraste pra mudar de lugar; segure pra esconder 30 min.", 12f, MUTED, parent = crk)
        chips(crk, listOf("📖 Botão de leitura ligado", "Sem botão"), { if (Macro.readBtn(this)) "📖 Botão de leitura ligado" else "Sem botão" }) { Macro.setReadBtn(this, it.startsWith("📖")) }
        val rpz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; crk.addView(rpz, lpMatch())
        val bPz = button("", rpz, weight = true) { }
        fun pzT() { bPz.text = if (Macro.paused(this)) "▶ Retomar o leitor de tela" else "⏸ Pausar o leitor por 1 h" }
        bPz.setOnClickListener { if (Macro.paused(this)) { Macro.pause(this, 0); toast("Leitor ligado de novo") } else { Macro.pause(this, 3600_000L); toast("Leitor pausado por 1 h: abra o Threads e veja se os seguidores/bio aparecem") }; pzT() }
        pzT()
        chips(crk, listOf("Aviso ligado", "Sem aviso"), { if (Macro.manualToast(this)) "Aviso ligado" else "Sem aviso" }) { Macro.setManualToast(this, it == "Aviso ligado") }
        Macro.lastRun(this).takeIf { it.isNotBlank() }?.let { text("Última coleta: $it", 11f, MUTED, parent = crk) }
        val rkBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        crk.addView(rkBox, lpMatch())
        text("De onde vêm os números (sem API): 1) abra seu PERFIL no Threads e role devagar — o leitor de tela pega seguidores e os números de cada post; 2) abra \"Ver insights/atividade\" de um post — pega visualizações, respostas, reposts e seguidores; 3) compartilhe o PRINT dos insights com o Post Leve (a IA lê).", 11f, MUTED, parent = crk)
        fun rankUi() {
            rkBox.removeAllViews()
            val ps = ThreadsApi.posts(this)
            val src = ps.groupBy { it.optString("src").ifEmpty { "api" } }.mapValues { it.value.size }
            val fol = ThreadsApi.followersNow(this)
            text((fol?.let { "👥 $it seguidores · " } ?: "") + "${ps.size} post(s) com números" + (if (src.isNotEmpty()) " (" + src.entries.joinToString(", ") { "${it.key} ${it.value}" } + ")" else ""), 12f, FG, parent = rkBox)
            // 🎬 vídeos
            val tv = ThreadsApi.topVideos(this, 5)
            if (tv.isNotEmpty()) {
                text("🎬 Reels que mais renderam:", 13f, FG, parent = rkBox, bold = true)
                tv.forEachIndexed { i, (m, s) ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    rkBox.addView(row, lpMatch().apply { topMargin = dp(6) })
                    row.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1C1C24.toInt())
                        SendLog.thumb(this@MainActivity, "video", m.optString("video"))?.let { setImageBitmap(it) } }, LinearLayout.LayoutParams(dp(36), dp(60)).apply { rightMargin = dp(8) })
                    row.addView(TextView(this).apply {
                        text = "${i + 1}º · ${(s * 100).toInt()} pts · " + listOfNotNull(m.optInt("views").takeIf { it > 0 }?.let { "▶ $it" }, "❤ ${m.optInt("likes")}", "💬 ${m.optInt("replies")}",
                            m.optInt("shares").takeIf { it > 0 }?.let { "↗ $it" }, m.optInt("saves").takeIf { it > 0 }?.let { "🔖 $it" }, m.optInt("fol").takeIf { it > 0 }?.let { "👥 +$it" }).joinToString(" ")
                        textSize = 12f; setTextColor(if (i < 2) OK else FG)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                text("📸 Fotos do Threads:", 13f, FG, parent = rkBox, bold = true)
            }
            val r = Insights.ranking(this, 10)
            if (r.isEmpty()) { text("O ranking das fotos aparece com 3+ posts com números.", 12f, MUTED, parent = rkBox); return }
            r.forEachIndexed { i, (m, s) ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                rkBox.addView(row, lpMatch().apply { topMargin = dp(6) })
                row.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1C1C24.toInt())
                    SendLog.thumb(this@MainActivity, "foto", m.optString("photo"))?.let { setImageBitmap(it) } }, LinearLayout.LayoutParams(dp(40), dp(56)).apply { rightMargin = dp(8) })
                row.addView(TextView(this).apply {
                    text = "${i + 1}º · ${(s * 100).toInt()} pts · " + listOfNotNull(m.optInt("views", 0).takeIf { it > 0 }?.let { "👁 $it" }, "❤ ${m.optInt("likes")}", "💬 ${m.optInt("replies")}",
                        m.optDouble("fol", 0.0).takeIf { it > 0 }?.let { "👥 +${"%.1f".format(it)}" }).joinToString(" ") + "\n" + m.optString("text").replace('\n', ' ').take(60)
                    textSize = 12f; setTextColor(if (i < 3) OK else FG)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        rankUi(); rankRedraw = { rankUi() }

        // ===== 🧽 Limpar (remover metadados)
        meta = MetaPanel(this); meta.build(pages["Limpar"]!!)

        // atalhos sempre à mão (ficam em Ajustes)
        val ck = card("📌 Botão de postar sempre à mão")
        val rk = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ck.addView(rk, lpMatch())
        val bBar = button("", rk, weight = true) { }
        val bBub = button("", rk, weight = true) { }
        fun keysUi() {
            val bar = PostBar.barOn(this); val bub = PostBar.bubbleOn(this) && PostBar.canBubble(this)
            bBar.text = (if (bar) "✓ " else "") + "🔔 Na notificação"
            bBub.text = (if (bub) "✓ " else "") + "🫧 Botão flutuante"
            for ((b, on) in listOf(bBar to bar, bBub to bub)) {
                b.setTextColor(if (on) 0xFF1A0A10.toInt() else FG)
                b.background = GradientDrawable().apply { setColor(if (on) ACCENT else 0xFF1C1C24.toInt()); cornerRadius = dp(10).toFloat() }
            }
        }
        bBar.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
            PostBar.setBar(this, !PostBar.barOn(this)); keysUi()
        }
        bBub.setOnClickListener {
            if (PostBar.bubbleOn(this) && PostBar.canBubble(this)) { PostBar.setBubble(this, false); keysUi(); return@setOnClickListener }
            if (!PostBar.canBubble(this)) {
                Store.prefs(this).edit().putBoolean("bubble_on", true).apply()
                toast("Ative \"Mostrar sobre outros apps\" pro Post Leve e volte. No Xiaomi, permita também \"Janelas pop-up em segundo plano\".")
                try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
            } else { PostBar.setBubble(this, true); keysUi() }
        }
        keysUi()
        keysRedraw = { keysUi() }
        text("Notificação fixa com 📸 Threads · 🎬 Instagram · 🤖 Produzir. O botão flutuante fica por cima de qualquer app: toque pra abrir o menu, arraste pra mudar de lugar, segure pra esconder. Ele pisca ⏰ quando é hora de postar e mostra quantos faltam hoje.", 12f, MUTED, parent = ck)

        // 🤖 tudo automático (configurações)
        val cau = card("🤖 Tudo automático")
        text("Vídeos novos do Flow entram nesta categoria e já são produzidos:", 12f, MUTED, parent = cau)
        val catNames = listOf("") + Contexts.names(this)
        chips(cau, catNames.map { if (it.isEmpty()) "📥 Sem categoria" else Contexts.label(this, it) }, { Auto.newCat(this).let { n -> if (n.isEmpty()) "📥 Sem categoria" else Contexts.label(this, n) } }) { lab ->
            Auto.setNewCat(this, catNames.firstOrNull { (if (it.isEmpty()) "📥 Sem categoria" else Contexts.label(this, it)) == lab } ?: "")
        }
        text("Limite de pedidos da IA por dia nas automações (o que você pede na mão não conta no limite):", 12f, MUTED, parent = cau)
        chips(cau, listOf("5", "12", "18", "30"), { Auto.aiMax(this).toString() }) { Auto.setAiMax(this, it.toInt()) }
        text("O grátis do Gemini dá uns 20 pedidos por dia: 12 deixa folga pra você usar na mão.", 11f, MUTED, parent = cau)
        text("A IA separa cada vídeo novo do Flow na categoria certa (usa 1 pedido a cada 6 vídeos):", 12f, MUTED, parent = cau)
        chips(cau, listOf("Ligado", "Desligado"), { if (AutoCat.on(this)) "Ligado" else "Desligado" }) { AutoCat.setOn(this, it == "Ligado") }
        text("🗄 Arquivar sozinho o que flopou 2+ vezes (com 1 reteste depois de 14 dias):", 12f, MUTED, parent = cau)
        chips(cau, listOf("Ligado", "Desligado"), { if (Archive.on(this)) "Ligado" else "Desligado" }) { Archive.setOn(this, it == "Ligado") }
        text("😴 Saturação: o que saiu demais ou está caindo descansa sozinho e o app produz/pede conteúdo diferente:", 12f, MUTED, parent = cau)
        chips(cau, listOf("Ligado", "Desligado"), { if (Saturation.on(this)) "Ligado" else "Desligado" }) { Saturation.setOn(this, it == "Ligado") }
        text("🧪 Teste A/B: 1 em cada 4 posts repete uma foto avaliada com legenda nova, pra descobrir se é a foto ou a legenda que bomba:", 12f, MUTED, parent = cau)
        chips(cau, listOf("Ligado", "Desligado"), { if (AB.on(this)) "Ligado" else "Desligado" }) { AB.setOn(this, it == "Ligado") }

        val cap = card("🔗 API do Threads (números exatos)")
        val tvApi = text(ThreadsApi.summary(this), 13f, FG, parent = cap)
        text("Com a API, o app lê de hora em hora as visualizações, curtidas, respostas, reposts e os SEGUIDORES de cada post, dá a nota sozinho e o modelo aprende com números de verdade. Só leitura: não posta nada. O token fica só no seu celular.", 12f, MUTED, parent = cap)
        val etTok = EditText(this).apply { hint = "Token de acesso do Threads"; setSingleLine(true); setTextColor(FG); setHintTextColor(MUTED); textSize = 13f }
        val etSec = EditText(this).apply { hint = "Segredo do app (opcional: deixa o token valer 60 dias)"; setSingleLine(true); setTextColor(FG); setHintTextColor(MUTED); textSize = 13f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        if (!ThreadsApi.on(this)) { cap.addView(etTok, lpMatch()); cap.addView(etSec, lpMatch()) }
        val rap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; cap.addView(rap, lpMatch())
        button(if (ThreadsApi.on(this)) "🔄 Sincronizar agora" else "🔗 Conectar", rap, primary = true, weight = true) {
            tvApi.text = "Conectando…"
            Thread {
                val m = try {
                    if (!ThreadsApi.on(this)) ThreadsApi.connect(this, etTok.text.toString(), etSec.text.toString()) + "\n" + ThreadsApi.sync(this, true)
                    else ThreadsApi.sync(this, true)
                } catch (e: Exception) { "⚠ " + (e.message ?: "erro") }
                ui.post { toast(m.lines().first()); tvApi.text = m + "\n\n" + ThreadsApi.summary(this); if (ThreadsApi.on(this)) { cap.removeView(etTok); cap.removeView(etSec) } }
            }.start()
        }
        if (ThreadsApi.on(this)) button("Desconectar", rap, weight = true) { ThreadsApi.disconnect(this); tvApi.text = "Desconectado. Reabra o app pra colar outro token." }
        button("📖 Como pegar o token (passo a passo)", cap) {
            AlertDialog.Builder(this).setTitle("Token da API do Threads").setMessage(
                "1. Abra developers.facebook.com e entre com o Facebook ligado ao Instagram/Threads.\n" +
                "2. Meus apps › Criar app › caso de uso \"Acessar a API do Threads\".\n" +
                "3. No caso de uso, em Permissões, adicione threads_basic e threads_manage_insights.\n" +
                "4. Em Funções do app › Funções, adicione a sua conta do Threads como Testador do Threads.\n" +
                "5. No app do Threads: Configurações › Conta › Permissões do site › Convites → aceite.\n" +
                "6. Volte ao painel: no caso de uso do Threads, use o Gerador de token de usuário e gere o token da sua conta.\n" +
                "7. Copie o token e cole aqui. (Opcional: em Configurações do app › Básico copie o \"Segredo do app do Threads\" e cole no segundo campo, pra o token valer 60 dias e renovar sozinho.)\n\n" +
                "Os nomes dos menus podem mudar um pouco; se travar em algum passo, me manda print.")
                .setPositiveButton("Abrir o site") { _, _ -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.facebook.com/apps/"))) } catch (e: Exception) { } }
                .setNegativeButton("Fechar", null).show()
        }
        ThreadsApi.topFollowers(this, 3).takeIf { it.isNotEmpty() }?.let { tf ->
            text("👥 Posts que mais trouxeram seguidores:\n" + tf.joinToString("\n") { "+${"%.1f".format(it.optDouble("fol"))} · ${it.optInt("views")} vis. · " + it.optString("text").replace('\n', ' ').take(50) }, 12f, OK, parent = cap)
        }

        val cdg = card("🧪 Autoteste e diagnóstico")
        text("Testa as peças principais (filas, dados, legenda, cenas, produção de um vídeo de 2 s, permissões) e guarda os erros que acontecem. \"Copiar diagnóstico\" copia tudo (sem a sua chave) pra você colar no chat.", 12f, MUTED, parent = cdg)
        val tvDg = text("", 12f, FG, parent = cdg)
        var lastRs: List<SelfTest.R> = emptyList()
        fun errLine() = ErrLog.all(this).let { e -> if (e.isEmpty()) "Nenhum erro registrado." else "${e.size} erro(s) registrado(s); último: " + e[0].optString("w") + " – " + e[0].optString("e").take(80) }
        tvDg.text = errLine()
        val rdg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; cdg.addView(rdg, lpMatch())
        button("🧪 Rodar autoteste", rdg, primary = true, weight = true) {
            tvDg.setTextColor(MUTED); tvDg.text = "Testando…"
            Thread {
                val rs = SelfTest.run(this)
                ui.post {
                    SelfTest.exportTest(this) { r ->
                        ui.post {
                            rs += r; lastRs = rs
                            val bad = rs.count { !it.ok }
                            tvDg.setTextColor(if (bad == 0) OK else ACCENT)
                            tvDg.text = (if (bad == 0) "✓ Tudo funcionando (${rs.size} testes)\n" else "✗ $bad de ${rs.size} com problema\n") +
                                rs.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.name + ": " + it.detail } + "\n\n" + errLine()
                        }
                    }
                }
            }.start()
        }
        button("📋 Copiar diagnóstico", rdg, weight = true) {
            Thread {
                val rs = lastRs.ifEmpty { SelfTest.run(this) }
                val txt = SelfTest.report(this, rs)
                ui.post {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("diagnóstico", txt))
                    toast("Diagnóstico copiado: cole aqui no chat")
                }
            }.start()
        }
        button("Limpar erros", cdg) { ErrLog.clear(this); tvDg.text = errLine() }

        val cbk = card("💾 Backup")
        val tvBk = text("", 12f, MUTED, parent = cbk)
        fun bkT() { tvBk.text = "Toda semana (no plano das 7:40) o app salva legendas, notas, filas, histórico e configurações em Download/PostLeve Backup. Fotos e vídeos ficam no app." +
            (Backup.last(this).takeIf { it > 0 }?.let { "\nÚltimo: " + android.text.format.DateFormat.format("dd/MM HH:mm", it) } ?: "\nAinda não fez nenhum.") }
        bkT()
        val rbk = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; cbk.addView(rbk, lpMatch())
        button("💾 Fazer agora", rbk, primary = true, weight = true) { Thread { val m = try { Backup.run(this) } catch (e: Exception) { "Erro: ${e.message}" }; ui.post { toast(m); bkT() } }.start() }
        button("♻ Restaurar", rbk, weight = true) {
            try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip"), REQ_RESTORE) } catch (e: Exception) { toast("Não abriu o seletor de arquivos") }
        }

        page = pages["Ajustes"]!!
        text("Ao postar vídeo, lembrar de desligar \"Mostrar na grade do perfil\" (feed só com fotos):", 12f, MUTED, parent = cau)
        chips(cau, listOf("Lembrar", "Não lembrar"), { if (Store.prefs(this).getBoolean("ig_grid_hint", true)) "Lembrar" else "Não lembrar" }) { Store.prefs(this).edit().putBoolean("ig_grid_hint", it == "Lembrar").apply() }

        page = pages["Postar"]!!

        // 🕘 histórico de envios + nota manual
        val chi = card("🕘 Envios e notas")
        text("Toda vez que você toca em Postar fica aqui. Se esqueceu de confirmar ou de dar a nota, faz por aqui.", 12f, MUTED, parent = chi)
        val rowH = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chi.addView(rowH, lpMatch())
        button("📝 Dar nota manual", rowH, primary = true, weight = true) { manualRate() }
        val tvWatch = text("", 12f, FG, parent = chi)
        val rowS = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chi.addView(rowS, lpMatch())
        val bNotif = button("", rowS, weight = true) { ThreadsNotif.openAccess(this) }
        val bReader = button("", rowS, weight = true) {
            if (RateWatch.readerOn(this)) toast("Leitor de tela já está ligado ✓")
            else { toast("Ative \"Post Leve\" em Apps instalados/Serviços baixados. Se bloquear: ⋮ › Permitir configurações restritas."); RateWatch.openReader(this) }
        }
        text("A nota automática usa 3 fontes: notificações de curtida, o leitor de tela (lê as curtidas quando você abre seus posts no Threads/Instagram) e a sua média. Se um post passa de 1 h sem nota, o vigia descobre o motivo, conserta o que dá e te pede a nota com prévia.", 11f, MUTED, parent = chi)
        val histBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chi.addView(histBox, lpMatch())
        var histAll = false
        fun histUi() {
            val acc = ThreadsNotif.hasAccess(this); val rd = RateWatch.readerOn(this)
            bNotif.text = (if (acc) "✓" else "✗") + " 🔔 Notificações"; bReader.text = (if (rd) "✓" else "✗") + " 👀 Leitor de tela"
            bNotif.setTextColor(if (acc) OK else ACCENT); bReader.setTextColor(if (rd) OK else ACCENT)
            tvWatch.text = "🧠 Vigia: " + RateWatch.diag(this).ifBlank { "ainda não rodou" }
            histBox.removeAllViews()
            val l = SendLog.all(this)
            val pend = SendLog.pending(this).size
            if (pend > 0) text("⚠ $pend envio(s) sem confirmar nem nota", 13f, ACCENT, parent = histBox)
            if (l.isEmpty()) text("Nada enviado ainda.", 12f, MUTED, parent = histBox)
            val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
            l.take(if (histAll) 60 else 8).forEach { e ->
                val kind = e.optString("kind"); val id = e.optString("id")
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                histBox.addView(row, lpMatch().apply { topMargin = dp(8) })
                row.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1C1C24.toInt())
                    SendLog.thumb(this@MainActivity, if (kind == "video") "video" else "foto", id)?.let { setImageBitmap(it) }
                }, LinearLayout.LayoutParams(dp(46), dp(70)).apply { rightMargin = dp(10) })
                val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                row.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val nota = e.optString("nota")
                val st = when { nota.isNotEmpty() -> Learn.emoji(nota) + " nota dada"; e.optBoolean("ok") -> "✓ postado, sem nota"; else -> "⏳ não confirmado" }
                text("${fmt.format(java.util.Date(e.optLong("at")))} · ${if (kind == "video") "🎬" else "📸"} ${e.optString("app")} · $st" + (if (e.optBoolean("manual")) " · manual" else ""), 11f,
                    if (nota.isEmpty() && !e.optBoolean("ok")) ACCENT else MUTED, parent = side)
                text(e.optString("cap").replace('\n', ' ').ifBlank { "(sem legenda)" }.take(70), 12f, FG, parent = side)
                val rr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                side.addView(rr, lpMatch())
                listOf("top" to "💎", "good" to "🔥", "meh" to "😐", "bad" to "👎").forEach { (k, em) ->
                    button(em, rr, primary = nota == k, weight = true) { SendLog.rate(this, e, k, manual = true); toast("${Learn.emoji(k)} anotado"); histUi(); refresh() }
                }
                if (!e.optBoolean("ok") && nota.isEmpty()) button("✕", rr, weight = true) { PostConfirm.answer(this, if (kind == "video") "video" else kind, id, false)
                    JsonFile.edit(java.io.File(filesDir, "envios.json")) { l2 -> l2.removeAll { it.optLong("at") == e.optLong("at") } }; histUi() }
            }
            if (l.size > 8) button(if (histAll) "Mostrar menos" else "Ver histórico completo (${minOf(l.size, 60)})", histBox) { histAll = !histAll; histUi() }
        }
        histUi(); histRedraw = { histUi() }

        val ch = card("🩺 Saúde das filas")
        val hBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ch.addView(hBox, lpMatch())
        fun healthUi() {
            Thread {
                val issues = try { Health.check(this) } catch (e: Exception) { emptyList() }
                ui.post { healthDraw(hBox, issues) }
            }.start()
        }
        healthDrawFn = { box, issues ->
            box.removeAllViews()
            if (issues.isEmpty()) text("✓ Tudo girando bem: fotos, legendas, vídeos, textos e músicas variados.", 13f, OK, parent = box)
            issues.forEach { i ->
                text("${i.area}: ${i.msg}", 13f, FG, parent = box)
                if (i.fix != null) button("🔄 ${i.fixLabel}", box) { toast(i.fix.invoke(this)); healthRedraw?.invoke() }
            }
            Health.lastFix(this).takeIf { it.isNotBlank() }?.let { text("Último conserto automático:\n$it", 11f, MUTED, parent = box) }
            val af = Archive.photos(this); val av = Archive.videos(this)
            if (af.size + av.size > 0) {
                text("🗄 Arquivo: ${af.size} foto(s) e ${av.size} vídeo(s) que flopparam 2+ vezes (fora da fila; depois de 14 dias voltam 1 vez pra um último teste).", 12f, MUTED, parent = box)
                val ra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; box.addView(ra, lpMatch())
                button("Ver / restaurar", ra, weight = true) {
                    val items = af.map { "📸 " + PostQueue.captionOf(it).replace('\n', ' ').take(40) to { Archive.restorePhoto(this, it.optString("id")) } } +
                        av.map { "🎬 " + it.optString("texto").replace('\n', ' ').ifBlank { "vídeo sem texto" }.take(40) to { Archive.restoreVideo(this, it.optString("id")) } }
                    AlertDialog.Builder(this).setTitle("Toque pra devolver à fila").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second(); toast("Voltou pra fila"); healthRedraw?.invoke() }.show()
                }
                button("🗑 Apagar arquivo", ra, weight = true) {
                    AlertDialog.Builder(this).setTitle("Apagar ${af.size + av.size} itens arquivados?").setMessage("Somem de vez (foto/vídeo e notas). Não dá pra desfazer.")
                        .setPositiveButton("Apagar") { _, _ -> toast("${Archive.deleteAll(this)} apagado(s)"); healthRedraw?.invoke() }.setNegativeButton("Cancelar", null).show()
                }
            }
        }
        healthUi(); healthRedraw = { healthUi() }
        chips(ch, listOf("🤖 Conserta sozinho", "Só avisar"), { if (Health.autoOn(this)) "🤖 Conserta sozinho" else "Só avisar" }) { Health.setAuto(this, it.startsWith("🤖")) }
        text("Código simples (sem IA, não gasta crédito). Confere todas as filas ao abrir o app e depois de cada post: se algo repete, gira a fila puxando o que está há mais tempo parado; limpa itens quebrados e destrava legendas.", 11f, MUTED, parent = ch)

        val cp = card("Fila de posts")
        text("Adicione fotos: cada uma sai sem metadados e ganha legendas. Toque em Postar e o Threads abre com a foto e a legenda juntas. É só publicar.", 13f, MUTED, parent = cp)
        button("＋ Adicionar fotos pra postar", cp, primary = true) { pickPosts() }
        button("🎲 Postar uma aleatória agora", cp) {
            val pk = Captions.randomPick(this)
            if (pk == null) toast("A fila está vazia")
            else startActivity(Intent(this, PostActivity::class.java).putExtra("id", pk.first).putExtra("caption", pk.second))
        }
        text("Lembrete pra postar: a notificação já vem com foto e legenda sorteadas. Toque em Postar e depois em Publicar no Threads.", 12f, MUTED, parent = cp)
        promoUi()
        everyRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cp.addView(everyRow, lpMatch())
        listOf(Triple(0, 0, "Off"), Triple(15, 15, "15 em 15"), Triple(15, 30, "15–30m"),
            Triple(30, 50, "30–50m"), Triple(60, 120, "1–2h"), Triple(120, 180, "2–3h")).chunked(3).forEach { row ->
            val rr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            everyRow.addView(rr, lpMatch())
            row.forEach { (mn, mx, t) ->
                button(t, rr, weight = true) { Reminder.setRange(this, mn, mx); styleEvery(); refresh()
                    toast(when { mn == 0 -> "Lembrete desligado"; mn == mx -> "Vou avisar a cada $mn minutos"; else -> "Vou avisar num horário diferente a cada vez, entre $t" }) }.tag = "$mn-$mx"
            }
        }
        // 👯 modo rajada
        text("👯 Postar em blocos (o intervalo acima vira a pausa entre os blocos)", 13f, FG, parent = cp, bold = true)
        val tvBurst = text("", 12f, MUTED, parent = cp)
        fun burstT() {
            val n = Burst.size(this)
            tvBurst.text = (if (Burst.auto(this)) "🤖 Automático, agora em: ${Burst.label(n)}. " else "") +
                (if (n > 1) "Posta ${Burst.label(n)}: ${n} posts com ${Burst.gap(this).first}–${Burst.gap(this).second} min entre eles (foto e legenda de tipos diferentes), depois espera o intervalo." else "Um post por vez.") +
                Burst.note(this).takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() + "\n" + Burst.report(this)
        }
        chips(cp, listOf("1 por vez", "👯 Dupla", "3️⃣ Trio", "🤖 Auto"), { if (Burst.auto(this)) "🤖 Auto" else when (Burst.size(this)) { 2 -> "👯 Dupla"; 3 -> "3️⃣ Trio"; else -> "1 por vez" } }) { o ->
            when (o) {
                "🤖 Auto" -> Burst.setSize(this, maxOf(2, Burst.size(this)), true, "🤖 Começando em ${Burst.label(maxOf(2, Burst.size(this)))}: a cada 3 dias comparo 1º × último do bloco e ajusto")
                "👯 Dupla" -> Burst.setSize(this, 2, false, "")
                "3️⃣ Trio" -> Burst.setSize(this, 3, false, "")
                else -> Burst.setSize(this, 1, false, "")
            }
            burstT(); refresh()
        }
        text("Tempo entre os posts do bloco:", 12f, MUTED, parent = cp)
        chips(cp, listOf("5–10 min", "10–15 min", "15–25 min"), { Burst.gap(this).let { "${it.first}–${it.second} min" } }) { o ->
            val (a, b) = o.removeSuffix(" min").split("–").map { it.toInt() }; Burst.setGap(this, a, b); burstT()
        }
        burstT()

        val day = button("", cp) { }
        fun dayText() { day.text = if (Reminder.dayOnly(this)) "Só entre 8h e 23h: ligado" else "Só entre 8h e 23h: desligado" }
        dayText()
        day.setOnClickListener { Store.prefs(this).edit().putBoolean("post_day_only", !Reminder.dayOnly(this)).apply(); Reminder.schedule(this); dayText(); refresh() }
        button("Sortear horários de hoje pra agendar no Threads", cp) {
            val plan = Reminder.planToday(this)
            if (plan.isEmpty()) toast("Escolha uma faixa de lembrete primeiro (ou já passou das 23h)")
            else AlertDialog.Builder(this).setTitle("Horários pra agendar hoje")
                .setMessage(plan.joinToString("\n") { android.text.format.DateFormat.format("HH:mm", it).toString() } +
                    "\n\nNo Threads: três pontinhos › Agendar, um post pra cada horário. Ele publica sozinho.")
                .setPositiveButton("Ok", null).show()
        }
        val gal = button("", cp) { }
        fun galText() { gal.text = if (PostQueue.galleryOn(this)) "Foto em primeiro na galeria: ligado" else "Foto em primeiro na galeria: desligado" }
        galText()
        gal.setOnClickListener {
            val on = !PostQueue.galleryOn(this)
            Store.prefs(this).edit().putBoolean("gallery_first", on).apply()
            if (!on) Thread { PostQueue.cleanGallery(this, true) }.start()
            galText()
        }
        text("Ao postar, a foto vai pra galeria (pasta PostLeve) em primeiro lugar. A cópia anterior é apagada, então nunca acumula duplicada.", 12f, MUTED, parent = cp)
        tvPostInfo = text("", 13f, OK, parent = cp)
        // pasta do Flow
        text("Pasta automática (ex.: Flow)", 12f, MUTED, parent = cp)
        btnFolder = button("", cp) { askFolder() }
        btn2k = button("", cp) { FolderScan.setOnly2k(this, !FolderScan.only2k(this)); updateFolderUi() }
        btnFlowNames = button("", cp) { FolderScan.setFlowNames(this, !FolderScan.flowNames(this)); updateFolderUi() }
        btnVideos = button("", cp) {
            FolderScan.setVideosOn(this, !FolderScan.videosOn(this))
            if (FolderScan.videosOn(this) && !FolderScan.hasVideoPerm(this)) requestPermissions(FolderScan.perms, 22)
            updateFolderUi()
        }
        btnExisting = button("", cp) { importExisting() }
        btnCleanDl = button("", cp) { cleanDownloads() }
        text("Escolha a pasta Download. Cada foto do Flow que cair lá entra sozinha na fila, sem metadados, e vai pra pasta \"PostLeve Flow\" da galeria. Depois você apaga as do Download com um toque. Foto que não era pra estar: toque em Apagar nela, que some da fila e da pasta.", 12f, MUTED, parent = cp)
        updateFolderUi()

        postList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cp.addView(postList, lpMatch())
        styleEvery()

        // ===== Legendas
        page = pages["Legendas"]!!
        buildCaptionsTab()

        // ===== Instagram
        page = pages["Instagram"]!!
        buildInstaTab()

        // ===== Analisar
        page = pages["Analisar"]!!
        val c1 = card("Prints de perfis")
        btnBubble = button("Ligar bolha de print", c1, primary = true) { toggleBubble() }
        text("Toque na bolha = 1 print. Segure a bolha = modo automático (você rola o perfil devagar e ele tira os prints sozinho, sem repetir). Arraste pra mudar de lugar.", 13f, MUTED, parent = c1)
        button("Adicionar prints da galeria", c1) { pickGallery() }
        button("Analisar fila agora", c1) { LeveService.send(this, LeveService.ACT_ANALYZE); toast("Analisando em segundo plano") }
        button("Testar IA", c1) { testAi() }

        // resultado
        val c2 = card("O que deu mais certo")
        tvPat = text("", 14f, FG, parent = c2)
        tvTop = text("", 14f, FG, parent = c2)
        button("Atualizar padrões com IA", c2) { refreshPatterns() }

        button("Levar as melhores pro banco de legendas", c2) {
            val n = Captions.pullFromAnalysis(this)
            toast(if (n > 0) "$n legenda(s) entraram no banco viral" else "O banco de prints ainda está vazio")
            renderRefs()
        }

        // ===== Refs
        page = pages["Refs"]!!
        refs = RefsTab(this)
        refs.build()

        // ===== Ajustes
        page = pages["Ajustes"]!!
        val cg = card("Celular limpo")
        text("O Post Leve guarda tudo dentro dele. O que precisa ir pra galeria (foto pro Flow, foto do post) é temporário e some sozinho.", 13f, MUTED, parent = cg)
        val fc = button("", cg) { }
        fun fcT() { fc.text = if (Store.prefs(this).getBoolean("flow_copy", false)) "Copiar fotos do Flow pra galeria: ligado" else "Copiar fotos do Flow pra galeria: desligado" }
        fcT()
        fc.setOnClickListener { Store.prefs(this).edit().putBoolean("flow_copy", !Store.prefs(this).getBoolean("flow_copy", false)).apply(); fcT() }
        val purge = button("🧹 Apagar da galeria as cópias antigas do app", cg) { }
        purge.setOnClickListener {
            if (purge.tag != "armed") { purge.tag = "armed"; purge.text = "Toque de novo pra apagar (nada some do app)"; ui.postDelayed({ purge.tag = null; purge.text = "🧹 Apagar da galeria as cópias antigas do app" }, 3000) }
            else { purge.tag = null; purge.text = "Apagando…"
                Thread { val n = GalleryClean.purgeAll(this); ui.post { purge.text = "🧹 Apagar da galeria as cópias antigas do app"; toast("$n cópia(s) apagada(s) da galeria. Tudo continua no app.") } }.start() }
        }
        text("Apaga só as cópias que o app criou (pastas PostLeve, PostLeve Ref, PostLeve Flow, músicas). Seus esboços, vídeos prontos, músicas e fotos de referência continuam no app.", 12f, MUTED, parent = cg)
        text("📋 Apagar a legenda copiada (área de transferência) depois de", 12f, MUTED, parent = cg)
        chips(cg, listOf("2 min", "10 min", "30 min", "Nunca"), { ClipSync.ttl(this).let { if (it == 0) "Nunca" else "$it min" } }) { o ->
            Store.prefs(this).edit().putInt("clip_ttl", if (o == "Nunca") 0 else o.substringBefore(' ').toInt()).apply()
        }
        button("🧹 Apagar do Leve Teclado as legendas já usadas", cg) { toast("Pedi pro teclado apagar ${ClipSync.forgetRecent(this)} legenda(s) do histórico") }
        text("Com o Leve Teclado, a legenda também some do histórico do teclado no tempo que você escolher lá. E no Threads, tudo que você copia vai sozinho pro banco de legendas (ligue em Leve Teclado › Post Leve).", 12f, MUTED, parent = cg)

        val ctn = card("🔕 Notificações do Threads")
        text("Some com as notificações de curtida pequenas. Só aparece quando um post passa do limite, e aí o Post Leve avisa com som e marca o post como 🔥 sozinho.", 13f, MUTED, parent = ctn)
        val tnBtn = button("", ctn) { }
        val tnSt = text("", 12f, OK, parent = ctn)
        val tnLog = text("", 11f, MUTED, parent = ctn)
        fun tnT() {
            val acc = ThreadsNotif.hasAccess(this); val on = ThreadsNotif.on(this) && acc
            tnBtn.text = if (!acc) "1. Liberar acesso às notificações" else if (on) "✓ Filtro ligado (toque pra desligar)" else "Ligar o filtro"
            tnSt.setTextColor(if (acc && ThreadsNotif.live(this)) OK else ACCENT)
            tnSt.text = when {
                !acc -> "✗ Acesso às notificações NÃO liberado (veja os 3 pontinhos ⋮ em Informações do app › Permitir configurações restritas)."
                !ThreadsNotif.live(this) -> "⚠ Acesso liberado, mas o serviço não está conectado. Toque em 🔄 Reconectar."
                on -> "✓ Conectado. Hoje: ${ThreadsNotif.silencedToday(this)} notificação(ões) silenciada(s)"
                else -> "Conectado, filtro desligado."
            }
            tnLog.text = if (acc) "Últimas do Threads:\n" + ThreadsNotif.logText(this) else ""
        }
        val tnR = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctn.addView(tnR, lpMatch())
        button("🔄 Reconectar", tnR, weight = true) { ThreadsNotif.rebind(this); ui.postDelayed({ tnT() }, 1500); toast("Reconectando o filtro…") }
        button("↻ Atualizar", tnR, weight = true) { tnT() }
        tnBtn.setOnClickListener {
            if (!ThreadsNotif.hasAccess(this)) { ThreadsNotif.setOn(this, true); toast("Ative o Post Leve em \"Acesso a notificações\" e volte"); ThreadsNotif.openAccess(this) }
            else { ThreadsNotif.setOn(this, !ThreadsNotif.on(this)); tnT() }
        }
        tnT(); tnRedraw = { tnT() }
        text("Aparecer só com", 12f, MUTED, parent = ctn)
        chips(ctn, listOf("10", "20", "50", "100", "500"), { ThreadsNotif.min(this).toString() }) { ThreadsNotif.setMin(this, it.toInt()) }
        ThreadsNotif.suggestedMin(this, "fila")?.let { s -> button("Usar o sugerido pelo seu histórico: $s", ctn) { ThreadsNotif.setMin(this, s); toast("Limite do Threads: $s") } }
        text("curtidas ou mais", 12f, MUTED, parent = ctn)
        text("O que silenciar", 12f, MUTED, parent = ctn)
        chips(ctn, listOf("Só curtidas", "Tudo do Threads"), { if (ThreadsNotif.mode(this) == "all") "Tudo do Threads" else "Só curtidas" }) { ThreadsNotif.setMode(this, if (it == "Tudo do Threads") "all" else "likes") }
        text("📸 Threads: passou do limite = 🔥 automático (5x o limite = 💎). Lê as curtidas mesmo com o silêncio desligado. Se a notificação não mostrar a legenda e você postou várias fotos em 48 h, o app pergunta qual foi.", 12f, MUTED, parent = ctn)
        chips(ctn, listOf("Ligado", "Desligado"), { if (ThreadsNotif.autoRate(this)) "Ligado" else "Desligado" }) { ThreadsNotif.setAutoRate(this, it == "Ligado") }
        button("2. Deixar o Threads sem som (recomendado)", ctn) { ThreadsNotif.openThreadsSettings(this) }
        text("📸 Instagram: Reel que passa de", 12f, MUTED, parent = ctn)
        chips(ctn, listOf("50", "100", "300", "1000"), { ThreadsNotif.igMin(this).toString() }) { ThreadsNotif.setIgMin(this, it.toInt()) }
        text("curtidas vira 🔥 sozinho (5x isso = 💎). Se postou vários vídeos em 48 h, o app pergunta qual foi.", 11f, MUTED, parent = ctn)
        chips(ctn, listOf("Nota automática ligada", "Desligada"), { if (ThreadsNotif.igRate(this)) "Nota automática ligada" else "Desligada" }) { ThreadsNotif.setIgRate(this, it.startsWith("Nota")) }
        chips(ctn, listOf("Silenciar curtidas pequenas do Insta", "Não silenciar"), { if (ThreadsNotif.igSilence(this)) "Silenciar curtidas pequenas do Insta" else "Não silenciar" }) { ThreadsNotif.setIgSilence(this, it.startsWith("Silenciar")) }

        val cap0 = card("🤖 Piloto automático")
        text("Todo dia às 7:40 o app recupera o que sumiu, puxa fotos e vídeos novos do Flow, gira as filas, produz os vídeos que faltam e te manda o ☀️ Plano de hoje (meta, filas, melhor horário e como foi ontem).", 13f, MUTED, parent = cap0)
        chips(cap0, listOf("Ligado", "Desligado"), { if (Daily.autopilot(this)) "Ligado" else "Desligado" }) { Daily.setAutopilot(this, it == "Ligado") }
        button("☀️ Ver o plano de hoje agora", cap0) { Thread { Daily.morning(this) }.start(); toast("Montando o plano…") }
        text("⏰ Lembrar mais nos melhores horários", 12f, MUTED, parent = cap0)
        chips(cap0, listOf("Ligado", "Desligado"), { if (Learn.bestHoursOn(this)) "Ligado" else "Desligado" }) { Learn.setBestHours(this, it == "Ligado"); Reminder.schedule(this); Daily.scheduleReminder(this) }
        text("Usa o histórico de notas: no horário que mais bomba os lembretes vêm mais seguidos; no que flopa, mais espaçados." +
            (Learn.bestBucket(this)?.let { " Hoje o melhor é: $it." } ?: " (Precisa de algumas notas pra saber.)"), 11f, MUTED, parent = cap0)
        text("O filtro apaga a notificação, mas o som do Threads pode tocar antes. Deixe as notificações do Threads silenciosas: quem toca é o aviso do Post Leve quando bomba. O 🔥 automático só funciona quando a notificação mostra um trecho da legenda.", 11f, MUTED, parent = ctn)

        val cu = card("Atualizações")
        text("Versão instalada: ${Updater.mineName(this)}", 13f, FG, parent = cu)
        val au = button("", cu) { }
        fun auT() { au.text = if (Updater.autoOn(this)) "Atualização automática: ligada" else "Atualização automática: desligada" }
        auT()
        au.setOnClickListener { Updater.setAuto(this, !Updater.autoOn(this)); auT() }
        button("Procurar atualização agora", cu, primary = true) { Updater.checkNow(this) }
        text("Ligada, o app confere sozinho no GitHub e avisa quando sair versão nova. Um toque baixa e abre o instalador; o Android sempre pede a sua confirmação pra instalar.", 12f, MUTED, parent = cu)

        val c4 = card("Configurações da IA")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        c4.addView(row, lpMatch())
        btnClaude = button("Claude", row, weight = true) { setProvider("claude") }
        btnGemini = button("Gemini", row, weight = true) { setProvider("gemini") }
        val p = Store.prefs(this)
        field("Chave da API", p.getString("key", "") ?: "", c4, password = true) { p.edit().putString("key", it.trim()).apply() }
        etModel = field("Modelo (vazio = padrão; vários separados por vírgula)", p.getString("model", "") ?: "", c4) { p.edit().putString("model", it.trim()).apply() }
        field("Modelo de imagem (🧽 remover objeto; vazio = gemini-2.5-flash-image)", p.getString("img_model", "") ?: "", c4) { p.edit().putString("img_model", it.trim()).apply() }
        field("Prints por lote (1 a 8)", p.getInt("batch", 4).toString(), c4, number = true) {
            p.edit().putInt("batch", (it.toIntOrNull() ?: 4).coerceIn(1, 8)).apply()
        }
        text("A chave fica só neste celular. Claude: console.anthropic.com. Gemini: aistudio.google.com (tem cota grátis).", 12f, MUTED, parent = c4)
        setProvider(Ai.provider(this), save = false)

        // dicas Xiaomi
        val c5 = card("Se parar sozinho (Xiaomi)")
        text("Configurações › Apps › Post Leve › Economia de bateria: Sem restrições. Ative também \"Exibir janelas pop-up enquanto em segundo plano\" e \"Início automático\".", 13f, MUTED, parent = c5)
        button("Abrir configurações do app", c5) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        val zero = button("Zerar banco", c5) { }
        zero.setOnClickListener {
            if (!clearArmed) { clearArmed = true; zero.text = "Toque de novo pra apagar tudo"; ui.postDelayed({ clearArmed = false; zero.text = "Zerar banco" }, 3000) }
            else { Store.clearAll(this); clearArmed = false; zero.text = "Zerar banco"; toast("Banco zerado"); refresh() }
        }
        showTab((Store.prefs(this).getString("tab", "Postar") ?: "Postar").let { if (it == "Analisar") "Postar" else it })
    }

    private var asking = false
    /** Na volta do Instagram/Threads: "Publicou?" — só marca como postado se você confirmar. */
    private fun askPending() {
        if (asking || isFinishing) return
        val p = PostConfirm.first(this) ?: return
        asking = true
        val kind = p.optString("kind"); val id = p.optString("id")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        SendLog.thumb(this, if (kind == "video") "video" else "foto", id)?.let { b ->
            box.addView(ImageView(this).apply { setImageBitmap(b); scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(-1, dp(220)))
        }
        text(p.optString("cap").take(140).ifBlank { "(sem legenda)" }, 13f, FG, parent = box)
        var dlg: AlertDialog? = null
        val opts = listOf("✓ Postei" to { PostConfirm.answer(this, kind, id, true) },
            "💎 Postei e viralizou" to { PostConfirm.answerRated(this, kind, id, "top") },
            "🔥 Postei e bombou" to { PostConfirm.answerRated(this, kind, id, "good") },
            "👎 Postei e flopou" to { PostConfirm.answerRated(this, kind, id, "bad") },
            "✕ Não postei" to { PostConfirm.answer(this, kind, id, false) })
        opts.forEach { (t, f) -> button(t, box, primary = t.startsWith("✓")) { f(); dlg?.dismiss(); after() } }
        dlg = AlertDialog.Builder(this).setTitle("Publicou ${PostConfirm.label(p)}?").setView(ScrollView(this).apply { addView(box) })
            .setNeutralButton("Depois", null)
            .setOnDismissListener { asking = false }
            .show()
    }
    private fun after() {
        postStamp = -1L; refresh()
        if (::insta.isInitialized) insta.refreshLists()
        QuickPost.updateWidgets(this)
        ui.postDelayed({ askPending() }, 400)
    }

    private fun showTab(name: String) {
        Store.prefs(this).edit().putString("tab", name).apply()
        if (name == "Legendas") { renderLearn(); renderMine(); renderRefs() }
        if (name == "Limpar" && ::meta.isInitialized) meta.renderHist()
        pages.forEach { (k, v) -> v.visibility = if (k == name) View.VISIBLE else View.GONE }
        for (i in 0 until tabRow.childCount) {
            val b = tabRow.getChildAt(i) as Button
            val on = b.tag == name
            b.setTextColor(if (on) 0xFF1A0A10.toInt() else FG)
            b.background = GradientDrawable().apply { setColor(if (on) ACCENT else 0xFF1C1C24.toInt()); cornerRadius = dp(10).toFloat() }
        }
    }

    // ------------------------------------------------------------------ aba Instagram

    private fun buildInstaTab() {
        insta = InstaEditor(this)
        insta.build()
        val cf = card("Foto pro Instagram")
        text("Foto direto no feed, sem metadados. A legenda vai copiada.", 13f, MUTED, parent = cf)
        button("Escolher foto", cf) { pickIgPhoto() }
        button("🎲 Foto aleatória da fila", cf) {
            val pk = Captions.randomPick(this, promo = false)
            if (pk == null) toast("A fila está vazia") else postPhotoIg(pk.first, pk.second)
        }
    }

    private fun pickIgPhoto() = pickImages(REQ_IGPHOTO, false)

    private fun copyCaption(t: String) {
        if (t.isBlank()) return
        ClipSync.copy(this, t)
        Captions.addMine(this, t)
    }

    private fun postPhotoIg(id: String, cap: String) {
        copyCaption(cap)
        PostConfirm.start(this, "fotoIg", id, cap, "Instagram")
        toast("Legenda copiada: no Instagram é só colar e postar")
        startActivity(VideoExport.shareToInstagram(this, PostQueue.uri(id), "image/jpeg"))
    }

    // ------------------------------------------------------------------ aba Legendas

    // ------------------------------------------------------------------ 📣 divulgação do Instagram
    private var promoRedraw: () -> Unit = {}
    private fun promoUi() {
        val cpr = card("📣 Divulgação do Instagram")
        text("Entre os posts do Threads, de vez em quando sai uma foto com o seu @ do Instagram pedindo pra seguir. A legenda vem de um banco só de divulgação que aprende com a nota e com os SEGUIDORES que cada uma trouxe: a melhor vira a \"legenda perfeita\".", 12f, MUTED, parent = cpr)
        val tvSt = text("", 13f, OK, parent = cpr, bold = true)
        text("📸 Seu @ do Instagram (o que vai na imagem e na legenda):", 13f, FG, parent = cpr, bold = true)
        val rh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }; cpr.addView(rh, lpMatch())
        val et = EditText(this).apply {
            hint = "ex.: seuperfil"; setText(Macro.igHandle(this@MainActivity)); setSingleLine(true); setTextColor(FG); setHintTextColor(MUTED); textSize = 16f
            setPadding(dp(14), dp(10), dp(14), dp(10)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = GradientDrawable().apply { setColor(0xFF1C1C24.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(1), ACCENT) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { Macro.setIgHandle(this@MainActivity, s?.toString().orEmpty().trim().removePrefix("@").substringAfterLast("instagram.com/").trim('/')); promoRedraw() }
            })
        }
        rh.addView(TextView(this).apply { text = "@"; textSize = 18f; setTextColor(MUTED); setPadding(0, 0, dp(6), 0) })
        rh.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        button("✓", rh) { Macro.setIgHandle(this, et.text.toString()); hideKeyboard(et); toast(if (Promo.handle(this).isEmpty()) "Escreva seu @" else "@${Promo.handle(this)} salvo"); promoRedraw() }
        chips(cpr, listOf("📣 Ligada", "Desligada"), { if (Promo.on(this)) "📣 Ligada" else "Desligada" }) { Promo.setOn(this, it.startsWith("📣")); promoRedraw() }
        text("Frequência:", 12f, FG, parent = cpr)
        chips(cpr, listOf("1 a cada 4", "1 a cada 6", "1 a cada 8", "1 a cada 10"), { "1 a cada " + Promo.every(this) }) { Promo.setEvery(this, it.substringAfterLast(' ').toInt()); promoRedraw() }
        chips(cpr, listOf("Até 1 por dia", "Até 2 por dia", "Até 3 por dia", "Até 4 por dia"), { "Até ${Promo.perDay(this)} por dia" }) { Promo.setPerDay(this, it.split(' ')[1].toInt()); promoRedraw() }
        text("Imagem:", 12f, FG, parent = cpr)
        val imgOpts = listOf("melhores" to "🏆 Melhores fotos + selo", "minhas" to "🖼 Minhas imagens", "misturar" to "🔀 Misturar (aprende)")
        chips(cpr, imgOpts.map { it.second }, { imgOpts.first { it.first == Promo.imgMode(this) }.second }) { v -> Promo.setImgMode(this, imgOpts.first { it.second == v }.first) }
        text("Na legenda:", 12f, FG, parent = cpr)
        val lkOpts = listOf("link" to "🔗 instagram.com/…", "arroba" to "@ no texto", "imagem" to "Só na imagem")
        chips(cpr, lkOpts.map { it.second }, { lkOpts.first { it.first == Promo.linkMode(this) }.second }) { v -> Promo.setLinkMode(this, lkOpts.first { it.second == v }.first); promoRedraw() }
        val rb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; cpr.addView(rb, lpMatch())
        button("👁 Prévia", rb, weight = true) { promoPreview() }
        button("📣 Postar agora", rb, primary = true, weight = true) {
            Macro.setIgHandle(this, et.text.toString())
            Thread { val pk = Promo.now(this); ui.post {
                if (pk == null) toast("Coloque seu @ do Instagram e tenha fotos na fila (ou imagens suas)")
                else startActivity(Intent(this, PostActivity::class.java).putExtra("id", pk.first).putExtra("caption", pk.second))
            } }.start()
        }
        val rb2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; cpr.addView(rb2, lpMatch())
        val bImgs = button("", rb2, weight = true) { pickImages(REQ_PROMO, true) }
        button("✨ Legendas (IA)", rb2, weight = true) {
            toast("Criando legendas de divulgação…")
            Thread { val m = try { "✓ ${Promo.generate(this, 8)} legendas novas" } catch (e: Exception) { "Erro: ${e.message}" }; ui.post { toast(m); promoRedraw() } }.start()
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; cpr.addView(box, lpMatch())
        promoRedraw = {
            tvSt.text = Promo.status(this); tvSt.setTextColor(if (Promo.handle(this).isEmpty()) ACCENT else OK)
            bImgs.text = "🖼 Minhas imagens (${Promo.bases(this).size})"
            box.removeAllViews()
            val pf = Promo.perfect(this)
            if (pf != null) text("🏆 Legenda perfeita até agora:\n\"" + Promo.fill(this, pf.optString("t")) + "\"\n" + promoStats(pf), 13f, FG, parent = box, bold = true)
            else text("🏆 A legenda perfeita aparece depois das primeiras divulgações com nota/seguidores.", 12f, MUTED, parent = box)
            val l = Promo.caps(this).sortedByDescending { Promo.score(it) }
            if (l.isNotEmpty()) text("Banco de divulgação (${l.size}), da melhor pra pior:", 12f, MUTED, parent = box)
            l.take(6).forEach { o -> text("• " + Promo.fill(this, o.optString("t")).replace('\n', ' ') + "  —  " + promoStats(o), 12f, MUTED, parent = box) }
        }
        promoRedraw()

        val cg = card("📈 Vigia do crescimento")
        val tvG = text("", 13f, FG, parent = cg)
        val tvR = text("", 12f, ACCENT, parent = cg)
        chips(cg, listOf("🤖 Ajustar a divulgação sozinho", "Eu ajusto"), { if (Growth.autoTune(this)) "🤖 Ajustar a divulgação sozinho" else "Eu ajusto" }) { Growth.setAutoTune(this, it.startsWith("🤖")) }
        text("Todo dia às 21h chega o resumo. Com 4+ divulgações medidas, o app aumenta a frequência quando ela traz bem mais seguidores que um post normal e diminui quando traz menos. A IA usa esses números ao criar legendas.", 11f, MUTED, parent = cg)
        val rg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; cg.addView(rg, lpMatch())
        fun gUi() { Thread { val r = try { Growth.report(this) } catch (e: Exception) { null }; ui.post {
            tvG.text = r?.lines?.joinToString("\n\n") ?: "Sem dados ainda."
            tvR.text = r?.recs?.joinToString("\n") { "• $it" }.orEmpty(); tvR.visibility = if (r?.recs.isNullOrEmpty()) View.GONE else View.VISIBLE
        } }.start() }
        button("↻ Atualizar", rg, weight = true) { gUi() }
        button("📋 Copiar", rg, weight = true) { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("crescimento", tvG.text.toString() + "\n" + tvR.text)); toast("Copiado") }
        gUi()
    }

    private fun hideKeyboard(v: View) = try { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) } catch (e: Exception) { false }

    private fun promoStats(o: org.json.JSONObject): String {
        val n = Promo.tested(o)
        if (n == 0 && o.optInt("uses") == 0) return "nova"
        return "${(Promo.score(o) * 100).toInt()} pts · usada ${o.optInt("uses")}x" +
            (if (o.optInt("top") + o.optInt("good") > 0) " · 🔥${o.optInt("good") + o.optInt("top")}" else "") + (if (o.optInt("bad") > 0) " · 👎${o.optInt("bad")}" else "") +
            (if (o.optInt("folN") > 0) " · 👥 " + "%+.1f".format(o.optDouble("folRaw") / o.optInt("folN")) + " vs normal" else "")
    }

    private fun promoPreview() {
        Thread {
            val r = try { Promo.make(this, save = false) } catch (e: Exception) { null }
            ui.post {
                if (r == null) { toast("Coloque seu @ do Instagram e tenha fotos na fila"); return@post }
                val (bmp, m) = r
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(6)) }
                box.addView(android.widget.ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true }, LinearLayout.LayoutParams(-1, dp(380)))
                text("Legenda: " + m.caption, 13f, FG, parent = box)
                text("Estilo: " + m.style.substringBefore(':') + " · cada vez sai uma combinação diferente", 11f, MUTED, parent = box)
                AlertDialog.Builder(this).setTitle("👁 Prévia da divulgação").setView(ScrollView(this).apply { addView(box) })
                    .setPositiveButton("Outra") { _, _ -> promoPreview() }.setNegativeButton("Fechar", null).show()
            }
        }.start()
    }

    private fun importPromo(uris: List<Uri>) {
        Thread {
            var n = 0
            uris.forEach { u -> try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
                var smp = 1; while (maxOf(o.outWidth, o.outHeight) / (smp * 2) >= 2160) smp *= 2
                val b = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = smp }) } ?: return@forEach
                java.io.File(Promo.baseDir(this), "b" + System.currentTimeMillis().toString(36) + n + ".jpg").outputStream().use { rotateByExif(u, b).compress(Bitmap.CompressFormat.JPEG, 94, it) }
                n++
            } catch (e: Exception) { } }
            ui.post { toast("$n imagem(ns) de divulgação salvas (sem metadados)"); promoRedraw() }
        }.start()
    }

    internal fun chips(parent: LinearLayout, options: List<String>, current: () -> String, pick: (String) -> Unit): () -> Unit {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(wrap, lpMatch())
        fun draw() {
            wrap.removeAllViews()
            options.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                wrap.addView(row, lpMatch())
                pair.forEach { opt ->
                    val b = button(opt, row, weight = true) { pick(opt); draw() }
                    b.textSize = 13f
                    val on = opt == current()
                    b.setTextColor(if (on) 0xFF1A0A10.toInt() else FG)
                    b.background = GradientDrawable().apply { setColor(if (on) ACCENT else 0xFF1C1C24.toInt()); cornerRadius = dp(10).toFloat() }
                }
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
        }
        draw()
        return { draw() }
    }

    private fun buildCaptionsTab() {
        val p = Store.prefs(this)
        val cv = card("Estilo das legendas")
        text("Vibe", 12f, MUTED, parent = cv)
        chips(cv, Captions.VIBES, { Captions.vibe(this) }) { p.edit().putString("vibe", it).apply() }
        text("Tamanho", 12f, MUTED, parent = cv)
        chips(cv, Captions.SIZES, { Captions.size(this) }) { p.edit().putString("tam", it).apply() }
        text("Final pra puxar interação", 12f, MUTED, parent = cv)
        chips(cv, listOf("Variado", "Sem final", "Fixo"), { Captions.ctaMode(this) }) { p.edit().putString("cta_mode", it).apply() }
        field("Final fixo (quando escolher Fixo)", p.getString("cta_fixed", "gostou, amor?") ?: "", cv) { p.edit().putString("cta_fixed", it.trim()).apply() }
        field("Contexto (opcional: praia, sexta-feira, look novo…)", p.getString("ctx", "") ?: "", cv) { p.edit().putString("ctx", it.trim()).apply() }

        val cia = card("Legendas da fila (Threads) com IA")
        text("Sem IA, cada foto nova já entra com legendas do seu banco (as suas + as que bombaram). A IA só roda quando você pedir, e não gasta crédito à toa.", 13f, MUTED, parent = cia)
        chips(cia, listOf("Só quando eu pedir", "Automático"), { if (Captions.aiAuto(this)) "Automático" else "Só quando eu pedir" }) {
            Captions.setAiAuto(this, it == "Automático")
            if (it != "Automático") Thread { Captions.fillFromBank(this); ui.post { postStamp = -1L; refresh() } }.start()
        }
        text("✨ Gerar legendas com IA agora para:", 12f, MUTED, parent = cia)
        val gr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cia.addView(gr, lpMatch())
        listOf(2, 3, 5, 0).forEach { n ->
            button(if (n == 0) "Todas" else "$n fotos", gr, weight = true) {
                val k = Captions.requestAi(this, n)
                if (k > 0) { LeveService.send(this, LeveService.ACT_ANALYZE); toast("A IA vai gerar legendas pra $k foto(s)") } else toast("Todas as fotos já têm legenda de IA")
                postStamp = -1L; refresh()
            }
        }

        val cs = card("O que está funcionando")
        tvLearn = text("", 13f, FG, parent = cs)
        text("Marque 💎 / 🔥 / 😐 / 👎 nos posts (o app também pergunta 5 h depois de cada post). 💎 Viralizou = deu MUITO certo, vale por dois 🔥. O que bombou sai MUITO mais (foto, legenda e a combinação das duas), mas nunca logo em seguida.", 12f, MUTED, parent = cs)
        val tvRules = text("", 13f, FG, parent = cs)
        fun rulesT() {
            val r = Captions.learned(this)
            val fotos = PostQueue.load(this).filter { it.optInt("usos") > 0 || it.optString("status") == "postada" }
            val (pp, pn) = Predictor.insights(this, false)
            tvRules.text = (if (pp.isNotEmpty() || pn.isNotEmpty()) "🎯 O que os números mostram (${Predictor.trainedOn()} posts):\n" + (if (pp.isNotEmpty()) "✅ " + pp.joinToString(" · ") + "\n" else "") + (if (pn.isNotEmpty()) "❌ " + pn.joinToString(" · ") + "\n" else "") + "\n" else "") +
                Learn.repostStats(fotos) + "\n" + Learn.hourStats(fotos) + "\n" + AB.report(this) + "\n\n" +
                (if (r.isEmpty()) "🧠 A IA ainda não analisou as combinações." else "🧠 O que a IA aprendeu (foto + legenda):\n" + r.joinToString("\n") { "• $it" })
        }
        rulesT()
        chips(cs, listOf("🔁 Dar 2ª chance", "Flopou, sai menos"), { if (Learn.secondChance(this)) "🔁 Dar 2ª chance" else "Flopou, sai menos" }) { Learn.setSecondChance(this, it.startsWith("🔁")) }
        text("2ª chance: uma foto que flopou 1 vez volta normal pro sorteio (às vezes foi o horário). Se flopar de novo, sai bem menos. Vale pros vídeos também.", 11f, MUTED, parent = cs)
        chips(cs, listOf("🤖 IA analisa sozinha", "Só quando eu pedir"), { if (Learn.autoOn(this)) "🤖 IA analisa sozinha" else "Só quando eu pedir" }) { Learn.setAuto(this, it.startsWith("🤖")) }
        val tvLearnSt = TextView(this).apply { textSize = 12f; setTextColor(OK) }
        button("🧠 Analisar fotos + legendas que bombaram (IA)", cs, primary = true) {
            tvLearnSt.setTextColor(MUTED); tvLearnSt.text = "A IA está comparando as fotos e legendas…"
            Thread {
                val msg = try { val r = Captions.learnCombos(this); "✓ ${r.size} regras novas. Elas entram nas próximas legendas da IA." } catch (e: Exception) { "⚠ " + (e.message ?: "erro") }
                ui.post { tvLearnSt.setTextColor(if (msg.startsWith("✓")) OK else ACCENT); tvLearnSt.text = msg; rulesT() }
            }.start()
        }
        button("✨ Gerar 10 legendas parecidas com as que bombaram", cs) {
            tvLearnSt.setTextColor(MUTED); tvLearnSt.text = "Gerando variações das vencedoras…"
            Thread {
                val msg = try { val n = Captions.variationsOfWinners(this, 10); if (n < 0) "⚠ Ainda não tem legenda com 🔥. Avalie alguns posts primeiro." else "✓ $n legendas novas no banco, no estilo das que bombaram." } catch (e: Exception) { "⚠ " + (e.message ?: "erro") }
                ui.post { tvLearnSt.setTextColor(if (msg.startsWith("✓")) OK else ACCENT); tvLearnSt.text = msg }
            }.start()
        }
        cs.addView(tvLearnSt)
        text("Cada análise usa 1 pedido da IA (com até 10 fotos pequenas). Faça de vez em quando, depois de avaliar vários posts.", 11f, MUTED, parent = cs)
        val br = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cs.addView(br, lpMatch())
        button("Copiar backup", br, weight = true) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("banco", CaptionDB.export(this)))
            toast("Banco copiado. Cole num lugar seguro (Notas, Drive…)")
        }
        val etImp = field("Colar backup aqui pra importar", "", cs) { }
        button("Importar backup", br, weight = true) {
            val n = CaptionDB.import(this, etImp.text.toString()); etImp.setText("")
            toast(if (n > 0) "$n legendas entraram no banco" else "Cole o backup no campo primeiro"); renderLearn(); renderMine(); renderRefs()
        }
        renderLearn()

        val cm = card("Minhas legendas")
        text("Toda legenda que você escreve fica salva aqui e entra no sorteio, mesmo sem a IA.", 13f, MUTED, parent = cm)
        val etm = field("Escreva uma legenda sua", "", cm) { }
        button("Salvar", cm, primary = true) {
            val t = etm.text.toString().trim()
            if (t.isEmpty()) toast("Escreva a legenda primeiro") else { Captions.addMine(this, t); etm.setText(""); renderMine(); toast("Salva") }
        }
        mineBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cm.addView(mineBox, lpMatch())
        renderMine()

        val cb = card("Banco de legendas virais")
        text("Viu uma legenda bombando no Threads? Salve aqui. O gerador copia o ritmo e o tom delas, nunca o texto.", 13f, MUTED, parent = cb)
        val et = field("Cole uma legenda que viralizou", "", cb) { }
        button("Salvar no banco", cb, primary = true) {
            val t = et.text.toString().trim()
            if (t.isEmpty()) toast("Cole uma legenda primeiro") else { Captions.addRef(this, t); et.setText(""); renderRefs(); toast("Legenda salva") }
        }
        refsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cb.addView(refsBox, lpMatch())
        renderRefs()
    }

    private fun renderLearn() {
        if (!::tvLearn.isInitialized) return
        val total = CaptionDB.size(this)
        val top = CaptionDB.top(this, 5)
        val words = CaptionDB.winningWords(this, 8)
        tvLearn.text = buildString {
            append("$total legendas no banco (limite ${CaptionDB.MAX})")
            if (top.isNotEmpty()) {
                append("\n\nMelhores até agora:")
                top.forEach { append("\n• ").append(it.optString("t").replace('\n', ' ').take(60)).append("  (").append((CaptionDB.mean(it) * 100).toInt()).append("%)") }
            } else append("\n\nAinda sem avaliações.")
            if (words.isNotEmpty()) append("\n\nPalavras que puxam: ").append(words.joinToString(", "))
        }
    }

    private fun renderMine() {
        if (!::mineBox.isInitialized) return
        mineBox.removeAllViews()
        val l = Captions.mine(this)
        if (l.isEmpty()) { text("Nenhuma ainda.", 12f, MUTED, parent = mineBox); return }
        if (l.size > 40) text("Mostrando as 40 mais recentes de ${l.size}.", 12f, MUTED, parent = mineBox)
        l.take(40).forEach { r ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            mineBox.addView(row, lpMatch().apply { topMargin = dp(6) })
            row.addView(TextView(this).apply { text = r; textSize = 14f; setTextColor(FG) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "✕"; setTextColor(MUTED); textSize = 14f
                background = GradientDrawable().apply { setColor(0xFF1C1C24.toInt()); cornerRadius = dp(8).toFloat() }
                setOnClickListener { Captions.removeMine(this@MainActivity, r); renderMine() }
            }, LinearLayout.LayoutParams(dp(44), dp(40)))
        }
    }

    private fun renderRefs() {
        if (!::refsBox.isInitialized) return
        refsBox.removeAllViews()
        val refs = Captions.refs(this)
        if (refs.isEmpty()) { text("Banco vazio. Use também \"Levar as melhores pro banco\" na aba Analisar.", 12f, MUTED, parent = refsBox); return }
        if (refs.size > 40) text("Mostrando as 40 mais recentes de ${refs.size}.", 12f, MUTED, parent = refsBox)
        refs.take(40).forEach { r ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            refsBox.addView(row, lpMatch().apply { topMargin = dp(6) })
            row.addView(TextView(this).apply { text = r; textSize = 14f; setTextColor(FG) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val x = Button(this).apply {
                text = "✕"; setTextColor(MUTED); textSize = 14f
                background = GradientDrawable().apply { setColor(0xFF1C1C24.toInt()); cornerRadius = dp(8).toFloat() }
                setOnClickListener { Captions.removeRef(this@MainActivity, r); renderRefs() }
            }
            row.addView(x, LinearLayout.LayoutParams(dp(44), dp(40)))
        }
    }

    internal fun lpMatch() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Cartões avançados começam fechados (toque no título abre/fecha; o app lembra). */
    private val FOLDED = setOf("🩺 Saúde das filas", "📌 Botão de postar sempre à mão", "🤖 Tudo automático", "💾 Backup", "🧪 Autoteste e diagnóstico",
        "🔕 Notificações do Threads", "🤖 Piloto automático", "Celular limpo", "Atualizações", "🕘 Envios e notas")

    internal fun card(title: String? = null): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat(); setStroke(dp(1), LINE) }
        }
        page.addView(c, lpMatch().apply { topMargin = dp(12) })
        if (title == null) return c
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val key = "fold_" + title.hashCode()
        val h = text("", 17f, FG, parent = c, bold = true)
        c.addView(body, lpMatch())
        fun draw() { val open = body.visibility == View.VISIBLE; h.text = (if (open) "▾  " else "▸  ") + title }
        body.visibility = if (Store.prefs(this).getBoolean(key, title in FOLDED)) View.GONE else View.VISIBLE
        draw()
        h.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            Store.prefs(this).edit().putBoolean(key, body.visibility != View.VISIBLE).apply(); draw()
        }
        return body
    }

    internal fun text(t: String, size: Float, color: Int, parent: LinearLayout = root, bold: Boolean = false): TextView {
        val v = TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(3), 0, dp(3))
        }
        parent.addView(v, lpMatch())
        return v
    }

    internal fun button(t: String, parent: LinearLayout, primary: Boolean = false, weight: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = t; isAllCaps = false; textSize = 15f
            setTextColor(if (primary) 0xFF1A0A10.toInt() else FG)
            background = GradientDrawable().apply {
                setColor(if (primary) ACCENT else 0xFF1C1C24.toInt()); cornerRadius = dp(10).toFloat()
                if (!primary) setStroke(dp(1), LINE)
            }
            setOnClickListener { onClick() }
        }
        val lp = if (weight) LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(6), dp(3), 0) }
                 else if ((parent as? LinearLayout)?.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { setMargins(dp(6), dp(6), 0, 0); b.setPadding(dp(14), 0, dp(14), 0) }
                 else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
        parent.addView(b, lp)
        return b
    }

    internal fun field(hint: String, value: String, parent: LinearLayout, password: Boolean = false, number: Boolean = false, onChange: (String) -> Unit): EditText {
        text(hint, 12f, MUTED, parent = parent)
        val e = EditText(this).apply {
            setText(value); setTextColor(FG); setHintTextColor(MUTED); textSize = 15f
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                number -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
            setSingleLine(true)
            background = GradientDrawable().apply { setColor(BG); cornerRadius = dp(10).toFloat(); setStroke(dp(1), LINE) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
            })
        }
        parent.addView(e, lpMatch())
        return e
    }

    private fun setProvider(p: String, save: Boolean = true) {
        if (save) Store.prefs(this).edit().putString("provider", p).apply()
        fun style(b: Button, on: Boolean) {
            b.setTextColor(if (on) 0xFF1A0A10.toInt() else FG)
            b.background = GradientDrawable().apply { setColor(if (on) ACCENT else 0xFF1C1C24.toInt()); cornerRadius = dp(10).toFloat() }
        }
        style(btnClaude, p == "claude"); style(btnGemini, p == "gemini")
        etModel.hint = if (p == "gemini") Ai.DEFAULT_GEMINI else Ai.DEFAULT_CLAUDE
    }

    private fun updateFolderUi() {
        val b = FolderScan.bucket(this)
        btnFolder.text = if (b == null) "Escolher pasta pra vigiar" else "Vigiando: $b (trocar)"
        btn2k.text = if (FolderScan.only2k(this)) "Só as em 2K (upscale): ligado" else "Só as em 2K (upscale): desligado"
        btn2k.visibility = if (b == null) View.GONE else View.VISIBLE
        btnFlowNames.text = if (FolderScan.flowNames(this)) "Só fotos do Flow (nome com números): ligado" else "Só fotos do Flow (nome com números): desligado"
        btnFlowNames.visibility = if (b == null) View.GONE else View.VISIBLE
        btnVideos.text = if (FolderScan.videosOn(this)) "Vídeos do Flow viram esboço (aba Instagram): ligado" else "Vídeos do Flow viram esboço (aba Instagram): desligado"
        btnVideos.visibility = if (b == null) View.GONE else View.VISIBLE
        btnExisting.visibility = View.GONE
        btnCleanDl.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= 30) Thread {
            val n = FolderScan.originals(this).size
            ui.post { btnCleanDl.text = "Apagar do Download as $n já importadas"; btnCleanDl.visibility = if (n > 0) View.VISIBLE else View.GONE }
        }.start()
        if (b != null && FolderScan.hasPerm(this)) Thread {
            val n = FolderScan.countExisting(this)
            ui.post {
                btnExisting.text = "Importar as $n que já estão na pasta"
                btnExisting.visibility = if (n > 0) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun cleanDownloads() {
        if (Build.VERSION.SDK_INT < 30) return
        Thread {
            val l = FolderScan.originals(this)
            ui.post {
                if (l.isEmpty()) { FolderScan.clearOriginals(this); updateFolderUi(); return@post }
                try {
                    val pi = android.provider.MediaStore.createDeleteRequest(contentResolver, l)
                    @Suppress("DEPRECATION")
                    startIntentSenderForResult(pi.intentSender, REQ_DEL, null, 0, 0, 0)
                } catch (e: Exception) { toast("Não consegui pedir pra apagar: ${e.message}") }
            }
        }.start()
    }

    private fun askFolder() {
        if (!FolderScan.hasPerm(this)) { requestPermissions(FolderScan.perms, 21); return }
        Thread {
            val list = FolderScan.buckets(this)
            ui.post {
                if (list.isEmpty()) { toast("Não achei pastas com imagens"); return@post }
                val sorted = list.sortedByDescending { it.first.contains("flow", true) || it.first.contains("download", true) }
                val names = sorted.map { "${it.first} (${it.second})" }.toMutableList()
                val cur = FolderScan.bucket(this)
                if (cur != null) names.add("Parar de vigiar")
                AlertDialog.Builder(this)
                    .setTitle("Qual pasta o Flow usa?")
                    .setItems(names.toTypedArray()) { _, i ->
                        if (i >= sorted.size) { FolderScan.stop(this); toast("Parei de vigiar a pasta") }
                        else { FolderScan.choose(this, sorted[i].first); toast("Vigiando ${sorted[i].first}. Imagens novas entram sozinhas na fila.") }
                        updateFolderUi()
                    }.show()
            }
        }.start()
    }

    private fun importExisting() {
        btnExisting.isEnabled = false
        toast("Importando da pasta…")
        Thread {
            val n = FolderScan.scan(this, all = true) + FolderScan.scanVideos(this, all = true)
            ui.post { if (::insta.isInitialized) insta.refreshLists() }
            ui.post {
                btnExisting.isEnabled = true
                toast("$n foto(s) na fila, sem metadados. Gerando legendas…")
                LeveService.send(this, LeveService.ACT_ANALYZE)
                updateFolderUi(); refresh()
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 23 && ::insta.isInitialized && FolderScan.hasVideoPerm(this)) { insta.flowPicker(false); return }
        if (requestCode == 21) {
            if (FolderScan.hasPerm(this)) askFolder()
            else toast("Sem permissão de ver as fotos não dá pra vigiar a pasta")
        }
    }

    private fun styleEvery() {
        val (mn, mx) = Reminder.range(this)
        val cur = if (mn == 0) "0-0" else "$mn-$mx"
        val all = (0 until everyRow.childCount).flatMap { r -> val g = everyRow.getChildAt(r) as LinearLayout; (0 until g.childCount).map { g.getChildAt(it) as Button } }
        for (b in all) {
            val on = b.tag == cur
            b.setTextColor(if (on) 0xFF1A0A10.toInt() else FG)
            b.background = GradientDrawable().apply { setColor(if (on) ACCENT else 0xFF1C1C24.toInt()); cornerRadius = dp(10).toFloat() }
        }
    }

    private fun thumb(id: String): Bitmap? = thumbs[id] ?: try {
        val f = PostQueue.imageFile(this, id)
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        var smp = 1; while (b.outWidth / (smp * 2) >= dp(110)) smp *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = smp })?.also { thumbs[id] = it }
    } catch (e: Exception) { null }

    private fun renderPosts() {
        val list = PostQueue.load(this)
        val ready = list.filter { it.optString("status") != "postada" }
        val done = list.filter { it.optString("status") == "postada" }.sortedByDescending { it.optLong("postedAt") }.take(10)
        val next = PostQueue.next(this)?.optString("id")
        postList.removeAllViews()
        if (list.isEmpty()) { text("Nenhuma foto na fila ainda.", 13f, MUTED, parent = postList); return }
        text("${ready.size} pra postar · ${list.size - ready.size} postadas", 13f, MUTED, parent = postList)
        for (o in ready + done) {
            val id = o.optString("id"); val st = o.optString("status")
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply { setColor(BG); cornerRadius = dp(12).toFloat()
                    setStroke(dp(if (id == next) 2 else 1), if (id == next) ACCENT else LINE) }
            }
            postList.addView(box, lpMatch().apply { topMargin = dp(10) })
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(head, lpMatch())
            head.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(thumb(id)) },
                LinearLayout.LayoutParams(dp(84), dp(105)).apply { rightMargin = dp(10) })
            val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            head.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val usos = o.optInt("usos")
            val label = when (st) {
                "gerando" -> "Gerando legendas…"
                "postada" -> "✓ Postada " + android.text.format.DateFormat.format("dd/MM HH:mm", o.optLong("postedAt"))
                else -> if (id == next) "Próxima" else "Pronta"
            } + (if (usos > 1) " · ${usos}x" else "") + (if (o.optBoolean("nova")) " · nova do Flow" else "")
            text(label, 13f, if (st == "postada") OK else ACCENT, parent = side, bold = true)
            o.optString("aviso").takeIf { it.isNotEmpty() }?.let { text(it, 12f, MUTED, parent = side) }
            if (st == "postada" || o.optLong("sentAt") > 0) {
                if (st == "postada") text("Legenda: " + PostQueue.captionOf(o).ifEmpty { "(nenhuma)" }, 13f, FG, parent = side)
                text(if (st == "postada") "Como foi esse post?" else "Postou? Dê a nota (já marca como postada):", 12f, MUTED, parent = side)
                val rr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                side.addView(rr, lpMatch())
                val nota = if (o.optLong("notaAt") >= o.optLong("sentAt") && o.optLong("notaAt") == o.optLong("postedAt")) o.optString("nota") else ""
                listOf("top" to "💎", "good" to "🔥", "meh" to "😐", "bad" to "👎").forEach { (k, e) ->
                    button(e, rr, primary = nota == k, weight = true) {
                        RateLater.applyPhoto(this, id, k); refresh()
                        toast(if (k == "top") "💎 Anotado: prioridade máxima pra fotos e legendas assim" else if (k == "good") "Anotado: vou usar mais legendas assim" else if (k == "bad") "Anotado: vou evitar legendas assim" else "Anotado")
                    }
                }
                button("🎲 Postar de novo com legenda aleatória ↗", side) {
                    val cur = o.optJSONArray("legendas")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    val cap = Captions.randomCaption(this, listOf(PostQueue.captionOf(o)))
                    PostQueue.update(this, id) { it2 ->
                        val n = org.json.JSONArray().put(cap); cur.filter { it != cap }.forEach { n.put(it) }
                        it2.put("legendas", n); it2.put("escolhida", cap)
                    }
                    startActivity(Intent(this, PostActivity::class.java).putExtra("id", id).putExtra("caption", cap))
                }
            }
            if (st != "gerando") {
                val post = button(if (st == "postada") "Postar de novo (mesma legenda) ↗" else "Postar ↗", side, primary = true) { }
                post.setOnClickListener {
                    val cap = PostQueue.captionOf(o)
                    startActivity(Intent(this, PostActivity::class.java).putExtra("id", id).putExtra("caption", cap))
                }
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            side.addView(row, lpMatch())
            if (st == "postada") button("Usar de novo", row, weight = true) { PostQueue.requeue(this, id); refresh() }
            else if (st != "gerando") button("✨ IA", row, weight = true) {
                PostQueue.update(this, id) { it.put("status", "gerando"); it.put("pedido", true) }
                LeveService.send(this, LeveService.ACT_ANALYZE); refresh()
            }
            button("🎲 Aleatória", row, weight = true) {
                val cur = o.optJSONArray("legendas")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                val cap = Captions.randomCaption(this, cur)
                PostQueue.update(this, id) { it2 ->
                    val n = org.json.JSONArray().put(cap); cur.filter { it != cap }.forEach { n.put(it) }
                    it2.put("legendas", n); it2.put("escolhida", cap)
                    if (it2.optString("status") == "gerando") it2.put("status", "pronta")
                }
                refresh()
            }
            val del = button("Apagar", row, weight = true) { }
            del.setOnClickListener {
                if (del.tag != "armed") { del.tag = "armed"; del.text = "De novo"; ui.postDelayed({ del.tag = null; del.text = "Apagar" }, 2500) }
                else { PostQueue.remove(this, id); thumbs.remove(id); refresh() }
            }
            run {
                val et = EditText(this).apply {
                    hint = "Coloque sua legenda aqui"; setTextColor(FG); setHintTextColor(MUTED); textSize = 14f
                    background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(8).toFloat(); setStroke(dp(1), LINE) }
                    setPadding(dp(10), dp(8), dp(10), dp(8)); minLines = 1; maxLines = 5
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
                box.addView(et, lpMatch().apply { topMargin = dp(8) })
                val mine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(mine, lpMatch())
                button("Usar minha legenda", mine, weight = true) {
                    val t = et.text.toString().trim()
                    if (t.isEmpty()) toast("Escreva a legenda primeiro")
                    else { PostQueue.setManual(this, id, t); Captions.addMine(this, t); et.clearFocus(); toast("Legenda escolhida e salva em Minhas legendas") ; refresh() }
                }
                button("Postar com ela ↗", mine, weight = true) {
                    val t = et.text.toString().trim()
                    if (t.isEmpty()) toast("Escreva a legenda primeiro")
                    else { PostQueue.setManual(this, id, t); Captions.addMine(this, t); startActivity(Intent(this, PostActivity::class.java).putExtra("id", id).putExtra("caption", t)) }
                }
            }
            if (st == "pronta") {
                val caps = o.optJSONArray("legendas")
                val chosen = PostQueue.captionOf(o)
                if (caps != null) for (k in 0 until caps.length()) {
                    val cap = caps.optString(k)
                    val on = cap == chosen
                    val t = TextView(this).apply {
                        text = cap; textSize = 14f; setTextColor(FG)
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        background = GradientDrawable().apply { setColor(if (on) 0xFF3A1824.toInt() else CARD); cornerRadius = dp(8).toFloat()
                            setStroke(dp(1), if (on) ACCENT else LINE) }
                        setOnClickListener { PostQueue.update(this@MainActivity, id) { it.put("escolhida", cap) }; refresh() }
                    }
                    box.addView(t, lpMatch().apply { topMargin = dp(6) })
                }
            }
        }
    }

    private fun refresh() {
        val ps = PostQueue.stamp(this)
        if (ps != postStamp && postList.findFocus() !is EditText) { postStamp = ps; renderPosts() }
        val ev = Reminder.every(this); val at = Reminder.nextAt(this)
        tvPostInfo.text = if (ev > 0 && at > 0) "Próximo lembrete: " + android.text.format.DateFormat.format("HH:mm", at) + " (sorteado)" else ""
        val fila = Store.pending(this).size
        val list = Store.sorted(this)
        val lidos = Store.prefs(this).getInt("prints_lidos", 0)
        val gerando = PostQueue.pendingCaptions(this).size
        tvStatus.text = (if (gerando > 0) "Gerando legendas: $gerando foto(s)" else "Legendas em dia") +
            "\nIA: ${if (Ai.provider(this) == "gemini") "Gemini" else "Claude"} · ${Ai.model(this)}"
        val err = LeveService.lastError
        tvErr.text = if (err.isNotEmpty()) err else LeveService.lastBatchInfo
        tvErr.setTextColor(if (err.isNotEmpty()) ACCENT else OK)
        btnBubble.text = if (LeveService.capturing) "Desligar bolha de print" else "Ligar bolha de print"

        val pats = Store.loadPatterns(this)
        tvPat.text = if (pats.length() == 0) "Os padrões aparecem quando o banco tiver pelo menos 6 posts."
            else (0 until pats.length()).joinToString("\n") { "• " + pats.optString(it) }
        tvTop.text = if (list.isEmpty()) "" else "\nTop 10\n" + list.take(10).mapIndexed { i, o ->
            val leg = o.optString("legenda").ifEmpty { "(sem legenda)" }
            "${i + 1}. $leg — ${Store.fmt(o.optLong("curtidas"))} curtidas, ${Store.fmt(o.optLong("comentarios"))} coment." +
                (o.optString("foto").let { if (it.isNotEmpty()) "\n    foto: $it" else "" })
        }.joinToString("\n")
    }

    // ------------------------------------------------------------------ ações

    private fun toggleBubble() {
        if (LeveService.capturing) { LeveService.send(this, LeveService.ACT_STOP_CAPTURE); return }
        if (!Settings.canDrawOverlays(this)) {
            toast("Permita o Análise Leve aparecer sobre outros apps")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Ai.key(this).isEmpty()) toast("Lembre de colocar a chave da API embaixo. Os prints ficam na fila até lá.")
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ)
    }

    private fun pickGallery() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(i, "Escolha os prints"), REQ_GALLERY)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (::insta.isInitialized && insta.onResult(requestCode, resultCode, data)) return
        if (::meta.isInitialized && meta.onResult(requestCode, resultCode, data)) return
        if (::refs.isInitialized && refs.onResult(requestCode, resultCode, data)) return
        if (requestCode == REQ_RESTORE) {
            val u = data?.data ?: return
            AlertDialog.Builder(this).setTitle("Restaurar este backup?").setMessage("Troca as legendas, notas, filas e configurações atuais pelas do backup e reinicia o app.")
                .setPositiveButton("Restaurar") { _, _ ->
                    Thread {
                        val m = try { Backup.restore(this, u) } catch (e: Exception) { "Erro: ${e.message}" }
                        ui.post {
                            toast(m)
                            ui.postDelayed({
                                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }, 1200)
                        }
                    }.start()
                }.setNegativeButton("Cancelar", null).show()
            return
        }
        if (requestCode == REQ_DEL) {
            if (resultCode == RESULT_OK) { FolderScan.clearOriginals(this); toast("Apagadas do Download") }
            updateFolderUi(); return
        }
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_PROMO) {
            val uris = ArrayList<Uri>(); data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) importPromo(uris); return
        }
        if (requestCode == REQ_IGPHOTO) {
            val u = data.data ?: return
            toast("Limpando a foto…")
            Thread {
                val id = try {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
                    var smp = 1; while (maxOf(o.outWidth, o.outHeight) / (smp * 2) >= 2560) smp *= 2
                    val b = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = smp }) }
                    if (b == null) null else PostQueue.add(this, rotateByExif(u, b))
                } catch (e: Exception) { null }
                ui.post {
                    if (id == null) toast("Não abriu essa foto")
                    else { PostQueue.update(this, id) { it.put("status", "pronta"); it.put("legendas", org.json.JSONArray(Captions.randomCaptions(this, 4))) }
                        postPhotoIg(id, Captions.randomCaption(this)) }
                }
            }.start()
            return
        }
        when (requestCode) {
            REQ_PROJ -> {
                val extra = Intent().putExtra("code", resultCode).putExtra("data", data)
                LeveService.send(this, LeveService.ACT_CAPTURE, extra)
                toast("Bolha ligada. Abra o Threads e toque nela pra tirar print.")
                moveTaskToBack(true)
            }
            REQ_POSTS -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                importPosts(uris)
            }
            REQ_GALLERY -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                importUris(uris)
            }
        }
    }

    private fun handleShare(i: Intent?) {
        if (i == null) return
        // link compartilhado (Instagram, TikTok, YouTube…) vira referência
        if (i.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val url = i.getStringExtra(Intent.EXTRA_TEXT)?.let { Refs.findUrl(it) }
            if (url != null && ::refs.isInitialized) { showTab("Refs"); refs.saveUrl(url) }
            else toast("Não achei um link no que foi compartilhado")
            return
        }
        val uris = ArrayList<Uri>()
        @Suppress("DEPRECATION")
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Guardar ${uris.size} foto(s) onde?")
            .setItems(arrayOf("🖼  Estoque de referência", "📮  Fila de posts (Threads)", "📊  Números de um post (print dos insights/perfil)")) { _, w ->
                when (w) {
                    0 -> { showTab("Refs"); refs.addPhotos(uris) }
                    1 -> { showTab("Postar"); importPosts(uris) }
                    else -> readPrints(uris)
                }
            }.show()
    }

    private fun pickPosts() = pickImages(REQ_POSTS, true)

    /** 📊 A IA lê os prints do Threads (insights do post ou perfil) e guarda os números. */
    private fun readPrints(uris: List<Uri>) {
        toast("Lendo ${uris.size} print(s)…")
        Thread {
            val out = uris.mapIndexed { i, u ->
                try {
                    val f = java.io.File(cacheDir, "print_$i.jpg"); contentResolver.openInputStream(u)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                    Insights.fromPrint(this, f).also { f.delete() }
                } catch (e: Exception) { ErrLog.log(this, "ler print", e); "⚠ " + (e.message ?: "erro") }
            }
            ui.post {
                AlertDialog.Builder(this).setTitle("📊 Prints lidos").setMessage(out.joinToString("\n\n")).setPositiveButton("Ok", null).show()
                rankRedraw?.invoke()
            }
        }.start()
    }

    private var rankRedraw: (() -> Unit)? = null

    /** ✨ Mostra a postagem perfeita (foto + legenda + horário) com o porquê. */
    private fun showPerfect(ai: Boolean) {
        toast(if (ai) "A IA está escrevendo legendas pra essa foto…" else "Montando…")
        Thread {
            val p = try { Perfect.build(this, ai) } catch (e: Exception) { ErrLog.log(this, "postagem perfeita", e); null }
            ui.post {
                if (p == null) { toast("Fila vazia"); return@post }
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
                SendLog.thumb(this, "foto", p.photoId)?.let { b -> box.addView(ImageView(this).apply { setImageBitmap(b); scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(-1, dp(260))) }
                text(p.caption, 15f, FG, parent = box, bold = true)
                text("🎯 Chance estimada: ${(p.chance * 100).toInt()}%" + (if (Predictor.trainedOn() < 15) " (ainda aprendendo: ${Predictor.trainedOn()} posts com nota)" else ""), 13f, OK, parent = box)
                p.bestTime?.let { text("⏰ Melhor horário: $it", 13f, FG, parent = box) }
                if (p.why.isNotEmpty()) text("Por que: " + p.why.joinToString(" · "), 12f, MUTED, parent = box)
                AlertDialog.Builder(this).setTitle("✨ Postagem perfeita agora").setView(ScrollView(this).apply { addView(box) })
                    .setPositiveButton("📸 Postar") { _, _ -> startActivity(Intent(this, PostActivity::class.java).putExtra("id", p.photoId).putExtra("caption", p.caption)) }
                    .setNeutralButton("✨ Legendas novas (IA)") { _, _ -> showPerfect(true) }
                    .setNegativeButton("Fechar", null).show()
            }
        }.start()
    }

    /** Galeria (seletor de fotos do celular) ou Arquivos. */
    internal fun pickImages(req: Int, multiple: Boolean) {
        AlertDialog.Builder(this).setTitle("Procurar em")
            .setItems(arrayOf("🖼  Galeria", "📁  Arquivos e pastas")) { _, w ->
                val i = if (w == 0 && Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply {
                    type = "image/*"; if (multiple) putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, minOf(100, android.provider.MediaStore.getPickImagesMaxLimit()))
                } else if (w == 0) Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                    type = "image/*"; if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                } else Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                try { @Suppress("DEPRECATION") startActivityForResult(i, req) } catch (e: Exception) { toast("Não achei a galeria. Use Arquivos.") }
            }.show()
    }

    private fun importPosts(uris: List<Uri>) {
        toast("Limpando ${uris.size} foto(s)…")
        Thread {
            var n = 0
            for (u in uris) try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var smp = 1
                while (bounds.outWidth / (smp * 2) >= 1440) smp *= 2
                val raw = contentResolver.openInputStream(u)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = smp })
                } ?: continue
                val bmp = rotateByExif(u, raw)
                PostQueue.add(this, bmp); n++
            } catch (e: Exception) { }
            ui.post {
                toast("$n foto(s) na fila, sem metadados. Gerando legendas…")
                LeveService.send(this, LeveService.ACT_ANALYZE)
                refresh()
            }
        }.start()
    }

    /** Vira a foto conforme a orientação original antes de descartar os metadados. */
    internal fun rotateByExif(u: Uri, b: Bitmap): Bitmap = try {
        val deg = contentResolver.openInputStream(u)?.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
        } ?: 0f
        if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
    } catch (e: Exception) { b }

    private fun importUris(uris: List<Uri>) {
        toast("Colocando ${uris.size} print(s) na fila…")
        Thread {
            var n = 0
            for (u in uris) try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 720) sample *= 2
                val bmp = contentResolver.openInputStream(u)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: continue
                if (Store.saveShot(this, bmp, false) != null) n++
            } catch (e: Exception) { }
            ui.post {
                toast("$n print(s) na fila. Analisando em segundo plano.")
                LeveService.send(this, LeveService.ACT_ANALYZE)
                refresh()
            }
        }.start()
    }

    private fun testAi() {
        tvErr.setTextColor(MUTED); tvErr.text = "Testando ${Ai.model(this)}…"
        Thread {
            val (ok, msg) = try { true to "IA respondeu: " + Ai.test(this) }
                catch (e: AiException) { false to "Erro ${e.code}: ${e.message}" }
                catch (e: Exception) { false to "Sem conexão: ${e.message}" }
            ui.post {
                ui.removeCallbacks(refresher)
                tvErr.setTextColor(if (ok) OK else ACCENT); tvErr.text = msg
                ui.postDelayed(refresher, 8000)
            }
        }.start()
    }

    private fun refreshPatterns() {
        val all = Store.sorted(this)
        if (all.size < 6) { toast("Preciso de pelo menos 6 posts no banco"); return }
        toast("Comparando os melhores com os piores…")
        Thread {
            val msg = try {
                Store.savePatterns(this, Ai.patterns(this, all.take(10), all.takeLast(minOf(8, all.size / 3))))
                "Padrões atualizados"
            } catch (e: Exception) { "Não deu: ${e.message}" }
            ui.post { toast(msg); refresh() }
        }.start()
    }

    private fun copyExport() {
        val n = Store.loadPosts(this).length()
        if (n == 0) { toast("O banco ainda está vazio"); return }
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Análise Leve", Store.exportJson(this)))
        toast("Banco copiado ($n posts). Cole no Post Leve.")
    }

    internal fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
