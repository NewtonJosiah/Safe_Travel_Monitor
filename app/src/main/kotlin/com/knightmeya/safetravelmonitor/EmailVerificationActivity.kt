package com.knightmeya.safetravelmonitor

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.knightmeya.safetravelmonitor.databinding.ActivityEmailVerificationBinding

class EmailVerificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmailVerificationBinding
    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmailVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val email = auth.currentUser?.email
        binding.tvVerificationMsg.text = getString(R.string.verification_email_sent, email)

        binding.btnVerifiedDone.setOnClickListener {
            checkVerificationStatus()
        }

        binding.tvResendEmail.setOnClickListener {
            resendVerificationEmail()
        }

        binding.btnBackToLogin.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun checkVerificationStatus() {
        val user = auth.currentUser
        user?.reload()?.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                if (user.isEmailVerified) {
                    Toast.makeText(this, "Email verified! Welcome.", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, "Email not verified yet. Please check your inbox.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Error checking status: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resendVerificationEmail() {
        auth.currentUser?.sendEmailVerification()?.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Toast.makeText(this, "Verification email resent!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to resend: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
