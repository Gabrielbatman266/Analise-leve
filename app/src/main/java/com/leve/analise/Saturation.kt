package com.leve.analise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

/**
 * 😴 Saturação: o que já saiu demais (ou está rendendo cada vez menos) descansa sozinho,
 * e o app pede/produz vídeos DIFERENTES no lugar.
 * Por quê: o Instagram corta da recomendação quem reposta conteúdo igual com frequência,
 * e o público cansa de ver a mesma cena.
 */
object Saturation {
    private fun p(c: Context) = Store.prefs(c)
    fun on(c: Context) = p(c).getBoolean("sat_on", true)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("sat_on", v).apply()
    private const val DAY = 86400_000L

    private fun sc(k: String) = when (k) { "top" -> 1.0; "good" -> 0.8; "meh" -> 0.45; "bad" -> 0.0; else -> 0.5 }

    /** Caindo: a última nota bem pior que a primeira (2+ notas). */
    private fun declining(o: JSONObject): Boolean {
        val h = Learn.hist(o); if (h.size < 2) return false
        return sc(h.first().second) - sc(h.last().second) >= 0.35
    }

    fun resting(o: JSONObject) = o.optLong("restUntil") > System.currentTimeMillis()
    private fun sceneRest(c: Context): JSONObject = try { JSONObject(p(c).getString("scene_rest", "{}")) } catch (e: Exception) { JSONObject() }
    fun sceneResting(c: Context, scene: String) = sceneRest(c).optLong(scene) > System.currentTimeMillis()

    /** Vídeo pode sair agora? (não descansando, nem a cena dele) */
    fun videoOk(c: Context, o: JSONObject) = !resting(o) && !sceneResting(c, VideoExport.origin(o))
    fun photoOk(o: JSONObject) = !resting(o)

    /** Roda de hora em hora: põe pra descansar e pede/produz diferentes. Devolve o resumo (ou null). */
    fun tick(c: Context): String? {
        if (!on(c)) return null
        Scenes.refresh(c)
        val now = System.currentTimeMillis()
        val sends = SendLog.all(c)
        val parts = ArrayList<String>()
        // ---------- vídeos
        var vRest = 0
        VideoExport.library(c).forEach { o ->
            if (resting(o)) return@forEach
            val id = o.optString("id")
            val n30 = sends.count { it.optString("kind") == "video" && it.optString("id") == id && now - it.optLong("at") < 30 * DAY }
            val why = when {
                n30 >= 2 -> "saiu ${n30}x em 30 dias (o Instagram corta conteúdo repetido)"
                declining(o) -> "rendendo cada vez menos"
                else -> null
            } ?: return@forEach
            VideoExport.updateLib(c, id) { it.put("restUntil", now + 30 * DAY); it.put("restWhy", why) }; vRest++
        }
        // cenas: mesma cena 3+ vezes em 14 dias descansa 14 dias
        val lib = VideoExport.library(c).associateBy { it.optString("id") }
        val byScene = sends.filter { it.optString("kind") == "video" && now - it.optLong("at") < 14 * DAY }.mapNotNull { e -> lib[e.optString("id")]?.let { VideoExport.origin(it) } }
            .groupingBy { it }.eachCount()
        val sr = sceneRest(c); var sRest = 0
        val scenes = lib.values.map { VideoExport.origin(it) }.distinct().size
        byScene.filter { it.value >= 3 && !sceneResting(c, it.key) }.forEach { (sc0, _) -> if (scenes > 3) { sr.put(sc0, now + 14 * DAY); sRest++ } }
        if (sRest > 0) p(c).edit().putString("scene_rest", sr.toString()).apply()
        if (vRest + sRest > 0) parts += "😴 ${vRest} vídeo(s) e ${sRest} cena(s) descansando"
        // ---------- fotos (Threads)
        var fRest = 0
        PostQueue.load(c).forEach { o ->
            if (resting(o)) return@forEach
            val id = o.optString("id")
            val n14 = sends.count { it.optString("kind") == "fila" && it.optString("id") == id && now - it.optLong("at") < 14 * DAY }
            if (n14 >= 3 || declining(o)) { PostQueue.update(c, id) { it.put("restUntil", now + 10 * DAY); it.put("restWhy", if (n14 >= 3) "saiu ${n14}x em 14 dias" else "rendendo cada vez menos") }; fRest++ }
        }
        if (fRest > 0) parts += "😴 $fRest foto(s) descansando"
        // ---------- pede/produz diferentes
        val fresh = VideoExport.library(c).count { it.optInt("posts") == 0 && videoOk(c, it) }
        val need = maxOf(Daily.remaining(c), 3) - fresh
        if (need > 0) {
            val usable = Projects.list(c).filter { pj -> lib.values.none { v -> v.optString("proj") == pj.optString("id") && !videoOk(c, v) } }
            if (usable.isNotEmpty() && !AutoProducer.running) { produceDifferent(c, minOf(need, 6)); parts += "🎬 produzindo $need vídeo(s) de cenas diferentes" }
            else if (now - p(c).getLong("sat_ask", 0) > 20 * 3600_000L) { askNewRaws(c); p(c).edit().putLong("sat_ask", now).apply(); parts += "📦 pedi vídeos crus novos" }
        }
        if (parts.isEmpty()) return null
        val msg = parts.joinToString(" · ")
        p(c).edit().putString("sat_last", msg).apply()
        if (vRest + sRest + fRest > 0) notify(c, "😴 Conteúdo saturado descansando", msg + "\nPra o perfil não parecer repetido (e o Instagram não cortar o alcance).")
        return msg
    }

