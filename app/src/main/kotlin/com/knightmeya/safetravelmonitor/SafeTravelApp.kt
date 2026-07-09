package com.knightmeya.safetravelmonitor

import android.app.Application
import com.google.firebase.database.FirebaseDatabase

class SafeTravelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Enable Firebase Offline Persistence for better reliability in poor connection areas
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        } catch (e: Exception) {
            // This can happen if Firebase is initialized multiple times or persistence is set after other DB calls
            android.util.Log.w("SafeTravelApp", "Failed to enable persistence", e)
        }
    }
}
