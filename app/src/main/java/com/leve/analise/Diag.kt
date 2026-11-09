package com.leve.analise

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject
import java.io.File

/** Liga o registro de erros assim que o app abre (inclusive serviços em segundo plano). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { ErrLog.log(this, "FECHOU (${t.name})", e) } catch (x: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }
}

/** 📝 Registro de erros (os últimos 60), pra copiar e mandar no diagnóstico. */
object ErrLog {
    private fun file(c: Context) = File(c.filesDir, "erros.json")
    fun log(c: Context, where: String, e: Throwable?) {
        try {
            JsonFile.edit(file(c)) { l ->
                l.add(0, JSONObject().put("at", System.currentTimeMillis()).put("w", where)
                    .put("e", (e?.javaClass?.simpleName ?: "") + ": " + (e?.message ?: "").take(200))
                    .put("s", e?.stackTrace?.take(4)?.joinToString(" < ") { "${it.fileName}:${it.lineNumber}" } ?: ""))
                while (l.size > 60) l.removeAt(l.size - 1)
            }
        } catch (x: Throwable) { }
    }
    fun all(c: Context) = JsonFile.read(file(c))
    fun clear(c: Context) = JsonFile.write(file(c), emptyList())
}

/** ✅ Checklist do que precisa estar ligado. */
object Setup {
    data class Item(val name: String, val ok: Boolean, val why: String, val fixLabel: String?, val important: Boolean = true)

    private fun has(c: Context, pkg: String) = try { c.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    fun items(c: Context): List<Item> {
        val l = ArrayList<Item>()
        l += Item("Chave da IA", Ai.key(c).isNotEmpty(), "legendas, textos, notas e ideias com IA", "Configurar")
        l += Item("Notificações do app", Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            "lembretes, \"Publicou?\" e pedidos de nota", "Permitir")
        l += Item("Acesso às fotos e vídeos", FolderScan.hasPerm(c), "puxar do Flow e salvar o que o Threads baixa", "Permitir")
        l += Item("Pasta do Flow", FolderScan.bucket(c) != null, "fotos e vídeos novos entram sozinhos", "Escolher")
        val acc = ThreadsNotif.hasAccess(c)
        l += Item("Leitor de notificações", acc && ThreadsNotif.live(c), if (acc) "liberado, mas desconectado" else "nota automática pelas curtidas e silenciar o Threads", if (acc) "Reconectar" else "Liberar")
        l += Item("Leitor de tela", RateWatch.readerOn(c), "nota pelas curtidas quando você abre seus posts", "Ligar", important = false)
        l += Item("API do Threads", ThreadsApi.on(c) && ThreadsApi.lastErr(c).isBlank(), if (ThreadsApi.on(c)) "erro: " + ThreadsApi.lastErr(c).take(60) else "números exatos e seguidores por post", "Configurar", important = false)
        val pm = c.getSystemService(PowerManager::class.java)
        l += Item("Bateria sem restrição", pm.isIgnoringBatteryOptimizations(c.packageName), "o Xiaomi não matar lembretes, produção e leitores", "Liberar")
        val ime = Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        l += Item("Leve Teclado ativo", ime.startsWith("com.leve.teclado"), if (has(c, "com.leve.teclado")) "instalado, mas não é o teclado em uso" else "não instalado (copiar textos do Threads, 💬 respostas)", if (has(c, "com.leve.teclado")) "Trocar teclado" else null, important = false)
        l += Item("Threads instalado", has(c, ThreadsNotif.THREADS), "postar as fotos", null)
        l += Item("Instagram instalado", has(c, ThreadsNotif.INSTA), "postar os vídeos", null, important = false)
        if (ThreadsRefs.on(c) && ThreadsRefs.deleteOn(c) && Build.VERSION.SDK_INT >= 31) l += Item("Apagar sem perguntar", ThreadsRefs.canManage(c), "tirar da galeria o que veio do Threads sem confirmar toda vez", "Liberar", important = false)
        l += Item("Backup recente", System.currentTimeMillis() - Backup.last(c) < 8 * 86400_000L, "proteger o que o app aprendeu", "Fazer agora", important = false)
        return l
    }

    fun summary(c: Context): Pair<Int, Int> { val i = items(c); return i.count { it.ok } to i.size }

    /** Abre o que resolve o item (as que pedem permissão de tela ficam na MainActivity). */
    fun open(c: Context, name: String): Boolean {
        val i = when (name) {
            "Leitor de tela" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "Bateria sem restrição" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName))
            "Leve Teclado ativo" -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            "Apagar sem perguntar" -> Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:" + c.packageName))
            else -> return false
        }
        return try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) {
            if (name == "Bateria sem restrição") try { c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (x: Exception) { false } else false
        }
    }
}

