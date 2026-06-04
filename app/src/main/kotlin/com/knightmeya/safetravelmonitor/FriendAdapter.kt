package com.knightmeya.safetravelmonitor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.knightmeya.safetravelmonitor.databinding.ItemFriendBinding
import com.knightmeya.safetravelmonitor.models.User

class FriendAdapter(private val friends: List<User>) :
    RecyclerView.Adapter<FriendAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemFriendBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFriendBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val friend = friends[position]
        holder.binding.tvFriendName.text = friend.name
    }

    override fun getItemCount() = friends.size
}
