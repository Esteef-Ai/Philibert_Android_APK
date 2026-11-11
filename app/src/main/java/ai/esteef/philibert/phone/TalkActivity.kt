package ai.esteef.philibert.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Talk: a spoken back-and-forth with the model on the computer, for brainstorming (in the car too).
 * The phone listens (its own speech recognition) and speaks (its own voice); the computer only thinks,
 * and the reply is spoken sentence by sentence while it is still being written. Hands-free: when
 * Philibert finishes speaking it listens again. Tap the orb to interrupt or to send right away.
 * Say "pause" to stop listening, "wrap it up" / "save the plan" to have the session written up as a
 * plan and saved to Philibert's notes (Brainstorms).
 */
class TalkActivity : Activity(), RecognitionListener, TextToSpeech.OnInitListener {
    private enum class S { IDLE, LISTENING, THINKING, SPEAKING, PAUSED, SUMMARY }

    private var s = S.IDLE
    private val ui = Handler(Looper.getMainLooper())
    private val dp get() = resources.displayMetrics.density

    private lateinit var orb: OrbView
    private lateinit var stateText: TextView
    private lateinit var caption: TextView
    private lateinit var log: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var pauseBtn: Button
    private lateinit var body: FrameLayout
    private var summaryView: View? = null

    private var sr: SpeechRecognizer? = null
    private var onDevice = true
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var focus: AudioFocusRequest? = null

    private val history = mutableListOf<JSONObject>()      // {role, content}
    private var model = ""

    // this turn: what was heard, and the reply being spoken
    private val heard = StringBuilder()
    private var grace: Runnable? = null
    private var idleSince = 0L
    @Volatile private var turn = 0
    private var job: String? = null
    private var streamed = ""
    private var spokenUpTo = 0
    private var queued = 0
    private var spoken = 0
    private var replyDone = false

    private val PAUSE_CMD = Regex("^(ok |okay )?(stop listening|pause|pause listening|hold on|go to sleep|stop)[.!]?$", RegexOption.IGNORE_CASE)
    private val WRAP_CMD = Regex("\\b(wrap (it|this|things|that) up|summari[sz]e (it|this|that|everything|the (session|conversation|plan|ideas?))|" +
        "save (the|this|that|my) (plan|session|conversation|ideas?|notes?)|write (it|this|that) (up|down))\\b", RegexOption.IGNORE_CASE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        model = intent.getStringExtra("model")?.takeIf { it.isNotEmpty() } ?: Prefs.talkModel(this)
        if (model.isNotEmpty()) Prefs.setTalkModel(this, model)
        try {
            val a = JSONArray(Prefs.talkHistory(this))
            for (i in 0 until a.length()) history.add(a.getJSONObject(i))
        } catch (e: Exception) { /* a fresh talk */ }
        build()
        history.forEach { bubble(it.optString("role"), it.optString("content")) }
        tts = TextToSpeech(this, this)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else state(S.PAUSED, "Philibert needs the microphone to talk. Tap the orb to try again.")
    }

    override fun onPause() {
        super.onPause()
        if (s != S.SUMMARY && !isChangingConfigurations) pause("Paused - tap the orb to talk")
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        job?.let { j -> thread { Net.post(this, "cancel", JSONObject().put("job", j)) } }
        job = null
        sr?.destroy()
        tts?.shutdown()
        unduck()
        super.onDestroy()
    }

