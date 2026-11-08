package com.leve.analise

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 🩺 Verificador das filas (código simples, sem IA, não gasta crédito).
 * Olha fotos, legendas, vídeos, textos, músicas e vídeos crus; se algo está repetindo,
 * "gira" a fila: põe na frente o que está há mais tempo sem sair. Também conserta itens quebrados.
 */
object Health {
    data class Issue(val area: String, val msg: String, val fixLabel: String?, val fix: ((Context) -> String)?)

    private fun p(c: Context) = Store.prefs(c)
    fun autoOn(c: Context) = p(c).getBoolean("health_auto", true)
    fun setAuto(c: Context, v: Boolean) = p(c).edit().putBoolean("health_auto", v).apply()

    // ---------- filas forçadas (o "girar")
    private fun forced(c: Context, k: String): MutableList<String> = try { val a = JSONArray(p(c).getString(k, "[]")); (0 until a.length()).map { a.optString(it) }.toMutableList() } catch (e: Exception) { mutableListOf() }
    private fun setForced(c: Context, k: String, l: List<String>) = p(c).edit().putString(k, JSONArray(l).toString()).apply()

    /** Próximo da fila girada (se houver), pulando o que não serve. */
    fun takeForced(c: Context, k: String, ok: (String) -> Boolean): String? {
        val l = forced(c, k)
        while (l.isNotEmpty()) { val id = l.removeAt(0); if (ok(id)) { setForced(c, k, l); return id } }
        setForced(c, k, l); return null
    }

    /** Legendas: janela extra pra não repetir (vale por 24 h depois de girar). */
    fun capExtra(c: Context) = if (System.currentTimeMillis() < p(c).getLong("cap_extra_until", 0)) p(c).getInt("cap_extra", 0) else 0
    fun musicExtra(c: Context) = if (System.currentTimeMillis() < p(c).getLong("mus_extra_until", 0)) 3 else 0

    private fun distinctRatio(l: List<String>) = if (l.isEmpty()) 1.0 else l.distinct().size.toDouble() / l.size

    // ---------- giros
    fun rotatePhotos(c: Context): String {
        val all = PostQueue.load(c).filter { PostQueue.imageFile(c, it.optString("id")).exists() }
        val rec = Captions.recentPhotos(c)
        // as que estão há mais tempo sem sair (ou nunca saíram) primeiro; 👎 repetido fica pro fim
        val order = all.sortedWith(compareBy<org.json.JSONObject> { val i = rec.indexOf(it.optString("id")); if (i < 0) -1000 else -i }
            .thenByDescending { if (Learn.verdict(it) == "bad" && Learn.rated(it) >= 2) 0 else 1 })
            .map { it.optString("id") }.filter { it !in rec.take(6) }.take(12)
        setForced(c, "force_fotos", order)
        return "Fila de fotos girada: as próximas ${order.size} são as que estavam paradas."
    }

    fun rotateCaps(c: Context): String {
        p(c).edit().putInt("cap_extra", 15).putLong("cap_extra_until", System.currentTimeMillis() + 24 * 3600_000L).apply()
        return "Legendas giradas: as últimas 27 não voltam nas próximas 24 h."
    }

    fun rotateVideos(c: Context): String {
        Scenes.refresh(c)
        val lib = VideoExport.library(c).filter { File(VideoExport.videoDir(c), "${it.optString("id")}.mp4").exists() }
        val rec = VideoExport.recentIds(c)
        // intercala vídeos crus diferentes, os mais parados primeiro
        val stale = lib.filter { it.optString("id") !in rec.take(6) }
            .sortedBy { val i = rec.indexOf(it.optString("id")); if (i < 0) -1000 else -i }
        val byOrigin = stale.groupBy { VideoExport.origin(it) }.values.map { it.toMutableList() }.toMutableList()
        val order = ArrayList<String>()
        while (order.size < 12 && byOrigin.any { it.isNotEmpty() }) byOrigin.forEach { g -> if (g.isNotEmpty() && order.size < 12) order += g.removeAt(0).optString("id") }
        setForced(c, "force_vids", order)
        return "Fila de vídeos girada: os próximos ${order.size} vêm de vídeos crus diferentes, os mais parados primeiro."
    }

