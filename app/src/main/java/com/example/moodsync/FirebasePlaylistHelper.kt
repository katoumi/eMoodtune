package com.example.moodsync

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope

object FirebasePlaylistSyncHelper {

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    fun syncLocalPlaylistToCloud(
        context: Context,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val uid = FirebaseAuthHelper.currentUserId()
        if (uid == null) {
            onError?.invoke("User not logged in")
            return
        }

        val database = AppDatabase.getDatabase(context)
        val playlistMetadataDao = database.playlistMetadataDao()
        val playlistDao = database.playlistDao()

        MainScope().launch(Dispatchers.IO) {
            try {
                val allPlaylists = playlistMetadataDao.getAllPlaylists().first()
                
                val batch = db.batch()
                val userRef = db.collection("users").document(uid)

                allPlaylists.forEach { playlist ->
                    val metaData = hashMapOf(
                        "name" to playlist.name,
                        "createdAt" to playlist.createdAt
                    )
                    batch.set(userRef.collection("playlists").document(playlist.id.toString()), metaData)

                    val songsInPlaylist = playlistDao.getPlaylistById(playlist.id).first()
                    val songsRef = userRef.collection("playlists").document(playlist.id.toString()).collection("songs")
                    
                    songsInPlaylist.forEach { song ->
                        val docId = "${song.title}_${song.artist}"
                            .lowercase()
                            .replace("/", "_")
                            .replace("\\", "_")
                            .replace(" ", "_")

                        val songData = hashMapOf(
                            "title" to song.title,
                            "artist" to song.artist,
                            "duration" to song.duration,
                            "emotion" to song.emotion,
                            "syncedAt" to System.currentTimeMillis()
                        )
                        batch.set(songsRef.document(docId), songData)
                    }
                }

                batch.commit().addOnSuccessListener {
                    onSuccess?.invoke()
                }.addOnFailureListener {
                    onError?.invoke(it.message ?: "Sync failed")
                }
            } catch (e: Exception) {
                onError?.invoke(e.message ?: "Local fetch failed")
            }
        }
    }
}
