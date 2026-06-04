package com.knightmeya.safetravelmonitor

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.knightmeya.safetravelmonitor.databinding.ActivityWelcomeBinding
import kotlin.random.Random

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private val fullText = "Safe Travel Monitor"
    private var typeWriterIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startAnimations()

        binding.btnGetStarted.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        binding.tvLoginLink.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    private fun startAnimations() {
        addStars()

        val rotateAnimator = ObjectAnimator.ofFloat(binding.globe, "rotation", 0f, 360f).apply {
            duration = 10000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        rotateAnimator.start()

        binding.tvTitle.text = ""
        val handler = Handler(Looper.getMainLooper())
        val typeWriterRunnable = object : Runnable {
            override fun run() {
                if (typeWriterIndex <= fullText.length) {
                    binding.tvTitle.text = fullText.substring(0, typeWriterIndex)
                    typeWriterIndex++
                    handler.postDelayed(this, 100)
                }
            }
        }
        handler.postDelayed(typeWriterRunnable, 1000)
    }

    private fun addStars() {
        val root = binding.root
        val random = Random.Default
        for (i in 0..50) {
            val star = View(this).apply {
                layoutParams = ViewGroup.LayoutParams(4, 4)
                setBackgroundColor(Color.WHITE)
                alpha = random.nextFloat() * 0.7f + 0.3f
            }
            root.addView(star)
            
            val randomX = random.nextInt(resources.displayMetrics.widthPixels).toFloat()
            val randomY = random.nextInt(resources.displayMetrics.heightPixels).toFloat()
            
            star.x = randomX
            star.y = randomY
            
            ObjectAnimator.ofFloat(star, "alpha", star.alpha, 0.1f, star.alpha).apply {
                duration = (random.nextInt(2000) + 1000).toLong()
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }
    }
}