/** 🧪 Autoteste: confere as peças principais e monta o diagnóstico pra copiar. */
object SelfTest {
    data class R(val name: String, val ok: Boolean, val detail: String)

    private inline fun t(out: MutableList<R>, name: String, block: () -> String) {
        val t0 = System.currentTimeMillis()
        try { out += R(name, true, block() + " (${System.currentTimeMillis() - t0} ms)") }
        catch (e: Throwable) { out += R(name, false, (e.javaClass.simpleName + ": " + (e.message ?: "")).take(160)); ErrLog.log(App0.c ?: return, "autoteste: $name", e) }
    }

    /** Testes que não precisam da tela (rodam em segundo plano). */
    fun run(c: Context): MutableList<R> {
        App0.c = c.applicationContext
        val out = ArrayList<R>()
        t(out, "Fila de fotos") { val q = PostQueue.load(c); val ok = q.count { PostQueue.imageFile(c, it.optString("id")).exists() }; check(q.isEmpty() || ok > 0) { "nenhuma foto com arquivo" }; "${q.size} item(ns), $ok com arquivo" }
        t(out, "Vídeos prontos") { val l = VideoExport.library(c); "${l.size} vídeo(s), ${l.count { it.optInt("posts") == 0 }} na fila" }
        t(out, "Vídeos crus") { val p = Projects.list(c); val ok = p.count { Projects.srcFile(c, it.optString("id"), it.optBoolean("photo")).exists() }; "${p.size} cru(s), $ok com arquivo" }
        t(out, "Arquivos de dados") {
            val bad = c.filesDir.listFiles { f -> f.extension == "json" }.orEmpty().filter { f -> try { org.json.JSONArray(f.readText()); false } catch (e: Exception) { try { JSONObject(f.readText()); false } catch (x: Exception) { true } } }
            check(bad.isEmpty()) { "estragados: " + bad.joinToString { it.name } }; "todos legíveis"
        }
        t(out, "Legenda sem IA") { val s = Captions.randomCaption(c); check(s.isNotBlank()); "\"" + s.replace('\n', ' ').take(40) + "\"" }
        t(out, "Próximos vídeos") { "${VideoExport.upcoming(c, 3).size} na previsão" }
        t(out, "Cenas") { "${Scenes.count(c)} cena(s) diferentes" }
        t(out, "Verificador das filas") { "${Health.check(c).size} aviso(s)" }
        t(out, "Espaço livre") { val mb = c.filesDir.usableSpace / 1_048_576; check(mb > 300) { "só $mb MB livres" }; "$mb MB" }
        Setup.items(c).filter { it.important }.forEach { i -> out += R("Config: ${i.name}", i.ok, if (i.ok) "ok" else i.why) }
        return out
    }

    /** Exporta um vídeo de 2 s a partir de um cru (precisa rodar na tela principal). */
    fun exportTest(c: Context, done: (R) -> Unit) {
        val p = Projects.list(c).firstOrNull { Projects.srcFile(c, it.optString("id"), it.optBoolean("photo")).exists() }
            ?: return done(R("Produção de vídeo", true, "sem vídeo cru pra testar (ok)"))
        if (VideoExport.busy() || AutoProducer.running) return done(R("Produção de vídeo", true, "pulei: já tem produção rodando"))
        val photo = p.optBoolean("photo")
        val src = Uri.fromFile(Projects.srcFile(c, p.optString("id"), photo))
        val t0 = System.currentTimeMillis()
        try {
            VideoExport.export(c, VideoExport.Job(src, photo, 2, "teste", TextStyle(), null, true, "1080p", 0, if (photo) 0 else 2000), {}) { f, err ->
                if (f != null && f.length() > 0) { val kb = f.length() / 1024; f.delete(); done(R("Produção de vídeo", true, "2 s exportados ($kb KB, ${(System.currentTimeMillis() - t0) / 1000} s)")) }
                else { ErrLog.log(c, "autoteste: produção", Exception(err)); done(R("Produção de vídeo", false, err ?: "falhou")) }
            }
        } catch (e: Throwable) { ErrLog.log(c, "autoteste: produção", e); done(R("Produção de vídeo", false, e.message ?: "erro")) }
    }

