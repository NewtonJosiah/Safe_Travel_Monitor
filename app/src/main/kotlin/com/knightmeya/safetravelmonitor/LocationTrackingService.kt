package com.knightmeya.safetravelmonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.database.FirebaseDatabase
import com.knightmeya.safetravelmonitor.models.LocationUpdate

class LocationTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var journeyId: String? = null
    private var monitorId: String? = null
    private var travelerUid: String? = null
    private val database = FirebaseDatabase.getInstance().reference
    
    private val notificationId = 1
    private val channelId = "location_tracking"

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        
        val prefs = getSharedPreferences("SafeTravelPrefs", MODE_PRIVATE)
        journeyId = prefs.getString("active_journey_id", null)
        monitorId = prefs.getString("monitor_id", null)
        travelerUid = prefs.getString("traveler_uid", null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Fix #8 & #10: Validate journey data before starting service
        if (journeyId.isNullOrEmpty() || monitorId.isNullOrEmpty()) {
            Log.w("LocationTrackingService", "No active journey - stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        
        startForegroundService()
        startLocationUpdates()
        return START_STICKY
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, TravelerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Safe Travel Monitor")
            .setContentText("Tracking your location...")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(notificationId, notification)
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000
        ).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    // Update Firebase
                    val jId = journeyId
                    val mId = monitorId
                    if ((jId != null) && (mId != null)) {
                        val update = LocationUpdate(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            timestamp = System.currentTimeMillis()
                        )
                        database.child("monitor_locations").child(mId).child(jId).setValue(update)
                            .addOnFailureListener { e ->
                                Log.e("LocationTrackingService", "Failed to update location", e)
                            }
                    }

                    // Also send local broadcast
                    val intent = Intent("LOCATION_UPDATE")
                    intent.putExtra("latitude", location.latitude)
                    intent.putExtra("longitude", location.longitude)
                    sendBroadcast(intent)
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
            } catch (e: Exception) {
                Log.e("LocationTrackingService", "Failed to request location updates", e)
                stopSelf()
            }
        } else {
            Log.w("LocationTrackingService", "Location permission not granted")
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try {
            if (::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
        } catch (e: Exception) {
            Log.e("LocationTrackingService", "Failed to remove location updates", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