    /** Produz preferindo vídeos crus de cenas que NÃO estão descansando e que saíram menos. */
    private fun produceDifferent(c: Context, n: Int) {
        p(c).edit().putBoolean("prefer_fresh_scenes", true).apply()
        try {
            val i = Intent(c, ProduceService::class.java).putExtra("n", n)
            if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        } catch (e: Exception) { ErrLog.log(c, "saturação: produzir", e) }
    }

    /** Sem vídeo cru utilizável: avisa com ideias de cenas NOVAS pro Flow. */
    private fun askNewRaws(c: Context) {
        val ideas = try { if (Auto.canSpend(c)) Stock.makeIdeas(c) else Stock.ideas(c) } catch (e: Exception) { Stock.ideas(c) }
        notify(c, "📦 Preciso de vídeos crus NOVOS", "As cenas que você tem já saíram bastante. Gere no Flow cenas diferentes (outro cenário, roupa, ação)." +
            (ideas.firstOrNull { it.startsWith("VIDEO") }?.let { "\n💡 Ideia: $it" } ?: ""))
    }

    private fun notify(c: Context, title: String, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("saturacao", "Saturação", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 101, Intent(c, MainActivity::class.java).putExtra("tab", "Instagram").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(c, "saturacao").setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle(title).setContentText(text.lines().first())
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
        try { nm.notify(102, b.build()) } catch (e: SecurityException) { }
    }

    fun restingVideos(c: Context) = VideoExport.library(c).filter { !videoOk(c, it) }
    fun restingPhotos(c: Context) = PostQueue.load(c).filter { resting(it) }
    fun wake(c: Context, kind: String, id: String) {
        if (kind == "video") VideoExport.updateLib(c, id) { it.remove("restUntil") } else PostQueue.update(c, id) { it.remove("restUntil") }
    }
    fun last(c: Context) = p(c).getString("sat_last", "") ?: ""
}

/** ⏱ Duração automática: testa 6/8/10/12 s e fica mais com a que rende (por categoria). */
object AutoDur {
    val OPTS = listOf(6, 8, 10, 12)
    fun pick(c: Context, ctx: String): Int {
        val lib = VideoExport.library(c).filter { it.optString("ctx") == ctx && it.optInt("dur") > 0 && Learn.rated(it) + (if (ThreadsApi.videoScore(c, it.optString("id")) != null) 1 else 0) > 0 }
        val score = OPTS.associateWith { d ->
            val v = lib.filter { kotlin.math.abs(it.optInt("dur") - d) <= 1 }
            val s = v.map { ThreadsApi.videoScore(c, it.optString("id")) ?: Learn.mean(it) }
            if (s.isEmpty()) 0.5 + Math.random() * 0.3 else s.average() + (Math.random() - 0.5) * 0.4 / Math.sqrt(s.size + 1.0) // explora pouco testados
        }
        return score.maxBy { it.value }.key
    }
}
