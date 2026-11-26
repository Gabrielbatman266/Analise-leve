package com.leve.analise

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Editor estilo CapCut: clipes, dividir, camadas, desfazer e a linha do tempo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditTest {
    private fun two(): EditState = EditState().apply {
        clips += Clip("src.mp4", false, 10_000, 1_000, 6_000)   // 5 s
        clips += Clip("clip_b.mp4", false, 4_000, 0, 4_000)     // 4 s
        layers += Layer("text", "oi", s = 0, e = 9_000)
    }

    @Test fun totalELocate() {
        val st = two()
        assertEquals(9_000, st.total())
        assertEquals(0 to 1_000L, st.locate(0))
        assertEquals(0 to 5_999L, st.locate(4_999))
        assertEquals(1 to 0L, st.locate(5_000))
        assertEquals(1 to 3_999L, st.locate(20_000)) // depois do fim: último quadro
        assertEquals(5_000, st.startOf(1))
    }

    @Test fun dividirClipe() {
        val st = two()
        val k = st.splitClip(2_000)
        assertEquals(1, k)
        assertEquals(3, st.clips.size)
        assertEquals(1_000L to 3_000L, st.clips[0].s to st.clips[0].e)
        assertEquals(3_000L to 6_000L, st.clips[1].s to st.clips[1].e)
        assertEquals(9_000, st.total()) // dividir não muda a duração
        assertNull(st.splitClip(5_050)) // colado no corte: não divide
    }

    @Test fun dividirCamada() {
        val st = two()
        val i = st.splitLayer(0, 3_000)
        assertEquals(1, i)
        assertEquals(0L to 3_000L, st.layers[0].s to st.layers[0].e)
        assertEquals(3_000L to 9_000L, st.layers[1].s to st.layers[1].e)
        assertEquals("oi", st.layers[1].text)
    }

    @Test fun apagarClipeEncolheCamadas() {
        val st = two()
        st.layers += Layer("image", file = "ov.png", s = 7_000, e = 8_500)
        st.clips.removeAt(1); st.clamp()
        assertEquals(5_000, st.total())
        assertEquals(5_000, st.layers[0].e)
        assertTrue(st.layers[1].s < st.layers[1].e && st.layers[1].e <= 5_000)
    }

    @Test fun salvarEAbrir() {
        val st = two(); st.clips[1].sound = false
        st.clips[0].crop = VideoExport.CropSpec(1.5f, 0.2f, -0.1f, true)
        st.layers[0].style.x = 0.3f; st.layers[0].style.y = 0.7f
        st.music = "/x/song.mp3"; st.musicStart = 1200; st.musicEnd = 4000
        val p = JSONObject(); st.saveTo(p)
        // campos antigos (produção automática) continuam certos
        assertEquals(1_000, p.optLong("cutStart")); assertEquals(6_000, p.optLong("cutEnd"))
        assertEquals("oi", p.optString("text")); assertEquals(0, p.optLong("textEnd")) // até o fim
        val back = EditState.fromProject(JSONObject(p.toString()), 10_000)
        assertEquals(2, back.clips.size)
        assertEquals(false, back.clips[1].sound)
        assertEquals(1.5f, back.clips[0].crop.zoom, 0.001f)
        assertEquals(0.7f, back.layers[0].style.y, 0.001f)
        assertEquals(1200, back.musicStart)
    }

    @Test fun projetoAntigoViraUmClipe() {
        val p = JSONObject().put("cutStart", 500).put("cutEnd", 0).put("text", "olá").put("textStart", 1000).put("textEnd", 0)
        val st = EditState.fromProject(p, 8_000)
        assertEquals(1, st.clips.size)
        assertEquals(7_500, st.total())
        assertEquals(1_000L to 7_500L, st.layers[0].s to st.layers[0].e)
        assertTrue(st.simple())
        val photo = EditState.fromProject(JSONObject().put("photo", true).put("secs", 5), 0)
        assertEquals(5_000, photo.total())
        assertTrue(photo.clips[0].photo)
    }

    @Test fun desfazerRefazer() {
        val u = Undo(); var st = two()
        u.push(st); st.splitClip(2_000)
        assertEquals(3, st.clips.size)
        st = u.undo(st)!!; assertEquals(2, st.clips.size)
        st = u.redo(st)!!; assertEquals(3, st.clips.size)
        assertNull(u.redo(st))
    }

    @Test fun camadasDesenham() {
        val dir = RuntimeEnvironment.getApplication().cacheDir
        val st = two()
        st.layers[0].style.y = 0.25f
        val b = LayerArt.render(st.layers, dir, 540, 960)
        assertEquals(540, b.width)
        val box = LayerArt.box(st.layers[0], dir, 540, 960)
        assertEquals(0.25f * 960, box.centerY(), 2f)
        // overlay da exportação: com deslocamento do 2º clipe, o texto (0–3 s) não aparece em 5 s
        st.layers[0].e = 3_000
        val ov = LayersOverlay(st.layers, 5_000, dir, 108, 192)
        ov.getBitmap(0)
        assertEquals(0f, ov.getOverlaySettings(0).alphaScale, 0.001f)
        val ov0 = LayersOverlay(st.layers, 0, dir, 108, 192)
        ov0.getBitmap(33_000)
        assertEquals(1f, ov0.getOverlaySettings(33_000).alphaScale, 0.001f)
        assertEquals(1f, ov0.getOverlaySettings(2_000_000 + 33_000).alphaScale, 0.001f)
        assertEquals(0f, ov0.getOverlaySettings(3_100_000 + 33_000).alphaScale, 0.001f)
    }

    private fun touch(v: View, action: Int, x: Float, y: Float, t0: Long) {
        val e = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y, 0)
        v.dispatchTouchEvent(e); e.recycle()
    }

    @Test fun linhaDoTempoSelecionaECorta() {
        val c = RuntimeEnvironment.getApplication()
        val tl = TimelineView(c)
        val st = two(); tl.st = st
        tl.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        tl.layout(0, 0, 1000, tl.measuredHeight)
        val bmp = Bitmap.createBitmap(1000, tl.measuredHeight, Bitmap.Config.ARGB_8888)
        tl.draw(Canvas(bmp)) // não quebra desenhando
        var changes = 0; var selected = 0; var before = 0
        tl.onChange = { _, _ -> changes++ }; tl.onSelect = { selected++ }; tl.onBeforeEdit = { before++ }
        val d = c.resources.displayMetrics.density
        val yV = 18 * d + 5 * d + 10 * d // dentro da trilha de vídeo
        // cursor em 0 no meio da tela: tocar um pouco à direita = 1º clipe
        val t0 = SystemClock.uptimeMillis()
        touch(tl, MotionEvent.ACTION_DOWN, 560f, yV, t0); touch(tl, MotionEvent.ACTION_UP, 560f, yV, t0)
        assertEquals("clip", tl.selKind); assertEquals(0, tl.selIdx); assertEquals(1, selected)
        // arrastar a alça esquerda do clipe (fica no cursor, x = 500) pra direita = corta o começo
        tl.draw(Canvas(bmp))
        touch(tl, MotionEvent.ACTION_DOWN, 500f, yV, t0)
        touch(tl, MotionEvent.ACTION_MOVE, 540f, yV, t0)
        touch(tl, MotionEvent.ACTION_MOVE, 560f, yV, t0)
        touch(tl, MotionEvent.ACTION_UP, 560f, yV, t0)
        assertTrue("o começo do clipe deveria andar: ${st.clips[0].s}", st.clips[0].s > 1_000)
        assertEquals(1, before)
        assertTrue(changes > 0)
        assertNotNull(st.clips[0])
        // arrastar no vazio anda o cursor
        tl.selKind = ""
        val ph = tl.playhead
        touch(tl, MotionEvent.ACTION_DOWN, 700f, yV, t0)
        touch(tl, MotionEvent.ACTION_MOVE, 660f, yV, t0)
        touch(tl, MotionEvent.ACTION_MOVE, 600f, yV, t0)
        touch(tl, MotionEvent.ACTION_UP, 600f, yV, t0)
        assertTrue(tl.playhead > ph)
    }
}
