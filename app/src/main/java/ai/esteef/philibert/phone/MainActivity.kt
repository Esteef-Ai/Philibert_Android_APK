package ai.esteef.philibert.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The Philibert chat: the computer's own phone page in a WebView, whose header (Esteef AI ·
 * Philibert) has the app's Talk and Setup buttons (window.PhilibertApp). Setup: the address to
 * pair with, each permission Philibert needs to act on the phone, and Talk's voice settings.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var body: FrameLayout
    private var setup: View? = null
    private val ui = Handler(Looper.getMainLooper())
    private val dp get() = resources.displayMetrics.density

    private val PERMS = arrayOf(
        Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)

    /** What the chat page can ask of the app. */
    inner class Bridge {
        @JavascriptInterface
        fun talk(model: String) {
            ui.post {
                startActivity(Intent(this@MainActivity, TalkActivity::class.java).putExtra("model", model))
            }
        }

        @JavascriptInterface
        fun setup() { ui.post { showSetup() } }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifs.setup(this)
        body = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            fitsSystemWindows = true
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                // the chat stays in the app; any other link opens in the browser
                override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                    val u = req.url.toString()
                    if (u.startsWith(Prefs.address(this@MainActivity))) return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (e: Exception) { }
                    return true
                }
            }
            setBackgroundColor(getColor(R.color.bg))
            addJavascriptInterface(Bridge(), "PhilibertApp")
        }
        body.addView(web)
        setContentView(body)

        if (Prefs.address(this).isEmpty()) showSetup() else {
            web.loadUrl(Prefs.address(this))
            HandsService.start(this)
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        HandsService.start(this)
        if (setup != null) showSetup()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            setup != null && Prefs.address(this).isNotEmpty() -> hideSetup()
            setup == null && web.canGoBack() -> web.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---- what is switched on ----------------------------------------------------------------------------
    private fun handsOn(): Boolean {
        if (PhoneAccessibility.me != null) return true
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return on.contains(ComponentName(this, PhoneAccessibility::class.java).flattenToString())
    }

    private fun notificationsOn(): Boolean =
        (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)

    private fun permsOn(): Boolean = PERMS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun batteryOn(): Boolean = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun connection(): String = when {
        Prefs.address(this).isEmpty() -> "Not paired with your computer yet"
        !Prefs.handsOn(this) -> "Chat only - acting on the phone is switched off"
        else -> when (HandsService.state) {
            "connected" -> "Connected to your computer" + if (handsOn()) " - Philibert can act on this phone" else ""
            "connecting" -> "Connecting to your computer…"
            "unreachable" -> "Can't reach your computer - is Philibert running, and Tailscale on?"
            else -> "Not connected"
        }
    }

    // ---- setup ------------------------------------------------------------------------------------------
    private fun hideSetup() {
        setup?.let { body.removeView(it) }
        setup = null
    }

    private fun showSetup() {
        hideSetup()
        val pad = (18 * dp).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad * 2)
        }
        fun title(t: String) = col.addView(TextView(this).apply {
            text = t; setTextColor(getColor(R.color.txt)); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, pad, 0, (6 * dp).toInt())
        })
        fun note(t: String) = col.addView(TextView(this).apply {
            text = t; setTextColor(getColor(R.color.dim)); textSize = 13.5f
            setPadding(0, 0, 0, (8 * dp).toInt())
        })
        fun row(name: String, on: Boolean, what: String, button: String, go: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            }
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            t.addView(TextView(this).apply {
                text = (if (on) "✓  " else "•  ") + name
                setTextColor(getColor(if (on) R.color.green else R.color.txt)); textSize = 15f
            })
            t.addView(TextView(this).apply { text = what; setTextColor(getColor(R.color.dim)); textSize = 12.5f })
            r.addView(t)
            if (!on) r.addView(Button(this).apply { text = button; isAllCaps = false; setOnClickListener { go() } })
            col.addView(r)
        }
        /** A row of choices; the chosen one is marked. */
        fun choices(label: String, opts: List<Pair<String, Any>>, current: Any, pick: (Any) -> Unit) {
            col.addView(TextView(this).apply {
                text = label; setTextColor(getColor(R.color.txt)); textSize = 15f
                setPadding(0, (8 * dp).toInt(), 0, (2 * dp).toInt())
            })
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            opts.forEach { (name, v) ->
                r.addView(Button(this).apply {
                    text = if (v == current) "✓ $name" else name
                    isAllCaps = false
                    setOnClickListener { pick(v); showSetup() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            col.addView(r)
        }

        // the panel's own top bar: the brand, and a close button once paired
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        brand.addView(TextView(this).apply {
            text = "ESTEEF AI"; setTextColor(getColor(R.color.cyan)); textSize = 10f
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.18f
        })
        brand.addView(TextView(this).apply {
            text = "Philibert · Setup"; setTextColor(getColor(R.color.txt)); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        })
        top.addView(brand)
        if (Prefs.address(this).isNotEmpty()) top.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "✕"; textSize = 20f; setTextColor(getColor(R.color.dim)); setOnClickListener { hideSetup() }
        })
        col.addView(top)
        note(connection())

        title("1. Pair with your computer")
        note("On the computer, Philibert shows a phone address where you turned the phone chat on (it starts with http://100. and ends in /phone/). Copy it here. Tailscale has to be on, on both.")
        val addr = EditText(this).apply {
            setText(Prefs.address(this@MainActivity))
            hint = "http://100.x.y.z:11500/k-…/phone/"
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(getColor(R.color.txt)); setHintTextColor(getColor(R.color.dim))
        }
        col.addView(addr)
        val result = TextView(this).apply { setTextColor(getColor(R.color.amber)); textSize = 13f }
        col.addView(Button(this).apply {
            text = "Connect"
            setOnClickListener {
                val a = Prefs.normalize(addr.text.toString())
                if (a.isEmpty()) { result.text = "Paste the phone address first."; return@setOnClickListener }
                result.text = "Trying…"
                thread {
                    val old = Prefs.address(this@MainActivity)
                    Prefs.setAddress(this@MainActivity, a)
                    val (code, r) = Net.post(this@MainActivity, "hello", JSONObject())
                    ui.post {
                        if (code == 200 && r?.optBoolean("ok") == true) {
                            result.text = "Connected to ${r.optString("computer")}."
                            HandsService.stop(this@MainActivity)
                            HandsService.start(this@MainActivity)
                            web.loadUrl(a)
                            ui.postDelayed({ hideSetup() }, 900)
                        } else {
                            Prefs.setAddress(this@MainActivity, old)
                            result.text = if (code == -1) "Nothing answered there. Is Tailscale on (on both), Philibert running, and the phone chat turned on?"
                            else "That address answered, but not as Philibert's phone chat (HTTP $code). Copy the whole address again."
                        }
                    }
                }
            }
        })
        col.addView(result)

        title("2. Let Philibert act on this phone")
        row("Philibert hands", handsOn(),
            "Accessibility: read the screen, tap, type and scroll when you ask. If Android says it's a restricted setting: " +
                "Settings > Apps > Philibert > ⋮ (top right) > Allow restricted settings, then come back and try again.",
            "Turn on") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        row("Microphone, texts, calls, contacts, location", permsOn(),
            "The microphone is for Talk. Texts and calls still ask you first, every time.",
            "Allow") { requestPermissions(PERMS, 2) }
        row("Read notifications", notificationsOn(),
            "So you can ask \"what did I miss?\".",
            "Turn on") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        row("Stay connected", batteryOn(),
            "Lets the app keep its line to the computer open while the screen is off.",
            "Allow") {
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(i)
        }
        col.addView(Switch(this).apply {
            text = "Philibert may act on this phone"
            setTextColor(getColor(R.color.txt))
            isChecked = Prefs.handsOn(this@MainActivity)
            setPadding(0, (10 * dp).toInt(), 0, 0)
            setOnCheckedChangeListener { _, on ->
                Prefs.setHandsOn(this@MainActivity, on)
                if (on) HandsService.start(this@MainActivity) else HandsService.stop(this@MainActivity)
            }
        })
        note("Off: the chat still works, but Philibert can't do anything on the phone.")

        title("3. Talk")
        note("The waveform button in the chat's top bar starts a spoken conversation with the model chosen there - for brainstorming, in the car too. Tap the orb to cut in, say \"pause\" to stop listening, and say \"wrap it up\" to have the session written up as a plan and saved to your Philibert notes (Brainstorms).")
        choices("Voice speed", listOf("Slower" to 0.9f, "Normal" to 1.0f, "Brisk" to 1.1f, "Fast" to 1.3f),
            Prefs.talkRate(this)) { Prefs.setTalkRate(this, it as Float) }
        choices("Wait after I stop talking", listOf("Short" to 800L, "Normal" to 1300L, "Long" to 2200L, "Longer" to 3500L),
            Prefs.talkPatience(this)) { Prefs.setTalkPatience(this, it as Long) }
        note("Longer lets you pause to think mid-idea without Philibert jumping in.")

        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            addView(col)
        }
        setup = scroll
        body.addView(scroll)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (setup != null) showSetup()
    }
}