    fun report(c: Context, rs: List<R>): String {
        val ver = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (e: Exception) { "?" }
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
        val p = Store.prefs(c)
        return buildString {
            appendLine("📋 Diagnóstico Post Leve $ver · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("IA: ${Ai.provider(c)} (chave ${if (Ai.key(c).isNotEmpty()) "ok" else "FALTA"}) · usados hoje ${Auto.aiUsedToday(c)}/${Auto.aiMax(c)} · automático ${if (Auto.on(c)) "on" else "off"}")
            appendLine("Notas: notif ${ThreadsNotif.hasAccess(c)}/${ThreadsNotif.live(c)} · tela ${RateWatch.readerOn(c)} · limite ${ThreadsNotif.min(c)}/${ThreadsNotif.igMin(c)}")
            appendLine("API Threads: " + ThreadsApi.summary(c).replace("\n", " | "))
            appendLine("Coleta: " + Macro.lastRun(c) + " · posts com números: ${ThreadsApi.posts(c).size} (de fora do app: ${ThreadsApi.posts(c).count { it.optBoolean("orphan") }}) · Reels com números: ${ThreadsApi.posts(c).count { it.optString("kind") == "video" }}")
            try { Growth.report(c).let { r -> appendLine("Crescimento: " + r.lines.joinToString(" | ")); if (r.recs.isNotEmpty()) appendLine("Falta: " + r.recs.joinToString(" | ")) } } catch (e: Exception) { }
            try { appendLine("Threads → Refs: " + ThreadsRefs.diag(c)) } catch (e: Exception) { }
            try { appendLine(Predictor.accuracyText(c)) } catch (e: Exception) { }
            try { appendLine("Laboratório: " + Lab.status(c).replace("\n", " | ")) } catch (e: Exception) { }
            appendLine("Leitor ensinado: " + ReaderRules.summary(c) + " · legenda forçada: " + (if (CapForce.on(c)) "ligada" else "desligada"))
            Store.prefs(c).getString("tela_dump", null)?.let { appendLine("Tela onde não achou números: " + it.take(1500)) }
            val lk = Likes.all(c)
            appendLine("Curtidas guardadas: ${lk.size} (notif ${lk.count { it.optString("src") == "notif" }}, tela ${lk.count { it.optString("src") == "tela" }}) · mediana fotos ${Likes.median(c, "fila") ?: "-"} · vídeos ${Likes.median(c, "video") ?: "-"} · modelo treinado em ${Predictor.trainedOn()}")
            appendLine("Vigia: " + RateWatch.diag(c).replace('\n', ' ').take(300))
            appendLine("Envios: ${SendLog.all(c).size} · sem nota: ${SendLog.pending(c).size}")
            appendLine("Threads (últimas): " + ThreadsNotif.logText(c).replace("\n", " | ").take(400))
            appendLine("— Testes —")
            rs.forEach { appendLine((if (it.ok) "✓ " else "✗ ") + it.name + ": " + it.detail) }
            appendLine("— Erros recentes —")
            val e = ErrLog.all(c)
            if (e.isEmpty()) appendLine("nenhum")
            e.take(20).forEach { appendLine("${fmt.format(java.util.Date(it.optLong("at")))} ${it.optString("w")}: ${it.optString("e")} @ ${it.optString("s")}") }
            p.getString("health_last", "")?.takeIf { it.isNotBlank() }?.let { appendLine("Último conserto: " + it.replace('\n', ' ')) }
        }
    }
}

object App0 { @Volatile var c: Context? = null }
