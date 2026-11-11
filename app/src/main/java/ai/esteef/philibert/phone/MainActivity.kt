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
import android.webkit.WebChromeClient
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
 * The Philibert chat (the computer's own phone page, in a WebView) with a status line on top, and
 * a Setup panel: the address to pair with, and each permission Philibert needs to act on the phone.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var body: FrameLayout
    private var setup: View? = null
    private val ui = Handler(Looper.getMainLooper())
    private val dp get() = resources.displayMetrics.density

    private val PERMS = arrayOf(
        Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifs.setup(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
            setBackgroundColor(getColor(R.color.panel))
        }
        status = TextView(this).apply {
            setTextColor(getColor(R.color.dim))
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(status)
        bar.addView(Button(this).apply {
            text = "Setup"
            isAllCaps = false
            setOnClickListener { showSetup() }
        })
        root.addView(bar)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(getColor(R.color.bg))
        }
        body.addView(web)
        setContentView(root)

        if (Prefs.address(this).isEmpty()) showSetup() else {
            web.loadUrl(Prefs.address(this))
            HandsService.start(this)
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        tick()
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

    // ---- the status line ------------------------------------------------------------------------------
    private fun tick() {
        val s = when {
            Prefs.address(this).isEmpty() -> "Not paired with your computer yet"
            !Prefs.handsOn(this) -> "● Chat only - acting on the phone is switched off"
            else -> when (HandsService.state) {
                "connected" -> "● Connected" + if (handsOn()) " · Philibert can act on this phone" else " · turn on Philibert hands in Setup"
                "connecting" -> "○ Connecting to your computer…"
                "unreachable" -> "○ Can't reach your computer - Philibert running? Tailscale on?"
                else -> "○ Not connected"
            }
        }
        status.text = s
        status.setTextColor(getColor(if (s.startsWith("● Connected") && handsOn()) R.color.green else R.color.dim))
        ui.postDelayed({ tick() }, 1500)
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
            setPadding(pad, pad, pad, pad * 2)
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

        col.addView(TextView(this).apply {
            text = "Philibert"; setTextColor(getColor(R.color.cyan)); textSize = 26f; typeface = Typeface.DEFAULT_BOLD
        })
        note("Your assistant runs on your own computer. This app is its chat on the phone - and, when you allow it, its hands on the phone.")

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
        row("Texts, calls, contacts, location", permsOn(),
            "So \"text Mom I'm late\" or \"where am I\" work. Texts and calls still ask you first, every time.",
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

        title("3. On or off")
        col.addView(Switch(this).apply {
            text = "Philibert may act on this phone"
            setTextColor(getColor(R.color.txt))
            isChecked = Prefs.handsOn(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setHandsOn(this@MainActivity, on)
                if (on) HandsService.start(this@MainActivity) else HandsService.stop(this@MainActivity)
            }
        })
        note("Off: the chat still works, but Philibert can't do anything on the phone.")

        if (Prefs.address(this).isNotEmpty()) col.addView(Button(this).apply {
            text = "Back to the chat"
            setOnClickListener { hideSetup() }
        })

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
