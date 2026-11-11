package ai.esteef.philibert.phone

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The connection to Philibert: pairs this phone with the computer (the device fabric's register),
 * then waits on a long poll for the actions the chat asks for, carries each one out (Actions) and
 * hands back what happened. Outgoing only - nothing listens on the phone.
 */
class HandsService : Service() {
    companion object {
        @Volatile var state = "off"            // off | connecting | connected | unreachable
        @Volatile var running = false

        fun start(c: Context) {
            if (Prefs.address(c).isEmpty() || !Prefs.handsOn(c)) return
            try {
                c.startForegroundService(Intent(c, HandsService::class.java))
            } catch (e: Exception) {
                // Android refuses a background start now and then; the app's next open tries again
            }
        }

        fun stop(c: Context) {
            running = false
            c.stopService(Intent(c, HandsService::class.java))
        }
    }

    @Volatile private var loop: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifs.setup(this)
        val n = Notifs.status(this, "Connecting to your computer…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (loop?.isAlive != true) {
            running = true
            loop = thread(name = "philibert-hands") { run() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        state = "off"
        super.onDestroy()
    }

    private fun say(s: String, text: String) {
        if (state == s) return
        state = s
        getSystemService(NotificationManager::class.java).notify(1, Notifs.status(this, text))
    }

    private fun register(): Boolean {
        val body = JSONObject()
            .put("device_id", Prefs.deviceId(this))
            .put("name", Build.MODEL ?: "Android phone")
            .put("platform", "android")
            .put("capabilities", Actions.capabilities())
        val (code, r) = Net.post(this, "device_register", body)
        return code == 200 && r?.optBoolean("ok") == true
    }

    private fun run() {
        var registered = false
        var backoff = 2000L
        while (running) {
            try {
                if (!registered) {
                    say("connecting", "Connecting to your computer…")
                    registered = register()
                    if (!registered) {
                        say("unreachable", "Can't reach your computer - is Philibert running and Tailscale on?")
                        Thread.sleep(backoff)
                        backoff = (backoff * 2).coerceAtMost(60000L)
                        continue
                    }
                    backoff = 2000L
                }
                say("connected", "Connected - Philibert can act on this phone when you ask")
                val (code, r) = Net.post(this, "device_poll",
                    JSONObject().put("device_id", Prefs.deviceId(this)).put("wait", 25), readTimeoutMs = 40000)
                when {
                    code == 404 -> registered = false          // Philibert restarted: pair again
                    code != 200 || r == null -> {
                        registered = false
                        say("unreachable", "Can't reach your computer - is Philibert running and Tailscale on?")
                        Thread.sleep(backoff)
                        backoff = (backoff * 2).coerceAtMost(60000L)
                    }
                    else -> r.optJSONObject("call")?.let { call ->
                        // each action on its own thread: one waiting on "ask me first" doesn't stop the line
                        thread(name = "philibert-action") {
                            val result = try {
                                Actions.run(this, call.optString("tool"), call.optJSONObject("args") ?: JSONObject())
                            } catch (e: Exception) {
                                "That didn't work on the phone: ${e.javaClass.simpleName}: ${e.message}"
                            }
                            Net.post(this, "device_result", JSONObject()
                                .put("device_id", Prefs.deviceId(this))
                                .put("call_id", call.optString("call_id"))
                                .put("result", result))
                        }
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                registered = false
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            }
        }
        state = "off"
    }
}
