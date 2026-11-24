package it.flubatir.coreassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

enum class VState { IDLE, LISTENING, THINKING, SPEAKING }

val PALETTE = intArrayOf(0xFF4C8DF6.toInt(), 0xFF9B72CB.toInt(), 0xFFD96570.toInt(), 0xFFF4B740.toInt(), 0xFF34D399.toInt())

/** Pallini colorati che diventano barre-onda mentre ascolti/parli e rimbalzano mentre ragiona (stile assistente Google, colori GPT CORE). */
class WaveView(c: Context) : View(c) {
    var state = VState.IDLE
        set(v) { if (field != v) { field = v; t0 = SystemClock.uptimeMillis() }; invalidate() }
    private var level = 0f
    private var smooth = 0f
    private var t0 = SystemClock.uptimeMillis()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var morph = 0f // 0 = pallini, 1 = barre

    fun setLevel(l: Float) { level = l.coerceIn(0f, 1f) }

    override fun onDraw(cv: Canvas) {
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        smooth += (level - smooth) * 0.25f
        val target = when (state) { VState.LISTENING, VState.SPEAKING -> 1f; else -> 0f }
        morph += (target - morph) * 0.18f
        val n = 5; val w = width.toFloat(); val h = height.toFloat()
        val dot = h * 0.2f; val gap = dot * 1.5f
        val total = n * dot + (n - 1) * gap
        var x = (w - total) / 2f
        for (i in 0 until n) {
            var bh = dot
            var dy = 0f
            when (state) {
                VState.LISTENING, VState.SPEAKING -> {
                    val env = if (state == VState.LISTENING) smooth else maxOf(smooth, 0.15f)
                    val wob = 0.55f + 0.45f * abs(sin(t * 5f + i * 1.3f))
                    val shape = if (i == 2) 1f else if (i == 1 || i == 3) 0.72f else 0.5f
                    bh = dot + (h * 0.78f - dot) * (env * wob * shape).coerceIn(0f, 1f) * morph
                }
                VState.THINKING -> dy = -sin(t * 2f * PI.toFloat() * 1.1f - i * 0.7f).coerceAtLeast(0f) * h * 0.28f
                VState.IDLE -> bh = dot * (1f + 0.08f * sin(t * 2f + i))
            }
            val cy = h / 2f + dy
            p.color = PALETTE[i % PALETTE.size]
            rect.set(x, cy - bh / 2f, x + dot, cy + bh / 2f)
            cv.drawRoundRect(rect, dot / 2f, dot / 2f, p)
            x += dot + gap
        }
        postInvalidateOnAnimation()
    }
}

/** Bagliore sul bordo inferiore dello schermo che scorre e pulsa con la voce. */
class GlowView(c: Context) : View(c) {
    var active = false
    private var level = 0f
    private var smooth = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setLevel(l: Float) { level = l }

    override fun onDraw(cv: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        smooth += ((if (active) 0.35f + level else 0.15f) - smooth) * 0.12f
        val w = width.toFloat(); val h = height.toFloat()
        val shift = (t * 0.35f) % 1f * w
        val colors = intArrayOf(PALETTE[0], PALETTE[1], PALETTE[2], PALETTE[3], PALETTE[0])
        val g = LinearGradient(-w + shift, 0f, shift, 0f, colors, null, Shader.TileMode.REPEAT)
        p.shader = g
        // alone sfumato verso l'alto
        val glowH = h * (0.55f + 0.45f * smooth.coerceIn(0f, 1.2f))
        p.alpha = (110 * smooth.coerceIn(0.2f, 1f)).toInt().coerceIn(30, 140)
        val fade = LinearGradient(0f, h, 0f, h - glowH, intArrayOf(0xFFFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        val sc = cv.saveLayer(0f, 0f, w, h, null)
        cv.drawRect(0f, h - glowH, w, h, p)
        p.shader = fade
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        p.alpha = 255
        cv.drawRect(0f, 0f, w, h, p)
        p.xfermode = null
        cv.restoreToCount(sc)
        // linea luminosa
        p.shader = g; p.alpha = 255
        cv.drawRect(0f, h - 6f, w, h, p)
        postInvalidateOnAnimation()
    }
}
