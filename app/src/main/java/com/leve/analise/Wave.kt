package com.leve.analise

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

/** Onda da música (pico a cada 50 ms), pra sincronizar olhando a batida. Fica em cache. */
object Wave {
    private const val BIN_MS = 50

    private fun cache(c: Context, f: File) = File(File(c.cacheDir, "waves").apply { mkdirs() }, "${f.name.hashCode()}_${f.length()}.bin")

    fun load(c: Context, f: File): FloatArray? {
        val cf = cache(c, f)
        if (cf.exists()) try {
            DataInputStream(cf.inputStream().buffered()).use { d -> return FloatArray(d.readInt()) { d.readFloat() } }
        } catch (e: Exception) { }
        val w = compute(f) ?: return null
        try { DataOutputStream(cf.outputStream().buffered()).use { d -> d.writeInt(w.size); w.forEach { d.writeFloat(it) } } } catch (e: Exception) { }
        return w
    }

    private fun compute(f: File): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(f.path)
            var track = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val tf = ex.getTrackFormat(i)
                if (tf.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = tf; break }
            }
            if (track < 0 || fmt == null) return null
            ex.selectTrack(track)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var per = maxOf(1, rate * ch * BIN_MS / 1000)
            val out = ArrayList<Float>(4000)
            var cur = 0; var cnt = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            val limit = 12 * 60 * 1000 / BIN_MS // até 12 minutos
            while (!outDone && out.size < limit) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    per = maxOf(1, rate * ch * BIN_MS / 1000)
                } else if (oi >= 0) {
                    val buf = codec.getOutputBuffer(oi)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (sb.hasRemaining()) {
                        val v = abs(sb.get().toInt()); if (v > cur) cur = v
                        if (++cnt >= per) { out.add(cur / 32768f); cur = 0; cnt = 0 }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            if (out.isEmpty()) return null
            val mx = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
            return FloatArray(out.size) { (out[it] / mx).coerceIn(0f, 1f) }
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop(); codec?.release() } catch (e: Exception) { }
            ex.release()
        }
    }
}
