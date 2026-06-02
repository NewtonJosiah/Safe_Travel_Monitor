package com.safetravelmonitor.app

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import java.text.SimpleDateFormat
import java.util.*

class MonitorActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private var isMonitoring = false
    private var journeyStartTime = 0L
    private var estimatedArrivalTime = 0L
    private val locationPath = mutableListOf<LatLng>()
    private val notifications = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)

        setupMapFragment()
        setupUI()
    }

    private fun setupMapFragment() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.monitorMap) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 10f))
    }

    private fun setupUI() {
        simulateJourneyMonitoring()
    }

    private fun simulateJourneyMonitoring() {
        onJourneyStarted(
            destination = LatLng(40.7128, -74.0060),
            eta = System.currentTimeMillis() + (30 * 60 * 1000)
        )
    }

    private fun onJourneyStarted(destination: LatLng, eta: Long) {
        isMonitoring = true
        journeyStartTime = System.currentTimeMillis()
        estimatedArrivalTime = eta

        mMap.addMarker(
            MarkerOptions()
                .position(destination)
                .title("Destination")
        )

        startCountdownTimer()
        addNotification("Journey Started", "Traveler has started their journey")
    }

    private fun startCountdownTimer() {
        Thread {
            while (isMonitoring) {
                val remainingTime = estimatedArrivalTime - System.currentTimeMillis()

                runOnUiThread {
                    if (remainingTime <= 0 && isMonitoring) {
                        onTravelerOverdue()
                    }
                }

                Thread.sleep(1000)
            }
        }.start()
    }

    private fun onTravelerOverdue() {
        addNotification("⚠️ Overdue Alert", "Traveler has not arrived by estimated time")
    }

    fun onTravelerLocationUpdate(location: LatLng) {
        locationPath.add(location)

        mMap.clear()
        mMap.addMarker(
            MarkerOptions()
                .position(location)
                .title("Current Location")
        )

        if (locationPath.size > 1) {
            val polylineOptions = PolylineOptions()
                .addAll(locationPath)
                .width(5f)
            mMap.addPolyline(polylineOptions)
        }

        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
    }

    fun onEmergencyAlert() {
        addNotification("🚨 Emergency Alert", "Traveler sent emergency signal!")
    }

    fun onSafeArrival() {
        isMonitoring = false
        addNotification("✅ Safe Arrival", "Traveler has safely reached destination")
    }

    private fun addNotification(title: String, message: String) {
        val notification = "$title: $message"
        notifications.add(0, notification)
    }
}
