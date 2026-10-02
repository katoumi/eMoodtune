package com.example.moodsync

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistMetadataDao {
    @Query("SELECT * FROM playlist_metadata ORDER BY id ASC")
    fun getAllPlaylists(): Flow<List<PlaylistMetadata>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: PlaylistMetadata): Long

    @Update
    suspend fun update(playlist: PlaylistMetadata)

    @Delete
    suspend fun delete(playlist: PlaylistMetadata)

    @Query("SELECT * FROM playlist_metadata WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PlaylistMetadata?

    @Query("SELECT * FROM playlist_metadata WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): PlaylistMetadata?
}
