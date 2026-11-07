package com.leve.analise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Motor da produção automática, sem tela: monta o plano (cru + texto + modelo + legenda),
 * pede textos à IA quando faltam e exporta um atrás do outro pra fila de Prontos.
 * Roda tanto pela aba 🤖 Auto quanto pelo serviço em segundo plano.
 */
object AutoProducer {
    @Volatile var running = false
    private val main = Handler(Looper.getMainLooper())

    data class Plan(val proj: String, val text: String, val tctx: String, val model: JSONObject?, val caption: String, val ctx: String, val version: Int = 0, val music: String? = null)

    /** Respostas do "Me perguntar" da música nesta produção (categoria → caminho / "" / "*"). */
    @Volatile var asked: Map<String, String> = emptyMap()

    fun quality(c: Context) = Store.prefs(c).getString("quality", "1080p") ?: "1080p"
    fun styleOf(st: JSONObject) = TextStyle(st.optInt("size", 52), st.optString("color", "Branco"), st.optString("bg", "Faixa escura"),
        st.optString("pos", "Baixo"), st.optString("font", "Negrito"), st.optString("align", "Centro"))
    fun models(c: Context): List<JSONObject> = try {
        val a = JSONArray(Store.prefs(c).getString("modelos", "[]")); (0 until a.length()).map { a.getJSONObject(it) }
    } catch (e: Exception) { emptyList() }

    fun vidKey(pid: String) = "v:$pid"
    fun waiting(c: Context) = VideoExport.library(c).count { it.optInt("posts") == 0 }

