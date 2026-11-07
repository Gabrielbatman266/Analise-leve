package com.leve.analise

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Aprendizado do que bomba:
 *  - nota de cada post (foto ou vídeo) soma; o que bombou sai mais e pode voltar depois de 2 outros;
 *  - a IA compara o que bombou com o que flopou e escreve regras (foto/vídeo + texto + legenda…);
 *  - IA automática: a cada 6 notas novas, analisa sozinha (1 pedido).
 */
object Learn {
    private fun p(c: Context) = Store.prefs(c)
    const val EVERY = 6

    fun autoOn(c: Context) = p(c).getBoolean("learn_auto", true)
    fun setAuto(c: Context, v: Boolean) = p(c).edit().putBoolean("learn_auto", v).apply()

    fun mean(o: JSONObject): Double {
        val g = o.optInt("good"); val m = o.optInt("meh"); val b = o.optInt("bad")
        val legacy = o.optString("nota").takeIf { g + m + b == 0 }
        val gg = g + (if (legacy == "good") 1 else 0); val mm = m + (if (legacy == "meh") 1 else 0); val bb = b + (if (legacy == "bad") 1 else 0)
        return (gg + 0.5 * mm + 1.0) / (gg + mm + bb + 2.0)
    }
    fun rated(o: JSONObject) = o.optInt("good") + o.optInt("meh") + o.optInt("bad") + (if (o.optInt("good") + o.optInt("meh") + o.optInt("bad") == 0 && o.optString("nota").isNotEmpty()) 1 else 0)
    fun verdict(o: JSONObject) = when { rated(o) == 0 -> ""; mean(o) > 0.6 -> "good"; mean(o) < 0.45 -> "bad"; else -> "meh" }

    // ---------- regras dos vídeos
    fun videoRules(c: Context): List<String> = try { val a = JSONArray(p(c).getString("combo_regras_v", "[]")); (0 until a.length()).map { a.optString(it) } } catch (e: Exception) { emptyList() }
    fun videoRulesText(c: Context) = videoRules(c).takeIf { it.isNotEmpty() }?.let { "🧠 O QUE JÁ SE APRENDEU NOS VÍDEOS DESTE PERFIL — siga:\n" + it.joinToString("\n") { r -> "- $r" } } ?: ""

    /** 🧠 IA compara os vídeos avaliados (quadro + texto + legenda + categoria + música + tempo). */
    fun learnVideos(c: Context): List<String> {
        val lib = VideoExport.library(c).filter { rated(it) > 0 && File(VideoExport.videoDir(c), "${it.optString("id")}.jpg").exists() }
        if (lib.size < 3) throw AiException(-3, "Avalie pelo menos 3 vídeos postados (🔥/😐/👎) pra IA comparar. Agora tem ${lib.size}.")
        val sorted = lib.sortedByDescending { mean(it) }
        val pick = (sorted.take(6) + sorted.takeLast(4)).distinct().take(10)
        val items = pick.map { o ->
            val desc = buildString {
                append("texto no vídeo: ").append(o.optString("texto").replace('\n', ' ').ifBlank { "(sem texto)" })
                o.optString("ctx").takeIf { it.isNotBlank() }?.let { append(" · categoria: ").append(it) }
                o.optString("musica").takeIf { it.isNotBlank() }?.let { append(" · música: ").append(File(it).nameWithoutExtension.take(30)) }
                o.optString("modelo").takeIf { it.isNotBlank() }?.let { append(" · edição: ").append(it) }
                o.optString("legenda").takeIf { it.isNotBlank() }?.let { append(" · legenda: ").append(it.replace('\n', ' ').take(80)) }
            }
            Triple(File(VideoExport.videoDir(c), "${o.optString("id")}.jpg"), desc, verdict(o))
        }
        val r = Ai.learnVideos(c, items)
        if (r.isNotEmpty()) p(c).edit().putString("combo_regras_v", JSONArray(r).toString()).putLong("combo_v_at", System.currentTimeMillis()).apply()
        return r
    }

    /** Chame depois de cada nota nova. A cada 6, a IA analisa sozinha (se ligada e com chave). */
    fun noteRated(c: Context, kind: String) {
        val k = "learn_new_$kind"
        val n = p(c).getInt(k, 0) + 1
        p(c).edit().putInt(k, n).apply()
        if (n < EVERY || !autoOn(c) || Ai.key(c).isEmpty()) return
        p(c).edit().putInt(k, 0).apply()
        Thread {
            try { if (kind == "v") learnVideos(c) else Captions.learnCombos(c) } catch (e: Exception) { }
        }.start()
    }
    fun pending(c: Context, kind: String) = p(c).getInt("learn_new_$kind", 0)
}
