package ai.esteef.philibert.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** What the app remembers: Philibert's phone address, this phone's device id, hands on/off. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("philibert", Context.MODE_PRIVATE)

    /** The phone page's address, always ending in /phone/ (e.g. http://100.x.y.z:11500/k-.../phone/). */
    fun address(c: Context): String = sp(c).getString("address", "") ?: ""
    fun setAddress(c: Context, a: String) = sp(c).edit().putString("address", a).apply()

    fun deviceId(c: Context): String {
        var id = sp(c).getString("device_id", "") ?: ""
        if (id.isEmpty()) {
            id = "android-" + UUID.randomUUID().toString().substring(0, 8)
            sp(c).edit().putString("device_id", id).apply()
        }
        return id
    }

    fun handsOn(c: Context): Boolean = sp(c).getBoolean("hands", true)
    fun setHandsOn(c: Context, on: Boolean) = sp(c).edit().putBoolean("hands", on).apply()

    /** Whatever was pasted (the sharing address, the phone page, with or without a slash) -> .../phone/ */
    fun normalize(raw: String): String {
        var a = raw.trim().replace(" ", "")
        if (a.isEmpty()) return ""
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
        a = a.trimEnd('/')
        if (!a.endsWith("/phone")) a += "/phone"
        return "$a/"
    }
}

object Net {
    /** POST JSON to Philibert's phone api. Returns (HTTP code, JSON or null); code -1 = unreachable. */
    fun post(c: Context, op: String, body: JSONObject, readTimeoutMs: Int = 20000): Pair<Int, JSONObject?> {
        val base = Prefs.address(c)
        if (base.isEmpty()) return -1 to null
        return try {
            val conn = URL(base + "api/" + op).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val txt = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            code to (try { JSONObject(txt) } catch (e: Exception) { null })
        } catch (e: Exception) {
            -1 to null
        }
    }
}

object Notifs {
    const val CHANNEL = "hands"
    const val CHANNEL_ASK = "ask"
    const val CHANNEL_SAY = "say"

    fun setup(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Philibert connection", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ASK, "Philibert asks first", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SAY, "Messages from Philibert", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun status(c: Context, text: String): Notification =
        Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_philibert)
            .setContentTitle("Philibert")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(c))
            .build()
}
