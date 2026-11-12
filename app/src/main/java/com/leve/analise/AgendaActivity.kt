package com.leve.analise

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 🗓 Agenda: calendário (mês / semana / dia), lista, editor com recorrência e os ajustes da publicação automática.
 */
class AgendaActivity : Activity() {
    private val BG = 0xFF000000.toInt(); private val CARD = 0xFF131319.toInt(); private val SURF = 0xFF1E1E27.toInt()
    private val FG = 0xFFF1F2F6.toInt(); private val MUTED = 0xFF8F94A3.toInt(); private val ACCENT = 0xFFFF5C82.toInt(); private val OK = 0xFF4CC68E.toInt()
    private val WARN = 0xFFFFB547.toInt(); private val BAD = 0xFFFF5A5A.toInt(); private val BLUE = 0xFF5B9DFF.toInt()
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private val zone get() = ZoneId.systemDefault()
    private var view = "mes"; private var sel: LocalDate = LocalDate.now(); private var listFilter = "proximos"
    private var screen = "agenda" // agenda | editor | ajustes

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hm(t: Long) = android.text.format.DateFormat.format("HH:mm", t).toString()
    private fun dayOf(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    private fun tv(t: String, s: Float, c: Int, p: LinearLayout, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = s; setTextColor(c); if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(0, dp(3), 0, dp(3)); p.addView(this)
    }
    private fun btn(t: String, p: LinearLayout, primary: Boolean = false, weight: Boolean = false, on: Boolean = false, small: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = t; textSize = if (small) 12.5f else 14f; gravity = Gravity.CENTER; setTextColor(if (primary) 0xFF1A0A10.toInt() else if (on) 0xFFFF9DB4.toInt() else FG)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); val v = if (small) 9 else 13; setPadding(dp(10), dp(v), dp(10), dp(v))
        background = (if (primary) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, 0xFFFF8A65.toInt())) else GradientDrawable().apply { setColor(if (on) 0x2EFF5C82 else SURF); if (on) setStroke(dp(1), ACCENT) }).apply { cornerRadius = dp(14).toFloat() }
        setOnClickListener { click() }
        p.addView(this, (if (weight) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2)).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) })
    }
    private fun row(p: LinearLayout) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }.also { p.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    private fun card(p: LinearLayout = root) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(18).toFloat() } }.also { p.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun bg(work: () -> String, done: (String) -> Unit) = Thread { val r = try { work() } catch (e: Exception) { "✗ " + e.message }; runOnUiThread { if (!isFinishing) done(r) } }.start()

    fun statusColor(st: String) = when (st) {
        AgendaCore.PUBLICADA -> OK; AgendaCore.ERRO -> BAD; AgendaCore.ATRASADA -> BAD; AgendaCore.PENDENTE -> WARN
        AgendaCore.NO_THREADS -> BLUE; AgendaCore.EXECUTANDO -> 0xFFB98CFF.toInt(); AgendaCore.CANCELADA -> MUTED; else -> ACCENT
    }
    fun statusLabel(st: String) = when (st) {
        AgendaCore.AGENDADA -> "agendada"; AgendaCore.NO_THREADS -> "agendada no Threads"; AgendaCore.EXECUTANDO -> "publicando…"
        AgendaCore.PUBLICADA -> "publicada"; AgendaCore.PENDENTE -> "pendente"; AgendaCore.ATRASADA -> "atrasada"; AgendaCore.ERRO -> "erro"; else -> st
    }
    private fun modeIcon(m: String) = when (m) { "api" -> "🤖"; "threads" -> "📲"; "teste" -> "🧪"; else -> "🔔" }
    private fun eff(o: JSONObject) = AgendaCore.effective(o.optString("status"), o.optString("mode"), o.optLong("at"), System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(36)) }
        scroll = ScrollView(this).apply { setBackgroundColor(BG); addView(root) }
        setContentView(scroll)
        Thread { try { Agenda.tick(this) } catch (e: Exception) { ErrLog.log(this, "agenda", e) }; runOnUiThread { if (screen == "agenda") draw() } }.start()
        intent.getStringExtra("id")?.let { id -> Agenda.get(this, id)?.let { sel = dayOf(it.optLong("at")); view = "dia" } }
        draw()
        intent.getStringExtra("id")?.let { id -> Agenda.get(this, id)?.let { scroll.post { actions(it) } } }
        if (intent.getBooleanExtra("lot", false)) scroll.post { lot() }
    }

    override fun onResume() { super.onResume(); if (screen == "agenda" && ::root.isInitialized) draw() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (screen != "agenda") { screen = "agenda"; draw() } else super.onBackPressed() }

    // ================================================================= tela principal
    private fun draw() {
        screen = "agenda"
        root.removeAllViews()
        tv("🗓 Agenda", 24f, FG, root, true)
        tv(Agenda.summary(this), 12.5f, MUTED, root)
        warnings()
        val top = row(root)
        btn("+ Novo agendamento", top, primary = true, weight = true) { editor(null) }
        val r2 = row(root)
        btn("✨ Vários de uma vez", r2, weight = true, small = true) { lot() }
        btn("⚙ Publicação", r2, weight = true, small = true) { settings() }
        btn("🧪 Testar", r2, weight = true, small = true) {
            val o = Agenda.selfTest(this, 60)
            toast("🧪 Teste criado pra " + hm(o.optLong("at")) + ". Daqui a 1 min deve chegar a notificação, mesmo com o app fechado. Não publica nada.")
            sel = LocalDate.now(); draw()
        }
        val tabs = row(root)
        listOf("mes" to "Mês", "semana" to "Semana", "dia" to "Dia", "lista" to "Lista").forEach { (k, l) -> btn(l, tabs, weight = true, on = view == k, small = true) { view = k; draw() } }
        when (view) { "mes" -> month(); "semana" -> week(); "dia" -> day(); else -> list() }
    }

    private fun warnings() {
        val msgs = ArrayList<Pair<String, () -> Unit>>()
        if (!Agenda.exactOk(this)) msgs += "⏰ Libere 'Alarmes e lembretes' pra agenda tocar no minuto certo" to {
            if (Build.VERSION.SDK_INT >= 31) try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } catch (e: Exception) { } }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            msgs += "🔔 Libere as notificações: é por elas que a agenda te chama" to { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5) }
        if (ScreenReader.instance == null) msgs += "📖 Ligue o Leitor de tela pra eu perceber sozinho quando você agenda no Threads" to {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { } }
        val need = Agenda.all(this).count { eff(it) in setOf(AgendaCore.ATRASADA, AgendaCore.ERRO, AgendaCore.PENDENTE) }
        if (need > 0) msgs += "⚠ $need agendamento(s) precisam de você (toque pra ver)" to { view = "lista"; listFilter = "atencao"; draw() }
        msgs.forEach { (t, a) -> val c = card(); tv(t, 13f, WARN, c); c.setOnClickListener { a() } }
    }

    private fun entriesOn(d: LocalDate) = Agenda.all(this).filter { dayOf(it.optLong("at")) == d && it.optString("status") != AgendaCore.CANCELADA }.sortedBy { it.optLong("at") }

    private fun month() {
        val c = card()
        val nav = row(c)
        btn("‹", nav, small = true) { sel = sel.minusMonths(1).withDayOfMonth(1); draw() }.layoutParams = LinearLayout.LayoutParams(dp(48), -2)
        TextView(this).apply { text = sel.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale("pt", "BR")).replaceFirstChar { it.uppercase() } + " " + sel.year
            textSize = 16f; setTextColor(FG); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; nav.addView(this, LinearLayout.LayoutParams(0, -2, 1f)) }
        btn("›", nav, small = true) { sel = sel.plusMonths(1).withDayOfMonth(1); draw() }.layoutParams = LinearLayout.LayoutParams(dp(48), -2)
        val head = row(c); listOf("S", "T", "Q", "Q", "S", "S", "D").forEach { TextView(this).apply { text = it; setTextColor(MUTED); gravity = Gravity.CENTER; textSize = 11f; head.addView(this, LinearLayout.LayoutParams(0, -2, 1f)) } }
        val first = sel.withDayOfMonth(1); val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
        val byDay = Agenda.all(this).filter { it.optString("status") != AgendaCore.CANCELADA }.groupBy { dayOf(it.optLong("at")) }
        val conf = Agenda.conflicts(this)
        var d = start
        repeat(6) { w ->
            if (w > 0 && d.month != sel.month) return@repeat
            val r = row(c)
            repeat(7) {
                val day = d; val list = byDay[day].orEmpty()
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(6), 0, dp(6))
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (day == sel) 0x2EFF5C82 else 0); if (day == LocalDate.now()) setStroke(dp(1), ACCENT) } }
                TextView(this).apply { text = day.dayOfMonth.toString(); textSize = 14f; gravity = Gravity.CENTER
                    setTextColor(if (day.month != sel.month) 0xFF4A4D57.toInt() else FG); cell.addView(this) }
                val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                list.sortedBy { it.optLong("at") }.take(4).forEach { o -> View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (o.optString("id") in conf && o.optString("status") in AgendaCore.ACTIVE) WARN else statusColor(eff(o))) }
                    dots.addView(this, LinearLayout.LayoutParams(dp(6), dp(6)).apply { setMargins(dp(1), dp(2), dp(1), 0) }) } }
                if (list.size > 4) TextView(this).apply { text = "+"; textSize = 8f; setTextColor(MUTED); dots.addView(this) }
                cell.addView(dots, LinearLayout.LayoutParams(-1, dp(10)))
                cell.setOnClickListener { sel = day; draw() }
                r.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
                d = d.plusDays(1)
            }
        }
        legend(c)
        dayList(sel)
    }

    private fun legend(p: LinearLayout) {
        val r = row(p)
        listOf(AgendaCore.AGENDADA, AgendaCore.NO_THREADS, AgendaCore.PUBLICADA, AgendaCore.PENDENTE, AgendaCore.ATRASADA).forEach { st ->
            TextView(this).apply { text = "● " + statusLabel(st).replace("agendada no Threads", "no Threads"); textSize = 10f; setTextColor(statusColor(st)); setPadding(dp(4), dp(6), dp(4), 0); r.addView(this) }
        }
    }

    private fun week() {
        val start = sel.minusDays((sel.dayOfWeek.value - 1).toLong())
        val nav = row(root)
        btn("‹ semana", nav, weight = true, small = true) { sel = sel.minusWeeks(1); draw() }
        btn("hoje", nav, weight = true, small = true) { sel = LocalDate.now(); draw() }
        btn("semana ›", nav, weight = true, small = true) { sel = sel.plusWeeks(1); draw() }
        val dias = listOf("seg", "ter", "qua", "qui", "sex", "sáb", "dom")
        for (i in 0..6) {
            val d = start.plusDays(i.toLong()); val l = entriesOn(d)
            val c = card()
            val h = row(c)
            TextView(this).apply { text = dias[i] + " " + "%02d/%02d".format(d.dayOfMonth, d.monthValue) + (if (d == LocalDate.now()) " · hoje" else ""); textSize = 14f
                setTextColor(if (d == LocalDate.now()) ACCENT else FG); typeface = Typeface.DEFAULT_BOLD; h.addView(this, LinearLayout.LayoutParams(0, -2, 1f)) }
            TextView(this).apply { text = "+"; textSize = 20f; setTextColor(ACCENT); setPadding(dp(12), 0, dp(4), 0); setOnClickListener { editor(null, d) }; h.addView(this) }
            if (l.isEmpty()) tv("—", 12f, MUTED, c)
            l.forEach { o -> val t = tv("${hm(o.optLong("at"))}  ${modeIcon(o.optString("mode"))} ${o.optString("cap").replace('\n', ' ').take(42).ifBlank { "(a fila escolhe)" }}", 13f, statusColor(eff(o)), c); t.setOnClickListener { actions(o) } }
        }
    }

    private fun day() {
        val nav = row(root)
        btn("‹", nav, weight = true, small = true) { sel = sel.minusDays(1); draw() }
        btn(if (sel == LocalDate.now()) "hoje" else "%02d/%02d".format(sel.dayOfMonth, sel.monthValue), nav, weight = true, small = true) {
            DatePickerDialog(this, { _, y, m, dd -> sel = LocalDate.of(y, m + 1, dd); draw() }, sel.year, sel.monthValue - 1, sel.dayOfMonth).show() }
        btn("›", nav, weight = true, small = true) { sel = sel.plusDays(1); draw() }
        dayList(sel)
    }

    private fun dayList(d: LocalDate) {
        val l = entriesOn(d)
        val dias = listOf("Segunda", "Terça", "Quarta", "Quinta", "Sexta", "Sábado", "Domingo")
        val h = row(root)
        TextView(this).apply { text = dias[d.dayOfWeek.value - 1] + ", " + "%02d/%02d".format(d.dayOfMonth, d.monthValue) + " · ${l.size} post(s)"; textSize = 15f; setTextColor(FG); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(2)); h.addView(this, LinearLayout.LayoutParams(0, -2, 1f)) }
        btn("+ neste dia", h, small = true) { editor(null, d) }.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) }
        if (l.isEmpty()) tv("Nada neste dia.", 13f, MUTED, root)
        val conf = Agenda.conflicts(this)
        l.forEach { entryCard(it, it.optString("id") in conf) }
    }

    private fun list() {
        val r = row(root)
        listOf("proximos" to "Próximos", "passados" to "Passados", "atencao" to "Precisam de você").forEach { (k, l) -> btn(l, r, weight = true, on = listFilter == k, small = true) { listFilter = k; draw() } }
        val now = System.currentTimeMillis()
        val all = Agenda.all(this).filter { it.optString("status") != AgendaCore.CANCELADA }
        val l = when (listFilter) {
            "passados" -> all.filter { it.optLong("at") < now && eff(it) !in setOf(AgendaCore.AGENDADA, AgendaCore.EXECUTANDO) }.sortedByDescending { it.optLong("at") }.take(80)
            "atencao" -> all.filter { eff(it) in setOf(AgendaCore.ATRASADA, AgendaCore.ERRO, AgendaCore.PENDENTE) }.sortedBy { it.optLong("at") }
            else -> all.filter { it.optString("status") in AgendaCore.ACTIVE && eff(it) !in setOf(AgendaCore.ATRASADA, AgendaCore.PENDENTE) }.sortedBy { it.optLong("at") }.take(80)
        }
        if (l.isEmpty()) tv(when (listFilter) { "passados" -> "Nada no histórico ainda."; "atencao" -> "✓ Nada pendente."; else -> "Nada agendado. Toque em + Novo agendamento." }, 13f, MUTED, root)
        val conf = Agenda.conflicts(this)
        var lastDay: LocalDate? = null
        l.forEach { o -> val d = dayOf(o.optLong("at")); if (d != lastDay) { lastDay = d; tv(AgendaCore.fmt(o.optLong("at")).substringBefore(" às"), 12f, MUTED, root, true).setPadding(0, dp(12), 0, 0) }; entryCard(o, o.optString("id") in conf) }
    }

    private fun entryCard(o: JSONObject, conflict: Boolean) {
        val st = eff(o)
        val c = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(16).toFloat(); if (conflict && o.optString("status") in AgendaCore.ACTIVE) setStroke(dp(1), WARN) } }
        root.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        c.addView(View(this).apply { background = GradientDrawable().apply { setColor(statusColor(st)); cornerRadius = dp(3).toFloat() } }, LinearLayout.LayoutParams(dp(4), dp(52)).apply { rightMargin = dp(10) })
        val pid = o.optString("photo")
        c.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = GradientDrawable().apply { setColor(SURF); cornerRadius = dp(8).toFloat() }; clipToOutline = true
            if (pid.isNotEmpty()) try { BitmapFactory.decodeFile(PostQueue.imageFile(this@AgendaActivity, pid).path, BitmapFactory.Options().apply { inSampleSize = 8 })?.let { setImageBitmap(it) } } catch (e: Exception) { } },
            LinearLayout.LayoutParams(dp(44), dp(58)).apply { rightMargin = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        tv(hm(o.optLong("at")) + "  " + modeIcon(o.optString("mode")) + (if (o.has("sid")) " 🔁" else ""), 17f, FG, col, true)
        tv("● " + statusLabel(st) + (if (conflict && o.optString("status") in AgendaCore.ACTIVE) "  ⚠ muito perto de outro post" else ""), 11.5f, statusColor(st), col)
        tv(when { o.optString("cap").isNotBlank() -> o.optString("cap").replace('\n', ' ').take(80); o.optString("content") == "texto" -> "(texto sorteado na hora)"; else -> "(a fila escolhe foto e legenda na hora)" }, 12f, MUTED, col)
        if (o.optString("status") == AgendaCore.ERRO && o.optString("err").isNotEmpty()) tv("✗ " + o.optString("err").take(120), 11f, BAD, col)
        c.setOnClickListener { actions(o) }
    }

    // ================================================================= ações de um agendamento
    private fun actions(o0: JSONObject) {
        val o = Agenda.get(this, o0.optString("id")) ?: return
        val id = o.optString("id"); val st = eff(o); val active = o.optString("status") in AgendaCore.ACTIVE || st == AgendaCore.ERRO
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (active) items += "✏ Editar" to { if (o.has("sid")) askSeries("Editar") { whole -> if (whole) editSeries(o.optString("sid")) else editor(o) } else editor(o) }
        if (active) items += "⏰ Reagendar" to { reschedule(o) }
        if (active && o.optString("mode") == "api") items += "🤖 Publicar agora (API)" to { ThreadsPublish.publishNow(this, id); toast("Publicando… você recebe uma notificação quando sair."); draw() }
        if (active && o.optString("mode") != "threads") items += "📲 Abrir no Threads pra postar agora" to { Agenda.postNow(this, id) }
        if (o.optString("status") != AgendaCore.PUBLICADA && o.optString("mode") != "teste") items += "✓ Marcar como publicada" to { Agenda.markPublished(this, id, "você marcou"); draw() }
        items += "⧉ Duplicar" to { pickDateTime(o.optLong("at") + 3600_000L) { at -> Agenda.duplicate(this, id, at); sel = dayOf(at); toast("Duplicado pra " + AgendaCore.fmt(at)); draw() } }
        if (o.optString("link").isNotEmpty()) items += "🔗 Ver no Threads" to { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.optString("link")))) } catch (e: Exception) { } }
        items += "📜 Histórico" to {
            val a = o.optJSONArray("log") ?: JSONArray()
            AlertDialog.Builder(this).setTitle("Histórico").setMessage((0 until a.length()).joinToString("\n") { a.optString(it) }.ifEmpty { "—" } +
                (if (o.optString("err").isNotEmpty()) "\n\nÚltimo erro: " + o.optString("err") else "")).setPositiveButton("OK", null).show()
        }
        items += "🗑 Excluir" to {
            if (o.has("sid")) askSeries("Excluir") { whole -> if (whole) Agenda.deleteSeries(this, o.optString("sid")) else Agenda.delete(this, id); draw() }
            else AlertDialog.Builder(this).setMessage("Excluir o post de " + AgendaCore.fmt(o.optLong("at")) + "?" + (if (o.optString("mode") == "threads" && o.optString("status") != AgendaCore.PUBLICADA) "\n\nIsso só tira da Agenda: no Threads, apague o agendamento lá também." else ""))
                .setPositiveButton("Excluir") { _, _ -> Agenda.delete(this, id); draw() }.setNegativeButton("Cancelar", null).show()
        }
        val title = AgendaCore.fmt(o.optLong("at")) + " · " + statusLabel(st)
        AlertDialog.Builder(this).setTitle(title).setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }.show()
    }

    private fun askSeries(what: String, go: (Boolean) -> Unit) {
        AlertDialog.Builder(this).setTitle("$what: esse post faz parte de uma repetição").setItems(arrayOf("Só este", "Toda a repetição (os próximos)")) { _, w -> go(w == 1) }.show()
    }

    private fun reschedule(o: JSONObject) {
        val id = o.optString("id")
        val base = maxOf(o.optLong("at"), System.currentTimeMillis())
        val opts = listOf("+30 min" to 30, "+1 hora" to 60, "+3 horas" to 180, "Amanhã, mesmo horário" to -1, "Escolher data e hora" to -2)
        AlertDialog.Builder(this).setTitle("Reagendar").setItems(opts.map { it.first }.toTypedArray()) { _, w ->
            val m = opts[w].second
            fun apply(at: Long) {
                val cf = Agenda.conflictsAt(this, at, id)
                Agenda.reschedule(this, id, at); sel = dayOf(at)
                toast("⏰ " + AgendaCore.fmt(at) + (if (cf.isNotEmpty()) " · ⚠ tem outro post às " + hm(cf.first().optLong("at")) else "") + (if (o.optString("mode") == "threads") "\nMude também no Threads." else ""))
                draw()
            }
            when (m) {
                -1 -> apply(Instant.ofEpochMilli(o.optLong("at")).atZone(zone).plusDays(1).let { if (it.toInstant().toEpochMilli() < System.currentTimeMillis()) ZonedDateTime.now().plusDays(1).with(it.toLocalTime()) else it }.toInstant().toEpochMilli())
                -2 -> pickDateTime(base) { apply(it) }
                else -> apply(base + m * 60_000L)
            }
        }.show()
    }

    private fun pickDateTime(start: Long, done: (Long) -> Unit) {
        val z = Instant.ofEpochMilli(start).atZone(zone)
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, mi -> done(ZonedDateTime.of(LocalDate.of(y, m + 1, d), LocalTime.of(h, mi), zone).toInstant().toEpochMilli()) }, z.hour, z.minute, true).show()
        }, z.year, z.monthValue - 1, z.dayOfMonth).show()
    }

    // ================================================================= editor
    private class Draft {
        var id = ""; var sid = ""; var content = "auto"; var photo = ""; var cap = ""; var mode = "lembrete"
        var date: LocalDate = LocalDate.now(); var time: LocalTime = LocalTime.of(20, 0)
        var freq = "nenhuma"; var every = 1; var days = mutableSetOf<Int>(); var times = mutableListOf<String>()
        var endKind = "nunca"; var end: LocalDate = LocalDate.now().plusWeeks(4); var count = 10
    }

    private fun editSeries(sid: String) {
        val s = Agenda.seriesOf(this, sid) ?: return
        val d = Draft().apply {
            this.sid = sid; content = s.optString("content", "auto"); photo = s.optString("photo"); cap = s.optString("cap"); mode = s.optString("mode", "lembrete")
            date = LocalDate.parse(s.optString("start")); freq = s.optString("freq"); every = s.optInt("every", 1)
            days = s.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toMutableSet() } ?: mutableSetOf()
            val ts = s.optJSONArray("times")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("20:00")
            time = AgendaCore.parseTime(ts.first()) ?: LocalTime.of(20, 0); times = ts.drop(1).toMutableList()
            if (s.optString("end").isNotEmpty()) { endKind = "data"; end = LocalDate.parse(s.optString("end")) }
            if (s.optInt("count") > 0) { endKind = "vezes"; count = s.optInt("count") }
        }
        drawEditor(d)
    }

    private fun editor(o: JSONObject?, day: LocalDate? = null) {
        val d = Draft()
        if (o != null) {
            val z = Instant.ofEpochMilli(o.optLong("at")).atZone(zone)
            d.id = o.optString("id"); d.content = o.optString("content", "auto"); d.photo = o.optString("photo"); d.cap = o.optString("cap"); d.mode = o.optString("mode", "lembrete")
            d.date = z.toLocalDate(); d.time = z.toLocalTime().withSecond(0).withNano(0)
        } else {
            d.mode = Agenda.defaultMode(this)
            d.date = day ?: LocalDate.now()
            // próximo horário bom: hora ativa da audiência, sem conflito
            val from = if (d.date == LocalDate.now()) System.currentTimeMillis() + 30 * 60_000L else ZonedDateTime.of(d.date, LocalTime.of(9, 0), zone).toInstant().toEpochMilli()
            val to = ZonedDateTime.of(d.date, LocalTime.of(23, 30), zone).toInstant().toEpochMilli()
            val t = if (to > from) Batch.slots(this, 1, from, to).firstOrNull { Agenda.conflictsAt(this, it).isEmpty() } else null
            if (t != null) { val z = Instant.ofEpochMilli(t).atZone(zone); d.time = LocalTime.of(z.hour, z.minute) } else if (d.date == LocalDate.now()) d.date = d.date.plusDays(1)
        }
        drawEditor(d)
    }

    private fun drawEditor(d: Draft) {
        screen = "editor"
        root.removeAllViews(); scroll.scrollTo(0, 0)
        tv(if (d.sid.isNotEmpty()) "🔁 Editar repetição" else if (d.id.isNotEmpty()) "✏ Editar agendamento" else "+ Novo agendamento", 22f, FG, root, true)

        // ---- conteúdo
        var c = card(); tv("O que vai sair", 13f, FG, c, true)
        val r = row(c)
        listOf("auto" to "🎲 A fila escolhe", "foto" to "🖼 Escolher foto", "texto" to "✍ Só texto").forEach { (k, l) -> btn(l, r, weight = true, small = true, on = d.content == k) {
            if (k == "foto") pickPhoto { pid, cap -> d.content = "foto"; d.photo = pid; if (d.cap.isBlank()) d.cap = cap; drawEditor(d) } else { d.content = k; if (k != "foto") d.photo = ""; drawEditor(d) } } }
        if (d.content == "foto" && d.photo.isNotEmpty()) c.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; adjustViewBounds = true
            try { BitmapFactory.decodeFile(PostQueue.imageFile(this@AgendaActivity, d.photo).path, BitmapFactory.Options().apply { inSampleSize = 4 })?.let { setImageBitmap(it) } } catch (e: Exception) { } },
            LinearLayout.LayoutParams(dp(120), dp(160)).apply { topMargin = dp(6) })
        tv(when (d.content) { "auto" -> "Foto e legenda sorteadas pela fila perto da hora (mesmo sorteio do 'Postar próximo')."; "texto" -> "Post só de texto (sem foto). Deixe em branco pra sortear um dos textos."; else -> "" }, 11.5f, MUTED, c)
        val capE = EditText(this).apply { setText(d.cap); hint = if (d.content == "auto") "Legenda (vazio = sorteia na hora)" else "Legenda / texto"; setTextColor(FG); setHintTextColor(MUTED); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; minLines = 2
            background = GradientDrawable().apply { setColor(SURF); cornerRadius = dp(12).toFloat() }; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        c.addView(capE, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val counter = tv("", 11f, MUTED, c)
        fun count() { val n = capE.text.length; counter.text = if (n > 500) "⚠ $n/500: o Threads corta em 500" else "$n/500"; counter.setTextColor(if (n > 500) BAD else MUTED) }
        count(); capE.addTextChangedListener(object : android.text.TextWatcher { override fun afterTextChanged(s: android.text.Editable?) { d.cap = s.toString(); count() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cc: Int) {}; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cc: Int) {} })
        btn("🎲 Sortear legenda agora", c, small = true) { d.cap = if (d.content == "texto") (TextPosts.now(this)?.second ?: Captions.randomCaption(this)) else (d.photo.takeIf { it.isNotEmpty() }?.let { PostQueue.get(this, it) }?.let { PostQueue.captionOf(it) }?.takeIf { it.isNotBlank() } ?: Captions.randomCaption(this)); drawEditor(d) }

        // ---- quando
        c = card(); tv("Quando", 13f, FG, c, true)
        val rw = row(c)
        btn("📅 " + "%02d/%02d/%d".format(d.date.dayOfMonth, d.date.monthValue, d.date.year), rw, weight = true) {
            DatePickerDialog(this, { _, y, m, dd -> d.date = LocalDate.of(y, m + 1, dd); drawEditor(d) }, d.date.year, d.date.monthValue - 1, d.date.dayOfMonth).show() }
        btn("🕐 " + "%02d:%02d".format(d.time.hour, d.time.minute), rw, weight = true) {
            TimePickerDialog(this, { _, h, m -> d.time = LocalTime.of(h, m); drawEditor(d) }, d.time.hour, d.time.minute, true).show() }
        val at = ZonedDateTime.of(d.date, d.time, zone).toInstant().toEpochMilli()
        if (d.freq == "nenhuma") {
            if (at < System.currentTimeMillis() - 60_000L) tv("⚠ Esse horário já passou", 12f, BAD, c)
            Agenda.conflictsAt(this, at, d.id).takeIf { it.isNotEmpty() }?.let { cf -> tv("⚠ Muito perto de outro post: " + cf.joinToString { hm(it.optLong("at")) } + " (mín. ${Agenda.gapMin(this)} min)", 12f, WARN, c) }
            Audience.score(this, d.time.hour)?.let { s -> tv("Audiência nessa hora: " + when { s >= 0.7 -> "🔥 alta"; s >= 0.45 -> "média"; else -> "baixa" }, 11.5f, MUTED, c) }
        }

        // ---- repetir
        c = card(); tv("Repetir", 13f, FG, c, true)
        val rr = row(c)
        listOf("nenhuma" to "Não", "diaria" to "Diário", "semanal" to "Semanal", "horas" to "A cada X h").forEach { (k, l) -> btn(l, rr, weight = true, small = true, on = d.freq == k || (k == "diaria" && d.freq == "dias")) { d.freq = k; if (k == "semanal" && d.days.isEmpty()) d.days.add(d.date.dayOfWeek.value); drawEditor(d) } }
        if (d.freq != "nenhuma") {
            val unit = when (d.freq) { "semanal" -> "semana(s)"; "horas" -> "hora(s)"; else -> "dia(s)" }
            val re = row(c)
            TextView(this).apply { text = "A cada"; setTextColor(FG); textSize = 13f; re.addView(this) }
            btn("−", re, small = true) { d.every = maxOf(1, d.every - 1); drawEditor(d) }.layoutParams = LinearLayout.LayoutParams(dp(44), -2)
            TextView(this).apply { text = "${d.every} $unit"; setTextColor(ACCENT); textSize = 15f; gravity = Gravity.CENTER; re.addView(this, LinearLayout.LayoutParams(0, -2, 1f)) }
            btn("+", re, small = true) { d.every = minOf(if (d.freq == "horas") 24 else 30, d.every + 1); drawEditor(d) }.layoutParams = LinearLayout.LayoutParams(dp(44), -2)
            if (d.freq == "semanal") {
                val rd = row(c)
                listOf("S", "T", "Q", "Q", "S", "S", "D").forEachIndexed { i, l -> btn(l, rd, weight = true, small = true, on = (i + 1) in d.days) { if (!d.days.remove(i + 1)) d.days.add(i + 1); drawEditor(d) } }
            }
            if (d.freq != "horas") {
                tv("Horários no dia: " + (listOf("%02d:%02d".format(d.time.hour, d.time.minute)) + d.times).joinToString(" · "), 12.5f, FG, c)
                val rt = row(c)
                btn("+ outro horário", rt, weight = true, small = true) { TimePickerDialog(this, { _, h, m -> val s = "%02d:%02d".format(h, m); if (s !in d.times) d.times.add(s); d.times.sort(); drawEditor(d) }, 21, 0, true).show() }
                if (d.times.isNotEmpty()) btn("limpar extras", rt, weight = true, small = true) { d.times.clear(); drawEditor(d) }
            }
            tv("Termina", 12.5f, FG, c, true)
            val rf = row(c)
            btn("Nunca", rf, weight = true, small = true, on = d.endKind == "nunca") { d.endKind = "nunca"; drawEditor(d) }
            btn(if (d.endKind == "data") "Em %02d/%02d".format(d.end.dayOfMonth, d.end.monthValue) else "Em uma data", rf, weight = true, small = true, on = d.endKind == "data") {
                DatePickerDialog(this, { _, y, m, dd -> d.endKind = "data"; d.end = LocalDate.of(y, m + 1, dd); drawEditor(d) }, d.end.year, d.end.monthValue - 1, d.end.dayOfMonth).show() }
            btn(if (d.endKind == "vezes") "Após ${d.count}x" else "Após N vezes", rf, weight = true, small = true, on = d.endKind == "vezes") {
                val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(d.count.toString()) }
                AlertDialog.Builder(this).setTitle("Quantas vezes?").setView(e).setPositiveButton("OK") { _, _ -> d.endKind = "vezes"; d.count = e.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: 10; drawEditor(d) }.show() }
            // prévia
            val rule = ruleOf(d)
            val prev = try { AgendaCore.expand(rule, System.currentTimeMillis(), System.currentTimeMillis() + 60L * 86400_000L) } catch (e: Exception) { emptyList() }
            tv("Próximas: " + prev.take(5).joinToString(" · ") { AgendaCore.fmt(it.at) } + (if (prev.size > 5) " … (${prev.size} nos próximos 60 dias)" else "").ifEmpty { "" }, 11.5f, MUTED, c)
            val slots = Agenda.all(this).filter { it.optString("status") in AgendaCore.ACTIVE && (d.sid.isEmpty() || it.optString("sid") != d.sid) }.map { AgendaCore.Slot(it.optString("id"), it.optLong("at")) }
            val clash = prev.take(60).count { p -> slots.any { Math.abs(it.at - p.at) < Agenda.gapMin(this) * 60_000L } }
            if (clash > 0) tv("⚠ $clash dessas datas ficam muito perto de outros posts", 12f, WARN, c)
        }

        // ---- como sai
        c = card(); tv("Como sai", 13f, FG, c, true)
        val rm = row(c)
        listOf("api" to "🤖 Sozinho (API)", "lembrete" to "🔔 Me chama", "threads" to "📲 Já agendei no Threads").forEach { (k, l) -> btn(l, rm, weight = true, small = true, on = d.mode == k) { d.mode = k; drawEditor(d) } }
        tv(when (d.mode) {
            "api" -> if (!ThreadsPublish.ready(this)) "⚠ A publicação automática ainda não está liberada (toque em ⚙ Publicação). Sem ela, na hora vira lembrete." else if (d.content != "texto" && !ThreadsPublish.hostReady(this)) "⚠ Foto precisa de hospedagem (⚙ Publicação). Sem ela, na hora vira lembrete; texto sai sozinho." else "Sai sozinho na hora, pela API oficial. Se falhar, tenta de novo e depois te chama."
            "threads" -> "Só anota: você agendou no próprio Threads e ele publica. Dica: com o Leitor de tela ligado eu percebo sozinho quando você agenda lá."
            else -> "Na hora chega uma notificação com a foto e a legenda; um toque abre o Threads pronto pra publicar." }, 11.5f, if (d.mode == "api" && !ThreadsPublish.ready(this)) WARN else MUTED, c)

        // ---- salvar
        val rs = row(root)
        btn("Cancelar", rs, weight = true) { draw() }
        btn("Salvar", rs, primary = true, weight = true) { save(d) }
        if (d.id.isNotEmpty() || d.sid.isNotEmpty()) btn("🗑 Excluir", root, small = true) {
            if (d.sid.isNotEmpty()) Agenda.deleteSeries(this, d.sid) else Agenda.delete(this, d.id); draw() }
    }

    private fun ruleOf(d: Draft) = AgendaCore.Rule(freq = d.freq, every = d.every, days = d.days.toSet(),
        times = listOf("%02d:%02d".format(d.time.hour, d.time.minute)) + d.times, start = d.date,
        end = if (d.endKind == "data") d.end else null, count = if (d.endKind == "vezes") d.count else null, zone = zone.id)

    private fun save(d: Draft) {
        if (d.content == "texto" && d.cap.length > 500) { toast("O Threads aceita até 500 caracteres"); return }
        if (d.freq == "nenhuma") {
            val at = ZonedDateTime.of(d.date, d.time, zone).toInstant().toEpochMilli()
            if (at < System.currentTimeMillis() - 60_000L && d.mode != "threads") { toast("Esse horário já passou"); return }
            if (d.sid.isNotEmpty()) { Agenda.deleteSeries(this, d.sid) }
            if (d.id.isNotEmpty()) Agenda.update(this, d.id) { it.put("at", at).put("mode", d.mode).put("content", d.content).put("photo", d.photo).put("cap", d.cap.trim())
                if (d.mode == "threads") it.put("status", AgendaCore.NO_THREADS) else if (it.optString("status") == AgendaCore.NO_THREADS) it.put("status", AgendaCore.AGENDADA) }
            else Agenda.create(this, at, d.mode, d.content, d.photo, d.cap.trim())
            toast("🗓 " + AgendaCore.fmt(at) + " · " + modeIcon(d.mode)); sel = d.date
        } else {
            if (d.freq == "semanal" && d.days.isEmpty()) { toast("Escolha os dias da semana"); return }
            val s = JSONObject().put("freq", d.freq).put("every", d.every).put("days", JSONArray(d.days.sorted())).put("times", JSONArray(listOf("%02d:%02d".format(d.time.hour, d.time.minute)) + d.times))
                .put("start", d.date.toString()).put("zone", zone.id).put("mode", d.mode).put("content", d.content).put("photo", d.photo).put("cap", d.cap.trim())
            if (d.endKind == "data") s.put("end", d.end.toString()); if (d.endKind == "vezes") s.put("count", d.count)
            if (d.sid.isNotEmpty()) { s.put("sid", d.sid); Agenda.seriesOf(this, d.sid)?.optJSONArray("exceptions")?.let { s.put("exceptions", it) } }
            if (d.id.isNotEmpty() && d.sid.isEmpty()) Agenda.delete(this, d.id) // virou repetição
            Agenda.saveSeries(this, s)
            val n = AgendaCore.expand(ruleOf(d), System.currentTimeMillis(), System.currentTimeMillis() + 21 * 86400_000L).size
            toast("🔁 Repetição salva · $n post(s) nas próximas 3 semanas (as seguintes vão sendo criadas sozinhas)"); sel = d.date
        }
        draw()
    }

    private fun pickPhoto(done: (String, String) -> Unit) {
        val photos = PostQueue.load(this).filter { PostQueue.imageFile(this, it.optString("id")).exists() }
            .sortedWith(compareBy({ if (it.optString("status") == "pronta") 0 else 1 }, { -it.optLong("at") })).take(90)
        if (photos.isEmpty()) { toast("A fila está vazia"); return }
        val grid = GridLayout(this).apply { columnCount = 3; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        var dlg: AlertDialog? = null
        val w = (resources.displayMetrics.widthPixels - dp(90)) / 3
        photos.forEach { o ->
            grid.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(SURF)
                try { BitmapFactory.decodeFile(PostQueue.imageFile(this@AgendaActivity, o.optString("id")).path, BitmapFactory.Options().apply { inSampleSize = 8 })?.let { setImageBitmap(it) } } catch (e: Exception) { }
                if (o.optString("status") == "postada") alpha = 0.55f
                setOnClickListener { dlg?.dismiss(); done(o.optString("id"), PostQueue.captionOf(o)) } },
                GridLayout.LayoutParams().apply { width = w; height = (w * 1.33).toInt(); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        dlg = AlertDialog.Builder(this).setTitle("Escolha a foto (apagadas = já postadas)").setView(ScrollView(this).apply { addView(grid) }).setNegativeButton("Cancelar", null).show()
    }

    // ================================================================= montar vários
    /**
     * ✨ Agendar vários de uma vez: escolhe quantos dias e quantos posts por dia; o app sorteia foto+legenda
     * (mesmo sorteio da fila) e põe cada um num horário em que sua audiência está ativa, sem bater com o que já tem.
     */
    private fun lot() {
        var perDay = 5; var days = 3; var fromTomorrow = LocalTime.now().isAfter(LocalTime.of(21, 0))
        var mode = if (ThreadsPublish.canPhoto(this)) "api" else "threads"
        var startH = 9; var endH = 23
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        fun drawBox() {
            box.removeAllViews()
            tv("Posts por dia", 13f, FG, box, true)
            val r = row(box); listOf(2, 3, 5, 8, 10).forEach { k -> btn("$k", r, weight = true, small = true, on = perDay == k) { perDay = k; drawBox() } }
            tv("Por quantos dias", 13f, FG, box, true)
            val r1 = row(box); listOf(1, 2, 3, 5, 7).forEach { k -> btn(if (k == 1) "1 dia" else "$k", r1, weight = true, small = true, on = days == k) { days = k; drawBox() } }
            val r2 = row(box); btn("Começando hoje", r2, weight = true, small = true, on = !fromTomorrow) { fromTomorrow = false; drawBox() }; btn("Começando amanhã", r2, weight = true, small = true, on = fromTomorrow) { fromTomorrow = true; drawBox() }
            tv("Entre", 13f, FG, box, true)
            val r4 = row(box)
            btn("das %02dh".format(startH), r4, weight = true, small = true) { TimePickerDialog(this, { _, h, _ -> startH = h.coerceAtMost(endH - 1); drawBox() }, startH, 0, true).show() }
            btn("às %02dh".format(endH), r4, weight = true, small = true) { TimePickerDialog(this, { _, h, _ -> endH = h.coerceIn(startH + 1, 23); drawBox() }, endH, 0, true).show() }
            tv("Como sai", 13f, FG, box, true)
            val r3 = row(box); listOf("threads" to "📲 No Agendar do Threads", "api" to "🤖 App publica", "lembrete" to "🔔 Me chama").forEach { (k, l) -> btn(l, r3, weight = true, small = true, on = mode == k) { mode = k; drawBox() } }
            tv("Total: ${perDay * days} posts. " + if (mode == "threads") "Um toque e eu vou no Threads e agendo um por um no Agendar dele (foto, legenda, data e hora). Depois o Threads publica sozinho, mesmo com o celular desligado. Não mexa no celular enquanto ele agenda (uns 30 s por post)." + (if (ScreenReader.instance == null) " ⚠ Precisa do Leitor de tela ligado." else "") else if (mode == "api") (if (!ThreadsPublish.ready(this)) "⚠ A publicação sozinha ainda não está liberada (⚙ Publicação). Até liberar, cada um vira lembrete na hora."
                else if (!ThreadsPublish.hostReady(this)) "⚠ Pra foto sair sozinha falta a hospedagem (⚙ Publicação). Até lá, cada um vira lembrete na hora."
                else "Cada um sai sozinho na hora, você não precisa fazer nada (o celular só precisa estar ligado e com internet).")
                else "Na hora de cada um chega uma notificação com foto e legenda; um toque abre o Threads pronto.", 11.5f, if (mode == "api" && !ThreadsPublish.canPhoto(this)) WARN else MUTED, box)
        }
        drawBox()
        AlertDialog.Builder(this).setTitle("✨ Agendar vários de uma vez").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Agendar") { _, _ ->
            if (mode == "threads") {
                val items = pickLot(perDay, days, fromTomorrow, startH, endH).map { (t, pk) -> JSONObject().put("id", pk.first).put("cap", pk.second).put("at", t).put("done", false) }
                if (items.isEmpty()) { toast("Fila vazia ou sem horário livre nesses dias"); return@setPositiveButton }
                Batch.setItems(this, items)
                val r = ScreenReader.instance
                if (r == null) { toast("Ligue o Leitor de tela do Post Leve: é ele que faz os toques no Threads. Depois toque em 🤖 Agendar todos."); startActivity(Intent(this, BatchActivity::class.java))
                    try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { } }
                else { toast("🤖 Agendando ${items.size} posts no Threads… não mexa no celular"); r.runBatchAuto() }
                return@setPositiveButton
            }
            val made = buildLot(perDay, days, fromTomorrow, startH, endH, mode)
            if (made.isEmpty()) { toast("Fila vazia ou sem horário livre nesses dias"); return@setPositiveButton }
            sel = dayOf(made.first()); view = "lista"; listFilter = "proximos"
            toast("✨ ${made.size} posts agendados: de " + AgendaCore.fmt(made.first()) + " até " + AgendaCore.fmt(made.last()))
            draw()
        }.setNegativeButton("Cancelar", null).show()
    }

    /** Monta os agendamentos; devolve os horários criados. */
    private fun buildLot(perDay: Int, days: Int, fromTomorrow: Boolean, startH: Int, endH: Int, mode: String): List<Long> =
        pickLot(perDay, days, fromTomorrow, startH, endH).map { (t, pk) -> Agenda.create(this, t, mode, "foto", pk.first, pk.second); t }

    /** Horários (audiência ativa, sem bater com nada) + foto e legenda sorteadas pra cada um. */
    private fun pickLot(perDay: Int, days: Int, fromTomorrow: Boolean, startH: Int, endH: Int): List<Pair<Long, Pair<String, String>>> {
        val out = ArrayList<Pair<Long, Pair<String, String>>>()
        val gap = Agenda.gapMin(this) * 60_000L
        for (k in 0 until days) {
            val day = LocalDate.now().plusDays((k + if (fromTomorrow) 1 else 0).toLong())
            var from = ZonedDateTime.of(day, LocalTime.of(startH, 0), zone).toInstant().toEpochMilli()
            val to = ZonedDateTime.of(day, LocalTime.of(endH, if (endH == 23) 30 else 0), zone).toInstant().toEpochMilli()
            from = maxOf(from, System.currentTimeMillis() + 30 * 60_000L)
            if (to - from < 30 * 60_000L) continue
            val busy = Agenda.all(this).filter { it.optString("status") in AgendaCore.ACTIVE }.map { it.optLong("at") } + out.map { it.first }
            val slots = Batch.slots(this, perDay + busy.size, from, to).filter { t -> busy.none { Math.abs(it - t) < gap } }.take(perDay)
            for (t in slots) {
                // mesma foto não aparece duas vezes no mesmo lote (tenta outro sorteio)
                var pk = Captions.randomPick(this, promo = false) ?: break
                var k = 0
                while (out.any { it.second.first == pk.first } && k++ < 12) pk = Captions.randomPick(this, promo = false) ?: break
                if (out.any { it.second.first == pk.first }) continue
                Captions.remember(this, pk.first, pk.second) // reservada: o próximo sorteio pega outra
                out += t to pk
            }
        }
        return out.sortedBy { it.first }
    }

    // ================================================================= ajustes
    private fun settings() {
        screen = "ajustes"
        root.removeAllViews(); scroll.scrollTo(0, 0)
        tv("⚙ Publicação e avisos", 22f, FG, root, true)

        var c = card(); tv("🤖 Publicar sozinho (API oficial do Threads)", 14f, FG, c, true)
        tv(ThreadsPublish.status(this), 12.5f, if (ThreadsPublish.ready(this)) OK else WARN, c)
        tv("Usa a mesma conexão da API que lê seus números, mas o token precisa ter a permissão threads_content_publish. " +
            "Salvar um horário aqui NÃO é publicar: só conta como publicado quando o Threads devolve o post.", 11.5f, MUTED, c)
        val out = tv("", 12.5f, FG, c)
        btn("Testar permissão de publicar", c, small = true) { out.text = "Testando…"; bg({ ThreadsPublish.testPermission(this) }) { out.text = it; out.setTextColor(if (it.startsWith("✓")) OK else BAD) } }
        btn("Publicar um texto de teste AGORA (sai no seu perfil)", c, small = true) {
            if (!ThreadsPublish.ready(this)) { toast("Teste a permissão primeiro"); return@btn }
            val e = EditText(this).apply { setText("teste"); setTextColor(FG) }
            AlertDialog.Builder(this).setTitle("Vai aparecer no seu perfil do Threads. Texto:").setView(e).setPositiveButton("Publicar") { _, _ ->
                val o = Agenda.create(this, System.currentTimeMillis(), "api", "texto", "", e.text.toString().ifBlank { "teste" })
                ThreadsPublish.publishNow(this, o.optString("id")); toast("Publicando… acompanhe na Agenda (hoje).") }.setNegativeButton("Cancelar", null).show()
        }

        c = card(); tv("🖼 Hospedagem da foto (só pra publicar foto sozinho)", 14f, FG, c, true)
        tv("O Threads só aceita foto por link público. O app sobe a foto, o Threads baixa e pronto. Grátis:\n" +
            "• ImgBB: crie conta em imgbb.com → api.imgbb.com → 'Get API key'. A foto se apaga sozinha em 1 h.\n" +
            "• Cloudinary: em Settings → Upload, crie um 'upload preset' Unsigned; cole o nome da conta (cloud name) e do preset.\n" +
            "A chave fica só no celular.", 11.5f, MUTED, c)
        var host = ThreadsPublish.host(this).ifEmpty { "imgbb" }
        val hr = row(c)
        val a = EditText(this).apply { setTextColor(FG); setHintTextColor(MUTED); textSize = 13f; isSingleLine = true }
        val b = EditText(this).apply { setTextColor(FG); setHintTextColor(MUTED); textSize = 13f; isSingleLine = true }
        fun fill() {
            if (host == "imgbb") { a.hint = "Chave da API do ImgBB"; a.setText(ThreadsPublish.imgbbKey(this)); b.visibility = View.GONE }
            else { a.hint = "cloud name"; a.setText(ThreadsPublish.cldCloud(this)); b.hint = "upload preset (unsigned)"; b.setText(ThreadsPublish.cldPreset(this)); b.visibility = View.VISIBLE }
        }
        val hb = ArrayList<TextView>()
        listOf("imgbb" to "ImgBB", "cloudinary" to "Cloudinary").forEach { (k, l) -> hb += btn(l, hr, weight = true, small = true, on = host == k) { host = k; settings(); } }
        c.addView(a); c.addView(b); fill()
        val ho = tv("", 12.5f, FG, c)
        btn("Salvar e testar hospedagem", c, small = true) {
            ThreadsPublish.setHost(this, host, a.text.toString(), b.text.toString())
            ho.text = "Subindo uma imagem de teste…"; bg({ ThreadsPublish.testHost(this) }) { ho.text = it; ho.setTextColor(if (it.startsWith("✓")) OK else BAD) }
        }

        c = card(); tv("🔔 Avisos", 14f, FG, c, true)
        tv("Avisar antes da hora", 12.5f, FG, c)
        val r1 = row(c); listOf(0, 5, 10, 30, 60).forEach { m -> btn(if (m == 0) "não" else "$m min", r1, weight = true, small = true, on = Agenda.remindMin(this) == m) { Agenda.setRemindMin(this, m); Agenda.reschedule(this); settings() } }
        tv("Distância mínima entre posts (alerta de conflito)", 12.5f, FG, c)
        val r2 = row(c); listOf(0, 15, 20, 30, 60).forEach { m -> btn(if (m == 0) "não" else "$m min", r2, weight = true, small = true, on = Agenda.gapMin(this) == m) { Agenda.setGapMin(this, m); settings() } }
        tv("Novo agendamento sai, por padrão:", 12.5f, FG, c)
        val r3 = row(c); listOf("api" to "🤖 Sozinho", "lembrete" to "🔔 Me chama", "threads" to "📲 No Threads").forEach { (k, l) -> btn(l, r3, weight = true, small = true, on = Agenda.defaultMode(this) == k) { Agenda.setDefaultMode(this, k); settings() } }

        c = card(); tv("🩺 Diagnóstico da agenda", 14f, FG, c, true)
        tv(Agenda.diag(this), 11.5f, MUTED, c)
        Store.prefs(this).getString("ag_sw_last", "")?.takeIf { it.isNotEmpty() }?.let { tv("Último agendamento detectado na tela do Threads: $it", 11.5f, MUTED, c) }
        Store.prefs(this).getString("ag_sw_seen", "")?.takeIf { it.isNotEmpty() }?.let { tv("Última data lida na tela de agendar: $it", 11.5f, MUTED, c) }
        btn("🧪 Teste da agenda (alarme em 1 min, não publica)", c, small = true) { Agenda.selfTest(this, 60); toast("Feche o app se quiser: a notificação deve chegar em 1 min.") }
        btn("‹ Voltar", root) { draw() }
    }
}