    /** Um quadro do cru (pra IA entender o vídeo). */
    fun frameOf(c: Context, p: JSONObject): File? {
        val photo = p.optBoolean("photo"); val f = Projects.srcFile(c, p.optString("id"), photo)
        val b: Bitmap = (if (photo) BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })
                         else VideoExport.frameAt(c, Uri.fromFile(f), 1500)) ?: return null
        return File(c.cacheDir, "frame_${p.optString("id")}.jpg").also { o -> o.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
    }

    /**
     * Gera textos com a IA (bloqueia; chame fora da tela).
     * key = "" (Geral), nome do contexto, ou "v:id" (só aquele vídeo, olhando um quadro dele).
     */
    fun genTexts(c: Context, n: Int, key: String): String = try {
        val isVid = key.startsWith("v:")
        val p = if (isVid) Projects.get(c, key.removePrefix("v:")) else null
        val cx = p?.optString("ctx") ?: key
        val good = (OverlayDB.top(c, 8, key) + OverlayDB.top(c, 4, cx) + OverlayDB.top(c, 3, "")).filter { OverlayDB.mean(it) > 0.55 }.map { it.optString("t") }.distinct().take(10)
        val bad = (OverlayDB.worst(c, 5, key) + OverlayDB.worst(c, 3, cx)).filter { OverlayDB.mean(it) < 0.45 }.map { it.optString("t") }.distinct().take(8)
        val name = if (isVid) "um vídeo específico" else cx
        val desc = if (isVid) listOf(p?.optString("desc").orEmpty(), Contexts.desc(c, cx)).filter { it.isNotBlank() }.joinToString(". ") else Contexts.desc(c, cx)
        // estilo: os textos que VOCÊ escreveu (do vídeo, da categoria e gerais), os que bombaram primeiro
        val style = (OverlayDB.all(c, key) + OverlayDB.all(c, cx) + OverlayDB.all(c, "")).filter { it.optString("src") == "meu" }
            .sortedByDescending { OverlayDB.mean(it) }.map { it.optString("t") }.distinct().take(15)
        val l = Ai.overlayTexts(c, n, good, bad, OverlayDB.all(c, key).map { it.optString("t") }, name, desc, if (isVid && p != null) frameOf(c, p) else null, style)
        l.forEach { OverlayDB.add(c, it, "ia", key) }
        "${l.size} textos novos"
    } catch (e: Exception) { "A IA não respondeu (${e.message?.take(60)})" }

    /** Antes de produzir: vídeos com descrição ganham textos próprios; contextos com poucos textos ganham mais. */
    fun textAiAuto(c: Context) = Store.prefs(c).getBoolean("ov_ai_auto", false)

    private fun ensureTexts(c: Context, projs: List<JSONObject>, progress: (String) -> Unit) {
        if (Ai.key(c).isEmpty() || !textAiAuto(c)) return // sem automático: usa só o que já tem no banco
        projs.filter { !Contexts.noText(c, it.optString("ctx")) && it.optString("desc").isNotBlank() && OverlayDB.count(c, vidKey(it.optString("id"))) < 4 }.forEach { p ->
            progress("Criando textos pro vídeo \"${p.optString("desc").take(30)}\"…"); genTexts(c, 8, vidKey(p.optString("id")))
        }
        projs.map { it.optString("ctx") }.distinct().filter { cx -> !Contexts.noText(c, cx) }.filter { cx -> OverlayDB.count(c, cx) + (if (cx.isEmpty()) 0 else OverlayDB.count(c, "")) < 6 }.forEach { cx ->
            progress("Criando textos pra ${Contexts.label(c, cx)}…"); genTexts(c, 10, cx)
        }
    }

    fun makePlans(c: Context, n: Int, only: String?): List<Plan> {
        val projs = Projects.list(c).filter { Projects.srcFile(c, it.optString("id"), it.optBoolean("photo")).exists() && (only == null || it.optString("ctx") == only) }
        if (projs.isEmpty()) return emptyList()
        val usedCombo = VideoExport.library(c).map { it.optString("proj") + "|" + it.optString("texto").trim().lowercase() }.toMutableSet()
        val order = projs.sortedBy { it.optInt("versoes") + Math.random() } // os menos usados primeiro
        val useModel = Store.prefs(c).getBoolean("auto_model", true)
        val ms = models(c)
        val out = ArrayList<Plan>()
        var k = 0
        while (out.size < n && k < n * 4) {
            val p = order[k % order.size]; k++
            val pid = p.optString("id"); val cx = p.optString("ctx")
            val cap = if (Contexts.noCaption(c, cx)) "" else Captions.randomCaption(c)
            if (Contexts.noText(c, cx)) {
                // sem texto: o vídeo já está pronto; cada versão sai com um corte diferente, até o limite da categoria
                val made = VideoExport.library(c).count { it.optString("proj") == pid } + out.count { it.proj == pid }
                if (made >= Contexts.versions(c, cx)) continue
                out.add(Plan(pid, "", "", if (useModel) ModelScore.pick(ms) else null, cap, cx, made)); continue
            }
            val avoid = usedCombo.filter { it.startsWith("$pid|") }.map { it.substringAfter('|') } + out.map { it.text }
            val pick = OverlayDB.pickFor(c, avoid, vidKey(pid), cx)
                ?: OverlayDB.STARTERS.filter { s -> avoid.none { it == s.lowercase() } }.randomOrNull()?.let { it to "" } ?: continue
            usedCombo.add(pid + "|" + pick.first.trim().lowercase())
            out.add(Plan(pid, pick.first, pick.second, if (useModel) ModelScore.pick(ms) else null, cap, cx))
        }
        // música por categoria (variando: nunca a mesma em seguida)
        val avoidM = ArrayList(MusicPick.recent(c))
        return out.map { pl ->
            val m = MusicPick.resolve(c, pl.ctx, avoidM.takeLast(2 + Health.musicExtra(c)), asked)
            if (!m.isNullOrEmpty()) avoidM.add(m)
            pl.copy(music = m)
        }
    }

    /** Chame na thread principal. progress/done também chegam na principal. */
    fun run(c: Context, n: Int, only: String?, progress: (String) -> Unit, done: (Int) -> Unit) {
        if (running || VideoExport.busy()) { done(-1); return }
        running = true
        Thread {
            val projs = Projects.list(c).filter { only == null || it.optString("ctx") == only }
            ensureTexts(c, projs) { m -> main.post { progress(m) } }
            val plans = makePlans(c, n, only)
            main.post {
                if (plans.isEmpty()) { running = false; asked = emptyMap(); done(0) } else step(c, plans, 0, 0, progress, done)
            }
        }.start()
    }

    private fun step(c: Context, plans: List<Plan>, i: Int, ok: Int, progress: (String) -> Unit, done: (Int) -> Unit) {
        if (i >= plans.size) { running = false; asked = emptyMap(); done(ok); return }
        val pl = plans[i]
        val p = Projects.get(c, pl.proj) ?: run { step(c, plans, i + 1, ok, progress, done); return }
        val photo = p.optBoolean("photo")
        val f = Projects.srcFile(c, pl.proj, photo)
        val m = pl.model
        val st = (m?.optJSONObject("style") ?: p.optJSONObject("style"))?.let { styleOf(it) } ?: TextStyle.load(c)
        var mus = (if (m != null && m.optString("music").isNotEmpty()) m.optString("music") else p.optString("music")).takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }
        var mt = if (m != null) m.optBoolean("mute") else p.optBoolean("mute")
        var msStart = if (m != null && m.optString("music").isNotEmpty()) m.optLong("musicStart") else p.optLong("musicStart")
        when {
            pl.music == null -> {}
            pl.music.isEmpty() -> { mus = null; mt = false; msStart = 0 }               // som original
            else -> { mus = File(pl.music).takeIf { it.exists() } ?: mus; mt = false; msStart = 0 } // música da categoria
        }
        val secs0 = if (m != null) m.optInt("secs", 8) else p.optInt("secs", 8)
        val q = m?.optString("quality")?.ifEmpty { null } ?: quality(c)
        var cs = p.optLong("cutStart"); var ce = p.optLong("cutEnd")
        val dur = if (photo) 0L else VideoExport.durationMs(f)
        val target = Contexts.seconds(c, pl.ctx) * 1000L
        if (!photo && dur > 0) {
            val base0 = cs.coerceIn(0, dur); val base1 = if (ce <= 0 || ce > dur) dur else ce
            val len = base1 - base0
            val seg = when {
                target in 1 until len -> target                                                     // tempo da categoria (ex.: 5 s)
                pl.text.isEmpty() && pl.version > 0 && len > 6000 -> (len * (0.70 + Math.random() * 0.25)).toLong().coerceAtLeast(4000)
                else -> len
            }
            if (seg < len) {
                // 1ª versão começa do começo do vídeo; as outras pegam outro trecho, pra não sair igual
                val st0 = if (pl.version == 0) base0 else base0 + ((len - seg) * Math.random()).toLong()
                cs = st0; ce = st0 + seg
            }
        }
        val secs = if (photo && target > 0) (target / 1000).toInt() else secs0
        val job = VideoExport.Job(Uri.fromFile(f), photo, secs, pl.text, st, mus, mt, q,
            if (photo) 0 else cs, if (photo || ce <= 0 || ce >= dur) 0 else ce, msStart,
            if (photo) 0 else p.optLong("textStart"), if (photo) 0 else p.optLong("textEnd"), 0, VideoExport.cropOf(p))
        val label = "Produzindo ${i + 1} de ${plans.size}"
        val info = (if (pl.text.isEmpty()) "\n🎬 sem texto" + (if (pl.version > 0) " · trecho ${pl.version + 1}" else "") else "\nT: ${pl.text.replace('\n', ' ').take(40)}") + (m?.let { "  ★ ${it.optString("name")}" } ?: "") + (mus?.let { "\n♪ " + VideoExport.nice(it) } ?: "")
        progress("$label…$info")
        VideoExport.export(c, job, { pr -> progress("$label… $pr%$info") }) { out, _ ->
            if (out == null) { step(c, plans, i + 1, ok, progress, done); return@export }
            Thread {
                val vid = VideoExport.addToLibrary(c, out, pl.caption)
                VideoExport.updateLib(c, vid) {
                    it.put("proj", pl.proj); it.put("texto", pl.text); it.put("tctx", pl.tctx); it.put("ctx", pl.ctx)
                    it.put("modelo", m?.optString("name") ?: ""); it.put("auto", true)
                    if (pl.caption.isEmpty() && Contexts.noCaption(c, pl.ctx)) it.put("semleg", true)
                    it.put("musica", mus?.path ?: "")
                }
                Projects.update(c, pl.proj) { it.put("versoes", it.optInt("versoes") + 1) }
                if (pl.text.isNotEmpty()) OverlayDB.used(c, pl.text, pl.tctx)
                mus?.let { MusicPick.used(c, it.path) }
                main.post { step(c, plans, i + 1, ok + 1, progress, done) }
            }.start()
        }
    }

    /** "Manter sempre N prontos": se faltar, produz em segundo plano (sem precisar abrir o app). */
    fun refillIfNeeded(c: Context) {
        // o estoque é o maior entre "manter sempre N" e o que ainda falta postar hoje pela meta
        val keep = maxOf(Store.prefs(c).getInt("auto_keep", 0), Daily.remaining(c))
        if (keep <= 0 || running) return
        val need = keep - waiting(c)
        if (need <= 0 || Projects.list(c).isEmpty()) return
        try {
            val i = Intent(c, ProduceService::class.java).putExtra("n", need)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        } catch (e: Exception) { }
    }
}

