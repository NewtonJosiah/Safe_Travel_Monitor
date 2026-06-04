package com.knightmeya.safetravelmonitor

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.knightmeya.safetravelmonitor.databinding.ActivityFriendsBinding
import com.knightmeya.safetravelmonitor.models.FriendRequest
import com.knightmeya.safetravelmonitor.models.User

class FriendsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFriendsBinding
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    private val currentUser = auth.currentUser
    
    private val requestsList = mutableListOf<FriendRequest>()
    private val friendsList = mutableListOf<User>()
    private lateinit var requestAdapter: FriendRequestAdapter
    private lateinit var friendAdapter: FriendAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFriendsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (currentUser == null) {
            finish()
            return
        }

        binding.btnSendRequest.setOnClickListener {
            sendFriendRequest()
        }

        setupRecyclerViews()
        loadData()
        loadMyInfo()
    }

    private fun loadMyInfo() {
        currentUser?.uid?.let { uid ->
            database.child("users").child(uid).child("numericId").get().addOnSuccessListener { snapshot ->
                val id = snapshot.getValue(String::class.java)
                id?.let {
                    binding.tvMyFriendsID.text = getString(R.string.your_id, it)
                }
            }
        }
    }

    private fun setupRecyclerViews() {
        requestAdapter = FriendRequestAdapter(requestsList, 
            onAccept = { request -> acceptRequest(request) },
            onReject = { request -> rejectRequest(request) }
        )
        binding.rvRequests.layoutManager = LinearLayoutManager(this)
        binding.rvRequests.adapter = requestAdapter

        friendAdapter = FriendAdapter(friendsList)
        binding.rvFriends.layoutManager = LinearLayoutManager(this)
        binding.rvFriends.adapter = friendAdapter
    }

    private fun sendFriendRequest() {
        val numericId = binding.etSearchId.text.toString().trim()
        if (numericId.isEmpty()) return

        database.child("id_to_uid").child(numericId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val targetUid = snapshot.getValue(String::class.java)
                if (targetUid == null) {
                    Toast.makeText(this@FriendsActivity, "User not found", Toast.LENGTH_SHORT).show()
                    return
                }

                val myUid = currentUser?.uid ?: return
                if (targetUid == myUid) {
                    Toast.makeText(this@FriendsActivity, "You cannot add yourself", Toast.LENGTH_SHORT).show()
                    return
                }

                database.child("users").child(myUid).child("name").get().addOnSuccessListener { nameSnapshot ->
                    val name = nameSnapshot.getValue(String::class.java) ?: "Someone"
                    val request = FriendRequest(fromId = myUid, fromName = name)
                    database.child("friend_requests").child(targetUid).child(myUid).setValue(request)
                        .addOnSuccessListener {
                            Toast.makeText(this@FriendsActivity, "Request sent!", Toast.LENGTH_SHORT).show()
                            binding.etSearchId.text?.clear()
                        }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun loadData() {
        val myUid = currentUser?.uid ?: return
        
        // Load Requests
        database.child("friend_requests").child(myUid).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                requestsList.clear()
                snapshot.children.forEach { child ->
                    val request = child.getValue(FriendRequest::class.java)
                    request?.let { requestsList.add(it) }
                }
                binding.tvRequestsHeader.visibility = if (requestsList.isEmpty()) View.GONE else View.VISIBLE
                requestAdapter.notifyDataSetChanged()
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // Load Friends
        database.child("users").child(myUid).child("friends").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                friendsList.clear()
                val friendsCount = snapshot.childrenCount
                if (friendsCount == 0L) {
                    friendAdapter.notifyDataSetChanged()
                    return
                }

                snapshot.children.forEach { friendSnapshot ->
                    val friendUid = friendSnapshot.key ?: return@forEach
                    database.child("users").child(friendUid).get().addOnSuccessListener { userSnapshot ->
                        val user = userSnapshot.getValue(User::class.java)
                        user?.let {
                            friendsList.add(it)
                            if (friendsList.size.toLong() == friendsCount) {
                                friendAdapter.notifyDataSetChanged()
                            }
                        }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun acceptRequest(request: FriendRequest) {
        val myUid = currentUser?.uid ?: return
        database.child("users").child(myUid).child("friends").child(request.fromId).setValue(true)
        database.child("users").child(request.fromId).child("friends").child(myUid).setValue(true)
        database.child("friend_requests").child(myUid).child(request.fromId).removeValue()
        Toast.makeText(this, "Friend request accepted!", Toast.LENGTH_SHORT).show()
    }

    private fun rejectRequest(request: FriendRequest) {
        val myUid = currentUser?.uid ?: return
        database.child("friend_requests").child(myUid).child(request.fromId).removeValue()
        Toast.makeText(this, "Friend request rejected", Toast.LENGTH_SHORT).show()
    }
}
