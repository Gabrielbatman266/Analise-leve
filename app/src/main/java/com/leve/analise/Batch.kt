package com.leve.analise

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 🗓 Agendar vários de uma vez no Threads.
 * O app monta o lote (fotos e legendas pelo mesmo sorteio da fila, horários nas horas em que sua audiência está ativa)
 * e te leva post por post: abre o Threads com foto+legenda, avisa o horário; você toca ⋯ › Agendar; volta e confirma.
 */
object Batch {
    private fun p(c: Context) = Store.prefs(c)
    fun load(c: Context): MutableList<JSONObject> = try { val a = JSONArray(p(c).getString("lote", "[]")); (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() } catch (e: Exception) { mutableListOf() }
    fun save(c: Context, l: List<JSONObject>) = p(c).edit().putString("lote", JSONArray().apply { l.forEach { put(it) } }.toString()).apply()
    fun clear(c: Context) = p(c).edit().remove("lote").remove("lote_wait").apply()

    /** Horários: as horas mais ativas da sua audiência dentro da janela, com espaço mínimo entre os posts. */
    fun slots(c: Context, n: Int, from: Long, to: Long): List<Long> {
        val step = 10 * 60_000L
        val gap = maxOf(25 * 60_000L, (to - from) / (n * 1.6).toLong())
        val cand = generateSequence(from) { it + step }.takeWhile { it <= to }.toList()
        val cal = Calendar.getInstance()
        val score = cand.associateWith { t -> cal.timeInMillis = t; (try { Audience.score(c, cal.get(Calendar.HOUR_OF_DAY)) } catch (e: Exception) { null } ?: 0.5) + Math.random() * 0.15 }
        val out = ArrayList<Long>()
        cand.sortedByDescending { score[it] }.forEach { t -> if (out.size < n && out.all { Math.abs(it - t) >= gap }) out += t }
        return out.sorted()
    }

    /** Monta o lote: [n] posts, hoje até 23h30 ou amanhã das 8h às 23h30. */
    fun build(c: Context, n: Int, tomorrow: Boolean): Int {
        val cal = Calendar.getInstance()
        val from: Long; val to: Long
        if (tomorrow) { cal.add(Calendar.DAY_OF_YEAR, 1); cal.set(Calendar.HOUR_OF_DAY, 8); cal.set(Calendar.MINUTE, 0); from = cal.timeInMillis; cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 30); to = cal.timeInMillis }
        else { from = System.currentTimeMillis() + 40 * 60_000L; cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 30); to = cal.timeInMillis }
        if (to - from < 30 * 60_000L) return 0
        val times = slots(c, n, from, to)
        val l = ArrayList<JSONObject>()
        times.forEach { t ->
            val pk = Captions.randomPick(c, promo = false) ?: return@forEach
            Captions.remember(c, pk.first, pk.second) // reservada: o próximo sorteio pega outra
            l += JSONObject().put("id", pk.first).put("cap", pk.second).put("at", t).put("done", false)
        }
        save(c, l); return l.size
    }

    /** Lote pronto (vários dias): troca o lote pelos itens dados. */
    fun setItems(c: Context, l: List<JSONObject>) { save(c, l.sortedBy { it.optLong("at") }); p(c).edit().remove("lote_wait").apply() }

    /** Horário que já passou (ou está em cima): refaz os horários dos que faltam, a partir de daqui a 20 min. */
    fun refreshTimes(c: Context) {
        val l = load(c); val left = l.filter { !it.optBoolean("done") }
        if (left.none { it.optLong("at") < System.currentTimeMillis() + 15 * 60_000L }) return
        val cal = Calendar.getInstance()
        val from = System.currentTimeMillis() + 20 * 60_000L
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 30)
        var to = cal.timeInMillis
        if (to - from < left.size * 25 * 60_000L) { cal.add(Calendar.DAY_OF_YEAR, 1); to = cal.timeInMillis } // não cabe hoje: vai até amanhã
        val t = slots(c, left.size, from, to)
        left.forEachIndexed { k, o -> t.getOrNull(k)?.let { o.put("at", it) } }
        save(c, l.sortedBy { it.optBoolean("done").not().let { nd -> if (nd) 1 else 0 } * 0 + it.optLong("at") })
    }

    /** Você confirmou que agendou: entra no histórico com o HORÁRIO AGENDADO (é dele que contam idade, números e nota). */
    fun scheduled(c: Context, o: JSONObject) {
        val id = o.optString("id"); val cap = o.optString("cap"); val at = o.optLong("at")
        SendLog.add(c, "fila", id, cap, "Threads", at = at)
        SendLog.confirm(c, "fila", id)
        PostQueue.update(c, id) { it.put("status", "postada"); it.put("postedAt", at); it.put("escolhida", cap); it.put("usos", it.optInt("usos") + 1) }
        if (cap.isNotBlank()) CaptionDB.used(c, cap)
        Lab.sent(c, cap)
    }
}

