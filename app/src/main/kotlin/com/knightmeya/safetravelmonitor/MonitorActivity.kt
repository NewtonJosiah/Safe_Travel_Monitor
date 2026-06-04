package com.knightmeya.safetravelmonitor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.knightmeya.safetravelmonitor.databinding.ActivityMonitorBinding
import com.knightmeya.safetravelmonitor.models.*
import java.util.*

class MonitorActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMonitorBinding
    private lateinit var mMap: GoogleMap
    private var isMonitoring = false
    private var estimatedArrivalTime = 0L
    private val locationPath = mutableListOf<LatLng>()
    private val notifications = mutableListOf<Notification>()
    private lateinit var adapter: NotificationAdapter
    
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    private val currentUser = auth.currentUser
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (currentUser == null) {
            finish()
            return
        }

        setupMapFragment()
        setupUI()
        listenForMonitoringRequests()
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
        binding.idInputCard.visibility = View.GONE
    }

    private fun listenForMonitoringRequests() {
        val myUid = currentUser?.uid ?: return
        database.child("monitoring_requests").child(myUid).addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val request = snapshot.getValue(MonitoringRequest::class.java) ?: return
                if (request.status == "pending") {
                    showRequestDialog(request)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val request = snapshot.getValue(MonitoringRequest::class.java) ?: return
                if (request.status == "pending") {
                    showRequestDialog(request)
                }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun showRequestDialog(request: MonitoringRequest) {
        AlertDialog.Builder(this)
            .setTitle("Monitoring Request")
            .setMessage("${request.travelerName} wants you to monitor their journey. Accept?")
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ ->
                val myUid = currentUser?.uid ?: return@setPositiveButton
                database.child("monitoring_requests").child(myUid).child(request.journeyId).child("status").setValue("accepted")
                listenForJourney(request.journeyId)
            }
            .setNegativeButton("Reject") { _, _ ->
                val myUid = currentUser?.uid ?: return@setNegativeButton
                database.child("monitoring_requests").child(myUid).child(request.journeyId).child("status").setValue("rejected")
            }
            .show()
    }

    private fun listenForJourney(journeyId: String) {
        binding.statusCard.visibility = View.VISIBLE
        val myUid = currentUser?.uid ?: return
        
        database.child("monitor_journeys").child(myUid).child(journeyId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val journey = snapshot.getValue(Journey::class.java)
                journey?.let { 
                    if (it.isActive) {
                        startMonitoring(myUid, it)
                    } else {
                        stopMonitoring()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun startMonitoring(monitorId: String, journey: Journey) {
        if (isMonitoring) return
        isMonitoring = true
        estimatedArrivalTime = journey.estimatedArrivalTime
        
        val dest = LatLng(journey.destination.latitude, journey.destination.longitude)
        mMap.clear()
        mMap.addMarker(MarkerOptions().position(dest).title("Destination"))
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(dest, 12f))
        
        database.child("monitor_locations").child(monitorId).child(journey.id).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val loc = snapshot.getValue(LocationUpdate::class.java)
                loc?.let { onTravelerLocationUpdate(LatLng(it.latitude, it.longitude)) }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

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
        binding.tvRemainingTimeLabel.text = "Journey Ended"
        binding.tvRemainingTime.text = "00:00"
        binding.overdueWarning.visibility = View.GONE
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
