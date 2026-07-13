package com.knightmeya.safetravelmonitor

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.knightmeya.safetravelmonitor.adapters.FriendAdapter
import com.knightmeya.safetravelmonitor.adapters.FriendRequestAdapter
import com.knightmeya.safetravelmonitor.databinding.ActivityFriendsBinding
import com.knightmeya.safetravelmonitor.models.FriendRequest
import com.knightmeya.safetravelmonitor.models.User

class FriendsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFriendsBinding
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    
    private lateinit var friendAdapter: FriendAdapter
    private lateinit var requestAdapter: FriendRequestAdapter
    
    private val friendsList = mutableListOf<User>()
    private val requestsList = mutableListOf<FriendRequest>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFriendsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerViews()
        setupUI()
        loadFriends()
        loadRequests()
    }

    private fun setupRecyclerViews() {
        friendAdapter = FriendAdapter(
            friendsList,
            { friend ->
                removeFriend(friend)
            },
        )
        binding.rvFriends.layoutManager = LinearLayoutManager(this)
        binding.rvFriends.adapter = friendAdapter

        requestAdapter = FriendRequestAdapter(
            requestsList,
            { request -> acceptRequest(request) },
            { request -> rejectRequest(request) },
        )
        binding.rvRequests.layoutManager = LinearLayoutManager(this)
        binding.rvRequests.adapter = requestAdapter
    }

    private fun setupUI() {
        binding.btnAddFriend.setOnClickListener {
            val friendId = binding.etFriendId.text.toString().trim()
            if (friendId.length == 8) {
                sendFriendRequest(friendId)
            } else {
                Toast.makeText(this, "Enter valid 8-digit ID", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadFriends() {
        val myUid = auth.currentUser?.uid ?: return
        database.child("users").child(myUid).child("friends").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val friendUids = snapshot.children.mapNotNull { it.key }
                if (friendUids.isEmpty()) {
                    friendsList.clear()
                    friendAdapter.updateList(emptyList())
                    return
                }
                
                val currentLoadedFriends = mutableListOf<User>()
                var loadedCount = 0
                for (fUid in friendUids) {
                    database.child("users").child(fUid).addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(userSnap: DataSnapshot) {
                            userSnap.getValue(User::class.java)?.let { currentLoadedFriends.add(it) }
                            loadedCount++
                            if (loadedCount == friendUids.size) {
                                updateFriendsUI(currentLoadedFriends)
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {
                            Log.e("FriendsActivity", "Failed to load friend $fUid: ${error.message}")
                            loadedCount++
                            if (loadedCount == friendUids.size) {
                                updateFriendsUI(currentLoadedFriends)
                            }
                        }
                    })
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("FriendsActivity", "Failed to listen to friends: ${error.message}")
            }
        })
    }

    private fun updateFriendsUI(newFriends: List<User>) {
        friendsList.clear()
        friendsList.addAll(newFriends.sortedBy { it.name })
        friendAdapter.updateList(friendsList)
    }

    private fun loadRequests() {
        val myUid = auth.currentUser?.uid ?: return
        database.child("friend_requests").child(myUid).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                requestsList.clear()
                for (child in snapshot.children) {
                    child.getValue(FriendRequest::class.java)?.copy(fromId = child.key ?: "")?.let {
                        if (it.status == "pending") requestsList.add(it)
                    }
                }
                
                if (requestsList.isNotEmpty()) {
                    binding.tvRequestsTitle.visibility = View.VISIBLE
                    binding.rvRequests.visibility = View.VISIBLE
                } else {
                    binding.tvRequestsTitle.visibility = View.GONE
                    binding.rvRequests.visibility = View.GONE
                }
                requestAdapter.updateList(requestsList)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun sendFriendRequest(numericId: String) {
        database.child("id_to_uid").child(numericId).get().addOnSuccessListener { snapshot ->
            val targetUid = snapshot.getValue(String::class.java)
            if (targetUid == null) {
                Toast.makeText(this, "User not found", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            
            val myUid = auth.currentUser?.uid ?: return@addOnSuccessListener
            if (targetUid == myUid) {
                Toast.makeText(this, "You cannot add yourself", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            database.child("users").child(myUid).child("name").get().addOnSuccessListener { nameSnap ->
                val myName = nameSnap.getValue(String::class.java) ?: "Someone"
                val request = FriendRequest(fromId = myUid, fromName = myName, status = "pending")
                database.child("friend_requests").child(targetUid).child(myUid).setValue(request)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Request sent!", Toast.LENGTH_SHORT).show()
                        binding.etFriendId.text?.clear()
                    }
            }
        }
    }

    private fun acceptRequest(request: FriendRequest) {
        val myUid = auth.currentUser?.uid ?: return
        val friendUid = request.fromId
        
        // Reciprocal update
        val updates = hashMapOf<String, Any?>(
            "users/$myUid/friends/$friendUid" to true,
            "users/$friendUid/friends/$myUid" to true,
            "friend_requests/$myUid/$friendUid" to null
        )
        
        database.updateChildren(updates).addOnSuccessListener {
            Toast.makeText(this, "Friend added!", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener { e ->
            Log.e("FriendsActivity", "Accept failed: ${e.message}", e)
            Toast.makeText(this, "Failed to accept: ${e.message}. Check database rules.", Toast.LENGTH_LONG).show()
        }
    }

    private fun rejectRequest(request: FriendRequest) {
        val myUid = auth.currentUser?.uid ?: return
        database.child("friend_requests").child(myUid).child(request.fromId).removeValue()
    }

    private fun removeFriend(friend: User) {
        val myUid = auth.currentUser?.uid ?: return
        val friendUid = friend.uid
        
        val updates = hashMapOf<String, Any?>(
            "users/$myUid/friends/$friendUid" to null,
            "users/$friendUid/friends/$myUid" to null
        )
        database.updateChildren(updates)
    }
}
