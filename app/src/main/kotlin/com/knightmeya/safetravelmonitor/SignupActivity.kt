package com.knightmeya.safetravelmonitor

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.knightmeya.safetravelmonitor.databinding.ActivitySignupBinding
import com.knightmeya.safetravelmonitor.models.User

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSignup.setOnClickListener {
            handleSignup()
        }

        binding.tvLoginLink.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun handleSignup() {
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val phone = binding.etPhone.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()

        if (name.isEmpty() || email.isEmpty() || phone.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        if (password != confirmPassword) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isPasswordValid(password)) {
            Toast.makeText(this, "Password must be at least 8 characters, include a capital letter, a number, and a symbol", Toast.LENGTH_LONG).show()
            return
        }

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val firebaseUser = task.result?.user
                    val uid = firebaseUser?.uid ?: return@addOnCompleteListener
                    val numericId = (10000000..99999999).random().toString()
                    
                    val user = User(
                        uid = uid,
                        numericId = numericId,
                        name = name,
                        email = email,
                        phone = phone,
                    )

                    database.child("users").child(uid).setValue(user)
                        .addOnSuccessListener {
                            database.child("id_to_uid").child(numericId).setValue(uid)
                            
                            firebaseUser.sendEmailVerification().addOnCompleteListener {
                                Toast.makeText(this, "Account created! Please verify your email.", Toast.LENGTH_LONG).show()
                                startActivity(Intent(this, EmailVerificationActivity::class.java))
                                finish()
                            }
                        }
                } else {
                    val exception = task.exception
                    val errorMessage = exception?.message ?: "Unknown error"
                    val errorCode = (exception as? com.google.firebase.auth.FirebaseAuthException)?.errorCode ?: "No Code"
                    
                    // Log the full stack trace for debugging
                    android.util.Log.e("SignupError", "Full exception:", exception)

                    Toast.makeText(this, "Signup failed ($errorCode): $errorMessage", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun isPasswordValid(password: String): Boolean {
        if (password.length < 8) return false
        val hasUpperCase = password.any { it.isUpperCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSymbol = password.any { !it.isLetterOrDigit() }
        return hasUpperCase && hasDigit && hasSymbol
    }
}
