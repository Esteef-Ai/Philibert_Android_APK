package ai.esteef.philibert.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Everything Philibert can do on this phone. Each one is reported to Philibert with its exact
 * parameters (the device fabric's contract), so the model uses real names instead of guessing.
 * Sending, calling and risky taps ask the person first (Confirm).
 */
object Actions {
    private class Cap(val name: String, val params: List<String>, val required: List<String>, val description: String)

    private val CAPS = listOf(
        Cap("set_alarm", listOf("hour", "minute", "label"), listOf("hour", "minute"),
            "Set an alarm in the Clock app. hour 0-23, minute 0-59 (6:30 pm = hour 18, minute 30)."),
        Cap("set_timer", listOf("minutes", "seconds", "label"), listOf(),
            "Start a countdown timer: give minutes and/or seconds."),
        Cap("open_app", listOf("name"), listOf("name"), "Open an app by its name, e.g. Spotify, Plex, Maps, Camera."),
        Cap("list_apps", listOf(), listOf(), "The names of the apps on this phone."),
        Cap("open_url", listOf("url"), listOf("url"),
            "Open a link, or a map: https://... , geo:0,0?q=coffee near me , google.navigation:q=ADDRESS (turn-by-turn directions), tel:NUMBER (dialer, not calling)."),
        Cap("send_text", listOf("to", "message"), listOf("to", "message"),
            "Send an SMS text. to = a contact's name or a phone number. The person confirms on the phone first."),
        Cap("call", listOf("to"), listOf("to"), "Phone someone: a contact's name or a number. The person confirms first."),
        Cap("find_contact", listOf("name"), listOf("name"), "Look up a contact's phone numbers."),
        Cap("flashlight", listOf("on"), listOf("on"), "Torch on (true) or off (false)."),
        Cap("volume", listOf("level", "change", "stream"), listOf(),
            "Set volume: level 0-100, or change = up | down | mute | unmute. stream = media (default) | ring | alarm | notification."),
        Cap("media", listOf("command"), listOf("command"),
            "Control whatever is playing: play, pause, play_pause, next, previous, stop."),
        Cap("battery", listOf(), listOf(), "Battery level and whether it is charging."),
        Cap("location", listOf(), listOf(), "Where the phone is (last known position, with the street address when it can be found)."),
        Cap("read_notifications", listOf(), listOf(), "The notifications on the phone now: app, title, text."),
        Cap("notify", listOf("title", "message"), listOf("message"), "Show a notification on the phone."),
        Cap("read_screen", listOf(), listOf(),
            "What is on the screen now, as a numbered list of items (buttons, text, boxes). Use before tap / type_text / scroll."),
        Cap("tap", listOf("id", "x", "y"), listOf(), "Tap item [id] from read_screen (or the point x,y)."),
        Cap("long_press", listOf("id"), listOf("id"), "Press and hold item [id] from read_screen."),
        Cap("type_text", listOf("text", "id", "enter"), listOf("text"),
            "Type into text box [id] (or the box with the cursor). enter = true presses Enter/Search/Send on the keyboard after."),
        Cap("scroll", listOf("direction", "id"), listOf("direction"), "Scroll up, down, left or right (item [id] = the list to scroll)."),
        Cap("swipe", listOf("x1", "y1", "x2", "y2", "ms"), listOf("x1", "y1", "x2", "y2"), "Swipe from x1,y1 to x2,y2 (screen pixels)."),
        Cap("press", listOf("key"), listOf("key"), "Press back, home, recents, notifications, quick_settings, lock_screen or screenshot."),
        Cap("wait", listOf("seconds"), listOf("seconds"), "Wait for something to load (1-10 s), then read_screen."),
    )

    fun capabilities(): JSONArray = JSONArray().apply {
        CAPS.forEach { c ->
            put(JSONObject()
                .put("name", c.name)
                .put("params", JSONArray(c.params))
                .put("required", JSONArray(c.required))
                .put("description", c.description))
        }
    }

    // ---- argument helpers (models send 6, "6", 6.0 or "true") ---------------------------------------
    private fun JSONObject.int(k: String): Int? {
        if (!has(k) || isNull(k)) return null
        val v = opt(k)
        return when (v) {
            is Number -> v.toInt()
            is String -> v.trim().toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    private fun JSONObject.bool(k: String): Boolean? {
        if (!has(k) || isNull(k)) return null
        return when (val v = opt(k)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.trim().lowercase() in setOf("true", "on", "yes", "1")
            else -> null
        }
    }

    private fun JSONObject.str(k: String): String = if (has(k) && !isNull(k)) optString(k).trim() else ""

    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hands(): PhoneAccessibility? = PhoneAccessibility.me
    private const val NO_HANDS = "Philibert can't see or touch the screen yet: on the phone, open the Philibert app > Setup and turn on \"Philibert hands\" (Accessibility)."

    /** Start an activity: from the accessibility service when it's on (Android lets it open apps from the background). */
    private fun start(c: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (PhoneAccessibility.me ?: c).startActivity(i)
    }

    fun run(c: Context, tool: String, a: JSONObject): String = when (tool) {
        "set_alarm" -> {
            val h = a.int("hour")
            val m = a.int("minute") ?: 0
            if (h == null || h !in 0..23 || m !in 0..59) "Give hour 0-23 and minute 0-59."
            else {
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h)
                    .putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                a.str("label").takeIf { it.isNotEmpty() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                start(c, i)
                "Alarm set for %d:%02d%s.".format(h, m, a.str("label").let { if (it.isEmpty()) "" else " ($it)" })
            }
        }
        "set_timer" -> {
            val secs = (a.int("minutes") ?: 0) * 60 + (a.int("seconds") ?: 0)
            if (secs <= 0) "Give minutes and/or seconds."
            else {
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                a.str("label").takeIf { it.isNotEmpty() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                start(c, i)
                "Timer started: ${secs / 60} min ${secs % 60} s."
            }
        }
        "open_app" -> openApp(c, a.str("name"))
        "list_apps" -> launchable(c).map { it.first }.sorted().joinToString(", ")
        "open_url" -> {
            val u = a.str("url")
            if (u.isEmpty()) "Give a url." else {
                start(c, Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                Thread.sleep(1500)
                "Opened $u."
            }
        }
        "find_contact" -> contacts(c, a.str("name")).let { list ->
            if (list == null) "Philibert isn't allowed to read contacts - in the app, Setup > Allow phone permissions."
            else if (list.isEmpty()) "No contact matches \"${a.str("name")}\"."
            else list.joinToString("\n") { "${it.first}: ${it.second}" }
        }
        "send_text" -> sendText(c, a.str("to"), a.str("message"))
        "call" -> call(c, a.str("to"))
        "flashlight" -> {
            val on = a.bool("on") ?: true
            val cm = c.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) "This phone has no flashlight." else {
                cm.setTorchMode(id, on)
                "Flashlight " + (if (on) "on." else "off.")
            }
        }
        "volume" -> volume(c, a)
        "media" -> {
            val code = when (a.str("command").lowercase()) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "play_pause", "toggle", "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> -1
            }
            if (code < 0) "command is play, pause, play_pause, next, previous or stop." else {
                val am = c.getSystemService(AudioManager::class.java)
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                "Sent ${a.str("command")} to the player."
            }
        }
        "battery" -> {
            val bm = c.getSystemService(BatteryManager::class.java)
            "Battery ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" +
                (if (bm.isCharging) ", charging." else ", not charging.")
        }
        "location" -> location(c)
        "read_notifications" -> {
            val l = NotifListener.me
            if (l == null) "Philibert can't read notifications yet: in the app, Setup > turn on notification access."
            else {
                val list = l.activeNotifications.filter { !it.isOngoing }.take(30).map { sbn ->
                    val e = sbn.notification.extras
                    val app = try {
                        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
                    } catch (x: Exception) { sbn.packageName }
                    val t = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val b = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
                    "$app: $t - ${b.take(200)}"
                }
                if (list.isEmpty()) "No notifications." else list.joinToString("\n")
            }
        }
        "notify" -> {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.notify((System.currentTimeMillis() % 100000).toInt() + 1000, Notification.Builder(c, Notifs.CHANNEL_SAY)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(a.str("title").ifEmpty { "Philibert" })
                .setContentText(a.str("message"))
                .setStyle(Notification.BigTextStyle().bigText(a.str("message")))
                .setAutoCancel(true)
                .build())
            "Shown."
        }
        "read_screen" -> hands()?.readScreen() ?: NO_HANDS
        "tap" -> hands()?.tap(a.int("id"), a.int("x"), a.int("y")) ?: NO_HANDS
        "long_press" -> a.int("id")?.let { hands()?.longPress(it) ?: NO_HANDS } ?: "Give the item's id from read_screen."
        "type_text" -> hands()?.type(a.str("text"), a.int("id"), a.bool("enter") ?: false) ?: NO_HANDS
        "scroll" -> hands()?.scroll(a.str("direction").ifEmpty { "down" }, a.int("id")) ?: NO_HANDS
        "swipe" -> {
            val x1 = a.int("x1"); val y1 = a.int("y1"); val x2 = a.int("x2"); val y2 = a.int("y2")
            if (x1 == null || y1 == null || x2 == null || y2 == null) "Give x1, y1, x2, y2."
            else hands()?.swipe(x1, y1, x2, y2, (a.int("ms") ?: 300).toLong()) ?: NO_HANDS
        }
        "press" -> hands()?.press(a.str("key")) ?: NO_HANDS
        "wait" -> {
            val s = (a.int("seconds") ?: 2).coerceIn(1, 10)
            Thread.sleep(s * 1000L)
            "Waited $s s."
        }
        else -> "This phone can't do \"$tool\". It can: " + CAPS.joinToString(", ") { it.name } + "."
    }

    // ---- apps -----------------------------------------------------------------------------------------
    private fun launchable(c: Context): List<Pair<String, String>> {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
    }

    private fun openApp(c: Context, name: String): String {
        if (name.isEmpty()) return "Give the app's name."
        val q = name.lowercase(Locale.ROOT).removeSuffix(" app").trim()
        val apps = launchable(c)
        val hit = apps.firstOrNull { it.first.lowercase(Locale.ROOT) == q }
            ?: apps.firstOrNull { it.second.lowercase(Locale.ROOT) == q }
            ?: apps.firstOrNull { it.first.lowercase(Locale.ROOT).startsWith(q) }
            ?: apps.firstOrNull { it.first.lowercase(Locale.ROOT).contains(q) }
            ?: apps.firstOrNull { it.second.lowercase(Locale.ROOT).contains(q.replace(" ", "")) }
            ?: return "No app called \"$name\" on this phone. Some of its apps: " +
                apps.map { it.first }.sorted().take(60).joinToString(", ")
        val launch = c.packageManager.getLaunchIntentForPackage(hit.second) ?: return "${hit.first} can't be opened."
        start(c, launch)
        Thread.sleep(1500)
        return "Opened ${hit.first}."
    }

    // ---- people ---------------------------------------------------------------------------------------
    /** name -> [(display name, number)], or null without the contacts permission. */
    private fun contacts(c: Context, name: String): List<Pair<String, String>>? {
        if (!granted(c, Manifest.permission.READ_CONTACTS)) return null
        if (name.isEmpty()) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
        val out = mutableListOf<Pair<String, String>>()
        c.contentResolver.query(uri, arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cur ->
            while (cur.moveToNext() && out.size < 10) out.add(cur.getString(0) to cur.getString(1))
        }
        return out.distinctBy { it.second.filter { ch -> ch.isDigit() } }
    }

    /** "Mom" or "+1 555..." -> (who, number), or an error to say. */
    private fun resolve(c: Context, to: String): Pair<Pair<String, String>?, String> {
        if (to.isEmpty()) return null to "Say who to."
        if (to.count { it.isDigit() } >= 3 && to.all { it.isDigit() || it in "+-() ." }) return (to to to) to ""
        val list = contacts(c, to) ?: return null to "Philibert isn't allowed to read contacts - in the app, Setup > Allow phone permissions. Or give the number."
        if (list.isEmpty()) return null to "No contact matches \"$to\"."
        val names = list.map { it.first }.distinct()
        if (names.size > 1 && list.none { it.first.equals(to, ignoreCase = true) })
            return null to "More than one contact matches \"$to\": " + list.joinToString("; ") { "${it.first} ${it.second}" } + ". Ask which one."
        val pick = list.firstOrNull { it.first.equals(to, ignoreCase = true) } ?: list[0]
        return pick to ""
    }

    private fun sendText(c: Context, to: String, message: String): String {
        if (message.isEmpty()) return "Say what the text should say."
        val (who, err) = resolve(c, to)
        if (who == null) return err
        val label = if (who.first == who.second) who.second else "${who.first} (${who.second})"
        if (!Confirm.ask(c, "Send a text to ${who.first}?", "To: $label\n\n$message"))
            return "The person declined on the phone. The text was NOT sent."
        if (!granted(c, Manifest.permission.SEND_SMS)) {
            start(c, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(who.second))).putExtra("sms_body", message))
            return "Philibert isn't allowed to send texts by itself, so the text is written in the Messages app - the person still has to tap Send."
        }
        val sms = c.getSystemService(SmsManager::class.java)
        sms.sendMultipartTextMessage(who.second, null, sms.divideMessage(message), null, null)
        return "Text sent to $label."
    }

    private fun call(c: Context, to: String): String {
        val (who, err) = resolve(c, to)
        if (who == null) return err
        val label = if (who.first == who.second) who.second else "${who.first} (${who.second})"
        if (!Confirm.ask(c, "Call ${who.first}?", "Philibert wants to call $label."))
            return "The person declined on the phone. No call was made."
        val uri = Uri.parse("tel:" + Uri.encode(who.second))
        return if (granted(c, Manifest.permission.CALL_PHONE)) {
            start(c, Intent(Intent.ACTION_CALL, uri)); "Calling $label."
        } else {
            start(c, Intent(Intent.ACTION_DIAL, uri)); "The number is in the dialer - the person taps Call (Philibert isn't allowed to call by itself)."
        }
    }

    // ---- the phone itself -------------------------------------------------------------------------------
    private fun volume(c: Context, a: JSONObject): String {
        val am = c.getSystemService(AudioManager::class.java)
        val stream = when (a.str("stream").lowercase()) {
            "ring", "ringer", "ringtone" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification", "notifications" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(stream)
        val lvl = a.int("level")
        when {
            lvl != null -> am.setStreamVolume(stream, (lvl.coerceIn(0, 100) * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
            else -> am.adjustStreamVolume(stream, when (a.str("change").lowercase()) {
                "up", "louder" -> AudioManager.ADJUST_RAISE
                "down", "quieter", "lower" -> AudioManager.ADJUST_LOWER
                "mute" -> AudioManager.ADJUST_MUTE
                "unmute" -> AudioManager.ADJUST_UNMUTE
                else -> return "Give level 0-100, or change = up, down, mute or unmute."
            }, AudioManager.FLAG_SHOW_UI)
        }
        return "Volume now ${am.getStreamVolume(stream) * 100 / max.coerceAtLeast(1)}%."
    }

    @Suppress("DEPRECATION")
    private fun location(c: Context): String {
        if (!granted(c, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(c, Manifest.permission.ACCESS_COARSE_LOCATION))
            return "Philibert isn't allowed to see the location - in the app, Setup > Allow phone permissions."
        val lm = c.getSystemService(LocationManager::class.java)
        val best = lm.getProviders(true).mapNotNull { p ->
            try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
        }.maxByOrNull { it.time } ?: return "The phone doesn't know where it is right now (location may be off)."
        val ageMin = (System.currentTimeMillis() - best.time) / 60000
        val addr = try {
            Geocoder(c, Locale.getDefault()).getFromLocation(best.latitude, best.longitude, 1)
                ?.firstOrNull()?.getAddressLine(0)
        } catch (e: Exception) { null }
        return "%.5f, %.5f (within %d m, %d min ago)%s".format(best.latitude, best.longitude, best.accuracy.toInt(), ageMin,
            if (addr != null) " - $addr" else "")
    }
}
