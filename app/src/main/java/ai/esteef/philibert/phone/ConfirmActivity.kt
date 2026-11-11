package ai.esteef.philibert.phone

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** "Philibert wants to send this text - OK?" Asked on the phone, by the person holding it. */
object Confirm {
    private class Ask(val title: String, val text: String) {
        val latch = CountDownLatch(1)
        @Volatile var yes = false
    }

    private val asks = ConcurrentHashMap<Int, Ask>()
    private var next = 100

    /** Blocks (never call on the main thread) until the person answers, or 75 s pass (= no; Philibert gives an action 90 s). */
    fun ask(c: Context, title: String, text: String): Boolean {
        val id = synchronized(this) { next++ }
        val a = Ask(title, text)
        asks[id] = a
        val i = Intent(c, ConfirmActivity::class.java)
            .putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val nm = c.getSystemService(NotificationManager::class.java)
        // the notification as well: if Android won't open the question over another app, it's here
        val pi = PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(id, Notification.Builder(c, Notifs.CHANNEL_ASK)
            .setSmallIcon(R.drawable.ic_philibert)
            .setContentTitle("Philibert asks: $title")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build())
        try {
            (PhoneAccessibility.me ?: c).startActivity(i)
        } catch (e: Exception) { /* the notification is there */ }
        a.latch.await(75, TimeUnit.SECONDS)
        nm.cancel(id)
        asks.remove(id)
        return a.yes
    }

    fun get(id: Int): Pair<String, String>? = asks[id]?.let { it.title to it.text }

    fun answer(id: Int, yes: Boolean) {
        asks[id]?.let { it.yes = yes; it.latch.countDown() }
    }
}

class ConfirmActivity : Activity() {
    private var id = -1
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getIntExtra("id", -1)
        val q = Confirm.get(id)
        if (q == null) { finish(); return }
        setFinishOnTouchOutside(false)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(getColor(R.color.panel))
        }
        box.addView(TextView(this).apply {
            text = "Philibert asks"
            setTextColor(getColor(R.color.cyan))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = q.first
            setTextColor(getColor(R.color.txt))
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, pad / 3, 0, pad / 3)
        })
        box.addView(TextView(this).apply {
            text = q.second
            setTextColor(getColor(R.color.dim))
            textSize = 15f
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, pad, 0, 0)
        }
        row.addView(Button(this).apply { text = "No"; setOnClickListener { done(false) } })
        row.addView(Button(this).apply { text = "Yes, do it"; setOnClickListener { done(true) } })
        box.addView(row)
        setContentView(box)
    }

    private fun done(yes: Boolean) {
        answered = true
        Confirm.answer(id, yes)
        finish()
    }

    override fun onDestroy() {
        if (!answered && isFinishing) Confirm.answer(id, false)
        super.onDestroy()
    }
}
