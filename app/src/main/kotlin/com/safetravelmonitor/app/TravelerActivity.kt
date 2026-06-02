package com.safetravelmonitor.app

import android.Manifest
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
import com.safetravelmonitor.app.models.Journey
import com.safetravelmonitor.app.models.Location as LocationModel
import java.text.SimpleDateFormat
import java.util.*

class TravelerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var journey: Journey
    private var isJourneyActive = false
    private var journeyStartTime = 0L
    private var estimatedArrivalTime = 0L
    private var selectedDestination: LatLng? = null
    private val locationHistory = mutableListOf<LocationModel>()
    private val LOCATION_PERMISSION_REQUEST_CODE = 100
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_traveler)

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
            }
        }
        getCurrentLocation()
    }

    private fun setupUI() {
        // Implementation of UI setup
    }

    private fun startJourney() {
        if (selectedDestination == null) {
            Toast.makeText(this, "Please select a destination", Toast.LENGTH_SHORT).show()
            return
        }

        val estimatedMinutes = 30
        estimatedArrivalTime = System.currentTimeMillis() + (estimatedMinutes * 60 * 1000)
        journeyStartTime = System.currentTimeMillis()

        journey = Journey(
            id = UUID.randomUUID().toString(),
            destination = selectedDestination!!,
            startTime = journeyStartTime,
            estimatedArrivalTime = estimatedArrivalTime,
            isActive = true
        )

        isJourneyActive = true
        startLocationTracking()
        startTimerUpdates()
        sendJourneyStartNotification()

        Toast.makeText(this, "Journey started!", Toast.LENGTH_SHORT).show()
    }

    private fun endJourney() {
        isJourneyActive = false
        stopLocationTracking()
        sendSafeArrivalNotification()
        Toast.makeText(this, "Journey ended safely!", Toast.LENGTH_SHORT).show()
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
        Thread {
            while (isJourneyActive) {
                val elapsedTime = System.currentTimeMillis() - journeyStartTime
                val remainingTime = estimatedArrivalTime - System.currentTimeMillis()

                runOnUiThread {
                    if (remainingTime <= 0 && isJourneyActive) {
                        showOverdueAlert()
                    }
                }

                Thread.sleep(1000)
            }
        }.start()
    }

    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
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
        mMap.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Destination")
        )
    }

    private fun sendEmergencyAlert() {
        if (!isJourneyActive) return
        val message = "🚨 EMERGENCY ALERT! I need help immediately!"
        showNotification("Emergency Alert Sent", "Your emergency alert has been sent to your monitors")
        Toast.makeText(this, "Emergency alert sent!", Toast.LENGTH_LONG).show()
    }

    private fun sendJourneyStartNotification() {
        val eta = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(estimatedArrivalTime))
        val message = "Journey started to destination. ETA: $eta"
        showNotification("Journey Started", message)
    }

    private fun sendSafeArrivalNotification() {
        showNotification("Safe Arrival", "You have safely reached your destination!")
    }

    private fun showOverdueAlert() {
        isJourneyActive = false
        showNotification("Overdue Alert", "Your estimated arrival time has passed!")
    }

    private fun showNotification(title: String, message: String) {
        // Notification implementation
    }

    private fun requestLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    getCurrentLocation()
                }
            }
        }
    }
}
