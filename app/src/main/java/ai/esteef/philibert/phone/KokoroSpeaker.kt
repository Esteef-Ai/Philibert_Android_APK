package ai.esteef.philibert.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Talk's natural voice: each sentence is voiced by Kokoro on the computer (the phone api's "speak")
 * and played here, in order, the next ones fetched while one plays. Same contract as the phone's
 * TextToSpeech: speak(text, id), then events("start" | "done", id); stop() drops everything queued.
 * A sentence the computer can't voice goes to `fallback` (the phone's own voice), which must call
 * the latch when it has been said.
 */
class KokoroSpeaker(
    private val c: Context,
    private val events: (String, String) -> Unit,
    private val fallback: (String, String, CountDownLatch) -> Unit,
) {
    private class Item(val text: String, val id: String, val gen: Int) {
        @Volatile var pcm: ByteArray? = null
        @Volatile var rate = 24000
        val ready = CountDownLatch(1)
    }

    var voice = "af_heart"
    var speed = 1.0f
    @Volatile private var gen = 0
    private val queue = LinkedBlockingQueue<Item>()
    private val fetchers = Executors.newFixedThreadPool(2)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var closed = false
    private val player = thread(name = "philibert-voice", isDaemon = true) { play() }

    fun speak(text: String, id: String) {
        val item = Item(text, id, gen)
        fetchers.execute {
            if (item.gen == gen) fetch(item)
            item.ready.countDown()
        }
        queue.add(item)
    }

    /** Stop now: whatever is playing and everything queued. */
    fun stop() {
        gen++
        queue.clear()
        track?.let { t -> try { t.pause(); t.flush() } catch (e: Exception) { } }
    }

    fun shutdown() {
        closed = true
        stop()
        player.interrupt()
        fetchers.shutdownNow()
        track?.release()
        track = null
    }

    private fun fetch(item: Item) {
        val base = Prefs.address(c)
        if (base.isEmpty()) return
        try {
            val conn = URL(base + "api/speak").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("text", item.text).put("voice", voice).put("speed", speed.toDouble())
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode == 200 && (conn.contentType ?: "").startsWith("audio/")) {
                val wav = conn.inputStream.use { it.readBytes() }
                parse(wav, item)
            }
            conn.disconnect()
        } catch (e: Exception) { /* falls back to the phone's voice */ }
    }

    /** WAV -> the 16-bit PCM inside, and its sample rate. */
    private fun parse(wav: ByteArray, item: Item) {
        if (wav.size < 44 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF") return
        fun le32(i: Int) = (wav[i].toInt() and 0xff) or ((wav[i + 1].toInt() and 0xff) shl 8) or
            ((wav[i + 2].toInt() and 0xff) shl 16) or ((wav[i + 3].toInt() and 0xff) shl 24)
        var p = 12
        while (p + 8 <= wav.size) {
            val id = String(wav, p, 4, Charsets.US_ASCII)
            val len = le32(p + 4)
            if (id == "fmt ") item.rate = le32(p + 12)
            if (id == "data") {
                val end = minOf(wav.size, p + 8 + len)
                item.pcm = wav.copyOfRange(p + 8, end)
                return
            }
            p += 8 + len + (len and 1)
        }
    }

    private fun trackFor(rate: Int): AudioTrack {
        track?.let { if (it.sampleRate == rate) return it; it.release() }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT), rate))
            .build()
        track = t
        return t
    }

    private fun play() {
        while (!closed) {
            val item = try { queue.take() } catch (e: InterruptedException) { return }
            if (item.gen != gen) continue
            item.ready.await(40, TimeUnit.SECONDS)
            if (item.gen != gen) continue
            val pcm = item.pcm
            if (pcm == null) {
                // the computer couldn't say it: the phone's voice does, and this waits for it
                val said = CountDownLatch(1)
                events("start", item.id)
                fallback(item.text, item.id, said)
                said.await(60, TimeUnit.SECONDS)
                if (item.gen == gen) events("done", item.id)
                continue
            }
            val t = trackFor(item.rate)
            events("start", item.id)
            try {
                t.play()
                val startHead = t.playbackHeadPosition
                var off = 0
                while (off < pcm.size && item.gen == gen) {
                    val n = t.write(pcm, off, minOf(8192, pcm.size - off))
                    if (n <= 0) break
                    off += n
                }
                val frames = off / 2
                // until the last of it has actually been heard
                val deadline = System.currentTimeMillis() + frames * 1000L / item.rate + 1500
                while (item.gen == gen && t.playbackHeadPosition - startHead < frames && System.currentTimeMillis() < deadline)
                    Thread.sleep(15)
            } catch (e: Exception) { }
            if (item.gen == gen) events("done", item.id)
        }
    }
}