/** Produz vídeos em segundo plano com uma notificação de progresso. */
class ProduceService : Service() {
    private val nid = 41
    private var wl: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null

    private fun notif(text: String, ongoing: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("producao", "Produção de vídeos", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 42, Intent(this, MainActivity::class.java).putExtra("tab", "Instagram"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "producao").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (ongoing) "🤖 Produzindo vídeos" else "🤖 Vídeos prontos")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(ongoing).setOnlyAlertOnce(true).setContentIntent(open).setAutoCancel(!ongoing).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = intent?.getIntExtra("n", 3) ?: 3
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(nid, notif("Preparando…", true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(nid, notif("Preparando…", true))
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leve:producao").apply { acquire(60 * 60 * 1000L) }
        val nm = getSystemService(NotificationManager::class.java)
        AutoProducer.run(this, n, Store.prefs(this).getString("auto_ctx", null),
            { t -> try { nm.notify(nid, notif(t, true)) } catch (e: Exception) { } }) { ok ->
            try { wl?.release() } catch (e: Exception) { }
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (ok > 0) try { nm.notify(nid + 1, notif("$ok vídeo(s) novo(s) na fila. É só tocar em 🎬 Vídeo no widget.", false)) } catch (e: Exception) { }
            QuickPost.updateWidgets(this)
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
