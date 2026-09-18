package org.fossify.gallery.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.gallery.models.Favorite

@Dao
interface FavoritesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(favorite: Favorite)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(favorites: List<Favorite>)

    @Query("SELECT favorites.full_path FROM favorites INNER JOIN media ON favorites.full_path = media.full_path WHERE media.deleted_ts = 0")
    fun getValidFavoritePaths(): List<String>

    @Query("SELECT id FROM favorites WHERE full_path = :path COLLATE NOCASE")
    fun isFavorite(path: String): Boolean

    @Query("UPDATE OR REPLACE favorites SET filename = :newFilename, full_path = :newFullPath, parent_path = :newParentPath WHERE full_path = :oldPath COLLATE NOCASE")
    fun updateFavorite(newFilename: String, newFullPath: String, newParentPath: String, oldPath: String)

    // the favorites counterpart of MediumDao.updatePathsUnderFolder()
    @Query("UPDATE OR REPLACE favorites SET full_path = :newFolder || substr(full_path, length(:oldFolder) + 1), parent_path = :newFolder || substr(parent_path, length(:oldFolder) + 1) WHERE full_path LIKE :oldFolder || '/%'")
    fun updatePathsUnderFolder(oldFolder: String, newFolder: String)

    @Query("DELETE FROM favorites WHERE full_path = :path COLLATE NOCASE")
    fun deleteFavoritePath(path: String)

    @Query("DELETE FROM favorites")
    fun clearFavorites()
}
