package com.knightmeya.safetravelmonitor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val videoView = findViewById<VideoView>(R.id.videoView)
        
        // Use getIdentifier to allow compilation even if the resource is missing initially
        val resId = resources.getIdentifier("welcome", "raw", packageName)
        
        if (resId != 0) {
            val videoPath = "android.resource://" + packageName + "/" + resId
            videoView.setVideoURI(Uri.parse(videoPath))
            
            videoView.setOnCompletionListener {
                navigateToNextScreen()
            }

            videoView.setOnErrorListener { _, _, _ ->
                navigateToNextScreen()
                true
            }

            videoView.start()
        } else {
            // If video is missing, skip to next screen immediately
            navigateToNextScreen()
        }
    }

    private fun navigateToNextScreen() {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        
        val intent = if (currentUser != null && currentUser.isEmailVerified) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, LoginActivity::class.java)
        }
        
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
