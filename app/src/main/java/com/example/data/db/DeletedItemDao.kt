package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.DeletedItemEntity

@Dao
interface DeletedItemDao {
    @Query("SELECT * FROM deleted_items ORDER BY deletedAt DESC")
    suspend fun getAllDeletedItemsDirect(): List<DeletedItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeletedItem(item: DeletedItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeletedItems(items: List<DeletedItemEntity>)

    @Query("DELETE FROM deleted_items WHERE itemKey = :itemKey")
    suspend fun deleteByKey(itemKey: String)

    @Query("DELETE FROM deleted_items WHERE itemType = :itemType AND LOWER(TRIM(title)) = :lowerTitle")
    suspend fun deleteByTypeAndLowerTitle(itemType: String, lowerTitle: String)

    @Query("DELETE FROM deleted_items WHERE deletedAt < :cutoffMillis")
    suspend fun pruneOlderThan(cutoffMillis: Long)

    @Query("DELETE FROM deleted_items")
    suspend fun deleteAllDeletedItems()
}
