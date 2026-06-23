package com.knightmeya.safetravelmonitor

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.knightmeya.safetravelmonitor.databinding.ActivityTravelerBinding
import com.knightmeya.safetravelmonitor.models.*
import java.text.SimpleDateFormat
import java.util.*

class TravelerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTravelerBinding
    private var isJourneyActive = false
    private var journeyStartTime = 0L
    private var estimatedArrivalTime = 0L
    private var selectedDestination: PointF? = null
    private var currentLocation: PointF? = null
    private var travelMode = "driving"
    private var journeyId: String? = null
    
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    
    private val currentUid: String
        get() = auth.currentUser?.uid ?: throw IllegalStateException("User must be logged in")
    
    private val friendsList = mutableListOf<User>()
    private lateinit var spinnerAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTravelerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCustomMap()
        setupUI()
        loadFriends()
    }

    private fun setupCustomMap() {
        // Load refined POIs from design
        val pois = listOf(
            MapFeature("Supermarket", "shop", 0.3f, 0.4f, Color.YELLOW),
            MapFeature("Main Hospital", "hospital", 0.7f, 0.2f, Color.RED),
            MapFeature("Local Market", "market", 0.5f, 0.8f, Color.GREEN),
            MapFeature("Post Office", "government", 0.2f, 0.7f, Color.CYAN)
        )
        binding.customMap.setPOIs(pois)

        binding.customMap.onMapClickListener = { point ->
            if (!isJourneyActive) {
                selectedDestination = point
                binding.customMap.setDestinationPosition(point.x, point.y)
                updateETA()
            } else {
                val prevLoc = currentLocation
                currentLocation = point
                binding.customMap.setTravelerPosition(point.x, point.y)
                syncPositionToFirebase(point.x, point.y)
                updateTelemetry(prevLoc, point)
            }
        }

        var isFollowEnabled = false
        binding.btnFollow.setOnClickListener {
            isFollowEnabled = !isFollowEnabled
            binding.customMap.setFollowMode(isFollowEnabled)
            binding.btnFollow.alpha = if (isFollowEnabled) 1.0f else 0.5f
        }

        var isSatellite = false
        binding.btnLayers.setOnClickListener {
            isSatellite = !isSatellite
            binding.customMap.setSatelliteView(isSatellite)
            Toast.makeText(this, if (isSatellite) "Satellite View" else "Normal View", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTelemetry(prev: PointF?, current: PointF) {
        if (prev != null) {
            val dx = current.x - prev.x
            val dy = current.y - prev.y
            val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            
            val speed = distance * 0.5f 
            findViewById<android.widget.TextView>(R.id.tvSpeed).text = String.format(Locale.US, "%.1f km/h", speed)
            
            val angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            val heading = when {
                angle in -45f..45f -> "EAST"
                angle in 45f..135f -> "SOUTH"
                angle in -135f..-45f -> "NORTH"
                else -> "WEST"
            }
            findViewById<android.widget.TextView>(R.id.tvHeading).text = heading
        }
    }

    private fun syncPositionToFirebase(x: Float, y: Float) {
        val jId = journeyId ?: return
        val mId = getSharedPreferences("SafeTravelPrefs", Context.MODE_PRIVATE).getString("monitor_id", null) ?: return
        
        val update = mapOf(
            "x" to x,
            "y" to y,
            "timestamp" to System.currentTimeMillis()
        )
        database.child("monitor_locations").child(mId).child(jId).setValue(update)
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
                
                if (!snapshot.exists() || snapshot.childrenCount == 0L) {
                    spinnerAdapter.clear()
                    spinnerAdapter.add("No monitors available")
                    spinnerAdapter.notifyDataSetChanged()
                    return
                }

                val totalFriends = snapshot.childrenCount
                var loadedCount = 0

                snapshot.children.forEach { friendSnapshot ->
                    val friendUid = friendSnapshot.key ?: return@forEach
                    database.child("users").child(friendUid).get().addOnSuccessListener { userSnapshot ->
                        val user = userSnapshot.getValue(User::class.java)
                        user?.let {
                            friendsList.add(it)
                            friendNames.add(it.name)
                        }
                        loadedCount++
                        if (loadedCount.toLong() == totalFriends) {
                            spinnerAdapter.clear()
                            spinnerAdapter.addAll(friendNames)
                            spinnerAdapter.notifyDataSetChanged()
                        }
                    }.addOnFailureListener {
                        loadedCount++
                        if (loadedCount.toLong() == totalFriends && friendsList.isEmpty()) {
                            spinnerAdapter.clear()
                            spinnerAdapter.add("Error loading friends")
                            spinnerAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                spinnerAdapter.clear()
                spinnerAdapter.add("Connection error")
                spinnerAdapter.notifyDataSetChanged()
            }
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
            val baseTime = when(travelMode) {
                "walking" -> 30
                "transit" -> 20
                else -> 15
            }
            estimatedArrivalTime = System.currentTimeMillis() + (baseTime * 60 * 1000)
            
            binding.tvDistance.text = "In Range"
            binding.tvDuration.text = "$baseTime min"
            binding.etaPanel.visibility = View.VISIBLE
            binding.btnStartJourney.isEnabled = friendsList.isNotEmpty()
        }
    }

    private fun requestMonitoring() {
        if (binding.spinnerFriends.selectedItemPosition < 0 || friendsList.isEmpty() || 
            binding.spinnerFriends.selectedItem.toString() == "No monitors available") {
            Toast.makeText(this, "Please select a valid monitor", Toast.LENGTH_SHORT).show()
            return
        }

        val monitor = friendsList[binding.spinnerFriends.selectedItemPosition]
        val id = UUID.randomUUID().toString()
        journeyId = id

        database.child("users").child(currentUid).child("name").get().addOnSuccessListener { nameSnapshot ->
            val myName = nameSnapshot.getValue(String::class.java) ?: "Traveler"
            
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
            destination = MyLatLng(selectedDestination!!.x.toDouble(), selectedDestination!!.y.toDouble()),
            startTime = journeyStartTime,
            estimatedArrivalTime = estimatedArrivalTime,
            isActive = true,
            travelMode = travelMode
        )

        database.child("monitor_journeys").child(monitorUid).child(id).setValue(journey)
        
        binding.waitingLayout.visibility = View.GONE
        binding.activeLayout.visibility = View.VISIBLE
        binding.tvStartTime.text = getString(R.string.started_at, SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(journeyStartTime)))
        
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
}
