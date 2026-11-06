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

    // paleta AMOLED
    private val BG = 0xFF000000.toInt()
    private val CARD = 0xFF121218.toInt()
    private val LINE = 0xFF262630.toInt()
    private val FG = 0xFFECEEF3.toInt()
    private val MUTED = 0xFF9A9EAB.toInt()
    private val ACCENT = 0xFFFF5C82.toInt()
    private val OK = 0xFF4CC68E.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var page: LinearLayout
    private val pages = LinkedHashMap<String, LinearLayout>()
    private lateinit var tabRow: LinearLayout
    private lateinit var refsBox: LinearLayout
    private lateinit var mineBox: LinearLayout
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
    private lateinit var btnCleanDl: Button
    private val thumbs = HashMap<String, Bitmap>()
    private lateinit var btnBubble: Button
    private lateinit var btnClaude: Button
    private lateinit var btnGemini: Button
    private lateinit var etModel: EditText
    private var clearArmed = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildUi()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        Thread {
            PostQueue.cleanGallery(this, false)
            val n = FolderScan.scan(this)
            FolderScan.schedule(this)
            if (n > 0 || PostQueue.pendingCaptions(this).isNotEmpty()) ui.post {
                if (n > 0) toast("$n foto(s) nova(s) da pasta ${FolderScan.bucket(this)} na fila")
                LeveService.send(this, LeveService.ACT_ANALYZE)
            }
        }.start()
        ui.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresher)
    }

    private val refresher = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1500) }
    }

    // ------------------------------------------------------------------ tela

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
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
        for (name in listOf("Postar", "Legendas", "Analisar", "Ajustes")) {
            if (name == "Analisar") { pages[name] = LinearLayout(this); continue } // desativada
            val pg = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            pages[name] = pg
            button(name, tabRow, weight = true) { showTab(name) }.tag = name
        }

        page = root
        // status
        val st = card()
        tvStatus = text("", 16f, FG, parent = st, bold = true)
        tvErr = text("", 13f, ACCENT, parent = st)

        pages.values.forEach { root.addView(it, lpMatch()) }

        // ===== Postar
        page = pages["Postar"]!!
        val cp = card("Fila de posts")
        text("Adicione fotos: cada uma sai sem metadados e ganha legendas. Toque em Postar e o Threads abre com a foto e a legenda juntas. É só publicar.", 13f, MUTED, parent = cp)
        button("＋ Adicionar fotos pra postar", cp, primary = true) { pickPosts() }
        button("🎲 Postar uma aleatória agora", cp) {
            val pk = Captions.randomPick(this)
            if (pk == null) toast("A fila está vazia")
            else startActivity(Intent(this, PostActivity::class.java).putExtra("id", pk.first).putExtra("caption", pk.second))
        }
        text("Lembrete pra postar: a notificação já vem com foto e legenda sorteadas. Toque em Postar e depois em Publicar no Threads.", 12f, MUTED, parent = cp)
        everyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cp.addView(everyRow, lpMatch())
        listOf(Triple(0, 0, "Off"), Triple(30, 50, "30–50m"), Triple(60, 120, "1–2h"), Triple(120, 180, "2–3h")).forEach { (mn, mx, t) ->
            button(t, everyRow, weight = true) { Reminder.setRange(this, mn, mx); styleEvery(); refresh()
                toast(if (mn == 0) "Lembrete desligado" else "Vou avisar num horário diferente a cada vez, entre $t") }.tag = mn
        }
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

        // ===== Ajustes
        page = pages["Ajustes"]!!
        val c4 = card("Configurações da IA")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        c4.addView(row, lpMatch())
        btnClaude = button("Claude", row, weight = true) { setProvider("claude") }
        btnGemini = button("Gemini", row, weight = true) { setProvider("gemini") }
        val p = Store.prefs(this)
        field("Chave da API", p.getString("key", "") ?: "", c4, password = true) { p.edit().putString("key", it.trim()).apply() }
        etModel = field("Modelo (vazio = padrão; vários separados por vírgula)", p.getString("model", "") ?: "", c4) { p.edit().putString("model", it.trim()).apply() }
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

    private fun showTab(name: String) {
        Store.prefs(this).edit().putString("tab", name).apply()
        pages.forEach { (k, v) -> v.visibility = if (k == name) View.VISIBLE else View.GONE }
        for (i in 0 until tabRow.childCount) {
            val b = tabRow.getChildAt(i) as Button
            val on = b.tag == name
            b.setTextColor(if (on) 0xFF1A0A10.toInt() else FG)
            b.background = GradientDrawable().apply { setColor(if (on) ACCENT else 0xFF1C1C24.toInt()); cornerRadius = dp(10).toFloat() }
        }
    }

    // ------------------------------------------------------------------ aba Legendas

    private fun chips(parent: LinearLayout, options: List<String>, current: () -> String, pick: (String) -> Unit) {
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
        button("Gerar legendas novas pra fila toda", cv, primary = true) {
            val n = Captions.regenerateAll(this)
            if (n > 0) { LeveService.send(this, LeveService.ACT_ANALYZE); toast("Gerando legendas novas pra $n foto(s)") } else toast("Nenhuma foto esperando na fila")
            refresh()
        }

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

    private fun renderMine() {
        if (!::mineBox.isInitialized) return
        mineBox.removeAllViews()
        val l = Captions.mine(this)
        if (l.isEmpty()) { text("Nenhuma ainda.", 12f, MUTED, parent = mineBox); return }
        l.forEach { r ->
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
        refs.forEach { r ->
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

    private fun lpMatch() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun card(title: String? = null): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat(); setStroke(dp(1), LINE) }
        }
        page.addView(c, lpMatch().apply { topMargin = dp(14) })
        if (title != null) text(title, 17f, FG, parent = c, bold = true)
        return c
    }

    private fun text(t: String, size: Float, color: Int, parent: LinearLayout = root, bold: Boolean = false): TextView {
        val v = TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(3), 0, dp(3))
        }
        parent.addView(v, lpMatch())
        return v
    }

    private fun button(t: String, parent: LinearLayout, primary: Boolean = false, weight: Boolean = false, onClick: () -> Unit): Button {
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
                 else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
        parent.addView(b, lp)
        return b
    }

    private fun field(hint: String, value: String, parent: LinearLayout, password: Boolean = false, number: Boolean = false, onChange: (String) -> Unit): EditText {
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
        if (!FolderScan.hasPerm(this)) { requestPermissions(arrayOf(FolderScan.perm), 21); return }
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
            val n = FolderScan.scan(this, all = true)
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
        if (requestCode == 21) {
            if (FolderScan.hasPerm(this)) askFolder()
            else toast("Sem permissão de ver as fotos não dá pra vigiar a pasta")
        }
    }

    private fun styleEvery() {
        val cur = Reminder.every(this)
        for (k in 0 until everyRow.childCount) {
            val b = everyRow.getChildAt(k) as Button
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
            if (st != "gerando") {
                val post = button(if (st == "postada") "Postar de novo ↗" else "Postar ↗", side, primary = true) { }
                post.setOnClickListener {
                    val cap = PostQueue.captionOf(o)
                    startActivity(Intent(this, PostActivity::class.java).putExtra("id", id).putExtra("caption", cap))
                }
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            side.addView(row, lpMatch())
            if (st == "postada") button("Usar de novo", row, weight = true) { PostQueue.requeue(this, id); refresh() }
            else if (st != "gerando") button("Mais legendas", row, weight = true) {
                PostQueue.update(this, id) { it.put("status", "gerando") }
                LeveService.send(this, LeveService.ACT_ANALYZE); refresh()
            }
            if (st != "postada") button("🎲 Aleatória", row, weight = true) {
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
        if (requestCode == REQ_DEL) {
            if (resultCode == RESULT_OK) { FolderScan.clearOriginals(this); toast("Apagadas do Download") }
            updateFolderUi(); return
        }
        if (resultCode != RESULT_OK || data == null) return
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
        val uris = ArrayList<Uri>()
        @Suppress("DEPRECATION")
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isNotEmpty()) importUris(uris)
    }

    private fun pickPosts() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(i, "Fotos pra postar"), REQ_POSTS)
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
    private fun rotateByExif(u: Uri, b: Bitmap): Bitmap = try {
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

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
