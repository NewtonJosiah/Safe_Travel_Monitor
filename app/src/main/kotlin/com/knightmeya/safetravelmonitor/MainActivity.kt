package com.knightmeya.safetravelmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import coil.load
import coil.transform.CircleCropTransformation
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import com.knightmeya.safetravelmonitor.databinding.ActivityMainBinding
import com.knightmeya.safetravelmonitor.models.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    private val storage = FirebaseStorage.getInstance().reference
    private val permissionRequestCode = 123
    private var requestDialog: androidx.appcompat.app.AlertDialog? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { uploadProfilePic(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, SplashActivity::class.java))
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
        val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000)
        
        database.child("monitoring_requests").child(myUid)
            .orderByChild("timestamp")
            .startAt(tenMinutesAgo.toDouble())
            .addChildEventListener(
            object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val key = snapshot.key ?: return
                    val request = snapshot.getValue(MonitoringRequest::class.java)?.copy(journeyId = key) ?: return
                    if (request.status == "pending") {
                        showMonitoringRequestDialog(request)
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    val key = snapshot.key ?: return
                    val request = snapshot.getValue(MonitoringRequest::class.java)?.copy(journeyId = key) ?: return
                    if ((request.status == "pending") && (requestDialog == null)) {
                        showMonitoringRequestDialog(request)
                    }
                }
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            },
        )
    }

    private fun showMonitoringRequestDialog(request: MonitoringRequest) {
        if (requestDialog?.isShowing == true) return
        
        requestDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Monitoring Request")
            .setMessage("${request.travelerName} wants you to monitor their journey. Accept?")
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ ->
                val myUid = auth.currentUser?.uid ?: return@setPositiveButton
                database.child("monitoring_requests").child(myUid).child(request.journeyId).child("status").setValue("accepted")
                
                val monitorIntent = Intent(this, MonitorActivity::class.java)
                monitorIntent.putExtra("EXTRA_JOURNEY_ID", request.journeyId)
                startActivity(monitorIntent)
            }
            .setNegativeButton("Reject") { _, _ ->
                val myUid = auth.currentUser?.uid ?: return@setNegativeButton
                database.child("monitoring_requests").child(myUid).child(request.journeyId).child("status").setValue("rejected")
            }
            .setOnDismissListener { requestDialog = null }
            .show()
    }

    private fun loadUserInfo(uid: String) {
        database.child("users").child(uid).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val user = snapshot.getValue(User::class.java)
                user?.let {
                    binding.tvUserName.text = it.name
                    binding.tvUserID.text = getString(R.string.id_format, it.numericId)
                    if (!it.profilePic.isNullOrEmpty()) {
                        binding.ivProfilePic.load(it.profilePic) {
                            transformations(CircleCropTransformation())
                        }
                        binding.tvUploadHint.visibility = View.GONE
                    } else {
                        binding.ivProfilePic.setImageResource(android.R.color.transparent)
                        binding.tvUploadHint.visibility = View.VISIBLE
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun uploadProfilePic(uri: Uri) {
        val uid = auth.currentUser?.uid ?: return
        val ref = storage.child("profile_pics/$uid.jpg")
        
        Toast.makeText(this, "Uploading...", Toast.LENGTH_SHORT).show()
        
        ref.putFile(uri).continueWithTask { task ->
            if (!task.isSuccessful) {
                task.exception?.let { throw it }
            }
            ref.downloadUrl
        }.addOnSuccessListener { downloadUri ->
            database.child("users").child(uid).child("profilePic").setValue(downloadUri.toString())
                .addOnSuccessListener {
                    Toast.makeText(this, "Profile updated!", Toast.LENGTH_SHORT).show()
                }
        }.addOnFailureListener { e ->
            Log.e("MainActivity", "Upload/URL failed", e)
            val errorMsg = if (e.message?.contains("exist") == true) {
                "Storage not initialized. Please enable Storage in Firebase Console."
            } else {
                e.message ?: "Unknown error"
            }
            Toast.makeText(this, "Upload failed: $errorMsg", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupUI() {
        binding.profilePicContainer.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnFriendsFamily.setOnClickListener {
            startActivity(Intent(this, FriendsActivity::class.java))
        }

        binding.travellerButton.setOnClickListener {
            startActivity(Intent(this, TravelerActivity::class.java))
        }

        binding.monitorButton.setOnClickListener {
            startActivity(Intent(this, MonitorActivity::class.java))
        }

        binding.btnLogout.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
        }
    }

    private fun checkAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
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
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), permissionRequestCode)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions required for full functionality", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
