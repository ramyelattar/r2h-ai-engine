package io.r2h.engine.splash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.r2h.engine.MainActivity
import io.r2h.engine.R

class SplashActivity : AppCompatActivity() {

    private lateinit var starField: StarFieldView
    private lateinit var glowRing: ImageView
    private lateinit var splashArt: ImageView
    private lateinit var luxuryGlow: View
    private lateinit var scanLine: View
    private lateinit var redPulse: View

    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_splash)

        starField = findViewById(R.id.star_field)
        glowRing = findViewById(R.id.iv_glow_ring)
        splashArt = findViewById(R.id.iv_splash_art)
        luxuryGlow = findViewById(R.id.v_luxury_glow)
        scanLine = findViewById(R.id.v_scan_line)
        redPulse = findViewById(R.id.v_red_pulse)

        runLuxurySequence()
    }

    private fun runLuxurySequence() {
        splashArt.apply {
            alpha = 0f
            scaleX = 1.055f
            scaleY = 1.055f
        }

        val artReveal =
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(splashArt, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(splashArt, View.SCALE_X, 1.055f, 1f),
                    ObjectAnimator.ofFloat(splashArt, View.SCALE_Y, 1.055f, 1f),
                )
                duration = 900L
                interpolator = DecelerateInterpolator(1.8f)
            }

        val stars =
            ObjectAnimator.ofFloat(starField, View.ALPHA, 0f, 0.50f).apply {
                duration = 900L
                startDelay = 120L
                interpolator = DecelerateInterpolator()
            }

        val glowReveal =
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(luxuryGlow, View.ALPHA, 0f, 0.72f),
                    ObjectAnimator.ofFloat(luxuryGlow, View.SCALE_X, 0.72f, 1.08f),
                    ObjectAnimator.ofFloat(luxuryGlow, View.SCALE_Y, 0.72f, 1.08f),
                    ObjectAnimator.ofFloat(glowRing, View.ALPHA, 0f, 0.88f),
                    ObjectAnimator.ofFloat(glowRing, View.SCALE_X, 0.72f, 1f),
                    ObjectAnimator.ofFloat(glowRing, View.SCALE_Y, 0.72f, 1f),
                    ObjectAnimator.ofFloat(glowRing, View.ROTATION, -16f, 0f),
                )
                duration = 980L
                startDelay = 260L
                interpolator = DecelerateInterpolator(2f)
            }

        val ringBreath =
            ValueAnimator.ofFloat(0.58f, 0.96f, 0.68f).apply {
                duration = 1100L
                startDelay = 900L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animation ->
                    glowRing.alpha = animation.animatedValue as Float
                }
            }

        val scan =
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(scanLine, View.ALPHA, 0f, 1f, 0f),
                    ObjectAnimator.ofFloat(
                        scanLine,
                        View.TRANSLATION_Y,
                        -resources.displayMetrics.density * 86f,
                        resources.displayMetrics.density * 92f,
                    ),
                    ObjectAnimator.ofFloat(scanLine, View.SCALE_X, 0.25f, 1f, 0.35f),
                )
                duration = 780L
                startDelay = 620L
                interpolator = AccelerateDecelerateInterpolator()
            }

        val pulse =
            ObjectAnimator.ofFloat(redPulse, View.ALPHA, 0f, 0.13f, 0f).apply {
                duration = 540L
                startDelay = 1050L
                interpolator = DecelerateInterpolator()
            }

        val exit =
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(window.decorView, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(splashArt, View.SCALE_X, 1f, 1.018f),
                    ObjectAnimator.ofFloat(splashArt, View.SCALE_Y, 1f, 1.018f),
                    ObjectAnimator.ofFloat(glowRing, View.ALPHA, glowRing.alpha, 0f),
                    ObjectAnimator.ofFloat(luxuryGlow, View.ALPHA, luxuryGlow.alpha, 0f),
                )
                duration = 360L
                startDelay = 1950L
                interpolator = AccelerateDecelerateInterpolator()
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            launchMain()
                        }
                    },
                )
            }

        AnimatorSet().apply {
            playTogether(
                artReveal,
                stars,
                glowReveal,
                ringBreath,
                scan,
                pulse,
                exit,
            )
            start()
        }
    }

    private fun launchMain() {
        if (launched) return
        launched = true

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            },
        )

        window.decorView.alpha = 1f
        finish()
        overridePendingTransition(android.R.anim.fade_in, 0)
    }
}
