package ai.esteef.philibert.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Philibert's hands on the screen: reads what is showing as a numbered list (read_screen) and taps,
 * types, scrolls, swipes and presses Back / Home by those numbers. Only acts when an action comes
 * from the chat; it watches no events of its own.
 */
class PhoneAccessibility : AccessibilityService() {
    companion object {
        @Volatile var me: PhoneAccessibility? = null
        private val RISKY = Regex("\\b(send|pay|buy|purchase|place order|order now|checkout|check out|delete|remove|" +
            "erase|post|publish|share|transfer|confirm|call|sign out|log out|uninstall|reset)\\b", RegexOption.IGNORE_CASE)
    }

    private val nodes = mutableListOf<AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        me = this
        HandsService.start(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        me = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        me = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- reading ------------------------------------------------------------------------------
    @Synchronized
    fun readScreen(): String {
        nodes.clear()
        val root = rootInActiveWindow ?: return "The screen can't be read right now (it may be locked, or the app hides itself)."
        val pkg = root.packageName?.toString() ?: ""
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
        val lines = mutableListOf<String>()
        walk(root, lines, 0)
        val head = "On screen: $app ($pkg). ${nodes.size} items; use their numbers with tap / type_text / scroll."
        if (lines.isEmpty()) return "$head\n(nothing readable - this screen may be a game, a video, or a protected app)"
        return head + "\n" + lines.joinToString("\n")
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (n == null || depth > 40 || nodes.size >= 180) return
        if (!n.isVisibleToUser) return
        val text = (n.text?.toString() ?: "").replace("\n", " ").trim()
        val desc = (n.contentDescription?.toString() ?: "").replace("\n", " ").trim()
        val hint = (if (android.os.Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null)?.trim() ?: ""
        val useful = text.isNotEmpty() || desc.isNotEmpty() || n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
        if (useful) {
            nodes.add(n)
            val kind = (n.className?.toString() ?: "").substringAfterLast('.')
            val flags = mutableListOf<String>()
            if (n.isClickable || n.isLongClickable) flags.add("tap")
            if (n.isEditable) flags.add("text box")
            if (n.isScrollable) flags.add("scrolls")
            if (n.isCheckable) flags.add(if (n.isChecked) "on" else "off")
            if (n.isSelected) flags.add("selected")
            if (n.isFocused) flags.add("focused")
            if (!n.isEnabled) flags.add("disabled")
            val label = buildString {
                if (text.isNotEmpty()) append("\"").append(text.take(90)).append("\"")
                if (desc.isNotEmpty() && desc != text) append(if (isEmpty()) "" else " ").append("(").append(desc.take(70)).append(")")
                if (isEmpty() && hint.isNotEmpty()) append("hint \"").append(hint.take(60)).append("\"")
            }
            out.add("[${nodes.size - 1}] $kind $label" + (if (flags.isEmpty()) "" else " {" + flags.joinToString(", ") + "}"))
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out, depth + 1)
    }

    private fun node(id: Int): AccessibilityNodeInfo? = synchronized(this) { nodes.getOrNull(id) }

    private fun labelOf(n: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun add(x: AccessibilityNodeInfo?, d: Int) {
            if (x == null || d > 3 || sb.length > 200) return
            x.text?.let { sb.append(it).append(' ') }
            x.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until x.childCount) add(x.getChild(i), d + 1)
        }
        add(n, 0)
        return sb.toString().trim()
    }

    // ---- acting -------------------------------------------------------------------------------
    /** Tap item [id] (from read_screen), or the point x,y. Asks first when the button sends, pays or deletes. */
    fun tap(id: Int?, x: Int?, y: Int?): String {
        if (id != null) {
            val n = node(id) ?: return "There is no item $id - call read_screen again, the screen has changed."
            val label = labelOf(n)
            if (RISKY.containsMatchIn(label) &&
                !Confirm.ask(this, "Tap \"${label.take(60)}\"?", "Philibert wants to tap \"${label.take(120)}\" on this screen.")) {
                return "The person declined tapping \"${label.take(60)}\" on the phone. Nothing was tapped."
            }
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Thread.sleep(700)
                return "Tapped [$id] ${label.take(60)}."
            }
            val r = Rect().also { n.getBoundsInScreen(it) }
            return if (gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 60))
                "Tapped [$id] ${label.take(60)} (by touch)." else "Couldn't tap [$id]."
        }
        if (x != null && y != null) {
            return if (gesture(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 60)) "Tapped at $x,$y." else "Couldn't tap at $x,$y."
        }
        return "Say which item to tap: id from read_screen (or x and y)."
    }

    fun longPress(id: Int): String {
        val n = node(id) ?: return "There is no item $id - call read_screen again."
        val r = Rect().also { n.getBoundsInScreen(it) }
        return if (gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 800)) "Long-pressed [$id]." else "Couldn't long-press [$id]."
    }

    /** Put text into box [id], or the box that has the cursor, or the first box on screen. */
    fun type(text: String, id: Int?, enter: Boolean): String {
        var target: AccessibilityNodeInfo? = if (id != null) node(id) else null
        if (target == null) target = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null || !target.isEditable) {
            if (nodes.isEmpty()) readScreen()
            target = synchronized(this) { nodes.firstOrNull { it.isEditable } }
        }
        if (target == null) return "There is no text box on the screen - tap one first (read_screen shows them as {text box})."
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "That box would not take the text."
        if (enter && android.os.Build.VERSION.SDK_INT >= 30) {
            Thread.sleep(200)
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        Thread.sleep(500)
        return "Typed \"${text.take(80)}\"" + (if (enter) " and pressed Enter." else ".")
    }

    fun scroll(direction: String, id: Int?): String {
        val forward = direction.lowercase() in setOf("down", "right", "forward", "next")
        var target: AccessibilityNodeInfo? = if (id != null) node(id) else null
        if (target == null) {
            if (nodes.isEmpty()) readScreen()
            target = synchronized(this) {
                nodes.filter { it.isScrollable }.maxByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.width() * r.height() }
            }
        }
        while (target != null && !target.isScrollable) target = target.parent
        if (target != null && target.performAction(
                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            Thread.sleep(600)
            return "Scrolled $direction."
        }
        // nothing scrollable said yes: swipe the middle of the screen instead
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val d = dm.heightPixels / 3f
        val ok = when (direction.lowercase()) {
            "up" -> gesture(cx, cy - d, cx, cy + d, 350)
            "left" -> gesture(cx - d, cy, cx + d, cy, 350)
            "right" -> gesture(cx + d, cy, cx - d, cy, 350)
            else -> gesture(cx, cy + d, cx, cy - d, 350)
        }
        Thread.sleep(600)
        return if (ok) "Swiped to scroll $direction." else "Couldn't scroll."
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): String =
        if (gesture(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), ms)) "Swiped." else "Couldn't swipe."

    fun press(key: String): String {
        val a = when (key.lowercase().replace(" ", "_")) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent_apps", "overview" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock", "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return "Unknown key \"$key\" - use back, home, recents, notifications, quick_settings, lock_screen or screenshot."
        }
        val ok = performGlobalAction(a)
        Thread.sleep(600)
        return if (ok) "Pressed $key." else "Couldn't press $key."
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val done = CountDownLatch(1)
        var ok = false
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; done.countDown() }
            override fun onCancelled(d: GestureDescription?) { done.countDown() }
        }, null)
        if (!sent) return false
        done.await(ms + 3000, TimeUnit.MILLISECONDS)
        return ok
    }
}
