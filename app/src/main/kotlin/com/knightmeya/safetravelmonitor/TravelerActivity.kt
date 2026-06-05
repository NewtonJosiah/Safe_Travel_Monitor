package com.knightmeya.safetravelmonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.knightmeya.safetravelmonitor.databinding.ActivityTravelerBinding
import com.knightmeya.safetravelmonitor.models.*
import java.text.SimpleDateFormat
import java.util.*

class TravelerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityTravelerBinding
    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var travelerMarker: com.google.android.gms.maps.model.Marker? = null
    private var destinationMarker: com.google.android.gms.maps.model.Marker? = null
    private var isJourneyActive = false
    private var journeyStartTime = 0L
    private var estimatedArrivalTime = 0L
    private var selectedDestination: LatLng? = null
    private var travelMode = "driving"
    private var journeyId: String? = null
    
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    
    // Production Mode: Use actual current UID
    private val currentUid: String
        get() = auth.currentUser?.uid ?: throw IllegalStateException("User must be logged in")
    
    private val friendsList = mutableListOf<User>()
    private lateinit var spinnerAdapter: ArrayAdapter<String>

    private val LOCATION_PERMISSION_REQUEST_CODE = 100
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 101

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val lat = intent?.getDoubleExtra("latitude", 0.0) ?: 0.0
            val lng = intent?.getDoubleExtra("longitude", 0.0) ?: 0.0
            if (lat != 0.0 && lng != 0.0) {
                updateTravelerLocationOnMap(LatLng(lat, lng))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTravelerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupMapFragment()
        setupUI()
        loadFriends()
        requestLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(locationReceiver, IntentFilter("LOCATION_UPDATE"), RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(locationReceiver, IntentFilter("LOCATION_UPDATE"))
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(locationReceiver)
    }

    private fun setupMapFragment() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.setOnMapClickListener { latLng ->
            if (!isJourneyActive) {
                selectedDestination = latLng
                updateDestinationMarker(latLng)
                updateETA()
            }
        }
        getCurrentLocation()
    }

    private fun setupUI() {
        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf("Loading monitors..."))
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFriends.adapter = spinnerAdapter

        binding.btnWalking.setOnClickListener { setTravelMode("walking") }
        binding.btnDriving.setOnClickListener { setTravelMode("driving") }
        binding.btnTransit.setOnClickListener { setTravelMode("transit") }

        binding.btnStartJourney.setOnClickListener { requestMonitoring() }
        binding.btnEndJourney.setOnClickListener { endJourney() }
        binding.btnEmergency.setOnClickListener { sendEmergencyAlert() }
        
        binding.btnCancelRequest.setOnClickListener { cancelRequest() }
    }

    private fun loadFriends() {
        val uid = auth.currentUser?.uid ?: return

        database.child("users").child(uid).child("friends").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                friendsList.clear()
                val friendNames = mutableListOf<String>()
                
                val friendsCount = snapshot.childrenCount
                if (friendsCount == 0L) {
                    spinnerAdapter.clear()
                    spinnerAdapter.add("No friends added yet")
                    spinnerAdapter.notifyDataSetChanged()
                    return
                }

                snapshot.children.forEach { friendSnapshot ->
                    val friendUid = friendSnapshot.key ?: return@forEach
                    database.child("users").child(friendUid).get().addOnSuccessListener { userSnapshot ->
                        val user = userSnapshot.getValue(User::class.java)
                        user?.let {
                            friendsList.add(it)
                            friendNames.add(it.name)
                            if (friendsList.size.toLong() == friendsCount) {
                                spinnerAdapter.clear()
                                spinnerAdapter.addAll(friendNames)
                                spinnerAdapter.notifyDataSetChanged()
                            }
                        }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun setTravelMode(mode: String) {
        travelMode = mode
        binding.btnWalking.alpha = if (mode == "walking") 1.0f else 0.5f
        binding.btnDriving.alpha = if (mode == "driving") 1.0f else 0.5f
        binding.btnTransit.alpha = if (mode == "transit") 1.0f else 0.5f
        updateETA()
    }

    private fun updateETA() {
        selectedDestination?.let {
            val distance = 5.4 
            val speed = when(travelMode) {
                "walking" -> 5
                "driving" -> 40
                "transit" -> 25
                else -> 40
            }
            val minutes = (distance / speed * 60).toInt()
            estimatedArrivalTime = System.currentTimeMillis() + (minutes * 60 * 1000)
            
            binding.tvDistance.text = String.format(Locale.getDefault(), "%.1f km", distance)
            binding.tvDuration.text = String.format(Locale.getDefault(), "%d min", minutes)
            binding.etaPanel.visibility = View.VISIBLE
            binding.btnStartJourney.isEnabled = friendsList.isNotEmpty()
        }
    }

    private fun requestMonitoring() {
        if (binding.spinnerFriends.selectedItemPosition < 0 || friendsList.isEmpty()) {
            Toast.makeText(this, "Please select a monitor", Toast.LENGTH_SHORT).show()
            return
        }

        val monitor = friendsList[binding.spinnerFriends.selectedItemPosition]
        val id = UUID.randomUUID().toString()
        journeyId = id

        database.child("users").child(currentUid).child("name").get().addOnSuccessListener { nameSnapshot ->
            val myName = nameSnapshot.getValue(String::class.java) ?: "Testing Traveler"
            
            val request = MonitoringRequest(
                travelerId = currentUid,
                travelerName = myName,
                journeyId = id,
                status = "pending"
            )

            database.child("monitoring_requests").child(monitor.uid).child(id).setValue(request)
                .addOnSuccessListener {
                    binding.selectionLayout.visibility = View.GONE
                    binding.waitingLayout.visibility = View.VISIBLE
                    listenForApproval(monitor.uid, id)
                }
        }
    }

    private fun listenForApproval(monitorUid: String, id: String) {
        database.child("monitoring_requests").child(monitorUid).child(id).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val req = snapshot.getValue(MonitoringRequest::class.java)
                if (req?.status == "accepted") {
                    startJourney(monitorUid)
                } else if (req?.status == "rejected") {
                    binding.selectionLayout.visibility = View.VISIBLE
                    binding.waitingLayout.visibility = View.GONE
                    Toast.makeText(this@TravelerActivity, "Request rejected", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun startJourney(monitorUid: String) {
        val id = journeyId ?: return
        isJourneyActive = true
        journeyStartTime = System.currentTimeMillis()

        getSharedPreferences("SafeTravelPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("active_journey_id", id)
            .putString("monitor_id", monitorUid)
            .putString("traveler_uid", currentUid)
            .apply()

        val journey = Journey(
            id = id,
            destination = MyLatLng(selectedDestination!!.latitude, selectedDestination!!.longitude),
            startTime = journeyStartTime,
            estimatedArrivalTime = estimatedArrivalTime,
            isActive = true,
            travelMode = travelMode
        )

        database.child("monitor_journeys").child(monitorUid).child(id).setValue(journey)
        
        binding.waitingLayout.visibility = View.GONE
        binding.activeLayout.visibility = View.VISIBLE
        binding.tvStartTime.text = getString(R.string.started_at, SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(journeyStartTime)))
        
        startLocationTracking()
        startTimerUpdates()
    }

    private fun cancelRequest() {
        journeyId?.let { id ->
            val monitor = if (friendsList.isNotEmpty() && binding.spinnerFriends.selectedItemPosition >= 0) 
                friendsList[binding.spinnerFriends.selectedItemPosition] else null
            
            monitor?.let { m ->
                database.child("monitoring_requests").child(m.uid).child(id).removeValue()
            }
        }
        binding.selectionLayout.visibility = View.VISIBLE
        binding.waitingLayout.visibility = View.GONE
    }

    private fun endJourney() {
        val mId = getSharedPreferences("SafeTravelPrefs", Context.MODE_PRIVATE).getString("monitor_id", null)
        journeyId?.let { id ->
            if (mId != null) {
                database.child("monitor_journeys").child(mId).child(id).child("isActive").setValue(false)
            }
        }
        isJourneyActive = false
        binding.selectionLayout.visibility = View.VISIBLE
        binding.activeLayout.visibility = View.GONE
        stopLocationTracking()
    }

    private fun sendEmergencyAlert() {
        val mId = getSharedPreferences("SafeTravelPrefs", Context.MODE_PRIVATE).getString("monitor_id", null)
        if (mId != null && journeyId != null) {
            val notification = Notification(
                id = UUID.randomUUID().toString(),
                title = "🚨 EMERGENCY",
                message = "Traveler needs help!",
                timestamp = System.currentTimeMillis(),
                type = "EMERGENCY_ALERT"
            )
            database.child("notifications").child(mId).child(journeyId!!).push().setValue(notification)
        }
    }

    private fun startLocationTracking() {
        val intent = Intent(this, LocationTrackingService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopLocationTracking() {
        val intent = Intent(this, LocationTrackingService::class.java)
        stopService(intent)
    }

    private fun startTimerUpdates() {
        binding.root.post(object : Runnable {
            override fun run() {
                if (isJourneyActive) {
                    val seconds = (System.currentTimeMillis() - journeyStartTime) / 1000
                    val minutes = seconds / 60
                    val remSeconds = seconds % 60
                    binding.tvElapsedTime.text = String.format(Locale.getDefault(), "Time Elapsed: %02d:%02d", minutes, remSeconds)
                    binding.root.postDelayed(this, 1000)
                }
            }
        })
    }

    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    val currentLatLng = LatLng(it.latitude, it.longitude)
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                }
            }
        }
    }

    private fun updateTravelerLocationOnMap(latLng: LatLng) {
        if (!::mMap.isInitialized) return
        
        if (travelerMarker == null) {
            travelerMarker = mMap.addMarker(MarkerOptions()
                .position(latLng)
                .title("You")
                .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_AZURE)))
        } else {
            travelerMarker?.position = latLng
        }

        if (isJourneyActive) {
            mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
        }
    }

    private fun updateDestinationMarker(latLng: LatLng) {
        destinationMarker?.remove()
        destinationMarker = mMap.addMarker(MarkerOptions().position(latLng).title("Destination"))
    }

    private fun requestLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            getCurrentLocation()
        }
    }
}
