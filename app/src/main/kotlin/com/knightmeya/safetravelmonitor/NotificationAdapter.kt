package com.knightmeya.safetravelmonitor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.knightmeya.safetravelmonitor.databinding.ItemNotificationBinding
import com.knightmeya.safetravelmonitor.models.Notification
import java.text.SimpleDateFormat
import java.util.*

class NotificationAdapter(private val notifications: List<Notification>) :
    RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemNotificationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val notification = notifications[position]
        holder.binding.tvMessage.text = notification.message
        holder.binding.tvTimestamp.text = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(notification.timestamp))
        
        // Icon logic could go here based on notification type
    }

    override fun getItemCount() = notifications.size
}
