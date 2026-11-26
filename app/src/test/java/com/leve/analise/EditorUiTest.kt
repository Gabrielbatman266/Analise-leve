package com.leve.analise

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Abre um esboço no editor de verdade e usa as ferramentas novas (sem exportar). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorUiTest {
    private fun call(o: Any, name: String, vararg args: Any?): Any? {
        val m = o.javaClass.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true; return m.invoke(o, *args)
    }
    private fun get(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(o) }
    private fun idle() { repeat(30) { Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle() } }

    @Test fun editorComFotoEClipesECamadas() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val ins = get(a, "insta") as InstaEditor
        // esboço de foto
        val pid = Projects.create(a, true)
        val f = Projects.srcFile(a, pid, true)
        Bitmap.createBitmap(90, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            .let { b -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        File(Projects.dir(a, pid), "clip_b.jpg").outputStream().use { Bitmap.createBitmap(90, 160, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        call(ins, "openProject", pid); idle()
        val ed = get(ins, "ed") as EditState
        assertEquals(1, ed.clips.size)
        assertEquals(8_000, ed.total())

        // escrever cria o texto no vídeo todo
        call(ins, "onTyped", "Primeiro texto"); idle()
        assertEquals(1, ed.layers.size); assertEquals(8_000, ed.layers[0].e)

        // ＋ outro texto no cursor
        val tl = get(ins, "timeline") as TimelineView
        tl.playhead = 4_000; call(ins, "onTimeline", "seek", true)
        call(ins, "newTextAndEdit"); call(ins, "onTyped", "Segundo"); idle()
        assertEquals(2, ed.layers.size); assertEquals(4_000, ed.layers[1].s)

        // dividir o texto selecionado
        tl.playhead = 5_000; call(ins, "onTimeline", "seek", true)
        call(ins, "splitHere"); idle()
        assertEquals(3, ed.layers.size)

        // juntar uma foto como 2º clipe, dividir o clipe e mover
        ed.clips.add(Clip("clip_b.jpg", true, 0, 0, 3_000)); call(ins, "afterEdit", "clip")
        assertEquals(11_000, ed.total())
        tl.selKind = ""; tl.playhead = 2_000; call(ins, "onTimeline", "seek", true)
        call(ins, "splitHere"); idle()
        assertEquals(3, ed.clips.size)
        call(ins, "moveClip", 1); idle()
        assertEquals(3, ed.clips.size)

        // desfazer volta a divisão
        call(ins, "doUndo", false); call(ins, "doUndo", false); idle()
        val ed2 = get(ins, "ed") as EditState
        assertEquals(2, ed2.clips.size)

        // tocar e pausar não quebra; salvar grava a edição
        call(ins, "togglePlay"); idle(); call(ins, "togglePlay")
        call(ins, "saveNow")
        val p = Projects.get(a, pid)!!
        assertTrue(p.optJSONObject("edit")!!.getJSONArray("clips").length() == 2)
        assertEquals("Primeiro texto", p.optString("text"))
        // reabrir mantém tudo
        call(ins, "newProject"); call(ins, "openProject", pid); idle()
        val ed3 = get(ins, "ed") as EditState
        assertEquals(2, ed3.clips.size); assertEquals(3, ed3.layers.size)
    }
}
