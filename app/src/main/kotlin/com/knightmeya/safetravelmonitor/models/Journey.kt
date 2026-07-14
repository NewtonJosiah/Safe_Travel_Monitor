package com.knightmeya.safetravelmonitor.models

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class User(
    val uid: String = "",
    val numericId: String = "", // e.g. 12345678
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val profilePic: String? = null,
    val friends: Map<String, Boolean> = emptyMap() // Map of friend UID -> true
)

@Suppress("unused")
@IgnoreExtraProperties
data class FriendRequest(
    val fromId: String = "",
    val fromName: String = "",
    val status: String = "pending" // pending, accepted, rejected
)

@IgnoreExtraProperties
data class MonitoringRequest(
    val travelerId: String = "",
    val travelerName: String = "",
    val journeyId: String = "",
    val status: String = "pending", // pending, accepted, rejected
    val timestamp: Long = 0
)

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
    val deadlineTime: Long = 0,
    var isActive: Boolean = false,
    val travelMode: String = "driving",
    val routePolyline: String = ""
)

@Suppress("unused")
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

@Suppress("unused")
enum class NotificationType {
    JOURNEY_STARTED,
    LOCATION_UPDATE,
    EMERGENCY_ALERT,
    OVERDUE_ALERT,
    SAFE_ARRIVAL
}
