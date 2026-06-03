package com.knightmeya.safetravelmonitor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.firebase.database.*
import com.knightmeya.safetravelmonitor.databinding.ActivityMonitorBinding
import com.knightmeya.safetravelmonitor.models.Journey
import com.knightmeya.safetravelmonitor.models.LocationUpdate
import com.knightmeya.safetravelmonitor.models.Notification
import java.util.*

class MonitorActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMonitorBinding
    private lateinit var mMap: GoogleMap
    private var isMonitoring = false
    private var estimatedArrivalTime = 0L
    private val locationPath = mutableListOf<LatLng>()
    private val notifications = mutableListOf<Notification>()
    private lateinit var adapter: NotificationAdapter
    
    private val database = FirebaseDatabase.getInstance().reference
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMapFragment()
        setupUI()
    }

    private fun setupMapFragment() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.monitorMap) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 2f))
    }

    private fun setupUI() {
        adapter = NotificationAdapter(notifications)
        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvNotifications.adapter = adapter

        binding.btnStartMonitoring.setOnClickListener {
            val travelerId = binding.etTravelerId.text.toString().trim()
            if (travelerId.isNotEmpty()) {
                initMonitoring(travelerId)
            } else {
                Toast.makeText(this, "Please enter a Traveler ID", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initMonitoring(monitorId: String) {
        binding.idInputCard.visibility = View.GONE
        binding.statusCard.visibility = View.VISIBLE
        
        // Listen for the most recent journey under this specific monitor ID
        database.child("monitor_journeys").child(monitorId).limitToLast(1).addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val journey = snapshot.getValue(Journey::class.java)
                journey?.let { startMonitoring(monitorId, it) }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val journey = snapshot.getValue(Journey::class.java)
                journey?.let { 
                    if (!it.isActive) stopMonitoring()
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun startMonitoring(monitorId: String, journey: Journey) {
        isMonitoring = true
        estimatedArrivalTime = journey.estimatedArrivalTime
        
        val dest = LatLng(journey.destination.latitude, journey.destination.longitude)
        mMap.clear()
        mMap.addMarker(MarkerOptions().position(dest).title("Destination"))
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(dest, 12f))
        
        // Listen for location updates
        database.child("monitor_locations").child(monitorId).child(journey.id).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val loc = snapshot.getValue(LocationUpdate::class.java)
                loc?.let { 
                    onTravelerLocationUpdate(LatLng(it.latitude, it.longitude))
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // Listen for notifications
        database.child("notifications").child(monitorId).child(journey.id).addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val note = snapshot.getValue(Notification::class.java)
                note?.let { addNotification(it) }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })

        binding.activeJourneyInfo.visibility = View.VISIBLE
        startCountdownTimer()
    }

    private fun stopMonitoring() {
        isMonitoring = false
        // Keep the UI visible but show that it's ended
        binding.tvRemainingTimeLabel.text = "Journey Ended"
        binding.tvRemainingTime.text = "00:00"
    }

    private fun addNotification(notification: Notification) {
        notifications.add(0, notification)
        adapter.notifyItemInserted(0)
        binding.rvNotifications.scrollToPosition(0)
    }

    private fun startCountdownTimer() {
        handler.post(object : Runnable {
            override fun run() {
                if (isMonitoring) {
                    val remainingMillis = estimatedArrivalTime - System.currentTimeMillis()
                    val seconds = Math.max(0, remainingMillis / 1000)
                    val minutes = seconds / 60
                    val remSeconds = seconds % 60
                    
                    binding.tvRemainingTime.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, remSeconds)
                    
                    if (remainingMillis < 0) {
                        binding.overdueWarning.visibility = View.VISIBLE
                        binding.tvRemainingTime.setTextColor(getColor(R.color.destructive))
                    } else {
                        binding.overdueWarning.visibility = View.GONE
                        binding.tvRemainingTime.setTextColor(getColor(R.color.traveler_header_start))
                    }
                    
                    handler.postDelayed(this, 1000)
                }
            }
        })
    }

    fun onTravelerLocationUpdate(location: LatLng) {
        locationPath.add(location)
        
        // Update marker (could optimize to only show current)
        mMap.addMarker(MarkerOptions().position(location).title("Current Location"))
        
        if (locationPath.size > 1) {
            mMap.addPolyline(PolylineOptions()
                .addAll(locationPath)
                .width(8f)
                .color(getColor(R.color.traveler_header_start))
                .geodesic(true))
        }
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
    }
}
