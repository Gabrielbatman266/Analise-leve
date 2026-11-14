package com.leve.analise

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 🗓 Agenda de publicações do Threads.
 *
 * Como funciona (e por que assim):
 *  - Tudo fica gravado em agenda.json / series.json (sobrevive a reiniciar o celular, fechar o app, atualizar).
 *  - UM alarme exato aponta pro próximo evento (lembrete ou hora de publicar). Quando toca, o app olha TODOS os
 *    agendamentos de novo — então nada se perde se o celular desligou, mudou o fuso ou o alarme atrasou.
 *    Ao ligar o celular, mudar a hora/fuso ou atualizar o app, refaz o alarme e recupera o que ficou pra trás.
 *  - 3 jeitos de sair:
 *      🤖 api       → publica sozinho pela API oficial do Threads (precisa da conexão com permissão de publicar
 *                     e de um lugar pra hospedar a foto por alguns minutos: o Threads só aceita foto por link público).
 *      🔔 lembrete  → na hora, notificação com a foto e a legenda: um toque abre o Threads pronto pra publicar.
 *      📲 threads   → você agendou no próprio Threads (o leitor de tela anota a data/hora sozinho); o Threads publica.
 *  - Nunca publica duas vezes: só sai de "agendada" pra "executando" dentro de uma gravação travada, e a publicação
 *    guarda o id do Threads antes de marcar como publicada (se cair no meio, confere no Threads antes de tentar de novo).
 */
object Agenda {
    private fun f(c: Context) = File(c.filesDir, "agenda.json")
    private fun fs(c: Context) = File(c.filesDir, "series.json")
    private fun p(c: Context) = Store.prefs(c)

    fun all(c: Context): List<JSONObject> = JsonFile.peek(f(c))
    fun series(c: Context): List<JSONObject> = JsonFile.peek(fs(c))
    fun get(c: Context, id: String) = all(c).firstOrNull { it.optString("id") == id }
    fun seriesOf(c: Context, sid: String) = series(c).firstOrNull { it.optString("sid") == sid }

    // ---------------- ajustes
    fun remindMin(c: Context) = p(c).getInt("ag_remind", 10)
    fun setRemindMin(c: Context, v: Int) = p(c).edit().putInt("ag_remind", v).apply()
    fun gapMin(c: Context) = p(c).getInt("ag_gap", 20)
    fun setGapMin(c: Context, v: Int) = p(c).edit().putInt("ag_gap", v).apply()
    fun defaultMode(c: Context) = p(c).getString("ag_mode", if (ThreadsPublish.ready(c)) "api" else "lembrete") ?: "lembrete"
    fun setDefaultMode(c: Context, v: String) = p(c).edit().putString("ag_mode", v).apply()

    private fun newId() = "a" + System.currentTimeMillis().toString(36) + (100..999).random()
    private fun zone() = ZoneId.systemDefault().id

    fun log(o: JSONObject, msg: String) {
        val a = o.optJSONArray("log") ?: JSONArray().also { o.put("log", it) }
        a.put(android.text.format.DateFormat.format("dd/MM HH:mm:ss", System.currentTimeMillis()).toString() + " · " + msg)
        while (a.length() > 20) a.remove(0)
    }

    // ---------------------------------------------------------------- criar / editar / apagar
    /**
     * content: "auto" (a fila escolhe foto e legenda na hora), "foto" (foto [photo] escolhida), "texto" (só texto).
     * cap vazio = sorteia a legenda na hora.
     */
    fun create(c: Context, at: Long, mode: String, content: String, photo: String = "", cap: String = "", sid: String = "", occ: String = ""): JSONObject {
        val o = JSONObject().put("id", newId()).put("at", at).put("zone", zone()).put("status", if (mode == "threads") AgendaCore.NO_THREADS else AgendaCore.AGENDADA)
            .put("mode", mode).put("content", content).put("photo", photo).put("cap", cap).put("created", System.currentTimeMillis())
        if (sid.isNotEmpty()) o.put("sid", sid).put("occ", occ)
        log(o, "criado para " + AgendaCore.fmt(at))
        JsonFile.edit(f(c)) { it.add(o) }
        reschedule(c)
        return o
    }