class BatchActivity : Activity() {
    private val BG = 0xFF000000.toInt(); private val CARD = 0xFF131319.toInt(); private val SURF = 0xFF1E1E27.toInt()
    private val FG = 0xFFF1F2F6.toInt(); private val MUTED = 0xFF8F94A3.toInt(); private val ACCENT = 0xFFFF5C82.toInt(); private val OK = 0xFF4CC68E.toInt()
    private lateinit var root: LinearLayout
    private var count = 5; private var tomorrow = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hm(t: Long) = android.text.format.DateFormat.format("HH:mm", t).toString()
    private fun tv(t: String, s: Float, c: Int, p: LinearLayout, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = s; setTextColor(c); if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(0, dp(3), 0, dp(3)); p.addView(this)
    }
    private fun btn(t: String, p: LinearLayout, primary: Boolean = false, weight: Boolean = false, on: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER; setTextColor(if (primary) 0xFF1A0A10.toInt() else if (on) 0xFFFF9DB4.toInt() else FG)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(12), dp(13), dp(12), dp(13))
        background = (if (primary) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, 0xFFFF8A65.toInt())) else GradientDrawable().apply { setColor(if (on) 0x2EFF5C82 else SURF); if (on) setStroke(dp(1), ACCENT) }).apply { cornerRadius = dp(14).toFloat() }
        setOnClickListener { click() }
        p.addView(this, (if (weight) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2)).apply { setMargins(dp(3), dp(5), dp(3), dp(5)) })
    }
    private fun row(p: LinearLayout) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { p.addView(it, LinearLayout.LayoutParams(-1, -2)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(28)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
        Batch.refreshTimes(this)
        draw()
    }

    override fun onResume() {
        super.onResume()
        val w = Store.prefs(this).getInt("lote_wait", -1); if (w < 0) return
        val l = Batch.load(this); val o = l.getOrNull(w) ?: return
        Store.prefs(this).edit().remove("lote_wait").apply()
        if (o.optBoolean("done")) { // o leitor viu você agendar: já anotou
            Toast.makeText(this, "✓ Detectei: agendado para " + AgendaCore.fmt(o.optLong("at")), Toast.LENGTH_LONG).show(); draw(); return
        }
        AlertDialog.Builder(this).setTitle("Agendou o post das ${hm(o.optLong("at"))}?")
            .setPositiveButton("✓ Agendei") { _, _ -> o.put("done", true); Batch.save(this, l); Batch.scheduled(this, o); try { Agenda.fromBatch(this, o) } catch (e: Exception) { }; draw()
                if (l.any { !it.optBoolean("done") }) next() else Toast.makeText(this, "🎉 Lote todo agendado", Toast.LENGTH_LONG).show() }
            .setNegativeButton("Não", null).show()
    }

    private fun draw() {
        root.removeAllViews()
        tv("🗓 Agendar vários de uma vez", 22f, FG, root, true)
        val l = Batch.load(this)
        if (l.isEmpty()) {
            tv("O app escolhe as fotos e legendas (mesmo sorteio da fila) e os horários em que sua audiência está mais ativa. Depois te leva um por um: abre o Threads com tudo pronto e avisa o horário. Você toca ⋯ › Agendar, escolhe o horário e volta aqui.", 13f, MUTED, root)
            tv("Quantos posts:", 13f, FG, root, true)
            val r = row(root); listOf(3, 5, 8, 10).forEach { n -> btn("$n", r, weight = true, on = n == count) { count = n; draw() } }
            tv("Quando:", 13f, FG, root, true)
            val r2 = row(root)
            btn("Hoje (até 23h30)", r2, weight = true, on = !tomorrow) { tomorrow = false; draw() }
            btn("Amanhã (8h–23h30)", r2, weight = true, on = tomorrow) { tomorrow = true; draw() }
            btn("✨ Montar o lote", root, primary = true) {
                val n = Batch.build(this, count, tomorrow)
                if (n == 0) Toast.makeText(this, "Sem horário sobrando hoje ou fila vazia: tente Amanhã", Toast.LENGTH_LONG).show()
                draw()
            }
            return
        }
        val left = l.count { !it.optBoolean("done") }
        tv("${l.size - left} de ${l.size} agendados", 13f, if (left == 0) OK else MUTED, root)
        l.forEachIndexed { i, o ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(16).toFloat() } }
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            card.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(SURF)
                try { BitmapFactory.decodeFile(PostQueue.imageFile(this@BatchActivity, o.optString("id")).path, BitmapFactory.Options().apply { inSampleSize = 8 })?.let { setImageBitmap(it) } } catch (e: Exception) { } },
                LinearLayout.LayoutParams(dp(48), dp(64)).apply { rightMargin = dp(12) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            card.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            tv((if (o.optBoolean("done")) "✓ " else "") + hm(o.optLong("at")), 17f, if (o.optBoolean("done")) OK else FG, col, true)
            tv(o.optString("cap").replace('\n', ' ').take(70), 12f, MUTED, col)
            if (!o.optBoolean("done")) card.setOnClickListener { itemMenu(i) }
        }
        if (left > 0) btn("🤖 Agendar todos sozinho no Threads ($left)", root, primary = true) { auto() }
        if (left > 0) btn("▶ Só o próximo · ${hm(l.first { !it.optBoolean("done") }.optLong("at"))}", root) { next() }
        btn("🗑 Recomeçar o lote", root) {
            AlertDialog.Builder(this).setMessage("Apagar este lote e montar outro? (o que você já agendou continua no Threads)").setPositiveButton("Recomeçar") { _, _ -> Batch.clear(this); draw() }.setNegativeButton("Cancelar", null).show()
        }
    }

    private fun itemMenu(i: Int) {
        val l = Batch.load(this); val o = l[i]
        AlertDialog.Builder(this).setTitle("Post das ${hm(o.optLong("at"))}").setItems(arrayOf("🔀 Trocar foto e legenda", "⏰ Mudar o horário", "✕ Tirar do lote")) { _, w ->
            when (w) {
                0 -> Captions.randomPick(this, promo = false)?.let { pk -> Captions.remember(this, pk.first, pk.second); o.put("id", pk.first).put("cap", pk.second); Batch.save(this, l); draw() }
                1 -> { val cal = Calendar.getInstance().apply { timeInMillis = o.optLong("at") }
                    android.app.TimePickerDialog(this, { _, h, m -> cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); o.put("at", cal.timeInMillis); Batch.save(this, l.sortedBy { it.optLong("at") }); draw() },
                        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show() }
                else -> { l.removeAt(i); Batch.save(this, l); draw() }
            }
        }.show()
    }

    /** 🤖 Um toque: o leitor de tela agenda todos, um atrás do outro, no Agendar do próprio Threads. */
    private fun auto() {
        val r = ScreenReader.instance
        if (r == null) { Toast.makeText(this, "Ligue o Leitor de tela do Post Leve (Acessibilidade) — é ele que faz os toques no Threads", Toast.LENGTH_LONG).show()
            try { startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { }; return }
        r.runBatchAuto()
    }

    /** Abre o Threads com foto+legenda do próximo e avisa o horário por cima do Threads. */
    private fun next() {
        Batch.refreshTimes(this)
        val l = Batch.load(this); val i = l.indexOfFirst { !it.optBoolean("done") }; if (i < 0) return
        val o = l[i]; val t = hm(o.optLong("at"))
        val day = if (android.text.format.DateUtils.isToday(o.optLong("at"))) "hoje" else "amanhã"
        try {
            // O Agendar só existe no editor COMPLETO do Threads (o do "Compartilhar" não tem). Então: a foto vira a mais recente
            // da galeria, a legenda fica copiada, o Threads abre normal e o leitor faz: novo post › anexar › foto › legenda › ⋯ › Agendar.
            // O Threads só mostra o "Agendar" no ⋯ quando o post já tem TEXTO. Então: manda foto+legenda, o leitor confere se a
            // legenda entrou (escreve se não entrou) e só depois abre ⋯ › Agendar.
            ClipSync.copy(this, o.optString("cap"))
            PostQueue.putInGallery(this, o.optString("id"), force = true)
            Store.prefs(this).edit().putInt("lote_wait", i).apply()
            val r = ScreenReader.instance
            // O editor que abre pelo "Compartilhar" NÃO tem Agendar. Só o do botão de novo post (dentro do Threads) tem.
            // Galeria leve: a pasta PostLeve fica só com a foto deste post, a mais nova do celular → é a 1ª na galeria do Threads.
            val launch = packageManager.getLaunchIntentForPackage(PostQueue.THREADS)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) ?: throw Exception("sem Threads")
            startActivity(launch)
            if (r != null) { r.armCompose("$day às $t", o.optString("cap"), fromShare = false); r.hint("🗓 Montando o post ${i + 1} de ${l.size} pra agendar ($day às $t)…", 3000) }
            else Toast.makeText(this, "Ligue o Leitor de tela pra eu montar o post sozinho. Na mão: + › foto mais recente › colar legenda › ⋯ › Agendar ($day às $t)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(this, "Não achei o Threads", Toast.LENGTH_LONG).show() }
    }
}
