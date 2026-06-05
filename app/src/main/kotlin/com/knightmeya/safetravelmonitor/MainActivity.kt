package com.knightmeya.safetravelmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.knightmeya.safetravelmonitor.databinding.ActivityMainBinding
import com.knightmeya.safetravelmonitor.models.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    private val PERMISSION_REQUEST_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        if (!currentUser.isEmailVerified) {
            Toast.makeText(this, "Please verify your email to continue", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setupUI()
            checkAllPermissions()
            
            loadUserInfo(currentUser.uid)
            listenForMonitoringRequests()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error starting app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun listenForMonitoringRequests() {
        val myUid = auth.currentUser?.uid ?: return
        database.child("monitoring_requests").child(myUid).addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val request = snapshot.getValue(MonitoringRequest::class.java) ?: return
                if (request.status == "pending") {
                    showMonitoringRequestDialog(request)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun showMonitoringRequestDialog(request: MonitoringRequest) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Monitoring Request")
            .setMessage("${request.travelerName} wants you to monitor their journey. Accept?")
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ ->
                val myUid = auth.currentUser?.uid ?: return@setPositiveButton
                database.child("monitoring_requests").child(myUid).child(request.journeyId).child("status").setValue("accepted")
                // Navigate to Monitor screen
                startActivity(Intent(this, MonitorActivity::class.java))
            }
            .setNegativeButton("Reject") { _, _ ->
                val myUid = auth.currentUser?.uid ?: return@setNegativeButton
                database.child("monitoring_requests").child(myUid).child(request.journeyId).child("status").setValue("rejected")
            }
            .show()
    }

    private fun loadUserInfo(uid: String) {
        database.child("users").child(uid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val user = snapshot.getValue(User::class.java)
                user?.let {
                    binding.tvUserName.text = getString(R.string.hello_user, it.name)
                    binding.tvUserID.text = getString(R.string.your_id, it.numericId)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun setupUI() {
        binding.travellerButton.setOnClickListener {
            startActivity(Intent(this, TravelerActivity::class.java))
        }

        binding.monitorButton.setOnClickListener {
            startActivity(Intent(this, MonitorActivity::class.java))
        }

        binding.btnFriends.setOnClickListener {
            startActivity(Intent(this, FriendsActivity::class.java))
        }

        binding.btnLogout.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
        }
    }

    private fun checkAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions required for full functionality", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
