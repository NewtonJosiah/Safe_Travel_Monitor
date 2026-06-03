package com.knightmeya.safetravelmonitor.models

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class MyLatLng(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

@IgnoreExtraProperties
data class Journey(
    val id: String = "",
    val destination: MyLatLng = MyLatLng(),
    val startTime: Long = 0,
    val estimatedArrivalTime: Long = 0,
    @field:JvmField
    val isActive: Boolean = false,
    val travelMode: String = "driving"
)

@IgnoreExtraProperties
data class LocationUpdate(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val timestamp: Long = 0
)

@IgnoreExtraProperties
data class Notification(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val timestamp: Long = 0,
    val type: String = ""
)

enum class NotificationType {
    JOURNEY_STARTED,
    LOCATION_UPDATE,
    EMERGENCY_ALERT,
    OVERDUE_ALERT,
    SAFE_ARRIVAL
}
