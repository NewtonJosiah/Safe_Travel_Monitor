package com.knightmeya.safetravelmonitor

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.net.toUri
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
        val resId = R.raw.welcome
        
        if (resId != 0) {
            val videoPath = "android.resource://$packageName/$resId"
            videoView.setVideoURI(videoPath.toUri())
            
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = true
                // Show video only when it's ready to avoid black flash
                videoView.animate().alpha(1f).setDuration(300).start()
            }

            videoView.start()
        }

        // startTypeWriter() moved to onResume to handle back navigation

        btnGetStarted.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        tvLoginLink.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val videoView = findViewById<VideoView>(R.id.videoView)
        
        // Ensure UI elements are hidden if we're coming back
        typeWriterIndex = 0
        tvTitle.text = ""
        findViewById<TextView>(R.id.tvSubtitle).alpha = 0f
        btnGetStarted.visibility = View.GONE
        tvLoginLink.visibility = View.GONE
        
        if (!videoView.isPlaying) {
            videoView.start()
        }
        
        startTypeWriter()
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
        handler.postDelayed(typeWriterRunnable, 1500)
    }

    private fun checkAuthAndShowButtons() {
        val currentUser = auth.currentUser
        if ((currentUser != null) && (currentUser.isEmailVerified)) {
            // Already logged in, transition to Main
            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(this, MainActivity::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val options = ActivityOptionsCompat.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
                    startActivity(intent, options.toBundle())
                } else {
                    startActivity(intent)
                    @Suppress("DEPRECATION")
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                }
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
