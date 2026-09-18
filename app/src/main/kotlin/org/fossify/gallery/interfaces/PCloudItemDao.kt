package org.fossify.gallery.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.gallery.models.PCloudItem

@Dao
interface PCloudItemDao {
    @Query("SELECT id, path, item_id, is_folder, content_hash, has_thumb, last_scanned_at FROM pcloud_items WHERE path = :path")
    fun getItem(path: String): PCloudItem?

    @Query("SELECT id, path, item_id, is_folder, content_hash, has_thumb, last_scanned_at FROM pcloud_items WHERE is_folder = 1")
    fun getFolders(): List<PCloudItem>

    @Query("SELECT COUNT(id) FROM pcloud_items WHERE is_folder = 0")
    fun getFileCount(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(items: List<PCloudItem>)

    @Query("DELETE FROM pcloud_items WHERE path = :path")
    fun deleteItemPath(path: String)

    @Query("DELETE FROM pcloud_items")
    fun deleteAll()
}
