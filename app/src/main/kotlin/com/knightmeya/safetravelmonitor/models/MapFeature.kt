package com.knightmeya.safetravelmonitor.models

import android.graphics.Color

data class MapFeature(
    val name: String,
    val type: String, // shop, hospital, market, etc.
    val xPercent: Float, // 0.0 - 1.0 relative to image width
    val yPercent: Float, // 0.0 - 1.0 relative to image height
    val color: Int = Color.GRAY
)
