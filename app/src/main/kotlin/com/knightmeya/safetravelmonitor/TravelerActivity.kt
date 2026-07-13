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
import android.util.Log
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.knightmeya.safetravelmonitor.adapters.FriendSelectionAdapter
import com.knightmeya.safetravelmonitor.databinding.ActivityTravelerBinding
import com.knightmeya.safetravelmonitor.models.*
import com.knightmeya.safetravelmonitor.utils.NotificationHelper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

class TravelerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityTravelerBinding
    private var isJourneyActive = false
    private var isRequestPending = false
    private var journeyStartTime = 0L
    private var estimatedArrivalTime = 0L
    private var selectedDestination: LatLng? = null
    private var currentLocation: LatLng? = null
    private var lastLocationTimestamp: Long = 0L
    private var travelMode = "driving"
    private var journeyId: String? = null
    private var monitorId: String? = null
    private var currentEncodedPolyline: String = ""
    private var initialRouteDistanceMeters: Float = 0f
    
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
    private var routeLine: Polyline? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var approvalListener: ValueEventListener? = null
    private var approvalRef: DatabaseReference? = null
    
    private lateinit var friendAdapter: FriendSelectionAdapter
    private val fullFriendsList = mutableListOf<User>()
    private val friendsList = mutableListOf<User>()
    private var notificationListener: ChildEventListener? = null

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
                    binding.tvElapsedTime.text = getString(R.string.remaining_time_format, hours, minutes, seconds)
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
            val batteryText = getString(R.string.battery_percentage, currentBatteryLevel)
            binding.telemetryLayout.tvBattery.text = batteryText
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
        setupFriendsList()
        restoreJourneyState()

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
        val currentTime = System.currentTimeMillis()
        currentLocation = latLng
        updateTravelerMarker(latLng)
        
        if (isJourneyActive) {
            updateJourneyProgress(prevLoc, latLng)
            syncPositionToFirebase(latLng.latitude, latLng.longitude)
        } else if (prevLoc == null) {
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
        }
        lastLocationTimestamp = currentTime
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
            // Use Road Distance for accurate progress tracking
            val remainingRoadDistance = max(0f, initialRouteDistanceMeters - totalDistanceCovered)
            
            // Check for arrival (within 50m of destination)
            Location.distanceBetween(current.latitude, current.longitude, dest.latitude, dest.longitude, results)
            val straightLineToDest = results[0]
            
            if ((straightLineToDest < 50) && (!isArrived)) {
                isArrived = true
                handleArrival()
            } else {
                updateDynamicETA(remainingRoadDistance)
            }
            
            binding.tvDistance.text = getString(R.string.distance_km_format, remainingRoadDistance / 1000f)
            updateTelemetryUI(prev, current)
        }
    }

    private fun updateDynamicETA(remainingMeters: Float) {
        // More realistic base speeds (km/h)
        val baseSpeed = when (travelMode) {
            "walking" -> 4.5f
            "transit" -> 30.0f // Average including stops
            "airplane" -> 800f
            else -> 40.0f // Average city driving
        }

        // Use a weighted average: 70% current performance, 30% theoretical base speed
        val currentSpeed = if (averageSpeedKmh > 1.0f) averageSpeedKmh else baseSpeed
        val speedToUse = (currentSpeed * 0.7f) + (baseSpeed * 0.3f)
        
        val remainingHours = (remainingMeters / 1000f) / speedToUse
        val oldETA = estimatedArrivalTime
        estimatedArrivalTime = System.currentTimeMillis() + (remainingHours * 3600000).toLong()

        // Update Target Arrival UI
        val arrivalTimeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(estimatedArrivalTime))
        binding.tvETA.text = getString(R.string.target_arrival_format, arrivalTimeStr)

        // Sync updated ETA to Monitor on every significant location update
        // This ensures monitor always has fresh ETA data
        if (isJourneyActive && (abs(estimatedArrivalTime - oldETA) > 5000)) { // Sync if diff > 5s
            val mId = monitorId
            val jId = journeyId
            if ((mId != null) && (jId != null)) {
                database.child("monitor_journeys").child(mId).child(jId).child("estimatedArrivalTime")
                    .setValue(estimatedArrivalTime)
            }
        }
    }

    private fun setupFriendsList() {
        friendAdapter = FriendSelectionAdapter(friendsList) { friend ->
            binding.etMonitorId.setText(friend.numericId)
            Toast.makeText(this, "Selected: ${friend.name}", Toast.LENGTH_SHORT).show()
        }
        binding.rvFriendsSelect.adapter = friendAdapter
        
        binding.etSearchFriends.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterFriends(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            },
        )
        
        loadFriends()
    }

    private fun filterFriends(query: String) {
        val filtered = if (query.isEmpty()) {
            fullFriendsList
        } else {
            fullFriendsList.filter { 
                it.name.contains(query, ignoreCase = true) || 
                it.numericId.contains(query) 
            }
        }
        friendsList.clear()
        friendsList.addAll(filtered.sortedBy { it.name })
        friendAdapter.updateList(friendsList)
    }

    private fun loadFriends() {
        val myUid = auth.currentUser?.uid ?: return
        database.child("users").child(myUid).child("friends").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                fullFriendsList.clear()
                val uids = snapshot.children.mapNotNull { it.key }
                if (uids.isEmpty()) {
                    friendsList.clear()
                    friendAdapter.updateList(emptyList())
                    return
                }
                
                var loaded = 0
                for (uid in uids) {
                    database.child("users").child(uid).addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snap: DataSnapshot) {
                            snap.getValue(User::class.java)?.let { fullFriendsList.add(it) }
                            loaded++
                            if (loaded == uids.size) {
                                filterFriends(binding.etSearchFriends.text.toString())
                            }
                        }
                        override fun onCancelled(error: DatabaseError) { loaded++ }
                    })
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun listenForNotifications() {
        val mId = monitorId ?: return
        val jId = journeyId ?: return
        
        // Remove existing listener if any
        notificationListener?.let {
            database.child("notifications").child(mId).child(jId).removeEventListener(it)
        }

        notificationListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val note = snapshot.getValue(Notification::class.java) ?: return
                // Show system notification
                NotificationHelper.showNotification(this@TravelerActivity, note.title, note.message)
                
                // Also show a toast for emergency alerts
                if (note.type == "EMERGENCY_ALERT") {
                    Toast.makeText(this@TravelerActivity, note.message, Toast.LENGTH_LONG).show()
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        database.child("notifications").child(mId).child(jId).addChildEventListener(notificationListener!!)
    }

    private fun handleArrival() {
        isJourneyActive = false
        initialRouteDistanceMeters = 0f
        currentEncodedPolyline = ""
        binding.tvElapsedTime.text = getString(R.string.zero_time)
        Toast.makeText(this, "You have arrived!", Toast.LENGTH_LONG).show()

        val mId = monitorId
        val jId = journeyId
        if ((mId != null) && (jId != null)) {
            val notification = Notification(
                id = UUID.randomUUID().toString(),
                title = "✅ Arrived",
                message = "Traveler has reached their destination safely.",
                timestamp = System.currentTimeMillis(),
                type = "SAFE_ARRIVAL",
            )
            database.child("notifications").child(mId).child(jId).push().setValue(notification)
            database.child("monitor_journeys").child(mId).child(jId).child("isActive").setValue(false)
            // Cleanup monitoring request
            database.child("monitoring_requests").child(mId).child(jId).removeValue()
        }

        getSharedPreferences("SafeTravelPrefs", MODE_PRIVATE).edit { clear() }

        // Return UI to selection state
        binding.activeLayout.visibility = View.GONE
        binding.selectionLayout.visibility = View.VISIBLE
        binding.searchCard.visibility = View.VISIBLE
        binding.tvMapHint.visibility = View.VISIBLE
        binding.telemetryLayout.root.visibility = View.GONE
        binding.headerTelemetryLayout.visibility = View.GONE
        
        stopService(Intent(this, LocationTrackingService::class.java))
    }

    override fun onStart() {
        super.onStart()
        try {
            ContextCompat.registerReceiver(
                this,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.w("TravelerActivity", "Failed to register battery receiver", e)
        }
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
        
        // Remove notification listener safely
        val mId = monitorId
        val jId = journeyId
        notificationListener?.let { listener ->
            if (mId != null && jId != null) {
                database.child("notifications").child(mId).child(jId).removeEventListener(listener)
            }
        }

        // Remove approval listener to prevent memory leaks
        approvalListener?.let { listener ->
            try {
                approvalRef?.removeEventListener(listener)
            } catch (e: Exception) {
                Log.w("TravelerActivity", "Failed to remove approval listener", e)
            }
            approvalListener = null
            approvalRef = null
        }
        
        // Unregister battery receiver with proper exception handling
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver wasn't registered, safe to ignore
            Log.d("TravelerActivity", "Battery receiver not registered", e)
        } catch (e: Exception) {
            Log.w("TravelerActivity", "Failed to unregister battery receiver", e)
        }
        
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

        binding.btnStartJourney.isEnabled = false // Button starts disabled
        binding.btnStartJourney.setOnClickListener { requestMonitoring() }
        binding.btnEndJourney.setOnClickListener { endJourney() }
        
        binding.btnCancelRequest.setOnClickListener { cancelRequest() }
        
        binding.btnMaximize.setOnClickListener { toggleMapMaximize() }

        // Show SOS button on Traveler side as requested
        binding.telemetryLayout.btnEmergency.visibility = View.VISIBLE
        binding.telemetryLayout.sosDivider.visibility = View.VISIBLE
        binding.telemetryLayout.btnEmergency.setOnClickListener {
            sendEmergencyAlert("SOS Emergency Alert triggered by traveler from Telemetry.")
        }

        // Set up location autocomplete
        val adapter = LocationAutocompleteAdapter(this)
        binding.etSearchLocation.setAdapter(adapter)
        
        binding.etSearchLocation.setOnItemClickListener { parent, _, position, _ ->
            val address = parent.getItemAtPosition(position) as? android.location.Address
            if (address != null) {
                val latLng = LatLng(address.latitude, address.longitude)
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                selectedDestination = latLng
                updateDestinationMarker(latLng)
                updateETA()
                
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.etSearchLocation.windowToken, 0)
            }
        }

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
        val apiKey = BuildConfig.PLACES_KEY
        
        if (apiKey.isEmpty() || apiKey == "YOUR_MAPS_API_KEY_HERE") {
            // Fallback to Geocoder if special key is missing
            searchWithGeocoder(query)
            return
        }

        Thread {
            try {
                val urlString = "https://maps.googleapis.com/maps/api/place/findplacefromtext/json?" +
                        "input=${query.replace(" ", "%20")}" +
                        "&inputtype=textquery" +
                        "&fields=formatted_address,geometry" +
                        "&key=$apiKey"
                
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(response)
                
                if (json.getString("status") == "OK") {
                    val candidate = json.getJSONArray("candidates").getJSONObject(0)
                    val loc = candidate.getJSONObject("geometry").getJSONObject("location")
                    val lat = loc.getDouble("lat")
                    val lng = loc.getDouble("lng")
                    
                    runOnUiThread {
                        val latLng = LatLng(lat, lng)
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                        selectedDestination = latLng
                        updateDestinationMarker(latLng)
                        updateETA()
                    }
                } else {
                    runOnUiThread { searchWithGeocoder(query) }
                }
            } catch (e: Exception) {
                Log.e("TravelerActivity", "Places search error", e)
                runOnUiThread { searchWithGeocoder(query) }
            }
        }.start()
    }

    private fun searchWithGeocoder(query: String) {
        val geocoder = Geocoder(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, 1)
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
        } catch (e: Exception) {
            Log.e("TravelerActivity", "Geocoder fallback error", e)
        }
    }

    private fun toggleMapMaximize() {
        val mapCard = binding.mapCard
        val root = binding.root
        
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
                (300 * resources.displayMetrics.density).toInt()
            )
            val margin = (16 * resources.displayMetrics.density).toInt()
            params.setMargins(0, 0, 0, margin)
            
            binding.travelerContainer.addView(mapCard, 0, params)
            mapCard.radius = 16 * resources.displayMetrics.density
            binding.btnMaximize.setIconResource(android.R.drawable.ic_menu_zoom)
            isMapMaximized = false
        }
    }

    private fun updateTelemetryUI(prev: LatLng?, current: LatLng) {
        val results = FloatArray(1)
        if (prev != null && lastLocationTimestamp > 0) {
            Location.distanceBetween(prev.latitude, prev.longitude, current.latitude, current.longitude, results)
            val distance = results[0]
            val timeDiffSeconds = (System.currentTimeMillis() - lastLocationTimestamp) / 1000.0f
            
            if (timeDiffSeconds > 0) {
                val speed = (distance / timeDiffSeconds) * 3.6f 
                binding.telemetryLayout.tvSpeed.text = getString(R.string.speed_kmh, speed)
            }
            
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

        val update = mapOf<String, Any>(
            "latitude" to lat,
            "longitude" to lng,
            "battery" to currentBatteryLevel,
            "timestamp" to System.currentTimeMillis(),
        )
        database.child("monitor_locations").child(mId).child(jId).updateChildren(update)
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
        
        if (travelMode == "airplane") {
            val results = FloatArray(1)
            Location.distanceBetween(current.latitude, current.longitude, dest.latitude, dest.longitude, results)
            val distance = results[0]
            initialRouteDistanceMeters = distance
            val speedKmh = 800f
            val hours = (distance / 1000f) / speedKmh
            
            estimatedArrivalTime = System.currentTimeMillis() + (hours * 3600000).toLong()
            updateEtaUI(distance, (hours * 60).toInt())
            
            // Draw straight line for airplane
            drawRoute(listOf(current, dest))
        } else {
            fetchRouteData(current, dest, travelMode)
        }
    }

    private fun updateEtaUI(distanceMeters: Float, durationMinutes: Int) {
        binding.tvDistance.text = getString(R.string.distance_km_format, distanceMeters / 1000f)
        binding.tvDuration.text = formatDuration(durationMinutes)
        
        val arrivalTimeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(estimatedArrivalTime))
        binding.tvETA.text = getString(R.string.target_arrival_format, arrivalTimeStr)

        binding.etaPanel.visibility = View.VISIBLE
        if (!isJourneyActive && !isRequestPending) {
            binding.btnStartJourney.isEnabled = true
        }
    }

    private fun fetchRouteData(origin: LatLng, dest: LatLng, mode: String) {
        val apiKey = BuildConfig.PLACES_KEY

        if (apiKey.isEmpty()) {
            Log.e("TravelerActivity", "Special API Key not found. Falling back to direct routing.")
            fallbackToDirectRouting(origin, dest)
            return
        }

        val urlString = "https://maps.googleapis.com/maps/api/directions/json?" +
                "origin=${origin.latitude},${origin.longitude}" +
                "&destination=${dest.latitude},${dest.longitude}" +
                "&mode=$mode" +
                "&departure_time=now" +
                "&traffic_model=best_guess" +
                "&key=$apiKey"

        Thread {
            try {
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                val json = JSONObject(response)
                val status = json.optString("status")
                
                if (status == "OK") {
                    val route = json.getJSONArray("routes").getJSONObject(0)
                    val leg = route.getJSONArray("legs").getJSONObject(0)
                    
                    val distance = leg.getJSONObject("distance").getLong("value").toFloat()
                    val duration = leg.getJSONObject("duration").getLong("value") / 60
                    val points = route.getJSONObject("overview_polyline").getString("points")
                    val path = decodePolyline(points)

                    runOnUiThread {
                        initialRouteDistanceMeters = distance
                        currentEncodedPolyline = points
                        estimatedArrivalTime = System.currentTimeMillis() + (duration * 60 * 1000)
                        updateEtaUI(distance, duration.toInt())
                        drawRoute(path)
                    }
                } else {
                    val errorMsg = json.optString("error_message", "No detailed error message provided.")
                    Log.w("TravelerActivity", "Directions API error: $status. Msg: $errorMsg")
                    runOnUiThread {
                        if (status == "REQUEST_DENIED") {
                            Toast.makeText(this@TravelerActivity, "Directions Error: $status. Falling back to direct routing.", Toast.LENGTH_SHORT).show()
                        }
                        fallbackToDirectRouting(origin, dest)
                    }
                }
            } catch (e: Exception) {
                Log.e("TravelerActivity", "Error fetching directions", e)
                runOnUiThread { fallbackToDirectRouting(origin, dest) }
            }
        }.start()
    }

    private fun fallbackToDirectRouting(origin: LatLng, dest: LatLng) {
        currentEncodedPolyline = ""
        val results = FloatArray(1)
        Location.distanceBetween(origin.latitude, origin.longitude, dest.latitude, dest.longitude, results)
        val distance = results[0]
        initialRouteDistanceMeters = distance
        
        val baseSpeed = when(travelMode) {
            "walking" -> 5f
            "transit" -> 40f
            else -> 60f
        }
        
        val hours = (distance / 1000f) / baseSpeed
        estimatedArrivalTime = System.currentTimeMillis() + (hours * 3600000).toLong()
        
        updateEtaUI(distance, (hours * 60).toInt())
        drawRoute(listOf(origin, dest))
    }

    private fun drawRoute(path: List<LatLng>) {
        routeLine?.remove()
        routeLine = googleMap?.addPolyline(
            PolylineOptions()
                .addAll(path)
                .color(getColor(R.color.primary))
                .width(10f)
                .pattern(listOf(com.google.android.gms.maps.model.Dash(20f), com.google.android.gms.maps.model.Gap(10f)))
        )
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

    private fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }

    private fun requestMonitoring() {
        if (isRequestPending || isJourneyActive) return
        
        if (selectedDestination == null) {
            Toast.makeText(this, "Please search and select a destination on the map first.", Toast.LENGTH_LONG).show()
            return
        }

        val monitorNumericId = binding.etMonitorId.text.toString().trim()
        if (monitorNumericId.isEmpty()) {
            Toast.makeText(this, "Please enter a monitor ID", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Prevent double-clicks immediately
        isRequestPending = true
        binding.btnStartJourney.isEnabled = false

        // Fix #9: Validate that monitor ID is numeric
        if (!monitorNumericId.matches(Regex("^\\d+$"))) {
            Toast.makeText(this, "Monitor ID must be numeric", Toast.LENGTH_SHORT).show()
            binding.btnStartJourney.isEnabled = true
            return
        }

        database.child("id_to_uid").child(monitorNumericId).get().addOnSuccessListener { snapshot ->
            val monitorUid = snapshot.getValue(String::class.java)
            Log.d("TravelerActivity", "Retrieved monitorUid: $monitorUid for numericId: $monitorNumericId")
            
            if (monitorUid == null) {
                Toast.makeText(this, "Monitor not found", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            val id = UUID.randomUUID().toString()
            journeyId = id

            database.child("users").child(currentUid).child("name").get().addOnSuccessListener { nameSnapshot ->
                val myName = nameSnapshot.getValue(String::class.java) ?: "Traveler"
                val request = MonitoringRequest(currentUid, myName, id, "pending", System.currentTimeMillis())
                Log.d("TravelerActivity", "Sending monitoring request to: $monitorUid with journeyId: $id")
                database.child("monitoring_requests").child(monitorUid).child(id).setValue(request)
                    .addOnSuccessListener {
                        binding.selectionLayout.visibility = View.GONE
                        binding.searchCard.visibility = View.GONE
                        binding.tvMapHint.visibility = View.GONE
                        binding.waitingLayout.visibility = View.VISIBLE
                        listenForApproval(id, monitorUid)
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Failed to send request: ${e.message}", Toast.LENGTH_LONG).show()
                        Log.e("TravelerActivity", "Firebase write failed for monitoring_request", e)
                    }
            }
        }.addOnFailureListener { e ->
            isRequestPending = false
            binding.btnStartJourney.isEnabled = true
            Log.e("TravelerActivity", "Error looking up monitor ID: $monitorNumericId. Check Firebase rules for 'id_to_uid' node.", e)
            val errorMsg = when {
                e.message?.contains("permission", ignoreCase = true) == true -> 
                    "Permission denied. Check Firebase rules for 'id_to_uid' node."
                else -> e.message ?: "Unknown error"
            }
            Toast.makeText(this, "Error looking up monitor: $errorMsg", Toast.LENGTH_LONG).show()
        }
    }

    private fun listenForApproval(journeyIdForRequest: String, monitorUid: String) {
        approvalListener?.let { listener ->
            try {
                approvalRef?.removeEventListener(listener)
            } catch (e: Exception) {
                Log.d("TravelerActivity", "Could not remove old approval listener", e)
            }
        }
        
        approvalRef = database.child("monitoring_requests").child(monitorUid).child(journeyIdForRequest)
        approvalListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java)
                Log.d("TravelerActivity", "Approval status update: $status")
                
                if (status == "accepted" && !isJourneyActive) {
                    isRequestPending = false
                    startJourney(monitorUid)
                } else if (status == "rejected") {
                    isRequestPending = false
                    binding.selectionLayout.visibility = View.VISIBLE
                    binding.searchCard.visibility = View.VISIBLE
                    binding.tvMapHint.visibility = View.VISIBLE
                    binding.waitingLayout.visibility = View.GONE
                    Toast.makeText(this@TravelerActivity, "Request rejected", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("TravelerActivity", "Approval listener cancelled: ${error.message}")
            }
        }
        
        approvalRef?.addValueEventListener(approvalListener!!)
    }

    private fun restoreJourneyState() {
        val prefs = getSharedPreferences("SafeTravelPrefs", MODE_PRIVATE)
        val activeId = prefs.getString("active_journey_id", null)
        if (activeId != null) {
            journeyId = activeId
            monitorId = prefs.getString("monitor_id", null)
            journeyStartTime = prefs.getLong("journey_start_time", 0)
            estimatedArrivalTime = prefs.getLong("estimated_arrival_time", 0)
            travelMode = prefs.getString("travel_mode", "driving") ?: "driving"
            
            val destLat = prefs.getFloat("dest_lat", 0f).toDouble()
            val destLng = prefs.getFloat("dest_lng", 0f).toDouble()
            if (destLat != 0.0) {
                selectedDestination = LatLng(destLat, destLng)
            }

            isJourneyActive = true
            
            // Restore UI
            binding.selectionLayout.visibility = View.GONE
            binding.waitingLayout.visibility = View.GONE
            binding.activeLayout.visibility = View.VISIBLE
            binding.searchCard.visibility = View.GONE
            binding.tvMapHint.visibility = View.GONE
            binding.telemetryLayout.root.visibility = View.VISIBLE
            binding.headerTelemetryLayout.visibility = View.VISIBLE
            binding.tvStartTime.text = getString(R.string.started_at, SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(journeyStartTime)))
            
            listenForNotifications()
            binding.root.post(timerRunnable)
        }
    }

    private fun startJourney(monitorUid: String) {
        val id = journeyId ?: return
        
        val dest = selectedDestination
        if (dest == null) {
            Toast.makeText(this, "No destination selected", Toast.LENGTH_SHORT).show()
            return
        }
        
        monitorId = monitorUid
        isJourneyActive = true
        journeyStartTime = System.currentTimeMillis()
        totalDistanceCovered = 0f
        averageSpeedKmh = 0f
        isArrived = false

        // Update UI state
        binding.selectionLayout.visibility = View.GONE
        binding.waitingLayout.visibility = View.GONE
        binding.activeLayout.visibility = View.VISIBLE
        binding.searchCard.visibility = View.GONE
        binding.tvMapHint.visibility = View.GONE
        binding.telemetryLayout.root.visibility = View.VISIBLE
        binding.headerTelemetryLayout.visibility = View.VISIBLE

        // Solution 7: Save full state including destination for continuity
        getSharedPreferences("SafeTravelPrefs", MODE_PRIVATE).edit(commit = true) {
            putString("active_journey_id", id)
            putString("monitor_id", monitorUid)
            putString("traveler_uid", currentUid)
            putLong("journey_start_time", journeyStartTime)
            putLong("estimated_arrival_time", estimatedArrivalTime)
            putString("travel_mode", travelMode)
            putFloat("dest_lat", dest.latitude.toFloat())
            putFloat("dest_lng", dest.longitude.toFloat())
        }

        val journey = Journey(
            id = id,
            destination = MyLatLng(dest.latitude, dest.longitude),
            startTime = journeyStartTime,
            estimatedArrivalTime = estimatedArrivalTime,
            isActive = true,
            travelMode = travelMode,
            routePolyline = currentEncodedPolyline
        )
        // Solution 6: Forced Initial Sync
        database.child("monitor_journeys").child(monitorUid).child(id).setValue(journey)
        currentLocation?.let { syncPositionToFirebase(it.latitude, it.longitude) }

        // Solution 4: Pass parameters via Intent Extras to Service
        val serviceIntent = Intent(this, LocationTrackingService::class.java).apply {
            putExtra("EXTRA_JOURNEY_ID", id)
            putExtra("EXTRA_MONITOR_ID", monitorUid)
            putExtra("EXTRA_TRAVELER_UID", currentUid)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        listenForNotifications()
        binding.tvStartTime.text = getString(R.string.started_at, SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(journeyStartTime)))
        binding.root.post(timerRunnable)
    }

    private fun cancelRequest() {
        isRequestPending = false
        binding.btnStartJourney.isEnabled = true
        binding.selectionLayout.visibility = View.VISIBLE
        binding.searchCard.visibility = View.VISIBLE
        binding.tvMapHint.visibility = View.VISIBLE
        binding.waitingLayout.visibility = View.GONE
    }

    private fun endJourney() {
        val mId = monitorId
        val jId = journeyId
        if ((mId != null) && (jId != null)) {
            // Send termination notification first so monitor receives it before listener is detached
            val notification = Notification(
                id = UUID.randomUUID().toString(),
                title = "🛑 Journey Terminated",
                message = "Traveler has manually ended the journey tracking.",
                timestamp = System.currentTimeMillis(),
                type = "JOURNEY_TERMINATED",
            )
            database.child("notifications").child(mId).child(jId).push().setValue(notification)

            database.child("monitor_journeys").child(mId).child(jId).child("isActive").setValue(false)
            // Cleanup monitoring request
            database.child("monitoring_requests").child(mId).child(jId).removeValue()
        }
        isJourneyActive = false
        monitorId = null
        journeyId = null
        initialRouteDistanceMeters = 0f
        currentEncodedPolyline = ""
        stopService(Intent(this, LocationTrackingService::class.java))
        
        getSharedPreferences("SafeTravelPrefs", MODE_PRIVATE).edit { clear() }

        binding.searchCard.visibility = View.VISIBLE
        binding.tvMapHint.visibility = View.VISIBLE
        binding.telemetryLayout.root.visibility = View.GONE
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
