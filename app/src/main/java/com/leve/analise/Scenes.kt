package com.leve.analise

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/**
 * Cenas: agrupa vídeos que são A MESMA CENA, mesmo com texto diferente ou vindos de vídeos crus diferentes.
 * Compara a imagem do vídeo em 3 faixas (cima, meio, baixo); se 2 das 3 batem, é a mesma cena
 * (o texto costuma ocupar só uma faixa). Sem IA, roda no celular.
 */
object Scenes {
    @Volatile private var map: Map<String, String> = emptyMap()
    @Volatile private var sig = ""

    /** 3 assinaturas de 64 bits (uma por faixa). */
    fun hash(b: Bitmap): LongArray {
        val s = Bitmap.createScaledBitmap(b, 9, 24, true)
        val g = Array(24) { y -> IntArray(9) { x -> val p = s.getPixel(x, y); ((p shr 16 and 255) * 30 + (p shr 8 and 255) * 59 + (p and 255) * 11) / 100 } }
        return LongArray(3) { band ->
            var h = 0L; var bit = 0
            for (y in band * 8 until band * 8 + 8) for (x in 0 until 8) { if (g[y][x] > g[y][x + 1]) h = h or (1L shl bit); bit++ }
            h
        }
    }

    fun same(a: LongArray, b: LongArray): Boolean = (0 until 3).count { java.lang.Long.bitCount(a[it] xor b[it]) <= 12 } >= 2

    private fun enc(h: LongArray) = h.joinToString(",") { it.toString() }
    private fun dec(s: String) = s.split(",").mapNotNull { it.toLongOrNull() }.takeIf { it.size == 3 }?.toLongArray()

    /** Atualiza os grupos de cena (rápido quando nada mudou). */
    fun refresh(c: Context): Map<String, String> {
        val lib = VideoExport.library(c)
        val s = lib.joinToString("|") { it.optString("id") }.hashCode().toString() + ":" + lib.size
        if (s == sig && map.isNotEmpty()) return map
        // assinatura de cada vídeo (fica salva no item)
        val hashes = HashMap<String, LongArray>()
        val toSave = HashMap<String, String>()
        lib.forEach { o ->
            val id = o.optString("id")
            val h = dec(o.optString("sh")) ?: run {
                val f = File(VideoExport.videoDir(c), "$id.jpg")
                val b = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
                b?.let { hash(it) }?.also { toSave[id] = enc(it) }
            }
            if (h != null) hashes[id] = h
        }
        if (toSave.isNotEmpty()) toSave.forEach { (id, v) -> VideoExport.updateLib(c, id) { it.put("sh", v) } }
        // une: mesmo vídeo cru OU imagem parecida
        val parent = HashMap<String, String>(); lib.forEach { parent[it.optString("id")] = it.optString("id") }
        fun find(x: String): String { var r = x; while (parent[r] != r) r = parent[r]!!; return r }
        fun union(a: String, b: String) { val ra = find(a); val rb = find(b); if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) }
        lib.groupBy { it.optString("proj") }.filterKeys { it.isNotEmpty() }.values.forEach { g -> g.drop(1).forEach { union(g[0].optString("id"), it.optString("id")) } }
        val ids = hashes.keys.toList()
        for (i in ids.indices) for (j in i + 1 until ids.size) if (same(hashes.getValue(ids[i]), hashes.getValue(ids[j]))) union(ids[i], ids[j])
        map = lib.associate { o -> o.optString("id") to "cena:" + find(o.optString("id")) }
        sig = s
        return map
    }

    fun of(o: JSONObject): String? = map[o.optString("id")]
    fun count(c: Context) = refresh(c).values.distinct().size
}
