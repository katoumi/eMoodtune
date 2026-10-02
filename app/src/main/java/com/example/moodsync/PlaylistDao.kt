package com.example.moodsync

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist ORDER BY id DESC")
    fun getAllPlaylist(): Flow<List<PlaylistItem>>

    @Query("SELECT * FROM playlist WHERE playlistId = :playlistId ORDER BY id DESC")
    fun getPlaylistById(playlistId: Long): Flow<List<PlaylistItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PlaylistItem)

    @Delete
    suspend fun delete(item: PlaylistItem)

    @Query("SELECT * FROM playlist WHERE title = :title AND artist = :artist AND duration = :duration LIMIT 1")
    suspend fun findSong(title: String, artist: String, duration: String): PlaylistItem?

    @Query("DELETE FROM playlist")
    suspend fun deleteAll()
}