    fun update(c: Context, id: String, change: (JSONObject) -> Unit) {
        JsonFile.edit(f(c)) { l -> l.firstOrNull { it.optString("id") == id }?.let { o ->
            val before = o.optLong("at")
            change(o); o.put("upd", System.currentTimeMillis())
            if (o.optLong("at") != before) { o.put("remindSent", false); if (o.optString("status") in setOf(AgendaCore.PENDENTE, AgendaCore.ERRO, AgendaCore.ATRASADA)) o.put("status", if (o.optString("mode") == "threads") AgendaCore.NO_THREADS else AgendaCore.AGENDADA); log(o, "horário → " + AgendaCore.fmt(o.optLong("at"))) }
            if (o.has("sid")) o.put("override", true) // editada à parte: a série não mexe mais nela
        } }
        reschedule(c)
    }

    /** Muda campos internos (status, ids da API, log) sem contar como edição sua. */
    fun patch(c: Context, id: String, change: (JSONObject) -> Unit) {
        JsonFile.edit(f(c)) { l -> l.firstOrNull { it.optString("id") == id }?.let { change(it); it.put("upd", System.currentTimeMillis()) } }
    }

    fun reschedule(c: Context, id: String, at: Long) = update(c, id) { it.put("at", at) }

    fun duplicate(c: Context, id: String, at: Long): JSONObject? {
        val o = get(c, id) ?: return null
        return create(c, at, o.optString("mode"), o.optString("content"), o.optString("photo"), o.optString("cap"))
    }

    /** Apaga um. Se for de uma série, a série não recria essa data. */
    fun delete(c: Context, id: String) {
        val o = get(c, id) ?: return
        if (o.has("sid")) JsonFile.edit(fs(c)) { l -> l.firstOrNull { it.optString("sid") == o.optString("sid") }?.let { s ->
            val ex = s.optJSONArray("exceptions") ?: JSONArray().also { s.put("exceptions", it) }; ex.put(o.optString("occ")) } }
        JsonFile.edit(f(c)) { l -> l.removeAll { it.optString("id") == id } }
        reschedule(c)
    }

