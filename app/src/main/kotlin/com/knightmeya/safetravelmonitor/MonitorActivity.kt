package com.knightmeya.safetravelmonitor

import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.knightmeya.safetravelmonitor.databinding.ActivityMonitorBinding
import com.knightmeya.safetravelmonitor.models.*
import java.util.*

class MonitorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMonitorBinding
    private var isMonitoring = false
    private var estimatedArrivalTime = 0L
    private val notifications = mutableListOf<Notification>()
    private lateinit var adapter: NotificationAdapter
    
    private var lastLocation: PointF? = null
    
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    
    private val currentUid: String
        get() = auth.currentUser?.uid ?: throw IllegalStateException("User must be logged in")
        
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCustomMap()
        setupUI()
        listenForMonitoringRequests()
    }

    private fun setupCustomMap() {
        val pois = listOf(
            MapFeature("Supermarket", "shop", 0.3f, 0.4f, Color.YELLOW, R.drawable.ic_shopping_cart),
            MapFeature("Main Hospital", "hospital", 0.7f, 0.2f, Color.RED, R.drawable.ic_hospital),
            MapFeature("Local Market", "market", 0.5f, 0.8f, Color.GREEN, R.drawable.ic_store),
            MapFeature("Post Office", "government", 0.2f, 0.7f, Color.CYAN, R.drawable.ic_store)
        )
        binding.monitorMap.setPOIs(pois)

        var isSatellite = false
        binding.btnLayers.setOnClickListener {
            isSatellite = !isSatellite
            binding.monitorMap.setSatelliteView(isSatellite)
            Toast.makeText(this, if (isSatellite) "Satellite View" else "Normal View", Toast.LENGTH_SHORT).show()
        }

        var isFollowEnabled = false
        binding.btnFollow.setOnClickListener {
            isFollowEnabled = !isFollowEnabled
            binding.monitorMap.setFollowMode(isFollowEnabled)
            binding.btnFollow.alpha = if (isFollowEnabled) 1.0f else 0.5f
        }
    }

    private fun setupUI() {
        adapter = NotificationAdapter(notifications)
        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvNotifications.adapter = adapter
        binding.idInputCard.visibility = View.GONE
    }

    private fun listenForMonitoringRequests() {
        database.child("monitoring_requests").child(currentUid).addChildEventListener(object : ChildEventListener {
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
                database.child("monitoring_requests").child(currentUid).child(request.journeyId).child("status").setValue("accepted")
                listenForJourney(request.journeyId)
            }
            .setNegativeButton("Reject") { _, _ ->
                database.child("monitoring_requests").child(currentUid).child(request.journeyId).child("status").setValue("rejected")
            }
            .show()
    }

    private fun listenForJourney(journeyId: String) {
        binding.statusCard.visibility = View.VISIBLE
        database.child("monitor_journeys").child(currentUid).child(journeyId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val journey = snapshot.getValue(Journey::class.java)
                journey?.let { 
                    if (it.isActive) {
                        startMonitoring(currentUid, it)
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
        
        binding.monitorMap.setDestinationPosition(journey.destination.latitude.toFloat(), journey.destination.longitude.toFloat())
        
        database.child("monitor_locations").child(monitorId).child(journey.id).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val x = snapshot.child("x").getValue(Float::class.java) ?: -1f
                val y = snapshot.child("y").getValue(Float::class.java) ?: -1f
                val battery = snapshot.child("battery").getValue(Int::class.java) ?: -1
                
                if (battery != -1) {
                    findViewById<TextView>(R.id.tvBattery).text = "$battery%"
                    findViewById<TextView>(R.id.tvBattery).setTextColor(
                        if (battery < 20) Color.RED else Color.parseColor("#22C55E")
                    )
                }

                if (x != -1f && y != -1f) {
                    val current = PointF(x, y)
                    updateTelemetry(lastLocation, current)
                    lastLocation = current
                    onTravelerLocationUpdate(x, y)
                }
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

    private fun updateTelemetry(prev: PointF?, current: PointF) {
        if (prev != null) {
            val dx = current.x - prev.x
            val dy = current.y - prev.y
            val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            
            val speed = distance * 0.5f 
            findViewById<TextView>(R.id.tvSpeed).text = String.format(Locale.US, "%.1f km/h", speed)
            
            val angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            val heading = when {
                angle in -45f..45f -> "EAST"
                angle in 45f..135f -> "SOUTH"
                angle in -135f..-45f -> "NORTH"
                else -> "WEST"
            }
            findViewById<TextView>(R.id.tvHeading).text = heading
        }
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

    private fun onTravelerLocationUpdate(x: Float, y: Float) {
        binding.monitorMap.setTravelerPosition(x, y)
    }
}
