package com.knightmeya.safetravelmonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
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
import com.google.firebase.database.FirebaseDatabase
import com.knightmeya.safetravelmonitor.databinding.ActivityTravelerBinding
import com.knightmeya.safetravelmonitor.models.Journey
import com.knightmeya.safetravelmonitor.models.MyLatLng
import com.knightmeya.safetravelmonitor.models.Notification
import com.knightmeya.safetravelmonitor.models.NotificationType
import java.text.SimpleDateFormat
import java.util.*

class TravelerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityTravelerBinding
    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var isJourneyActive = false
    private var journeyStartTime = 0L
    private var estimatedArrivalTime = 0L
    private var selectedDestination: LatLng? = null
    private var travelMode = "driving"
    private var journeyId: String? = null
    
    private val database = FirebaseDatabase.getInstance().reference
    private val LOCATION_PERMISSION_REQUEST_CODE = 100
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTravelerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupMapFragment()
        setupUI()
        requestLocationPermission()
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
        binding.btnWalking.setOnClickListener { setTravelMode("walking") }
        binding.btnDriving.setOnClickListener { setTravelMode("driving") }
        binding.btnTransit.setOnClickListener { setTravelMode("transit") }

        binding.btnStartJourney.setOnClickListener { startJourney() }
        binding.btnEndJourney.setOnClickListener { endJourney() }
        binding.btnEmergency.setOnClickListener { sendEmergencyAlert() }
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
            val distance = 5.4 // Mocked distance
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
            binding.etaPanel.visibility = android.view.View.VISIBLE
            binding.btnStartJourney.isEnabled = true
        }
    }

    private fun startJourney() {
        val id = UUID.randomUUID().toString()
        journeyId = id
        isJourneyActive = true
        journeyStartTime = System.currentTimeMillis()

        // Save journey ID for the background service
        getSharedPreferences("SafeTravelPrefs", Context.MODE_PRIVATE)
            .edit().putString("active_journey_id", id).apply()

        val journey = Journey(
            id = id,
            destination = MyLatLng(selectedDestination!!.latitude, selectedDestination!!.longitude),
            startTime = journeyStartTime,
            estimatedArrivalTime = estimatedArrivalTime,
            isActive = true,
            travelMode = travelMode
        )

        // Push to Firebase
        database.child("journeys").child(id).setValue(journey)
        sendFirebaseNotification(id, "Journey Started", "Traveler is on the way!", NotificationType.JOURNEY_STARTED)

        binding.selectionLayout.visibility = android.view.View.GONE
        binding.activeLayout.visibility = android.view.View.VISIBLE
        binding.tvStartTime.text = getString(R.string.started_at, SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(journeyStartTime)))
        
        startLocationTracking()
        startTimerUpdates()
        Toast.makeText(this, "Journey started!", Toast.LENGTH_SHORT).show()
    }

    private fun endJourney() {
        journeyId?.let { id ->
            database.child("journeys").child(id).child("isActive").setValue(false)
            sendFirebaseNotification(id, "Safe Arrival", "Traveler has reached the destination!", NotificationType.SAFE_ARRIVAL)
        }
        
        isJourneyActive = false
        binding.selectionLayout.visibility = android.view.View.VISIBLE
        binding.activeLayout.visibility = android.view.View.GONE
        stopLocationTracking()
        Toast.makeText(this, "Journey ended safely!", Toast.LENGTH_SHORT).show()
    }

    private fun sendEmergencyAlert() {
        journeyId?.let { id ->
            sendFirebaseNotification(id, "🚨 EMERGENCY", "Traveler needs help!", NotificationType.EMERGENCY_ALERT)
            Toast.makeText(this, "EMERGENCY ALERT SENT!", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendFirebaseNotification(journeyId: String, title: String, message: String, type: NotificationType) {
        val notification = Notification(
            id = UUID.randomUUID().toString(),
            title = title,
            message = message,
            timestamp = System.currentTimeMillis(),
            type = type.name
        )
        database.child("notifications").child(journeyId).push().setValue(notification)
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
                    val remainingSeconds = seconds % 60
                    binding.tvElapsedTime.text = String.format(Locale.getDefault(), "Time Elapsed: %02d:%02d", minutes, remainingSeconds)
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

    private fun updateDestinationMarker(latLng: LatLng) {
        mMap.clear()
        mMap.addMarker(MarkerOptions().position(latLng).title("Destination"))
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
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation()
            }
        }
    }
}
