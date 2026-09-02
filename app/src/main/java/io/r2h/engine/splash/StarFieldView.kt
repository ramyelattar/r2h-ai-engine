package io.r2h.engine.splash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws an animated star-field and soft teal glow emanating from the center.
 * Rendered entirely on the Canvas — no images, no external dependencies.
 */
class StarFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private data class Star(
        var x: Float,
        var y: Float,
        val baseRadius: Float,
        val speed: Float,       // twinkle speed multiplier
        val phase: Float,       // phase offset so stars don't all twinkle together
        val angle: Float,       // drift angle in radians
        val driftSpeed: Float,  // px per frame
    )

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCEEFF.toInt()
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowShader: RadialGradient? = null
    private var lastWidth = 0
    private var lastHeight = 0

    private val stars = mutableListOf<Star>()
    private var frameCount = 0L
    private val rng = Random(42)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)  // required for shadow/blur effects
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        lastWidth = w
        lastHeight = h
        rebuildStars(w, h)
        rebuildGlow(w, h)
    }

    private fun rebuildStars(w: Int, h: Int) {
        stars.clear()
        repeat(160) {
            stars += Star(
                x = rng.nextFloat() * w,
                y = rng.nextFloat() * h,
                baseRadius = 0.6f + rng.nextFloat() * 1.8f,
                speed = 0.4f + rng.nextFloat() * 1.2f,
                phase = rng.nextFloat() * (2f * Math.PI.toFloat()),
                angle = rng.nextFloat() * (2f * Math.PI.toFloat()),
                driftSpeed = 0.05f + rng.nextFloat() * 0.15f,
            )
        }
    }

    private fun rebuildGlow(w: Int, h: Int) {
        val cx = w * 0.5f
        val cy = h * 0.42f
        val r = maxOf(w, h) * 0.6f
        glowShader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                0x2A1ACBE8.toInt(),  // teal centre glow
                0x141ACBE8.toInt(),
                0x00000000,
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        glowPaint.shader = glowShader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lastWidth == 0) return

        // Draw radial teal atmosphere
        canvas.drawRect(0f, 0f, lastWidth.toFloat(), lastHeight.toFloat(), glowPaint)

        val time = frameCount * 0.016f  // ~60fps tick

        for (star in stars) {
            // Twinkle: sine wave on alpha and radius
            val twinkle = (0.4f + 0.6f * ((sin((time * star.speed + star.phase).toDouble()).toFloat() + 1f) / 2f))
            val radius = star.baseRadius * (0.7f + 0.3f * twinkle)
            val alpha = (twinkle * 255 * alpha).toInt().coerceIn(0, 255)

            // Slow drift
            star.x += cos(star.angle.toDouble()).toFloat() * star.driftSpeed
            star.y += sin(star.angle.toDouble()).toFloat() * star.driftSpeed

            // Wrap around edges
            if (star.x < -4) star.x = lastWidth.toFloat() + 4
            if (star.x > lastWidth + 4) star.x = -4f
            if (star.y < -4) star.y = lastHeight.toFloat() + 4
            if (star.y > lastHeight + 4) star.y = -4f

            starPaint.alpha = alpha
            canvas.drawCircle(star.x, star.y, radius, starPaint)
        }

        frameCount++
        postInvalidateOnAnimation()
    }
}

