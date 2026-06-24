package com.knightmeya.safetravelmonitor.models

import android.graphics.Color

data class MapFeature(
    val name: String,
    val type: String, 
    val xPercent: Float, 
    val yPercent: Float, 
    val color: Int = android.graphics.Color.GRAY,
    val iconResId: Int? = null
)
