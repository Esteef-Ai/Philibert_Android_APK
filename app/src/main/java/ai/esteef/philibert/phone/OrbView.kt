package ai.esteef.philibert.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** Talk mode's orb: breathes with your voice while listening, pulses while Philibert speaks. */
class OrbView(c: Context) : View(c) {
    companion object {
        const val IDLE = 0
        const val LISTEN = 1
        const val THINK = 2
        const val SPEAK = 3
    }

    var mode = IDLE
    var level = 0f                     // 0..1, the microphone's loudness while listening
    private var shown = 0f
    private val t0 = System.nanoTime()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun color(): Int = when (mode) {
        LISTEN -> Color.rgb(0, 229, 255)
        THINK -> Color.rgb(255, 195, 90)
        SPEAK -> Color.rgb(255, 46, 136)
        else -> Color.rgb(80, 100, 135)
    }

    private fun alpha(col: Int, a: Int) = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))

    private fun lighter(col: Int) = Color.rgb(
        Color.red(col) + (255 - Color.red(col)) / 2, Color.green(col) + (255 - Color.green(col)) / 2,
        Color.blue(col) + (255 - Color.blue(col)) / 2)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val base = min(w, h) * 0.27f
        val t = (System.nanoTime() - t0) / 1e9f
        shown += ((if (mode == LISTEN) level else 0f) - shown) * 0.25f
        val pulse = when (mode) {
            LISTEN -> 0.04f * sin(t * 2.2f) + shown * 0.35f
            THINK -> 0.07f * sin(t * 3f)
            SPEAK -> 0.07f * abs(sin(t * 5.5f)) + 0.03f * sin(t * 13f)
            else -> 0.02f * sin(t * 1.2f)
        }
        val r = base * (1f + pulse)
        val col = color()
        p.shader = RadialGradient(cx, cy, r * 1.9f, intArrayOf(alpha(col, 120), alpha(col, 0)), null, Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r * 1.9f, p)
        p.shader = RadialGradient(cx - r * 0.25f, cy - r * 0.35f, r * 1.3f, intArrayOf(lighter(col), col, alpha(col, 235)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, p)
        p.shader = null
        postInvalidateOnAnimation()
    }
}
