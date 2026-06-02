package com.safetravelmonitor.app.models

import com.google.android.gms.maps.model.LatLng

data class Journey(
    val id: String,
    val destination: LatLng,
    val startTime: Long,
    val estimatedArrivalTime: Long,
    val isActive: Boolean,
    val locationHistory: MutableList<Location> = mutableListOf()
)

data class Location(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long
)

data class Notification(
    val id: String,
    val title: String,
    val message: String,
    val timestamp: Long,
    val type: NotificationType
)

enum class NotificationType {
    JOURNEY_STARTED,
    LOCATION_UPDATE,
    EMERGENCY_ALERT,
    OVERDUE_ALERT,
    SAFE_ARRIVAL
}
