package com.knightmeya.safetravelmonitor

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.knightmeya.safetravelmonitor.databinding.ActivityWelcomeBinding
import kotlin.math.cos
import kotlin.math.sin

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private val auth = FirebaseAuth.getInstance()
    private val fullText = "Safe Travel Monitor"
    private var typeWriterIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val currentUser = auth.currentUser
        if (currentUser != null) {
            // Hide buttons if already logged in to use as splash screen
            binding.btnGetStarted.visibility = View.GONE
            binding.tvLoginLink.visibility = View.GONE
        }

        startAnimations()

        binding.btnGetStarted.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        binding.tvLoginLink.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    private fun startAnimations() {
        // Star animation removed to prevent emulator crashes

        // Rotate the globe slowly
        ObjectAnimator.ofFloat(binding.globe, "rotation", 0f, 360f).apply {
            duration = 20000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // Plane Flight Animation
        binding.ivPlane.visibility = View.VISIBLE
        val radius = 130f * resources.displayMetrics.density
        
        val animator = ValueAnimator.ofFloat(0f, 360f)
        animator.duration = 4000
        animator.interpolator = LinearInterpolator()
        
        animator.addUpdateListener { anim ->
            val angle = anim.animatedValue as Float
            val angleRad = Math.toRadians(angle.toDouble())
            
            val x = (radius * cos(angleRad)).toFloat()
            val y = (radius * sin(angleRad)).toFloat()
            
            binding.ivPlane.translationX = x
            binding.ivPlane.translationY = y
            binding.ivPlane.rotation = angle + 90f // Keep plane pointed forward
        }
        
        animator.addListener(
            object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    startTypeWriter()
                }
            },
        )
        
        animator.start()
    }

    private fun startTypeWriter() {
        binding.tvTitle.text = ""
        val handler = Handler(Looper.getMainLooper())
        val typeWriterRunnable = object : Runnable {
            override fun run() {
                if (typeWriterIndex <= fullText.length) {
                    binding.tvTitle.text = fullText.substring(0, typeWriterIndex)
                    typeWriterIndex++
                    handler.postDelayed(this, 100)
                } else {
                    // Transition to next screen if user is already logged in
                    val currentUser = auth.currentUser
                    if (currentUser != null) {
                        handler.postDelayed({
                            if (currentUser.isEmailVerified) {
                                startActivity(Intent(this@WelcomeActivity, MainActivity::class.java))
                            } else {
                                startActivity(Intent(this@WelcomeActivity, EmailVerificationActivity::class.java))
                            }
                            finish()
                        }, 1000)
                    }
                }
            }
        }
        handler.postDelayed(typeWriterRunnable, 500)
    }
}