    fun rotateMusic(c: Context): String {
        p(c).edit().putLong("mus_extra_until", System.currentTimeMillis() + 24 * 3600_000L).apply()
        return "Música dos vídeos: as últimas 5 usadas ficam de fora nas próximas produções (24 h)."
    }

    // ---------- verificação
    fun check(c: Context): List<Issue> {
        val out = ArrayList<Issue>()
        // FOTOS (Threads)
        val q = PostQueue.load(c)
        val broken = q.filter { !PostQueue.imageFile(c, it.optString("id")).exists() }
        if (broken.isNotEmpty()) out += Issue("📸 Fotos", "${broken.size} item(ns) da fila sem o arquivo da foto.", "Limpar") { cc ->
            broken.forEach { PostQueue.remove(cc, it.optString("id")) }; "${broken.size} item(ns) quebrados removidos." }
        val stuck = q.filter { it.optString("status") == "gerando" && System.currentTimeMillis() - it.optLong("at") > 3600_000L && !it.optBoolean("pedido") }
        if (stuck.isNotEmpty()) out += Issue("📸 Fotos", "${stuck.size} foto(s) presas esperando legenda.", "Dar legenda do banco") { cc -> "${Captions.fillFromBank(cc)} foto(s) receberam legendas." }
        val ok = q.filter { PostQueue.imageFile(c, it.optString("id")).exists() }
        val rf = Captions.recentPhotos(c).take(10)
        if (ok.size >= 5 && rf.size >= 6 && distinctRatio(rf) < 0.7)
            out += Issue("📸 Fotos", "Repetindo: só ${rf.distinct().size} fotos diferentes nos últimos ${rf.size} posts (você tem ${ok.size}).", "Girar fila", ::rotatePhotos)
        val fresh = ok.count { it.optString("status") != "postada" }
        if (ok.isNotEmpty() && fresh == 0) out += Issue("📸 Fotos", "Todas as ${ok.size} fotos já foram postadas: vão repetir mais. Mande fotos novas do Flow.", null, null)
        // LEGENDAS
        val rc = Captions.recentCaps(c).take(15)
        if (rc.size >= 8 && distinctRatio(rc) < 0.75 && capExtra(c) == 0)
            out += Issue("💬 Legendas", "Legendas repetindo: ${rc.distinct().size} diferentes nos últimos ${rc.size} posts.", "Girar legendas", ::rotateCaps)
        val bank = CaptionDB.size(c)
        if (bank in 1..24) out += Issue("💬 Legendas", "Banco pequeno ($bank legendas): repete mais. Escreva algumas ou gere com ✨.", null, null)
        // VÍDEOS
        val lib = VideoExport.library(c)
        Scenes.refresh(c)
        val vBroken = lib.filter { !File(VideoExport.videoDir(c), "${it.optString("id")}.mp4").exists() }
        if (vBroken.isNotEmpty()) out += Issue("🎬 Vídeos", "${vBroken.size} vídeo(s) na lista sem o arquivo.", "Limpar") { cc ->
            vBroken.forEach { VideoExport.removeLib(cc, it.optString("id")) }; "${vBroken.size} item(ns) quebrados removidos." }
        val vok = lib.size - vBroken.size
        val rv = VideoExport.recentIds(c).take(10)
        val ro = VideoExport.recentOrigins(c).take(6)
        val origins = lib.map { VideoExport.origin(it) }.distinct().size
        if (vok >= 4 && rv.size >= 6 && (distinctRatio(rv) < 0.7 || (origins >= 3 && distinctRatio(ro) < 0.5)))
            out += Issue("🎬 Vídeos", "Repetindo: ${rv.distinct().size} vídeos e ${ro.distinct().size} vídeos crus diferentes nos últimos posts (você tem $vok de $origins crus).", "Girar fila", ::rotateVideos)
        val scenesN = lib.map { VideoExport.origin(it) }.distinct().size
        val waitingV = lib.count { it.optInt("posts") == 0 }
        if (scenesN in 1..5 && lib.size >= scenesN * 3)
            out += Issue("🎬 Vídeos", "Só $scenesN cena(s) diferente(s) pra ${lib.size} vídeos: no perfil vai parecer tudo igual. Grave/gere mais vídeos crus diferentes ou use menos versões por vídeo.", null, null)
        val top = rv.groupingBy { it }.eachCount().maxByOrNull { it.value }
        if (vok >= 6 && rv.size >= 8 && top != null && top.value >= 3)
            out += Issue("🎬 Vídeos", "Um vídeo saiu ${top.value} vezes nos últimos ${rv.size}.", "Girar fila", ::rotateVideos)
        val waiting = lib.count { it.optInt("posts") == 0 }
        val need = Daily.remaining(c)
        if (need > waiting && Projects.list(c).isNotEmpty()) out += Issue("🎬 Vídeos", "Faltam ${need - waiting} vídeo(s) prontos pra meta de hoje.", "Produzir") { cc -> AutoProducer.refillIfNeeded(cc); "Produção começou em segundo plano." }
        // TEXTOS NO VÍDEO (repetição)
        val txts = lib.take(12).map { it.optString("texto").trim() }.filter { it.isNotEmpty() }
        if (txts.size >= 6 && distinctRatio(txts) < 0.6) out += Issue("T Textos", "Os últimos vídeos repetem texto (${txts.distinct().size} diferentes em ${txts.size}).", null, null)
        Contexts.names(c).filter { !Contexts.noText(c, it) && Projects.list(c).any { p -> p.optString("ctx") == it } && OverlayDB.count(c, it) + OverlayDB.count(c, "") < 3 }
            .forEach { n -> out += Issue("T Textos", "${Contexts.label(c, n)} tem poucos textos: vai repetir.", null, null) }
        // MÚSICA
        // música de fundo dos vídeos PRODUZIDOS (não é a fila de postagem)
        val recentLib = lib.take(8).filter { it.optString("musica").isNotEmpty() }
        val topM = recentLib.groupingBy { it.optString("musica") }.eachCount().maxByOrNull { it.value }
        if (topM != null && recentLib.size >= 5 && topM.value >= 3) {
            val cats = recentLib.filter { it.optString("musica") == topM.key }.map { it.optString("ctx") }.distinct()
            // só é problema se a categoria sorteia música e tem mais de uma pra escolher
            val canVary = cats.any { cx -> MusicPick.mode(c, cx).let { it == MusicPick.AUTO || it == MusicPick.ASK } && MusicPick.pool(c, cx).size >= 2 }
            val name = File(topM.key).nameWithoutExtension.take(30)
            if (canVary && musicExtra(c) == 0)
                out += Issue("🎵 Música dos vídeos", "A mesma música (\"$name\") foi usada em ${topM.value} dos últimos ${recentLib.size} vídeos produzidos.", "Variar a música", ::rotateMusic)
            else if (!canVary && cats.isNotEmpty() && VideoExport.musics(c).size < 2)
                out += Issue("🎵 Música dos vídeos", "Todos os vídeos estão saindo com a mesma música porque só tem 1 salva. Salve mais em 🎵 Músicas.", null, null)
        }
        // VÍDEOS CRUS
        val crus = Projects.list(c)
        val idle = crus.filter { pj -> lib.none { it.optString("proj") == pj.optString("id") } && System.currentTimeMillis() - pj.optLong("created") > 2 * 86400_000L }
        if (idle.size >= 3) out += Issue("🗂 Crus", "${idle.size} vídeos crus nunca viraram vídeo (2+ dias).", null, null)
        return out
    }

    /** Conserta sozinho o que é seguro (girar, limpar quebrados, legenda presa). Devolve o que fez. */
    fun autoFix(c: Context): List<String> {
        if (!autoOn(c)) return emptyList()
        return check(c).filter { it.fix != null && it.fixLabel != "Produzir" }.mapNotNull { i -> try { i.fix!!(c) } catch (e: Exception) { null } }
            .filter { msg -> val k = "hf_" + msg.hashCode(); val last = p(c).getLong(k, 0); (System.currentTimeMillis() - last > 6 * 3600_000L).also { if (it) p(c).edit().putLong(k, System.currentTimeMillis()).apply() } }
            .also { if (it.isNotEmpty()) p(c).edit().putString("health_last", it.joinToString("\n")).putLong("health_at", System.currentTimeMillis()).apply() }
    }
    fun lastFix(c: Context) = p(c).getString("health_last", "") ?: ""
}
