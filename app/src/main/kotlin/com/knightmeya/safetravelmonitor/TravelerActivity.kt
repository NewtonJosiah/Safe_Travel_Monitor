package com.knightmeya.safetravelmonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.transition.TransitionManager
import com.google.android.gms.location.*
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
import com.knightmeya.safetravelmonitor.databinding.ActivityTravelerBinding
import com.knightmeya.safetravelmonitor.models.*
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.atan2

class TravelerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityTravelerBinding
    private var isJourneyActive = false
    private var journeyStartTime = 0L
    private var estimatedArrivalTime = 0L
    private var selectedDestination: LatLng? = null
    private var currentLocation: LatLng? = null
    private var travelMode = "driving"
    private var journeyId: String? = null
    private var monitorId: String? = null
    
    // Dynamic ETA fields
    private var totalDistanceCovered = 0f
    private var averageSpeedKmh = 0f
    private var isArrived = false
    private var currentBatteryLevel = 0

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    
    private var isMapMaximized = false
    private var googleMap: GoogleMap? = null
    private var travelerMarker: Marker? = null
    private var destinationMarker: Marker? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isJourneyActive) {
                val remainingMillis = estimatedArrivalTime - System.currentTimeMillis()
                if (remainingMillis <= 0) {
                    binding.tvElapsedTime.text = getString(R.string.zero_time)
                    if ((!isArrived) && (averageSpeedKmh < 1.0f)) {
                        sendEmergencyAlert("Automatic Alert: Traveler stationary and timer expired.")
                    }
                } else {
                    val totalSecs = remainingMillis / 1000
                    val hours = totalSecs / 3600
                    val minutes = (totalSecs % 3600) / 60
                    val seconds = totalSecs % 60
                    binding.tvElapsedTime.text = getString(R.string.eta_timer_format, hours, minutes, seconds)
                }
                binding.root.postDelayed(this, 1000)
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level: Int = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            currentBatteryLevel = if (scale > 0) ((level * 100) / scale.toFloat()).toInt() else 0
        }
    }

    private val currentUid: String
        get() = auth.currentUser?.uid ?: throw IllegalStateException("User must be logged in")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTravelerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()

        val mapFragment = supportFragmentManager.findFragmentById(R.id.googleMap) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupUI()
        setupImmersiveMode()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(enabled = true) {
                override fun handleOnBackPressed() {
                    if (isJourneyActive) {
                        Toast.makeText(
                            this@TravelerActivity,
                            "Journey active. Cannot exit. Use Home button to keep app running in background.",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else if (isMapMaximized) {
                        toggleMapMaximize()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                val newLatLng = LatLng(location.latitude, location.longitude)
                handleNewLocation(newLatLng)
            }
        }
    }

    private fun handleNewLocation(latLng: LatLng) {
        val prevLoc = currentLocation
        currentLocation = latLng
        updateTravelerMarker(latLng)
        
        if (isJourneyActive) {
            updateJourneyProgress(prevLoc, latLng)
            syncPositionToFirebase(latLng.latitude, latLng.longitude)
        } else if (prevLoc == null) {
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
        }
    }

    private fun updateJourneyProgress(prev: LatLng?, current: LatLng) {
        val results = FloatArray(1)
        
        if (prev != null) {
            Location.distanceBetween(prev.latitude, prev.longitude, current.latitude, current.longitude, results)
            totalDistanceCovered += results[0]
            
            val timeElapsedHours = (System.currentTimeMillis() - journeyStartTime) / 3600000f
            if (timeElapsedHours > 0.001f) {
                averageSpeedKmh = (totalDistanceCovered / 1000f) / timeElapsedHours
            }
        }

        selectedDestination?.let { dest ->
            Location.distanceBetween(current.latitude, current.longitude, dest.latitude, dest.longitude, results)
            val distanceToDest = results[0]
            
            if ((distanceToDest < 50) && (!isArrived)) {
                isArrived = true
                handleArrival()
            } else {
                updateDynamicETA(distanceToDest)
            }
            
            binding.tvDistance.text = getString(R.string.distance_km_format, distanceToDest / 1000f)
            updateTelemetryUI(prev, current)
        }
    }

    private fun updateDynamicETA(distanceToDest: Float) {
        val baseSpeed = when (travelMode) {
            "walking" -> 5f
            "transit" -> 40f
            "airplane" -> 800f
            else -> 60f
        }

        val speedToUse = if (averageSpeedKmh > 2.0f) averageSpeedKmh else baseSpeed
        val remainingHours = (distanceToDest / 1000f) / speedToUse
        val oldETA = estimatedArrivalTime
        estimatedArrivalTime = System.currentTimeMillis() + (remainingHours * 3600000).toLong()

        // Sync updated ETA to Monitor
        if (isJourneyActive && abs(estimatedArrivalTime - oldETA) > 30000) { // Sync if diff > 30s
            val mId = monitorId
            val jId = journeyId
            if ((mId != null) && (jId != null)) {
                database.child("monitor_journeys").child(mId).child(jId).child("estimatedArrivalTime")
                    .setValue(estimatedArrivalTime)
            }
        }
    }

    private fun handleArrival() {
        isJourneyActive = false
        binding.tvElapsedTime.text = getString(R.string.zero_time)
        Toast.makeText(this, "You have arrived!", Toast.LENGTH_LONG).show()

        val mId = monitorId
        if ((mId != null) && (journeyId != null)) {
            val notification = Notification(
                id = UUID.randomUUID().toString(),
                title = "✅ Arrived",
                message = "Traveler has reached their destination safely.",
                timestamp = System.currentTimeMillis(),
                type = "SAFE_ARRIVAL",
            )
            database.child("notifications").child(mId).child(journeyId!!).push().setValue(notification)
            database.child("monitor_journeys").child(mId).child(journeyId!!).child("isActive").setValue(false)
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(2000)
            .build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    override fun onStop() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        super.onStop()
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(timerRunnable)
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            googleMap?.isMyLocationEnabled = true
        }

        googleMap?.uiSettings?.apply {
            isZoomControlsEnabled = true
            isZoomGesturesEnabled = true
            isScrollGesturesEnabled = true
            isRotateGesturesEnabled = true
        }

        googleMap?.setOnMapClickListener { latLng ->
            if (!isJourneyActive) {
                selectedDestination = latLng
                updateDestinationMarker(latLng)
                updateETA()
            }
        }
    }

    private fun updateDestinationMarker(latLng: LatLng) {
        if (destinationMarker == null) {
            destinationMarker = googleMap?.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Destination")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
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
                    .title("You")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)),
            )
        } else {
            travelerMarker?.position = latLng
        }
        
        if (isJourneyActive) {
            googleMap?.animateCamera(CameraUpdateFactory.newLatLng(latLng))
        }
    }

    private fun setupUI() {
        binding.btnWalking.setOnClickListener { setTravelMode("walking") }
        binding.btnDriving.setOnClickListener { setTravelMode("driving") }
        binding.btnTransit.setOnClickListener { setTravelMode("transit") }
        binding.btnAirplane.setOnClickListener { setTravelMode("airplane") }

        binding.btnStartJourney.setOnClickListener { requestMonitoring() }
        binding.btnEndJourney.setOnClickListener { endJourney() }
        binding.btnEmergencyHeader.setOnClickListener { sendEmergencyAlert("SOS Emergency Alert triggered by traveler from Header.") }
        binding.telemetryLayout.btnEmergency.setOnClickListener { sendEmergencyAlert("SOS Emergency Alert triggered by traveler from Telemetry Overlay.") }
        
        binding.btnCancelRequest.setOnClickListener { cancelRequest() }
        
        binding.btnMaximize.setOnClickListener { toggleMapMaximize() }

        binding.etSearchLocation.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || 
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                searchLocation(v.text.toString())
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    private fun searchLocation(query: String) {
        if (query.isEmpty()) return
        val geocoder = Geocoder(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Thread {
                geocoder.getFromLocationName(query, 1) { addresses ->
                    runOnUiThread {
                        if (addresses.isNotEmpty()) {
                            val address = addresses[0]
                            val latLng = LatLng(address.latitude, address.longitude)
                            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                            selectedDestination = latLng
                            updateDestinationMarker(latLng)
                            updateETA()
                        } else {
                            Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.start()
        } else {
            @Suppress("DEPRECATION")
            Thread {
                try {
                    val addresses = geocoder.getFromLocationName(query, 1)
                    runOnUiThread {
                        if (addresses.isNullOrEmpty().not()) {
                            val address = addresses[0]
                            val latLng = LatLng(address.latitude, address.longitude)
                            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                            selectedDestination = latLng
                            updateDestinationMarker(latLng)
                            updateETA()
                        } else {
                            Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: IOException) {
                    runOnUiThread {
                        Toast.makeText(this, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun toggleMapMaximize() {
        val mapCard = binding.mapCard
        val root = binding.root as ViewGroup
        
        TransitionManager.beginDelayedTransition(root)

        if (!isMapMaximized) {
            (mapCard.parent as? ViewGroup)?.removeView(mapCard)
            root.addView(mapCard, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            mapCard.radius = 0f
            binding.btnMaximize.setIconResource(android.R.drawable.ic_menu_close_clear_cancel)
            isMapMaximized = true
        } else {
            (mapCard.parent as? ViewGroup)?.removeView(mapCard)
            binding.selectionLayout.addView(mapCard, 0)
            val params = mapCard.layoutParams
            params.height = (300 * resources.displayMetrics.density).toInt()
            mapCard.layoutParams = params
            mapCard.radius = 16 * resources.displayMetrics.density
            binding.btnMaximize.setIconResource(android.R.drawable.ic_menu_zoom)
            isMapMaximized = false
        }
    }

    private fun updateTelemetryUI(prev: LatLng?, current: LatLng) {
        val results = FloatArray(1)
        if (prev != null) {
            Location.distanceBetween(prev.latitude, prev.longitude, current.latitude, current.longitude, results)
            val distance = results[0]
            val speed = (distance / 5.0f) * 3.6f 
            binding.telemetryLayout.tvSpeed.text = getString(R.string.speed_kmh, speed)
            
            // Update Header Telemetry
            if (isJourneyActive) {
                binding.headerTelemetryLayout.visibility = View.VISIBLE
                binding.tvAvgSpeedHeader.text = String.format(Locale.getDefault(), "AVG_SPEED: %.0fKM/H", averageSpeedKmh)
                binding.tvDistCoveredHeader.text = String.format(Locale.getDefault(), "DIST. COVERED: %.1fKM", totalDistanceCovered / 1000f)
            }

            val dy = current.latitude - prev.latitude
            val dx = current.longitude - prev.longitude
            val heading = when (Math.toDegrees(atan2(dy, dx)).toFloat()) {
                in -45f..45f -> "EAST"
                in 45f..135f -> "NORTH"
                in -135f..-45f -> "SOUTH"
                else -> "WEST"
            }
            binding.telemetryLayout.tvHeading.text = heading
        }
    }

    private fun syncPositionToFirebase(lat: Double, lng: Double) {
        val jId = journeyId ?: return
        val mId = monitorId ?: return

        val update = mapOf(
            "latitude" to lat,
            "longitude" to lng,
            "battery" to currentBatteryLevel,
            "timestamp" to System.currentTimeMillis(),
        )
        database.child("monitor_locations").child(mId).child(jId).setValue(update)
    }

    private fun setTravelMode(mode: String) {
        travelMode = mode
        
        // Update button styles to show selection
        val buttons = mapOf(
            "walking" to binding.btnWalking,
            "driving" to binding.btnDriving,
            "transit" to binding.btnTransit,
            "airplane" to binding.btnAirplane,
        )

        buttons.forEach { (m, btn) ->
            if (m == mode) {
                // Selected style (Filled)
                btn.setBackgroundColor(getColor(R.color.primary))
                btn.setTextColor(getColor(R.color.primary_foreground))
            } else {
                // Unselected style (Tonal-like)
                btn.setBackgroundColor(getColor(R.color.secondary))
                btn.setTextColor(getColor(R.color.secondary_foreground))
            }
        }

        updateETA()
    }

    private fun updateETA() {
        val dest = selectedDestination ?: return
        val current = currentLocation ?: return
        
        val results = FloatArray(1)
        Location.distanceBetween(current.latitude, current.longitude, dest.latitude, dest.longitude, results)
        val distance = results[0]
        
        val baseSpeed = when(travelMode) {
            "walking" -> 5f
            "transit" -> 40f
            "airplane" -> 800f
            else -> 60f
        }
        
        val hours = (distance / 1000f) / baseSpeed
        estimatedArrivalTime = System.currentTimeMillis() + (hours * 3600000).toLong()
        
        binding.tvDistance.text = getString(R.string.distance_km_format, distance / 1000f)
        binding.tvDuration.text = formatDuration((hours * 60).toInt())
        binding.etaPanel.visibility = View.VISIBLE
        binding.btnStartJourney.isEnabled = true
    }

    private fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }

    private fun requestMonitoring() {
        val monitorNumericId = binding.etMonitorId.text.toString().trim()
        if (monitorNumericId.isEmpty()) {
            Toast.makeText(this, "Please enter a monitor ID", Toast.LENGTH_SHORT).show()
            return
        }

        database.child("id_to_uid").child(monitorNumericId).get().addOnSuccessListener { snapshot ->
            val monitorUid = snapshot.getValue(String::class.java)
            if (monitorUid == null) {
                Toast.makeText(this, "Monitor not found", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            val id = UUID.randomUUID().toString()
            journeyId = id

                    database.child("users").child(currentUid).child("name").get().addOnSuccessListener { nameSnapshot ->
                val myName = nameSnapshot.getValue(String::class.java) ?: "Traveler"
                val request = MonitoringRequest(currentUid, myName, id, "pending")
                database.child("monitoring_requests").child(monitorUid).child(id).setValue(request)
                    .addOnSuccessListener {
                        binding.selectionLayout.visibility = View.GONE
                        binding.waitingLayout.visibility = View.VISIBLE
                        listenForApproval(monitorUid)
                    }
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Error looking up monitor", Toast.LENGTH_SHORT).show()
        }
    }

    private fun listenForApproval(monitorUid: String) {
        database.child("users").child(currentUid).child("active_monitoring_approval")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val status = snapshot.child("status").getValue(String::class.java)
                    val confirmedMonitorId = snapshot.child("monitorId").getValue(String::class.java)
                    val confirmedJourneyId = snapshot.child("journeyId").getValue(String::class.java)
                    
                    if (status == "accepted" && !isJourneyActive) {
                        // Ensure we use the correct journeyId if it was changed or reassigned
                        confirmedJourneyId?.let { journeyId = it }

                        // Clear the approval node to reset state
                        snapshot.ref.removeValue()
                        startJourney(confirmedMonitorId ?: monitorUid)
                    } else if (status == "rejected") {
                        snapshot.ref.removeValue()
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
        monitorId = monitorUid
        isJourneyActive = true
        journeyStartTime = System.currentTimeMillis()
        totalDistanceCovered = 0f
        isArrived = false

        // Update UI state immediately
        binding.selectionLayout.visibility = View.GONE
        binding.waitingLayout.visibility = View.GONE
        binding.activeLayout.visibility = View.VISIBLE
        binding.searchCard.visibility = View.GONE
        binding.headerTelemetryLayout.visibility = View.VISIBLE

        // Use commit() to ensure IDs are available for the service immediately
        getSharedPreferences("SafeTravelPrefs", MODE_PRIVATE).edit().apply {
            putString("active_journey_id", id)
            putString("monitor_id", monitorUid)
            putString("traveler_uid", currentUid)
            commit()
        }

        val journey = Journey(
            id = id,
            destination = MyLatLng(selectedDestination!!.latitude, selectedDestination!!.longitude),
            startTime = journeyStartTime,
            estimatedArrivalTime = estimatedArrivalTime,
            isActive = true,
            travelMode = travelMode,
        )
        database.child("monitor_journeys").child(monitorUid).child(id).setValue(journey)
        
        // Start background service
        val serviceIntent = Intent(this, LocationTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        binding.waitingLayout.visibility = View.GONE
        binding.activeLayout.visibility = View.VISIBLE
        binding.tvStartTime.text = getString(R.string.started_at, SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(journeyStartTime)))
        
        binding.root.post(timerRunnable)
    }

    private fun cancelRequest() {
        binding.selectionLayout.visibility = View.VISIBLE
        binding.waitingLayout.visibility = View.GONE
    }

    private fun endJourney() {
        val mId = monitorId
        val jId = journeyId
        if ((mId != null) && (jId != null)) {
            database.child("monitor_journeys").child(mId).child(jId).child("isActive").setValue(false)
        }
        isJourneyActive = false
        monitorId = null
        stopService(Intent(this, LocationTrackingService::class.java))
        binding.searchCard.visibility = View.VISIBLE
        binding.headerTelemetryLayout.visibility = View.GONE
        binding.selectionLayout.visibility = View.VISIBLE
        binding.activeLayout.visibility = View.GONE
    }

    private fun sendEmergencyAlert(message: String) {
        val mId = monitorId
        if (mId != null && journeyId != null) {
            val notification = Notification(
                UUID.randomUUID().toString(),
                "🚨 EMERGENCY",
                message,
                System.currentTimeMillis(),
                "EMERGENCY_ALERT",
            )
            database.child("notifications").child(mId).child(journeyId!!).push().setValue(notification)
        }
    }
}
