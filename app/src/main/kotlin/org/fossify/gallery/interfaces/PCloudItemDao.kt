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

    // the diff sync gets ids from pCloud and has to find the paths for them; file and folder
    // ids are separate number spaces, so the kind is part of the key
    @Query("SELECT id, path, item_id, is_folder, content_hash, has_thumb, last_scanned_at FROM pcloud_items WHERE item_id = :itemId AND is_folder = :isFolder LIMIT 1")
    fun getItemByItemId(itemId: Long, isFolder: Boolean): PCloudItem?

    // a row for a folder seen but not listed: its id becomes known, a row already there
    // keeps its last scan time
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfMissing(items: List<PCloudItem>)

    @Query("SELECT COUNT(id) FROM pcloud_items WHERE is_folder = 0")
    fun getFileCount(): Long

    // the folder scanner uses this to spot the rows of a folder pCloud no longer has; the
    // prefix carries no LIKE wildcards of its own
    @Query("SELECT path FROM pcloud_items WHERE path LIKE :prefix || '%'")
    fun getPathsWithPrefix(prefix: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(items: List<PCloudItem>)

    // after a rename on pCloud: the row of the item itself and, for a folder, every row under
    // it get the new path in place of the old one. The ids and hashes stay, pCloud keeps them
    @Query("UPDATE OR REPLACE pcloud_items SET path = :newPath || substr(path, length(:oldPath) + 1) WHERE path = :oldPath OR path LIKE :oldPath || '/%'")
    fun updatePaths(oldPath: String, newPath: String)

    @Query("DELETE FROM pcloud_items WHERE path = :path")
    fun deleteItemPath(path: String)

    @Query("DELETE FROM pcloud_items")
    fun deleteAll()
}
