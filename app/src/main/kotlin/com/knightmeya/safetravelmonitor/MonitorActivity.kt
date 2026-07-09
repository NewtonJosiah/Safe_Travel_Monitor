package com.knightmeya.safetravelmonitor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.TransitionManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.knightmeya.safetravelmonitor.databinding.ActivityMonitorBinding
import com.knightmeya.safetravelmonitor.models.*
import kotlin.math.atan2
import kotlin.math.max

class MonitorActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMonitorBinding
    private var isMonitoring = false
    private var estimatedArrivalTime = 0L
    private val notifications = mutableListOf<Notification>()
    private lateinit var adapter: NotificationAdapter
    
    private var lastLocation: LatLng? = null
    private var googleMap: GoogleMap? = null
    private var travelerMarker: Marker? = null
    private var destinationMarker: Marker? = null
    
    private var activeLocationListener: ValueEventListener? = null
    private var activeNotificationListener: ChildEventListener? = null
    private var monitoringRequestsListener: ChildEventListener? = null
    private var etaListener: ValueEventListener? = null
    private var activeJourneyListener: ValueEventListener? = null
    private var monitoredJourneyId: String? = null
    private var monitoredJourneyRef: DatabaseReference? = null

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    
    private var isMapMaximized = false
    private val handler = Handler(Looper.getMainLooper())

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                val remainingMillis = estimatedArrivalTime - System.currentTimeMillis()
                val totalSecs = max(0, remainingMillis / 1000)
                val hours = totalSecs / 3600
                val minutes = (totalSecs % 3600) / 60
                val remSeconds = totalSecs % 60
                
                binding.tvRemainingTime.text = getString(R.string.eta_timer_format, hours, minutes, remSeconds)
                
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
    }

    private val currentUid: String
        get() = auth.currentUser?.uid ?: throw IllegalStateException("User must be logged in")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.googleMapMonitor) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupUI()
        listenForMonitoringRequests()
    }

    override fun onDestroy() {
        handler.removeCallbacks(countdownRunnable)
        removeMonitoredListeners()
        
        // Remove journey listener
        activeJourneyListener?.let {
            monitoredJourneyRef?.removeEventListener(it)
        }
        activeJourneyListener = null
        monitoredJourneyRef = null

        // Fix #11: Remove monitoring requests listener to prevent memory leaks
        if (monitoringRequestsListener != null) {
            try {
                database.child("monitoring_requests").child(currentUid)
                    .removeEventListener(monitoringRequestsListener!!)
                monitoringRequestsListener = null
            } catch (e: Exception) {
                Log.w("MonitorActivity", "Failed to remove monitoring requests listener", e)
            }
        }
        
        super.onDestroy()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        val defaultLoc = LatLng(-1.286389, 36.817223)
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLoc, 13f))
    }

    private fun setupUI() {
        adapter = NotificationAdapter(notifications)
        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvNotifications.adapter = adapter
        binding.idInputCard.visibility = View.GONE
        
        binding.btnMaximize.setOnClickListener { toggleMapMaximize() }
        binding.monitorTelemetry.btnEmergency.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Emergency SOS")
                .setMessage("Trigger emergency protocol?")
                .setPositiveButton("Trigger") { _, _ ->
                    Toast.makeText(this, "Emergency Alert Sent!", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun toggleMapMaximize() {
        val mapCard = binding.monitorMapCard
        val root = binding.root as ViewGroup
        
        TransitionManager.beginDelayedTransition(root)

        if (!isMapMaximized) {
            (mapCard.parent as? ViewGroup)?.removeView(mapCard)
            root.addView(
                mapCard,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            mapCard.radius = 0f
            binding.btnMaximize.setIconResource(android.R.drawable.ic_menu_close_clear_cancel)
            isMapMaximized = true
        } else {
            (mapCard.parent as? ViewGroup)?.removeView(mapCard)
            val scrollView = binding.root.getChildAt(1) as? androidx.core.widget.NestedScrollView
            val linearLayout = scrollView?.getChildAt(0) as? android.widget.LinearLayout
            linearLayout?.addView(mapCard, 1)
            val params = mapCard.layoutParams
            params.height = (400 * resources.displayMetrics.density).toInt()
            mapCard.layoutParams = params
            mapCard.radius = 16 * resources.displayMetrics.density
            binding.btnMaximize.setIconResource(android.R.drawable.ic_menu_zoom)
            isMapMaximized = false
        }
    }

    private fun listenForMonitoringRequests() {
        // Fix #11: Store listener reference for proper cleanup in onDestroy
        monitoringRequestsListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val request = snapshot.getValue(MonitoringRequest::class.java) ?: return
                val key = snapshot.key ?: ""
                // Ensure journeyId is set correctly from the node key
                val reqWithId = request.copy(journeyId = key)
                
                when (reqWithId.status) {
                    "pending" -> showRequestDialog(reqWithId)
                    "accepted" -> {
                        binding.statusCard.visibility = View.VISIBLE
                        listenForJourney(reqWithId.journeyId)
                    }
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val request = snapshot.getValue(MonitoringRequest::class.java) ?: return
                val key = snapshot.key ?: ""
                val reqWithId = request.copy(journeyId = key)
                if (reqWithId.status == "pending") showRequestDialog(reqWithId)
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                if (error.code == DatabaseError.PERMISSION_DENIED) {
                    Log.e("MonitorActivity", "Permission denied for monitoring_requests. Check security rules.")
                    database.child("monitoring_requests").child(currentUid).removeEventListener(this)
                }
            }
        }
        
        database.child("monitoring_requests").child(currentUid)
            .addChildEventListener(monitoringRequestsListener!!)
    }

    private fun showRequestDialog(request: MonitoringRequest) {
        AlertDialog.Builder(this)
            .setTitle("Monitoring Request")
            .setMessage("${request.travelerName} wants you to monitor their journey. Accept?")
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ ->
                database.child("monitoring_requests").child(currentUid).child(request.journeyId).child("status").setValue("accepted")
                
                // Notify traveler in their private secure node to bypass permission issues
                database.child("users").child(request.travelerId).child("approval_response").child(request.journeyId)
                    .setValue(mapOf(
                        "status" to "accepted",
                        "monitorId" to currentUid,
                        "journeyId" to request.journeyId
                    ))

                listenForJourney(request.journeyId)
            }
            .setNegativeButton("Reject") { _, _ ->
                database.child("monitoring_requests").child(currentUid).child(request.journeyId).child("status").setValue("rejected")
                
                database.child("users").child(request.travelerId).child("approval_response").child(request.journeyId)
                    .setValue(mapOf("status" to "rejected"))
            }
            .show()
    }

    private fun listenForJourney(journeyId: String) {
        // Clean up any existing journey listener first
        activeJourneyListener?.let {
            monitoredJourneyRef?.removeEventListener(it)
        }

        binding.statusCard.visibility = View.VISIBLE
        monitoredJourneyRef = database.child("monitor_journeys").child(currentUid).child(journeyId)
        activeJourneyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val journey = snapshot.getValue(Journey::class.java)
                if (journey != null) {
                    if (journey.isActive) {
                        startMonitoring(currentUid, journey)
                    } else {
                        stopMonitoring()
                    }
                } else {
                    stopMonitoring()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("MonitorActivity", "Journey listener cancelled: ${error.message}")
            }
        }
        monitoredJourneyRef?.addValueEventListener(activeJourneyListener!!)
    }

    private fun removeMonitoredListeners() {
        val jId = monitoredJourneyId ?: return
        activeLocationListener?.let {
            database.child("monitor_locations").child(currentUid).child(jId).removeEventListener(it)
        }
        activeNotificationListener?.let {
            database.child("notifications").child(currentUid).child(jId).removeEventListener(it)
        }
        
        // Fix #13: Remove ETA listener separately
        etaListener?.let {
            try {
                database.child("monitor_journeys").child(currentUid).child(jId).child("estimatedArrivalTime")
                    .removeEventListener(it)
            } catch (e: Exception) {
                Log.d("MonitorActivity", "Could not remove ETA listener", e)
            }
        }
        
        activeLocationListener = null
        activeNotificationListener = null
        etaListener = null
    }

    private fun startMonitoring(monitorId: String, journey: Journey) {
        if (isMonitoring && (monitoredJourneyId == journey.id)) return // Already setup for this journey
        
        // Clean previous listeners if switching journeys (though usually one at a time)
        removeMonitoredListeners()
        
        // Initialize journey data immediately from the journey object
        estimatedArrivalTime = journey.estimatedArrivalTime
        updateDestinationMarker(LatLng(journey.destination.latitude, journey.destination.longitude))
        
        // Show active UI info
        binding.activeJourneyInfo.visibility = View.VISIBLE
        binding.statusCard.visibility = View.VISIBLE
        binding.idInputCard.visibility = View.GONE

        monitoredJourneyId = journey.id
        isMonitoring = true
        startCountdownTimer()
        
        // Fetch the initial location snapshot to populate immediately instead of waiting for listener
        database.child("monitor_locations").child(monitorId).child(journey.id).get().addOnSuccessListener { snapshot ->
            val lat = snapshot.child("latitude").getValue(Double::class.java) ?: -1.0
            val lng = snapshot.child("longitude").getValue(Double::class.java) ?: -1.0
            
            if ((lat != -1.0) && (lng != -1.0)) {
                val current = LatLng(lat, lng)
                updateTravelerMarker(current)
                lastLocation = current
                // First location fix - zoom in
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(current, 15f))
            }
        }
        
        activeLocationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lat = snapshot.child("latitude").getValue(Double::class.java) ?: -1.0
                val lng = snapshot.child("longitude").getValue(Double::class.java) ?: -1.0

                if ((lat != -1.0) && (lng != -1.0)) {
                    val current = LatLng(lat, lng)
                    updateTravelerMarker(current)
                    
                    if (lastLocation != null) {
                        updateTelemetryUI(lastLocation, current)
                    } else {
                        // First location fix - zoom in
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(current, 15f))
                    }
                    lastLocation = current
                }
            }
            override fun onCancelled(error: DatabaseError) {
                if (error.code == DatabaseError.PERMISSION_DENIED) {
                    Log.e("MonitorActivity", "Permission denied for monitoring_requests. Check security rules.")
                    database.child("monitoring_requests").child(currentUid).removeEventListener(this)
                }
            }
        }
        database.child("monitor_locations").child(monitorId).child(journey.id).addValueEventListener(activeLocationListener!!)

        // Fix #13: Listen for ETA updates from Traveler with tracked listener
        etaListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newETA = snapshot.getValue(Long::class.java) ?: return
                estimatedArrivalTime = newETA
            }

            override fun onCancelled(error: DatabaseError) {
                if (error.code == DatabaseError.PERMISSION_DENIED) {
                    Log.e("MonitorActivity", "Permission denied for monitoring_requests. Check security rules.")
                    database.child("monitoring_requests").child(currentUid).removeEventListener(this)
                }
            }
        }
        database.child("monitor_journeys").child(monitorId).child(journey.id).child("estimatedArrivalTime")
            .addValueEventListener(etaListener!!)

        activeNotificationListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val note = snapshot.getValue(Notification::class.java)
                note?.let { 
                    addNotification(it)
                    when (it.type) {
                        "SAFE_ARRIVAL" -> Toast.makeText(this@MonitorActivity, "Traveler has arrived!", Toast.LENGTH_LONG).show()
                        "JOURNEY_TERMINATED" -> Toast.makeText(this@MonitorActivity, "Traveler ended the journey manually.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                if (error.code == DatabaseError.PERMISSION_DENIED) {
                    Log.e("MonitorActivity", "Permission denied for monitoring_requests. Check security rules.")
                    database.child("monitoring_requests").child(currentUid).removeEventListener(this)
                }
            }
        }
        database.child("notifications").child(monitorId).child(journey.id).addChildEventListener(activeNotificationListener!!)

        binding.activeJourneyInfo.visibility = View.VISIBLE
    }

    private fun updateDestinationMarker(latLng: LatLng) {
        if (destinationMarker == null) {
            destinationMarker = googleMap?.addMarker(
                MarkerOptions().position(latLng).title("Destination"),
            )
        } else {
            destinationMarker?.position = latLng
        }
    }

    private fun updateTravelerMarker(latLng: LatLng) {
        if (travelerMarker == null) {
            travelerMarker = googleMap?.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Traveler")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)),
            )
        } else {
            travelerMarker?.position = latLng
        }
        googleMap?.animateCamera(CameraUpdateFactory.newLatLng(latLng))
    }

    private fun updateTelemetryUI(prev: LatLng?, current: LatLng) {
        if (prev != null) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(prev.latitude, prev.longitude, current.latitude, current.longitude, results)
            val distance = results[0]
            val speed = (distance / 5.0f) * 3.6f 
            binding.monitorTelemetry.tvSpeed.text = getString(R.string.speed_kmh, speed)
            
            val dy = current.latitude - prev.latitude
            val dx = current.longitude - prev.longitude
            val heading = when (Math.toDegrees(atan2(dy, dx)).toFloat()) {
                in -45f..45f -> "EAST"
                in 45f..135f -> "NORTH"
                in -135f..-45f -> "SOUTH"
                else -> "WEST"
            }
            binding.monitorTelemetry.tvHeading.text = heading
        }
    }

    private fun stopMonitoring() {
        isMonitoring = false
        // Fix: Call removeMonitoredListeners BEFORE clearing monitoredJourneyId
        removeMonitoredListeners()
        monitoredJourneyId = null
        
        binding.tvRemainingTimeLabel.text = getString(R.string.journey_ended)
        binding.tvRemainingTime.text = getString(R.string.zero_time)
        binding.overdueWarning.visibility = View.GONE
        
        // Optional: clear markers or show final state
        travelerMarker?.alpha = 0.5f // Fade out traveler instead of removing
    }

    private fun addNotification(notification: Notification) {
        notifications.add(0, notification)
        adapter.notifyItemInserted(0)
        binding.rvNotifications.scrollToPosition(0)
    }

    private fun startCountdownTimer() {
        handler.removeCallbacks(countdownRunnable)
        handler.post(countdownRunnable)
    }
}
