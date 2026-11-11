package ai.esteef.philibert.phone

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Hears the person start talking while Philibert is speaking (or thinking), so they can cut in and
 * steer without touching the phone.
 *
 * The microphone is opened as a voice call opens it (VOICE_COMMUNICATION, the phone's echo
 * canceller and noise suppressor on), so Philibert's own voice is mostly taken out. What is left of
 * it is learned as the floor: it follows the loudest recent level of that echo and sinks slowly in
 * the gaps between sentences. Speech well above the floor for a quarter of a second = cut in.
 * Sensitivity sets how far above (dB).
 */
class BargeIn(private val onSpeech: () -> Unit) {
    @Volatile private var running = false
    @Volatile private var relearn = 0
    private var worker: Thread? = null

    /** margin in dB above the echo floor: 8 = sensitive, 12 = normal, 17 = only clearly louder speech */
    @Volatile var margin = 12.0

    fun active() = running

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) { relearnNow(); return }
        running = true
        relearn = 30                                     // 0.6 s of learning what "quiet" sounds like
        worker = thread(name = "philibert-bargein") {
            val rate = 16000
            val frame = 320                              // 20 ms
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(min, frame * 2 * 10))
            } catch (e: Exception) { running = false; return@thread }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); running = false; return@thread }
            val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null
            val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null
            val buf = ShortArray(frame)
            var floor = 0.0
            var heard = false
            var over = 0
            var window = 0
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, frame)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                    val db = 20 * log10(sqrt(sum / n) + 1.0)          // 0 .. ~90 for 16-bit audio
                    if (relearn > 0) {                               // learning: the loudest echo / room noise heard
                        floor = if (relearn == 30) db else max(floor, db)
                        relearn--
                        over = 0; window = 0
                        continue
                    }
                    val threshold = max(floor + margin, 48.0)
                    if (db > threshold) {
                        over++
                    } else {
                        // not speech: the floor follows the echo up, and sinks ~5 dB/s in the gaps
                        floor = if (db > floor) floor + (db - floor) * 0.3 else floor - 0.1
                    }
                    window++
                    if (window >= 16) {                              // ~320 ms windows
                        if (over >= 11) {                            // ~220 ms of it above the floor: they're talking
                            heard = true
                            break
                        }
                        over = (over * 0.5).toInt(); window = 8
                    }
                }
            } catch (e: Exception) {
                // the microphone was taken (the recognizer started): just stop
            } finally {
                try { rec.stop() } catch (e: Exception) { }
                aec?.release(); ns?.release()
                rec.release()
                running = false
            }
            // only now, with the microphone let go, so the speech recognizer can have it
            if (heard) onSpeech()
        }
    }

    /** Philibert's voice is about to start (or changed): learn the new floor before listening for speech. */
    fun relearnNow() { relearn = 30 }

    fun stop() {
        running = false
        worker?.join(300)
        worker = null
    }
}
