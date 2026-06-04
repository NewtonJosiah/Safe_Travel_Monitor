package com.knightmeya.safetravelmonitor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.knightmeya.safetravelmonitor.databinding.ItemFriendRequestBinding
import com.knightmeya.safetravelmonitor.models.FriendRequest

class FriendRequestAdapter(
    private val requests: List<FriendRequest>,
    private val onAccept: (FriendRequest) -> Unit,
    private val onReject: (FriendRequest) -> Unit
) : RecyclerView.Adapter<FriendRequestAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemFriendRequestBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFriendRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val request = requests[position]
        holder.binding.tvName.text = request.fromName
        
        holder.binding.btnAccept.setOnClickListener { onAccept(request) }
        holder.binding.btnReject.setOnClickListener { onReject(request) }
    }

    override fun getItemCount() = requests.size
}
