package com.knightmeya.safetravelmonitor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    private val fullText = "Safe Travel Monitor"
    private var typeWriterIndex = 0
    private lateinit var tvTitle: TextView
    private lateinit var btnGetStarted: MaterialButton
    private lateinit var tvLoginLink: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        tvTitle = findViewById(R.id.tvTitle)
        btnGetStarted = findViewById(R.id.btnGetStarted)
        tvLoginLink = findViewById(R.id.tvLoginLink)

        val videoView = findViewById<VideoView>(R.id.videoView)
        val resId = resources.getIdentifier("welcome", "raw", packageName)
        
        if (resId != 0) {
            val videoPath = "android.resource://$packageName/$resId"
            videoView.setVideoURI(Uri.parse(videoPath))
            
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = true
            }

            videoView.start()
        }

        startTypeWriter()

        btnGetStarted.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        tvLoginLink.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    private fun startTypeWriter() {
        tvTitle.text = ""
        val handler = Handler(Looper.getMainLooper())
        val typeWriterRunnable = object : Runnable {
            override fun run() {
                if (typeWriterIndex <= fullText.length) {
                    tvTitle.text = fullText.substring(0, typeWriterIndex)
                    typeWriterIndex++
                    handler.postDelayed(this, 100)
                } else {
                    checkAuthAndShowButtons()
                }
            }
        }
        handler.postDelayed(typeWriterRunnable, 500)
    }

    private fun checkAuthAndShowButtons() {
        val currentUser = auth.currentUser
        if (currentUser != null && currentUser.isEmailVerified) {
            // Already logged in, transition to Main
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this, MainActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }, 1000)
        } else {
            // Not logged in, show options
            findViewById<TextView>(R.id.tvSubtitle).animate().alpha(1f).duration = 1000
            btnGetStarted.visibility = View.VISIBLE
            tvLoginLink.visibility = View.VISIBLE
            btnGetStarted.animate().alpha(1f).setDuration(1000).start()
            tvLoginLink.animate().alpha(1f).setDuration(1000).start()
        }
    }
}
