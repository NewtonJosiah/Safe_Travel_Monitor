package com.knightmeya.safetravelmonitor.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.knightmeya.safetravelmonitor.R
import com.knightmeya.safetravelmonitor.databinding.ItemFriendHorizontalBinding
import com.knightmeya.safetravelmonitor.models.User

class FriendSelectionAdapter(
    friends: List<User>,
    private val onItemClick: (User) -> Unit,
) : RecyclerView.Adapter<FriendSelectionAdapter.ViewHolder>() {

    private var friends: List<User> = friends.toList()

    class ViewHolder(val binding: ItemFriendHorizontalBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFriendHorizontalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val friend = friends[position]
        holder.binding.tvFriendName.text = friend.name
        
        holder.binding.ivFriendProfile.colorFilter = null // Clear any persistent tint

        if (!friend.profilePic.isNullOrEmpty()) {
            holder.binding.ivFriendProfile.load(friend.profilePic) {
                transformations(CircleCropTransformation())
                placeholder(R.drawable.app_logo)
                error(R.drawable.app_logo)
            }
        } else {
            holder.binding.ivFriendProfile.load(R.drawable.app_logo) {
                transformations(CircleCropTransformation())
            }
            holder.binding.ivFriendProfile.setColorFilter(
                "#64748B".toColorInt(),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
        }

        holder.binding.root.setOnClickListener { onItemClick(friend) }
    }

    override fun getItemCount() = friends.size

    fun updateList(newList: List<User>) {
        val latestList = newList.toList()
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = friends.size
            override fun getNewListSize() = latestList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = friends[oldPos].uid == latestList[newPos].uid
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = friends[oldPos] == latestList[newPos]
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        friends = latestList
        diffResult.dispatchUpdatesTo(this)
    }
}
