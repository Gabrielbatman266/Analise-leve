package com.leve.analise

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Listas do app gravadas com segurança:
 *  - grava num arquivo temporário e só depois troca (se o celular matar o app no meio, o antigo continua inteiro);
 *  - guarda a versão anterior (.bak) e lê dela se a principal estiver estragada;
 *  - uma trava por arquivo: duas partes do app nunca gravam ao mesmo tempo.
 */
object JsonFile {
    private val locks = ConcurrentHashMap<String, Any>()
    fun lock(f: File): Any = locks.getOrPut(f.path) { Any() }

    private fun parse(f: File): MutableList<JSONObject>? = try {
        if (!f.exists()) null else { val a = JSONArray(f.readText()); (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
    } catch (e: Exception) { null }

    fun read(f: File): MutableList<JSONObject> = synchronized(lock(f)) {
        parse(f) ?: parse(File(f.path + ".bak")) ?: mutableListOf()
    }

    fun write(f: File, l: List<JSONObject>) = synchronized(lock(f)) {
        val a = JSONArray(); l.forEach { a.put(it) }
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { out -> out.write(a.toString().toByteArray(Charsets.UTF_8)); out.fd.sync() }
        if (f.exists() && parse(f) != null) f.copyTo(File(f.path + ".bak"), true)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
    }

    /** Lê, muda e grava sem ninguém mexer no meio. */
    fun <T> edit(f: File, block: (MutableList<JSONObject>) -> T): T = synchronized(lock(f)) {
        val l = read(f); val r = block(l); write(f, l); r
    }
}

/** Recupera itens que sumiram da lista mas cujo arquivo continua no celular. */
object Recover {
    fun run(c: Context): IntArray {
        var fotos = 0; var videos = 0; var crus = 0; var refs = 0
        // fotos da fila do Threads
        try {
            val known = PostQueue.load(c).map { it.optString("id") }.toSet()
            val lost = PostQueue.dir(c).listFiles { f -> f.name.endsWith(".jpg") }?.filter { it.nameWithoutExtension !in known }.orEmpty()
            if (lost.isNotEmpty()) PostQueue.restore(c, lost.sortedBy { it.lastModified() }.map { it.nameWithoutExtension to it.lastModified() }).also { fotos = it }
        } catch (e: Exception) { }
        // vídeos prontos
        try {
            val known = VideoExport.library(c).map { it.optString("id") }.toSet()
            val lost = VideoExport.videoDir(c).listFiles { f -> f.name.endsWith(".mp4") }?.filter { it.nameWithoutExtension !in known }.orEmpty()
            if (lost.isNotEmpty()) videos = VideoExport.restore(c, lost.map { it.nameWithoutExtension to it.lastModified() })
        } catch (e: Exception) { }
        // vídeos crus / projetos
        try { crus = Projects.restore(c) } catch (e: Exception) { }
        // fotos de referência
        try { refs = Refs.restorePhotos(c) } catch (e: Exception) { }
        return intArrayOf(fotos, videos, crus, refs)
    }
}
