package com.example.moodsync

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY epochMillis DESC")
    fun getAllHistory(): Flow<List<HistoryItem>>

    @Query("SELECT * FROM history WHERE songTitle LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR emotion LIKE '%' || :query || '%' ORDER BY epochMillis DESC")
    fun searchHistory(query: String): Flow<List<HistoryItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: HistoryItem)

    @Update
    suspend fun update(item: HistoryItem)

    @Query("SELECT * FROM history WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getBySessionId(sessionId: String): HistoryItem?

    @Query("SELECT songTitle, artist, duration, emotion, COUNT(*) as playCount FROM history WHERE wasPlayed = 1 GROUP BY songTitle, artist, emotion HAVING playCount >= 1 ORDER BY playCount DESC LIMIT 10")
    fun getMoodMixes(): Flow<List<MoodMixItem>>

    @Query("DELETE FROM history")
    suspend fun deleteAll()
}
