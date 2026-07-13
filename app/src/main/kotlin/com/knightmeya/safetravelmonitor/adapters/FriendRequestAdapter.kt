package com.knightmeya.safetravelmonitor.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.knightmeya.safetravelmonitor.databinding.ItemFriendRequestBinding
import com.knightmeya.safetravelmonitor.models.FriendRequest

class FriendRequestAdapter(
    requests: List<FriendRequest>,
    private val onAccept: (FriendRequest) -> Unit,
    private val onReject: (FriendRequest) -> Unit,
) : RecyclerView.Adapter<FriendRequestAdapter.ViewHolder>() {

    private var requests: List<FriendRequest> = requests.toList()

    class ViewHolder(val binding: ItemFriendRequestBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFriendRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val request = requests[position]
        holder.binding.tvRequesterName.text = request.fromName
        holder.binding.btnAccept.setOnClickListener { onAccept(request) }
        holder.binding.btnReject.setOnClickListener { onReject(request) }
    }

    override fun getItemCount() = requests.size

    fun updateList(newList: List<FriendRequest>) {
        val latestList = newList.toList()
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = requests.size
            override fun getNewListSize() = latestList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = requests[oldPos].fromId == latestList[newPos].fromId
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = requests[oldPos] == latestList[newPos]
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        requests = latestList
        diffResult.dispatchUpdatesTo(this)
    }
}