    // ---------------------------------------------------------------- séries (recorrência)
    fun ruleOf(s: JSONObject) = AgendaCore.Rule(
        freq = s.optString("freq", "diaria"), every = s.optInt("every", 1),
        days = s.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } ?: emptySet(),
        times = s.optJSONArray("times")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("20:00"),
        start = LocalDate.parse(s.optString("start")), end = s.optString("end").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) },
        count = s.optInt("count", 0).takeIf { it > 0 }, zone = s.optString("zone", zone()))

    /** Salva (cria ou altera) uma série e refaz as próximas datas dela. Datas editadas à parte ficam como estão. */
    fun saveSeries(c: Context, s: JSONObject): String {
        val sid = s.optString("sid").ifEmpty { "s" + System.currentTimeMillis().toString(36) }.also { s.put("sid", it) }
        if (!s.has("zone")) s.put("zone", zone())
        JsonFile.edit(fs(c)) { l -> l.removeAll { it.optString("sid") == sid }; l.add(s) }
        // apaga as futuras que ainda não foram mexidas e recria pela regra nova
        val now = System.currentTimeMillis()
        JsonFile.edit(f(c)) { l -> l.removeAll { it.optString("sid") == sid && !it.optBoolean("override") && it.optLong("at") > now && it.optString("status") in setOf(AgendaCore.AGENDADA, AgendaCore.NO_THREADS) } }
        materialize(c)
        return sid
    }

    /** Apaga a série inteira (as que já saíram ficam no histórico). */
    fun deleteSeries(c: Context, sid: String) {
        val now = System.currentTimeMillis()
        JsonFile.edit(fs(c)) { l -> l.removeAll { it.optString("sid") == sid } }
        JsonFile.edit(f(c)) { l -> l.removeAll { it.optString("sid") == sid && it.optLong("at") > now && it.optString("status") in AgendaCore.ACTIVE } }
        reschedule(c)
    }

    /** Cria as datas das séries pros próximos 21 dias (o resto vai sendo criado conforme o tempo passa). */
    fun materialize(c: Context) {
        val now = System.currentTimeMillis(); val to = now + 21 * 86400_000L
        val have = all(c).filter { it.has("sid") }.map { it.optString("sid") + "|" + it.optString("occ") }.toSet()
        val add = ArrayList<JSONObject>()
        series(c).forEach { s ->
            val ex = s.optJSONArray("exceptions")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
            val occ = try { AgendaCore.expand(ruleOf(s), now - 60_000L, to, ex) } catch (e: Exception) { emptyList() }
            occ.filter { (s.optString("sid") + "|" + it.key) !in have }.forEach { o ->
                val mode = s.optString("mode", "lembrete")
                add += JSONObject().put("id", newId()).put("at", o.at).put("zone", s.optString("zone")).put("status", if (mode == "threads") AgendaCore.NO_THREADS else AgendaCore.AGENDADA)
                    .put("mode", mode).put("content", s.optString("content", "auto")).put("photo", s.optString("photo")).put("cap", s.optString("cap"))
                    .put("sid", s.optString("sid")).put("occ", o.key).put("created", now)
            }
        }
        if (add.isNotEmpty()) JsonFile.edit(f(c)) { it.addAll(add) }
        reschedule(c)
    }

    // ---------------------------------------------------------------- conflitos
    fun conflicts(c: Context): Set<String> = AgendaCore.conflicts(
        all(c).filter { it.optString("status") in AgendaCore.ACTIVE }.map { AgendaCore.Slot(it.optString("id"), it.optLong("at")) }, gapMin(c))

    fun conflictsAt(c: Context, at: Long, exceptId: String = ""): List<JSONObject> =
        all(c).filter { it.optString("id") != exceptId && it.optString("status") in AgendaCore.ACTIVE && Math.abs(it.optLong("at") - at) < gapMin(c) * 60_000L }

    // ---------------------------------------------------------------- alarme (um só, pro próximo evento)
    private fun nextEvent(c: Context): Long? {
        val rm = remindMin(c) * 60_000L; val now = System.currentTimeMillis()
        return all(c).filter { it.optString("status") in setOf(AgendaCore.AGENDADA, AgendaCore.NO_THREADS) }.flatMap { o ->
            val at = o.optLong("retryAt").takeIf { it > 0 } ?: o.optLong("at")
            listOfNotNull(if (!o.optBoolean("remindSent") && at - rm > now) at - rm else null, at)
        }.filter { it > now - 60_000L }.minOrNull()
    }

    fun reschedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 7701, Intent(c, AgendaReceiver::class.java).setAction("tick"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val t = nextEvent(c)
        if (t == null) { am.cancel(pi); p(c).edit().remove("ag_next").apply(); return }
        val at = maxOf(t, System.currentTimeMillis() + 1000)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        p(c).edit().putLong("ag_next", at).apply()
    }

    fun exactOk(c: Context) = Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    // ---------------------------------------------------------------- quando o alarme toca (e de hora em hora, como rede de segurança)
    @Synchronized fun tick(c: Context) {
        val now = System.currentTimeMillis(); val rm = remindMin(c) * 60_000L
        materialize(c)
        val toRemind = ArrayList<JSONObject>(); val due = ArrayList<JSONObject>(); val late = ArrayList<JSONObject>()
        JsonFile.edit(f(c)) { l -> l.forEach { o ->
            val st = o.optString("status"); val at = o.optLong("retryAt").takeIf { it > 0 } ?: o.optLong("at")
            if (st in setOf(AgendaCore.AGENDADA, AgendaCore.NO_THREADS) && !o.optBoolean("remindSent") && now >= at - rm && now < at) { o.put("remindSent", true); toRemind += JSONObject(o.toString()) }
            if (st == AgendaCore.AGENDADA && now >= at) {
                when {
                    o.optString("mode") == "teste" -> { o.put("status", AgendaCore.PUBLICADA).put("delay", now - o.optLong("at")); log(o, "🧪 teste: alarme tocou com ${(now - o.optLong("at")) / 1000} s de atraso"); due += JSONObject(o.toString()) }
                    o.optString("mode") == "api" && now - at < 6 * 3600_000L -> { o.put("status", AgendaCore.EXECUTANDO).put("execAt", now); log(o, "publicando pela API…"); due += JSONObject(o.toString()) }
                    else -> { o.put("status", AgendaCore.PENDENTE); log(o, if (o.optString("mode") == "api") "passou da hora demais pra publicar sozinho: confira" else "hora de postar"); due += JSONObject(o.toString()) }
                }
            }
            // publicando há mais de 15 min (o app caiu no meio): volta pra fila de tentativas (a publicação confere antes de repetir)
            if (st == AgendaCore.EXECUTANDO && now - o.optLong("execAt") > 15 * 60_000L) { o.put("status", AgendaCore.AGENDADA).put("retryAt", now); log(o, "retomando publicação interrompida") }
            if (st == AgendaCore.PENDENTE && now - o.optLong("at") > 30 * 60_000L && !o.optBoolean("lateSent")) { o.put("lateSent", true); late += JSONObject(o.toString()) }
        } }
        toRemind.forEach { notifyRemind(c, it) }
        due.forEach { o ->
            when {
                o.optString("mode") == "teste" -> notifySimple(c, o, "🧪 Teste da agenda: tocou no horário", "Atraso de ${o.optLong("delay") / 1000} s. Tudo certo com os alarmes.")
                o.optString("status") == AgendaCore.EXECUTANDO -> PublishJob.start(c)
                else -> notifyDue(c, resolve(c, o.optString("id")) ?: o)
            }
        }
        late.forEach { notifyDue(c, it, late = true) }
        reschedule(c)
    }

    /** Conteúdo "a fila escolhe": define a foto e a legenda agora (perto da hora), pelo mesmo sorteio da fila. */
    fun resolve(c: Context, id: String): JSONObject? {
        val o = get(c, id) ?: return null
        if (o.optString("mode") == "threads") return o
        val needPhoto = o.optString("content") == "auto" && o.optString("photo").isEmpty()
        val needCap = o.optString("cap").isBlank()
        if (!needPhoto && !needCap) return o
        var photo = o.optString("photo"); var cap = o.optString("cap")
        when {
            o.optString("content") == "texto" && needCap -> cap = TextPosts.now(c)?.second ?: Captions.randomCaption(c, emptySet())
            needPhoto -> Captions.randomPick(c, promo = false)?.let { (pid, pc) -> photo = pid; if (needCap) cap = pc; Captions.remember(c, pid, pc) }
            needCap -> cap = PostQueue.get(c, photo)?.let { PostQueue.captionOf(it) }?.takeIf { it.isNotBlank() } ?: Captions.randomCaption(c, emptySet())
        }
        patch(c, id) { it.put("photo", photo).put("cap", cap); log(it, "a fila escolheu " + (if (photo.isEmpty()) "o texto" else "a foto e a legenda")) }
        return get(c, id)
    }

    // ---------------------------------------------------------------- publicado (por qualquer caminho)
    /** Marca como publicada e alimenta o aprendizado (histórico de envios com a hora real). */
    fun markPublished(c: Context, id: String, how: String, mediaId: String = "", link: String = "", sideEffects: Boolean = true) {
        var o: JSONObject? = null
        JsonFile.edit(f(c)) { l -> l.firstOrNull { it.optString("id") == id }?.let { x ->
            if (x.optString("status") == AgendaCore.PUBLICADA && x.optBoolean("logged")) return@let
            x.put("status", AgendaCore.PUBLICADA).put("pubAt", System.currentTimeMillis()).put("logged", true)
            if (mediaId.isNotEmpty()) x.put("mid", mediaId); if (link.isNotEmpty()) x.put("link", link)
            log(x, "✓ publicada ($how)"); o = JSONObject(x.toString())
        } }
        val e = o ?: return
        val pid = e.optString("photo"); val cap = e.optString("cap"); val at = if (e.optString("mode") == "threads") e.optLong("at") else e.optLong("pubAt")
        val logId = pid.ifEmpty { "texto_" + e.optString("id") }
        if (!sideEffects || e.optBoolean("prelogged")) { reschedule(c); return } // o "Publiquei?" do app já registrou tudo
        if (SendLog.all(c).none { it.optString("id") == logId && Math.abs(it.optLong("at") - at) < 6 * 3600_000L }) {
            SendLog.add(c, "fila", logId, cap, "Threads", at = at); SendLog.confirm(c, "fila", logId)
        }
        if (pid.isNotEmpty()) PostQueue.update(c, pid) { it.put("status", "postada"); it.put("postedAt", at); it.put("escolhida", cap); it.put("usos", it.optInt("usos") + 1) }
        if (cap.isNotBlank()) CaptionDB.used(c, cap)
        if (pid.isNotEmpty()) try { RateLater.schedulePhoto(c, pid) } catch (e: Exception) { }
        reschedule(c)
    }

    /** Adia [min] minutos (a partir de agora). */
    fun snooze(c: Context, id: String, min: Int) {
        patch(c, id) { it.put("status", if (it.optString("mode") == "threads") AgendaCore.NO_THREADS else AgendaCore.AGENDADA).put("at", System.currentTimeMillis() + min * 60_000L)
            .put("remindSent", true).remove("retryAt"); it.remove("lateSent"); log(it, "adiado $min min") }
        reschedule(c)
    }

    /** Você confirmou no "Publiquei?" do app: se essa foto/texto era de um agendamento aberto, marca como publicada. */
    fun onConfirmed(c: Context, postId: String) {
        val open = p(c).getString("ag_open", "") ?: ""
        val now = System.currentTimeMillis()
        all(c).filter { it.optString("status") in setOf(AgendaCore.PENDENTE, AgendaCore.AGENDADA, AgendaCore.ERRO) && Math.abs(it.optLong("at") - now) < 6 * 3600_000L &&
            (it.optString("id") == open || (it.optString("photo").isNotEmpty() && it.optString("photo") == postId) || "texto_" + it.optString("id") == postId) }
            .minByOrNull { Math.abs(it.optLong("at") - now) }?.let { markPublished(c, it.optString("id"), "você confirmou", sideEffects = false) }
        if (open.isNotEmpty()) p(c).edit().remove("ag_open").apply()
    }

    /** O Threads avisou "Thread posted" (notificação): confirma o agendado/pendente mais perto de agora. */
    fun onThreadsPosted(c: Context) {
        val now = System.currentTimeMillis()
        all(c).filter { it.optString("status") in setOf(AgendaCore.NO_THREADS, AgendaCore.PENDENTE) && Math.abs(it.optLong("at") - now) < 45 * 60_000L }
            .minByOrNull { Math.abs(it.optLong("at") - now) }?.let { markPublished(c, it.optString("id"), "o Threads avisou que publicou") }
    }

    /**
     * Você agendou NA MÃO no Threads: o leitor de tela leu a data/hora (e a legenda do post). Anota na agenda,
     * liga com a foto/legenda do app quando reconhece, e avisa. Devolve o texto do aviso.
     */
    /** Chave pra comparar legendas (a tela pode trazer espaços/quebras diferentes). */
    fun ck(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim().take(40)

    fun addDetected(c: Context, at: Long, cap: String): String {
        val k = ck(cap)
        val msg = "🗓 Agendado no Threads: " + AgendaCore.fmt(at) + " · anotei na Agenda"
        val dup = all(c).firstOrNull { it.optString("mode") == "threads" && it.optString("status") in AgendaCore.ACTIVE &&
            ((Math.abs(it.optLong("at") - at) < 2 * 60_000L && (k.isEmpty() || ck(it.optString("cap")) == k || it.optString("cap").isEmpty())) ||
             (k.isNotEmpty() && ck(it.optString("cap")) == k && Math.abs(it.optLong("at") - at) < 12 * 3600_000L)) }
        if (dup != null) {
            // mesmo post: atualiza a hora (você mudou o horário) e a legenda que faltava
            if (dup.optLong("at") != at || (dup.optString("cap").isEmpty() && cap.isNotBlank())) patch(c, dup.optString("id")) { it.put("at", at); if (it.optString("cap").isEmpty() && cap.isNotBlank()) it.put("cap", cap.trim()); log(it, "horário conferido: " + AgendaCore.fmt(at)) }
            reschedule(c); return msg
        }
        val photo = if (k.isEmpty()) "" else PostQueue.load(c).firstOrNull { o -> ck(o.optString("escolhida")) == k || o.optJSONArray("legendas")?.let { a -> (0 until a.length()).any { ck(a.optString(it)) == k } } == true }?.optString("id").orEmpty()
        val b = Batch.load(c); val bi = if (k.isEmpty()) null else b.firstOrNull { !it.optBoolean("done") && ck(it.optString("cap")) == k }
        val pid = photo.ifEmpty { bi?.optString("id").orEmpty() }
        val fullCap = bi?.optString("cap")?.takeIf { it.isNotBlank() } ?: cap.trim()
        // já tinha um lembrete/agendada desse post? vira "agendada no Threads"
        val mine = if (k.isEmpty()) null else all(c).firstOrNull { it.optString("status") in setOf(AgendaCore.AGENDADA, AgendaCore.PENDENTE, AgendaCore.ERRO) && ck(it.optString("cap")) == k }
        val id = if (mine != null) { patch(c, mine.optString("id")) { it.put("mode", "threads").put("status", AgendaCore.NO_THREADS).put("at", at).put("remindSent", false); log(it, "você agendou no Threads: " + AgendaCore.fmt(at)) }; mine.optString("id") }
            else create(c, at, "threads", if (pid.isEmpty() && k.isNotEmpty()) "texto" else "foto", pid, fullCap).optString("id").also { nid -> patch(c, nid) { log(it, "detectado na tela do Threads") } }
        // item do lote: já conta como enviado (igual ao "✓ Agendei" do lote)
        if (bi != null) {
            bi.put("done", true).put("at", at); Batch.save(c, b)
            Batch.scheduled(c, bi); patch(c, id) { it.put("prelogged", true) }
        }
        reschedule(c)
        return msg
    }

    /** "✓ Agendei" no lote (sem o leitor ter visto a tela): anota na agenda também. */
    fun fromBatch(c: Context, o: JSONObject) {
        val k = ck(o.optString("cap"))
        if (all(c).any { it.optString("mode") == "threads" && ck(it.optString("cap")) == k && Math.abs(it.optLong("at") - o.optLong("at")) < 12 * 3600_000L }) return
        val e = create(c, o.optLong("at"), "threads", "foto", o.optString("id"), o.optString("cap"))
        patch(c, e.optString("id")) { it.put("prelogged", true); log(it, "você confirmou no lote") }
    }

    // ---------------------------------------------------------------- notificações
    private fun ch(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("agenda", "Agenda de posts", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("agenda_lembrete", "Agenda: avisos antes da hora", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun nid(o: JSONObject) = 8000 + (o.optString("id").hashCode() and 0x3ff)
    private fun openAgenda(c: Context, o: JSONObject) = PendingIntent.getActivity(c, nid(o), Intent(c, AgendaActivity::class.java).putExtra("id", o.optString("id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun act(c: Context, o: JSONObject, a: String, code: Int) = PendingIntent.getBroadcast(c, nid(o) * 10 + code, Intent(c, AgendaReceiver::class.java).setAction(a).putExtra("id", o.optString("id")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun notifySimple(c: Context, o: JSONObject, title: String, text: String) {
        ch(c)
        try { c.getSystemService(NotificationManager::class.java).notify(nid(o), Notification.Builder(c, "agenda").setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(openAgenda(c, o)).setAutoCancel(true).build()) } catch (e: SecurityException) { }
    }

    private fun notifyRemind(c: Context, o: JSONObject) {
        ch(c)
        val how = when (o.optString("mode")) { "api" -> "vai sair sozinho"; "threads" -> "o Threads publica"; else -> "eu te chamo na hora" }
        try { c.getSystemService(NotificationManager::class.java).notify(nid(o), Notification.Builder(c, "agenda_lembrete").setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("🗓 Post às " + android.text.format.DateFormat.format("HH:mm", o.optLong("at")) + " ($how)")
            .setContentText(o.optString("cap").ifBlank { "Foto e legenda escolhidas na hora pela fila" }).setContentIntent(openAgenda(c, o)).setAutoCancel(true).build()) } catch (e: SecurityException) { }
    }

    /** Hora de postar (lembrete) ou deu errado/atrasou: foto + legenda e o botão que abre o Threads pronto. */
    fun notifyDue(c: Context, o: JSONObject, late: Boolean = false) {
        ch(c)
        val b = Notification.Builder(c, "agenda").setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle((if (late) "⏰ Atrasado: " else if (o.optString("status") == AgendaCore.ERRO) "⚠ Não publicou: " else "🗓 Hora de postar: ") + android.text.format.DateFormat.format("HH:mm", o.optLong("at")))
            .setContentText(o.optString("cap").ifBlank { "Toque pra postar" }).setContentIntent(openAgenda(c, o)).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "📲 Postar agora", act(c, o, "post", 1)).build())
            .addAction(Notification.Action.Builder(null, "⏱ +15 min", act(c, o, "snooze", 2)).build())
            .addAction(Notification.Action.Builder(null, "✓ Já postei", act(c, o, "done", 3)).build())
        o.optString("photo").takeIf { it.isNotEmpty() }?.let { pid -> try { BitmapFactory.decodeFile(PostQueue.imageFile(c, pid).path, BitmapFactory.Options().apply { inSampleSize = 4 })?.let { b.setLargeIcon(it).setStyle(Notification.BigPictureStyle().bigPicture(it).setSummaryText(o.optString("cap"))) } } catch (e: Exception) { } }
        if (o.optString("photo").isEmpty()) b.setStyle(Notification.BigTextStyle().bigText(o.optString("cap")))
        try { c.getSystemService(NotificationManager::class.java).notify(nid(o), b.build()) } catch (e: SecurityException) { }
    }

    fun cancelNotif(c: Context, id: String) = get(c, id)?.let { c.getSystemService(NotificationManager::class.java).cancel(nid(it)) }

    /** "Postar agora" (lembrete): abre o Threads com foto+legenda; o leitor/notificação do Threads confirma depois. */
    fun postNow(c: Context, id: String) {
        val o = resolve(c, id) ?: return
        val i = if (o.optString("photo").isEmpty()) Intent(c, PostActivity::class.java).putExtra("id", "texto_" + id).putExtra("caption", o.optString("cap"))
            else Intent(c, PostActivity::class.java).putExtra("id", o.optString("photo")).putExtra("caption", o.optString("cap"))
        patch(c, id) { it.put("status", AgendaCore.PENDENTE).put("opened", System.currentTimeMillis()); log(it, "abriu o Threads pra postar") }
        p(c).edit().putString("ag_open", id).apply()
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------------------------------------------------------------- 🧪 autoteste no aparelho
    /** Cria um agendamento de teste (não publica nada) daqui a [sec] segundos; o resultado aparece na lista e numa notificação. */
    fun selfTest(c: Context, sec: Int = 60): JSONObject = create(c, System.currentTimeMillis() + sec * 1000L, "teste", "texto", "", "🧪 Teste da agenda (não publica nada)")

    fun summary(c: Context): String {
        val now = System.currentTimeMillis()
        val l = all(c)
        val next = l.filter { it.optString("status") in AgendaCore.ACTIVE && it.optLong("at") > now - 600_000L }.minByOrNull { it.optLong("at") }
        val today = l.count { android.text.format.DateUtils.isToday(it.optLong("at")) && it.optString("status") != AgendaCore.CANCELADA }
        val bad = l.count { AgendaCore.effective(it.optString("status"), it.optString("mode"), it.optLong("at"), now) in setOf(AgendaCore.ATRASADA, AgendaCore.ERRO, AgendaCore.PENDENTE) }
        return (if (next != null) "Próximo: " + AgendaCore.fmt(next.optLong("at")) else "Nada agendado") + " · hoje: $today" + (if (bad > 0) " · ⚠ $bad precisa(m) de você" else "") +
            " · " + (if (ThreadsPublish.ready(c)) "🤖 publica sozinho" else "🔔 lembretes (publicação automática não configurada)")
    }

    fun diag(c: Context): String {
        val l = all(c); val now = System.currentTimeMillis()
        val by = l.groupingBy { AgendaCore.effective(it.optString("status"), it.optString("mode"), it.optLong("at"), now) }.eachCount()
        val test = l.filter { it.optString("mode") == "teste" }.maxByOrNull { it.optLong("created") }
        return "itens ${l.size} " + by.entries.joinToString(" ") { "${it.key}:${it.value}" } + " · séries ${series(c).size} · alarme exato ${exactOk(c)} · próximo alarme " +
            (p(c).getLong("ag_next", 0).takeIf { it > 0 }?.let { AgendaCore.fmt(it) } ?: "-") + " · publicação: " + ThreadsPublish.status(c) +
            (test?.let { " · último teste: " + if (it.optString("status") == AgendaCore.PUBLICADA) "✓ tocou com ${it.optLong("delay") / 1000} s de atraso" else "esperando (" + AgendaCore.fmt(it.optLong("at")) + ")" } ?: "")
    }
}

/** Alarme da agenda + ligar o celular + mudar hora/fuso + app atualizado + botões das notificações. */
class AgendaReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pr = goAsync()
        Thread {
            try {
                val id = i.getStringExtra("id").orEmpty()
                when (i.action) {
                    "post" -> { Agenda.cancelNotif(c, id); Agenda.postNow(c, id) }
                    "snooze" -> { Agenda.cancelNotif(c, id); Agenda.snooze(c, id, 15) }
                    "done" -> { Agenda.cancelNotif(c, id); Agenda.markPublished(c, id, "você confirmou") }
                    // 📅 o Threads tinha 25 agendados: um já saiu, o lote continua sozinho
                    "piloto" -> android.os.Handler(android.os.Looper.getMainLooper()).post { try { AutoPilot.tick(c) } catch (e: Exception) { ErrLog.log(c, "piloto", e) } }
                    "lote" -> android.os.Handler(android.os.Looper.getMainLooper()).post { ScreenReader.instance?.let { if (!it.autoRunning) it.runBatchAuto() } }
                    else -> Agenda.tick(c) // tick, BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, MY_PACKAGE_REPLACED
                }
            } catch (e: Exception) { ErrLog.log(c, "agenda", e) } finally { pr.finish() }
        }.start()
    }
}

/** Publica pela API em segundo plano (com internet garantida pelo sistema), sem depender do app aberto. */
class PublishJob : JobService() {
    companion object {
        fun start(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            js.schedule(JobInfo.Builder(7702, ComponentName(c, PublishJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(60_000L).setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            var retry = false
            try { retry = ThreadsPublish.runPending(this) } catch (e: Exception) { ErrLog.log(this, "publicar", e); retry = true }
            jobFinished(params, retry)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}
