package com.safetravelmonitor.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.safetravelmonitor.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        binding.travellerButton.setOnClickListener {
            startActivity(Intent(this, TravelerActivity::class.java))
        }

        binding.monitorButton.setOnClickListener {
            startActivity(Intent(this, MonitorActivity::class.java))
        }
    }
}