    // ---- the screen ----------------------------------------------------------------------------------
    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            fitsSystemWindows = true
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), 0)
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(TextView(this).apply {
            text = "ESTEEF AI · TALK"; setTextColor(getColor(R.color.cyan)); textSize = 10f
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.18f
        })
        titles.addView(TextView(this).apply {
            text = if (model.isEmpty()) "Philibert" else "Philibert · " + model.substringAfterLast('/')
            setTextColor(getColor(R.color.txt)); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        top.addView(titles)
        top.addView(flat("New talk") { newTalk() })
        top.addView(flat("✕") { finish() })
        root.addView(top)

        body = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        orb = OrbView(this).apply { setOnClickListener { orbTapped() } }
        col.addView(orb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (230 * dp).toInt()))
        stateText = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(getColor(R.color.txt)); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        }
        col.addView(stateText)
        caption = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(getColor(R.color.dim)); textSize = 15f
            setPadding((20 * dp).toInt(), (6 * dp).toInt(), (20 * dp).toInt(), (6 * dp).toInt())
            maxLines = 4; ellipsize = TextUtils.TruncateAt.START
        }
        col.addView(caption)
        log = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }
        logScroll = ScrollView(this).apply { addView(log) }
        col.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(col)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        }
        pauseBtn = Button(this).apply {
            text = "Pause"; isAllCaps = false
            setOnClickListener { if (s == S.PAUSED || s == S.IDLE) start() else pause("Paused - tap the orb to talk") }
        }
        bottom.addView(pauseBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(Button(this).apply {
            text = "Wrap up & save"; isAllCaps = false
            setOnClickListener { wrapUp(autoSave = false) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bottom)
        setContentView(root)
        state(S.IDLE, "Getting ready…")
    }

    private fun flat(t: String, go: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = t; isAllCaps = false; setTextColor(getColor(R.color.dim)); setOnClickListener { go() }
    }

    private fun bubble(role: String, text: String) {
        val me = role == "user"
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14.5f
            setTextColor(getColor(R.color.txt))
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(if (me) 0xFF0E5D78.toInt() else getColor(R.color.panel))
            }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (me) Gravity.END else Gravity.START
            topMargin = (6 * dp).toInt()
            if (me) leftMargin = (40 * dp).toInt() else rightMargin = (40 * dp).toInt()
        }
        log.addView(tv, lp)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun state(n: S, text: String, cap: String? = null) {
        s = n
        stateText.text = text
        if (cap != null) caption.text = cap
        orb.mode = when (n) {
            S.LISTENING -> OrbView.LISTEN
            S.THINKING, S.SUMMARY -> OrbView.THINK
            S.SPEAKING -> OrbView.SPEAK
            else -> OrbView.IDLE
        }
        if (n != S.LISTENING) orb.level = 0f
        pauseBtn.text = if (n == S.PAUSED || n == S.IDLE) "Talk" else "Pause"
    }

    private fun saveHistory() = Prefs.setTalkHistory(this, JSONArray(history.takeLast(200)).toString())

    // ---- voice -----------------------------------------------------------------------------------------
    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            state(S.PAUSED, "This phone's text-to-speech isn't working - check Settings > Accessibility > Text-to-speech.")
            return
        }
        t.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        pickVoice(t)
        t.setSpeechRate(Prefs.talkRate(this))
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { ui.post { said(id) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { ui.post { said(id) } }
        })
        ttsReady = true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
    }

    /** The best voice for the phone's language that works without the internet. */
    private fun pickVoice(t: TextToSpeech) {
        try {
            val loc = Locale.getDefault()
            val v = t.voices?.filter { it.locale.language == loc.language && !it.isNetworkConnectionRequired &&
                    !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                ?.sortedWith(compareByDescending<Voice> { it.locale.country == loc.country }.thenByDescending { it.quality })
                ?.firstOrNull()
            if (v != null) t.voice = v else t.language = loc
        } catch (e: Exception) { /* the engine's default voice */ }
    }

    private fun start() {
        if (!ttsReady) { state(S.IDLE, "Getting ready…"); return }
        if (history.isEmpty()) {
            turn++
            state(S.SPEAKING, "Speaking", "")
            speak("I'm listening. What are we working on?", true)
            replyDone = true
        } else listen()
    }

    private fun speak(text: String, last: Boolean = false) {
        val t = spoken(text)
        if (t.isBlank()) { if (last && spoken >= queued) ui.postDelayed({ if (s == S.SPEAKING) listen() }, 200); return }
        duck()
        queued++
        tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "t${turn}_$queued")
    }

    private fun said(id: String?) {
        if (id == null || !id.startsWith("t${turn}_")) return          // an interrupted turn's voice
        spoken = maxOf(spoken, id.substringAfter('_').toIntOrNull() ?: 0)
        if (replyDone && spoken >= queued && s == S.SPEAKING) {
            unduck()
            ui.postDelayed({ if (s == S.SPEAKING) listen() }, 250)
        }
    }

    /** Markdown and links out, so the voice reads sentences, not symbols. */
    private fun spoken(text: String): String = text
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "a link")
        .replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*([-*•]|\\d+[.)])\\s+"), "")
        .replace(Regex("[*_`~|>]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun duck() {
        if (focus != null) return
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
        getSystemService(AudioManager::class.java).requestAudioFocus(f)
        focus = f
    }

    private fun unduck() {
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    // ---- listening ---------------------------------------------------------------------------------------
    private fun recognizer(): SpeechRecognizer {
        sr?.let { return it }
        val r = if (onDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else {
            onDevice = false
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        r.setRecognitionListener(this)
        sr = r
        return r
    }

    private fun listen(keepHeard: Boolean = false) {
        if (s == S.SUMMARY || isFinishing) return
        if (!keepHeard) {
            heard.setLength(0)
            idleSince = System.currentTimeMillis()
        }
        state(S.LISTENING, "Listening…", if (keepHeard) heard.toString() else "")
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, Prefs.talkPatience(this@TalkActivity) + 700)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, Prefs.talkPatience(this@TalkActivity))
        }
        try {
            recognizer().startListening(i)
        } catch (e: Exception) {
            sr?.destroy(); sr = null
            ui.postDelayed({ if (s == S.LISTENING) listen(keepHeard) }, 600)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {
        grace?.let { ui.removeCallbacks(it) }       // still talking: the turn isn't over
        grace = null
    }
    override fun onRmsChanged(rmsdB: Float) { orb.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onPartialResults(b: Bundle?) {
        val p = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        if (s == S.LISTENING) caption.text = (heard.toString() + " " + p).trim()
    }

    override fun onResults(b: Bundle?) {
        if (s != S.LISTENING) return
        val r = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""
        if (r.isNotEmpty()) heard.append(if (heard.isEmpty()) "" else " ").append(r)
        if (heard.isBlank()) { listen(); return }
        caption.text = heard.toString()
        // a moment more, in case this was only a pause for thought; speech in that moment continues the turn
        val g = Runnable { endTurn() }
        grace = g
        ui.postDelayed(g, 700)
        listen(keepHeard = true)
    }

    override fun onError(error: Int) {
        if (s != S.LISTENING) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (heard.isNotBlank()) endTurn()
                else if (System.currentTimeMillis() - idleSince > 5 * 60_000) pause("Paused after 5 quiet minutes - tap the orb to talk")
                else listen()
            }
            12, 13 -> {                                     // on-device language not there: the regular recognizer
                onDevice = false
                sr?.destroy(); sr = null
                listen(keepHeard = true)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> pause("Philibert needs the microphone - allow it in Settings > Apps > Philibert.")
            else -> {
                sr?.destroy(); sr = null
                ui.postDelayed({ if (s == S.LISTENING) listen(keepHeard = heard.isNotBlank()) }, 500)
            }
        }
    }

    private fun endTurn() {
        grace?.let { ui.removeCallbacks(it) }
        grace = null
        sr?.cancel()
        val text = heard.toString().trim()
        heard.setLength(0)
        if (text.isEmpty()) { listen(); return }
        if (PAUSE_CMD.matches(text)) { pause("Paused - tap the orb to talk"); return }
        bubble("user", text)
        if (WRAP_CMD.containsMatchIn(text) && history.any { it.optString("role") == "user" }) {
            wrapUp(autoSave = true)
            return
        }
        history.add(JSONObject().put("role", "user").put("content", text))
        saveHistory()
        ask()
    }

    private fun pause(why: String) {
        grace?.let { ui.removeCallbacks(it) }
        grace = null
        sr?.cancel()
        interruptReply()
        state(S.PAUSED, why)
    }

    private fun orbTapped() {
        when (s) {
            S.LISTENING -> if (heard.isNotBlank() || caption.text.isNotBlank()) {
                if (heard.isBlank()) heard.append(caption.text)
                endTurn()                                    // done talking: send it now
            }
            S.SPEAKING, S.THINKING -> { interruptReply(); listen() }   // cut in
            S.PAUSED, S.IDLE -> start()
            S.SUMMARY -> {}
        }
    }

    // ---- the reply ------------------------------------------------------------------------------------------
    private fun ask() {
        turn++
        streamed = ""; spokenUpTo = 0; queued = 0; spoken = 0; replyDone = false
        state(S.THINKING, "Thinking…", "")
        val myTurn = turn
        val msgs = JSONArray(history.takeLast(60))
        thread {
            val (code, r) = Net.post(this, "chat", JSONObject().put("messages", msgs).put("model", model).put("mode", "talk"))
            val id = r?.optString("job") ?: ""
            if (code != 200 || id.isEmpty()) {
                ui.post { if (turn == myTurn) failed(r?.optString("msg")?.takeIf { it.isNotEmpty() } ?: "I can't reach your computer.") }
                return@thread
            }
            ui.post { if (turn == myTurn) job = id }
            var from = 0
            while (true) {
                if (turn != myTurn) return@thread
                val (pc, p) = Net.post(this, "poll", JSONObject().put("job", id).put("from", from))
                if (pc != 200 || p == null) { Thread.sleep(800); continue }
                val chunk = p.optString("stream")
                val at = p.optInt("stream_at", from)
                if (chunk.isNotEmpty()) from = p.optInt("stream_len", from)
                when (p.optString("status")) {
                    "done" -> { ui.post { if (turn == myTurn) { streamIn(chunk, at); finished(p.optString("reply")) } }; return@thread }
                    "error" -> { ui.post { if (turn == myTurn) failed(p.optString("error")) }; return@thread }
                }
                if (chunk.isNotEmpty()) ui.post { if (turn == myTurn) streamIn(chunk, at) }
                Thread.sleep(220)
            }
        }
    }

    /** Model thinking (<think>...</think>) is never spoken. */
    private fun visible(t: String): String {
        var v = t.replace(Regex("(?s)<think>.*?</think>"), "")
        val open = v.indexOf("<think>")
        if (open >= 0) v = v.substring(0, open)
        return v
    }

    private fun streamIn(chunk: String, at: Int) {
        if (chunk.isEmpty()) return
        streamed = streamed.substring(0, minOf(at.coerceAtLeast(0), streamed.length)) + chunk
        val v = visible(streamed)
        if (v.isNotBlank() && s == S.THINKING) state(S.SPEAKING, "Speaking - tap to cut in")
        caption.text = spoken(v)
        // speak each finished sentence as soon as it is there
        val rest = v.substring(minOf(spokenUpTo, v.length))
        val m = Regex("(?s)^(.*[.!?…:;])[\"')\\]]*(\\s|$)|^(.*\\n)").find(rest)
        var cut = m?.let { (it.groups[1] ?: it.groups[3])!!.range.last + 1 } ?: 0
        if (cut == 0 && rest.length > 240) cut = rest.lastIndexOf(", ").takeIf { it > 80 }?.plus(1) ?: rest.lastIndexOf(' ').coerceAtLeast(1)
        if (cut > 0) {
            speak(rest.substring(0, cut))
            spokenUpTo += cut
        }
    }

    private fun finished(reply: String) {
        job = null
        val v = visible(streamed).ifBlank { reply }
        if (spokenUpTo < v.length) speak(v.substring(spokenUpTo))
        spokenUpTo = v.length
        replyDone = true
        val text = reply.ifBlank { v }.trim()
        if (text.isNotEmpty()) {
            history.add(JSONObject().put("role", "assistant").put("content", text))
            saveHistory()
            bubble("assistant", text)
        }
        if (s == S.THINKING) state(S.SPEAKING, "Speaking - tap to cut in", spoken(text))
        if (spoken >= queued) ui.postDelayed({ if (s == S.SPEAKING) { unduck(); listen() } }, 250)
    }

    private fun failed(why: String) {
        job = null
        replyDone = true
        state(S.PAUSED, "Couldn't get an answer - tap the orb to try again", why.take(200))
        // the question stays in the history: tapping the orb and talking carries on from it
    }

    /** Stop speaking / stop the answer; what was already said stays in the conversation. */
    private fun interruptReply() {
        val wasAnswering = s == S.SPEAKING || s == S.THINKING
        turn++
        tts?.stop()
        unduck()
        job?.let { j -> thread { Net.post(this, "cancel", JSONObject().put("job", j)) } }
        job = null
        if (wasAnswering && !replyDone) {
            val said = spoken(visible(streamed)).trim()
            if (said.isNotEmpty()) {
                history.add(JSONObject().put("role", "assistant").put("content", "$said …(interrupted)"))
                bubble("assistant", "$said …")
                saveHistory()
            }
        }
        replyDone = true
    }

    // ---- new talk / wrap up ------------------------------------------------------------------------------------
    private fun newTalk() {
        AlertDialog.Builder(this)
            .setTitle("Start a new talk?")
            .setMessage("This conversation is cleared from the phone. Wrap up & save first to keep its plan.")
            .setPositiveButton("New talk") { _, _ ->
                interruptReply()
                history.clear(); saveHistory(); log.removeAllViews()
                caption.text = ""
                start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun transcript(): String = history.joinToString("\n\n") {
        (if (it.optString("role") == "user") "Me: " else "Philibert: ") + it.optString("content")
    }

    private fun wrapUp(autoSave: Boolean) {
        if (history.none { it.optString("role") == "user" }) {
            Toast.makeText(this, "Nothing to sum up yet - talk first.", Toast.LENGTH_SHORT).show()
            return
        }
        grace?.let { ui.removeCallbacks(it) }
        sr?.cancel()
        interruptReply()
        state(S.SUMMARY, "Writing up the plan…", "")
        val talk = transcript()
        thread {
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "The conversation:\n\n$talk"))
            val (code, r) = Net.post(this, "chat", JSONObject().put("messages", msgs).put("model", model).put("mode", "summary"))
            val id = r?.optString("job") ?: ""
            var md = ""
            var err = if (code != 200 || id.isEmpty()) (r?.optString("msg") ?: "I can't reach your computer.") else ""
            while (err.isEmpty() && md.isEmpty()) {
                Thread.sleep(700)
                val (pc, p) = Net.post(this, "poll", JSONObject().put("job", id).put("from", 0))
                if (pc != 200 || p == null) continue
                when (p.optString("status")) {
                    "done" -> md = p.optString("reply").ifBlank { "(no summary came back)" }
                    "error" -> err = p.optString("error")
                }
            }
            ui.post {
                if (err.isNotEmpty()) state(S.PAUSED, "Couldn't write it up - tap the orb to keep talking", err.take(200))
                else showSummary(visible(md).trim(), talk, autoSave)
            }
        }
    }

    private fun titleOf(md: String): String =
        md.lineSequence().firstOrNull { it.trim().startsWith("# ") }?.trim()?.removePrefix("# ")?.trim()?.take(100)
            ?: ("Brainstorm " + SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date()))

    private fun showSummary(md: String, talk: String, autoSave: Boolean) {
        summaryView?.let { body.removeView(it) }
        val pad = (16 * dp).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val status = TextView(this).apply { setTextColor(getColor(R.color.cyan)); textSize = 13f }
        col.addView(status)
        col.addView(TextView(this).apply {
            text = md; setTextColor(getColor(R.color.txt)); textSize = 15f; setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad)
        })
        val note = md + "\n\n---\n\n## Transcript\n\n" + talk.replace("\n\n", "\n\n") + "\n"
        val title = titleOf(md)
        fun save(then: (() -> Unit)? = null) {
            status.text = "Saving to your notes…"
            thread {
                val (code, r) = Net.post(this, "save_note", JSONObject().put("title", title).put("body", note))
                ui.post {
                    if (code == 200 && r?.optBoolean("ok") == true) {
                        status.text = "Saved to your Philibert notes: Brainstorms / ${r.optString("title")}"
                        then?.invoke()
                    } else status.text = "Not saved: " + (r?.optString("msg") ?: "can't reach your computer")
                }
            }
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(Button(this).apply { text = "Save to notes"; isAllCaps = false; setOnClickListener { save() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(Button(this).apply {
            text = "Share"; isAllCaps = false
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, note), "Share the plan"))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(Button(this).apply {
            text = "Read it to me"; isAllCaps = false
            setOnClickListener { speakSummary(md, "", andListen = false) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(Button(this).apply {
            text = "Keep talking"; isAllCaps = false
            setOnClickListener { closeSummary(); listen() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row2)
        val sv = ScrollView(this).apply { setBackgroundColor(getColor(R.color.bg)); addView(col) }
        summaryView = sv
        body.addView(sv)
        state(S.SUMMARY, "The plan", "")
        if (autoSave) save {
            // hands-free (said "wrap it up"): the plan goes into the conversation, read out, then back to listening
            closeSummary()
            bubble("assistant", md)
            speakSummary(md, "Saved to your notes, under Brainstorms. Here's the gist. ", andListen = true)
        }
    }

    /** Reads the plan aloud. andListen (said "wrap it up"): its Summary, "anything else?", then back to
     *  listening. The button: the whole plan, and it stays on the plan. */
    private fun speakSummary(md: String, lead: String, andListen: Boolean) {
        tts?.stop()
        turn++; queued = 0; spoken = 0; replyDone = false
        val sec = Regex("(?s)##\\s*Summary\\s*\\n(.*?)(\\n##|$)").find(md)?.groupValues?.get(1)?.trim()
        val gist = if (andListen) (sec ?: md.lineSequence().filter { !it.trim().startsWith("#") }.joinToString(" ").take(600))
                   else md
        // SPEAKING goes back to listening when the voice is done (said()); SUMMARY just stops
        state(if (andListen) S.SPEAKING else S.SUMMARY, if (andListen) "Speaking - tap to cut in" else "Reading the plan")
        speak(lead + gist)
        if (andListen) speak("Anything else, or are we done?")
        replyDone = true
    }

    private fun closeSummary() {
        summaryView?.let { body.removeView(it) }
        summaryView = null
        if (s == S.SUMMARY) s = S.IDLE
    }
}
