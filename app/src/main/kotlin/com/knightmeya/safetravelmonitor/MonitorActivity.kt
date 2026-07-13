package com.knightmeya.safetravelmonitor

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.knightmeya.safetravelmonitor.databinding.ActivityMonitorBinding
import com.knightmeya.safetravelmonitor.models.*
import com.knightmeya.safetravelmonitor.utils.NotificationHelper
import kotlin.math.atan2
import kotlin.math.max

class MonitorActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMonitorBinding
    private var isMonitoring = false
    private var estimatedArrivalTime = 0L
    private val notifications = mutableListOf<Notification>()
    private lateinit var adapter: NotificationAdapter
    
    private var lastLocation: LatLng? = null
    private var lastLocationTimestamp: Long = 0L
    private var googleMap: GoogleMap? = null
    private var travelerMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var routeLine: Polyline? = null
    private var pendingTravelerLocation: LatLng? = null
    private var pendingDestinationLocation: LatLng? = null
    
    private var activeLocationListener: ValueEventListener? = null
    private var activeNotificationListener: ChildEventListener? = null
    private var monitoringRequestsListener: ChildEventListener? = null
    private var etaListener: ValueEventListener? = null
    private var activeJourneyListener: ValueEventListener? = null
    private var monitoredJourneyId: String? = null
    private var monitoredJourneyRef: DatabaseReference? = null
    private var lastPolyline: String? = null

    private var monitoredTravelerId: String? = null

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
                
                binding.tvRemainingTime.text = getString(R.string.timer_format, hours, minutes, remSeconds)
                
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
        
        val incomingJourneyId = intent.getStringExtra("EXTRA_JOURNEY_ID")
        if (incomingJourneyId != null) {
            // Priority: Explicitly requested journey (e.g., from notification/dialog)
            listenForJourney(incomingJourneyId)
        }
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
        Log.d("MonitorActivity", "Map ready")
        val defaultLoc = LatLng(-1.286389, 36.817223)
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLoc, 13f))
        
        // Apply any locations that arrived before map was ready
        pendingTravelerLocation?.let { updateTravelerMarker(it) }
        pendingDestinationLocation?.let { updateDestinationMarker(it) }
    }

    private fun setupUI() {
        adapter = NotificationAdapter(notifications)
        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvNotifications.adapter = adapter
        binding.idInputCard.visibility = View.GONE
        
        binding.btnMaximize.setOnClickListener { toggleMapMaximize() }
        binding.monitorTelemetry.btnEmergency.setOnClickListener {
            val jId = monitoredJourneyId
            if (jId != null) {
                AlertDialog.Builder(this)
                    .setTitle("🚨 Emergency SOS")
                    .setMessage("Trigger emergency protocol? This will:\n1. Notify the traveler\n2. Log the SOS in the database\n3. Open your dialer with emergency services (911)")
                    .setPositiveButton("Confirm & Call") { _, _ ->
                        sendEmergencyAlert()
                        
                        // Launch dialer for emergency services
                        try {
                            val intent = Intent(Intent.ACTION_DIAL)
                            intent.data = "tel:911".toUri()
                            startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(this, "Unable to open dialer", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                Toast.makeText(this, "No active journey to alert.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendEmergencyAlert() {
        val jId = monitoredJourneyId ?: return
        val message = "SOS Alert triggered by Monitor. Calling emergency services."
        Log.d("MonitorActivity", "Sending SOS: $message")
        
        val notification = Notification(
            id = java.util.UUID.randomUUID().toString(),
            title = "🚨 MONITOR SOS",
            message = message,
            timestamp = System.currentTimeMillis(),
            type = "EMERGENCY_ALERT"
        )
        // Per revised rules, Monitor can write to notifications node
        database.child("notifications").child(currentUid).child(jId).push().setValue(notification)
    }

    private fun toggleMapMaximize() {
        val mapCard = binding.monitorMapCard
        val root = binding.root as ViewGroup
        
        if (!isMapMaximized) {
            // Maximize
            (mapCard.parent as? ViewGroup)?.removeView(mapCard)
            
            val params = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT
            )
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            
            root.addView(mapCard, params)
            mapCard.radius = 0f
            binding.btnMaximize.setIconResource(android.R.drawable.ic_menu_close_clear_cancel)
            isMapMaximized = true
        } else {
            // Minimize
            (mapCard.parent as? ViewGroup)?.removeView(mapCard)
            
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (400 * resources.displayMetrics.density).toInt()
            )
            val margin = (24 * resources.displayMetrics.density).toInt()
            params.setMargins(0, 0, 0, margin)
            
            binding.monitorContainer.addView(mapCard, 1, params)
            mapCard.radius = 16 * resources.displayMetrics.density
            binding.btnMaximize.setIconResource(android.R.drawable.ic_menu_zoom)
            isMapMaximized = false
        }
    }

    private fun listenForMonitoringRequests() {
        // Prevent redundant listener attachment
        if (monitoringRequestsListener != null) {
            try {
                database.child("monitoring_requests").child(currentUid)
                    .removeEventListener(monitoringRequestsListener!!)
            } catch (_: Exception) {}
        }

        monitoringRequestsListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val key = snapshot.key ?: return
                val request = snapshot.getValue(MonitoringRequest::class.java)?.copy(journeyId = key) ?: return
                
                when (request.status) {
                    "pending" -> {
                        // Only show dialog for fresh requests (last 10 mins)
                        val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000)
                        if (request.timestamp >= tenMinutesAgo) {
                            showRequestDialog(request)
                        }
                    }
                    "accepted" -> {
                        // Automatically resume monitoring for any accepted journey
                        Log.d("MonitorActivity", "Resuming monitor for accepted journey: ${request.journeyId}")
                        listenForJourney(request.journeyId)
                    }
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val key = snapshot.key ?: return
                val request = snapshot.getValue(MonitoringRequest::class.java)?.copy(journeyId = key) ?: return
                if (request.status == "pending") {
                    val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000)
                    if (request.timestamp >= tenMinutesAgo) {
                        showRequestDialog(request)
                    }
                } else if (request.status == "accepted") {
                    listenForJourney(request.journeyId)
                }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val key = snapshot.key ?: return
                if (key == monitoredJourneyId) {
                    Log.d("MonitorActivity", "Active request removed from DB, stopping monitor")
                    stopMonitoring()
                }
            }
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
                listenForJourney(request.journeyId)
            }
            .setNegativeButton("Reject") { _, _ ->
                database.child("monitoring_requests").child(currentUid).child(request.journeyId).child("status").setValue("rejected")
            }
            .show()
    }

    private fun listenForJourney(journeyId: String) {
        // Prevent redundant listener attachment
        if (monitoredJourneyId == journeyId && activeJourneyListener != null) {
            Log.d("MonitorActivity", "Already listening for journey: $journeyId")
            return
        }

        // Clean up any existing journey listener first
        activeJourneyListener?.let {
            monitoredJourneyRef?.removeEventListener(it)
        }

        Log.d("MonitorActivity", "Listening for journey: $journeyId under monitor: $currentUid")
        binding.statusCard.visibility = View.VISIBLE
        monitoredJourneyRef = database.child("monitor_journeys").child(currentUid).child(journeyId)
        activeJourneyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val journey = snapshot.getValue(Journey::class.java)
                val travelerId = snapshot.child("travelerId").getValue(String::class.java)
                monitoredTravelerId = travelerId

                Log.d("MonitorActivity", "Journey data changed: exists=${snapshot.exists()}, isActive=${journey?.isActive}")
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
            } catch (_: Exception) {
                Log.d("MonitorActivity", "Could not remove ETA listener")
            }
        }
        
        activeLocationListener = null
        activeNotificationListener = null
        etaListener = null
    }

    private fun startMonitoring(monitorId: String, journey: Journey) {
        Log.d("MonitorActivity", "Starting monitoring for journey: ${journey.id}")
        
        // Solution 5: Consolidate listeners - clean slate every time
        removeMonitoredListeners()
        isMonitoring = false // Reset state before setting up new one
        
        // Initialize journey data immediately from the journey object
        estimatedArrivalTime = journey.estimatedArrivalTime
        updateDestinationMarker(LatLng(journey.destination.latitude, journey.destination.longitude))
        updateRouteLine(journey.routePolyline)
        
        // Show active UI info
        binding.statusCard.visibility = View.VISIBLE
        binding.activeJourneyInfo.visibility = View.VISIBLE
        binding.idInputCard.visibility = View.GONE

        monitoredJourneyId = journey.id
        isMonitoring = true
        startCountdownTimer()

        // UI Badges Reset
        binding.badgeActive.visibility = View.VISIBLE
        binding.badgeEnded.visibility = View.GONE
        binding.tvRemainingTimeLabel.visibility = View.VISIBLE
        binding.tvRemainingTimeLabel.text = getString(R.string.time_remaining)
        binding.tvRemainingTime.setTextColor(getColor(R.color.traveler_header_start))
        
        // Solution 3: Defensive Data Casting (Double/Long safety)
        database.child("monitor_locations").child(monitorId).child(journey.id).get().addOnSuccessListener { snapshot ->
            val lat = (snapshot.child("latitude").value as? Number)?.toDouble() ?: -1.0
            val lng = (snapshot.child("longitude").value as? Number)?.toDouble() ?: -1.0
            val timestamp = (snapshot.child("timestamp").value as? Number)?.toLong() ?: 0L
            val battery = (snapshot.child("battery").value as? Number)?.toInt() ?: -1
            Log.d("MonitorActivity", "Initial location fetch: $lat, $lng at $timestamp, battery: $battery")
            
            if ((lat != -1.0) && (lng != -1.0)) {
                val current = LatLng(lat, lng)
                updateTravelerMarker(current)
                lastLocation = current
                lastLocationTimestamp = timestamp
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(current, 15f))
            }

            if (battery != -1) {
                binding.monitorTelemetry.tvBattery.text = getString(R.string.battery_percentage, battery)
            }
        }.addOnFailureListener { e ->
            Log.e("MonitorActivity", "Failed to fetch initial location", e)
        }
        
        activeLocationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lat = (snapshot.child("latitude").value as? Number)?.toDouble() ?: -1.0
                val lng = (snapshot.child("longitude").value as? Number)?.toDouble() ?: -1.0
                val timestamp = (snapshot.child("timestamp").value as? Number)?.toLong() ?: 0L
                val battery = (snapshot.child("battery").value as? Number)?.toInt() ?: -1
                Log.d("MonitorActivity", "Location update: $lat, $lng at $timestamp, battery: $battery")

                if ((lat != -1.0) && (lng != -1.0)) {
                    val current = LatLng(lat, lng)
                    updateTravelerMarker(current)
                    
                    if (lastLocation != null && lastLocationTimestamp > 0 && timestamp > lastLocationTimestamp) {
                        updateTelemetryUI(lastLocation!!, current, lastLocationTimestamp, timestamp)
                    } else if (lastLocation == null) {
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(current, 15f))
                    }
                    lastLocation = current
                    lastLocationTimestamp = timestamp
                }

                if (battery != -1) {
                    binding.monitorTelemetry.tvBattery.text = getString(R.string.battery_percentage, battery)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("MonitorActivity", "Location listener cancelled: ${error.message}")
            }
        }
        database.child("monitor_locations").child(monitorId).child(journey.id).addValueEventListener(activeLocationListener!!)

        etaListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newETA = (snapshot.value as? Number)?.toLong() ?: return
                Log.d("MonitorActivity", "Received ETA update: $newETA")
                estimatedArrivalTime = newETA
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("MonitorActivity", "ETA listener cancelled: ${error.message}")
            }
        }
        database.child("monitor_journeys").child(monitorId).child(journey.id).child("estimatedArrivalTime")
            .addValueEventListener(etaListener!!)

        activeNotificationListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val note = snapshot.getValue(Notification::class.java)
                note?.let { 
                    addNotification(it)
                    // Show system notification
                    NotificationHelper.showNotification(this@MonitorActivity, it.title, it.message)

                    when (it.type) {
                        "SAFE_ARRIVAL" -> {
                            Toast.makeText(this@MonitorActivity, "Traveler has arrived!", Toast.LENGTH_LONG).show()
                            stopMonitoring()
                        }
                        "JOURNEY_TERMINATED" -> {
                            Toast.makeText(this@MonitorActivity, "Traveler ended the journey manually.", Toast.LENGTH_LONG).show()
                            stopMonitoring()
                        }
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
        if (googleMap == null) {
            pendingDestinationLocation = latLng
            return
        }
        pendingDestinationLocation = null
        if (destinationMarker == null) {
            destinationMarker = googleMap?.addMarker(
                MarkerOptions().position(latLng).title("Destination"),
            )
        } else {
            destinationMarker?.position = latLng
        }
        updateRouteLine()
    }

    private fun updateTravelerMarker(latLng: LatLng) {
        Log.d("MonitorActivity", "Updating traveler marker at: ${latLng.latitude}, ${latLng.longitude}")
        if (googleMap == null) {
            Log.d("MonitorActivity", "GoogleMap not ready, saving pending location")
            pendingTravelerLocation = latLng
            return
        }
        pendingTravelerLocation = null
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
        updateRouteLine()
    }

    private fun updateRouteLine(polyline: String? = null) {
        val start = travelerMarker?.position
        val end = destinationMarker?.position
        
        val polyToUse = polyline ?: lastPolyline
        
        if (polyToUse != null && polyToUse.isNotEmpty()) {
            lastPolyline = polyToUse
            val path = decodePolyline(polyToUse)
            if (routeLine == null) {
                routeLine = googleMap?.addPolyline(
                    com.google.android.gms.maps.model.PolylineOptions()
                        .addAll(path)
                        .color(getColor(R.color.primary))
                        .width(8f)
                        .pattern(listOf(com.google.android.gms.maps.model.Dash(20f), com.google.android.gms.maps.model.Gap(10f)))
                )
            } else {
                routeLine?.points = path
            }
            return
        }

        if (start != null && end != null) {
            if (routeLine == null) {
                routeLine = googleMap?.addPolyline(
                    com.google.android.gms.maps.model.PolylineOptions()
                        .add(start, end)
                        .color(getColor(R.color.primary))
                        .width(8f)
                        .pattern(listOf(com.google.android.gms.maps.model.Dash(20f), com.google.android.gms.maps.model.Gap(10f)))
                )
            } else {
                routeLine?.points = listOf(start, end)
            }
        }
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng
            poly.add(LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5))
        }
        return poly
    }

    private fun updateTelemetryUI(prev: LatLng, current: LatLng, prevTime: Long, currentTime: Long) {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(prev.latitude, prev.longitude, current.latitude, current.longitude, results)
        val distance = results[0]
        
        val timeDiffSeconds = (currentTime - prevTime) / 1000.0f
        if (timeDiffSeconds > 0) {
            val speed = (distance / timeDiffSeconds) * 3.6f 
            binding.monitorTelemetry.tvSpeed.text = getString(R.string.speed_kmh, speed)
        }
        
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

    private fun stopMonitoring() {
        isMonitoring = false
        handler.removeCallbacks(countdownRunnable)
        // Fix: Call removeMonitoredListeners BEFORE clearing monitoredJourneyId
        removeMonitoredListeners()
        monitoredJourneyId = null
        lastLocation = null
        lastLocationTimestamp = 0L
        lastPolyline = null
        
        binding.badgeActive.visibility = View.GONE
        binding.badgeEnded.visibility = View.VISIBLE
        
        binding.tvRemainingTimeLabel.visibility = View.INVISIBLE
        binding.tvRemainingTime.text = getString(R.string.zero_time)
        binding.tvRemainingTime.setTextColor(getColor(R.color.muted_foreground))
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
