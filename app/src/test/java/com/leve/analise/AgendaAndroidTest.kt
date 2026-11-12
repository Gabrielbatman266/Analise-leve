package com.leve.analise

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

/**
 * Testes da agenda num Android simulado (Robolectric): arquivos, alarmes, notificações, reinício do celular,
 * publicação pela API (contra um servidor falso do Threads que responde como a documentação da Meta) e a tela.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgendaAndroidTest {
    private lateinit var c: Application
    private val min = 60_000L
    private fun now() = System.currentTimeMillis()

    @Before fun setUp() {
        c = RuntimeEnvironment.getApplication()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Agenda.setRemindMin(c, 10); Agenda.setGapMin(c, 20)
    }

    /** O app usa UM alarme só (sempre o mesmo, trocado a cada mudança). */
    private fun alarmAt(): Long? {
        val l = shadowOf(c.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .filter { shadowOf(it.operation).savedIntent.component?.className == AgendaReceiver::class.java.name }
        assertTrue("devia ter 1 alarme só, tem ${l.size}: " + l.joinToString { "${it.triggerAtMs - now()} ms ${it.operation}" }, l.size <= 1)
        return l.firstOrNull()?.triggerAtMs
    }
    private fun notifs() = shadowOf(c.getSystemService(NotificationManager::class.java)).allNotifications

    // ------------------------------------------------------------ criar / editar / excluir / duplicar / persistência
    @Test fun criarEditarDuplicarExcluirEPersistir() {
        val at = now() + 3 * 3600_000L
        val o = Agenda.create(c, at, "lembrete", "texto", "", "olá")
        assertEquals(AgendaCore.AGENDADA, o.optString("status"))
        // gravado em arquivo (sobrevive ao app fechar): lê direto do disco
        val disk = JsonFile.read(java.io.File(c.filesDir, "agenda.json"))
        assertEquals(1, disk.size); assertEquals("olá", disk[0].optString("cap"))

        Agenda.update(c, o.optString("id")) { it.put("cap", "editado").put("at", at + 30 * min) }
        assertEquals("editado", Agenda.get(c, o.optString("id"))!!.optString("cap"))
        assertEquals(at + 30 * min, Agenda.get(c, o.optString("id"))!!.optLong("at"))

        val d = Agenda.duplicate(c, o.optString("id"), at + 5 * 3600_000L)!!
        assertEquals("editado", d.optString("cap")); assertEquals(2, Agenda.all(c).size)

        Agenda.reschedule(c, d.optString("id"), at + 26 * 3600_000L)
        assertEquals(at + 26 * 3600_000L, Agenda.get(c, d.optString("id"))!!.optLong("at"))

        Agenda.delete(c, o.optString("id"))
        assertNull(Agenda.get(c, o.optString("id"))); assertEquals(1, Agenda.all(c).size)
    }

    @Test fun conflitoDeHorario() {
        val at = now() + 5 * 3600_000L
        val a = Agenda.create(c, at, "lembrete", "texto", "", "a")
        val b = Agenda.create(c, at + 10 * min, "lembrete", "texto", "", "b")
        Agenda.create(c, at + 3 * 3600_000L, "lembrete", "texto", "", "c")
        assertEquals(setOf(a.optString("id"), b.optString("id")), Agenda.conflicts(c))
        assertEquals(1, Agenda.conflictsAt(c, at + 15 * min, b.optString("id")).size)
    }

    // ------------------------------------------------------------ repetição
    @Test fun repeticaoSemanalExcecaoEdicaoDeUmaEDaSerie() {
        val start = LocalDate.now().plusDays(1)
        val s = JSONObject().put("freq", "diaria").put("every", 1).put("times", org.json.JSONArray(listOf("10:00", "21:00")))
            .put("start", start.toString()).put("zone", ZoneId.systemDefault().id).put("mode", "lembrete").put("content", "texto").put("cap", "série").put("count", 6)
        val sid = Agenda.saveSeries(c, s)
        var occ = Agenda.all(c).filter { it.optString("sid") == sid }.sortedBy { it.optLong("at") }
        assertEquals(6, occ.size) // 3 dias × 2 horários

        // apaga uma: não volta quando a série é recalculada
        Agenda.delete(c, occ[1].optString("id"))
        Agenda.materialize(c)
        assertEquals(5, Agenda.all(c).count { it.optString("sid") == sid })

        // edita só uma (vira exceção) e depois muda a série inteira: a editada fica como você deixou
        val one = occ[2].optString("id")
        Agenda.update(c, one) { it.put("cap", "só esta") }
        val s2 = Agenda.seriesOf(c, sid)!!.put("cap", "série nova").put("times", org.json.JSONArray(listOf("11:00")))
        Agenda.saveSeries(c, s2)
        occ = Agenda.all(c).filter { it.optString("sid") == sid }
        assertEquals("só esta", Agenda.get(c, one)!!.optString("cap"))
        assertTrue(occ.filter { it.optString("id") != one }.all { it.optString("cap") == "série nova" })

        // apaga a série toda
        Agenda.deleteSeries(c, sid)
        assertTrue(Agenda.all(c).none { it.optString("sid") == sid && it.optLong("at") > now() })
        assertNull(Agenda.seriesOf(c, sid))
    }

    // ------------------------------------------------------------ alarme, lembrete, hora de postar
    @Test fun alarmeApontaProProximoEventoEAvisoAntes() {
        val at = now() + 60 * min
        Agenda.create(c, at, "lembrete", "texto", "", "x")
        assertEquals(at - 10 * min, alarmAt()) // primeiro o aviso 10 min antes
        val o2 = Agenda.create(c, now() + 5 * min, "lembrete", "texto", "", "perto")
        // dentro da janela de aviso: o tick avisa agora
        Agenda.tick(c)
        assertTrue(Agenda.get(c, o2.optString("id"))!!.optBoolean("remindSent"))
        assertTrue(notifs().any { shadowOf(it).contentText.toString().contains("perto") })
        // depois do aviso, o próximo alarme é a hora do post mais perto
        assertEquals(o2.optLong("at"), alarmAt())
    }

    @Test fun naHoraViraPendenteComNotificacaoEBotoes() {
        val o = Agenda.create(c, now() - 30_000L, "lembrete", "texto", "", "agora sim")
        Agenda.tick(c)
        assertEquals(AgendaCore.PENDENTE, Agenda.get(c, o.optString("id"))!!.optString("status"))
        val n = notifs().last { shadowOf(it).contentTitle.toString().contains("Hora de postar") }
        assertEquals(3, n.actions.size) // Postar agora / +15 min / Já postei
        // "+15 min"
        Agenda.snooze(c, o.optString("id"), 15)
        val e = Agenda.get(c, o.optString("id"))!!
        assertEquals(AgendaCore.AGENDADA, e.optString("status")); assertTrue(e.optLong("at") > now() + 14 * min)
        // "Já postei"
        Agenda.markPublished(c, o.optString("id"), "teste")
        assertEquals(AgendaCore.PUBLICADA, Agenda.get(c, o.optString("id"))!!.optString("status"))
        assertEquals(1, SendLog.all(c).count { it.optString("id") == "texto_" + o.optString("id") })
    }

    @Test fun statusAtrasada() {
        val o = Agenda.create(c, now() - 3600_000L, "lembrete", "texto", "", "esquecido")
        Agenda.patch(c, o.optString("id")) { it.put("status", AgendaCore.PENDENTE) }
        val e = Agenda.get(c, o.optString("id"))!!
        assertEquals(AgendaCore.ATRASADA, AgendaCore.effective(e.optString("status"), e.optString("mode"), e.optLong("at"), now()))
        Agenda.tick(c)
        assertTrue(notifs().any { shadowOf(it).contentTitle.toString().contains("Atrasado") })
    }

    // ------------------------------------------------------------ reinício do celular / mudança de hora
    @Test fun recuperaDepoisDeReiniciar() {
        val perdido = Agenda.create(c, now() + 2 * min, "lembrete", "texto", "", "perdido")
        val futuro = Agenda.create(c, now() + 4 * 3600_000L, "lembrete", "texto", "", "futuro")
        // celular desligou: alarmes somem e a hora do primeiro passou
        val am = c.getSystemService(AlarmManager::class.java)
        shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation) }
        assertNull(alarmAt())
        println("após cancelar: ok")
        Agenda.patch(c, perdido.optString("id")) { it.put("at", now() - 20 * min) }
        // o sistema avisa que ligou
        c.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setComponent(ComponentName(c, AgendaReceiver::class.java)))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val t0 = now()
        while (now() - t0 < 5000 && Agenda.get(c, perdido.optString("id"))!!.optString("status") == AgendaCore.AGENDADA) Thread.sleep(50)
        assertEquals(AgendaCore.PENDENTE, Agenda.get(c, perdido.optString("id"))!!.optString("status"))
        assertEquals(futuro.optLong("at") - 10 * min, alarmAt()) // alarme refeito pro próximo
        // e o receptor está registrado pros avisos do sistema
        val q = c.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(c.packageName), 0)
        assertTrue(q.any { it.activityInfo.name.endsWith("AgendaReceiver") })
        val q2 = c.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_TIMEZONE_CHANGED).setPackage(c.packageName), 0)
        assertTrue(q2.any { it.activityInfo.name.endsWith("AgendaReceiver") })
    }

    @Test fun publicacaoInterrompidaVoltaPraFila() {
        val o = Agenda.create(c, now() - 30 * min, "api", "texto", "", "caiu no meio")
        Agenda.patch(c, o.optString("id")) { it.put("status", AgendaCore.EXECUTANDO).put("execAt", now() - 20 * min) }
        Agenda.tick(c) // volta pra agendada e, como já é hora, vai publicar de novo
        val st = Agenda.get(c, o.optString("id"))!!.optString("status")
        assertTrue(st == AgendaCore.EXECUTANDO || st == AgendaCore.AGENDADA)
    }

    // ------------------------------------------------------------ sem duplicar
    @Test fun doisTicksAoMesmoTempoNaoDuplicam() {
        val o = Agenda.create(c, now() - 10_000L, "lembrete", "texto", "", "uma vez só")
        val ths = (1..6).map { Thread { Agenda.tick(c) } }; ths.forEach { it.start() }; ths.forEach { it.join() }
        assertEquals(1, notifs().count { shadowOf(it).contentTitle.toString().contains("Hora de postar") && shadowOf(it).contentText.toString().contains("uma vez só") })
        Agenda.markPublished(c, o.optString("id"), "a"); Agenda.markPublished(c, o.optString("id"), "b")
        assertEquals(1, SendLog.all(c).count { it.optString("id") == "texto_" + o.optString("id") })
    }

    // ------------------------------------------------------------ autoteste
    @Test fun autotesteTocaEAvisa() {
        val o = Agenda.selfTest(c, 1)
        Thread.sleep(1200)
        Agenda.tick(c)
        val e = Agenda.get(c, o.optString("id"))!!
        assertEquals(AgendaCore.PUBLICADA, e.optString("status"))
        assertTrue(notifs().any { shadowOf(it).contentTitle.toString().contains("Teste da agenda") })
        assertTrue(Agenda.diag(c).contains("último teste: ✓"))
    }

    // ------------------------------------------------------------ você agendou no Threads (detecção)
    @Test fun agendamentoDetectadoNoThreads() {
        val at = now() + 3 * 3600_000L
        val msg = Agenda.addDetected(c, at, "Legenda do post\ncom quebra")
        assertTrue(msg.contains(AgendaCore.fmt(at)))
        // a tela foi lida de novo / o aviso do Threads chegou: não duplica, só confere a hora
        Agenda.addDetected(c, at, "legenda do post com quebra")
        Agenda.addDetected(c, at + 30_000L, "")
        assertEquals(1, Agenda.all(c).size)
        val e = Agenda.all(c).first()
        assertEquals("threads", e.optString("mode")); assertEquals(AgendaCore.NO_THREADS, e.optString("status"))
        // você mudou a hora no Threads
        Agenda.addDetected(c, at + 3600_000L, "Legenda do post com quebra")
        assertEquals(1, Agenda.all(c).size); assertEquals(at + 3600_000L, Agenda.all(c).first().optLong("at"))
        // lembrete: o Threads só avisa "publicado" perto da hora
        Agenda.patch(c, e.optString("id")) { it.put("at", now() - 2 * min) }
        Agenda.onThreadsPosted(c)
        assertEquals(AgendaCore.PUBLICADA, Agenda.get(c, e.optString("id"))!!.optString("status"))
    }

    @Test fun detectadoMarcaItemDoLote() {
        val at = now() + 5 * 3600_000L
        Batch.save(c, listOf(JSONObject().put("id", "foto1").put("cap", "Oi gente").put("at", at - 3600_000L).put("done", false)))
        Agenda.addDetected(c, at, "oi  gente")
        val b = Batch.load(c).first()
        assertTrue(b.optBoolean("done")); assertEquals(at, b.optLong("at"))
        val e = Agenda.all(c).single()
        assertEquals("foto1", e.optString("photo")); assertTrue(e.optBoolean("prelogged"))
        assertEquals(1, SendLog.all(c).count { it.optString("id") == "foto1" })
        Agenda.markPublished(c, e.optString("id"), "x") // já estava no histórico: não repete
        assertEquals(1, SendLog.all(c).count { it.optString("id") == "foto1" })
    }

    // ------------------------------------------------------------ API do Threads (servidor falso)
    private lateinit var srv: MockWebServer
    private val reqs = Collections.synchronizedList(ArrayList<RecordedRequest>())
    @Volatile private var containerStatus = "FINISHED"
    @Volatile private var publishCode = 200

    private fun startServer() {
        srv = MockWebServer()
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                reqs += r
                val p = r.path.orEmpty()
                fun j(s: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(s)
                return when {
                    p.contains("threads_publishing_limit") -> j("""{"data":[{"quota_usage":3,"config":{"quota_total":250,"quota_duration":86400}}]}""")
                    r.method == "POST" && p.startsWith("/v1.0/u1/threads_publish") ->
                        if (publishCode == 200) j("""{"id":"m1"}""") else j("""{"error":{"message":"Application does not have permission for this action","type":"OAuthException","code":10}}""", publishCode)
                    r.method == "POST" && p.startsWith("/v1.0/u1/threads") -> j("""{"id":"c1"}""")
                    p.startsWith("/v1.0/c1") -> j("""{"status":"$containerStatus","id":"c1"}""")
                    p.startsWith("/v1.0/m1") -> j("""{"permalink":"https://www.threads.com/@eu/post/abc","id":"m1"}""")
                    p.startsWith("/upload") -> j("""{"data":{"url":"${srv.url("/img.jpg")}"},"success":true}""")
                    p.startsWith("/img.jpg") -> MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(ByteArray(64)))
                    else -> j("""{"error":{"message":"rota desconhecida $p"}}""", 404)
                }
            }
        }
        srv.start()
        ThreadsPublish.BASE = srv.url("/").toString(); ThreadsPublish.IMGBB = srv.url("/upload").toString()
        ThreadsPublish.firstWaitMs = 10; ThreadsPublish.pollMs = 10
        Store.prefs(c).edit().putString("th_token", "TOKEN").putString("th_uid", "u1").putString("th_user", "eu")
            .putLong("th_exp", now() + 50L * 86400_000L).apply()
    }

    @After fun tearDown() { if (::srv.isInitialized) try { srv.shutdown() } catch (e: Exception) { } }

    private fun bodyOf(path: String) = reqs.first { it.path.orEmpty().startsWith(path) && it.method == "POST" }.body.readUtf8()

    @Test fun testePermissaoDePublicar() {
        startServer()
        val r = ThreadsPublish.testPermission(c)
        assertTrue(r, r.startsWith("✓")); assertTrue(ThreadsPublish.ready(c)); assertTrue(r.contains("3 de 250"))
    }

    @Test fun publicaTextoPelaApiNaHora() {
        startServer(); ThreadsPublish.testPermission(c)
        val o = Agenda.create(c, now() - 5_000L, "api", "texto", "", "post de teste ✨")
        Agenda.tick(c) // hora chegou: vira "executando" e chama a publicação em segundo plano
        assertEquals(AgendaCore.EXECUTANDO, Agenda.get(c, o.optString("id"))!!.optString("status"))
        assertFalse(ThreadsPublish.runPending(c))
        val e = Agenda.get(c, o.optString("id"))!!
        assertEquals(AgendaCore.PUBLICADA, e.optString("status"))
        assertEquals("m1", e.optString("mid")); assertTrue(e.optString("link").contains("threads.com"))
        val body = java.net.URLDecoder.decode(bodyOf("/v1.0/u1/threads"), "UTF-8")
        assertTrue(body, body.contains("media_type=TEXT") && body.contains("text=post de teste ✨"))
        assertTrue(bodyOf("/v1.0/u1/threads_publish").contains("creation_id=c1"))
        assertEquals(1, SendLog.all(c).count { it.optString("id") == "texto_" + o.optString("id") })
        assertTrue(notifs().any { shadowOf(it).contentTitle.toString().contains("Publicado no Threads") })
    }

    @Test fun naoPublicaDuasVezesSeCaiuNoMeio() {
        startServer(); ThreadsPublish.testPermission(c)
        val o = Agenda.create(c, now() - 60_000L, "api", "texto", "", "já tinha saído")
        // caiu depois de publicar mas antes de anotar: tem o contêiner salvo e o Threads diz PUBLISHED
        Agenda.patch(c, o.optString("id")) { it.put("status", AgendaCore.EXECUTANDO).put("cid", "c1").put("execAt", now()) }
        containerStatus = "PUBLISHED"
        ThreadsPublish.runPending(c)
        assertEquals(AgendaCore.PUBLICADA, Agenda.get(c, o.optString("id"))!!.optString("status"))
        assertTrue(reqs.none { it.method == "POST" && it.path.orEmpty().startsWith("/v1.0/u1/threads_publish?") })
        assertTrue(reqs.joinToString { it.method + " " + it.path } + " | " + Agenda.get(c, o.optString("id"))!!.optJSONArray("log"),
            reqs.none { it.method == "POST" && it.path.orEmpty().startsWith("/v1.0/u1/threads") })
    }

    @Test fun semPermissaoViraErroELembrete() {
        startServer(); ThreadsPublish.testPermission(c)
        publishCode = 400
        val o = Agenda.create(c, now() - 5_000L, "api", "texto", "", "vai falhar")
        Agenda.tick(c)
        ThreadsPublish.runPending(c)
        val e = Agenda.get(c, o.optString("id"))!!
        assertEquals(AgendaCore.ERRO, e.optString("status")); assertTrue(e.optString("err").contains("permission"))
        assertTrue(notifs().any { shadowOf(it).contentTitle.toString().contains("Não publicou") && it.actions.size == 3 })
    }

    @Test fun falhaPassageiraTentaDeNovo() {
        startServer(); ThreadsPublish.testPermission(c)
        val o = Agenda.create(c, now() - 5_000L, "api", "texto", "", "sem internet")
        Agenda.tick(c)
        srv.shutdown() // sem conexão
        assertTrue(ThreadsPublish.runPending(c)) // pede pra tentar de novo
        val e = Agenda.get(c, o.optString("id"))!!
        assertEquals(AgendaCore.EXECUTANDO, e.optString("status")); assertEquals(1, e.optInt("tries"))
    }

    @Test fun apiNaoLiberadaViraLembrete() {
        val o = Agenda.create(c, now() - 5_000L, "api", "texto", "", "sem api")
        Agenda.tick(c)
        ThreadsPublish.runPending(c)
        assertEquals(AgendaCore.PENDENTE, Agenda.get(c, o.optString("id"))!!.optString("status"))
        assertTrue(notifs().any { shadowOf(it).contentTitle.toString().contains("Hora de postar") })
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun publicaFotoComHospedagem() {
        startServer(); ThreadsPublish.testPermission(c)
        ThreadsPublish.setHost(c, "imgbb", "CHAVE")
        val bmp = Bitmap.createBitmap(2000, 2500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        PostQueue.imageFile(c, "f1").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val o = Agenda.create(c, now() - 5_000L, "api", "foto", "f1", "foto com legenda")
        Agenda.tick(c); ThreadsPublish.runPending(c)
        assertEquals(AgendaCore.PUBLICADA, Agenda.get(c, o.optString("id"))!!.optString("status"))
        val up = reqs.first { it.path.orEmpty().startsWith("/upload") }
        assertTrue(up.path!!.contains("expiration=3600") && up.path!!.contains("key=CHAVE"))
        // a foto foi reduzida pra no máx. 1440 px antes de subir
        val b64 = java.net.URLDecoder.decode(up.body.readUtf8().substringAfter("image="), "UTF-8")
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
        assertEquals(1440, opt.outWidth)
        val body = java.net.URLDecoder.decode(bodyOf("/v1.0/u1/threads"), "UTF-8")
        assertTrue(body, body.contains("media_type=IMAGE") && body.contains("image_url=" + srv.url("/img.jpg")))
    }

    // ------------------------------------------------------------ tela
    private fun texts(v: View, out: MutableList<TextView> = ArrayList()): MutableList<TextView> {
        if (v is TextView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }
    private fun click(a: android.app.Activity, prefix: String) {
        val t = texts(a.window.decorView).firstOrNull { it.text.toString().startsWith(prefix) } ?: throw AssertionError("sem botão '$prefix' na tela: " + texts(a.window.decorView).joinToString { it.text })
        t.performClick(); shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    private fun has(a: android.app.Activity, s: String) = texts(a.window.decorView).any { it.text.toString().contains(s) }

    @Test fun telaCalendarioListaEditor() {
        Agenda.create(c, now() + 2 * 3600_000L, "lembrete", "texto", "", "post da tela")
        val a = Robolectric.buildActivity(AgendaActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(has(a, "🗓 Agenda"))
        for (v in listOf("Semana", "Dia", "Lista", "Mês")) { click(a, v) }
        click(a, "Lista"); assertTrue(has(a, "post da tela"))
        // novo agendamento pelo editor
        click(a, "+ Novo agendamento"); assertTrue(has(a, "O que vai sair")); assertTrue(has(a, "Repetir"))
        click(a, "✍ Só texto")
        val ed = texts(a.window.decorView).filterIsInstance<android.widget.EditText>().first()
        ed.setText("feito pela tela")
        click(a, "Semanal"); assertTrue(has(a, "Próximas:"))
        click(a, "Não")
        click(a, "Salvar")
        assertTrue(Agenda.all(c).any { it.optString("cap") == "feito pela tela" })
        // ajustes
        click(a, "⚙ Publicação"); assertTrue(has(a, "Hospedagem da foto")); assertTrue(has(a, "Diagnóstico da agenda"))
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun variosDeUmaVezEmVariosDias() {
        repeat(12) { k -> PostQueue.add(c, Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(k * 20, 80, 120)) }) }
        PostQueue.load(c).forEach { o -> PostQueue.update(c, o.optString("id")) { it.put("status", "pronta").put("legendas", org.json.JSONArray(listOf("legenda " + o.optString("id")))) } }
        val a = Robolectric.buildActivity(AgendaActivity::class.java, Intent(c, AgendaActivity::class.java).putExtra("lot", true)).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val dlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog() as android.app.AlertDialog
        assertTrue(dlg.isShowing)
        fun tap(t: String) { texts(dlg.window!!.decorView).first { it.text.toString() == t }.performClick(); shadowOf(android.os.Looper.getMainLooper()).idle() }
        tap("3"); tap("Começando amanhã"); tap("🔔 Me chama")
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); shadowOf(android.os.Looper.getMainLooper()).idle()
        val l = Agenda.all(c).sortedBy { it.optLong("at") }
        val days = l.map { java.time.Instant.ofEpochMilli(it.optLong("at")).atZone(ZoneId.systemDefault()).toLocalDate() }.toSet()
        assertEquals("posts: " + l.joinToString { AgendaCore.fmt(it.optLong("at")) }, 9, l.size) // 3 por dia × 3 dias
        assertEquals(setOf(1L, 2L, 3L).map { LocalDate.now().plusDays(it) }.toSet(), days)
        assertTrue(l.all { it.optString("photo").isNotEmpty() && it.optString("cap").isNotEmpty() && it.optString("mode") == "lembrete" })
        assertTrue(Agenda.conflicts(c).isEmpty()) // nenhum colado no outro
        assertEquals(9, l.map { it.optString("photo") }.toSet().size) // fotos diferentes
    }
}
